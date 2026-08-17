package msgman

import sttp.ai.claude.ClaudeSyncClient
import sttp.ai.claude.ClaudeExceptions.ClaudeException
import sttp.ai.claude.config.ClaudeConfig
import sttp.ai.claude.requests.MessageRequest
import sttp.ai.claude.models.{ContentBlock, Message}

// Only compiled in when "claude" is present in -Dmsgman.aiProviders, see
// unmanagedSourceDirectories in build.sbt. Excluded from coverage (see
// coverageExcludedFiles): it wraps a live network call to the Claude API,
// which cannot be exercised in a test. The prompt/response logic it calls
// into (AiProtocol) is provider-agnostic and covered normally.
object ClaudeFactory:

  // Authentication/permission/invalid-request errors are the same on every
  // retry (bad key, no access, or an unsupported/deprecated model), so
  // they're fatal; rate limits, outages and the rest might not be.
  private def isFatal(e: Throwable): Boolean = e match
    case _: ClaudeException.AuthenticationException => true
    case _: ClaudeException.PermissionException     => true
    case _: ClaudeException.InvalidRequestException => true
    case _                                          => false

  def instance(apiKey: Option[String]): ClaudeTranslator = apiKey match
    case None =>
      (_: TranslationRequest) =>
        TranslationOutcome.Failure("no API key configured for provider 'claude'; set ANTHROPIC_API_KEY or claude.fallback-key in .msgman")
    case Some(key) =>
      (request: TranslationRequest) =>
        val client = ClaudeSyncClient(ClaudeConfig(apiKey = key))
        try
          val prompt = AiProtocol.buildPrompt(request)
          val messageRequest =
            MessageRequest.simple(model = request.model, messages = List(Message.user(prompt)), maxTokens = 4096, outputConfig = None)
          val response = client.createMessage(messageRequest)
          val text = response.content.collect { case ContentBlock.Text(t, _, _) => t }.mkString
          TranslationOutcome.Success(TranslationResponse(AiProtocol.parseResponse(text)))
        catch
          case e: Exception =>
            TranslationOutcome.Failure(Option(e.getMessage).getOrElse(e.getClass.getSimpleName), fatal = isFatal(e))
        finally client.close()
