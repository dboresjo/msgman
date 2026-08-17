package msgman

import java.io.File
import java.nio.file.Files

class ConfigSpec extends munit.FunSuite:

  test("parseFile reads flat key = value lines"):
    assertEquals(Config.parseFile("provider = openai\nstealth = true\n"), Map("provider" -> "openai", "stealth" -> "true"))

  test("parseFile ignores comments and blank lines"):
    assertEquals(Config.parseFile("# a comment\n\nprovider = openai\n"), Map("provider" -> "openai"))

  test("parseFile trims whitespace around key and value"):
    assertEquals(Config.parseFile("  provider   =   openai  \n"), Map("provider" -> "openai"))

  test("parseFile ignores a line with no '='"):
    assertEquals(Config.parseFile("not a valid line\nprovider = openai\n"), Map("provider" -> "openai"))

  test("parseFile reads dotted per-provider keys"):
    assertEquals(Config.parseFile("openai.model = gpt-5\n"), Map("openai.model" -> "gpt-5"))

  test("merge: most local file wins per key"):
    val local = Map("provider" -> "openai")
    val home = Map("provider" -> "claude", "stealth" -> "true")
    assertEquals(Config.merge(List(local, home)), Map("provider" -> "openai", "stealth" -> "true"))

  test("merge with no files is empty"):
    assertEquals(Config.merge(Nil), Map.empty[String, String])

  test("fromSettings reads provider, per-provider model and fallback-key, stealth, translation-context"):
    val settings = Map(
      "provider"              -> "claude",
      "claude.model"          -> "claude-sonnet-5",
      "claude.fallback-key"   -> "sk-fallback",
      "openai.model"          -> "gpt-5",
      "stealth"               -> "true",
      "translation-context"   -> "docs/CONTEXT.md"
    )
    assertEquals(
      Config.fromSettings(settings),
      Config(
        provider = Some("claude"),
        model = Map("claude" -> "claude-sonnet-5", "openai" -> "gpt-5"),
        fallbackKey = Map("claude" -> "sk-fallback"),
        stealth = true,
        translationContext = Some("docs/CONTEXT.md")
      )
    )

  test("fromSettings defaults when nothing is set"):
    assertEquals(Config.fromSettings(Map.empty), Config())

  test("fromSettings treats any non-'true' stealth value as false"):
    assertEquals(Config.fromSettings(Map("stealth" -> "yes")).stealth, false)

  private def tempDir(): File = Files.createTempDirectory("msgman-config").toFile

  private def write(dir: File, name: String, content: String): Unit =
    dir.mkdirs()
    val w = new java.io.PrintWriter(new File(dir, name), "UTF-8")
    try w.print(content) finally w.close()

  test("load merges cwd, home and etc locations, most local first"):
    val cwd = tempDir()
    val home = tempDir()
    val etc = tempDir()
    write(cwd, ".msgman", "provider = openai\n")
    write(home, ".msgman", "provider = claude\nstealth = true\n")
    write(etc, "msgman", "stealth = false\nopenai.model = gpt-5\n")
    val config = Config.load(cwd, home, etc)
    assertEquals(config, Config(provider = Some("openai"), stealth = true, model = Map("openai" -> "gpt-5")))

  test("loadSettings merges cwd, home and etc locations, most local first, as a raw settings map"):
    val cwd = tempDir()
    val home = tempDir()
    val etc = tempDir()
    write(cwd, ".msgman", "priority-keys = phase,site\n")
    write(home, ".msgman", "priority-keys = ignored\nstealth = true\n")
    assertEquals(Config.loadSettings(cwd, home, etc), Map("priority-keys" -> "phase,site", "stealth" -> "true"))

  test("loadSettings with no config files anywhere is empty"):
    val cwd = tempDir()
    val home = tempDir()
    val etc = tempDir()
    assertEquals(Config.loadSettings(cwd, home, etc), Map.empty[String, String])

  test("load treats a missing location as absent, not an error"):
    val cwd = tempDir()
    val home = tempDir()
    val etc = tempDir()
    write(cwd, ".msgman", "provider = openai\n")
    assertEquals(Config.load(cwd, home, etc), Config(provider = Some("openai")))

  test("load with no config files anywhere returns defaults"):
    val cwd = tempDir()
    val home = tempDir()
    val etc = tempDir()
    assertEquals(Config.load(cwd, home, etc), Config())

  test("load(cwd) convenience overload uses the real HOME and /etc"):
    val cwd = tempDir()
    // Just exercises the delegation; real HOME/etc are not expected to hold
    // a .msgman file for the value asserted here to be meaningful, but the
    // call must not throw and cwd's own file must still be picked up.
    write(cwd, ".msgman", "provider = openai\n")
    assertEquals(Config.load(cwd).provider, Some("openai"))

  test("apiKeyEnvVar returns each provider's own convention"):
    assertEquals(Config.apiKeyEnvVar("openai"), "OPENAI_KEY")
    assertEquals(Config.apiKeyEnvVar("claude"), "ANTHROPIC_API_KEY")
    assertEquals(Config.apiKeyEnvVar("gemini"), "GEMINI_API_KEY")

  test("apiKeyEnvVar rejects an unknown provider"):
    intercept[IllegalArgumentException](Config.apiKeyEnvVar("bogus"))

  test("resolveApiKey prefers the environment variable over the fallback key"):
    val config = Config(fallbackKey = Map("openai" -> "fallback-key"))
    val env = Map("OPENAI_KEY" -> "env-key")
    assertEquals(Config.resolveApiKey("openai", config, env.get), Some("env-key"))

  test("resolveApiKey falls back to the configured key when the env var is unset"):
    val config = Config(fallbackKey = Map("openai" -> "fallback-key"))
    assertEquals(Config.resolveApiKey("openai", config, _ => None), Some("fallback-key"))

  test("resolveApiKey falls back to the configured key when the env var is set but empty"):
    val config = Config(fallbackKey = Map("openai" -> "fallback-key"))
    val env = Map("OPENAI_KEY" -> "")
    assertEquals(Config.resolveApiKey("openai", config, env.get), Some("fallback-key"))

  test("resolveApiKey is None when neither the env var nor a fallback key is present"):
    assertEquals(Config.resolveApiKey("openai", Config(), _ => None), None)
