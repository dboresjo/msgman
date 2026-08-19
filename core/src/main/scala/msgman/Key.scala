package msgman

/** Ordering of message keys per the canonical format: dots separate hierarchy
  * segments, and segments are compared alphabetically level by level.
  */
object Key {

  def segments(key: String): List[String] = key.split("\\.", -1).toList

  def topLevel(key: String): String = segments(key).head

  val ordering: Ordering[String] = (a: String, b: String) => compareSegments(segments(a), segments(b))

  /** Like `ordering`, but the top-level blocks named in `priority` sort ahead
    * of every other block, in the order given, before falling back to plain
    * alphabetical order for the rest. Ordering within a block, and at every
    * level below the top, is unaffected.
    */
  def prioritized(priority: List[String]): Ordering[String] = {
    val rank = priority.zipWithIndex.toMap
    (a: String, b: String) => {
      val topA = topLevel(a)
      val topB = topLevel(b)
      val c = (rank.get(topA), rank.get(topB)) match {
        case (Some(ra), Some(rb)) => ra.compareTo(rb)
        case (Some(_), None)      => -1
        case (None, Some(_))      => 1
        case (None, None)         => topA.compareTo(topB)
      }
      if (c != 0) c else compareSegments(segments(a).tail, segments(b).tail)
    }
  }

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
