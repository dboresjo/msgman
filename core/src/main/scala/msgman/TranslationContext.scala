package msgman

import java.io.File

object TranslationContext {

  private val nameSetting = "(?m)^\\s*name\\s*:=\\s*\"([^\"]*)\"".r
  private val descriptionSetting = "(?m)^\\s*description\\s*:=\\s*\"([^\"]*)\"".r

  def projectName(buildSbt: String): Option[String] = nameSetting.findFirstMatchIn(buildSbt).map(_.group(1))

  def projectDescription(buildSbt: String): Option[String] = descriptionSetting.findFirstMatchIn(buildSbt).map(_.group(1))

  /** Resolves the TRANSLATION-CONTEXT.md path: `overridePath` may be absolute
    * or relative to `cwd`. The default, when not overridden, is
    * TRANSLATION-CONTEXT.md directly in `cwd`.
    */
  def translationContextFile(cwd: File, overridePath: Option[String]): File =
    overridePath match {
      case Some(p) =>
        val f = new File(p)
        if (f.isAbsolute) f else new File(cwd, p)
      case None => new File(cwd, "TRANSLATION-CONTEXT.md")
    }

  def readIfPresent(file: File): Option[String] =
    if (file.isFile) {
      val source = scala.io.Source.fromFile(file, "UTF-8")
      try Some(source.mkString)
      finally source.close()
    } else None

  /** Sibling sub-keys of `topLevelKey` in `master`, each paired with its
    * master text and, where present, the current translation from `target`.
    * Excludes `excludeKeys`, the keys actually being translated in this
    * batch, they are not their own sibling context.
    */
  def siblingKeys(master: MessagesFile, target: MessagesFile, topLevelKey: String, excludeKeys: Set[String]): List[SiblingKey] = {
    val targetValues = target.entries.map(e => e.key -> e.value).toMap
    master.entries
      .filter(e => Key.topLevel(e.key) == topLevelKey && !excludeKeys.contains(e.key))
      .sortBy(_.key)(Key.ordering)
      .map(e => SiblingKey(e.key, e.value, targetValues.get(e.key)))
  }

  /** Builds the shared context for a block-batched translation request, see
    * "Translation Context" in TRANSLATION.md. `excludeKeys` are the keys
    * being translated in this same batch.
    */
  def build(
      cwd: File,
      config: Config,
      master: MessagesFile,
      target: MessagesFile,
      topLevelKey: String,
      excludeKeys: Set[String],
      masterCode: String,
      targetCode: String
  ): BlockContext = {
    val buildSbtContent = readIfPresent(new File(cwd, "build.sbt"))
    val translationContextContent = readIfPresent(translationContextFile(cwd, config.translationContext))

    BlockContext(
      projectName = buildSbtContent.flatMap(projectName),
      projectDescription = buildSbtContent.flatMap(projectDescription),
      translationContext = translationContextContent,
      topLevelKey = topLevelKey,
      siblingKeys = siblingKeys(master, target, topLevelKey, excludeKeys),
      masterLanguageCode = masterCode,
      masterLanguageName = Languages.name(masterCode),
      targetLanguageCode = targetCode,
      targetLanguageName = Languages.name(targetCode)
    )
  }
}
