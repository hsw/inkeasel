package io.github.hsw.inkeasel.catalog

/**
 * The label languages to show the catalog in, most wanted first, as Wikidata language codes (the catalog's
 * `labels-<code>` files): the UI language with its regional or script variant first, then [ENGLISH], then
 * [MULTILINGUAL] (Wikidata's language-independent labels). A label missing in every one of them falls back to
 * any other stored language, which is the query's part, not the chain's. Pure: it takes a BCP 47 language tag
 * (`Locale.toLanguageTag()`, `_` accepted too), so the caller decides which locale is the UI's.
 *
 * Variants are added only where Wikidata has them, so the chain never names a file the release cannot have.
 */
object LanguageChain {
    const val ENGLISH = "en"
    const val MULTILINGUAL = "mul"

    /** Android's legacy language codes (and Filipino) as Wikidata writes them. */
    private val LEGACY = mapOf("in" to "id", "iw" to "he", "ji" to "yi", "fil" to "tl")

    /** Regional variants Wikidata has labels in, apart from Chinese (see [chinese]) and Spanish (`es-419`). */
    private val REGIONAL = setOf("de-at", "de-ch", "en-ca", "en-gb", "en-us", "pt-br")

    /** Regions whose Spanish is Latin American (`es-419`); Android offers `es-US` as that variant. */
    private val LATIN_AMERICA = setOf(
        "419", "ar", "bo", "cl", "co", "cr", "cu", "do", "ec", "gt", "hn", "mx", "ni", "pa", "pe", "pr", "py",
        "sv", "us", "uy", "ve",
    )

    private val LANGUAGE = Regex("[a-z]{2,3}")
    private val SCRIPT = Regex("[a-z]{4}")
    private val REGION = Regex("[a-z]{2}|[0-9]{3}")

    fun of(tag: String): List<String> {
        val parts = tag.trim().replace('_', '-').lowercase().split('-')
        val language = parts[0].let { LEGACY[it] ?: it }.takeIf { LANGUAGE.matches(it) && it != "und" }
        var i = 1
        val script = parts.getOrNull(i)?.takeIf { SCRIPT.matches(it) }?.also { i++ }
        val region = parts.getOrNull(i)?.takeIf { REGION.matches(it) }
        val own = when (language) {
            null -> emptyList()
            "zh" -> chinese(script, region)
            "sr" -> if (script == "latn") listOf("sr-el", "sr") else listOf("sr-ec", "sr")
            "nb", "no" -> listOf("nb", "no")
            "nn" -> listOf("nn", "nb", "no")
            "es" -> listOfNotNull("es-419".takeIf { region in LATIN_AMERICA }, "es")
            else -> listOfNotNull(region?.let { "$language-$it" }?.takeIf { it in REGIONAL }, language)
        }
        return (own + ENGLISH + MULTILINGUAL).distinct()
    }

    /**
     * Traditional script (`Hant`, or Taiwan, Hong Kong, Macau without a script) or Simplified (everything
     * else, `zh` alone included, as on Android): the script's code, the regional codes with the user's region
     * first, then plain `zh`.
     */
    private fun chinese(script: String?, region: String?): List<String> {
        val traditional = script == "hant" || (script == null && region in setOf("tw", "hk", "mo"))
        val regions = when {
            traditional -> when (region) {
                "mo" -> listOf("mo", "hk", "tw")
                "hk" -> listOf("hk", "tw")
                else -> listOf("tw", "hk")
            }
            else -> when (region) {
                "sg", "my" -> listOf(region, "cn")
                else -> listOf("cn")
            }
        }
        return listOf(if (traditional) "zh-hant" else "zh-hans") + regions.map { "zh-$it" } + "zh"
    }
}
