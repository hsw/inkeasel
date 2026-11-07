package io.github.hsw.inkeasel.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.hsw.inkeasel.nowplaying.Source.Type
import java.io.File

class DefaultStoreTest {
    private val pictures = "/sdcard/pictures"

    /** The pictures folder holds `sea.jpg` and `Mountains.png`. */
    private val resolvePhoto: (String) -> File? = { name ->
        when (name.lowercase()) {
            "sea", "sea.jpg" -> File("$pictures/sea.jpg")
            "mountains" -> File("$pictures/Mountains.png")
            else -> null
        }
    }
    private val files = setOf("/data/local/tmp/a.png", "$pictures/sea.jpg")
    private val isFile: (String) -> Boolean = { it in files }

    private fun roundTrip(s: Source) = Source.decode(s.encode())

    // --- encode / decode ---

    @Test
    fun roundTripPhoto() {
        val s = Source(Type.PHOTO, "sea", "sea")
        assertEquals(s, roundTrip(s))
    }

    @Test
    fun roundTripPath() {
        val s = Source(Type.PATH, "/data/local/tmp/a.png", "a")
        assertEquals(s, roundTrip(s))
    }

    @Test
    fun roundTripUrl() {
        val s = Source(Type.URL, "https://example.org/p.jpg?x=1&y=2", "Озеро: вид")
        assertEquals(s, roundTrip(s))
    }

    @Test
    fun roundTripEmptyTitle() {
        val s = Source(Type.URL, "https://example.org/p.jpg")
        assertEquals(s, roundTrip(s))
    }

    @Test
    fun encodeUsesThreePlainKeys() {
        assertEquals(
            mapOf("type" to "PATH", "value" to "/x.png", "title" to "x"),
            Source(Type.PATH, "/x.png", "x").encode(),
        )
    }

    @Test
    fun emptyPrefsDecodeToNull() {
        assertNull(Source.decode(emptyMap<String, Any?>()))
    }

    @Test
    fun corruptPrefsDecodeToNull() {
        val ok = mapOf("type" to "URL", "value" to "https://e/p.jpg", "title" to "p")
        assertNull("unknown type", Source.decode(ok + ("type" to "FILE")))
        assertNull("lowercase type", Source.decode(ok + ("type" to "url")))
        assertNull("missing type", Source.decode(ok - "type"))
        assertNull("missing value", Source.decode(ok - "value"))
        assertNull("empty value", Source.decode(ok + ("value" to "")))
        assertNull("blank value", Source.decode(ok + ("value" to "  ")))
        assertNull("non-string type", Source.decode(ok + ("type" to 3)))
        assertNull("non-string value", Source.decode(ok + ("value" to 3)))
        assertNull("null value", Source.decode(ok + ("value" to null)))
    }

    @Test
    fun missingOrCorruptTitleIsEmpty() {
        val ok = mapOf("type" to "URL", "value" to "https://e/p.jpg")
        assertEquals(Source(Type.URL, "https://e/p.jpg"), Source.decode(ok))
        assertEquals(Source(Type.URL, "https://e/p.jpg"), Source.decode(ok + ("title" to 7)))
    }

    // --- source -> art ---

    @Test
    fun photoResolvesToFilePath() {
        assertEquals(Art.File("$pictures/Mountains.png"), Source(Type.PHOTO, "mountains").toArt(resolvePhoto, isFile))
    }

    @Test
    fun deletedPhotoIsNull() {
        assertNull(Source(Type.PHOTO, "gone").toArt(resolvePhoto, isFile))
    }

    @Test
    fun pathMustStillBeAFile() {
        assertEquals(Art.File("/data/local/tmp/a.png"), Source(Type.PATH, "/data/local/tmp/a.png").toArt(resolvePhoto, isFile))
        assertNull(Source(Type.PATH, "/data/local/tmp/gone.png").toArt(resolvePhoto, isFile))
    }

    @Test
    fun urlIsUsedAsIs() {
        assertEquals(Art.Url("https://e/p.jpg"), Source(Type.URL, "https://e/p.jpg").toArt(resolvePhoto, isFile))
    }

    @Test
    fun decodeThenArtForEachType() {
        for ((s, art) in listOf(
            Source(Type.PHOTO, "sea", "sea") to Art.File("$pictures/sea.jpg"),
            Source(Type.PATH, "/data/local/tmp/a.png", "a") to Art.File("/data/local/tmp/a.png"),
            Source(Type.URL, "https://e/p.jpg", "p") to Art.Url("https://e/p.jpg"),
        )) {
            assertEquals(art, Source.decode(s.encode())?.toArt(resolvePhoto, isFile))
        }
    }

    @Test
    fun pathToArtWithRealFileSystem() {
        val f = File.createTempFile("default", ".png")
        try {
            assertEquals(Art.File(f.path), Source(Type.PATH, f.path).toArt(resolvePhoto))
            f.delete()
            assertNull(Source(Type.PATH, f.path).toArt(resolvePhoto))
        } finally {
            f.delete()
        }
    }

    // --- UploadService extras -> source ---

    private fun extras(path: String? = null, photo: String? = null, url: String? = null, title: String? = null) =
        Source.fromExtras(path, photo, url, title, resolvePhoto, isFile)?.source

    @Test
    fun pickedExtrasCarryTheResolvedPicture() {
        val pick = { path: String?, photo: String?, url: String? ->
            Source.fromExtras(path, photo, url, null, resolvePhoto, isFile)?.art
        }
        assertEquals(Art.File("/data/local/tmp/a.png"), pick("/data/local/tmp/a.png", "sea", null))
        assertEquals(Art.File("$pictures/Mountains.png"), pick(null, "mountains", null))
        assertEquals(Art.Url("https://e/p.jpg"), pick("/gone.png", "gone", "https://e/p.jpg"))
    }

    @Test
    fun pathExtraWinsWhenItIsAFile() {
        assertEquals(
            Source(Type.PATH, "/data/local/tmp/a.png", "a"),
            extras(path = "/data/local/tmp/a.png", photo = "sea", url = "https://e/p.jpg"),
        )
    }

    @Test
    fun missingPathFallsThroughToPhoto() {
        assertEquals(Source(Type.PHOTO, "SEA", "sea"), extras(path = "/gone.png", photo = "SEA"))
    }

    @Test
    fun unresolvedPhotoFallsThroughToUrl() {
        assertEquals(Source(Type.URL, "https://e/p.jpg", ""), extras(photo = "gone", url = "https://e/p.jpg"))
    }

    @Test
    fun titleExtraOverridesTheDefaultTitle() {
        assertEquals(Source(Type.URL, "https://e/p.jpg", "Дюна"), extras(url = "https://e/p.jpg", title = "Дюна"))
        assertEquals(Source(Type.PATH, "/data/local/tmp/a.png", "Дюна"), extras(path = "/data/local/tmp/a.png", title = "Дюна"))
        assertEquals(Source(Type.PHOTO, "sea", "Дюна"), extras(photo = "sea", title = "Дюна"))
        assertEquals(Source(Type.URL, "https://e/p.jpg", ""), extras(url = "https://e/p.jpg", title = " "))
    }

    @Test
    fun noUsableExtraIsNull() {
        assertNull(extras())
        assertNull(extras(path = "/gone.png", photo = "gone"))
        assertNull(extras(path = "", photo = "", url = " "))
    }
}
