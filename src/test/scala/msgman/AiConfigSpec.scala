package msgman

import java.io.File
import java.nio.file.Files

class AiConfigSpec extends munit.FunSuite:

  test("parseFile reads flat key = value lines"):
    assertEquals(AiConfig.parseFile("provider = openai\nstealth = true\n"), Map("provider" -> "openai", "stealth" -> "true"))

  test("parseFile ignores comments and blank lines"):
    assertEquals(AiConfig.parseFile("# a comment\n\nprovider = openai\n"), Map("provider" -> "openai"))

  test("parseFile trims whitespace around key and value"):
    assertEquals(AiConfig.parseFile("  provider   =   openai  \n"), Map("provider" -> "openai"))

  test("parseFile ignores a line with no '='"):
    assertEquals(AiConfig.parseFile("not a valid line\nprovider = openai\n"), Map("provider" -> "openai"))

  test("parseFile reads dotted per-provider keys"):
    assertEquals(AiConfig.parseFile("openai.model = gpt-5\n"), Map("openai.model" -> "gpt-5"))

  test("merge: most local file wins per key"):
    val local = Map("provider" -> "openai")
    val home = Map("provider" -> "claude", "stealth" -> "true")
    assertEquals(AiConfig.merge(List(local, home)), Map("provider" -> "openai", "stealth" -> "true"))

  test("merge with no files is empty"):
    assertEquals(AiConfig.merge(Nil), Map.empty[String, String])

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
      AiConfig.fromSettings(settings),
      AiConfig(
        provider = Some("claude"),
        model = Map("claude" -> "claude-sonnet-5", "openai" -> "gpt-5"),
        fallbackKey = Map("claude" -> "sk-fallback"),
        stealth = true,
        translationContext = Some("docs/CONTEXT.md")
      )
    )

  test("fromSettings defaults when nothing is set"):
    assertEquals(AiConfig.fromSettings(Map.empty), AiConfig())

  test("fromSettings treats any non-'true' stealth value as false"):
    assertEquals(AiConfig.fromSettings(Map("stealth" -> "yes")).stealth, false)

  private def tempDir(): File = Files.createTempDirectory("msgman-aiconfig").toFile

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
    val config = AiConfig.load(cwd, home, etc)
    assertEquals(config, AiConfig(provider = Some("openai"), stealth = true, model = Map("openai" -> "gpt-5")))

  test("load treats a missing location as absent, not an error"):
    val cwd = tempDir()
    val home = tempDir()
    val etc = tempDir()
    write(cwd, ".msgman", "provider = openai\n")
    assertEquals(AiConfig.load(cwd, home, etc), AiConfig(provider = Some("openai")))

  test("load with no config files anywhere returns defaults"):
    val cwd = tempDir()
    val home = tempDir()
    val etc = tempDir()
    assertEquals(AiConfig.load(cwd, home, etc), AiConfig())

  test("load(cwd) convenience overload uses the real HOME and /etc"):
    val cwd = tempDir()
    // Just exercises the delegation; real HOME/etc are not expected to hold
    // a .msgman file for the value asserted here to be meaningful, but the
    // call must not throw and cwd's own file must still be picked up.
    write(cwd, ".msgman", "provider = openai\n")
    assertEquals(AiConfig.load(cwd).provider, Some("openai"))

  test("apiKeyEnvVar returns each provider's own convention"):
    assertEquals(AiConfig.apiKeyEnvVar("openai"), "OPENAI_KEY")
    assertEquals(AiConfig.apiKeyEnvVar("claude"), "ANTHROPIC_API_KEY")
    assertEquals(AiConfig.apiKeyEnvVar("gemini"), "GEMINI_API_KEY")

  test("apiKeyEnvVar rejects an unknown provider"):
    intercept[IllegalArgumentException](AiConfig.apiKeyEnvVar("bogus"))

  test("resolveApiKey prefers the environment variable over the fallback key"):
    val config = AiConfig(fallbackKey = Map("openai" -> "fallback-key"))
    val env = Map("OPENAI_KEY" -> "env-key")
    assertEquals(AiConfig.resolveApiKey("openai", config, env.get), Some("env-key"))

  test("resolveApiKey falls back to the configured key when the env var is unset"):
    val config = AiConfig(fallbackKey = Map("openai" -> "fallback-key"))
    assertEquals(AiConfig.resolveApiKey("openai", config, _ => None), Some("fallback-key"))

  test("resolveApiKey falls back to the configured key when the env var is set but empty"):
    val config = AiConfig(fallbackKey = Map("openai" -> "fallback-key"))
    val env = Map("OPENAI_KEY" -> "")
    assertEquals(AiConfig.resolveApiKey("openai", config, env.get), Some("fallback-key"))

  test("resolveApiKey is None when neither the env var nor a fallback key is present"):
    assertEquals(AiConfig.resolveApiKey("openai", AiConfig(), _ => None), None)
