package msgman.sbtplugin

import java.io.File
import java.nio.file.Files
import msgman.{BlockContext, TranslationOutcome, TranslationRequest}

class AiProvidersSpec extends munit.FunSuite:

  private def tempCwd(): File = Files.createTempDirectory("msgman-plugin-ai").toFile

  test("build links in every provider, keyed by name"):
    val providers = AiProviders.build(tempCwd())
    assertEquals(providers.keySet, Set("openai", "claude", "gemini"))

  test("a provider with no configured API key fails without attempting a network call"):
    val providers = AiProviders.build(tempCwd())
    val request = TranslationRequest(
      context = BlockContext(None, None, None, "site", Nil, "en", "English", "cy", "Welsh"),
      targets = Nil,
      model = "unused"
    )
    providers.foreach:
      case (name, translator) =>
        translator.translateBlock(request) match
          case TranslationOutcome.Failure(reason, _) => assert(reason.contains(name))
          case TranslationOutcome.Success(_)         => fail(s"expected $name to fail with no API key configured")
