package io.github.hsw.inkeasel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class PictureDetailsTest {
    private val e6 = FrameAdvert.parse(byteArrayOf(0x43, 0x13, 0x64, 0x15))
    private val bw = FrameAdvert.parse(byteArrayOf(0x03, 0x13, 0x64))

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
        assertNull(PictureDetails.sourceName("other"))
    }

    @Test
    fun preview_perSelectedFrame() {
        assertEquals(PictureDetails.Preview.NoFrame, PictureDetails.preview(null))
        assertEquals(
            PictureDetails.Preview.Render(FrameFormats.DEFAULT, "NM000001"),
            PictureDetails.preview(SelectedFrame("NM000001", "AA:BB:CC:DD:EE:01", e6, 1L)),
        )
        val unsupported = PictureDetails.preview(SelectedFrame("NM000001", "AA:BB:CC:DD:EE:01", bw, 1L))
        assertEquals(
            PictureDetails.Preview.Unsupported(
                Txt(R.string.details_preview_unsupported, "NM000001", Txt(R.string.reason_untested_format)),
            ),
            unsupported,
        )
    }

    @Test
    fun previewFailure() {
        assertEquals(
            Txt(R.string.details_preview_failed, Txt(R.string.upload_err_decode)),
            PictureDetails.previewFailure(TxtException(Txt(R.string.upload_err_decode))),
        )
        assertEquals(Txt(R.string.catalog_err_offline), PictureDetails.previewFailure(UnknownHostException("x")))
        assertEquals(Txt(R.string.catalog_err_http, 404), PictureDetails.previewFailure(HttpStatusException(404, "u")))
        assertEquals(Txt(R.string.details_preview_failed, "boom"), PictureDetails.previewFailure(IOException("boom")))
    }
}
