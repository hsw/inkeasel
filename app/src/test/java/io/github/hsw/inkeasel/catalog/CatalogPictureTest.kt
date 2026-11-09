package io.github.hsw.inkeasel.catalog

import io.github.hsw.inkeasel.Picture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogPictureTest {
    private val sep = " / "

    private fun row(
        id: String = "wd:Q175036",
        wikidataId: String? = "Q175036",
        commonsFile: String? = "Mural del \"Guernica\" de Picasso.jpg",
        width: Int? = 4520,
        height: Int? = 2060,
        licence: String? = "CC BY-SA 4.0",
        licenceUrl: String? = null,
        attr: Boolean = true,
        attribution: String? = "A photographer",
        inception: Int? = 1937,
        title: String? = "Guernica",
        artists: List<String> = listOf("Pablo Picasso"),
        collection: String? = "Museo Reina Sofía",
        date: String? = null,
        pageUrl: String? = null,
        thumbUrl: String? = null,
        fullUrl: String? = null,
    ) = CatalogRow(
        id = id, source = id.substringBefore(':'), rank = 10, wikidataId = wikidataId, commonsFile = commonsFile,
        museumRef = null, fame = 71, width = width, height = height, licence = licence, licenceUrl = licenceUrl,
        attr = attr, attribution = attribution, inception = inception, title = title, artists = artists,
        collection = collection, date = date, pageUrl = pageUrl, thumbUrl = thumbUrl, fullUrl = fullUrl,
    )

    private val guernicaDir =
        "https://upload.wikimedia.org/wikipedia/commons/thumb/7/76/Mural_del_%22Guernica%22_de_Picasso.jpg"

    @Test
    fun wikidataRow_everyField() {
        assertEquals(
            Picture(
                id = "wd:Q175036",
                title = "Guernica",
                credit = "Pablo Picasso / 1937",
                thumbUrl = "$guernicaDir/500px-Mural_del_%22Guernica%22_de_Picasso.jpg",
                fullUrl = "$guernicaDir/1920px-Mural_del_%22Guernica%22_de_Picasso.jpg",
                artist = "Pablo Picasso",
                date = "1937",
                collection = "Museo Reina Sofía",
                pageUrl = "https://www.wikidata.org/wiki/Q175036",
                licence = "CC BY-SA 4.0",
                licenceUrl = "https://creativecommons.org/licenses/by-sa/4.0/",
                width = 4520,
                height = 2060,
                commonsFile = "Mural del \"Guernica\" de Picasso.jpg",
                attribution = "A photographer",
            ),
            CatalogPicture.of(row(), sep),
        )
        assertEquals("wd", CatalogPicture.of(row(), sep).source)
    }

    @Test
    fun severalCreators_joinedWithTheGivenSeparator_theCardTakesTheFirst() {
        val p = CatalogPicture.of(row(artists = listOf("Hubert van Eyck", "Jan van Eyck")), ", ")
        assertEquals("Hubert van Eyck, Jan van Eyck", p.artist)
        assertEquals("Hubert van Eyck, 1937", p.credit)
    }

    @Test
    fun noLabel_theFileNameAsTitle_noArtistNoCollection() {
        val p = CatalogPicture.of(row(title = null, artists = emptyList(), collection = null, inception = null), sep)
        assertEquals("Mural del \"Guernica\" de Picasso", p.title)
        assertNull(p.artist)
        assertNull(p.collection)
        assertNull(p.date)
        assertEquals("", p.credit)
        // a blank artist label counts as none
        assertNull(CatalogPicture.of(row(artists = listOf(" ")), sep).artist)
    }

    @Test
    fun nudity_theRowsLevel_checkedAgainstAFilter() {
        val nude = CatalogPicture.of(row().copy(nudity = NudityLevel.NUDE.ordinal), sep)
        assertEquals(NudityLevel.NUDE.ordinal, nude.nudity)
        assertEquals(true, nude.shownUnder(NudityFilter.SHOW_ALL.nudityBelow))
        assertEquals(true, nude.shownUnder(NudityFilter.HIDE_EXPLICIT.nudityBelow))
        assertEquals(false, nude.shownUnder(NudityFilter.HIDE_NUDES.nudityBelow))
        val explicit = CatalogPicture.of(row().copy(nudity = NudityLevel.EXPLICIT.ordinal), sep)
        assertEquals(false, explicit.shownUnder(NudityFilter.HIDE_EXPLICIT.nudityBelow))
        assertEquals(true, explicit.shownUnder(NudityFilter.SHOW_ALL.nudityBelow))
        // one of My pictures passes every filter
        val mine = Picture("local:a.png", "a.png", "", "/a.png", "/a.png", local = true, nudity = NudityLevel.EXPLICIT.ordinal)
        assertEquals(true, mine.shownUnder(NudityFilter.HIDE_NUDES.nudityBelow))
    }

    @Test
    fun cardWidth_byColumnWidth() {
        assertEquals(330, CatalogPicture.cardWidth(200))
        assertEquals(330, CatalogPicture.cardWidth(330))
        assertEquals(500, CatalogPicture.cardWidth(331))
        assertEquals(500, CatalogPicture.cardWidth(402)) // a TV card: 201 dp at density 2
        assertEquals(500, CatalogPicture.cardWidth(900))
    }

    @Test
    fun thumbWidth_byTheWidthThePictureTakesInTheCard() {
        val tv = 402 // a TV card: 201 dp at density 2, 1024×600 frame shape (236 px high)
        val shape = 1024f / 600
        assertEquals(500, CatalogPicture.thumbWidth(tv, shape, 1920, 1080)) // wider than the card: the column
        assertEquals(500, CatalogPicture.thumbWidth(tv, shape, 1024, 600))
        // the Mona Lisa (portrait, about 2:3): about 159 px wide in the card, so the smaller thumbnail
        assertEquals(330, CatalogPicture.thumbWidth(tv, shape, 7479, 11146))
        assertEquals(330, CatalogPicture.thumbWidth(tv, shape, 1000, 1000)) // square: 236 px
        // a little narrower than the card still needs more than 330 px
        assertEquals(500, CatalogPicture.thumbWidth(tv, shape, 1500, 1000)) // 354 px
        assertEquals(330, CatalogPicture.thumbWidth(300, shape, 1920, 1080)) // a narrow column as before
        assertEquals(500, CatalogPicture.thumbWidth(tv, shape, null, 1000)) // unknown size: the column
        assertEquals(500, CatalogPicture.thumbWidth(tv, shape, 0, 0))
    }

    @Test
    fun cardWidth_asChosen_narrowOriginal_theOriginal() {
        assertEquals(
            "$guernicaDir/330px-Mural_del_%22Guernica%22_de_Picasso.jpg",
            CatalogPicture.of(row(), sep, cardWidth = 330).thumbUrl,
        )
        // 1200 px wide: wide enough for the card, not for the frame's 1920 px
        val narrow = CatalogPicture.of(row(width = 1200, height = 900), sep)
        assertEquals("$guernicaDir/500px-Mural_del_%22Guernica%22_de_Picasso.jpg", narrow.thumbUrl)
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/7/76/Mural_del_%22Guernica%22_de_Picasso.jpg", narrow.fullUrl)
    }

    @Test
    fun tiff_alwaysThumbnails() {
        val p = CatalogPicture.of(row(commonsFile = "Rainbow.tif", width = 1200, height = 900), sep)
        val dir = "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a9/Rainbow.tif"
        assertEquals("$dir/lossy-page1-500px-Rainbow.tif.jpg", p.thumbUrl)
        assertEquals("$dir/lossy-page1-960px-Rainbow.tif.jpg", p.fullUrl)
    }

    @Test
    fun licenceUrl_theReleasesOwnFirst_elseTheDeed_elseNone() {
        assertEquals("https://example.org/own", CatalogPicture.of(row(licenceUrl = "https://example.org/own"), sep).licenceUrl)
        assertEquals("https://creativecommons.org/publicdomain/zero/1.0/", CatalogPicture.of(row(licence = "CC0"), sep).licenceUrl)
        assertNull(CatalogPicture.of(row(licence = "Public domain"), sep).licenceUrl)
        assertNull(CatalogPicture.of(row(licence = null), sep).licenceUrl)
    }

    @Test
    fun attribution_onlyWhereTheLicenceAsks() {
        assertNull(CatalogPicture.of(row(attr = false, attribution = "Leonardo da Vinci"), sep).attribution)
        assertNull(CatalogPicture.of(row(attr = true, attribution = " "), sep).attribution)
        assertNull(CatalogPicture.of(row(attr = true, attribution = null), sep).attribution)
    }

    @Test
    fun liveRow_keepsItsOwnTextsAndUrls() {
        val p = CatalogPicture.of(
            row(
                id = "cma:94979", wikidataId = null, commonsFile = null, licence = "CC0", attr = false, attribution = null,
                inception = null, title = "Water Lilies", artists = listOf("Claude Monet (French, 1840–1926)"),
                collection = "Cleveland Museum of Art", date = "c. 1915–26", pageUrl = "https://example.org/art/94979",
                thumbUrl = "https://example.org/94979_web.jpg", fullUrl = "https://example.org/94979_print.jpg",
            ),
            sep,
        )
        assertEquals("cma", p.source)
        assertEquals("Water Lilies", p.title)
        assertEquals("Claude Monet (French, 1840–1926) / c. 1915–26", p.credit)
        assertEquals("c. 1915–26", p.date)
        assertEquals("https://example.org/art/94979", p.pageUrl)
        assertEquals("https://example.org/94979_web.jpg", p.thumbUrl)
        assertEquals("https://example.org/94979_print.jpg", p.fullUrl)
        assertNull(p.commonsFile)
    }

    @Test
    fun liveCommonsRow_urlsFromTheFileWhenNotGiven() {
        val p = CatalogPicture.of(row(id = "commons:Rainbow.jpg", wikidataId = null, commonsFile = "Rainbow.jpg", width = 4000), sep)
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/thumb/e/e8/Rainbow.jpg/500px-Rainbow.jpg", p.thumbUrl)
        assertNull(p.pageUrl)
    }

    @Test
    fun deedUrl_knownCreativeCommonsLicences() {
        val cc = "https://creativecommons.org"
        assertEquals("$cc/licenses/by-sa/4.0/", Licences.deedUrl("CC BY-SA 4.0"))
        assertEquals("$cc/licenses/by-sa/3.0/", Licences.deedUrl("CC BY-SA 3.0"))
        assertEquals("$cc/licenses/by/2.0/", Licences.deedUrl("CC BY 2.0"))
        assertEquals("$cc/licenses/by/2.5/", Licences.deedUrl("CC BY 2.5"))
        assertEquals("$cc/licenses/by-sa/2.0/fr/", Licences.deedUrl("CC BY-SA 2.0 fr"))
        assertEquals("$cc/licenses/by-sa/3.0/igo/", Licences.deedUrl("CC BY-SA 3.0 igo"))
        assertEquals("$cc/publicdomain/zero/1.0/", Licences.deedUrl("CC0"))
        assertEquals("$cc/publicdomain/zero/1.0/", Licences.deedUrl("CC0 1.0"))
    }

    @Test
    fun deedUrl_anyOtherIsNone() {
        for (s in listOf(null, "", "Public domain", "PDM-owner", "FAL", "Attribution", "GODL-India", "CC BY-NC 4.0", "CC BY-SA 4.0 de", "CC BY 5.0")) {
            assertNull(s, Licences.deedUrl(s))
        }
    }
}
