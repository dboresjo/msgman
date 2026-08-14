package msgman

enum Command:
  case Format, Verify

final case class Config(
    command: Command,
    master: String = "en",
    filePattern: String = "messages.$1",
    path: String = "conf",
    fix: Boolean = false,
    strict: Boolean = false,
    require: List[String] = Nil,
    translate: Boolean = false,
    model: Option[String] = None,
    verbose: Boolean = false
)

enum ParseResult:
  case Success(config: Config)
  case Help
  case Revision
  case Failure(message: String)

object Cli:

  val usage: String =
    """msgman - manage the canonical order of Scala messages files
      |
      |Usage: msgman <format|verify> [options]
      |
      |Commands:
      |  format    Rewrite messages files in-place into canonical order
      |  verify    Check messages files are already in canonical order (no files changed)
      |
      |Options:
      |  --master <code>        Country code for the master language, an ISO 2-letter code (default: en)
      |  --file-pattern <pat>   Filename pattern for messages files, $1 is the language code (default: messages.$1)
      |  --path <dir>           Directory containing messages files, relative to the current dir (default: conf)
      |  --fix                  format only: add missing translations, prefixed with the target language code
      |  --translate            format only: generate missing translations using an AI service (cannot combine with --fix)
      |  --model <id>           Override the configured AI model, only valid with --translate
      |  --verbose              Print each translation request and response as it happens, only valid with --translate
      |  --strict               verify only: treat language-code-prefixed placeholder values as missing
      |  --require <codes>      Require a messages file to exist for each comma-separated ISO 2-letter code
      |  --help                 Show this help message
      |  --revision             Show the GitHub URL of the revision this binary was built from
      |""".stripMargin

  private val isoCode = "^[A-Za-z]{2}$".r

  private def isIsoCode(code: String): Boolean = isoCode.pattern.matcher(code).matches()

  def parse(args: Array[String]): ParseResult =
    if args.contains("--help") || args.contains("-h") then ParseResult.Help
    else if args.contains("--revision") then ParseResult.Revision
    else parseArgs(args)

  private def parseArgs(args: Array[String]): ParseResult =
    val positional = scala.collection.mutable.ListBuffer.empty[String]
    var master = "en"
    var filePattern = "messages.$1"
    var path = "conf"
    var fix = false
    var strict = false
    var require = List.empty[String]
    var translate = false
    var model: Option[String] = None
    var verbose = false
    var error: Option[String] = None

    var i = 0
    while i < args.length && error.isEmpty do
      args(i) match
        case "--master" =>
          takeValue(args, i, "--master") match
            case Left(e)          => error = Some(e)
            case Right((v, next)) => master = v; i = next
        case "--file-pattern" =>
          takeValue(args, i, "--file-pattern") match
            case Left(e)          => error = Some(e)
            case Right((v, next)) => filePattern = v; i = next
        case "--path" =>
          takeValue(args, i, "--path") match
            case Left(e)          => error = Some(e)
            case Right((v, next)) => path = v; i = next
        case "--require" =>
          takeValue(args, i, "--require") match
            case Left(e) => error = Some(e)
            case Right((v, next)) =>
              val codes = v.split(",").map(_.trim).filter(_.nonEmpty).toList
              if codes.isEmpty then error = Some("--require requires at least one country code")
              else
                require = codes
                i = next
        case "--model" =>
          takeValue(args, i, "--model") match
            case Left(e)          => error = Some(e)
            case Right((v, next)) => model = Some(v); i = next
        case "--fix"                  => fix = true
        case "--translate"            => translate = true
        case "--verbose"              => verbose = true
        case "--strict"               => strict = true
        case s if s.startsWith("--")  => error = Some(s"unknown option: $s")
        case other                    => positional += other
      i += 1

    error match
      case Some(e) => ParseResult.Failure(e)
      case None    => finish(positional.toList, master, filePattern, path, fix, strict, require, translate, model, verbose)

  private def takeValue(args: Array[String], i: Int, flag: String): Either[String, (String, Int)] =
    if i + 1 >= args.length then Left(s"missing value for $flag") else Right((args(i + 1), i + 1))

  private def finish(
      positional: List[String],
      master: String,
      filePattern: String,
      path: String,
      fix: Boolean,
      strict: Boolean,
      require: List[String],
      translate: Boolean,
      model: Option[String],
      verbose: Boolean
  ): ParseResult =
    if !isIsoCode(master) then
      ParseResult.Failure(s"--master must be a 2-letter ISO country code: $master")
    else if !require.forall(isIsoCode) then
      ParseResult.Failure(s"--require codes must be 2-letter ISO country codes: ${require.filterNot(isIsoCode).mkString(", ")}")
    else
      positional match
        case command :: Nil =>
          command match
            case "format" if strict           => ParseResult.Failure("--strict is only valid with the verify command")
            case "verify" if fix               => ParseResult.Failure("--fix is only valid with the format command")
            case "verify" if translate         => ParseResult.Failure("--translate is only valid with the format command")
            case "format" if fix && translate  => ParseResult.Failure("--translate cannot be used together with --fix")
            case _ if model.isDefined && !translate   => ParseResult.Failure("--model is only valid with --translate")
            case _ if verbose && !translate           => ParseResult.Failure("--verbose is only valid with --translate")
            case "format" => ParseResult.Success(Config(Command.Format, master, filePattern, path, fix, strict, require, translate, model, verbose))
            case "verify" => ParseResult.Success(Config(Command.Verify, master, filePattern, path, fix, strict, require, translate, model, verbose))
            case other    => ParseResult.Failure(s"unknown command: $other")
        case Nil => ParseResult.Failure("expected a command: format or verify")
        case _   => ParseResult.Failure("expected exactly one command: format or verify")
