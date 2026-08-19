package msgman

/** Renders the `--revision` output: a link to the repository as it stood at
  * the exact commit the running binary was built from. `commitSha`, `dirty`
  * and the raw `origin` remote URL come from `BuildInfo`, which is generated
  * at compile time by build.sbt from the local git checkout. `aiProviders`
  * also comes from `BuildInfo`; it lists the AI providers linked into this
  * binary via `--with-ai`, and is empty when the binary has no AI support.
  */
object VersionInfo {

  private val sshRemote = "^(?:ssh://)?git@([^:/]+)[:/](.+?)(?:\\.git)?/?$".r
  private val httpRemote = "^https?://(?:[^@/]+@)?([^/]+)/(.+?)(?:\\.git)?/?$".r

  /** Turns a `git remote get-url origin` value (SSH or HTTPS form, with or
    * without a `.git` suffix) into its browsable HTTPS URL, e.g.
    * `git@github.com:owner/repo.git` or `https://github.com/owner/repo.git`
    * both become `https://github.com/owner/repo`. This lets a binary built
    * from a fork link back to that fork rather than a hardcoded upstream
    * repo. Returns `None` if the remote isn't in a recognised form.
    */
  def parseRemoteUrl(remote: String): Option[String] =
    remote.trim match {
      case sshRemote(host, path)  => Some(s"https://$host/$path")
      case httpRemote(host, path) => Some(s"https://$host/$path")
      case _                      => None
    }

  def render(commitSha: String, dirty: Boolean, repoUrl: Option[String], aiProviders: List[String] = Nil): String = {
    val notes = List(
      if (dirty) Some("dirty: built with uncommitted changes") else None,
      if (repoUrl.isEmpty) Some("repository URL unknown: no git remote found at build time") else None
    ).flatten
    val suffix = if (notes.isEmpty) "" else s" (${notes.mkString("; ")})"
    val base = repoUrl match {
      case Some(url) => s"$url/tree/$commitSha"
      case None      => s"commit $commitSha"
    }
    val providers = if (aiProviders.isEmpty) "" else s" [${aiProviders.sorted.mkString(", ")}]"
    s"$base$providers$suffix\n"
  }
}
