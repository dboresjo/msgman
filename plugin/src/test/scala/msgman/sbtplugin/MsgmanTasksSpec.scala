package msgman.sbtplugin

import java.io.File
import java.nio.file.Files
import sbt.internal.util.MessageOnlyException

class MsgmanTasksSpec extends munit.FunSuite {

  test("buildArgs with nothing set just carries the command") {
    assertEquals(MsgmanTasks.buildArgs("verify", None, None, None, Nil, Nil).toList, List("verify"))

  }
  test("buildArgs includes master when set") {
    assertEquals(MsgmanTasks.buildArgs("format", Some("cy"), None, None, Nil, Nil).toList, List("format", "--master", "cy"))

  }
  test("buildArgs includes path when set") {
    assertEquals(MsgmanTasks.buildArgs("format", None, Some("app/messages"), None, Nil, Nil).toList, List("format", "--path", "app/messages"))

  }
  test("buildArgs includes file pattern when set") {
    assertEquals(
      MsgmanTasks.buildArgs("format", None, None, Some("messages_$1.properties"), Nil, Nil).toList,
      List("format", "--file-pattern", "messages_$1.properties")
    )

  }
  test("buildArgs joins priority keys with commas") {
    assertEquals(
      MsgmanTasks.buildArgs("format", None, None, None, List("phase", "site"), Nil).toList,
      List("format", "--priority-keys", "phase,site")
    )

  }
  test("buildArgs joins require codes with commas") {
    assertEquals(MsgmanTasks.buildArgs("verify", None, None, None, Nil, List("cy", "fr")).toList, List("verify", "--require", "cy,fr"))

  }
  test("buildArgs combines every setting, in a fixed order") {
    assertEquals(
      MsgmanTasks.buildArgs("format", Some("en"), Some("conf"), Some("messages.$1"), List("phase"), List("cy")).toList,
      List("format", "--master", "en", "--path", "conf", "--file-pattern", "messages.$1", "--priority-keys", "phase", "--require", "cy")
    )

  }
  test("buildArgs includes --fix when set") {
    assertEquals(
      MsgmanTasks.buildArgs("format", None, None, None, Nil, Nil, fix = true).toList,
      List("format", "--fix")
    )
  }
  test("buildArgs includes --translate when set") {
    assertEquals(
      MsgmanTasks.buildArgs("format", None, None, None, Nil, Nil, translate = true).toList,
      List("format", "--translate")
    )
  }
  test("buildArgs includes --model when set") {
    assertEquals(
      MsgmanTasks.buildArgs("format", None, None, None, Nil, Nil, translate = true, model = Some("gpt-5")).toList,
      List("format", "--translate", "--model", "gpt-5")
    )
  }
  test("buildArgs includes --verbose when set") {
    assertEquals(
      MsgmanTasks.buildArgs("format", None, None, None, Nil, Nil, translate = true, verbose = true).toList,
      List("format", "--translate", "--verbose")
    )
  }
  test("buildArgs includes --strict when set") {
    assertEquals(
      MsgmanTasks.buildArgs("verify", None, None, None, Nil, Nil, strict = true).toList,
      List("verify", "--strict")
    )
  }

  private def tempCwd(): File = Files.createTempDirectory("msgman-plugin").toFile

  private def write(dir: File, name: String, content: String): File = {
    val f = new File(dir, name)
    val w = new java.io.PrintWriter(f, "UTF-8")
    try w.print(content)
    finally w.close()
    f
  }

  test("runOrThrow does not throw on a successful run") {
    val cwd = tempCwd()
    val conf = new File(cwd, "conf")
    conf.mkdirs()
    write(conf, "messages.en", "site.back = Back\n")
    MsgmanTasks.runOrThrow(Array("verify"), cwd, "sbt-msgman")

  }
  test("runOrThrow throws MessageOnlyException with the exit code on a failed run") {
    val cwd = tempCwd()
    val ex = intercept[MessageOnlyException] {
      MsgmanTasks.runOrThrow(Array("verify"), cwd, "sbt-msgman")
    }
    assert(ex.getMessage.contains("verify"))
    assert(ex.getMessage.contains("exit code"))
  }

  test("msgmanTranslate with the default (empty) providers map fails as a clean setup error") {
    val cwd = tempCwd()
    val conf = new File(cwd, "conf")
    conf.mkdirs()
    write(conf, "messages.en", "site.back = Back\n")
    write(conf, "messages.cy", "")
    val args = MsgmanTasks.buildArgs("format", None, None, None, Nil, Nil, translate = true, model = Some("test-model"))
    // No AI provider dependency is linked into this branch's plugin at all (see
    // build.sbt), so this exercises the real default `providers = Map.empty`
    // path, not an explicitly-passed fake provider.
    val ex = intercept[MessageOnlyException] {
      MsgmanTasks.runOrThrow(args, cwd, "sbt-msgman")
    }
    assert(ex.getMessage.contains("format"))
  }

  test("runOrThrow accepts an explicit providers map") {
    val cwd = tempCwd()
    val conf = new File(cwd, "conf")
    conf.mkdirs()
    write(conf, "messages.en", "site.back = Back\n")
    val fake: msgman.Translator = (_: msgman.TranslationRequest) => msgman.TranslationOutcome.Failure("unused")
    MsgmanTasks.runOrThrow(Array("verify"), cwd, "sbt-msgman", providers = Map("openai" -> fake))
  }

  test("runOrThrow's env lookup is reached when --translate resolves a provider's API key") {
    val cwd = tempCwd()
    val conf = new File(cwd, "conf")
    conf.mkdirs()
    write(conf, "messages.en", "site.back = Back\n")
    write(conf, "messages.cy", "")
    val fake: msgman.Translator = (_: msgman.TranslationRequest) => msgman.TranslationOutcome.Failure("unused")
    val args = MsgmanTasks.buildArgs("format", None, None, None, Nil, Nil, translate = true, model = Some("test-model"))
    // No provider API key is configured in this environment, so this fails before any
    // network call is attempted, exercising the real env lookup (sys.env.get) along the way.
    val ex = intercept[MessageOnlyException] {
      MsgmanTasks.runOrThrow(args, cwd, "sbt-msgman", providers = Map("openai" -> fake))
    }
    assert(ex.getMessage.contains("format"))
  }
}
