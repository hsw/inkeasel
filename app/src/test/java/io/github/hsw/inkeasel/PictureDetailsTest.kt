package io.github.hsw.inkeasel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PictureDetailsTest {
    @Test
    fun rows_everyField_inOrder() {
        val p = Picture(
            "commons:3", "Valley", "Someone, CC BY-SA 4.0", "t", "f",
            artist = "Someone", date = "2026-09-21", collection = "A museum", pageUrl = "https://example.org/c",
            licence = "CC BY-SA 4.0", licenceUrl = "https://example.org/by-sa", width = 6744, height = 4500,
        )
        assertEquals(
            listOf(
                DetailRow(Txt(R.string.details_artist), "Someone"),
                DetailRow(Txt(R.string.details_date), "2026-09-21"),
                DetailRow(Txt(R.string.details_collection), "A museum"),
                DetailRow(Txt(R.string.details_source), Txt(R.string.details_source_commons)),
                DetailRow(Txt(R.string.details_licence), "CC BY-SA 4.0"),
                DetailRow(Txt(R.string.details_licence_link), "https://example.org/by-sa"),
                DetailRow(Txt(R.string.details_size), Txt(R.string.details_size_px, 6744, 4500)),
                DetailRow(Txt(R.string.details_page), "https://example.org/c"),
            ),
            PictureDetails.rows(p),
        )
    }

    @Test
    fun rows_imageCreditAfterTheLicence_onlyWhereAsked() {
        val p = Picture(
            "wd:Q175036", "Guernica", "", "t", "f", artist = "Pablo Picasso", licence = "CC BY-SA 4.0",
            licenceUrl = "https://creativecommons.org/licenses/by-sa/4.0/", attribution = "A photographer",
            pageUrl = "https://www.wikidata.org/wiki/Q175036",
        )
        assertEquals(
            listOf(
                DetailRow(Txt(R.string.details_artist), "Pablo Picasso"),
                DetailRow(Txt(R.string.details_source), Txt(R.string.details_source_wikidata)),
                DetailRow(Txt(R.string.details_licence), "CC BY-SA 4.0"),
                DetailRow(Txt(R.string.details_licence_link), "https://creativecommons.org/licenses/by-sa/4.0/"),
                DetailRow(Txt(R.string.details_attribution), "A photographer"),
                DetailRow(Txt(R.string.details_page), "https://www.wikidata.org/wiki/Q175036"),
            ),
            PictureDetails.rows(p),
        )
        assertEquals(PictureDetails.rows(p.copy(attribution = null)), PictureDetails.rows(p.copy(attribution = " ")))
    }

    @Test
    fun rows_onlyWhatIsKnown() {
        val local = Picture("local:a.jpg", "a", "", "/x/a.jpg", "/x/a.jpg", local = true, width = 800, height = 600)
        assertEquals(
            listOf(
                DetailRow(Txt(R.string.details_source), Txt(R.string.tab_local)),
                DetailRow(Txt(R.string.details_size), Txt(R.string.details_size_px, 800, 600)),
            ),
            PictureDetails.rows(local),
        )
        // an unknown source and a size with one side missing give no line; nor does an empty text
        assertEquals(emptyList<DetailRow>(), PictureDetails.rows(Picture("x:1", "t", "", "t", "f", artist = "", width = 5)))
    }

    @Test
    fun sourceNames_matchTheSources() {
        assertEquals(Txt(R.string.details_source_cma), PictureDetails.sourceName(ClevelandPaintings.id))
        assertEquals(Txt(R.string.details_source_commons), PictureDetails.sourceName(CommonsLandscapes.id))
        assertEquals(Txt(R.string.tab_local), PictureDetails.sourceName("local"))
        assertEquals(Txt(R.string.details_source_wikidata), PictureDetails.sourceName("wd"))
        assertNull(PictureDetails.sourceName("other"))
    }
}
