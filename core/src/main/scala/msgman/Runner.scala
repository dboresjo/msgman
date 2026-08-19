package msgman

import java.io.{File, PrintStream}

/** The exit codes msgman produces. */
object ExitCode:
  val Success = 0
  val Fatal = 1
  val UsageError = 2
  val TranslationFailure = 3

/** Ties the CLI parsing, file discovery, parsing and rendering together. Kept
  * separate from Main so it can be exercised in tests without going through
  * `System.exit`.
  */
object Runner:

  def run(
      args: Array[String],
      cwd: File,
      out: PrintStream,
      err: PrintStream,
      revision: String,
      providers: Map[String, Translator] = Map.empty,
      home: File = new File(System.getProperty("user.home")),
      etc: File = new File("/etc"),
      env: String => Option[String]
  ): Int =
    Cli.parse(args) match
      case ParseResult.Help =>
        out.print(Cli.usage)
        ExitCode.Success
      case ParseResult.Revision =>
        out.print(revision)
        ExitCode.Success
      case ParseResult.Failure(message) =>
        err.println(s"msgman: $message")
        err.print(Cli.usage)
        ExitCode.UsageError
      case ParseResult.Success(config) =>
        runCommand(config, cwd, out, err, providers, home, etc, env)

  private def runCommand(
      config: Options,
      cwd: File,
      out: PrintStream,
      err: PrintStream,
      providers: Map[String, Translator],
      home: File,
      etc: File,
      env: String => Option[String]
  ): Int =
    val resolved = resolve(config, cwd, home, etc)
    validate(resolved) match
      case Some(message) =>
        err.println(s"msgman: $message")
        ExitCode.Fatal
      case None =>
        val dir = new File(cwd, resolved.path)
        val discovered =
          try Right(FileDiscovery.discover(dir, resolved.filePattern))
          catch case e: InvalidFilePatternException => Left(e.message)

        discovered match
          case Left(message) =>
            err.println(s"msgman: $message")
            ExitCode.Fatal
          case Right(files) if files.isEmpty =>
            err.println(s"msgman: no messages files found matching '${resolved.filePattern}' in ${resolved.path}")
            ExitCode.Fatal
          case Right(files) if !files.exists(_.code == resolved.master) =>
            err.println(s"msgman: master language file for '${resolved.master}' not found in ${resolved.path}")
            ExitCode.Fatal
          case Right(files) if resolved.require.exists(code => !files.exists(_.code == code)) =>
            val missingCodes = resolved.require.filterNot(code => files.exists(_.code == code))
            err.println(s"msgman: required messages file(s) not found for: ${missingCodes.mkString(", ")}")
            ExitCode.Fatal
          case Right(files) =>
            analyze(files, resolved.priority) match
              case Left(errors) =>
                errors.foreach:
                  case (code, message) => err.println(s"msgman: $code: $message")
                ExitCode.Fatal
              case Right(analyses) =>
                config.command match
                  case Command.Format => runFormat(config, analyses, resolved, cwd, out, err, providers, home, etc, env)
                  case Command.Verify => runVerify(config, analyses, resolved, out, err)

  /** What each of `--master`, `--file-pattern`, `--path`, `--require` and
    * `--priority-keys` resolves to: the command-line switch if given,
    * otherwise the matching key in the merged `.msgman` configuration files
    * (see `Config.loadSettings`), otherwise the built-in default.
    */
  private final case class Resolved(master: String, filePattern: String, path: String, require: List[String], priority: List[String])

  private def resolve(config: Options, cwd: File, home: File, etc: File): Resolved =
    val settings = Config.loadSettings(cwd, home, etc)
    def csv(key: String): List[String] = settings.get(key).map(_.split(",").map(_.trim).filter(_.nonEmpty).toList).getOrElse(Nil)
    Resolved(
      master = config.master.orElse(settings.get("master")).getOrElse("en"),
      filePattern = config.filePattern.orElse(settings.get("file-pattern")).getOrElse("messages.$1"),
      path = config.path.orElse(settings.get("path")).getOrElse("conf"),
      require = if config.require.nonEmpty then config.require else csv("require"),
      priority = if config.priority.nonEmpty then config.priority else csv("priority-keys")
    )

  /** Validates settings that could have come from `.msgman` rather than the
    * command line, where `Cli`'s own parse-time validation doesn't apply. A
    * value supplied on the command line is already known valid by this
    * point, so this only ever rejects something newly picked up from
    * `.msgman`.
    */
  private def validate(resolved: Resolved): Option[String] =
    if !Cli.isIsoCode(resolved.master) then Some(s"master must be a 2-letter ISO country code: ${resolved.master}")
    else if !resolved.require.forall(Cli.isIsoCode) then
      Some(s"require codes must be 2-letter ISO country codes: ${resolved.require.filterNot(Cli.isIsoCode).mkString(", ")}")
    else None

  private final case class FileAnalysis(code: String, file: File, raw: String, parsed: MessagesFile, priority: List[String]):
    def canonical: String = MessagesFile.render(parsed, priority)
    def isCanonical: Boolean = raw == canonical

  private def analyze(files: List[LanguageFile], priority: List[String]): Either[List[(String, String)], List[FileAnalysis]] =
    val results = files.map: lf =>
      val raw = readFile(lf.file)
      try Right(FileAnalysis(lf.code, lf.file, raw, MessagesFile.parse(raw), priority))
      catch case e: MessagesFileParseException => Left(lf.code -> e.message)
    val errors = results.collect { case Left(e) => e }
    if errors.nonEmpty then Left(errors) else Right(results.collect { case Right(a) => a })

  /** Resolves what `--translate` needs before any AI call is made: the
    * configured provider must be linked into this build, an API key must be
    * available for it, and a model must be known (either from `--model` or
    * the configuration file). All of this is checked once, up front, rather
    * than discovered by exhausting the block/per-key retry loop with the same
    * unrecoverable failure: none of these are the kind of thing that could
    * succeed on a retry. `Right(None)` means `--translate` was not requested
    * at all.
    */
  private def resolveTranslation(
      config: Options,
      cwd: File,
      providers: Map[String, Translator],
      home: File,
      etc: File,
      env: String => Option[String]
  ): Either[String, Option[(Config, Translator, String)]] =
    if !config.translate then Right(None)
    else if providers.isEmpty then
      Left("this binary was built without --translate support; rebuild with --with-ai <provider> to link one in")
    else
      val aiConfig = Config.load(cwd, home, etc)
      // With exactly one provider linked in, that's the only sensible choice, so
      // .msgman doesn't need to say which one to use. With more than one linked
      // in, the choice is ambiguous and .msgman must select one explicitly.
      val selectedProvider: Either[String, String] = aiConfig.provider match
        case Some(p)                     => Right(p)
        case None if providers.size == 1 => Right(providers.keys.head)
        case None =>
          val linked = providers.keys.toList.sorted.mkString(", ")
          Left(s"--translate requires a provider to be selected, set 'provider' in .msgman (linked: $linked)")
      selectedProvider.flatMap: provider =>
        providers.get(provider) match
          case None =>
            val linked = providers.keys.toList.sorted.mkString(", ")
            Left(s"AI provider '$provider' is configured but not linked into this build (linked: $linked)")
          case Some(translator) =>
            Config.resolveApiKey(provider, aiConfig, env) match
              case None =>
                Left(s"no API key configured for provider '$provider'; set ${Config.apiKeyEnvVar(provider)} or $provider.fallback-key in .msgman")
              case Some(_) =>
                config.model.orElse(aiConfig.model.get(provider)) match
                  case None        => Left(s"no AI model configured for provider '$provider'; set $provider.model in .msgman or pass --model")
                  case Some(model) => Right(Some((aiConfig, translator, model)))

  private def runFormat(
      config: Options,
      analyses: List[FileAnalysis],
      resolved: Resolved,
      cwd: File,
      out: PrintStream,
      err: PrintStream,
      providers: Map[String, Translator],
      home: File,
      etc: File,
      env: String => Option[String]
  ): Int =
    val conflicts = analyses.sortBy(_.code).flatMap: a =>
      MessagesFile.duplicates(a.parsed).filter(_.isConflicting).map(a.code -> _)
    if conflicts.nonEmpty then
      conflicts.foreach:
        case (code, group) =>
          err.println(s"msgman: $code: duplicate key '${group.key}' has conflicting values: ${group.values.mkString(", ")}")
      return ExitCode.Fatal

    resolveTranslation(config, cwd, providers, home, etc, env) match
      case Left(message) =>
        err.println(s"msgman: $message")
        ExitCode.Fatal
      case Right(translation) =>
        val deduped = analyses.map(a => a.copy(parsed = MessagesFile.dedupe(a.parsed)))

        val master = deduped.find(_.code == resolved.master).get
        val others = deduped.filterNot(_.code == resolved.master)

        val log: String => Unit = if config.verbose then (msg: String) => out.println(s"msgman: $msg") else _ => ()

        translateAll(cwd, translation, log, master, others, resolved.master) match
          case Left(fatalReason) =>
            err.println(s"msgman: $fatalReason")
            ExitCode.Fatal
          case Right(translationResults) =>
            finishFormat(config, deduped, master, others, translationResults, resolved.priority, out, err)

  /** Runs `AiTranslate.translate` for every non-master file, in order,
    * stopping at the first fatal failure (see `TranslateResult`) rather than
    * attempting the rest with a translator/model/key combination already
    * known not to work. `Right(Map.empty)` when `translation` is `None`,
    * `--translate` was not requested.
    */
  private def translateAll(
      cwd: File,
      translation: Option[(Config, Translator, String)],
      log: String => Unit,
      master: FileAnalysis,
      others: List[FileAnalysis],
      masterCode: String
  ): Either[String, Map[String, (List[Entry], List[(String, String)])]] =
    translation match
      case None => Right(Map.empty)
      case Some((aiConfig, translator, model)) =>
        others.foldLeft(Right(Map.empty): Either[String, Map[String, (List[Entry], List[(String, String)])]]): (acc, analysis) =>
          acc match
            case Left(_) => acc
            case Right(resultsSoFar) =>
              val result =
                AiTranslate.translate(cwd, aiConfig, translator, model, aiConfig.stealth, master.parsed, analysis.parsed, masterCode, analysis.code, log)
              result.fatal match
                case Some(reason) => Left(reason)
                case None         => Right(resultsSoFar + (analysis.code -> (result.entries, result.stillMissing)))

  private def finishFormat(
      config: Options,
      deduped: List[FileAnalysis],
      master: FileAnalysis,
      others: List[FileAnalysis],
      translationResults: Map[String, (List[Entry], List[(String, String)])],
      priority: List[String],
      out: PrintStream,
      err: PrintStream
  ): Int =
    val masterValues = master.parsed.entries.map(e => e.key -> e.value).toMap
    val othersParsed = others.map(a => a.code -> a.parsed).toMap

    val missing = Translations.findMissing(master.parsed, othersParsed, strict = false)
    missing.foreach(m => out.println(s"msgman: missing translation [${m.languageCode}] ${m.key}"))

    val extra = Translations.findExtra(master.parsed, othersParsed)
    extra.foreach(e => out.println(s"msgman: removed translation [${e.languageCode}] ${e.key}"))

    val missingByCode = missing.groupBy(_.languageCode)
    val extraKeysByCode = extra.groupBy(_.languageCode).view.mapValues(_.map(_.key).toSet).toMap

    translationResults.toList.sortBy(_._1).foreach:
      case (code, (_, stillMissing)) =>
        stillMissing.sortBy(_._1)(Key.ordering).foreach:
          case (key, reason) => err.println(s"msgman: translation failed [$code] $key: $reason")

    deduped.foreach: analysis =>
      val extraKeys = extraKeysByCode.getOrElse(analysis.code, Set.empty[String])
      val keptEntries = analysis.parsed.entries.filterNot(e => extraKeys.contains(e.key))
      val newEntries =
        if config.fix then missingByCode.getOrElse(analysis.code, Nil).map(m => Entry(m.key, s"${analysis.code}: ${masterValues(m.key)}"))
        else translationResults.get(analysis.code).map(_._1).getOrElse(Nil)
      val updated = analysis.parsed.copy(entries = keptEntries ++ newEntries)
      val rendered = MessagesFile.render(updated, priority)
      if rendered != analysis.raw then writeFile(analysis.file, rendered)

    if translationResults.values.exists(_._2.nonEmpty) then ExitCode.TranslationFailure else ExitCode.Success

  private def runVerify(config: Options, analyses: List[FileAnalysis], resolved: Resolved, out: PrintStream, err: PrintStream): Int =
    val nonCanonical = analyses.filterNot(_.isCanonical).sortBy(_.code)
    nonCanonical.foreach(a => err.println(s"msgman: ${a.code} is not in canonical format"))

    val duplicates = analyses.sortBy(_.code).flatMap(a => MessagesFile.duplicates(a.parsed).map(a.code -> _))
    duplicates.foreach:
      case (code, group) => out.println(s"msgman: duplicate key [$code] ${group.key}")

    val master = analyses.find(_.code == resolved.master).get
    val others = analyses.filterNot(_.code == resolved.master)
    val othersParsed = others.map(a => a.code -> a.parsed).toMap

    val missing = Translations.findMissing(master.parsed, othersParsed, config.strict)
    missing.foreach(m => err.println(s"msgman: missing translation [${m.languageCode}] ${m.key}"))

    val extra = Translations.findExtra(master.parsed, othersParsed)
    extra.foreach(e => out.println(s"msgman: extra translation [${e.languageCode}] ${e.key}"))

    if nonCanonical.nonEmpty || missing.nonEmpty || duplicates.nonEmpty then ExitCode.Fatal else ExitCode.Success

  private def readFile(file: File): String =
    val source = scala.io.Source.fromFile(file, "UTF-8")
    try source.mkString
    finally source.close()

  private def writeFile(file: File, content: String): Unit =
    val writer = new java.io.PrintWriter(file, "UTF-8")
    try writer.print(content)
    finally writer.close()
