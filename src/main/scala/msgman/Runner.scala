package msgman

import java.io.{File, PrintStream}

/** The exit codes msgman produces. */
object ExitCode {
  val Success = 0
  val Fatal = 1
  val UsageError = 2
}

/** Ties the CLI parsing, file discovery, parsing and rendering together. Kept
  * separate from Main so it can be exercised in tests without going through
  * `System.exit`.
  */
object Runner {

  def run(args: Array[String], cwd: File, out: PrintStream, err: PrintStream): Int =
    Cli.parse(args) match {
      case ParseResult.Help =>
        out.print(Cli.usage)
        ExitCode.Success
      case ParseResult.Failure(message) =>
        err.println(s"msgman: $message")
        err.print(Cli.usage)
        ExitCode.UsageError
      case ParseResult.Success(config) =>
        runCommand(config, cwd, out, err)
    }

  private def runCommand(config: Config, cwd: File, out: PrintStream, err: PrintStream): Int = {
    val dir = new File(cwd, config.path)
    val discovered =
      try Right(FileDiscovery.discover(dir, config.filePattern))
      catch { case e: InvalidFilePatternException => Left(e.message) }

    discovered match {
      case Left(message) =>
        err.println(s"msgman: $message")
        ExitCode.Fatal
      case Right(files) if files.isEmpty =>
        err.println(s"msgman: no messages files found matching '${config.filePattern}' in ${config.path}")
        ExitCode.Fatal
      case Right(files) if !files.exists(_.code == config.master) =>
        err.println(s"msgman: master language file for '${config.master}' not found in ${config.path}")
        ExitCode.Fatal
      case Right(files) if config.require.exists(code => !files.exists(_.code == code)) =>
        val missingCodes = config.require.filterNot(code => files.exists(_.code == code))
        err.println(s"msgman: required messages file(s) not found for: ${missingCodes.mkString(", ")}")
        ExitCode.Fatal
      case Right(files) =>
        analyze(files) match {
          case Left(errors) =>
            errors.foreach { case (code, message) => err.println(s"msgman: $code: $message") }
            ExitCode.Fatal
          case Right(analyses) =>
            config.command match {
              case Command.Format => runFormat(config, analyses, out, err)
              case Command.Verify => runVerify(config, analyses, out, err)
            }
        }
    }
  }

  private final case class FileAnalysis(code: String, file: File, raw: String, parsed: MessagesFile) {
    def canonical: String = MessagesFile.render(parsed)
    def isCanonical: Boolean = raw == canonical
  }

  private def analyze(files: List[LanguageFile]): Either[List[(String, String)], List[FileAnalysis]] = {
    val results = files.map { lf =>
      val raw = readFile(lf.file)
      try Right(FileAnalysis(lf.code, lf.file, raw, MessagesFile.parse(raw)))
      catch { case e: MessagesFileParseException => Left(lf.code -> e.message) }
    }
    val errors = results.collect { case Left(e) => e }
    if (errors.nonEmpty) Left(errors) else Right(results.collect { case Right(a) => a })
  }

  private def runFormat(config: Config, analyses: List[FileAnalysis], out: PrintStream, err: PrintStream): Int = {
    val conflicts = analyses.sortBy(_.code).flatMap { a =>
      MessagesFile.duplicates(a.parsed).filter(_.isConflicting).map(a.code -> _)
    }
    if (conflicts.nonEmpty) {
      conflicts.foreach { case (code, group) =>
        err.println(s"msgman: $code: duplicate key '${group.key}' has conflicting values: ${group.values.mkString(", ")}")
      }
      return ExitCode.Fatal
    }

    val deduped = analyses.map(a => a.copy(parsed = MessagesFile.dedupe(a.parsed)))

    val master = deduped.find(_.code == config.master).get
    val masterValues = master.parsed.entries.map(e => e.key -> e.value).toMap
    val others = deduped.filterNot(_.code == config.master)
    val othersParsed = others.map(a => a.code -> a.parsed).toMap

    val missing = Translations.findMissing(master.parsed, othersParsed, strict = false)
    missing.foreach { m => out.println(s"msgman: missing translation [${m.languageCode}] ${m.key}") }

    val extra = Translations.findExtra(master.parsed, othersParsed)
    extra.foreach { e => out.println(s"msgman: removed translation [${e.languageCode}] ${e.key}") }

    val missingByCode = missing.groupBy(_.languageCode)
    val extraKeysByCode = extra.groupBy(_.languageCode).view.mapValues(_.map(_.key).toSet).toMap

    deduped.foreach { analysis =>
      val extraKeys = extraKeysByCode.getOrElse(analysis.code, Set.empty[String])
      val keptEntries = analysis.parsed.entries.filterNot(e => extraKeys.contains(e.key))
      val newEntries =
        if (config.fix) missingByCode.getOrElse(analysis.code, Nil).map(m => Entry(m.key, s"${analysis.code}: ${masterValues(m.key)}"))
        else Nil
      val updated = analysis.parsed.copy(entries = keptEntries ++ newEntries)
      val rendered = MessagesFile.render(updated)
      if (rendered != analysis.raw) writeFile(analysis.file, rendered)
    }

    ExitCode.Success
  }

  private def runVerify(config: Config, analyses: List[FileAnalysis], out: PrintStream, err: PrintStream): Int = {
    val nonCanonical = analyses.filterNot(_.isCanonical).sortBy(_.code)
    nonCanonical.foreach(a => err.println(s"msgman: ${a.code} is not in canonical format"))

    val duplicates = analyses.sortBy(_.code).flatMap(a => MessagesFile.duplicates(a.parsed).map(a.code -> _))
    duplicates.foreach { case (code, group) => out.println(s"msgman: duplicate key [$code] ${group.key}") }

    val master = analyses.find(_.code == config.master).get
    val others = analyses.filterNot(_.code == config.master)
    val othersParsed = others.map(a => a.code -> a.parsed).toMap

    val missing = Translations.findMissing(master.parsed, othersParsed, config.strict)
    missing.foreach { m => err.println(s"msgman: missing translation [${m.languageCode}] ${m.key}") }

    val extra = Translations.findExtra(master.parsed, othersParsed)
    extra.foreach { e => out.println(s"msgman: extra translation [${e.languageCode}] ${e.key}") }

    if (nonCanonical.nonEmpty || missing.nonEmpty || duplicates.nonEmpty) ExitCode.Fatal else ExitCode.Success
  }

  private def readFile(file: File): String = {
    val source = scala.io.Source.fromFile(file, "UTF-8")
    try source.mkString
    finally source.close()
  }

  private def writeFile(file: File, content: String): Unit = {
    val writer = new java.io.PrintWriter(file, "UTF-8")
    try writer.print(content)
    finally writer.close()
  }
}
