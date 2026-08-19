package msgman

/** A sub-key in the same message block as the ones being translated, given as
  * context alongside a translation request. `targetText` is set only when
  * that sub-key already has a translation in the target language.
  */
final case class SiblingKey(subKey: String, masterText: String, targetText: Option[String])

/** The shared context for every key translated in a single block-batched
  * request, see "Translation Context" in TRANSLATION.md.
  */
final case class BlockContext(
    projectName: Option[String],
    projectDescription: Option[String],
    translationContext: Option[String],
    topLevelKey: String,
    siblingKeys: List[SiblingKey],
    masterLanguageCode: String,
    masterLanguageName: String,
    targetLanguageCode: String,
    targetLanguageName: String
)

/** One key within the block still needing a translation. */
final case class TranslationTarget(subKey: String, masterText: String)

/** A block-batched translation request: one shared `context`, and every
  * missing key in that block to translate in a single call, see "Cost and
  * latency" in TRANSLATION.md.
  */
final case class TranslationRequest(context: BlockContext, targets: List[TranslationTarget], model: String)

/** `translations` maps sub-key to translated text; a target key absent from
  * the map, or a subsequent placeholder-validation failure, is handled the
  * same as a request-level `Failure` by the caller (see AiTranslate).
  */
final case class TranslationResponse(translations: Map[String, String])

sealed trait TranslationOutcome
object TranslationOutcome {
  final case class Success(response: TranslationResponse) extends TranslationOutcome

  /** `fatal` marks a failure that will fail identically on every retry (eg.
    * a rejected API key, or a model id the provider no longer serves), as
    * opposed to one that might succeed on a smaller request or a later
    * attempt (eg. a rate limit or a transient network error). A fatal
    * failure stops `--translate` immediately instead of being retried
    * per-key and repeated for every other missing key, see "Error handling"
    * in TRANSLATION.md.
    */
  final case class Failure(reason: String, fatal: Boolean = false) extends TranslationOutcome
}

/** Provider-agnostic call shape. Implementations wrap a specific provider's
  * sttp-ai client; tests fake this trait directly rather than exercising a
  * live network call.
  */
trait Translator {
  def translateBlock(request: TranslationRequest): TranslationOutcome
}

/** One trait per linked-in provider, so a fake in a test is tied to the
  * provider it stands in for, see "Test coverage" in TRANSLATION.md.
  */
trait OpenAiTranslator extends Translator
trait ClaudeTranslator extends Translator
trait GeminiTranslator extends Translator
