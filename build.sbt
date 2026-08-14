import scala.scalanative.build._
import scala.sys.process._
import scala.util.Try

// AI providers to link in, driven by the install script's --with-ai flag via
// -Dmsgman.aiProviders=<comma-separated-list>. Empty (the default for a plain
// `sbt nativeLink`) means no provider is linked in and --translate is
// unavailable in the built binary. See TRANSLATION.md.
val validAiProviders = Set("openai", "claude", "gemini")
val aiProviders: Seq[String] = {
  val providers = sys.props
    .get("msgman.aiProviders")
    .map(_.split(",").iterator.map(_.trim).filter(_.nonEmpty).toSeq)
    .getOrElse(Seq.empty)
  providers.find(!validAiProviders.contains(_)).foreach { p =>
    sys.error(s"unknown msgman.aiProviders entry '$p' (expected one of: ${validAiProviders.mkString(", ")})")
  }
  providers
}
val sttpAiVersion = "0.8.0"

lazy val root = (project in file("."))
  .enablePlugins(ScalaNativePlugin)
  .settings(
    name := "msgman",
    version := "0.1.0",
    scalaVersion := "3.3.7",
    Compile / mainClass := Some("msgman.Main"),
    nativeConfig ~= { c =>
      c.withMode(Mode.debug)
    },
    Compile / sourceGenerators += Def.task {
      def quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

      val commitSha = Seq("git", "rev-parse", "HEAD").!!.trim
      val dirty = Seq("git", "status", "--porcelain").!!.trim.nonEmpty
      // origin may be absent (a tarball checkout, a CI job with no remote configured), so this
      // is best-effort: VersionInfo falls back gracefully when it's None.
      val remoteUrl = Try(Seq("git", "remote", "get-url", "origin").!!.trim).toOption.filter(_.nonEmpty)
      val remoteUrlLiteral = remoteUrl match {
        case Some(url) => s"Some(${quote(url)})"
        case None      => "None"
      }

      val aiProvidersLiteral = aiProviders.map(quote).mkString("List(", ", ", ")")

      val file = (Compile / sourceManaged).value / "msgman" / "BuildInfo.scala"
      IO.write(
        file,
        s"""package msgman
           |
           |// Generated at build time by build.sbt, do not edit.
           |object BuildInfo {
           |  val commitSha: String = ${quote(commitSha)}
           |  val dirty: Boolean = $dirty
           |  val remoteUrl: Option[String] = $remoteUrlLiteral
           |  val aiProviders: List[String] = $aiProvidersLiteral
           |}
           |""".stripMargin
      )
      Seq(file)
    }.taskValue,
    libraryDependencies += "org.scalameta" %%% "munit" % "1.3.5" % Test,
    // Only pulled in when the install script's --with-ai flag requested at least
    // one provider. An empty aiProviders means msgman needs nothing beyond libc
    // to run, same as today; see TRANSLATION.md.
    libraryDependencies ++= aiProviders.map(p => "com.softwaremill.sttp.ai" %%% p % sttpAiVersion),
    // Each provider has a real (needs that provider's sttp-ai module) and a stub
    // (no dependency, always fails) source directory defining the same factory
    // object, eg. ClaudeFactory. Only one of the two is compiled in per provider,
    // chosen by whether it's in aiProviders, so Main.scala can reference all three
    // factories unconditionally regardless of which providers were linked in.
    Compile / unmanagedSourceDirectories ++= {
      val base = baseDirectory.value / "src" / "main"
      validAiProviders.toList.sorted.map { p =>
        if (aiProviders.contains(p)) base / s"scala-ai-$p" else base / s"scala-ai-$p-stub"
      }
    },
    // Only needed to satisfy java.security.SecureRandom when coverage instrumentation
    // is active (the `coverage` command), see coverageExcludedFiles below. Its native
    // sources get linked into any binary that merely has it on the classpath, so it is
    // added conditionally rather than as a plain Test dependency: a plain `sbt test`
    // must not need libcrypto at all, only `sbt coverage test` does.
    libraryDependencies ++= {
      if (coverageEnabled.value) Seq("com.github.lolgab" %%% "scala-native-crypto" % "0.4.0" % Test)
      else Seq.empty
    },
    testFrameworks += new TestFramework("munit.Framework"),
    // The `$COVERAGE-OFF$` comment marker only works on Scala 2, so Main (which just
    // wires Runner to the real process and cannot be covered without calling sys.exit
    // from a test) is excluded here instead. File exclusion needs Scala 3.3.4+. The
    // per-provider *Factory objects wrap a live AI provider client and cannot be
    // exercised without a live network call either, see "Test coverage" in
    // TRANSLATION.md; the prompt-building and response-parsing logic they call into
    // (AiProtocol) is provider-agnostic and not excluded, it is covered normally.
    coverageExcludedFiles := ".*BuildInfo.*;.*Main;.*OpenAiFactory;.*ClaudeFactory;.*GeminiFactory",
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked"
    )
  )
