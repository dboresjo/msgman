package msgman

class CliSpec extends munit.FunSuite {

  test("parse defaults") {
    Cli.parse(Array("format")) match {
      case ParseResult.Success(config) =>
        assertEquals(config.command, Command.Format)
        assertEquals(config.master, None)
        assertEquals(config.filePattern, None)
        assertEquals(config.path, None)
        assertEquals(config.fix, false)
        assertEquals(config.strict, false)
      case other => fail(s"expected Success, got $other")
    }

  }
  test("parse recognises the verify command") {
    assertEquals(Cli.parse(Array("verify")), ParseResult.Success(Options(Command.Verify)))

  }
  test("parse an unknown command fails") {
    assertEquals(Cli.parse(Array("bogus")), ParseResult.Failure("unknown command: bogus"))

  }
  test("parse with no command fails") {
    assertEquals(Cli.parse(Array()), ParseResult.Failure("expected a command: format or verify"))

  }
  test("parse with two commands fails") {
    assertEquals(Cli.parse(Array("format", "verify")), ParseResult.Failure("expected exactly one command: format or verify"))

  }
  test("parse --master") {
    assertEquals(Cli.parse(Array("format", "--master", "cy")), ParseResult.Success(Options(Command.Format, master = Some("cy"))))

  }
  test("parse --master without a value fails") {
    assertEquals(Cli.parse(Array("format", "--master")), ParseResult.Failure("missing value for --master"))

  }
  test("parse --master rejects a code that is not a 2-letter ISO code") {
    assertEquals(
      Cli.parse(Array("format", "--master", "eng")),
      ParseResult.Failure("--master must be a 2-letter ISO country code: eng")
    )

  }
  test("parse --file-pattern") {
    assertEquals(
      Cli.parse(Array("format", "--file-pattern", "msg_$1.properties")),
      ParseResult.Success(Options(Command.Format, filePattern = Some("msg_$1.properties")))
    )

  }
  test("parse --file-pattern without a value fails") {
    assertEquals(Cli.parse(Array("format", "--file-pattern")), ParseResult.Failure("missing value for --file-pattern"))

  }
  test("parse --path") {
    assertEquals(Cli.parse(Array("format", "--path", "app/conf")), ParseResult.Success(Options(Command.Format, path = Some("app/conf"))))

  }
  test("parse --path without a value fails") {
    assertEquals(Cli.parse(Array("format", "--path")), ParseResult.Failure("missing value for --path"))

  }
  test("parse --fix with format") {
    assertEquals(Cli.parse(Array("format", "--fix")), ParseResult.Success(Options(Command.Format, fix = true)))

  }
  test("parse --fix with verify fails") {
    assertEquals(Cli.parse(Array("verify", "--fix")), ParseResult.Failure("--fix is only valid with the format command"))

  }
  test("parse --strict with verify") {
    assertEquals(Cli.parse(Array("verify", "--strict")), ParseResult.Success(Options(Command.Verify, strict = true)))

  }
  test("parse --strict with format fails") {
    assertEquals(Cli.parse(Array("format", "--strict")), ParseResult.Failure("--strict is only valid with the verify command"))

  }
  test("parse --require with a single code") {
    assertEquals(Cli.parse(Array("verify", "--require", "cy")), ParseResult.Success(Options(Command.Verify, require = List("cy"))))

  }
  test("parse --require splits and trims a comma-separated list of codes") {
    assertEquals(
      Cli.parse(Array("verify", "--require", "cy, fr ,de")),
      ParseResult.Success(Options(Command.Verify, require = List("cy", "fr", "de")))
    )

  }
  test("parse --require without a value fails") {
    assertEquals(Cli.parse(Array("verify", "--require")), ParseResult.Failure("missing value for --require"))

  }
  test("parse --require with an empty value fails") {
    assertEquals(Cli.parse(Array("verify", "--require", " , ,")), ParseResult.Failure("--require requires at least one country code"))

  }
  test("parse --require rejects a code that is not a 2-letter ISO code") {
    assertEquals(
      Cli.parse(Array("verify", "--require", "cy,xyz")),
      ParseResult.Failure("--require codes must be 2-letter ISO country codes: xyz")
    )

  }
  test("parse --priority-keys with a single key") {
    assertEquals(Cli.parse(Array("format", "--priority-keys", "site")), ParseResult.Success(Options(Command.Format, priority = List("site"))))

  }
  test("parse --priority-keys splits and trims a comma-separated list of keys, preserving order") {
    assertEquals(
      Cli.parse(Array("format", "--priority-keys", "phase, site ,date")),
      ParseResult.Success(Options(Command.Format, priority = List("phase", "site", "date")))
    )

  }
  test("parse --priority-keys without a value fails") {
    assertEquals(Cli.parse(Array("format", "--priority-keys")), ParseResult.Failure("missing value for --priority-keys"))

  }
  test("parse --priority-keys with an empty value fails") {
    assertEquals(Cli.parse(Array("format", "--priority-keys", " , ,")), ParseResult.Failure("--priority-keys requires at least one key"))

  }
  test("parse --priority-keys with verify") {
    assertEquals(Cli.parse(Array("verify", "--priority-keys", "site")), ParseResult.Success(Options(Command.Verify, priority = List("site"))))

  }
  test("parse --translate with format") {
    assertEquals(Cli.parse(Array("format", "--translate")), ParseResult.Success(Options(Command.Format, translate = true)))

  }
  test("parse --translate with verify fails") {
    assertEquals(Cli.parse(Array("verify", "--translate")), ParseResult.Failure("--translate is only valid with the format command"))

  }
  test("parse --translate together with --fix fails") {
    assertEquals(
      Cli.parse(Array("format", "--fix", "--translate")),
      ParseResult.Failure("--translate cannot be used together with --fix")
    )

  }
  test("parse --model with --translate") {
    assertEquals(
      Cli.parse(Array("format", "--translate", "--model", "claude-sonnet-5")),
      ParseResult.Success(Options(Command.Format, translate = true, model = Some("claude-sonnet-5")))
    )

  }
  test("parse --model without --translate fails") {
    assertEquals(
      Cli.parse(Array("format", "--model", "claude-sonnet-5")),
      ParseResult.Failure("--model is only valid with --translate")
    )

  }
  test("parse --model without a value fails") {
    assertEquals(Cli.parse(Array("format", "--translate", "--model")), ParseResult.Failure("missing value for --model"))

  }
  test("parse --verbose with --translate") {
    assertEquals(
      Cli.parse(Array("format", "--translate", "--verbose")),
      ParseResult.Success(Options(Command.Format, translate = true, verbose = true))
    )

  }
  test("parse --verbose without --translate fails") {
    assertEquals(Cli.parse(Array("format", "--verbose")), ParseResult.Failure("--verbose is only valid with --translate"))

  }
  test("parse an unknown option fails") {
    assertEquals(Cli.parse(Array("format", "--bogus")), ParseResult.Failure("unknown option: --bogus"))

  }
  test("parse --help short-circuits everything else") {
    assertEquals(Cli.parse(Array("--help")), ParseResult.Help)
    assertEquals(Cli.parse(Array("format", "--bogus", "--help")), ParseResult.Help)
    assertEquals(Cli.parse(Array("-h")), ParseResult.Help)

  }
  test("usage text mentions both commands") {
    assert(Cli.usage.contains("format"))
    assert(Cli.usage.contains("verify"))
    assert(Cli.usage.contains("--require"))
    assert(Cli.usage.contains("--priority-keys"))
    assert(Cli.usage.contains("--translate"))
    assert(Cli.usage.contains("--model"))
    assert(Cli.usage.contains("--verbose"))

  }
  test("parse --revision short-circuits everything else") {
    assertEquals(Cli.parse(Array("--revision")), ParseResult.Revision)
    assertEquals(Cli.parse(Array("format", "--bogus", "--revision")), ParseResult.Revision)

  }
  test("parse --help takes priority over --revision") {
    assertEquals(Cli.parse(Array("--help", "--revision")), ParseResult.Help)
  }
}
