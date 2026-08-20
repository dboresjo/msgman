package msgman.sbtplugin

import java.io.File
import msgman.{ExitCode, Runner, Translator}

/** The argument-building and exit-code handling behind [[MsgmanPlugin]]'s tasks,
  * kept free of sbt's `Def`/`SettingKey` machinery so it can be unit-tested
  * directly rather than needing a live sbt task-graph harness (the same
  * reasoning that keeps `Main` untested on the CLI side).
  */
object MsgmanTasks:

  /** Thrown by `runOrThrow` when the underlying `msgman` run fails, so the
    * owning sbt task fails with a clear, single-line message rather than a
    * raw exit code.
    */
  final case class MsgmanTaskFailed(message: String) extends RuntimeException(message)

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
  ): Array[String] =
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
    if priorityKeys.nonEmpty then
      args += "--priority-keys"; args += priorityKeys.mkString(",")
    if require.nonEmpty then
      args += "--require"; args += require.mkString(",")
    if fix then args += "--fix"
    if translate then args += "--translate"
    model.foreach { m =>
      args += "--model"; args += m
    }
    if verbose then args += "--verbose"
    if strict then args += "--strict"
    args.result().toArray

  /** Runs `msgman` in-process against `cwd` and throws `MsgmanTaskFailed` if it
    * doesn't exit successfully. `providers` is only meaningful for `format
    * --translate`; `verify` never uses it.
    */
  def runOrThrow(args: Array[String], cwd: File, revision: String, providers: Map[String, Translator] = Map.empty): Unit =
    val exitCode = Runner.run(args, cwd, System.out, System.err, revision, providers = providers, env = sys.env.get)
    if exitCode != ExitCode.Success then
      throw MsgmanTaskFailed(s"msgman ${args.headOption.getOrElse("")} failed (exit code $exitCode)")
