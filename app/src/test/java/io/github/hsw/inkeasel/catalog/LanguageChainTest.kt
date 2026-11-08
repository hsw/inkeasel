package io.github.hsw.inkeasel.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageChainTest {
    private fun chain(tag: String) = LanguageChain.of(tag)

    @Test
    fun plainLanguageThenEnglishThenMul() {
        assertEquals(listOf("ru", "en", "mul"), chain("ru"))
        assertEquals(listOf("ru", "en", "mul"), chain("ru-RU"))
        assertEquals(listOf("de", "en", "mul"), chain("de-DE"))
        assertEquals(listOf("ast", "en", "mul"), chain("ast-ES"))
    }

    @Test
    fun englishAppearsOnce() {
        assertEquals(listOf("en", "mul"), chain("en"))
        assertEquals(listOf("en-gb", "en", "mul"), chain("en-GB"))
        assertEquals(listOf("en-us", "en", "mul"), chain("en-US"))
        assertEquals(listOf("en", "mul"), chain("en-IN"))
    }

    @Test
    fun regionalVariantsWikidataHas() {
        assertEquals(listOf("pt-br", "pt", "en", "mul"), chain("pt-BR"))
        assertEquals(listOf("pt", "en", "mul"), chain("pt-PT"))
        assertEquals(listOf("de-ch", "de", "en", "mul"), chain("de-CH"))
        assertEquals(listOf("de-at", "de", "en", "mul"), chain("de-AT"))
        assertEquals(listOf("en-ca", "en", "mul"), chain("en-CA"))
        // a region without a Wikidata variant names no file
        assertEquals(listOf("fr", "en", "mul"), chain("fr-CA"))
    }

    @Test
    fun latinAmericanSpanish() {
        assertEquals(listOf("es-419", "es", "en", "mul"), chain("es-419"))
        assertEquals(listOf("es-419", "es", "en", "mul"), chain("es-MX"))
        assertEquals(listOf("es-419", "es", "en", "mul"), chain("es-US"))
        assertEquals(listOf("es", "en", "mul"), chain("es-ES"))
        assertEquals(listOf("es", "en", "mul"), chain("es"))
    }

    @Test
    fun chinese() {
        assertEquals(listOf("zh-hant", "zh-tw", "zh-hk", "zh", "en", "mul"), chain("zh-Hant-TW"))
        assertEquals(listOf("zh-hant", "zh-tw", "zh-hk", "zh", "en", "mul"), chain("zh-TW"))
        assertEquals(listOf("zh-hant", "zh-tw", "zh-hk", "zh", "en", "mul"), chain("zh-Hant"))
        assertEquals(listOf("zh-hant", "zh-hk", "zh-tw", "zh", "en", "mul"), chain("zh-Hant-HK"))
        assertEquals(listOf("zh-hant", "zh-hk", "zh-tw", "zh", "en", "mul"), chain("zh-HK"))
        assertEquals(listOf("zh-hant", "zh-mo", "zh-hk", "zh-tw", "zh", "en", "mul"), chain("zh-MO"))
        assertEquals(listOf("zh-hans", "zh-cn", "zh", "en", "mul"), chain("zh-Hans-CN"))
        assertEquals(listOf("zh-hans", "zh-cn", "zh", "en", "mul"), chain("zh-CN"))
        assertEquals(listOf("zh-hans", "zh-cn", "zh", "en", "mul"), chain("zh"))
        assertEquals(listOf("zh-hans", "zh-sg", "zh-cn", "zh", "en", "mul"), chain("zh-SG"))
        assertEquals(listOf("zh-hans", "zh-my", "zh-cn", "zh", "en", "mul"), chain("zh-Hans-MY"))
        // the script wins over the region
        assertEquals(listOf("zh-hans", "zh-cn", "zh", "en", "mul"), chain("zh-Hans-HK"))
    }

    @Test
    fun serbianScripts() {
        assertEquals(listOf("sr-el", "sr", "en", "mul"), chain("sr-Latn"))
        assertEquals(listOf("sr-el", "sr", "en", "mul"), chain("sr-Latn-RS"))
        assertEquals(listOf("sr-ec", "sr", "en", "mul"), chain("sr-Cyrl-RS"))
        assertEquals(listOf("sr-ec", "sr", "en", "mul"), chain("sr"))
    }

    @Test
    fun norwegian() {
        assertEquals(listOf("nb", "no", "en", "mul"), chain("nb"))
        assertEquals(listOf("nb", "no", "en", "mul"), chain("nb-NO"))
        assertEquals(listOf("nb", "no", "en", "mul"), chain("no"))
        assertEquals(listOf("nn", "nb", "no", "en", "mul"), chain("nn-NO"))
    }

    @Test
    fun androidLegacyCodes() {
        assertEquals(listOf("id", "en", "mul"), chain("in"))
        assertEquals(listOf("id", "en", "mul"), chain("in-ID"))
        assertEquals(listOf("he", "en", "mul"), chain("iw-IL"))
        assertEquals(listOf("yi", "en", "mul"), chain("ji"))
        assertEquals(listOf("tl", "en", "mul"), chain("fil-PH"))
        assertEquals(listOf("id", "en", "mul"), chain("id"))
        assertEquals(listOf("he", "en", "mul"), chain("he"))
    }

    @Test
    fun underscoresCaseAndExtensions() {
        assertEquals(listOf("pt-br", "pt", "en", "mul"), chain("pt_BR"))
        assertEquals(listOf("pt-br", "pt", "en", "mul"), chain(" PT-br "))
        assertEquals(listOf("de-ch", "de", "en", "mul"), chain("de-CH-u-co-phonebk"))
        assertEquals(listOf("ru", "en", "mul"), chain("ru-RU-x-private"))
    }

    @Test
    fun unusableTagsFallBackToEnglish() {
        for (tag in listOf("", "und", "x-klingon", "i-klingon", "english", "1", "-")) {
            assertEquals(tag, listOf("en", "mul"), chain(tag))
        }
    }
}
