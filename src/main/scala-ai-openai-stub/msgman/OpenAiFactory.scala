package msgman

// Only compiled in when "openai" is absent from -Dmsgman.aiProviders, see
// unmanagedSourceDirectories in build.sbt. Never actually invoked: Main only
// calls .instance for providers present in BuildInfo.aiProviders, which this
// stub directory is, by construction, never one of.
object OpenAiFactory:
  def instance(apiKey: Option[String]): OpenAiTranslator =
    (_: TranslationRequest) => TranslationOutcome.Failure("openai support is not linked into this build")
