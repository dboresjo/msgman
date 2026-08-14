package msgman

class AiProtocolSpec extends munit.FunSuite:

  private val context = BlockContext(
    projectName = Some("msgman"),
    projectDescription = Some("manages messages files"),
    translationContext = Some("This is a UK tax service."),
    topLevelKey = "site",
    siblingKeys = List(SiblingKey("site.back", "Back", Some("Yn ol")), SiblingKey("site.forward", "Forward", None)),
    masterLanguageCode = "en",
    masterLanguageName = "English",
    targetLanguageCode = "cy",
    targetLanguageName = "Welsh"
  )

  private val request = TranslationRequest(context, List(TranslationTarget("site.change", "Change")), "claude-sonnet-5")

  test("buildPrompt names the source and target languages"):
    val prompt = AiProtocol.buildPrompt(request)
    assert(prompt.contains("English (en)"))
    assert(prompt.contains("Welsh (cy)"))

  test("buildPrompt includes project name and description when present"):
    val prompt = AiProtocol.buildPrompt(request)
    assert(prompt.contains("Project: msgman"))
    assert(prompt.contains("Description: manages messages files"))

  test("buildPrompt omits project name and description when absent"):
    val prompt = AiProtocol.buildPrompt(request.copy(context = context.copy(projectName = None, projectDescription = None)))
    assert(!prompt.contains("Project:"))
    assert(!prompt.contains("Description:"))

  test("buildPrompt includes the translation context when present"):
    val prompt = AiProtocol.buildPrompt(request)
    assert(prompt.contains("This is a UK tax service."))

  test("buildPrompt omits the context section when absent"):
    val prompt = AiProtocol.buildPrompt(request.copy(context = context.copy(translationContext = None)))
    assert(!prompt.contains("Context:"))

  test("buildPrompt lists sibling keys with both master and target text where present"):
    val prompt = AiProtocol.buildPrompt(request)
    assert(prompt.contains("site.back (en) = Back"))
    assert(prompt.contains("site.back (cy) = Yn ol"))
    assert(prompt.contains("site.forward (en) = Forward"))
    assert(!prompt.contains("site.forward (cy)"))

  test("buildPrompt omits the sibling section when there are none"):
    val prompt = AiProtocol.buildPrompt(request.copy(context = context.copy(siblingKeys = Nil)))
    assert(!prompt.contains("Other keys already in this block"))

  test("buildPrompt lists every target key with its master text"):
    val multi = request.copy(targets = List(TranslationTarget("site.change", "Change"), TranslationTarget("site.hello", "Hello {0}")))
    val prompt = AiProtocol.buildPrompt(multi)
    assert(prompt.contains("site.change (en) = Change"))
    assert(prompt.contains("site.hello (en) = Hello {0}"))

  test("parseResponse reads flat key = value lines"):
    assertEquals(AiProtocol.parseResponse("site.change = Newid\nsite.back = Yn ol\n"), Map("site.change" -> "Newid", "site.back" -> "Yn ol"))

  test("parseResponse ignores anything that isn't a key = value line"):
    assertEquals(AiProtocol.parseResponse("Sure, here is the translation:\nsite.change = Newid\n"), Map("site.change" -> "Newid"))
