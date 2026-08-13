package msgman

class TranslationsSpec extends munit.FunSuite:

  test("isPlaceholder recognises a two-letter language code prefix"):
    assert(Translations.isPlaceholder("en: Some text"))

  test("isPlaceholder recognises a three-letter language code prefix"):
    assert(Translations.isPlaceholder("cym: Some text"))

  test("isPlaceholder rejects ordinary text"):
    assert(!Translations.isPlaceholder("Some text"))

  test("isPlaceholder requires the colon to be followed by whitespace"):
    assert(!Translations.isPlaceholder("en:Some text"))

  test("isPlaceholder only matches at the start of the value"):
    assert(!Translations.isPlaceholder("Some en: text"))

  private val master = MessagesFile(List(Entry("site.back", "Back"), Entry("site.change", "Change")))

  test("findMissing reports a key entirely absent from another language file"):
    val cy = MessagesFile(List(Entry("site.back", "Yn ol")))
    val missing = Translations.findMissing(master, Map("cy" -> cy), strict = false)
    assertEquals(missing, List(Translations.Missing("cy", "site.change")))

  test("findMissing reports nothing when every key is translated"):
    val cy = MessagesFile(List(Entry("site.back", "Yn ol"), Entry("site.change", "Newid")))
    assertEquals(Translations.findMissing(master, Map("cy" -> cy), strict = false), Nil)

  test("findMissing does not flag a placeholder value when not strict"):
    val cy = MessagesFile(List(Entry("site.back", "en: Back"), Entry("site.change", "Newid")))
    assertEquals(Translations.findMissing(master, Map("cy" -> cy), strict = false), Nil)

  test("findMissing in strict mode also flags placeholder values as missing"):
    val cy = MessagesFile(List(Entry("site.back", "en: Back"), Entry("site.change", "Newid")))
    assertEquals(
      Translations.findMissing(master, Map("cy" -> cy), strict = true),
      List(Translations.Missing("cy", "site.back"))
    )

  test("findMissing orders results by language code then by canonical key order"):
    val cy = MessagesFile(Nil)
    val fr = MessagesFile(Nil)
    val missing = Translations.findMissing(master, Map("fr" -> fr, "cy" -> cy), strict = false)
    assertEquals(
      missing,
      List(
        Translations.Missing("cy", "site.back"),
        Translations.Missing("cy", "site.change"),
        Translations.Missing("fr", "site.back"),
        Translations.Missing("fr", "site.change")
      )
    )

  test("findExtra reports a key present in a translation but absent from the master"):
    val cy = MessagesFile(List(Entry("site.back", "Yn ol"), Entry("site.orphan", "Rhywbeth")))
    assertEquals(Translations.findExtra(master, Map("cy" -> cy)), List(Translations.Extra("cy", "site.orphan")))

  test("findExtra reports nothing when every key exists in the master"):
    val cy = MessagesFile(List(Entry("site.back", "Yn ol")))
    assertEquals(Translations.findExtra(master, Map("cy" -> cy)), Nil)

  test("findExtra orders results by language code then by canonical key order"):
    val cy = MessagesFile(List(Entry("zzz.orphan", "a"), Entry("aaa.orphan", "b")))
    val fr = MessagesFile(List(Entry("fr.orphan", "c")))
    assertEquals(
      Translations.findExtra(master, Map("fr" -> fr, "cy" -> cy)),
      List(
        Translations.Extra("cy", "aaa.orphan"),
        Translations.Extra("cy", "zzz.orphan"),
        Translations.Extra("fr", "fr.orphan")
      )
    )
