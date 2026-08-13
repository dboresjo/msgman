import scala.scalanative.build._
import scala.sys.process._
import scala.util.Try

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
           |}
           |""".stripMargin
      )
      Seq(file)
    }.taskValue,
    libraryDependencies += "org.scalameta" %%% "munit" % "1.3.5" % Test,
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
    // from a test) is excluded here instead. File exclusion needs Scala 3.3.4+.
    coverageExcludedFiles := ".*BuildInfo.*;.*Main",
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked"
    )
  )
