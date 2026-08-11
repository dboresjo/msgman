package msgman

class KeySpec extends munit.FunSuite {

  test("segments splits on dots") {
    assertEquals(Key.segments("site.error.title"), List("site", "error", "title"))
  }

  test("segments of a key without a dot is a single element list") {
    assertEquals(Key.segments("phase"), List("phase"))
  }

  test("topLevel returns the first segment") {
    assertEquals(Key.topLevel("site.error.title"), "site")
    assertEquals(Key.topLevel("phase"), "phase")
  }

  test("ordering sorts alphabetically within a level") {
    assertEquals(Key.ordering.compare("site.back", "site.change"), -1)
    assertEquals(Key.ordering.compare("site.change", "site.back"), 1)
  }

  test("ordering treats equal keys as equal") {
    assertEquals(Key.ordering.compare("site.back", "site.back"), 0)
  }

  test("ordering places a parent key before its children") {
    assert(Key.ordering.compare("site", "site.back") < 0)
    assert(Key.ordering.compare("site.back", "site") > 0)
  }

  test("ordering compares hierarchically, not as plain strings") {
    // '-' sorts before '.' as plain characters, but "a" is a parent of "a.b"
    // regardless of what the next sibling segment looks like.
    assert(Key.ordering.compare("a.b", "a-b") < 0)
  }

  test("ordering sorts a realistic key list into canonical order") {
    val keys = List(
      "site.change",
      "site.back",
      "date.year",
      "date.day",
      "date.error.day",
      "phase"
    )
    val expected = List(
      "date.day",
      "date.error.day",
      "date.year",
      "phase",
      "site.back",
      "site.change"
    )
    assertEquals(keys.sorted(Key.ordering), expected)
  }
}
