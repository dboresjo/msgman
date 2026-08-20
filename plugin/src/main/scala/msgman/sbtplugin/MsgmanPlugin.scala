package msgman.sbtplugin

import sbt._
import sbt.Keys._
import sbt.plugins.JvmPlugin

/** Runs `msgman format`/`verify` in-process as sbt tasks, sharing the exact
  * same parsing/sorting logic as the CLI (see `core/`) rather than shelling
  * out to a separately-installed binary. Not auto-enabled: a project opts in
  * with `.enablePlugins(MsgmanPlugin)` and wires the tasks into its own build
  * (e.g. `Compile / compile := (Compile / compile).dependsOn(msgmanVerify).value`),
  * the same way projects already did by hand before this plugin existed.
  *
  * `--translate` cannot be fulfilled on this branch: `com.softwaremill.sttp.ai`
  * (the client library the real AI provider factories need) is only
  * published for Scala 2.13 and 3, never 2.12, so there is no working
  * artifact to link against here (see `build.sbt`). The `msgmanTranslate`/
  * `msgmanModel`/`msgmanVerbose` settings still exist for API parity with the
  * mainline's sbt-2.x plugin, but `msgmanFormat` always runs with no provider
  * linked in, so `msgmanTranslate := true` fails with the CLI's own standard
  * "no provider linked in" setup error, the same as a CLI binary built
  * without `--with-ai`.
  */
object MsgmanPlugin extends AutoPlugin {

  override def trigger = noTrigger
  override def requires = JvmPlugin

  object autoImport {
    val msgmanFormat = taskKey[Unit]("Rewrite messages files in-place into canonical order via msgman")
    val msgmanVerify = taskKey[Unit]("Verify messages files are already in canonical order via msgman")
    val msgmanMaster =
      settingKey[Option[String]]("Country code of the master language (see msgman --master); unset falls back to .msgman, then 'en'")
    val msgmanPath =
      settingKey[Option[String]]("Directory containing messages files, relative to the project base (see msgman --path); unset falls back to .msgman, then 'conf'")
    val msgmanFilePattern =
      settingKey[Option[String]]("Filename pattern for messages files (see msgman --file-pattern); unset falls back to .msgman, then 'messages.$1'")
    val msgmanPriorityKeys =
      settingKey[List[String]]("Top-level keys sorted ahead of the rest, in order (see msgman --priority-keys); empty falls back to .msgman, then none")
    val msgmanRequire =
      settingKey[List[String]]("Country codes a messages file is required to exist for (see msgman --require); empty falls back to .msgman, then none")
    val msgmanFix =
      settingKey[Boolean]("format only: add missing translations as placeholders (see msgman --fix); cannot combine with msgmanTranslate")
    val msgmanTranslate =
      settingKey[Boolean]("format only: generate missing translations using an AI service (see msgman --translate); cannot combine with msgmanFix")
    val msgmanModel =
      settingKey[Option[String]]("Override the AI model used by msgmanTranslate (see msgman --model); unset falls back to .msgman")
    val msgmanVerbose =
      settingKey[Boolean]("Print each translation request and response as it happens (see msgman --verbose); only valid with msgmanTranslate")
    val msgmanStrict =
      settingKey[Boolean]("verify only: treat language-code-prefixed placeholder values as missing (see msgman --strict)")
  }

  import autoImport._

  override def projectSettings: Seq[Def.Setting[_]] = Seq(
    msgmanMaster := None,
    msgmanPath := None,
    msgmanFilePattern := None,
    msgmanPriorityKeys := Nil,
    msgmanRequire := Nil,
    msgmanFix := false,
    msgmanTranslate := false,
    msgmanModel := None,
    msgmanVerbose := false,
    msgmanStrict := false,
    // Unlike sbt 2.x, sbt 1.x does not cache task results across invocations
    // by default, so there's no equivalent of Def.uncached needed here: every
    // `sbt msgmanVerify` run actually re-executes this task body.
    msgmanFormat := {
      MsgmanTasks.runOrThrow(
        MsgmanTasks.buildArgs(
          "format",
          msgmanMaster.value,
          msgmanPath.value,
          msgmanFilePattern.value,
          msgmanPriorityKeys.value,
          msgmanRequire.value,
          fix = msgmanFix.value,
          translate = msgmanTranslate.value,
          model = msgmanModel.value,
          verbose = msgmanVerbose.value
        ),
        baseDirectory.value,
        "sbt-msgman"
      )
    },
    msgmanVerify := {
      MsgmanTasks.runOrThrow(
        MsgmanTasks.buildArgs(
          "verify",
          msgmanMaster.value,
          msgmanPath.value,
          msgmanFilePattern.value,
          msgmanPriorityKeys.value,
          msgmanRequire.value,
          strict = msgmanStrict.value
        ),
        baseDirectory.value,
        "sbt-msgman"
      )
    }
  )
}
