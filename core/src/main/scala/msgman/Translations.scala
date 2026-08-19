package msgman

object Translations {

  final case class Missing(languageCode: String, key: String)
  final case class Extra(languageCode: String, key: String)

  private val placeholderPattern = "^[A-Za-z]{2,3}:\\s".r
  private val aiGeneratedPrefix = "added by msgman"

  /** True if `value` looks like an unfinished placeholder left by `--fix`,
    * e.g. "en: Some text", rather than a real translation.
    */
  def isPlaceholder(value: String): Boolean = placeholderPattern.findFirstIn(value).isDefined

  /** True if `entry` carries the "added by msgman" comment left by
    * `--translate`, see "Inserted Translation" in TRANSLATION.md.
    */
  def isAiGenerated(entry: Entry): Boolean = entry.comments.exists(_.startsWith(aiGeneratedPrefix))

  /** Keys present in `master` that are missing (or, in strict mode, only
    * present as an untranslated placeholder, or as an unreviewed AI-added
    * translation) from each of `others`. Results are ordered by language
    * code, then by canonical key order.
    */
  def findMissing(master: MessagesFile, others: Map[String, MessagesFile], strict: Boolean): List[Missing] = {
    val masterKeys = master.entries.map(_.key).sorted(Key.ordering)
    others.toList.sortBy(_._1).flatMap {
      case (code, file) =>
        val entriesByKey = file.entries.map(e => e.key -> e).toMap
        masterKeys.flatMap { key =>
          entriesByKey.get(key) match {
            case None                                                => Some(Missing(code, key))
            case Some(entry) if strict && isPlaceholder(entry.value) => Some(Missing(code, key))
            case Some(entry) if strict && isAiGenerated(entry)       => Some(Missing(code, key))
            case _                                                   => None
          }
        }
    }
  }

  /** Keys that `--translate` should attempt to translate: absent from a
    * translation file entirely, or present only as an untranslated
    * placeholder left by `--fix` (eg. "cy: Some text"). A key that already
    * holds an AI-generated translation is left alone even if it happened to
    * still match the placeholder pattern, so a rerun of `format --translate`
    * never re-translates a key it already translated, see "Determinism" in
    * TRANSLATION.md.
    */
  def findMissingForTranslation(master: MessagesFile, others: Map[String, MessagesFile]): List[Missing] = {
    val masterKeys = master.entries.map(_.key).sorted(Key.ordering)
    others.toList.sortBy(_._1).flatMap {
      case (code, file) =>
        val entriesByKey = file.entries.map(e => e.key -> e).toMap
        masterKeys.flatMap { key =>
          entriesByKey.get(key) match {
            case None                                                              => Some(Missing(code, key))
            case Some(entry) if isPlaceholder(entry.value) && !isAiGenerated(entry) => Some(Missing(code, key))
            case _                                                                  => None
          }
        }
    }
  }

  /** Keys present in one of `others` but absent from `master` altogether,
    * ordered by language code, then by canonical key order.
    */
  def findExtra(master: MessagesFile, others: Map[String, MessagesFile]): List[Extra] = {
    val masterKeys = master.entries.map(_.key).toSet
    others.toList.sortBy(_._1).flatMap {
      case (code, file) =>
        file.entries.map(_.key).filterNot(masterKeys.contains).sorted(Key.ordering).map(key => Extra(code, key))
    }
  }
}
