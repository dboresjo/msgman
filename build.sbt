import scala.scalanative.build._

lazy val root = (project in file("."))
  .enablePlugins(ScalaNativePlugin)
  .settings(
    name := "msgman",
    version := "0.1.0",
    scalaVersion := "2.13.16",
    Compile / mainClass := Some("msgman.Main"),
    nativeConfig ~= { c =>
      c.withMode(Mode.debug)
    },
    libraryDependencies += "org.scalameta" %%% "munit" % "1.3.5" % Test,
    testFrameworks += new TestFramework("munit.Framework"),
    coverageEnabled := true,
    libraryDependencies += "org.scoverage" %%% "scalac-scoverage-runtime" % coverageScalacPluginVersion.value,
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked"
    )
  )
