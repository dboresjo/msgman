package msgman

import java.io.File
import java.nio.file.Files

class FileDiscoverySpec extends munit.FunSuite {

  private def tempDir(): File = Files.createTempDirectory("msgman-discovery").toFile

  private def touch(dir: File, name: String): Unit = {
    val f = new File(dir, name)
    val w = new java.io.PrintWriter(f)
    try w.print("") finally w.close()
  }

  test("patternToRegex requires a $1 placeholder") {
    intercept[InvalidFilePatternException] {
      FileDiscovery.patternToRegex("messages")

    }
  }
  test("patternToRegex captures the language code in place of $1") {
    val regex = FileDiscovery.patternToRegex("messages.$1")
    assertEquals(regex.findFirstMatchIn("messages.en").map(_.group(1)), Some("en"))
    assertEquals(regex.findFirstMatchIn("unrelated.txt"), None)

  }
  test("patternToRegex escapes regex metacharacters in the surrounding text") {
    val regex = FileDiscovery.patternToRegex("messages[$1].txt")
    assertEquals(regex.findFirstMatchIn("messages[en].txt").map(_.group(1)), Some("en"))
    // the literal '[' must not be treated as the start of a character class
    assertEquals(regex.findFirstMatchIn("messagesXenY.txt"), None)

  }
  test("discover finds files matching the pattern and extracts their language code") {
    val dir = tempDir()
    touch(dir, "messages.en")
    touch(dir, "messages.cy")
    touch(dir, "readme.txt")

    val found = FileDiscovery.discover(dir, "messages.$1")
    assertEquals(found.map(_.code), List("cy", "en"))

  }
  test("discover ignores subdirectories") {
    val dir = tempDir()
    touch(dir, "messages.en")
    new File(dir, "messages.sub").mkdir()

    val found = FileDiscovery.discover(dir, "messages.$1")
    assertEquals(found.map(_.code), List("en"))

  }
  test("discover returns an empty list when the directory does not exist") {
    val dir = new File(tempDir(), "does-not-exist")
    assertEquals(FileDiscovery.discover(dir, "messages.$1"), Nil)

  }
  test("discover returns an empty list when nothing matches") {
    val dir = tempDir()
    touch(dir, "readme.txt")
    assertEquals(FileDiscovery.discover(dir, "messages.$1"), Nil)
  }
}
