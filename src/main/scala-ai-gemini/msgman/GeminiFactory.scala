package msgman

import sttp.ai.gemini.GeminiSyncClient
import sttp.ai.gemini.GeminiExceptions.GeminiException
import sttp.ai.gemini.config.GeminiConfig
import sttp.ai.gemini.requests.InteractionRequest

// Only compiled in when "gemini" is present in -Dmsgman.aiProviders, see
// unmanagedSourceDirectories in build.sbt. Excluded from coverage (see
// coverageExcludedFiles): it wraps a live network call to the Gemini API,
// which cannot be exercised in a test. The prompt/response logic it calls
// into (AiProtocol) is provider-agnostic and covered normally.
object GeminiFactory:

  // Authentication/permission/invalid-request/not-found errors are the same
  // on every retry (bad key, no access, or an unsupported/deprecated model,
  // eg. "gemini-2.5-flash is no longer available to new users"), so they're
  // fatal; rate limits, outages and the rest might not be.
  private def isFatal(e: Throwable): Boolean = e match
    case _: GeminiException.AuthenticationException => true
    case _: GeminiException.PermissionException     => true
    case _: GeminiException.InvalidRequestException => true
    case _: GeminiException.NotFoundException       => true
    case _                                          => false

  def instance(apiKey: Option[String]): GeminiTranslator = apiKey match
    case None =>
      (_: TranslationRequest) =>
        TranslationOutcome.Failure("no API key configured for provider 'gemini'; set GEMINI_API_KEY or gemini.fallback-key in .msgman")
    case Some(key) =>
      (request: TranslationRequest) =>
        try
          val gemini = GeminiSyncClient(GeminiConfig(apiKey = key))
          val prompt = AiProtocol.buildPrompt(request)
          val geminiRequest = InteractionRequest.simple(model = request.model, text = prompt)
          val response = gemini.createInteraction(geminiRequest)
          TranslationOutcome.Success(TranslationResponse(AiProtocol.parseResponse(response.outputText)))
        catch
          case e: Exception =>
            TranslationOutcome.Failure(Option(e.getMessage).getOrElse(e.getClass.getSimpleName), fatal = isFatal(e))
