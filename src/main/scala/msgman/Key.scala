package msgman

/** Ordering of message keys per the canonical format: dots separate hierarchy
  * segments, and segments are compared alphabetically level by level.
  */
object Key {

  def segments(key: String): List[String] = key.split("\\.", -1).toList

  def topLevel(key: String): String = segments(key).head

  val ordering: Ordering[String] = (a: String, b: String) => compareSegments(segments(a), segments(b))

  @annotation.tailrec
  private def compareSegments(a: List[String], b: List[String]): Int = (a, b) match {
    case (Nil, Nil) => 0
    case (Nil, _)   => -1
    case (_, Nil)   => 1
    case (ah :: at, bh :: bt) =>
      val c = ah.compareTo(bh)
      if (c != 0) c else compareSegments(at, bt)
  }
}
