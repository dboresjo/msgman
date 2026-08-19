package msgman

class VersionInfoSpec extends munit.FunSuite {

  test("parseRemoteUrl accepts the scp-like SSH form") {
    assertEquals(VersionInfo.parseRemoteUrl("git@github.com:dboresjo/msgman.git"), Some("https://github.com/dboresjo/msgman"))

  }
  test("parseRemoteUrl accepts the explicit ssh:// form") {
    assertEquals(VersionInfo.parseRemoteUrl("ssh://git@github.com/dboresjo/msgman.git"), Some("https://github.com/dboresjo/msgman"))

  }
  test("parseRemoteUrl accepts an https remote with a .git suffix") {
    assertEquals(VersionInfo.parseRemoteUrl("https://github.com/dboresjo/msgman.git"), Some("https://github.com/dboresjo/msgman"))

  }
  test("parseRemoteUrl accepts an https remote without a .git suffix") {
    assertEquals(VersionInfo.parseRemoteUrl("https://github.com/dboresjo/msgman"), Some("https://github.com/dboresjo/msgman"))

  }
  test("parseRemoteUrl accepts an https remote with a trailing slash") {
    assertEquals(VersionInfo.parseRemoteUrl("https://github.com/dboresjo/msgman/"), Some("https://github.com/dboresjo/msgman"))

  }
  test("parseRemoteUrl accepts an https remote with embedded userinfo") {
    assertEquals(VersionInfo.parseRemoteUrl("https://user@github.com/dboresjo/msgman.git"), Some("https://github.com/dboresjo/msgman"))

  }
  test("parseRemoteUrl reflects a fork's own owner and repo, not a hardcoded upstream") {
    assertEquals(VersionInfo.parseRemoteUrl("git@github.com:someoneelse/msgman-fork.git"), Some("https://github.com/someoneelse/msgman-fork"))

  }
  test("parseRemoteUrl rejects an unrecognised remote") {
    assertEquals(VersionInfo.parseRemoteUrl("/home/dan/bare-repos/msgman.git"), None)
    assertEquals(VersionInfo.parseRemoteUrl(""), None)

  }
  test("render prints the tree URL for a clean build with a known remote") {
    assertEquals(
      VersionInfo.render("abc123", dirty = false, repoUrl = Some("https://github.com/dboresjo/msgman")),
      "https://github.com/dboresjo/msgman/tree/abc123\n"
    )

  }
  test("render flags a dirty build with a known remote") {
    assertEquals(
      VersionInfo.render("abc123", dirty = true, repoUrl = Some("https://github.com/dboresjo/msgman")),
      "https://github.com/dboresjo/msgman/tree/abc123 (dirty: built with uncommitted changes)\n"
    )

  }
  test("render falls back to the bare commit when no remote was found at build time") {
    assertEquals(
      VersionInfo.render("abc123", dirty = false, repoUrl = None),
      "commit abc123 (repository URL unknown: no git remote found at build time)\n"
    )

  }
  test("render combines the dirty and unknown-remote notes") {
    assertEquals(
      VersionInfo.render("abc123", dirty = true, repoUrl = None),
      "commit abc123 (dirty: built with uncommitted changes; repository URL unknown: no git remote found at build time)\n"
    )

  }
  test("render omits the AI providers bracket when the binary has no AI support") {
    assertEquals(
      VersionInfo.render("abc123", dirty = false, repoUrl = Some("https://github.com/dboresjo/msgman"), aiProviders = Nil),
      "https://github.com/dboresjo/msgman/tree/abc123\n"
    )

  }
  test("render lists the linked-in AI providers in brackets, sorted, after the revision url") {
    assertEquals(
      VersionInfo.render("abc123", dirty = false, repoUrl = Some("https://github.com/dboresjo/msgman"), aiProviders = List("openai", "claude")),
      "https://github.com/dboresjo/msgman/tree/abc123 [claude, openai]\n"
    )

  }
  test("render places the AI providers bracket before the dirty/unknown-remote notes") {
    assertEquals(
      VersionInfo.render("abc123", dirty = true, repoUrl = None, aiProviders = List("gemini")),
      "commit abc123 [gemini] (dirty: built with uncommitted changes; repository URL unknown: no git remote found at build time)\n"
    )
  }
}
