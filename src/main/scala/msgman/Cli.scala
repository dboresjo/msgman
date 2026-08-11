package msgman

sealed trait Command
object Command {
  case object Format extends Command
  case object Verify extends Command
}

final case class Config(
    command: Command,
    master: String = "en",
    filePattern: String = "messages.$1",
    path: String = "conf",
    fix: Boolean = false,
    strict: Boolean = false,
    require: List[String] = Nil
)

sealed trait ParseResult
object ParseResult {
  final case class Success(config: Config) extends ParseResult
  case object Help extends ParseResult
  final case class Failure(message: String) extends ParseResult
}

object Cli {

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
      |  --strict               verify only: treat language-code-prefixed placeholder values as missing
      |  --require <codes>      Require a messages file to exist for each comma-separated ISO 2-letter code
      |  --help                 Show this help message
      |""".stripMargin

  private val isoCode = "^[A-Za-z]{2}$".r

  private def isIsoCode(code: String): Boolean = isoCode.pattern.matcher(code).matches()

  def parse(args: Array[String]): ParseResult = {
    if (args.contains("--help") || args.contains("-h")) {
      ParseResult.Help
    } else {
      parseArgs(args)
    }
  }

  private def parseArgs(args: Array[String]): ParseResult = {
    val positional = scala.collection.mutable.ListBuffer.empty[String]
    var master = "en"
    var filePattern = "messages.$1"
    var path = "conf"
    var fix = false
    var strict = false
    var require = List.empty[String]
    var error: Option[String] = None

    var i = 0
    while (i < args.length && error.isEmpty) {
      args(i) match {
        case "--master" =>
          takeValue(args, i, "--master") match {
            case Left(e)          => error = Some(e)
            case Right((v, next)) => master = v; i = next
          }
        case "--file-pattern" =>
          takeValue(args, i, "--file-pattern") match {
            case Left(e)          => error = Some(e)
            case Right((v, next)) => filePattern = v; i = next
          }
        case "--path" =>
          takeValue(args, i, "--path") match {
            case Left(e)          => error = Some(e)
            case Right((v, next)) => path = v; i = next
          }
        case "--require" =>
          takeValue(args, i, "--require") match {
            case Left(e) => error = Some(e)
            case Right((v, next)) =>
              val codes = v.split(",").map(_.trim).filter(_.nonEmpty).toList
              if (codes.isEmpty) error = Some("--require requires at least one country code")
              else { require = codes; i = next }
          }
        case "--fix"    => fix = true
        case "--strict" => strict = true
        case s if s.startsWith("--") => error = Some(s"unknown option: $s")
        case other => positional += other
      }
      i += 1
    }

    error match {
      case Some(e) => ParseResult.Failure(e)
      case None    => finish(positional.toList, master, filePattern, path, fix, strict, require)
    }
  }

  private def takeValue(args: Array[String], i: Int, flag: String): Either[String, (String, Int)] =
    if (i + 1 >= args.length) Left(s"missing value for $flag") else Right((args(i + 1), i + 1))

  private def finish(
      positional: List[String],
      master: String,
      filePattern: String,
      path: String,
      fix: Boolean,
      strict: Boolean,
      require: List[String]
  ): ParseResult = {
    if (!isIsoCode(master)) {
      ParseResult.Failure(s"--master must be a 2-letter ISO country code: $master")
    } else if (!require.forall(isIsoCode)) {
      ParseResult.Failure(s"--require codes must be 2-letter ISO country codes: ${require.filterNot(isIsoCode).mkString(", ")}")
    } else {
      positional match {
        case command :: Nil =>
          command match {
            case "format" if strict => ParseResult.Failure("--strict is only valid with the verify command")
            case "verify" if fix    => ParseResult.Failure("--fix is only valid with the format command")
            case "format" => ParseResult.Success(Config(Command.Format, master, filePattern, path, fix, strict, require))
            case "verify" => ParseResult.Success(Config(Command.Verify, master, filePattern, path, fix, strict, require))
            case other    => ParseResult.Failure(s"unknown command: $other")
          }
        case Nil => ParseResult.Failure("expected a command: format or verify")
        case _   => ParseResult.Failure("expected exactly one command: format or verify")
      }
    }
  }
}
