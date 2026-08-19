package msgman

import java.io.File

/** The `--translate` settings parsed out of the merged `.msgman`
  * configuration files. `model` and `fallbackKey` are keyed by provider name
  * (eg. "openai"), since both are specified on a per-provider basis. The
  * `.msgman` file itself is not AI-specific (see `loadSettings`); other
  * features read their own keys directly out of the raw settings map instead
  * of being modelled here.
  */
final case class Config(
    provider: Option[String] = None,
    model: Map[String, String] = Map.empty,
    fallbackKey: Map[String, String] = Map.empty,
    stealth: Boolean = false,
    translationContext: Option[String] = None
)

object Config {

  private val blank = "^\\s*(#.*)?$".r
  private val keyValue = "^([^=\\s][^=]*?)\\s*=\\s*(.*)$".r
  private val perProviderKey = "^([a-z]+)\\.(model|fallback-key)$".r

  /** Parses a single properties-format file: flat `key = value` lines, `#`
    * starts a comment. Lines that are neither blank/comment nor `key = value`
    * are ignored, same tolerance as blank lines, since a config file is
    * hand-edited and unlikely to be validated up front like a messages file.
    */
  def parseFile(content: String): Map[String, String] =
    scala.io.Source.fromString(content).getLines().foldLeft(Map.empty[String, String]) { (acc, rawLine) =>
      val trimmed = rawLine.trim
      if (blank.pattern.matcher(trimmed).matches()) acc
      else
        trimmed match {
          case keyValue(k, v) => acc + (k.trim -> v.trim)
          case _              => acc
        }
    }

  /** Merges parsed config files, most local taking priority per key. `files`
    * must be ordered most local first, matching the search order in
    * TRANSLATION.md.
    */
  def merge(files: List[Map[String, String]]): Map[String, String] =
    files.foldRight(Map.empty[String, String])((local, acc) => acc ++ local)

  def fromSettings(settings: Map[String, String]): Config = {
    val perProvider = settings.toList.collect { case (perProviderKey(provider, field), value) => (provider, field, value) }
    Config(
      provider = settings.get("provider"),
      model = perProvider.collect { case (provider, "model", value) => provider -> value }.toMap,
      fallbackKey = perProvider.collect { case (provider, "fallback-key", value) => provider -> value }.toMap,
      stealth = settings.get("stealth").contains("true"),
      translationContext = settings.get("translation-context")
    )
  }

  /** Reads and merges the three config file locations, most local first,
    * matching the search order in TRANSLATION.md. A location that doesn't
    * exist is skipped. Not AI-specific: this is the raw `.msgman` settings
    * map, shared by any feature configurable through that file (e.g.
    * `priority-keys`, read directly by `Runner`).
    */
  def loadSettings(cwd: File, home: File, etc: File): Map[String, String] = {
    val locations = List(new File(cwd, ".msgman"), new File(home, ".msgman"), new File(etc, "msgman"))
    val parsed = locations.filter(_.isFile).map(f => parseFile(readFile(f)))
    merge(parsed)
  }

  def load(cwd: File, home: File, etc: File): Config = fromSettings(loadSettings(cwd, home, etc))

  /** Convenience overload for production use: `$HOME` and `/etc` as reported
    * by the JVM/OS.
    */
  def load(cwd: File): Config =
    load(cwd, new File(System.getProperty("user.home")), new File("/etc"))

  private def readFile(file: File): String = {
    val source = scala.io.Source.fromFile(file, "UTF-8")
    try source.mkString
    finally source.close()
  }

  /** The environment variable each provider's sttp-ai client reads its API
    * key from by default (its own `fromEnv` convention), see TRANSLATION.md.
    */
  def apiKeyEnvVar(provider: String): String = provider match {
    case "openai" => "OPENAI_KEY"
    case "claude"  => "ANTHROPIC_API_KEY"
    case "gemini"  => "GEMINI_API_KEY"
    case other     => throw new IllegalArgumentException(s"unknown AI provider: $other")
  }

  /** The API key to use for `provider`: its own environment variable if set,
    * otherwise the configured fallback key, otherwise none.
    */
  def resolveApiKey(provider: String, config: Config, env: String => Option[String]): Option[String] =
    env(apiKeyEnvVar(provider)).filter(_.nonEmpty).orElse(config.fallbackKey.get(provider))
}
