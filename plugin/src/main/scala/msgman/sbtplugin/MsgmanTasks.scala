package msgman.sbtplugin

import java.io.File
import msgman.{ExitCode, Runner, Translator}
import sbt.internal.util.MessageOnlyException

/** The argument-building and exit-code handling behind [[MsgmanPlugin]]'s tasks,
  * kept free of sbt's `Def`/`SettingKey` machinery so it can be unit-tested
  * directly rather than needing a live sbt task-graph harness (the same
  * reasoning that keeps `Main` untested on the CLI side).
  */
object MsgmanTasks {

  /** Builds the same style of argument vector `Cli.parse` expects, from an sbt
    * project's settings. Only settings the project actually set are included;
    * an unset (`None`/`Nil`) setting is simply omitted, so `Runner`'s own
    * `.msgman`-file-then-built-in-default resolution applies exactly as it
    * does for the CLI, rather than the plugin silently overriding it with a
    * hardcoded default of its own.
    */
  def buildArgs(
      command: String,
      master: Option[String],
      path: Option[String],
      filePattern: Option[String],
      priorityKeys: List[String],
      require: List[String],
      fix: Boolean = false,
      translate: Boolean = false,
      model: Option[String] = None,
      verbose: Boolean = false,
      strict: Boolean = false
  ): Array[String] = {
    val args = List.newBuilder[String]
    args += command
    master.foreach { m =>
      args += "--master"; args += m
    }
    path.foreach { p =>
      args += "--path"; args += p
    }
    filePattern.foreach { fp =>
      args += "--file-pattern"; args += fp
    }
    if (priorityKeys.nonEmpty) {
      args += "--priority-keys"; args += priorityKeys.mkString(",")
    }
    if (require.nonEmpty) {
      args += "--require"; args += require.mkString(",")
    }
    if (fix) args += "--fix"
    if (translate) args += "--translate"
    model.foreach { m =>
      args += "--model"; args += m
    }
    if (verbose) args += "--verbose"
    if (strict) args += "--strict"
    args.result().toArray
  }

  /** Runs `msgman` in-process against `cwd` and throws `MessageOnlyException`
    * if it doesn't exit successfully, so sbt prints just the message, not a
    * stack trace through its own task-execution machinery, which would tell
    * a caller nothing about the actual `msgman` failure. `providers` is only
    * meaningful for `format --translate`; `verify` never uses it.
    */
  def runOrThrow(args: Array[String], cwd: File, revision: String, providers: Map[String, Translator] = Map.empty): Unit = {
    val exitCode = Runner.run(args, cwd, System.out, System.err, revision, providers = providers, env = sys.env.get)
    if (exitCode != ExitCode.Success)
      throw new MessageOnlyException(s"msgman ${args.headOption.getOrElse("")} failed (exit code $exitCode)")
  }
}
