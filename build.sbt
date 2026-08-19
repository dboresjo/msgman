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
  // Fans out `test`/`compile` etc. to the plugin project too, so a bare `sbt
  // test` (as run in CI, see .github/workflows/scala.yml) covers sbt-msgman's
  // own tests as well, not just the CLI's.
  .aggregate(plugin)
  .settings(
    name := "msgman",
    version := "0.1.0",
    scalaVersion := "3.3.7",
    Compile / mainClass := Some("msgman.Main"),
    nativeConfig ~= { c =>
      c.withMode(Mode.debug)
    },
    // The CLI-agnostic parsing/sorting/translation logic lives under core/, shared
    // with the sbt plugin (see plugin/), which compiles the very same source files
    // as a JVM build rather than depending on this project's Native artifact
    // directly (Scala Native binaries aren't consumable as a plain JVM dependency).
    Compile / unmanagedSourceDirectories += baseDirectory.value / "core" / "src" / "main" / "scala",
    Test / unmanagedSourceDirectories += baseDirectory.value / "core" / "src" / "test" / "scala",
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
    // Unlike the sbt-2.x mainline, sbt 1.x's ScalaNativePlugin does not auto-suffix
    // a plain %% dependency to the native-cross-versioned artifact id (eg.
    // scala-native-crypto is only ever published as .._native0.5_3, never a plain
    // .._3), so %%% (still needed under sbt 1.x) is kept here rather than the %%
    // used on main.
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
    ),
    // The CLI is a Scala Native binary, not a JVM library, and must never be
    // pushed to Maven Central; only plugin/ (sbt-msgman) is published there.
    publish / skip := true,
    publishArtifact := false
  )

// Distributes the same format/verify logic as an sbt plugin (sbt-msgman), for
// projects that would rather run it as a build task than install the CLI
// separately. This is the frozen sbt-1.x snapshot: core/'s syntax was rewritten
// to a dialect valid under both Scala 2.12 and 3.3.7 so it could be shared
// unchanged with the mainline's sbt-2.x plugin build (see the branch's own
// history for that rewrite); this branch is not intended to be merged back.
lazy val plugin = (project in file("plugin"))
  .settings(
    organization := "io.github.dboresjo",
    name := "sbt-msgman",
    // Overridden from the pushed tag by the publish-plugin CI job (see
    // sbt1-release.yml), the same way the CLI's own version comes from
    // GITHUB_REF_NAME rather than this hardcoded fallback; this default is
    // only ever seen locally (publishLocal, sbt shell), never in a real
    // Central Portal publish.
    version := sys.env.getOrElse("MSGMAN_PLUGIN_VERSION", "0.1.0"),
    // Must match the Scala version sbt 1.x itself runs on: plugin classes are
    // loaded straight into sbt's own running JVM process/classloader.
    scalaVersion := "2.12.20",
    sbtPlugin := true,
    Compile / unmanagedSourceDirectories += (ThisBuild / baseDirectory).value / "core" / "src" / "main" / "scala",
    Test / unmanagedSourceDirectories += (ThisBuild / baseDirectory).value / "core" / "src" / "test" / "scala",
    libraryDependencies += "org.scalameta" %% "munit" % "1.3.5" % Test,
    testFrameworks += new TestFramework("munit.Framework"),
    // Central Portal publish metadata (io.github.dboresjo namespace, verified via
    // GitHub OAuth). See https://www.scala-sbt.org/2.x/docs/en/recipes/central.html.
    organizationName := "dboresjo",
    organizationHomepage := Some(url("https://github.com/dboresjo")),
    scmInfo := Some(
      ScmInfo(url("https://github.com/dboresjo/msgman"), "scm:git@github.com:dboresjo/msgman.git")
    ),
    developers := List(
      Developer(
        id = "dboresjo",
        name = "Dan Boresjö",
        email = "6451217+dboresjo@users.noreply.github.com",
        url = url("https://github.com/dboresjo")
      )
    ),
    description := "sbt plugin distributing msgman's format/verify tasks as in-process build tasks, " +
      "for projects that would rather not install the msgman CLI separately.",
    licenses := List(License.Apache2),
    homepage := Some(url("https://github.com/dboresjo/msgman")),
    versionScheme := Some("early-semver"),
    pomIncludeRepository := { _ => false },
    publishMavenStyle := true,
    publishTo := {
      val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
      if (version.value.endsWith("-SNAPSHOT")) Some("central-snapshots" at centralSnapshots)
      else localStaging.value
    },
    // MsgmanPlugin's own Def.task wiring can't be exercised without a live sbt
    // task-graph harness, the same reasoning that excludes root's Main; the
    // pure argument-building/exit-code logic it calls into (MsgmanTasks) is not
    // excluded and is covered normally by MsgmanTasksSpec.
    coverageExcludedFiles := ".*MsgmanPlugin;.*NoTranslation",
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked"
    )
  )
