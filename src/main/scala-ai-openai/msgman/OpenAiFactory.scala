package msgman

import sttp.ai.openai.OpenAISyncClient
import sttp.ai.openai.OpenAIExceptions.OpenAIException
import sttp.ai.openai.requests.completions.chat.ChatRequestBody.{ChatBody, ChatCompletionModel}
import sttp.ai.openai.requests.completions.chat.message.{Content, Message}

// Only compiled in when "openai" is present in -Dmsgman.aiProviders, see
// unmanagedSourceDirectories in build.sbt. Excluded from coverage (see
// coverageExcludedFiles): it wraps a live network call to the OpenAI API,
// which cannot be exercised in a test. The prompt/response logic it calls
// into (AiProtocol) is provider-agnostic and covered normally.
object OpenAiFactory:

  // Authentication/permission/invalid-request errors are the same on every
  // retry (bad key, no access, or an unsupported/deprecated model), so
  // they're fatal; rate limits, outages and the rest might not be.
  private def isFatal(e: Throwable): Boolean = e match
    case _: OpenAIException.AuthenticationException => true
    case _: OpenAIException.PermissionException     => true
    case _: OpenAIException.InvalidRequestException => true
    case _                                          => false

  def instance(apiKey: Option[String]): OpenAiTranslator = apiKey match
    case None =>
      (_: TranslationRequest) =>
        TranslationOutcome.Failure("no API key configured for provider 'openai'; set OPENAI_KEY or openai.fallback-key in .msgman")
    case Some(key) =>
      (request: TranslationRequest) =>
        try
          val openAi = OpenAISyncClient(key)
          val prompt = AiProtocol.buildPrompt(request)
          val chatBody = ChatBody(
            model = ChatCompletionModel.CustomChatCompletionModel(request.model),
            messages = Seq(Message.User(content = Content.TextContent(prompt)))
          )
          val response = openAi.createChatCompletion(chatBody)
          val text = response.choices.flatMap(_.message.content).mkString
          TranslationOutcome.Success(TranslationResponse(AiProtocol.parseResponse(text)))
        catch
          case e: Exception =>
            TranslationOutcome.Failure(Option(e.getMessage).getOrElse(e.getClass.getSimpleName), fatal = isFatal(e))
