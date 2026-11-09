package io.github.hsw.inkeasel

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun credit_imageCreditAfterTheArtist_whereTheLicenceAsks() {
        val p = wd("Guernica.jpg", 4520, 2060).copy(artist = "Pablo Picasso", licence = "CC BY-SA 4.0", attribution = "A photographer")
        assertEquals(
            listOf(
                "Pablo Picasso", Txt(R.string.viewer_attribution, "A photographer"),
                Txt(R.string.details_source_wikidata), "CC BY-SA 4.0",
            ),
            ViewerRules.credit(p),
        )
        assertEquals(listOf(Txt(R.string.details_source_wikidata)), ViewerRules.credit(wd("Guernica.jpg", 4520, 2060).copy(attribution = "")))
    }

    private val wdThumb = "https://upload.wikimedia.org/wikipedia/commons/thumb"

    private fun wd(file: String, w: Int?, h: Int?) =
        Picture("wd:Q1", "t", "", "unused-thumb", "unused-full", width = w, height = h, commonsFile = file)

    @Test
    fun wikidata_jpegThumbnailAtTheStepThatCovers() {
        val p = wd("Mona Lisa, by Leonardo da Vinci, from C2RMF retouched.jpg", 7479, 11146)
        val dir = "$wdThumb/e/ec/Mona_Lisa,_by_Leonardo_da_Vinci,_from_C2RMF_retouched.jpg"
        // portrait 0.67 in a 1920 × 1080 window: 725 px needed, 960 the first viewer step
        assertEquals("$dir/960px-Mona_Lisa,_by_Leonardo_da_Vinci,_from_C2RMF_retouched.jpg", ViewerRules.rendition(p, 1920, 1080))
        // 3840 × 2160: 1450 px, so 1920
        assertEquals("$dir/1920px-Mona_Lisa,_by_Leonardo_da_Vinci,_from_C2RMF_retouched.jpg", ViewerRules.rendition(p, 3840, 2160))
        // never past 3840
        val wide = wd("Mona Lisa, by Leonardo da Vinci, from C2RMF retouched.jpg", 20000, 5000)
        assertEquals("$dir/3840px-Mona_Lisa,_by_Leonardo_da_Vinci,_from_C2RMF_retouched.jpg", ViewerRules.rendition(wide, 7680, 4320))
    }

    @Test
    fun wikidata_narrowJpeg_theOriginal() {
        // 1250 px wide, 3840 needed: Commons does not scale up, so the original
        val p = wd("Rainbow.jpg", 1250, 700)
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/e/e8/Rainbow.jpg", ViewerRules.rendition(p, 3840, 2160))
        // wide enough for the step: the thumbnail
        assertEquals("$wdThumb/e/e8/Rainbow.jpg/960px-Rainbow.jpg", ViewerRules.rendition(p, 960, 540))
    }

    @Test
    fun wikidata_tiffAlwaysAThumbnail() {
        val name = "Paul Gauguin - Manaò tupapaú (Spirit of the Dead Watching) - 1965-1 - Albright–Knox Art Gallery.tiff"
        val enc = "Paul_Gauguin_-_Mana%C3%B2_tupapa%C3%BA_(Spirit_of_the_Dead_Watching)_-_1965-1_-_Albright%E2%80%93Knox_Art_Gallery.tiff"
        val p = wd(name, 3392, 2683)
        assertEquals("$wdThumb/f/fe/$enc/lossy-page1-1920px-$enc.jpg", ViewerRules.rendition(p, 1920, 1080))
        // 3840 needed but the original has 3392: the largest standard width it has
        assertEquals("$wdThumb/f/fe/$enc/lossy-page1-1920px-$enc.jpg", ViewerRules.rendition(p, 3840, 2160))
        // narrower than the first viewer step: still a thumbnail, never the TIFF itself
        assertEquals("$wdThumb/f/fe/$enc/lossy-page1-500px-$enc.jpg", ViewerRules.rendition(wd(name, 900, 700), 1920, 1080))
    }

    @Test
    fun wikidata_commonsFileWinsOverTheListedUrl_localStaysAFile() {
        // a picture with a Commons file name gets its rendition from the name, whatever its fullUrl
        assertEquals("$wdThumb/e/e8/Rainbow.jpg/1920px-Rainbow.jpg", ViewerRules.rendition(commons(4000, 2000).copy(commonsFile = "Rainbow.jpg"), 1920, 1080))
        val local = Picture("local:a.jpg", "a", "", "/x/a.jpg", "/x/a.jpg", local = true, commonsFile = "Rainbow.jpg")
        assertEquals("/x/a.jpg", ViewerRules.rendition(local, 1920, 1080))
    }

    @Test
    fun liveCommons_renditionFromItsFileName() {
        val p = Picture("commons:Rainbow.jpg", "Rainbow", "", "", "", width = 4000, height = 2000, commonsFile = "Rainbow.jpg")
        assertEquals("$wdThumb/e/e8/Rainbow.jpg/1280px-Rainbow.jpg", ViewerRules.rendition(p, 1280, 720))
        // 2560 px needed: no standard width, so 3840
        assertEquals("$wdThumb/e/e8/Rainbow.jpg/3840px-Rainbow.jpg", ViewerRules.rendition(p, 2560, 1600))
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
    fun listSteps_stopAtTheEnds() = runTest {
        val items = listOf(commons(1, 1).copy(id = "a"), commons(1, 1).copy(id = "b"), commons(1, 1).copy(id = "c"))
        val steps = ListSteps(items)
        val s = steps.open(items[1])
        assertEquals(ViewerState(items[1], 1, 3), s)
        assertTrue(s.showsPosition)
        assertEquals(ViewerState(items[2], 2, 3), steps.step(s, 1))
        assertEquals(ViewerState(items[0], 0, 3), steps.step(s, -1))
        assertNull(steps.step(steps.step(s, 1)!!, 1))
        assertNull(steps.step(steps.step(s, -1)!!, -1))
        assertNull(steps.step(s, 0))
    }

    @Test
    fun listSteps_pictureNoLongerInTheList_shownAlone() = runTest {
        val p = commons(1, 1).copy(id = "gone")
        val steps = ListSteps(listOf(commons(1, 1), commons(2, 1).copy(id = "commons:2")))
        val s = steps.open(p)
        assertEquals(ViewerState.alone(p), s)
        assertFalse(s.showsPosition)
        // alone, it has no neighbours, although index 0 is a picture of the list
        assertNull(steps.step(s, 1))
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

    @Test
    fun overlay_onOpen_aPressWithoutAFrameShowsTheRow() {
        // the press sent nothing: the row's "no frame" note is its answer
        assertEquals(ViewerOverlay.ACTIONS, ViewerOverlay.onOpen(byPress = true, frameChosen = false))
        assertEquals(ViewerOverlay.CREDIT, ViewerOverlay.onOpen(byPress = true, frameChosen = true))
        // from the details panel, as before
        assertEquals(ViewerOverlay.CREDIT, ViewerOverlay.onOpen(byPress = false, frameChosen = false))
        assertEquals(ViewerOverlay.CREDIT, ViewerOverlay.onOpen(byPress = false, frameChosen = true))
    }
}
