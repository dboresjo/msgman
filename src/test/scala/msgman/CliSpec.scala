package msgman

class CliSpec extends munit.FunSuite {

  test("parse defaults") {
    Cli.parse(Array("format")) match {
      case ParseResult.Success(config) =>
        assertEquals(config.command, Command.Format)
        assertEquals(config.master, "en")
        assertEquals(config.filePattern, "messages.$1")
        assertEquals(config.path, "conf")
        assertEquals(config.fix, false)
        assertEquals(config.strict, false)
      case other => fail(s"expected Success, got $other")
    }
  }

  test("parse recognises the verify command") {
    assertEquals(Cli.parse(Array("verify")), ParseResult.Success(Config(Command.Verify)))
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
    assertEquals(Cli.parse(Array("format", "--master", "cy")), ParseResult.Success(Config(Command.Format, master = "cy")))
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
      ParseResult.Success(Config(Command.Format, filePattern = "msg_$1.properties"))
    )
  }

  test("parse --file-pattern without a value fails") {
    assertEquals(Cli.parse(Array("format", "--file-pattern")), ParseResult.Failure("missing value for --file-pattern"))
  }

  test("parse --path") {
    assertEquals(Cli.parse(Array("format", "--path", "app/conf")), ParseResult.Success(Config(Command.Format, path = "app/conf")))
  }

  test("parse --path without a value fails") {
    assertEquals(Cli.parse(Array("format", "--path")), ParseResult.Failure("missing value for --path"))
  }

  test("parse --fix with format") {
    assertEquals(Cli.parse(Array("format", "--fix")), ParseResult.Success(Config(Command.Format, fix = true)))
  }

  test("parse --fix with verify fails") {
    assertEquals(Cli.parse(Array("verify", "--fix")), ParseResult.Failure("--fix is only valid with the format command"))
  }

  test("parse --strict with verify") {
    assertEquals(Cli.parse(Array("verify", "--strict")), ParseResult.Success(Config(Command.Verify, strict = true)))
  }

  test("parse --strict with format fails") {
    assertEquals(Cli.parse(Array("format", "--strict")), ParseResult.Failure("--strict is only valid with the verify command"))
  }

  test("parse --require with a single code") {
    assertEquals(Cli.parse(Array("verify", "--require", "cy")), ParseResult.Success(Config(Command.Verify, require = List("cy"))))
  }

  test("parse --require splits and trims a comma-separated list of codes") {
    assertEquals(
      Cli.parse(Array("verify", "--require", "cy, fr ,de")),
      ParseResult.Success(Config(Command.Verify, require = List("cy", "fr", "de")))
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
  }
}
