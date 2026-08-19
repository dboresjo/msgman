package msgman

import java.io.{ByteArrayOutputStream, File, PrintStream}
import java.nio.file.Files

private class FakeTranslator(behaviour: PartialFunction[Set[String], TranslationOutcome]) extends Translator:
  val requests: scala.collection.mutable.ListBuffer[TranslationRequest] = scala.collection.mutable.ListBuffer.empty
  def translateBlock(request: TranslationRequest): TranslationOutcome =
    requests += request
    behaviour.applyOrElse(request.targets.map(_.subKey).toSet, (_: Set[String]) => TranslationOutcome.Failure("unhandled"))

class RunnerSpec extends munit.FunSuite:

  private def tempCwd(): File = Files.createTempDirectory("msgman-runner").toFile
  private def tempHome(): File = Files.createTempDirectory("msgman-runner-home").toFile
  private def tempEtc(): File = Files.createTempDirectory("msgman-runner-etc").toFile

  private def confDir(cwd: File): File =
    val dir = new File(cwd, "conf")
    dir.mkdirs()
    dir

  private def write(dir: File, name: String, content: String): File =
    val f = new File(dir, name)
    val w = new java.io.PrintWriter(f, "UTF-8")
    try w.print(content) finally w.close()
    f

  private def read(file: File): String =
    val s = scala.io.Source.fromFile(file, "UTF-8")
    try s.mkString finally s.close()

  private case class Result(exitCode: Int, out: String, err: String)

  private val testRevision = "https://github.com/dboresjo/msgman/tree/testsha\n"

  private def runIn(cwd: File, args: String*): Result =
    val outBytes = new ByteArrayOutputStream()
    val errBytes = new ByteArrayOutputStream()
    val out = new PrintStream(outBytes, true, "UTF-8")
    val err = new PrintStream(errBytes, true, "UTF-8")
    val code = Runner.run(args.toArray, cwd, out, err, testRevision, env = _ => None)
    Result(code, outBytes.toString("UTF-8"), errBytes.toString("UTF-8"))

  // Defaults to "every provider has a key", so tests that aren't specifically
  // about API key resolution don't need to think about it.
  private def runWithAi(
      cwd: File,
      home: File,
      etc: File,
      providers: Map[String, Translator],
      args: String*
  )(env: String => Option[String] = _ => Some("test-key")): Result =
    val outBytes = new ByteArrayOutputStream()
    val errBytes = new ByteArrayOutputStream()
    val out = new PrintStream(outBytes, true, "UTF-8")
    val err = new PrintStream(errBytes, true, "UTF-8")
    val code = Runner.run(args.toArray, cwd, out, err, testRevision, providers, home, etc, env)
    Result(code, outBytes.toString("UTF-8"), errBytes.toString("UTF-8"))

  test("--help prints usage and exits successfully"):
    val cwd = tempCwd()
    val result = runIn(cwd, "--help")
    assertEquals(result.exitCode, ExitCode.Success)
    assert(result.out.contains("Usage: msgman"))

  test("--revision prints the supplied revision string and exits successfully"):
    val cwd = tempCwd()
    val result = runIn(cwd, "--revision")
    assertEquals(result.exitCode, ExitCode.Success)
    assertEquals(result.out, testRevision)
    assertEquals(result.err, "")

  test("a usage error exits with the usage error code and prints usage to stderr"):
    val cwd = tempCwd()
    val result = runIn(cwd, "bogus")
    assertEquals(result.exitCode, ExitCode.UsageError)
    assert(result.err.contains("unknown command: bogus"))
    assert(result.err.contains("Usage: msgman"))

  test("an invalid --file-pattern is a fatal error"):
    val cwd = tempCwd()
    confDir(cwd)
    val result = runIn(cwd, "verify", "--file-pattern", "messages")
    assertEquals(result.exitCode, ExitCode.Fatal)
    assert(result.err.contains("$1"))

  test("no matching files is a fatal error"):
    val cwd = tempCwd()
    confDir(cwd)
    val result = runIn(cwd, "verify")
    assertEquals(result.exitCode, ExitCode.Fatal)
    assert(result.err.contains("no messages files found"))

  test("a missing master language file is a fatal error"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.cy", "site.back = Yn ol\n")
    val result = runIn(cwd, "verify")
    assertEquals(result.exitCode, ExitCode.Fatal)
    assert(result.err.contains("master language file"))

  test("a malformed messages file is a fatal error"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "not a valid line\n")
    val result = runIn(cwd, "verify")
    assertEquals(result.exitCode, ExitCode.Fatal)
    assert(result.err.contains("en:"))

  test("verify succeeds on a canonical, fully translated set of files"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\nsite.change = Change\n")
    write(dir, "messages.cy", "site.back = Yn ol\nsite.change = Newid\n")
    val result = runIn(cwd, "verify")
    assertEquals(result.exitCode, ExitCode.Success)
    assertEquals(result.err, "")

  test("verify fails when a file is not in canonical order"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.change = Change\nsite.back = Back\n")
    val result = runIn(cwd, "verify")
    assertEquals(result.exitCode, ExitCode.Fatal)
    assert(result.err.contains("en is not in canonical format"))

  test("verify reports every non-canonical file, ordered by language code"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.change = Change\nsite.back = Back\n")
    write(dir, "messages.cy", "site.change = Newid\nsite.back = Yn ol\n")
    val result = runIn(cwd, "verify")
    assertEquals(result.exitCode, ExitCode.Fatal)
    val cyIndex = result.err.indexOf("cy is not in canonical format")
    val enIndex = result.err.indexOf("en is not in canonical format")
    assert(cyIndex >= 0 && enIndex >= 0 && cyIndex < enIndex)

  test("verify does not modify files"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    val content = "site.change = Change\nsite.back = Back\n"
    val file = write(dir, "messages.en", content)
    runIn(cwd, "verify")
    assertEquals(read(file), content)

  test("verify fails on a duplicate key, even with identical values, and reports it to stdout"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    val content = "site.back = Back\nsite.back = Back\n"
    val file = write(dir, "messages.en", content)
    val result = runIn(cwd, "verify")
    assertEquals(result.exitCode, ExitCode.Fatal)
    assert(result.out.contains("duplicate key [en] site.back"))
    assertEquals(result.err, "")
    assertEquals(read(file), content)

  test("verify fails on a duplicate key with conflicting values too"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\nsite.back = Again\n")
    val result = runIn(cwd, "verify")
    assertEquals(result.exitCode, ExitCode.Fatal)
    assert(result.out.contains("duplicate key [en] site.back"))

  test("verify fails and reports a missing translation"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\nsite.change = Change\n")
    write(dir, "messages.cy", "site.back = Yn ol\n")
    val result = runIn(cwd, "verify")
    assertEquals(result.exitCode, ExitCode.Fatal)
    assert(result.err.contains("missing translation [cy] site.change"))

  test("verify without --strict treats a placeholder value as translated"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\n")
    write(dir, "messages.cy", "site.back = en: Back\n")
    val result = runIn(cwd, "verify")
    assertEquals(result.exitCode, ExitCode.Success)

  test("verify --strict treats a placeholder value as missing"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\n")
    write(dir, "messages.cy", "site.back = en: Back\n")
    val result = runIn(cwd, "verify", "--strict")
    assertEquals(result.exitCode, ExitCode.Fatal)
    assert(result.err.contains("missing translation [cy] site.back"))

  test("verify reports a key not present in the master to stdout, without failing or changing the file"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\n")
    val content = "site.back = Yn ol\nsite.orphan = Rhywbeth\n"
    val cyFile = write(dir, "messages.cy", content)
    val result = runIn(cwd, "verify")
    assertEquals(result.exitCode, ExitCode.Success)
    assert(result.out.contains("extra translation [cy] site.orphan"))
    assertEquals(result.err, "")
    assertEquals(read(cyFile), content)

  test("--require fails when a required language file is missing"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\n")
    val result = runIn(cwd, "verify", "--require", "cy,fr")
    assertEquals(result.exitCode, ExitCode.Fatal)
    assert(result.err.contains("cy"))
    assert(result.err.contains("fr"))

  test("--require succeeds when every required language file is present"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\n")
    write(dir, "messages.cy", "site.back = Yn ol\n")
    val result = runIn(cwd, "verify", "--require", "cy")
    assertEquals(result.exitCode, ExitCode.Success)

  test("format merges duplicate keys with identical values into a single entry"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    val file = write(dir, "messages.en", "site.back = Back\nsite.back = Back\nsite.change = Change\n")
    val result = runIn(cwd, "format")
    assertEquals(result.exitCode, ExitCode.Success)
    assertEquals(read(file), "site.back = Back\nsite.change = Change\n")

  test("format fails without changing any file when a duplicate key has conflicting values"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    val enContent = "site.back = Back\nsite.back = Again\n"
    val enFile = write(dir, "messages.en", enContent)
    val cyContent = "site.change = Newid\nsite.back = Yn ol\n"
    val cyFile = write(dir, "messages.cy", cyContent)
    val result = runIn(cwd, "format")
    assertEquals(result.exitCode, ExitCode.Fatal)
    assert(result.err.contains("en"))
    assert(result.err.contains("site.back"))
    assertEquals(result.out, "")
    assertEquals(read(enFile), enContent)
    assertEquals(read(cyFile), cyContent)

  test("format rewrites a non-canonical file in place"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    val file = write(dir, "messages.en", "site.change = Change\nsite.back = Back\n")
    val result = runIn(cwd, "format")
    assertEquals(result.exitCode, ExitCode.Success)
    assertEquals(read(file), "site.back = Back\nsite.change = Change\n")

  test("format leaves an already canonical file untouched"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    val content = "site.back = Back\nsite.change = Change\n"
    val file = write(dir, "messages.en", content)
    val before = file.lastModified()
    Thread.sleep(10)
    runIn(cwd, "format")
    assertEquals(read(file), content)
    assertEquals(file.lastModified(), before)

  test("format --priority-keys sorts the named block(s) ahead of the rest, in the order given"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    val file = write(dir, "messages.en", "site.back = Back\ndate.day = Day\nphase = Phase\n")
    val result = runIn(cwd, "format", "--priority-keys", "phase,site")
    assertEquals(result.exitCode, ExitCode.Success)
    assertEquals(read(file), "phase = Phase\n\nsite.back = Back\n\ndate.day = Day\n")

  test("verify --priority-keys treats a file arranged with the priority block first as canonical"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "phase = Phase\n\ndate.day = Day\n\nsite.back = Back\n")
    val result = runIn(cwd, "verify", "--priority-keys", "phase")
    assertEquals(result.exitCode, ExitCode.Success)

  test("verify without --priority-keys treats a priority-first file as non-canonical"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "phase = Phase\n\ndate.day = Day\n\nsite.back = Back\n")
    val result = runIn(cwd, "verify")
    assertEquals(result.exitCode, ExitCode.Fatal)
    assert(result.err.contains("en is not in canonical format"))

  test("format falls back to 'priority-keys' from .msgman when --priority-keys is not given"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    val file = write(dir, "messages.en", "site.back = Back\nphase = Phase\n")
    write(cwd, ".msgman", "priority-keys = phase\n")
    val result = runWithAi(cwd, tempHome(), tempEtc(), Map.empty, "format")()
    assertEquals(result.exitCode, ExitCode.Success)
    assertEquals(read(file), "phase = Phase\n\nsite.back = Back\n")

  test("--priority-keys on the command line overrides 'priority-keys' in .msgman"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    val file = write(dir, "messages.en", "site.back = Back\nphase = Phase\n")
    write(cwd, ".msgman", "priority-keys = phase\n")
    val result = runWithAi(cwd, tempHome(), tempEtc(), Map.empty, "format", "--priority-keys", "site")()
    assertEquals(result.exitCode, ExitCode.Success)
    assertEquals(read(file), "site.back = Back\n\nphase = Phase\n")

  test("format reports missing translations to stdout but does not fail"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\nsite.change = Change\n")
    write(dir, "messages.cy", "site.back = Yn ol\n")
    val result = runIn(cwd, "format")
    assertEquals(result.exitCode, ExitCode.Success)
    assert(result.out.contains("missing translation [cy] site.change"))
    assertEquals(result.err, "")

  test("format removes a translation for a key not present in the master, and reports it to stdout"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\n")
    val cyFile = write(dir, "messages.cy", "site.back = Yn ol\nsite.orphan = Rhywbeth\n")
    val result = runIn(cwd, "format")
    assertEquals(result.exitCode, ExitCode.Success)
    assert(result.out.contains("removed translation [cy] site.orphan"))
    assertEquals(read(cyFile), "site.back = Yn ol\n")

  test("format does not remove or report a key that also exists in the master"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\nsite.change = Change\n")
    val cyFile = write(dir, "messages.cy", "site.back = Yn ol\nsite.change = Newid\n")
    val result = runIn(cwd, "format")
    assertEquals(result.exitCode, ExitCode.Success)
    assertEquals(result.out, "")
    assertEquals(read(cyFile), "site.back = Yn ol\nsite.change = Newid\n")

  test("format --fix adds a placeholder entry prefixed with the target language code"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\nsite.change = Change\n")
    val cyFile = write(dir, "messages.cy", "site.back = Yn ol\n")
    val result = runIn(cwd, "format", "--fix")
    assertEquals(result.exitCode, ExitCode.Success)
    assertEquals(read(cyFile), "site.back = Yn ol\nsite.change = cy: Change\n")

  test("format --fix on a file with no missing translations only reformats if necessary"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\nsite.change = Change\n")
    val cyFile = write(dir, "messages.cy", "site.change = Newid\nsite.back = Yn ol\n")
    val result = runIn(cwd, "format", "--fix")
    assertEquals(result.exitCode, ExitCode.Success)
    assertEquals(read(cyFile), "site.back = Yn ol\nsite.change = Newid\n")

  test("a custom --path and --file-pattern are respected"):
    val cwd = tempCwd()
    val dir = new File(cwd, "translations")
    dir.mkdirs()
    write(dir, "msg_en.txt", "site.back = Back\n")
    val result = runIn(cwd, "verify", "--path", "translations", "--file-pattern", "msg_$1.txt")
    assertEquals(result.exitCode, ExitCode.Success)

  test("a custom --master is respected"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.cy", "site.back = Yn ol\n")
    val result = runIn(cwd, "verify", "--master", "cy")
    assertEquals(result.exitCode, ExitCode.Success)

  test("master, file-pattern, path and require fall back to .msgman when their switches are not given"):
    val cwd = tempCwd()
    val dir = new File(cwd, "translations")
    dir.mkdirs()
    write(dir, "msg_cy.txt", "site.back = Yn ol\n")
    write(cwd, ".msgman", "master = cy\nfile-pattern = msg_$1.txt\npath = translations\nrequire = cy\n")
    val result = runWithAi(cwd, tempHome(), tempEtc(), Map.empty, "verify")()
    assertEquals(result.exitCode, ExitCode.Success)

  test("--master, --file-pattern, --path and --require on the command line override .msgman"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\n")
    write(dir, "messages.cy", "site.back = Yn ol\n")
    write(cwd, ".msgman", "master = cy\nfile-pattern = msg_$1.txt\npath = translations\nrequire = fr\n")
    val result = runWithAi(cwd, tempHome(), tempEtc(), Map.empty, "verify", "--master", "en", "--file-pattern", "messages.$1", "--path", "conf", "--require", "cy")()
    assertEquals(result.exitCode, ExitCode.Success)

  test("a 'master' from .msgman that is not a 2-letter ISO code is a fatal error"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\n")
    write(cwd, ".msgman", "master = eng\n")
    val result = runWithAi(cwd, tempHome(), tempEtc(), Map.empty, "verify")()
    assertEquals(result.exitCode, ExitCode.Fatal)
    assert(result.err.contains("master must be a 2-letter ISO country code: eng"))

  test("a 'require' entry from .msgman that is not a 2-letter ISO code is a fatal error"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\n")
    write(cwd, ".msgman", "require = xyz\n")
    val result = runWithAi(cwd, tempHome(), tempEtc(), Map.empty, "verify")()
    assertEquals(result.exitCode, ExitCode.Fatal)
    assert(result.err.contains("require codes must be 2-letter ISO country codes: xyz"))

  test("--translate in a build with no AI provider linked in at all is a fatal error"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\n")
    write(dir, "messages.cy", "")
    val result = runWithAi(cwd, tempHome(), tempEtc(), Map.empty, "format", "--translate")()
    assertEquals(result.exitCode, ExitCode.Fatal)
    assert(result.err.contains("built without --translate support"))
    assert(result.err.contains("--with-ai"))

  test("--translate with no AI provider linked reports that even when .msgman selects one"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\n")
    write(dir, "messages.cy", "")
    write(cwd, ".msgman", "provider = claude\n")
    val result = runWithAi(cwd, tempHome(), tempEtc(), Map.empty, "format", "--translate")()
    assertEquals(result.exitCode, ExitCode.Fatal)
    assert(result.err.contains("built without --translate support"))

  test("--translate with no provider configured, but more than one linked in, is an ambiguous fatal error"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\n")
    write(dir, "messages.cy", "")
    val translator = new FakeTranslator(PartialFunction.empty)
    val result = runWithAi(cwd, tempHome(), tempEtc(), Map("claude" -> translator, "openai" -> translator), "format", "--translate")()
    assertEquals(result.exitCode, ExitCode.Fatal)
    assert(result.err.contains("requires a provider to be selected"))
    assert(result.err.contains("linked: claude, openai"))

  test("--translate with no provider configured auto-selects the single provider linked in"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\n")
    val cyFile = write(dir, "messages.cy", "")
    write(cwd, ".msgman", "claude.model = claude-sonnet-5\n")
    val translator = new FakeTranslator({
      case keys if keys == Set("site.back") => TranslationOutcome.Success(TranslationResponse(Map("site.back" -> "Yn ol")))
    })
    val result = runWithAi(cwd, tempHome(), tempEtc(), Map("claude" -> translator), "format", "--translate")()
    assertEquals(result.exitCode, ExitCode.Success)
    assertEquals(read(cyFile), "## added by msgman using claude-sonnet-5\nsite.back = Yn ol\n")

  test("--translate with a provider not among the ones linked into this build is a fatal error"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\n")
    write(dir, "messages.cy", "")
    write(cwd, ".msgman", "provider = claude\n")
    val translator = new FakeTranslator(PartialFunction.empty)
    val result = runWithAi(cwd, tempHome(), tempEtc(), Map("openai" -> translator), "format", "--translate")()
    assertEquals(result.exitCode, ExitCode.Fatal)
    assert(result.err.contains("'claude'"))
    assert(result.err.contains("not linked"))
    assert(result.err.contains("linked: openai"))

  test("--translate with a provider not linked reports the providers that are, sorted"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\n")
    write(dir, "messages.cy", "")
    write(cwd, ".msgman", "provider = claude\n")
    val translator = new FakeTranslator(PartialFunction.empty)
    val result = runWithAi(cwd, tempHome(), tempEtc(), Map("openai" -> translator, "gemini" -> translator), "format", "--translate")()
    assertEquals(result.exitCode, ExitCode.Fatal)
    assert(result.err.contains("linked: gemini, openai"))

  test("--translate with no API key available is a fatal error, without ever calling the translator"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\nsite.change = Change\n")
    write(dir, "messages.cy", "")
    write(cwd, ".msgman", "provider = claude\nclaude.model = claude-sonnet-5\n")
    val translator = new FakeTranslator(PartialFunction.empty)
    val result = runWithAi(cwd, tempHome(), tempEtc(), Map("claude" -> translator), "format", "--translate")(_ => None)
    assertEquals(result.exitCode, ExitCode.Fatal)
    assert(result.err.contains("no API key configured for provider 'claude'"))
    assert(result.err.contains("ANTHROPIC_API_KEY"))
    assert(result.err.contains("claude.fallback-key"))
    assertEquals(translator.requests.size, 0)

  test("--translate falls back to the configured fallback key when the env var is not set"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\n")
    val cyFile = write(dir, "messages.cy", "")
    write(cwd, ".msgman", "provider = claude\nclaude.model = claude-sonnet-5\nclaude.fallback-key = sk-fallback\n")
    val translator = new FakeTranslator({
      case keys if keys == Set("site.back") => TranslationOutcome.Success(TranslationResponse(Map("site.back" -> "Yn ol")))
    })
    val result = runWithAi(cwd, tempHome(), tempEtc(), Map("claude" -> translator), "format", "--translate")(_ => None)
    assertEquals(result.exitCode, ExitCode.Success)
    assertEquals(read(cyFile), "## added by msgman using claude-sonnet-5\nsite.back = Yn ol\n")

  test("--translate with no model configured is a fatal error"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\n")
    write(dir, "messages.cy", "")
    write(cwd, ".msgman", "provider = claude\n")
    val translator = new FakeTranslator(PartialFunction.empty)
    val result = runWithAi(cwd, tempHome(), tempEtc(), Map("claude" -> translator), "format", "--translate")()
    assertEquals(result.exitCode, ExitCode.Fatal)
    assert(result.err.contains("no AI model configured"))

  test("--translate successfully translates a missing key and tags it"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\n")
    val cyFile = write(dir, "messages.cy", "")
    write(cwd, ".msgman", "provider = claude\nclaude.model = claude-sonnet-5\n")
    val translator = new FakeTranslator({
      case keys if keys == Set("site.back") => TranslationOutcome.Success(TranslationResponse(Map("site.back" -> "Yn ol")))
    })
    val result = runWithAi(cwd, tempHome(), tempEtc(), Map("claude" -> translator), "format", "--translate")()
    assertEquals(result.exitCode, ExitCode.Success)
    assertEquals(read(cyFile), "## added by msgman using claude-sonnet-5\nsite.back = Yn ol\n")
    assert(!result.out.contains("requesting translation"))

  test("--verbose prints each request before, and its response after"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\n")
    write(dir, "messages.cy", "")
    write(cwd, ".msgman", "provider = claude\nclaude.model = claude-sonnet-5\n")
    val translator = new FakeTranslator({
      case keys if keys == Set("site.back") => TranslationOutcome.Success(TranslationResponse(Map("site.back" -> "Yn ol")))
    })
    val result = runWithAi(cwd, tempHome(), tempEtc(), Map("claude" -> translator), "format", "--translate", "--verbose")()
    assertEquals(result.exitCode, ExitCode.Success)
    val requestIndex = result.out.indexOf("msgman: [cy] requesting translation of site (back) from claude-sonnet-5")
    val responseIndex = result.out.indexOf("msgman: [cy] received translation of site: back = Yn ol")
    assert(requestIndex >= 0 && responseIndex >= 0 && requestIndex < responseIndex)

  test("--verbose reports a failed request too"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\n")
    write(dir, "messages.cy", "")
    write(cwd, ".msgman", "provider = claude\nclaude.model = claude-sonnet-5\n")
    val translator = new FakeTranslator(PartialFunction.empty)
    val result = runWithAi(cwd, tempHome(), tempEtc(), Map("claude" -> translator), "format", "--translate", "--verbose")()
    assertEquals(result.exitCode, ExitCode.TranslationFailure)
    assert(result.out.contains("msgman: [cy] translation of site failed: unhandled"))

  test("--model overrides the configured model"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\n")
    val cyFile = write(dir, "messages.cy", "")
    write(cwd, ".msgman", "provider = claude\nclaude.model = claude-sonnet-5\n")
    val translator = new FakeTranslator({
      case keys if keys == Set("site.back") => TranslationOutcome.Success(TranslationResponse(Map("site.back" -> "Yn ol")))
    })
    val result = runWithAi(cwd, tempHome(), tempEtc(), Map("claude" -> translator), "format", "--translate", "--model", "claude-opus-5")()
    assertEquals(result.exitCode, ExitCode.Success)
    assertEquals(read(cyFile), "## added by msgman using claude-opus-5\nsite.back = Yn ol\n")

  test("stealth mode omits the added-by-msgman comment"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\n")
    val cyFile = write(dir, "messages.cy", "")
    write(cwd, ".msgman", "provider = claude\nclaude.model = claude-sonnet-5\nstealth = true\n")
    val translator = new FakeTranslator({
      case keys if keys == Set("site.back") => TranslationOutcome.Success(TranslationResponse(Map("site.back" -> "Yn ol")))
    })
    val result = runWithAi(cwd, tempHome(), tempEtc(), Map("claude" -> translator), "format", "--translate")()
    assertEquals(result.exitCode, ExitCode.Success)
    assertEquals(read(cyFile), "site.back = Yn ol\n")

  test("a failed translation leaves the key missing and exits with the translation-failure code"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\nsite.change = Change\n")
    val cyFile = write(dir, "messages.cy", "")
    write(cwd, ".msgman", "provider = claude\nclaude.model = claude-sonnet-5\n")
    val translator = new FakeTranslator(PartialFunction.empty)
    val result = runWithAi(cwd, tempHome(), tempEtc(), Map("claude" -> translator), "format", "--translate")()
    assertEquals(result.exitCode, ExitCode.TranslationFailure)
    assert(result.err.contains("translation failed [cy] site.back: unhandled"))
    assert(result.err.contains("translation failed [cy] site.change: unhandled"))
    assertEquals(read(cyFile), "")

  test("a fatal translation failure exits fatally, without writing any file, and reports one clear message"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\n")
    val cyFile = write(dir, "messages.cy", "")
    write(cwd, ".msgman", "provider = claude\nclaude.model = gemini-2.5-flash\n")
    val translator = new FakeTranslator({
      case _ => TranslationOutcome.Failure("This model models/gemini-2.5-flash is no longer available to new users.", fatal = true)
    })
    val result = runWithAi(cwd, tempHome(), tempEtc(), Map("claude" -> translator), "format", "--translate")()
    assertEquals(result.exitCode, ExitCode.Fatal)
    assertEquals(result.err, "msgman: This model models/gemini-2.5-flash is no longer available to new users.\n")
    assertEquals(read(cyFile), "")
    assertEquals(translator.requests.size, 1)

  test("a fatal translation failure stops before attempting any further target language file"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\n")
    write(dir, "messages.cy", "")
    write(dir, "messages.fr", "")
    write(cwd, ".msgman", "provider = claude\nclaude.model = claude-sonnet-5\n")
    val translator = new FakeTranslator({
      case _ => TranslationOutcome.Failure("no API key configured", fatal = true)
    })
    val result = runWithAi(cwd, tempHome(), tempEtc(), Map("claude" -> translator), "format", "--translate")()
    assertEquals(result.exitCode, ExitCode.Fatal)
    // cy and fr are processed alphabetically; a single request means the run
    // stopped after the first target language rather than trying both.
    assertEquals(translator.requests.size, 1)

  test("--translate handles more than one target language file"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\n")
    val cyFile = write(dir, "messages.cy", "")
    val frFile = write(dir, "messages.fr", "")
    write(cwd, ".msgman", "provider = claude\nclaude.model = claude-sonnet-5\n")
    val translator = new FakeTranslator({
      case keys if keys == Set("site.back") => TranslationOutcome.Success(TranslationResponse(Map("site.back" -> "translated")))
    })
    val result = runWithAi(cwd, tempHome(), tempEtc(), Map("claude" -> translator), "format", "--translate")()
    assertEquals(result.exitCode, ExitCode.Success)
    assertEquals(read(cyFile), "## added by msgman using claude-sonnet-5\nsite.back = translated\n")
    assertEquals(read(frFile), "## added by msgman using claude-sonnet-5\nsite.back = translated\n")

  test("--translate is not combined with --fix"):
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\n")
    val result = runWithAi(cwd, tempHome(), tempEtc(), Map.empty, "format", "--translate", "--fix")()
    assertEquals(result.exitCode, ExitCode.UsageError)
    assert(result.err.contains("--translate cannot be used together with --fix"))
