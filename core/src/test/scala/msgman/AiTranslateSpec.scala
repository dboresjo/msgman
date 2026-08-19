package msgman

import java.io.File
import java.nio.file.Files
import scala.collection.mutable.ListBuffer

class AiTranslateSpec extends munit.FunSuite {

  test("groupByBlock groups by top-level key and orders blocks and keys canonically") {
    assertEquals(
      AiTranslate.groupByBlock(List("site.change", "phase", "site.back")),
      List("phase" -> List("phase"), "site" -> List("site.back", "site.change"))
    )

  }
  test("groupByBlock on an empty list is empty") {
    assertEquals(AiTranslate.groupByBlock(Nil), Nil)

  }
  private def tempDir(): File = Files.createTempDirectory("msgman-aitranslate").toFile

  private val master = MessagesFile(List(Entry("site.back", "Back"), Entry("site.change", "Change")))

  /** A fake Translator that records every request it receives and answers
    * according to `behaviour`, keyed by the set of sub-keys in the request.
    */
  private class FakeTranslator(behaviour: PartialFunction[Set[String], TranslationOutcome]) extends Translator {
    val requests: ListBuffer[TranslationRequest] = ListBuffer.empty
    def translateBlock(request: TranslationRequest): TranslationOutcome = {
      requests += request
      behaviour.applyOrElse(request.targets.map(_.subKey).toSet, (_: Set[String]) => TranslationOutcome.Failure("unhandled"))
    }
  }

  test("no missing keys: translator is never called and nothing changes") {
    val cwd = tempDir()
    val translator = new FakeTranslator(PartialFunction.empty)
    val target = MessagesFile(List(Entry("site.back", "Yn ol"), Entry("site.change", "Newid")))
    val result = AiTranslate.translate(cwd, Config(), translator, "claude-sonnet-5", stealth = false, master, target, "en", "cy")
    assertEquals(result.entries, Nil)
    assertEquals(result.stillMissing, Nil)
    assertEquals(result.fatal, None)
    assertEquals(translator.requests.size, 0)

  }
  test("a single missing key is translated and tagged with the added-by-msgman comment") {
    val cwd = tempDir()
    val translator = new FakeTranslator({
      case keys if keys == Set("site.change") => TranslationOutcome.Success(TranslationResponse(Map("site.change" -> "Newid")))
    })
    val target = MessagesFile(List(Entry("site.back", "Yn ol")))
    val result = AiTranslate.translate(cwd, Config(), translator, "claude-sonnet-5", stealth = false, master, target, "en", "cy")
    assertEquals(result.entries, List(Entry("site.change", "Newid", List("added by msgman using claude-sonnet-5"))))
    assertEquals(result.stillMissing, Nil)

  }
  test("stealth mode omits the added-by-msgman comment") {
    val cwd = tempDir()
    val translator = new FakeTranslator({
      case keys if keys == Set("site.change") => TranslationOutcome.Success(TranslationResponse(Map("site.change" -> "Newid")))
    })
    val target = MessagesFile(List(Entry("site.back", "Yn ol")))
    val result = AiTranslate.translate(cwd, Config(), translator, "claude-sonnet-5", stealth = true, master, target, "en", "cy")
    assertEquals(result.entries, List(Entry("site.change", "Newid")))

  }
  test("two missing keys in the same block are batched into a single request") {
    val cwd = tempDir()
    val translator = new FakeTranslator({
      case keys if keys == Set("site.back", "site.change") =>
        TranslationOutcome.Success(TranslationResponse(Map("site.back" -> "Yn ol", "site.change" -> "Newid")))
    })
    val target = MessagesFile(Nil)
    val result = AiTranslate.translate(cwd, Config(), translator, "claude-sonnet-5", stealth = false, master, target, "en", "cy")
    assertEquals(result.entries.map(_.key).toSet, Set("site.back", "site.change"))
    assertEquals(result.stillMissing, Nil)
    assertEquals(translator.requests.size, 1)

  }
  test("missing keys in different blocks are sent as separate requests") {
    val cwd = tempDir()
    val master2 = MessagesFile(List(Entry("site.back", "Back"), Entry("phase", "Phase")))
    val translator = new FakeTranslator({
      case keys if keys == Set("site.back") => TranslationOutcome.Success(TranslationResponse(Map("site.back" -> "Yn ol")))
      case keys if keys == Set("phase")     => TranslationOutcome.Success(TranslationResponse(Map("phase" -> "Cyfnod")))
    })
    val target = MessagesFile(Nil)
    val result = AiTranslate.translate(cwd, Config(), translator, "claude-sonnet-5", stealth = false, master2, target, "en", "cy")
    assertEquals(result.entries.map(_.key).toSet, Set("site.back", "phase"))
    assertEquals(result.stillMissing, Nil)
    assertEquals(translator.requests.size, 2)

  }
  test("a request-level failure falls back to one request per key") {
    val cwd = tempDir()
    val translator = new FakeTranslator({
      case keys if keys == Set("site.back", "site.change") => TranslationOutcome.Failure("rate limited")
      case keys if keys == Set("site.back")                => TranslationOutcome.Success(TranslationResponse(Map("site.back" -> "Yn ol")))
      case keys if keys == Set("site.change")               => TranslationOutcome.Success(TranslationResponse(Map("site.change" -> "Newid")))
    })
    val target = MessagesFile(Nil)
    val result = AiTranslate.translate(cwd, Config(), translator, "claude-sonnet-5", stealth = false, master, target, "en", "cy")
    assertEquals(result.entries.map(_.key).toSet, Set("site.back", "site.change"))
    assertEquals(result.stillMissing, Nil)
    assertEquals(translator.requests.size, 3)

  }
  test("a response missing a key falls back to per-key requests for the whole block") {
    val cwd = tempDir()
    val translator = new FakeTranslator({
      case keys if keys == Set("site.back", "site.change") =>
        TranslationOutcome.Success(TranslationResponse(Map("site.back" -> "Yn ol")))
      case keys if keys == Set("site.back")   => TranslationOutcome.Success(TranslationResponse(Map("site.back" -> "Yn ol")))
      case keys if keys == Set("site.change") => TranslationOutcome.Success(TranslationResponse(Map("site.change" -> "Newid")))
    })
    val target = MessagesFile(Nil)
    val result = AiTranslate.translate(cwd, Config(), translator, "claude-sonnet-5", stealth = false, master, target, "en", "cy")
    assertEquals(result.entries.map(_.key).toSet, Set("site.back", "site.change"))
    assertEquals(result.stillMissing, Nil)

  }
  test("a placeholder-token mismatch is rejected and falls back per key") {
    val cwd = tempDir()
    val masterWithPlaceholder = MessagesFile(List(Entry("site.hello", "Hello {0}")))
    val translator = new FakeTranslator({
      case keys if keys == Set("site.hello") => TranslationOutcome.Success(TranslationResponse(Map("site.hello" -> "Bonjour")))
    })
    val target = MessagesFile(Nil)
    val result = AiTranslate.translate(cwd, Config(), translator, "claude-sonnet-5", stealth = false, masterWithPlaceholder, target, "en", "fr")
    assertEquals(result.entries, Nil)
    assertEquals(result.stillMissing.map(_._1), List("site.hello"))
    assert(result.stillMissing.head._2.nonEmpty)

  }
  test("a key still missing after the per-key fallback fails is reported with a reason, without an entry") {
    val cwd = tempDir()
    val translator = new FakeTranslator(PartialFunction.empty) // every call falls through to Failure("unhandled")
    val target = MessagesFile(Nil)
    val result = AiTranslate.translate(cwd, Config(), translator, "claude-sonnet-5", stealth = false, master, target, "en", "cy")
    assertEquals(result.entries, Nil)
    assertEquals(result.stillMissing.map(_._1).toSet, Set("site.back", "site.change"))
    assert(result.stillMissing.forall(_._2 == "unhandled"))

  }
  test("an existing AI-generated translation is left alone, not re-sent") {
    val cwd = tempDir()
    val translator = new FakeTranslator(PartialFunction.empty)
    val target = MessagesFile(
      List(
        Entry("site.back", "Yn ol", List("added by msgman using claude-sonnet-5")),
        Entry("site.change", "Newid", List("added by msgman using claude-sonnet-5"))
      )
    )
    val result = AiTranslate.translate(cwd, Config(), translator, "claude-sonnet-5", stealth = false, master, target, "en", "cy")
    assertEquals(result.entries, Nil)
    assertEquals(result.stillMissing, Nil)
    assertEquals(translator.requests.size, 0)

  }
  test("a placeholder value left by --fix is picked up and translated") {
    val cwd = tempDir()
    val translator = new FakeTranslator({
      case keys if keys == Set("site.change") => TranslationOutcome.Success(TranslationResponse(Map("site.change" -> "Newid")))
    })
    val target = MessagesFile(List(Entry("site.back", "Yn ol"), Entry("site.change", "cy: Change")))
    val result = AiTranslate.translate(cwd, Config(), translator, "claude-sonnet-5", stealth = false, master, target, "en", "cy")
    assertEquals(result.entries, List(Entry("site.change", "Newid", List("added by msgman using claude-sonnet-5"))))
    assertEquals(result.stillMissing, Nil)

  }
  test("the batch request carries the full translation context") {
    val cwd = tempDir()
    val w = new java.io.PrintWriter(new File(cwd, "build.sbt"), "UTF-8")
    try w.print("name := \"msgman\"\n") finally w.close()
    val translator = new FakeTranslator({
      case keys if keys == Set("site.change") => TranslationOutcome.Success(TranslationResponse(Map("site.change" -> "Newid")))
    })
    val target = MessagesFile(List(Entry("site.back", "Yn ol")))
    AiTranslate.translate(cwd, Config(), translator, "claude-sonnet-5", stealth = false, master, target, "en", "cy")
    val request = translator.requests.head
    assertEquals(request.model, "claude-sonnet-5")
    assertEquals(request.context.topLevelKey, "site")
    assertEquals(request.context.projectName, Some("msgman"))
    assertEquals(request.context.siblingKeys, List(SiblingKey("site.back", "Back", Some("Yn ol"))))
    assertEquals(request.context.masterLanguageCode, "en")
    assertEquals(request.context.targetLanguageCode, "cy")
    assertEquals(request.targets, List(TranslationTarget("site.change", "Change")))

  }
  test("a fatal block-level failure stops immediately, no per-key fallback") {
    val cwd = tempDir()
    val translator = new FakeTranslator({
      case keys if keys == Set("site.back", "site.change") => TranslationOutcome.Failure("model no longer available", fatal = true)
    })
    val target = MessagesFile(Nil)
    val result = AiTranslate.translate(cwd, Config(), translator, "claude-sonnet-5", stealth = false, master, target, "en", "cy")
    assertEquals(result.fatal, Some("model no longer available"))
    assertEquals(result.entries, Nil)
    assertEquals(result.stillMissing, Nil)
    assertEquals(translator.requests.size, 1)

  }
  test("a fatal failure in one block discards results from an earlier block and stops further blocks") {
    val cwd = tempDir()
    val masterMultiBlock = MessagesFile(List(Entry("phase", "Phase"), Entry("site.back", "Back")))
    val translator = new FakeTranslator({
      // "phase" sorts before "site", so it is attempted first and fails fatally,
      // meaning the "site" block below must never be attempted at all.
      case keys if keys == Set("phase")    => TranslationOutcome.Failure("model no longer available", fatal = true)
      case keys if keys == Set("site.back") => TranslationOutcome.Success(TranslationResponse(Map("site.back" -> "Yn ol")))
    })
    val target = MessagesFile(Nil)
    val result = AiTranslate.translate(cwd, Config(), translator, "claude-sonnet-5", stealth = false, masterMultiBlock, target, "en", "cy")
    assertEquals(result.fatal, Some("model no longer available"))
    assertEquals(result.entries, Nil)
    assertEquals(result.stillMissing, Nil)
    assertEquals(translator.requests.size, 1)

  }
  test("a fatal per-key retry (after a non-fatal block failure) also stops immediately") {
    val cwd = tempDir()
    val translator = new FakeTranslator({
      case keys if keys == Set("site.back", "site.change") => TranslationOutcome.Failure("response did not include a valid translation for every key")
      case keys if keys == Set("site.back")                => TranslationOutcome.Failure("model no longer available", fatal = true)
    })
    val target = MessagesFile(Nil)
    val result = AiTranslate.translate(cwd, Config(), translator, "claude-sonnet-5", stealth = false, master, target, "en", "cy")
    assertEquals(result.fatal, Some("model no longer available"))
    assertEquals(result.entries, Nil)
    assertEquals(result.stillMissing, Nil)
    // The block attempt, then just the first per-key retry (site.back); site.change is never attempted.
    assertEquals(translator.requests.size, 2)

  }
  test("log defaults to doing nothing") {
    val cwd = tempDir()
    val translator = new FakeTranslator({
      case keys if keys == Set("site.change") => TranslationOutcome.Success(TranslationResponse(Map("site.change" -> "Newid")))
    })
    val target = MessagesFile(List(Entry("site.back", "Yn ol")))
    // Just exercises the default log parameter; nothing to assert beyond not throwing.
    AiTranslate.translate(cwd, Config(), translator, "claude-sonnet-5", stealth = false, master, target, "en", "cy")

  }
  test("log reports a request before and its response after, for a successful call") {
    val cwd = tempDir()
    val translator = new FakeTranslator({
      case keys if keys == Set("site.change") => TranslationOutcome.Success(TranslationResponse(Map("site.change" -> "Newid")))
    })
    val target = MessagesFile(List(Entry("site.back", "Yn ol")))
    val logged = ListBuffer.empty[String]
    AiTranslate.translate(cwd, Config(), translator, "claude-sonnet-5", stealth = false, master, target, "en", "cy", (s => logged.append(s)))
    assertEquals(
      logged.toList,
      List(
        "[cy] requesting translation of site (change) from claude-sonnet-5",
        "[cy] received translation of site: change = Newid"
      )
    )

  }
  test("log strips the block's own key prefix even when a sub-key has further dots") {
    val cwd = tempDir()
    val nestedMaster = MessagesFile(List(Entry("PartnerDetails.additionalAddressInfoYesNo.hint", "Hint text")))
    val translator = new FakeTranslator({
      case keys if keys == Set("PartnerDetails.additionalAddressInfoYesNo.hint") =>
        TranslationOutcome.Success(TranslationResponse(Map("PartnerDetails.additionalAddressInfoYesNo.hint" -> "Testun awgrym")))
    })
    val target = MessagesFile(Nil)
    val logged = ListBuffer.empty[String]
    AiTranslate.translate(cwd, Config(), translator, "gemini-3.6-flash", stealth = false, nestedMaster, target, "en", "cy", (s => logged.append(s)))
    assertEquals(
      logged.toList,
      List(
        "[cy] requesting translation of PartnerDetails (additionalAddressInfoYesNo.hint) from gemini-3.6-flash",
        "[cy] received translation of PartnerDetails: additionalAddressInfoYesNo.hint = Testun awgrym"
      )
    )

  }
  test("log reports the failure reason for a failed call, and its per-key retry") {
    val cwd = tempDir()
    val translator = new FakeTranslator(PartialFunction.empty)
    val target = MessagesFile(List(Entry("site.back", "Yn ol")))
    val logged = ListBuffer.empty[String]
    AiTranslate.translate(cwd, Config(), translator, "claude-sonnet-5", stealth = false, master, target, "en", "cy", (s => logged.append(s)))
    assertEquals(
      logged.toList,
      List(
        "[cy] requesting translation of site (change) from claude-sonnet-5",
        "[cy] translation of site failed: unhandled",
        "[cy] requesting translation of site (change) from claude-sonnet-5",
        "[cy] translation of site failed: unhandled"
      )
    )

  }
  test("log reports both the failed batch request and the successful per-key retries") {
    val cwd = tempDir()
    val translator = new FakeTranslator({
      case keys if keys == Set("site.back", "site.change") => TranslationOutcome.Failure("rate limited")
      case keys if keys == Set("site.back")                => TranslationOutcome.Success(TranslationResponse(Map("site.back" -> "Yn ol")))
      case keys if keys == Set("site.change")               => TranslationOutcome.Success(TranslationResponse(Map("site.change" -> "Newid")))
    })
    val target = MessagesFile(Nil)
    val logged = ListBuffer.empty[String]
    AiTranslate.translate(cwd, Config(), translator, "claude-sonnet-5", stealth = false, master, target, "en", "cy", (s => logged.append(s)))
    assertEquals(
      logged.toList,
      List(
        "[cy] requesting translation of site (back, change) from claude-sonnet-5",
        "[cy] translation of site failed: rate limited",
        "[cy] requesting translation of site (back) from claude-sonnet-5",
        "[cy] received translation of site: back = Yn ol",
        "[cy] requesting translation of site (change) from claude-sonnet-5",
        "[cy] received translation of site: change = Newid"
      )
    )
  }
}
