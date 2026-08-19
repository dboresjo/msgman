package msgman

import java.io.File
import scala.util.matching.Regex

final case class LanguageFile(code: String, file: File)

final case class InvalidFilePatternException(message: String) extends RuntimeException(message)

object FileDiscovery {

  /** Turns a `--file-pattern` such as "messages.$1" into a regex that captures
    * the language code in place of the `$1` placeholder.
    */
  def patternToRegex(pattern: String): Regex = {
    val idx = pattern.indexOf("$1")
    if (idx < 0)
      throw InvalidFilePatternException(s"file pattern must contain the $$1 placeholder for the language code: $pattern")
    val before = Regex.quote(pattern.substring(0, idx))
    val after = Regex.quote(pattern.substring(idx + 2))
    new Regex(s"^$before([a-zA-Z-]+)$after$$")
  }

  /** Finds every file directly inside `dir` whose name matches `pattern`,
    * pairing each with the language code extracted from its name.
    */
  def discover(dir: File, pattern: String): List[LanguageFile] = {
    val regex = patternToRegex(pattern)
    val files = Option(dir.listFiles()).getOrElse(Array.empty[File])
    files.toList
      .filter(_.isFile)
      .flatMap(f => regex.findFirstMatchIn(f.getName).map(m => LanguageFile(m.group(1), f)))
      .sortBy(_.code)
  }
}
