addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.12")
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.4.4")
// Signs the sbt-msgman plugin's published artifacts for Central Portal. Only
// plugin/ actually publishes (see publish/skip on root in build.sbt), but the
// plugin declaration itself is build-wide, sbt has no per-project plugins.sbt.
addSbtPlugin("com.github.sbt" % "sbt-pgp" % "2.3.1")
