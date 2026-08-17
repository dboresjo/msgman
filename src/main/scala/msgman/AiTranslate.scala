package msgman

import java.io.File

/** The result of a `--translate` attempt for one target language file.
  * `fatal`, when set, means a request failed in a way that will fail
  * identically for every other key too (see `TranslationOutcome.Failure`),
  * so the run stopped immediately: `entries`/`stillMissing` only cover
  * whatever was decided before that happened.
  */
final case class TranslateResult(entries: List[Entry], stillMissing: List[(String, String)], fatal: Option[String])

/** Orchestrates `--translate`: works out which keys of `target` are missing
  * (see `Translations.findMissingForTranslation`), batches them per message
  * block into one `Translator` call per block, and falls back to one call
  * per key for any block whose batched response fails validation. See "Cost
  * and latency" and "Translation safety" in TRANSLATION.md.
  */
object AiTranslate:

  /** Groups `keys` (assumed already known missing) by top-level key, both the
    * blocks and the keys within each block in canonical key order.
    */
  def groupByBlock(keys: List[String]): List[(String, List[String])] =
    keys
      .groupBy(Key.topLevel)
      .toList
      .sortBy(_._1)(Key.ordering)
      .map { case (top, ks) => top -> ks.sorted(Key.ordering) }

  private final case class RequestFailure(reason: String, fatal: Boolean)

  /** Calls the translator once for `targets` and validates every returned
    * value preserves its source's placeholder tokens. `Left` covers both a
    * request-level failure and a response that is missing a key or fails
    * validation for any key, either way carrying a reason the caller can
    * report if retrying individually doesn't recover it. Reports the request
    * and its outcome to `log` before and after the call, for `--verbose`.
    */
  // Drops the block's own top-level key from a sub-key for --verbose display,
  // eg. "additionalAddressInfoYesNo.hint" rather than
  // "PartnerDetails.additionalAddressInfoYesNo.hint" when the block
  // "PartnerDetails" is already named right next to it. The full key is still
  // used everywhere else (the request/response themselves, and the file).
  private def displayKey(topLevelKey: String, fullKey: String): String =
    fullKey.stripPrefix(s"$topLevelKey.")

  private def callAndValidate(
      translator: Translator,
      context: BlockContext,
      targets: List[TranslationTarget],
      model: String,
      log: String => Unit
  ): Either[RequestFailure, Map[String, String]] =
    val keyList = targets.map(t => displayKey(context.topLevelKey, t.subKey)).mkString(", ")
    log(s"[${context.targetLanguageCode}] requesting translation of ${context.topLevelKey} ($keyList) from $model")
    val result = translator.translateBlock(TranslationRequest(context, targets, model)) match
      case TranslationOutcome.Failure(reason, fatal) => Left(RequestFailure(reason, fatal))
      case TranslationOutcome.Success(response) =>
        val validated = targets.map: t =>
          response.translations.get(t.subKey).filter(TranslationSafety.tokensMatch(t.masterText, _)).map(t.subKey -> _)
        if validated.forall(_.isDefined) then Right(validated.flatten.toMap)
        else Left(RequestFailure("response did not include a valid translation for every key", fatal = false))
    result match
      case Right(translations) =>
        val summary = translations.toList
          .sortBy(_._1)(Key.ordering)
          .map { case (k, v) => s"${displayKey(context.topLevelKey, k)} = $v" }
          .mkString("; ")
        log(s"[${context.targetLanguageCode}] received translation of ${context.topLevelKey}: $summary")
      case Left(RequestFailure(reason, _)) =>
        log(s"[${context.targetLanguageCode}] translation of ${context.topLevelKey} failed: $reason")
    result

  private final case class Acc(entries: List[Entry], stillMissing: List[(String, String)], fatal: Option[String]):
    def isDone: Boolean = fatal.isDefined
    def withSuccess(key: String, text: String, stealth: Boolean, model: String): Acc =
      copy(entries = entries :+ Entry(key, text, if stealth then Nil else List(s"added by msgman using $model")))
    def withMissing(key: String, reason: String): Acc = copy(stillMissing = stillMissing :+ (key -> reason))
    // Discards whatever was accumulated so far: a fatal failure means the whole
    // attempt is treated as failed, not a confusing partial mix of "translated",
    // "missing" and "the run was actually broken".
    def withFatal(reason: String): Acc = Acc(Nil, Nil, Some(reason))

  /** Translates every key of `target` (for `targetCode`) that
    * `findMissingForTranslation` reports as missing. Returns the new entries
    * to merge into the file (each carrying the "added by msgman" comment
    * unless `stealth`), the sub-keys still missing after a failed attempt
    * paired with the reason, and, if a fatal failure was hit, that reason,
    * in which case no further blocks or keys were attempted at all. `log` is
    * called before and after every request to the model, for `--verbose`.
    */
  def translate(
      cwd: File,
      config: Config,
      translator: Translator,
      model: String,
      stealth: Boolean,
      master: MessagesFile,
      target: MessagesFile,
      masterCode: String,
      targetCode: String,
      log: String => Unit = _ => ()
  ): TranslateResult =
    val masterValues = master.entries.map(e => e.key -> e.value).toMap
    val missingKeys = Translations.findMissingForTranslation(master, Map(targetCode -> target)).map(_.key)
    val blocks = groupByBlock(missingKeys)

    val result = blocks.foldLeft(Acc(Nil, Nil, None)): (acc, block) =>
      if acc.isDone then acc
      else
        val (topLevelKey, keys) = block
        val context = TranslationContext.build(cwd, config, master, target, topLevelKey, keys.toSet, masterCode, targetCode)
        val targets = keys.map(k => TranslationTarget(k, masterValues(k)))
        // callAndValidate only ever returns Right once every requested target has
        // validated successfully, so `translations`/`single` below are guaranteed
        // to contain every key being looked up; direct map access is safe.
        callAndValidate(translator, context, targets, model, log) match
          case Right(translations) =>
            keys.foldLeft(acc): (a, k) =>
              a.withSuccess(k, translations(k), stealth, model)
          case Left(RequestFailure(reason, true)) => acc.withFatal(reason)
          case Left(RequestFailure(blockReason, false)) =>
            keys.foldLeft(acc): (a, k) =>
              if a.isDone then a
              else
                callAndValidate(translator, context, List(TranslationTarget(k, masterValues(k))), model, log) match
                  case Right(single)                       => a.withSuccess(k, single(k), stealth, model)
                  case Left(RequestFailure(reason, true))  => a.withFatal(reason)
                  case Left(RequestFailure(reason, false)) => a.withMissing(k, reason)

    TranslateResult(result.entries, result.stillMissing, result.fatal)
