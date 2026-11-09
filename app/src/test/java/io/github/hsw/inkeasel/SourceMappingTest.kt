package io.github.hsw.inkeasel

import io.github.hsw.inkeasel.catalog.LiveRow
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceMappingTest {
    private fun fixture(name: String) =
        JSONObject(javaClass.getResource("/catalog/$name")!!.readText())

    @Test
    fun cleveland_mapsEveryField() {
        val list = ClevelandMapping.rows(fixture("cleveland.json"))
        // the portrait (778×893) and the artwork without images are left out
        assertEquals(listOf("cma:152006", "cma:99"), list.map { it.id })
        assertEquals(
            LiveRow(
                id = "cma:152006",
                title = "Rocky, Wooded Landscape with a Dell and Weir",
                artists = listOf("Thomas Gainsborough", "Anonymous Landscape Painter"),
                date = "c. 1782–1783",
                collection = "Cleveland Museum of Art",
                pageUrl = "https://clevelandart.org/art/1984.59",
                licence = "CC0",
                width = 5500,
                height = 4211,
                museumRef = "1984.59",
                thumbUrl = "https://openaccess-cdn.clevelandart.org/1984.59/1984.59_web.jpg",
                fullUrl = "https://openaccess-cdn.clevelandart.org/1984.59/1984.59_print.jpg",
            ),
            list[0],
        )
        assertTrue(ClevelandMapping.FIELDS.split(',').contains("accession_number"))
    }

    @Test
    fun cleveland_missingFieldsStayNull() {
        val r = ClevelandMapping.rows(fixture("cleveland.json"))[1]
        assertEquals("Untitled", r.title)
        assertEquals(emptyList<String>(), r.artists)
        // no print: the frame gets the web image, whose size is the largest known
        assertEquals("https://openaccess-cdn.clevelandart.org/99/99_web.jpg", r.fullUrl)
        assertEquals(1100, r.width)
        assertEquals(700, r.height)
        assertNull(r.date)
        assertNull(r.pageUrl)
        assertNull(r.licence)
        assertNull(r.licenceUrl)
        assertNull(r.museumRef) // blank
        assertNull(r.commonsFile)
        assertEquals("Cleveland Museum of Art", r.collection)
    }

    @Test
    fun cleveland_emptyTitleIsNull() {
        val a = JSONObject(
            """{"id": 5, "title": " \r\n", "images": {"web": {"url": "https://cdn.example.org/5.jpg", "width": "1200", "height": "800"}}}""",
        )
        assertNull(ClevelandMapping.row(a)!!.title)
    }

    @Test
    fun commons_mapsEveryField() {
        val page = CommonsMapping.page(fixture("commons.json"))
        assertEquals("20260909101556|199181121", page.next)
        // the 7:1 banner and the pages without image info are left out; the id is the file name
        assertEquals(listOf("commons:Example valley at dawn - 06.jpg", "commons:Example lake.jpeg"), page.items.map { it.id })
        assertEquals(
            LiveRow(
                id = "commons:Example valley at dawn - 06.jpg",
                title = "Example valley at dawn - 06",
                artists = listOf("Example Photographer & Friend"),
                date = "2026-09-21",
                pageUrl = "https://commons.wikimedia.org/w/index.php?curid=1001",
                licence = "CC BY-SA 4.0",
                licenceUrl = "https://creativecommons.org/licenses/by-sa/4.0",
                width = 6744,
                height = 4500,
                // renditions are built from the file name, as for the release's rows
                commonsFile = "Example valley at dawn - 06.jpg",
            ),
            page.items[0],
        )
    }

    @Test
    fun commons_fallbacks() {
        val r = CommonsMapping.page(fixture("commons.json")).items[1]
        assertEquals("Example lake", r.title) // the file name without its extension
        assertEquals("Example lake.jpeg", r.commonsFile)
        assertNull(r.thumbUrl)
        assertNull(r.fullUrl)
        assertEquals(emptyList<String>(), r.artists)
        assertEquals("1890", r.date)
        assertEquals("https://commons.wikimedia.org/wiki/File:Example_lake.jpeg", r.pageUrl)
        assertEquals("https://example.org/pd", r.licenceUrl)
        assertNull(r.collection)
        assertEquals(3000, r.width)
    }

    @Test
    fun commons_noFileNameNoRow() {
        val info = """"imageinfo": [{"width": 3000, "height": 2000}]"""
        assertNull(CommonsMapping.row(JSONObject("""{"pageid": 7, $info}""")))
        assertNull(CommonsMapping.row(JSONObject("""{"pageid": 7, "title": "File: ", $info}""")))
        assertNull(CommonsMapping.row(JSONObject("""{"pageid": 7, "title": "Category:Lakes", $info}""")))
        assertEquals("commons:Lake.jpg", CommonsMapping.row(JSONObject("""{"pageid": 7, "title": "File:Lake.jpg", $info}"""))?.id)
    }

    @Test
    fun commons_lastPage_noNext() {
        assertNull(CommonsMapping.page(JSONObject("{\"query\": {\"pages\": []}}")).next)
        assertEquals(emptyList<LiveRow>(), CommonsMapping.page(JSONObject("{}")).items)
    }

    @Test
    fun commonsDate() {
        assertEquals("2026-09-21", CommonsMapping.date("2026-09-21 11:26:06"))
        assertEquals("2026-09-21", CommonsMapping.date("2026-09-21T11:26"))
        assertEquals("2026-09-21", CommonsMapping.date("2026-09-21"))
        assertEquals("circa 1890", CommonsMapping.date("circa 1890"))
        assertNull(CommonsMapping.date(""))
    }

    @Test
    fun plainText_dropsTagsAndDecodesEntities() {
        assertEquals("A & B \"C\" 'D' <E> é", plainText("<b>A</b> &amp; B &quot;C&quot; &#39;D&#x27; &lt;E&gt;\n&#233;"))
        assertEquals("&unknown;", plainText("&unknown;"))
    }

    @Test
    fun httpsUrl_onlyHttps() {
        assertEquals("https://a.example/x", httpsUrl("https://a.example/x"))
        assertEquals("https://a.example/x", httpsUrl("//a.example/x"))
        assertNull(httpsUrl("http://a.example/x"))
        assertNull(httpsUrl("javascript:alert(1)"))
        assertNull(httpsUrl(""))
        assertNull(httpsUrl(null))
    }
}
