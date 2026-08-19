package msgman

/** ISO 639-1 code to English language name, for the subset of languages a
  * messages file is realistically translated into. Falls back to the bare
  * code for anything not listed here, rather than failing the translation
  * over a missing display name.
  */
object Languages {

  private val names: Map[String, String] = Map(
    "en" -> "English",
    "cy" -> "Welsh",
    "fr" -> "French",
    "de" -> "German",
    "es" -> "Spanish",
    "it" -> "Italian",
    "pt" -> "Portuguese",
    "nl" -> "Dutch",
    "pl" -> "Polish",
    "ro" -> "Romanian",
    "sv" -> "Swedish",
    "da" -> "Danish",
    "no" -> "Norwegian",
    "fi" -> "Finnish",
    "el" -> "Greek",
    "tr" -> "Turkish",
    "ru" -> "Russian",
    "uk" -> "Ukrainian",
    "ar" -> "Arabic",
    "he" -> "Hebrew",
    "hi" -> "Hindi",
    "ur" -> "Urdu",
    "zh" -> "Chinese",
    "ja" -> "Japanese",
    "ko" -> "Korean",
    "ga" -> "Irish",
    "gd" -> "Scottish Gaelic"
  )

  def name(code: String): String = names.getOrElse(code, code)
}
