package msgman

import java.io.File
import java.nio.file.Files

class TranslationContextSpec extends munit.FunSuite:

  private def tempDir(): File = Files.createTempDirectory("msgman-context").toFile

  private def write(dir: File, name: String, content: String): Unit =
    val f = new File(dir, name)
    f.getParentFile.mkdirs()
    val w = new java.io.PrintWriter(f, "UTF-8")
    try w.print(content) finally w.close()

  test("projectName reads the name := setting"):
    assertEquals(TranslationContext.projectName("name := \"msgman\"\nversion := \"0.1.0\"\n"), Some("msgman"))

  test("projectName is None when there is no name setting"):
    assertEquals(TranslationContext.projectName("version := \"0.1.0\"\n"), None)

  test("projectDescription reads the description := setting"):
    assertEquals(TranslationContext.projectDescription("description := \"a tool\"\n"), Some("a tool"))

  test("projectDescription is None when there is no description setting"):
    assertEquals(TranslationContext.projectDescription("name := \"msgman\"\n"), None)

  test("translationContextFile defaults to TRANSLATION-CONTEXT.md in cwd"):
    val cwd = tempDir()
    assertEquals(TranslationContext.translationContextFile(cwd, None), new File(cwd, "TRANSLATION-CONTEXT.md"))

  test("translationContextFile resolves a relative override against cwd"):
    val cwd = tempDir()
    assertEquals(TranslationContext.translationContextFile(cwd, Some("docs/CONTEXT.md")), new File(cwd, "docs/CONTEXT.md"))

  test("translationContextFile respects an absolute override"):
    val cwd = tempDir()
    assertEquals(TranslationContext.translationContextFile(cwd, Some("/etc/msgman-context.md")), new File("/etc/msgman-context.md"))

  test("readIfPresent reads an existing file"):
    val dir = tempDir()
    write(dir, "note.md", "hello")
    assertEquals(TranslationContext.readIfPresent(new File(dir, "note.md")), Some("hello"))

  test("readIfPresent is None for a missing file"):
    val dir = tempDir()
    assertEquals(TranslationContext.readIfPresent(new File(dir, "missing.md")), None)

  private val master = MessagesFile(List(Entry("site.back", "Back"), Entry("site.change", "Change"), Entry("phase", "Phase")))
  private val target = MessagesFile(List(Entry("site.back", "Yn ol")))

  test("siblingKeys includes only same-block keys, excluding the batch's own targets"):
    val siblings = TranslationContext.siblingKeys(master, target, "site", excludeKeys = Set("site.change"))
    assertEquals(siblings, List(SiblingKey("site.back", "Back", Some("Yn ol"))))

  test("siblingKeys marks a sibling with no existing translation as None"):
    val siblings = TranslationContext.siblingKeys(master, MessagesFile(Nil), "site", excludeKeys = Set("site.change"))
    assertEquals(siblings, List(SiblingKey("site.back", "Back", None)))

  test("build assembles the full block context"):
    val cwd = tempDir()
    write(cwd, "build.sbt", "name := \"msgman\"\ndescription := \"manages messages files\"\n")
    write(cwd, "TRANSLATION-CONTEXT.md", "This is a UK tax service.")
    val context = TranslationContext.build(cwd, AiConfig(), master, target, "site", Set("site.change"), "en", "cy")
    assertEquals(
      context,
      BlockContext(
        projectName = Some("msgman"),
        projectDescription = Some("manages messages files"),
        translationContext = Some("This is a UK tax service."),
        topLevelKey = "site",
        siblingKeys = List(SiblingKey("site.back", "Back", Some("Yn ol"))),
        masterLanguageCode = "en",
        masterLanguageName = "English",
        targetLanguageCode = "cy",
        targetLanguageName = "Welsh"
      )
    )

  test("build tolerates a missing build.sbt and TRANSLATION-CONTEXT.md"):
    val cwd = tempDir()
    val context = TranslationContext.build(cwd, AiConfig(), master, target, "site", Set("site.change"), "en", "cy")
    assertEquals(context.projectName, None)
    assertEquals(context.projectDescription, None)
    assertEquals(context.translationContext, None)

  test("build honours a configured translation-context override"):
    val cwd = tempDir()
    write(cwd, "docs/CONTEXT.md", "custom context")
    val context = TranslationContext.build(
      cwd,
      AiConfig(translationContext = Some("docs/CONTEXT.md")),
      master,
      target,
      "site",
      Set("site.change"),
      "en",
      "cy"
    )
    assertEquals(context.translationContext, Some("custom context"))

class LanguagesSpec extends munit.FunSuite:

  test("name returns the full English name for a known code"):
    assertEquals(Languages.name("cy"), "Welsh")

  test("name falls back to the bare code for an unknown one"):
    assertEquals(Languages.name("xx"), "xx")
