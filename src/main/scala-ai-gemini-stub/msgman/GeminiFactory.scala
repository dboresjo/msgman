package msgman

// Only compiled in when "gemini" is absent from -Dmsgman.aiProviders, see
// unmanagedSourceDirectories in build.sbt. Never actually invoked: Main only
// calls .instance for providers present in BuildInfo.aiProviders, which this
// stub directory is, by construction, never one of.
object GeminiFactory:
  def instance(apiKey: Option[String]): GeminiTranslator =
    (_: TranslationRequest) => TranslationOutcome.Failure("gemini support is not linked into this build")
