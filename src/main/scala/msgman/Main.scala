package msgman

object Main {

  // $COVERAGE-OFF$
  // Excluded from coverage: this just wires Runner to the real process, and
  // calling sys.exit from a test would kill the test runner itself.
  def main(args: Array[String]): Unit = {
    val repoUrl = BuildInfo.remoteUrl.flatMap(VersionInfo.parseRemoteUrl)
    val revision = VersionInfo.render(BuildInfo.commitSha, BuildInfo.dirty, repoUrl)
    val exitCode = Runner.run(args, new java.io.File("."), System.out, System.err, revision)
    sys.exit(exitCode)
  }
  // $COVERAGE-ON$
}
