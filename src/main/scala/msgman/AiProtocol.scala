package msgman

/** The wire format used to talk to an AI provider: the prompt asks for a
  * reply as flat `sub-key = translated text` lines, one per target, the same
  * grammar `.msgman` itself uses (see `AiConfig.parseFile`), so the response
  * needs no separate parser. Kept provider-agnostic and free of any sttp-ai
  * dependency so it can be fully unit tested, unlike the concrete provider
  * adapters that call the real API, see "Test coverage" in TRANSLATION.md.
  */
object AiProtocol:

  def buildPrompt(request: TranslationRequest): String =
    val ctx = request.context
    val sb = new StringBuilder
    sb.append(s"Translate Play Framework messages file text from ${ctx.masterLanguageName} (${ctx.masterLanguageCode}) ")
      .append(s"to ${ctx.targetLanguageName} (${ctx.targetLanguageCode}).\n")
    ctx.projectName.foreach(n => sb.append(s"Project: $n\n"))
    ctx.projectDescription.foreach(d => sb.append(s"Description: $d\n"))
    ctx.translationContext.foreach(c => sb.append(s"Context:\n$c\n"))
    sb.append(s"Message block: ${ctx.topLevelKey}\n")
    if ctx.siblingKeys.nonEmpty then
      sb.append("Other keys already in this block, for context:\n")
      ctx.siblingKeys.foreach: s =>
        sb.append(s"  ${s.subKey} (${ctx.masterLanguageCode}) = ${s.masterText}\n")
        s.targetText.foreach(t => sb.append(s"  ${s.subKey} (${ctx.targetLanguageCode}) = $t\n"))
    sb.append("Preserve any {0}, {1}, ... placeholders and '' escaped quotes exactly, and any HTML markup verbatim.\n")
    sb.append("Reply with exactly one line per key below, in the form 'key = translated text', nothing else.\n")
    request.targets.foreach(t => sb.append(s"${t.subKey} (${ctx.masterLanguageCode}) = ${t.masterText}\n"))
    sb.toString

  /** Parses an AI reply of `key = translated text` lines into a sub-key to
    * translation map. Reuses the same flat grammar as `.msgman`.
    */
  def parseResponse(text: String): Map[String, String] = AiConfig.parseFile(text)
