package msgman

/** A single translation entry. `comments` holds the text of any comment lines
  * attached specifically to this entry (a "line comment"); they are rendered
  * with a `##` prefix and travel with the key when the file is re-sorted.
  */
final case class Entry(key: String, value: String, comments: List[String] = Nil)

/** A parsed messages file.
  *
  * `blockComments` holds, for each top-level key, the text of a comment that
  * introduced that whole block in the source file (a single `#`, appearing
  * immediately before the first entry of a run of entries sharing that
  * top-level key). It is rendered once, above the block, wherever that block
  * ends up after sorting.
  *
  * `trailer` holds fully-rendered comment lines (hash prefix included) that
  * could not be attached to any key because none followed them; they are
  * preserved verbatim at the end of the file.
  */
final case class MessagesFile(blockComments: Map[String, List[String]], entries: List[Entry], trailer: List[String])

final case class MessagesFileParseException(message: String) extends RuntimeException(message)

object MessagesFile {

  def apply(entries: List[Entry]): MessagesFile = MessagesFile(Map.empty[String, List[String]], entries, Nil)

  private final case class PendingComment(text: String, doubleHashed: Boolean)

  def parse(content: String): MessagesFile = {
    val lines = scala.io.Source.fromString(content).getLines().toList

    val entries = Vector.newBuilder[Entry]
    val blockComments = scala.collection.mutable.LinkedHashMap.empty[String, List[String]]
    val claimedBlocks = scala.collection.mutable.Set.empty[String]
    var pending = Vector.empty[PendingComment]
    var previousTop: Option[String] = None

    lines.foreach { rawLine =>
      val trimmed = rawLine.trim
      if (trimmed.isEmpty) {
        // blank lines are pure formatting; they don't break comment attachment
        ()
      } else if (trimmed.startsWith("#")) {
        val doubleHashed = trimmed.startsWith("##")
        val text = trimmed.replaceFirst("^#+\\s*", "")
        pending = pending :+ PendingComment(text, doubleHashed)
      } else {
        val idx = trimmed.indexOf('=')
        if (idx < 0)
          throw new MessagesFileParseException(s"malformed line (expected 'key = value'): $trimmed")
        val key = trimmed.substring(0, idx).trim
        val value = trimmed.substring(idx + 1).trim
        if (key.isEmpty)
          throw new MessagesFileParseException(s"malformed line (empty key): $trimmed")

        val top = Key.topLevel(key)
        val isBlockStart = !previousTop.contains(top)
        previousTop = Some(top)

        if (pending.isEmpty) {
          entries += Entry(key, value)
        } else if (isBlockStart && !pending.head.doubleHashed && !claimedBlocks.contains(top)) {
          blockComments(top) = pending.map(_.text).toList
          claimedBlocks += top
          entries += Entry(key, value)
        } else {
          entries += Entry(key, value, pending.map(_.text).toList)
        }
        pending = Vector.empty
      }
    }

    val trailer = pending.map(c => if (c.doubleHashed) s"## ${c.text}" else s"# ${c.text}").toList

    MessagesFile(blockComments.toMap, entries.result().toList, trailer)
  }

  def render(file: MessagesFile, priority: List[String] = Nil): String = {
    val sorted = file.entries.sortBy(_.key)(Key.prioritized(priority))
    val sb = new StringBuilder

    var previousTop: Option[String] = None
    sorted.foreach { entry =>
      val top = Key.topLevel(entry.key)
      val isNewBlock = !previousTop.contains(top)
      if (previousTop.isDefined && isNewBlock) sb.append('\n')
      if (isNewBlock) {
        file.blockComments.getOrElse(top, Nil).foreach(line => sb.append("# ").append(line).append('\n'))
      }
      entry.comments.foreach(line => sb.append("## ").append(line).append('\n'))
      sb.append(entry.key).append(" = ").append(entry.value).append('\n')
      previousTop = Some(top)
    }

    if (file.trailer.nonEmpty) {
      if (sorted.nonEmpty) sb.append('\n')
      file.trailer.foreach(line => sb.append(line).append('\n'))
    }

    sb.toString
  }

  /** A key that appears more than once in a single file. */
  final case class DuplicateGroup(key: String, entries: List[Entry]) {
    def values: List[String] = entries.map(_.value)
    def isConflicting: Boolean = values.distinct.size > 1
  }

  /** Every key that appears more than once in `file`, ordered by canonical key order. */
  def duplicates(file: MessagesFile): List[DuplicateGroup] =
    file.entries
      .groupBy(_.key)
      .collect { case (key, es) if es.size > 1 => DuplicateGroup(key, es) }
      .toList
      .sortBy(_.key)(Key.ordering)

  /** Collapses non-conflicting duplicate keys (identical value in every
    * occurrence) down to their first occurrence. Entries with conflicting
    * values are left untouched; callers should reject those separately.
    */
  def dedupe(file: MessagesFile): MessagesFile = {
    val firstOccurrence = scala.collection.mutable.LinkedHashMap.empty[String, Entry]
    file.entries.foreach { entry =>
      if (!firstOccurrence.contains(entry.key)) firstOccurrence(entry.key) = entry
    }
    file.copy(entries = firstOccurrence.values.toList)
  }
}
