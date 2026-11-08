package io.github.hsw.inkeasel

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceMappingTest {
    private fun fixture(name: String) =
        JSONObject(javaClass.getResource("/catalog/$name")!!.readText())

    @Test
    fun cleveland_mapsEveryField() {
        val list = ClevelandMapping.pictures(fixture("cleveland.json"))
        // the portrait (778×893) and the artwork without images are left out
        assertEquals(listOf("cma:152006", "cma:99"), list.map { it.id })
        assertEquals(
            Picture(
                id = "cma:152006",
                title = "Rocky, Wooded Landscape with a Dell and Weir",
                credit = "Thomas Gainsborough, c. 1782–1783",
                thumbUrl = "https://openaccess-cdn.clevelandart.org/1984.59/1984.59_web.jpg",
                fullUrl = "https://openaccess-cdn.clevelandart.org/1984.59/1984.59_print.jpg",
                artist = "Thomas Gainsborough, Anonymous Landscape Painter",
                date = "c. 1782–1783",
                collection = "Cleveland Museum of Art",
                pageUrl = "https://clevelandart.org/art/1984.59",
                licence = "CC0",
                licenceUrl = "https://creativecommons.org/publicdomain/zero/1.0/",
                width = 5500,
                height = 4211,
            ),
            list[0],
        )
        assertEquals("cma", list[0].source)
    }

    @Test
    fun cleveland_missingFieldsStayNull() {
        val p = ClevelandMapping.pictures(fixture("cleveland.json"))[1]
        assertEquals("Untitled", p.title)
        assertEquals("", p.credit)
        // no print: the frame gets the web image, whose size is the largest known
        assertEquals("https://openaccess-cdn.clevelandart.org/99/99_web.jpg", p.fullUrl)
        assertEquals(1100, p.width)
        assertEquals(700, p.height)
        assertNull(p.artist)
        assertNull(p.date)
        assertNull(p.pageUrl)
        assertNull(p.licence)
        assertNull(p.licenceUrl)
        assertEquals("Cleveland Museum of Art", p.collection)
    }

    @Test
    fun commons_mapsEveryField() {
        val page = CommonsMapping.page(fixture("commons.json"))
        assertEquals("20260909101556|199181121", page.next)
        // the 7:1 banner and the page without image info are left out
        assertEquals(listOf("commons:1001", "commons:1003"), page.items.map { it.id })
        assertEquals(
            Picture(
                id = "commons:1001",
                title = "Example valley at dawn - 06",
                credit = "Example Photographer & Friend, CC BY-SA 4.0",
                thumbUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/0/0a/Example_valley_at_dawn_-_06.jpg/500px-Example_valley_at_dawn_-_06.jpg",
                fullUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/0/0a/Example_valley_at_dawn_-_06.jpg/1920px-Example_valley_at_dawn_-_06.jpg",
                artist = "Example Photographer & Friend",
                date = "2026-09-21",
                pageUrl = "https://commons.wikimedia.org/w/index.php?curid=1001",
                licence = "CC BY-SA 4.0",
                licenceUrl = "https://creativecommons.org/licenses/by-sa/4.0",
                width = 6744,
                height = 4500,
            ),
            page.items[0],
        )
        assertEquals("commons", page.items[0].source)
    }

    @Test
    fun commons_fallbacks() {
        val p = CommonsMapping.page(fixture("commons.json")).items[1]
        assertEquals("Example lake", p.title) // the file name without its extension
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/1/1a/Example_lake.jpeg", p.fullUrl)
        assertNull(p.artist)
        assertEquals("Public domain", p.credit)
        assertEquals("1890", p.date)
        assertEquals("https://commons.wikimedia.org/wiki/File:Example_lake.jpeg", p.pageUrl)
        assertEquals("https://example.org/pd", p.licenceUrl)
        assertNull(p.collection)
        assertEquals(3000, p.width)
    }

    @Test
    fun commons_lastPage_noNext() {
        assertNull(CommonsMapping.page(JSONObject("{\"query\": {\"pages\": []}}")).next)
        assertEquals(emptyList<Picture>(), CommonsMapping.page(JSONObject("{}")).items)
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
