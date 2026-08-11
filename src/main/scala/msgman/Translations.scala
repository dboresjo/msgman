package msgman

object Translations {

  final case class Missing(languageCode: String, key: String)
  final case class Extra(languageCode: String, key: String)

  private val placeholderPattern = "^[A-Za-z]{2,3}:\\s".r

  /** True if `value` looks like an unfinished placeholder left by `--fix`,
    * e.g. "en: Some text", rather than a real translation.
    */
  def isPlaceholder(value: String): Boolean = placeholderPattern.findFirstIn(value).isDefined

  /** Keys present in `master` that are missing (or, in strict mode, only
    * present as an untranslated placeholder) from each of `others`. Results
    * are ordered by language code, then by canonical key order.
    */
  def findMissing(master: MessagesFile, others: Map[String, MessagesFile], strict: Boolean): List[Missing] = {
    val masterKeys = master.entries.map(_.key).sorted(Key.ordering)
    others.toList.sortBy(_._1).flatMap { case (code, file) =>
      val valuesByKey = file.entries.map(e => e.key -> e.value).toMap
      masterKeys.flatMap { key =>
        valuesByKey.get(key) match {
          case None                                       => Some(Missing(code, key))
          case Some(value) if strict && isPlaceholder(value) => Some(Missing(code, key))
          case _                                          => None
        }
      }
    }
  }

  /** Keys present in one of `others` but absent from `master` altogether,
    * ordered by language code, then by canonical key order.
    */
  def findExtra(master: MessagesFile, others: Map[String, MessagesFile]): List[Extra] = {
    val masterKeys = master.entries.map(_.key).toSet
    others.toList.sortBy(_._1).flatMap { case (code, file) =>
      file.entries.map(_.key).filterNot(masterKeys.contains).sorted(Key.ordering).map(key => Extra(code, key))
    }
  }
}
