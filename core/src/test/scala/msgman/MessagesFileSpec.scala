package msgman

class MessagesFileSpec extends munit.FunSuite {

  test("parse reads simple key = value lines") {
    val parsed = MessagesFile.parse("site.back = Back\nsite.change = Change\n")
    assertEquals(parsed.entries, List(Entry("site.back", "Back"), Entry("site.change", "Change")))
    assertEquals(parsed.blockComments, Map.empty[String, List[String]])
    assertEquals(parsed.trailer, Nil)

  }
  test("parse trims whitespace around key and value") {
    val parsed = MessagesFile.parse("datePart.day   =   diwrnod  \n")
    assertEquals(parsed.entries, List(Entry("datePart.day", "diwrnod")))

  }
  test("parse allows an empty value") {
    val parsed = MessagesFile.parse("site.empty =\n")
    assertEquals(parsed.entries, List(Entry("site.empty", "")))

  }
  test("parse allows '=' characters within the value") {
    val parsed = MessagesFile.parse("site.equation = a = b\n")
    assertEquals(parsed.entries, List(Entry("site.equation", "a = b")))

  }
  test("parse ignores blank lines between entries") {
    val parsed = MessagesFile.parse("site.back = Back\n\nsite.change = Change\n")
    assertEquals(parsed.entries, List(Entry("site.back", "Back"), Entry("site.change", "Change")))

  }
  test("parse rejects a line without '='") {
    intercept[MessagesFileParseException] {
      MessagesFile.parse("not a valid line\n")

    }
  }
  test("parse rejects a line with an empty key") {
    intercept[MessagesFileParseException] {
      MessagesFile.parse(" = value with no key\n")

    }
  }
  test("parse allows a key to appear more than once, keeping every occurrence") {
    val parsed = MessagesFile.parse("site.back = Back\nsite.back = Again\n")
    assertEquals(parsed.entries, List(Entry("site.back", "Back"), Entry("site.back", "Again")))

  }
  // --- duplicate keys --------------------------------------------------

  test("duplicates finds a key that appears more than once") {
    val file = MessagesFile.parse("site.back = Back\nsite.back = Back\nsite.change = Change\n")
    assertEquals(MessagesFile.duplicates(file), List(MessagesFile.DuplicateGroup("site.back", List(Entry("site.back", "Back"), Entry("site.back", "Back")))))

  }
  test("duplicates reports nothing when every key is unique") {
    val file = MessagesFile.parse("site.back = Back\nsite.change = Change\n")
    assertEquals(MessagesFile.duplicates(file), Nil)

  }
  test("duplicates orders groups by canonical key order") {
    val file = MessagesFile.parse("site.change = a\nsite.change = a\nsite.back = b\nsite.back = b\n")
    assertEquals(MessagesFile.duplicates(file).map(_.key), List("site.back", "site.change"))

  }
  test("a duplicate group with identical values is not conflicting") {
    val group = MessagesFile.DuplicateGroup("k", List(Entry("k", "v"), Entry("k", "v")))
    assert(!group.isConflicting)

  }
  test("a duplicate group with differing values is conflicting") {
    val group = MessagesFile.DuplicateGroup("k", List(Entry("k", "v1"), Entry("k", "v2")))
    assert(group.isConflicting)

  }
  test("dedupe collapses duplicate keys down to their first occurrence") {
    val file = MessagesFile.parse("site.back = Back\nsite.change = Change\nsite.back = Back\n")
    val deduped = MessagesFile.dedupe(file)
    assertEquals(deduped.entries, List(Entry("site.back", "Back"), Entry("site.change", "Change")))

  }
  test("dedupe leaves a file with no duplicates unchanged") {
    val file = MessagesFile.parse("site.back = Back\nsite.change = Change\n")
    assertEquals(MessagesFile.dedupe(file), file)

  }
  // --- comment classification -------------------------------------------

  test("a single-hash comment at the start of a block attaches to the block") {
    val parsed = MessagesFile.parse("# Change Business Name\nchangeBusinessName.title = Title\n")
    assertEquals(parsed.blockComments, Map("changeBusinessName" -> List("Change Business Name")))
    assertEquals(parsed.entries, List(Entry("changeBusinessName.title", "Title")))

  }
  test("a single-hash comment with no space after the hash is still a block comment") {
    val parsed = MessagesFile.parse("#Change Business Name\nchangeBusinessName.title = Title\n")
    assertEquals(parsed.blockComments, Map("changeBusinessName" -> List("Change Business Name")))

  }
  test("multiple consecutive comment lines at the start of a block form one block comment") {
    val parsed = MessagesFile.parse("# line one\n# line two\nkey.a = value\n")
    assertEquals(parsed.blockComments, Map("key" -> List("line one", "line two")))
    assertEquals(parsed.entries.head.comments, Nil)

  }
  test("a double-hash comment at the start of a block attaches to the line, not the block") {
    val parsed = MessagesFile.parse("## note\nkey.a = value\n")
    assertEquals(parsed.blockComments, Map.empty[String, List[String]])
    assertEquals(parsed.entries, List(Entry("key.a", "value", List("note"))))

  }
  test("a single-hash comment that is not at the start of a block attaches to the line") {
    val parsed = MessagesFile.parse("key.a = first\n# not a block start\nkey.b = second\n")
    assertEquals(parsed.blockComments, Map.empty[String, List[String]])
    assertEquals(parsed.entries, List(Entry("key.a", "first"), Entry("key.b", "second", List("not a block start"))))

  }
  test("only the first source occurrence of a block claims the block comment") {
    val content =
      "# first block\n" +
        "key.a = a\n" +
        "other.x = x\n" +
        "# second block\n" +
        "key.b = b\n"
    val parsed = MessagesFile.parse(content)
    assertEquals(parsed.blockComments, Map("key" -> List("first block")))
    assertEquals(parsed.entries.find(_.key == "key.b").get.comments, List("second block"))

  }
  test("a comment with no following key is kept as a trailer, verbatim including its hash count") {
    val parsed = MessagesFile.parse("key.a = value\n# trailing note\n## another\n")
    assertEquals(parsed.trailer, List("# trailing note", "## another"))
    assertEquals(parsed.entries, List(Entry("key.a", "value")))

  }
  test("blank lines between a comment and the key it attaches to do not break the attachment") {
    val parsed = MessagesFile.parse("# note\n\n\nkey.a = value\n")
    assertEquals(parsed.blockComments, Map("key" -> List("note")))

  }
  // --- rendering -----------------------------------------------------------

  test("render sorts entries into canonical key order") {
    val file = MessagesFile(List(Entry("site.change", "Change"), Entry("site.back", "Back")))
    assertEquals(MessagesFile.render(file), "site.back = Back\nsite.change = Change\n")

  }
  test("render separates differing top-level blocks with a single blank line") {
    val file = MessagesFile(List(Entry("date.day", "Day"), Entry("site.back", "Back")))
    assertEquals(MessagesFile.render(file), "date.day = Day\n\nsite.back = Back\n")

  }
  test("render does not insert a blank line within the same top-level block") {
    val file = MessagesFile(List(Entry("date.year", "Year"), Entry("date.day", "Day")))
    assertEquals(MessagesFile.render(file), "date.day = Day\ndate.year = Year\n")

  }
  test("render prints a line comment immediately above its entry, double-hashed") {
    val file = MessagesFile(List(Entry("key.a", "value", List("note"))))
    assertEquals(MessagesFile.render(file), "## note\nkey.a = value\n")

  }
  test("render prints a block comment once, above the block's first canonical entry") {
    val file = MessagesFile(Map("key" -> List("intro")), List(Entry("key.b", "b"), Entry("key.a", "a")), Nil)
    assertEquals(MessagesFile.render(file), "# intro\nkey.a = a\nkey.b = b\n")

  }
  test("render prints both a block comment and an entry's own line comment when both are present") {
    val file = MessagesFile(Map("key" -> List("intro")), List(Entry("key.a", "a", List("specific"))), Nil)
    assertEquals(MessagesFile.render(file), "# intro\n## specific\nkey.a = a\n")

  }
  test("render omits a block comment whose block has no remaining entries") {
    val file = MessagesFile(Map("gone" -> List("stale")), List(Entry("key.a", "a")), Nil)
    assertEquals(MessagesFile.render(file), "key.a = a\n")

  }
  test("render prints the trailer verbatim at the end, after a blank line") {
    val file = MessagesFile(Map.empty, List(Entry("key.a", "value")), List("# trailing note"))
    assertEquals(MessagesFile.render(file), "key.a = value\n\n# trailing note\n")

  }
  test("render of a trailer-only file omits the leading blank line") {
    val file = MessagesFile(Map.empty, Nil, List("# just a trailer"))
    assertEquals(MessagesFile.render(file), "# just a trailer\n")

  }
  test("render of an empty file is an empty string") {
    assertEquals(MessagesFile.render(MessagesFile(Nil)), "")

  }
  test("parsing a real-world canonical file and rendering it again is a no-op (round trip)") {
    val content =
      """# Change Business Name
        |changeBusinessName.heading = Change your business name
        |changeBusinessName.title = Change your business name
        |
        |date.day = Day
        |date.error.day = day
        |date.year = Year
        |
        |service.name = Foo Bar service
        |
        |site.back = Back
        |site.change = Change
        |site.continue = Continue
        |""".stripMargin
    assertEquals(MessagesFile.render(MessagesFile.parse(content)), content)

  }
  test("render with a priority list places that block ahead of the rest") {
    val file = MessagesFile(List(Entry("site.back", "Back"), Entry("date.day", "Day"), Entry("phase", "Phase")))
    assertEquals(MessagesFile.render(file, List("phase")), "phase = Phase\n\ndate.day = Day\n\nsite.back = Back\n")

  }
  test("render with several priority blocks orders them as given, not alphabetically") {
    val file = MessagesFile(List(Entry("site.back", "Back"), Entry("date.day", "Day"), Entry("phase", "Phase")))
    assertEquals(
      MessagesFile.render(file, List("site", "phase")),
      "site.back = Back\n\nphase = Phase\n\ndate.day = Day\n"
    )

  }
  test("render with no priority list is unaffected (default parameter)") {
    val file = MessagesFile(List(Entry("site.back", "Back"), Entry("date.day", "Day")))
    assertEquals(MessagesFile.render(file), MessagesFile.render(file, Nil))

  }
  test("re-rendering a single-hash mid-block comment normalises it to double-hash, stably") {
    val original = "key.a = a\n# note\nkey.b = b\n"
    val onceFormatted = MessagesFile.render(MessagesFile.parse(original))
    assertEquals(onceFormatted, "key.a = a\n## note\nkey.b = b\n")
    val twiceFormatted = MessagesFile.render(MessagesFile.parse(onceFormatted))
    assertEquals(twiceFormatted, onceFormatted)
  }
}
