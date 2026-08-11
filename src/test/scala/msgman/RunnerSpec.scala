package msgman

import java.io.{ByteArrayOutputStream, File, PrintStream}
import java.nio.file.Files

class RunnerSpec extends munit.FunSuite {

  private def tempCwd(): File = Files.createTempDirectory("msgman-runner").toFile

  private def confDir(cwd: File): File = {
    val dir = new File(cwd, "conf")
    dir.mkdirs()
    dir
  }

  private def write(dir: File, name: String, content: String): File = {
    val f = new File(dir, name)
    val w = new java.io.PrintWriter(f, "UTF-8")
    try w.print(content) finally w.close()
    f
  }

  private def read(file: File): String = {
    val s = scala.io.Source.fromFile(file, "UTF-8")
    try s.mkString finally s.close()
  }

  private case class Result(exitCode: Int, out: String, err: String)

  private def runIn(cwd: File, args: String*): Result = {
    val outBytes = new ByteArrayOutputStream()
    val errBytes = new ByteArrayOutputStream()
    val out = new PrintStream(outBytes, true, "UTF-8")
    val err = new PrintStream(errBytes, true, "UTF-8")
    val code = Runner.run(args.toArray, cwd, out, err)
    Result(code, outBytes.toString("UTF-8"), errBytes.toString("UTF-8"))
  }

  test("--help prints usage and exits successfully") {
    val cwd = tempCwd()
    val result = runIn(cwd, "--help")
    assertEquals(result.exitCode, ExitCode.Success)
    assert(result.out.contains("Usage: msgman"))
  }

  test("a usage error exits with the usage error code and prints usage to stderr") {
    val cwd = tempCwd()
    val result = runIn(cwd, "bogus")
    assertEquals(result.exitCode, ExitCode.UsageError)
    assert(result.err.contains("unknown command: bogus"))
    assert(result.err.contains("Usage: msgman"))
  }

  test("an invalid --file-pattern is a fatal error") {
    val cwd = tempCwd()
    confDir(cwd)
    val result = runIn(cwd, "verify", "--file-pattern", "messages")
    assertEquals(result.exitCode, ExitCode.Fatal)
    assert(result.err.contains("$1"))
  }

  test("no matching files is a fatal error") {
    val cwd = tempCwd()
    confDir(cwd)
    val result = runIn(cwd, "verify")
    assertEquals(result.exitCode, ExitCode.Fatal)
    assert(result.err.contains("no messages files found"))
  }

  test("a missing master language file is a fatal error") {
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.cy", "site.back = Yn ol\n")
    val result = runIn(cwd, "verify")
    assertEquals(result.exitCode, ExitCode.Fatal)
    assert(result.err.contains("master language file"))
  }

  test("a malformed messages file is a fatal error") {
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "not a valid line\n")
    val result = runIn(cwd, "verify")
    assertEquals(result.exitCode, ExitCode.Fatal)
    assert(result.err.contains("en:"))
  }

  test("verify succeeds on a canonical, fully translated set of files") {
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\nsite.change = Change\n")
    write(dir, "messages.cy", "site.back = Yn ol\nsite.change = Newid\n")
    val result = runIn(cwd, "verify")
    assertEquals(result.exitCode, ExitCode.Success)
    assertEquals(result.err, "")
  }

  test("verify fails when a file is not in canonical order") {
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.change = Change\nsite.back = Back\n")
    val result = runIn(cwd, "verify")
    assertEquals(result.exitCode, ExitCode.Fatal)
    assert(result.err.contains("en is not in canonical format"))
  }

  test("verify reports every non-canonical file, ordered by language code") {
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.change = Change\nsite.back = Back\n")
    write(dir, "messages.cy", "site.change = Newid\nsite.back = Yn ol\n")
    val result = runIn(cwd, "verify")
    assertEquals(result.exitCode, ExitCode.Fatal)
    val cyIndex = result.err.indexOf("cy is not in canonical format")
    val enIndex = result.err.indexOf("en is not in canonical format")
    assert(cyIndex >= 0 && enIndex >= 0 && cyIndex < enIndex)
  }

  test("verify does not modify files") {
    val cwd = tempCwd()
    val dir = confDir(cwd)
    val content = "site.change = Change\nsite.back = Back\n"
    val file = write(dir, "messages.en", content)
    runIn(cwd, "verify")
    assertEquals(read(file), content)
  }

  test("verify fails on a duplicate key, even with identical values, and reports it to stdout") {
    val cwd = tempCwd()
    val dir = confDir(cwd)
    val content = "site.back = Back\nsite.back = Back\n"
    val file = write(dir, "messages.en", content)
    val result = runIn(cwd, "verify")
    assertEquals(result.exitCode, ExitCode.Fatal)
    assert(result.out.contains("duplicate key [en] site.back"))
    assertEquals(result.err, "")
    assertEquals(read(file), content)
  }

  test("verify fails on a duplicate key with conflicting values too") {
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\nsite.back = Again\n")
    val result = runIn(cwd, "verify")
    assertEquals(result.exitCode, ExitCode.Fatal)
    assert(result.out.contains("duplicate key [en] site.back"))
  }

  test("verify fails and reports a missing translation") {
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\nsite.change = Change\n")
    write(dir, "messages.cy", "site.back = Yn ol\n")
    val result = runIn(cwd, "verify")
    assertEquals(result.exitCode, ExitCode.Fatal)
    assert(result.err.contains("missing translation [cy] site.change"))
  }

  test("verify without --strict treats a placeholder value as translated") {
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\n")
    write(dir, "messages.cy", "site.back = en: Back\n")
    val result = runIn(cwd, "verify")
    assertEquals(result.exitCode, ExitCode.Success)
  }

  test("verify --strict treats a placeholder value as missing") {
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\n")
    write(dir, "messages.cy", "site.back = en: Back\n")
    val result = runIn(cwd, "verify", "--strict")
    assertEquals(result.exitCode, ExitCode.Fatal)
    assert(result.err.contains("missing translation [cy] site.back"))
  }

  test("verify reports a key not present in the master to stdout, without failing or changing the file") {
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
  }

  test("--require fails when a required language file is missing") {
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\n")
    val result = runIn(cwd, "verify", "--require", "cy,fr")
    assertEquals(result.exitCode, ExitCode.Fatal)
    assert(result.err.contains("cy"))
    assert(result.err.contains("fr"))
  }

  test("--require succeeds when every required language file is present") {
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\n")
    write(dir, "messages.cy", "site.back = Yn ol\n")
    val result = runIn(cwd, "verify", "--require", "cy")
    assertEquals(result.exitCode, ExitCode.Success)
  }

  test("format merges duplicate keys with identical values into a single entry") {
    val cwd = tempCwd()
    val dir = confDir(cwd)
    val file = write(dir, "messages.en", "site.back = Back\nsite.back = Back\nsite.change = Change\n")
    val result = runIn(cwd, "format")
    assertEquals(result.exitCode, ExitCode.Success)
    assertEquals(read(file), "site.back = Back\nsite.change = Change\n")
  }

  test("format fails without changing any file when a duplicate key has conflicting values") {
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
  }

  test("format rewrites a non-canonical file in place") {
    val cwd = tempCwd()
    val dir = confDir(cwd)
    val file = write(dir, "messages.en", "site.change = Change\nsite.back = Back\n")
    val result = runIn(cwd, "format")
    assertEquals(result.exitCode, ExitCode.Success)
    assertEquals(read(file), "site.back = Back\nsite.change = Change\n")
  }

  test("format leaves an already canonical file untouched") {
    val cwd = tempCwd()
    val dir = confDir(cwd)
    val content = "site.back = Back\nsite.change = Change\n"
    val file = write(dir, "messages.en", content)
    val before = file.lastModified()
    Thread.sleep(10)
    runIn(cwd, "format")
    assertEquals(read(file), content)
    assertEquals(file.lastModified(), before)
  }

  test("format reports missing translations to stdout but does not fail") {
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\nsite.change = Change\n")
    write(dir, "messages.cy", "site.back = Yn ol\n")
    val result = runIn(cwd, "format")
    assertEquals(result.exitCode, ExitCode.Success)
    assert(result.out.contains("missing translation [cy] site.change"))
    assertEquals(result.err, "")
  }

  test("format removes a translation for a key not present in the master, and reports it to stdout") {
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\n")
    val cyFile = write(dir, "messages.cy", "site.back = Yn ol\nsite.orphan = Rhywbeth\n")
    val result = runIn(cwd, "format")
    assertEquals(result.exitCode, ExitCode.Success)
    assert(result.out.contains("removed translation [cy] site.orphan"))
    assertEquals(read(cyFile), "site.back = Yn ol\n")
  }

  test("format does not remove or report a key that also exists in the master") {
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\nsite.change = Change\n")
    val cyFile = write(dir, "messages.cy", "site.back = Yn ol\nsite.change = Newid\n")
    val result = runIn(cwd, "format")
    assertEquals(result.exitCode, ExitCode.Success)
    assertEquals(result.out, "")
    assertEquals(read(cyFile), "site.back = Yn ol\nsite.change = Newid\n")
  }

  test("format --fix adds a placeholder entry prefixed with the target language code") {
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\nsite.change = Change\n")
    val cyFile = write(dir, "messages.cy", "site.back = Yn ol\n")
    val result = runIn(cwd, "format", "--fix")
    assertEquals(result.exitCode, ExitCode.Success)
    assertEquals(read(cyFile), "site.back = Yn ol\nsite.change = cy: Change\n")
  }

  test("format --fix on a file with no missing translations only reformats if necessary") {
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.en", "site.back = Back\nsite.change = Change\n")
    val cyFile = write(dir, "messages.cy", "site.change = Newid\nsite.back = Yn ol\n")
    val result = runIn(cwd, "format", "--fix")
    assertEquals(result.exitCode, ExitCode.Success)
    assertEquals(read(cyFile), "site.back = Yn ol\nsite.change = Newid\n")
  }

  test("a custom --path and --file-pattern are respected") {
    val cwd = tempCwd()
    val dir = new File(cwd, "translations")
    dir.mkdirs()
    write(dir, "msg_en.txt", "site.back = Back\n")
    val result = runIn(cwd, "verify", "--path", "translations", "--file-pattern", "msg_$1.txt")
    assertEquals(result.exitCode, ExitCode.Success)
  }

  test("a custom --master is respected") {
    val cwd = tempCwd()
    val dir = confDir(cwd)
    write(dir, "messages.cy", "site.back = Yn ol\n")
    val result = runIn(cwd, "verify", "--master", "cy")
    assertEquals(result.exitCode, ExitCode.Success)
  }
}
