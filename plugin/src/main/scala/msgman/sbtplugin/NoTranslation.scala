package msgman.sbtplugin

/** `Runner.run` requires an environment lookup function for `--translate`'s
  * API key resolution, even though the plugin never links an AI provider in
  * (see `MsgmanTasks.runOrThrow`) and so can never actually reach it. Kept in
  * its own file, excluded from coverage, rather than pulling the whole of
  * `MsgmanTasks` out of coverage for one parameter that can never run.
  */
object NoTranslation:
  val env: String => Option[String] = _ => None
