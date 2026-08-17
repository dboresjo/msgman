package msgman

class TranslationSafetySpec extends munit.FunSuite:

  test("tokens extracts MessageFormat placeholders"):
    assertEquals(TranslationSafety.tokens("Hello {0}, you have {1} messages"), List("{0}", "{1}"))

  test("tokens extracts escaped quotes"):
    assertEquals(TranslationSafety.tokens("It''s {0}"), List("''", "{0}"))

  test("tokens is empty for plain text"):
    assertEquals(TranslationSafety.tokens("Hello world"), Nil)

  test("tokensMatch is true when the same tokens appear, even reordered"):
    assert(TranslationSafety.tokensMatch("Hello {0}, {1}", "Bonjour {1}, {0}"))

  test("tokensMatch is true for plain text with no tokens on either side"):
    assert(TranslationSafety.tokensMatch("Hello world", "Bonjour le monde"))

  test("tokensMatch is false when a placeholder is dropped"):
    assert(!TranslationSafety.tokensMatch("Hello {0}, {1}", "Bonjour {0}"))

  test("tokensMatch is false when a placeholder is duplicated"):
    assert(!TranslationSafety.tokensMatch("Hello {0}", "Bonjour {0} {0}"))

  test("tokensMatch is false when an escaped quote is dropped"):
    assert(!TranslationSafety.tokensMatch("It''s {0}", "Its {0}"))
