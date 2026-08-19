package msgman.sbtplugin

import java.io.File
import java.nio.file.Files

class MsgmanTasksSpec extends munit.FunSuite:

  test("buildArgs with nothing set just carries the command"):
    assertEquals(MsgmanTasks.buildArgs("verify", None, None, None, Nil, Nil).toList, List("verify"))

  test("buildArgs includes master when set"):
    assertEquals(MsgmanTasks.buildArgs("format", Some("cy"), None, None, Nil, Nil).toList, List("format", "--master", "cy"))

  test("buildArgs includes path when set"):
    assertEquals(MsgmanTasks.buildArgs("format", None, Some("app/messages"), None, Nil, Nil).toList, List("format", "--path", "app/messages"))

  test("buildArgs includes file pattern when set"):
    assertEquals(
      MsgmanTasks.buildArgs("format", None, None, Some("messages_$1.properties"), Nil, Nil).toList,
      List("format", "--file-pattern", "messages_$1.properties")
    )

  test("buildArgs joins priority keys with commas"):
    assertEquals(
      MsgmanTasks.buildArgs("format", None, None, None, List("phase", "site"), Nil).toList,
      List("format", "--priority-keys", "phase,site")
    )

  test("buildArgs joins require codes with commas"):
    assertEquals(MsgmanTasks.buildArgs("verify", None, None, None, Nil, List("cy", "fr")).toList, List("verify", "--require", "cy,fr"))

  test("buildArgs combines every setting, in a fixed order"):
    assertEquals(
      MsgmanTasks.buildArgs("format", Some("en"), Some("conf"), Some("messages.$1"), List("phase"), List("cy")).toList,
      List("format", "--master", "en", "--path", "conf", "--file-pattern", "messages.$1", "--priority-keys", "phase", "--require", "cy")
    )

  private def tempCwd(): File = Files.createTempDirectory("msgman-plugin").toFile

  private def write(dir: File, name: String, content: String): File =
    val f = new File(dir, name)
    val w = new java.io.PrintWriter(f, "UTF-8")
    try w.print(content)
    finally w.close()
    f

  test("runOrThrow does not throw on a successful run"):
    val cwd = tempCwd()
    val conf = new File(cwd, "conf")
    conf.mkdirs()
    write(conf, "messages.en", "site.back = Back\n")
    MsgmanTasks.runOrThrow(Array("verify"), cwd, "sbt-msgman")

  test("runOrThrow throws MsgmanTaskFailed with the exit code on a failed run"):
    val cwd = tempCwd()
    val ex = intercept[MsgmanTasks.MsgmanTaskFailed]:
      MsgmanTasks.runOrThrow(Array("verify"), cwd, "sbt-msgman")
    assert(ex.getMessage.contains("verify"))
    assert(ex.getMessage.contains("exit code"))
