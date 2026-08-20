package msgman.sbtplugin

import java.io.File
import msgman.{ClaudeFactory, Config, GeminiFactory, OpenAiFactory, Translator}

/** Builds the `providers` map `--translate` needs, the same way the CLI's
  * `Main` does. Unlike the CLI, which links providers in one at a time at
  * Scala Native link time to keep the binary small (see `--with-ai`), a JVM
  * plugin classpath has no equivalent size cost, so every provider is always
  * linked in here; `.msgman`'s `provider` setting (or the "exactly one
  * linked in" default, see `Runner.resolveTranslation`) picks which one is
  * actually used.
  */
object AiProviders:

  private val names = List("openai", "claude", "gemini")

  def build(cwd: File): Map[String, Translator] =
    val aiConfig = Config.load(cwd)
    names.map { provider =>
      val apiKey = Config.resolveApiKey(provider, aiConfig, sys.env.get)
      val translator = provider match
        case "openai" => OpenAiFactory.instance(apiKey)
        case "claude" => ClaudeFactory.instance(apiKey)
        case "gemini" => GeminiFactory.instance(apiKey)
      provider -> translator
    }.toMap
