package io.github.hsw.inkeasel.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The Guernica, Gauguin TIFF and long Giorgione URLs below were checked against upload.wikimedia.org (HTTP 200,
 * image/jpeg); the WebP thumbnail stays WebP there.
 */
class CommonsUrlsTest {
    private val base = "https://upload.wikimedia.org/wikipedia/commons"

    @Test
    fun thumb_hashPathAndUnderscores() {
        assertEquals(
            "$base/thumb/e/ec/Mona_Lisa,_by_Leonardo_da_Vinci,_from_C2RMF_retouched.jpg/" +
                "960px-Mona_Lisa,_by_Leonardo_da_Vinci,_from_C2RMF_retouched.jpg",
            CommonsUrls.thumb("Mona Lisa, by Leonardo da Vinci, from C2RMF retouched.jpg", 960),
        )
        // a name given with underscores, or with stray spaces around it, is the same file
        assertEquals(CommonsUrls.thumb("Rainbow.jpg", 500), CommonsUrls.thumb(" Rainbow.jpg ", 500))
        assertEquals(CommonsUrls.thumb("A b.jpg", 500), CommonsUrls.thumb("A_b.jpg", 500))
    }

    @Test
    fun thumb_quotesAreEncoded_commasAndParenthesesStay() {
        assertEquals(
            "$base/thumb/7/76/Mural_del_%22Guernica%22_de_Picasso.jpg/960px-Mural_del_%22Guernica%22_de_Picasso.jpg",
            CommonsUrls.thumb("Mural del \"Guernica\" de Picasso.jpg", 960),
        )
    }

    @Test
    fun thumb_questionMarkAmpersandPlus_encoded() {
        val enc = "What%3F_A_%26_B_%2B_C.jpg"
        assertEquals("$base/thumb/7/7a/$enc/330px-$enc", CommonsUrls.thumb("What? A & B + C.jpg", 330))
    }

    @Test
    fun thumb_nonAscii_utf8Encoded() {
        val enc = "Ivan_Shishkin_-_%D0%A3%D1%82%D1%80%D0%BE_%D0%B2_%D1%81%D0%BE%D1%81%D0%BD%D0%BE%D0%B2%D0%BE%D0%BC_%D0%BB%D0%B5%D1%81%D1%83.jpg"
        assertEquals("$base/thumb/6/65/$enc/500px-$enc", CommonsUrls.thumb("Ivan Shishkin - Утро в сосновом лесу.jpg", 500))
        assertEquals("$base/9/91/Gem%C3%A4lde.jpeg", CommonsUrls.original("Gemälde.jpeg"))
    }

    @Test
    fun thumb_tiff_firstPageAsJpeg() {
        val enc = "Paul_Gauguin_-_Mana%C3%B2_tupapa%C3%BA_(Spirit_of_the_Dead_Watching)_-_1965-1_-_Albright%E2%80%93Knox_Art_Gallery.tiff"
        assertEquals(
            "$base/thumb/f/fe/$enc/lossy-page1-960px-$enc.jpg",
            CommonsUrls.thumb("Paul Gauguin - Manaò tupapaú (Spirit of the Dead Watching) - 1965-1 - Albright–Knox Art Gallery.tiff", 960),
        )
        assertEquals("$base/thumb/a/a9/Rainbow.tif/lossy-page1-1280px-Rainbow.tif.jpg", CommonsUrls.thumb("Rainbow.tif", 1280))
    }

    @Test
    fun thumb_svg_asPng() {
        assertEquals("$base/thumb/3/32/Some_map.svg/1920px-Some_map.svg.png", CommonsUrls.thumb("Some map.svg", 1920))
    }

    @Test
    fun thumb_otherFormats_keepTheirExtension() {
        assertEquals("$base/thumb/1/1e/Rainbow.png/500px-Rainbow.png", CommonsUrls.thumb("Rainbow.png", 500))
        assertEquals("$base/thumb/0/08/Rainbow.webp/960px-Rainbow.webp", CommonsUrls.thumb("Rainbow.webp", 960))
        assertEquals("$base/thumb/f/f7/A.JPG/250px-A.JPG", CommonsUrls.thumb("A.JPG", 250))
    }

    private val giorgione =
        "Giorgio da Castelfranco, gen. Giorgione, , Kunsthistorisches Museum Wien, Gemäldegalerie - " +
            "Bildnis einer jungen Frau (\"Laura\") - GG 31 - Kunsthistorisches Museum"
    private val giorgioneEnc =
        "Giorgio_da_Castelfranco,_gen._Giorgione,_,_Kunsthistorisches_Museum_Wien,_Gem%C3%A4ldegalerie_-_" +
            "Bildnis_einer_jungen_Frau_(%22Laura%22)_-_GG_31_-_Kunsthistorisches_Museum"

    @Test
    fun thumb_longName_thumbnailWithTheLowercaseExtension() {
        // 166 bytes: over Commons' 160
        assertEquals("$base/thumb/2/2f/$giorgioneEnc.jpg/960px-thumbnail.jpg", CommonsUrls.thumb("$giorgione.jpg", 960))
        assertEquals("$base/thumb/2/20/$giorgioneEnc.JPG/500px-thumbnail.jpg", CommonsUrls.thumb("$giorgione.JPG", 500))
        assertEquals("$base/thumb/5/5f/$giorgioneEnc.tif/lossy-page1-960px-thumbnail.tif.jpg", CommonsUrls.thumb("$giorgione.tif", 960))
        assertEquals("$base/thumb/6/60/$giorgioneEnc.svg/960px-thumbnail.svg.png", CommonsUrls.thumb("$giorgione.svg", 960))
    }

    @Test
    fun thumb_longNameCountsUtf8Bytes() {
        val at160 = "a".repeat(156) + ".jpg"
        val at161 = "a".repeat(157) + ".jpg"
        assertEquals("960px-$at160", CommonsUrls.thumb(at160, 960).substringAfterLast('/'))
        assertEquals("960px-thumbnail.jpg", CommonsUrls.thumb(at161, 960).substringAfterLast('/'))
        // 79 two-byte letters and ".jpg": 162 bytes in 83 characters
        assertEquals("960px-thumbnail.jpg", CommonsUrls.thumb("ä".repeat(79) + ".jpg", 960).substringAfterLast('/'))
    }

    @Test
    fun thumb_everyStandardWidth_noOther() {
        for (w in listOf(20, 40, 60, 120, 250, 330, 500, 960, 1280, 1920, 3840)) {
            assertEquals("$base/thumb/e/e8/Rainbow.jpg/${w}px-Rainbow.jpg", CommonsUrls.thumb("Rainbow.jpg", w))
        }
        for (w in listOf(0, 100, 800, 1024, 2560, 4000)) {
            assertThrows(IllegalArgumentException::class.java) { CommonsUrls.thumb("Rainbow.jpg", w) }
        }
    }

    @Test
    fun step_smallestStandardWidthThatCovers() {
        assertEquals(20, CommonsUrls.step(1))
        assertEquals(330, CommonsUrls.step(330))
        assertEquals(500, CommonsUrls.step(331))
        assertEquals(1920, CommonsUrls.step(1281))
        assertEquals(3840, CommonsUrls.step(10_000))
    }

    @Test
    fun url_wideOriginal_thumbnailAtTheStep() {
        assertEquals("$base/thumb/e/e8/Rainbow.jpg/500px-Rainbow.jpg", CommonsUrls.url("Rainbow.jpg", 400, 4000))
        assertEquals("$base/thumb/e/e8/Rainbow.jpg/1920px-Rainbow.jpg", CommonsUrls.url("Rainbow.jpg", 1920, 1921))
        // an unknown width: the step as asked
        assertEquals("$base/thumb/e/e8/Rainbow.jpg/960px-Rainbow.jpg", CommonsUrls.url("Rainbow.jpg", 960, null))
        assertEquals("$base/thumb/e/e8/Rainbow.jpg/960px-Rainbow.jpg", CommonsUrls.url("Rainbow.jpg", 960, 0))
    }

    @Test
    fun url_narrowerThanRequested_theOriginalForJpegPngWebp() {
        assertEquals("$base/e/e8/Rainbow.jpg", CommonsUrls.url("Rainbow.jpg", 1920, 1800))
        assertEquals("$base/e/e8/Rainbow.jpg", CommonsUrls.url("Rainbow.jpg", 1920, 1920))
        assertEquals("$base/1/1e/Rainbow.png", CommonsUrls.url("Rainbow.png", 500, 400))
        assertEquals("$base/0/08/Rainbow.webp", CommonsUrls.url("Rainbow.webp", 960, 900))
        // the long name keeps its full name in the original's URL
        assertEquals("$base/2/2f/$giorgioneEnc.jpg", CommonsUrls.url("$giorgione.jpg", 3840, 2474))
    }

    @Test
    fun url_tiffAlwaysAThumbnail_atMostItsWidth() {
        assertEquals("$base/thumb/a/a9/Rainbow.tif/lossy-page1-1920px-Rainbow.tif.jpg", CommonsUrls.url("Rainbow.tif", 1920, 1920))
        assertEquals("$base/thumb/a/a9/Rainbow.tif/lossy-page1-1280px-Rainbow.tif.jpg", CommonsUrls.url("Rainbow.tif", 1920, 1800))
        assertEquals("$base/thumb/a/a9/Rainbow.tif/lossy-page1-20px-Rainbow.tif.jpg", CommonsUrls.url("Rainbow.tif", 500, 10))
    }

    @Test
    fun url_svgAtAnyWidth() {
        assertEquals("$base/thumb/2/2a/Rainbow.svg/3840px-Rainbow.svg.png", CommonsUrls.url("Rainbow.svg", 3840, 512))
    }
}
