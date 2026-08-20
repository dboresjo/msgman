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
  * `--translate` runs with every AI provider linked in (see `AiProviders`);
  * `.msgman`'s `provider` setting (or the CLI's own "exactly one linked in"
  * default) picks which one is actually used.
  */
object MsgmanPlugin extends AutoPlugin:

  override def trigger = noTrigger
  override def requires = JvmPlugin

  object autoImport:
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

  import autoImport._

  override def projectSettings: Seq[Def.Setting[?]] = Seq(
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
    // sbt 2.x caches task results by default and, on a cache hit, returns the
    // cached value without re-running the task body at all, side effects
    // included. msgman reads and writes messages files that sbt's dependency
    // graph knows nothing about, so a cached "succeeded last time" result must
    // never be substituted for actually running it again; Def.uncached opts
    // both tasks out of that caching.
    msgmanFormat := Def.uncached {
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
        "sbt-msgman",
        providers = AiProviders.build(baseDirectory.value)
      )
    },
    msgmanVerify := Def.uncached {
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
