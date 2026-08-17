package msgman

import java.io.File

object Main:

  // Excluded from coverage via coverageExcludedFiles in build.sbt: this just wires
  // Runner to the real process, and calling sys.exit from a test would kill the
  // test runner itself.
  def main(args: Array[String]): Unit =
    val repoUrl = BuildInfo.remoteUrl.flatMap(VersionInfo.parseRemoteUrl)
    val revision = VersionInfo.render(BuildInfo.commitSha, BuildInfo.dirty, repoUrl, BuildInfo.aiProviders)
    val cwd = new File(".")
    val aiConfig = Config.load(cwd)
    val providers: Map[String, Translator] = BuildInfo.aiProviders.map: provider =>
      val apiKey = Config.resolveApiKey(provider, aiConfig, sys.env.get)
      val translator = provider match
        case "openai" => OpenAiFactory.instance(apiKey)
        case "claude" => ClaudeFactory.instance(apiKey)
        case "gemini" => GeminiFactory.instance(apiKey)
      provider -> translator
    .toMap
    val exitCode = Runner.run(args, cwd, System.out, System.err, revision, providers, env = sys.env.get)
    sys.exit(exitCode)
