package msgman

/** Validates that an AI-translated value preserves the source's MessageFormat
  * placeholders and escaped quotes, see "Translation safety" in
  * TRANSLATION.md. HTML markup is not validated here, unlike placeholder
  * tokens it has no fixed, easily-comparable form.
  */
object TranslationSafety:

  private val tokenPattern = "\\{\\d+\\}|''".r

  def tokens(text: String): List[String] = tokenPattern.findAllIn(text).toList

  /** True if `translated` contains the same multiset of placeholder tokens as
    * `source`, regardless of order.
    */
  def tokensMatch(source: String, translated: String): Boolean =
    tokens(source).sorted == tokens(translated).sorted
