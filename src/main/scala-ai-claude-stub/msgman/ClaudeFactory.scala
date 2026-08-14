package msgman

// Only compiled in when "claude" is absent from -Dmsgman.aiProviders, see
// unmanagedSourceDirectories in build.sbt. Never actually invoked: Main only
// calls .instance for providers present in BuildInfo.aiProviders, which this
// stub directory is, by construction, never one of.
object ClaudeFactory:
  def instance(apiKey: Option[String]): ClaudeTranslator =
    (_: TranslationRequest) => TranslationOutcome.Failure("claude support is not linked into this build")
