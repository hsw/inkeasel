package io.github.hsw.inkeasel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class ViewerRulesTest {
    private val thumb = "https://upload.wikimedia.org/wikipedia/commons/thumb/a/ad/Rainbow.jpg"

    private fun commons(w: Int, h: Int, full: String = "$thumb/1920px-Rainbow.jpg?utm_source=x") =
        Picture("commons:1", "Rainbow", "", "$thumb/500px-Rainbow.jpg", full, width = w, height = h)

    private val cma = Picture(
        "cma:2", "Fields", "", "https://cdn.example.org/2_web.jpg", "https://cdn.example.org/2_print.jpg",
        width = 7795, height = 4258,
    )

    @Test
    fun neededWidth_fitsTheLandscapeWindow() {
        // 2:1 on 16:9: as wide as the window; 4:3 on 16:9: as high as the window
        assertEquals(1920, ViewerRules.neededWidth(commons(4000, 2000), 1920, 1080))
        assertEquals(1440, ViewerRules.neededWidth(commons(4000, 3000), 1920, 1080))
        // a portrait window counts as its landscape twin: a rotation needs no other rendition
        assertEquals(1440, ViewerRules.neededWidth(commons(4000, 3000), 1080, 1920))
        // no known size: the window's long side
        assertEquals(2560, ViewerRules.neededWidth(commons(0, 0).copy(width = null, height = null), 1600, 2560))
    }

    @Test
    fun commons_smallestStandardStepThatCovers() {
        assertEquals("$thumb/1920px-Rainbow.jpg?utm_source=x", ViewerRules.rendition(commons(4000, 2000), 1920, 1080))
        assertEquals("$thumb/1280px-Rainbow.jpg?utm_source=x", ViewerRules.rendition(commons(4000, 2000), 1280, 720))
        // 2560 px needed: 2560 is no standard width, so 3840
        assertEquals("$thumb/3840px-Rainbow.jpg?utm_source=x", ViewerRules.rendition(commons(4000, 2500), 2560, 1600))
        assertEquals("$thumb/3840px-Rainbow.jpg?utm_source=x", ViewerRules.rendition(commons(8000, 4000), 3840, 2160))
        // a wider window than any step: 3840 at most
        assertEquals("$thumb/3840px-Rainbow.jpg?utm_source=x", ViewerRules.rendition(commons(16000, 8000), 7680, 4320))
    }

    @Test
    fun commons_neverBeyondWhatTheOriginalNeeds() {
        // a 1250 px wide original in a 4K window: the 1280 step covers all it has
        val small = commons(1250, 700, full = "$thumb/1920px-Rainbow.jpg")
        assertEquals("$thumb/1280px-Rainbow.jpg", ViewerRules.rendition(small, 3840, 2160))
    }

    @Test
    fun commons_originalNarrowerThanTheFrameRendition_asGiven() {
        val original = "https://upload.wikimedia.org/wikipedia/commons/a/ad/Rainbow.jpg?utm_content=thumbnail_unscaled"
        assertEquals(original, ViewerRules.rendition(commons(1800, 1000, full = original), 3840, 2160))
    }

    @Test
    fun cleveland_webUpToFullHd_printBeyond() {
        assertEquals(cma.thumbUrl, ViewerRules.rendition(cma, 1920, 1080))
        assertEquals(cma.thumbUrl, ViewerRules.rendition(cma, 1080, 2400)) // a phone, either way round
        assertEquals(cma.fullUrl, ViewerRules.rendition(cma, 2560, 1600))
        assertEquals(cma.fullUrl, ViewerRules.rendition(cma, 3840, 2160))
    }

    @Test
    fun local_fileAsIs() {
        val p = Picture("local:a.jpg", "a", "", "/x/a.jpg", "/x/a.jpg", local = true, width = 9000, height = 6000)
        assertEquals("/x/a.jpg", ViewerRules.rendition(p, 3840, 2160))
    }

    @Test
    fun credit_artistSourceLicence_onlyWhatIsKnown() {
        val p = commons(4000, 2000).copy(artist = "Someone", licence = "CC BY-SA 4.0")
        assertEquals(listOf("Someone", Txt(R.string.details_source_commons), "CC BY-SA 4.0"), ViewerRules.credit(p))
        assertEquals(listOf(Txt(R.string.details_source_commons)), ViewerRules.credit(commons(4000, 2000).copy(artist = " ")))
        val local = Picture("local:a.jpg", "a", "", "/x/a.jpg", "/x/a.jpg", local = true)
        assertEquals(listOf(Txt(R.string.tab_local)), ViewerRules.credit(local))
    }

    @Test
    fun failure_downloadAsTheCatalogSaysIt_elseWithItsMessage() {
        assertEquals(Txt(R.string.catalog_err_offline), ViewerRules.failure(UnknownHostException("x")))
        assertEquals(Txt(R.string.catalog_err_http, 404), ViewerRules.failure(HttpStatusException(404, "u")))
        assertEquals(Txt(R.string.viewer_failed, "bad file"), ViewerRules.failure(IOException("bad file")))
    }

    @Test
    fun swipe_pastTheThreshold() {
        assertEquals(1, ViewerRules.swipe(-200f, 100f)) // to the left: the next picture
        assertEquals(-1, ViewerRules.swipe(150f, 100f))
        assertEquals(0, ViewerRules.swipe(-60f, 100f))
    }

    @Test
    fun state_stepsStopAtTheEnds() {
        val items = listOf(commons(1, 1).copy(id = "a"), commons(1, 1).copy(id = "b"), commons(1, 1).copy(id = "c"))
        val s = ViewerState.of(items[1], items)
        assertEquals(1, s.index)
        assertEquals("c", s.step(1)?.picture?.id)
        assertEquals("a", s.step(-1)?.picture?.id)
        assertNull(s.step(1)!!.step(1))
        assertNull(s.step(-1)!!.step(-1))
    }

    @Test
    fun state_pictureNoLongerInTheList_shownAlone() {
        val p = commons(1, 1).copy(id = "gone")
        assertEquals(ViewerState(listOf(p), 0), ViewerState.of(p, listOf(commons(1, 1))))
    }

    @Test
    fun overlay_creditOnStep_actionsOnKeyOrTap() {
        assertEquals(ViewerOverlay.CREDIT, ViewerOverlay.HIDDEN.onStep())
        assertEquals(ViewerOverlay.ACTIONS, ViewerOverlay.ACTIONS.onStep())
        assertEquals(ViewerOverlay.ACTIONS, ViewerOverlay.CREDIT.onKey())
        assertEquals(ViewerOverlay.ACTIONS, ViewerOverlay.HIDDEN.onTap())
        assertEquals(ViewerOverlay.ACTIONS, ViewerOverlay.CREDIT.onTap())
        assertEquals(ViewerOverlay.HIDDEN, ViewerOverlay.ACTIONS.onTap())
    }
}
