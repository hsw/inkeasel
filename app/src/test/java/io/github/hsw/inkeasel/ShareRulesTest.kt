package io.github.hsw.inkeasel

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.ZoneOffset

class ShareRulesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val own = "io.github.hsw.inkeasel"

    // 2026-11-07 18:30:05 UTC
    private val now = 1_794_076_205_000L
    private val none: (String) -> Boolean = { false }

    private fun name(display: String?, mime: String?, exists: (String) -> Boolean = none) =
        ShareRules.sharedFileName(display, mime, now, exists, ZoneOffset.UTC)

    @Test
    fun acceptShare_contentFromAnotherApp() {
        assertTrue(ShareRules.acceptShare("content", "media", own))
        assertTrue(ShareRules.acceptShare("content", "com.google.android.apps.photos.contentprovider", own))
        assertTrue(ShareRules.acceptShare("CONTENT", "com.android.providers.media.documents", own))
        // another package whose name only starts like this one's
        assertTrue(ShareRules.acceptShare("content", "io.github.hsw.inkeaselx.files", own))
    }

    @Test
    fun acceptShare_ownAuthority_refused() {
        assertFalse(ShareRules.acceptShare("content", own, own))
        assertFalse(ShareRules.acceptShare("content", "$own.fileprovider", own))
        assertFalse(ShareRules.acceptShare("content", "media;$own.files", own))
        // ContentResolver drops a user-id prefix before it looks the provider up
        assertFalse(ShareRules.acceptShare("content", "0@$own.x", own))
        assertFalse(ShareRules.acceptShare("content", "10@$own", own))
        assertTrue(ShareRules.acceptShare("content", "0@media", own))
    }

    @Test
    fun acceptShare_fileAndOthers_refused() {
        assertFalse(ShareRules.acceptShare("file", null, own))
        assertFalse(ShareRules.acceptShare("file", "", own))
        assertFalse(ShareRules.acceptShare("https", "example.org", own))
        assertFalse(ShareRules.acceptShare(null, null, own))
        assertFalse(ShareRules.acceptShare("content", null, own))
        assertFalse(ShareRules.acceptShare("content", " ", own))
    }

    @Test
    fun sharedFileName_displayName_extFromMime() {
        assertEquals("IMG_1234.jpg", name("IMG_1234.jpg", "image/jpeg"))
        assertEquals("IMG_1234.jpg", name("IMG_1234.JPEG", "image/jpeg"))
        assertEquals("Screenshot.png", name("Screenshot.jpg", "image/png")) // the type wins over the name
        assertEquals("sea.webp", name("sea", "image/webp"))
        assertEquals("Закат на море.jpg", name("Закат на море.jpg", "image/jpeg"))
    }

    @Test
    fun sharedFileName_sanitized() {
        assertEquals("evil.jpg", name("../../evil.jpg", "image/jpeg"))
        assertEquals("b.jpg", name("a\\b.jpg", "image/jpeg"))
        assertEquals("a_b_c_d_e_f_g_h.png", name("a:b*c?d\"e<f>g|h.png", "image/png"))
        assertEquals("tab_x.jpg", name("tab\tx.jpg", "image/jpeg"))
        assertEquals("x.jpg", name("  ..x..  .jpg", "image/jpeg"))
        assertEquals("a".repeat(80) + ".jpg", name("a".repeat(200) + ".jpg", "image/jpeg"))
        assertEquals("hidden.jpg", name(".hidden", "image/jpeg")) // a leading dot starts no extension
    }

    @Test
    fun sharedFileName_noDisplayName_generated() {
        assertEquals("shared-20261107-183005.jpg", name(null, "image/jpeg"))
        assertEquals("shared-20261107-183005.png", name("", "image/png"))
        assertEquals("shared-20261107-183005.jpg", name("...", "image/jpeg"))
    }

    @Test
    fun sharedFileName_genericImageType_extFromName() {
        assertEquals("photo.png", name("photo.png", "image/*"))
        assertEquals("photo.jpg", name("photo.jpeg", "image/*"))
        assertEquals("photo.jpg", name("photo", "image/*")) // nothing known: jpg
        assertEquals("photo.jpg", name("photo", null))
        assertEquals("photo.png", name("photo.png", null))
    }

    @Test
    fun sharedFileName_heic() {
        assertEquals("IMG_1.heic", name("IMG_1.HEIC", "image/heic"))
        assertEquals("IMG_1.heic", name("IMG_1.heif", "image/heif"))
        assertEquals("IMG_1.heic", name("IMG_1.heif", "image/*"))
        assertEquals("heic", ShareRules.extForMime("image/heif"))
    }

    @Test
    fun sharedFileName_unknownType_jpg() {
        assertEquals("doc.jpg", name("doc.tiff", "image/tiff"))
        assertEquals("doc.jpg", name("doc.xyz", "application/octet-stream"))
        assertEquals("bmp", ShareRules.extForMime("image/x-ms-bmp"))
        assertEquals("jpg", ShareRules.extForMime("IMAGE/JPEG; charset=binary"))
        assertNull(ShareRules.extForMime("image/*"))
        assertNull(ShareRules.extForMime(null))
    }

    @Test
    fun sharedFileName_collision_numbered() {
        val taken = setOf("sea.jpg", "sea-2.jpg", "shared-20261107-183005.png")
        assertEquals("sea-3.jpg", name("sea.jpg", "image/jpeg") { it in taken })
        assertEquals("sea.png", name("sea.jpg", "image/png") { it in taken })
        assertEquals("shared-20261107-183005-2.png", name(null, "image/png") { it in taken })
    }

    @Test
    fun copyCapped_copiesUpToTheCap() {
        val data = ByteArray(200_000) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        assertEquals(200_000L, ShareRules.copyCapped(ByteArrayInputStream(data), out, cap = 200_000))
        assertArrayEquals(data, out.toByteArray())
        assertEquals(0L, ShareRules.copyCapped(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream()))
    }

    @Test
    fun copyCapped_overTheCap_fails() {
        try {
            ShareRules.copyCapped(ByteArrayInputStream(ByteArray(3 * 1024 * 1024 + 1)), ByteArrayOutputStream(), cap = 3L * 1024 * 1024)
            fail("no exception over the cap")
        } catch (e: TxtException) {
            assertEquals(Txt(R.string.share_too_big, 3), e.txt)
        }
    }

    @Test
    fun copyCapped_defaultCapIsMaxBytes() {
        try {
            ShareRules.copyCapped(zeros(ShareRules.MAX_BYTES + 1), OutputStream.nullOutputStream())
            fail("no exception over the default cap")
        } catch (e: TxtException) {
            assertEquals(Txt(R.string.share_too_big, 50), e.txt)
        }
        assertEquals(ShareRules.MAX_BYTES, ShareRules.copyCapped(zeros(ShareRules.MAX_BYTES), OutputStream.nullOutputStream()))
    }

    @Test
    fun copyCapped_checkCancels() {
        var calls = 0
        val cancel = IllegalStateException("cancelled")
        try {
            ShareRules.copyCapped(ByteArrayInputStream(ByteArray(1_000_000)), ByteArrayOutputStream()) { if (++calls == 3) throw cancel }
            fail("check did not stop the copy")
        } catch (e: IllegalStateException) {
            assertTrue(e === cancel)
        }
        assertEquals(3, calls)
    }

    @Test
    fun pendingShare_freshAndPresent_sent() {
        assertTrue(ShareRules.sendPending(savedAtMs = now, nowMs = now, fileExists = true))
        assertTrue(ShareRules.sendPending(savedAtMs = now - 59 * 60_000, nowMs = now, fileExists = true))
        assertTrue(ShareRules.sendPending(savedAtMs = now - ShareRules.PENDING_MAX_AGE_MS, nowMs = now, fileExists = true))
    }

    @Test
    fun pendingShare_staleOrMissing_dropped() {
        assertFalse(ShareRules.sendPending(savedAtMs = now - ShareRules.PENDING_MAX_AGE_MS - 1, nowMs = now, fileExists = true))
        assertFalse(ShareRules.sendPending(savedAtMs = now - 24 * 3_600_000L, nowMs = now, fileExists = true))
        assertFalse(ShareRules.sendPending(savedAtMs = now, nowMs = now, fileExists = false))
        assertFalse(ShareRules.sendPending(savedAtMs = now + 60_000, nowMs = now, fileExists = true)) // clock set back
    }

    /** A stream of [size] zero bytes, without holding them. */
    private fun zeros(size: Long) = object : InputStream() {
        var left = size

        override fun read(): Int = if (left-- > 0) 0 else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = minOf(len.toLong(), left).toInt()
            b.fill(0, off, off + n)
            left -= n
            return n
        }
    }

    private fun save(dir: File, display: String? = "sea.jpg", data: ByteArray = ByteArray(1_000) { 1 }, cap: Long = ShareRules.MAX_BYTES, check: () -> Unit = {}) =
        ShareRules.save(dir, display, "image/jpeg", now, { ByteArrayInputStream(data) }, cap, check, ZoneOffset.UTC)

    @Test
    fun save_copiesAndRenames() {
        val dir = tmp.newFolder()
        val data = ByteArray(200_000) { (it % 7).toByte() }
        val f = save(dir, data = data)
        assertEquals(File(dir, "sea.jpg"), f)
        assertArrayEquals(data, f.readBytes())
        assertEquals(listOf("sea.jpg"), dir.list()!!.toList())
    }

    @Test
    fun save_takenNamesAndPartFilesCount() {
        val dir = tmp.newFolder()
        File(dir, "sea.jpg").writeText("old")
        File(dir, "sea-2.jpg.part").writeText("another share in progress")
        val f = save(dir)
        assertEquals("sea-3.jpg", f.name)
        assertEquals("old", File(dir, "sea.jpg").readText())
        assertEquals("another share in progress", File(dir, "sea-2.jpg.part").readText())
    }

    @Test
    fun save_failingStream_leavesNothing() {
        val dir = tmp.newFolder()
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("read failed")
        }
        try {
            ShareRules.save(dir, "sea.jpg", "image/jpeg", now, { broken }, zone = ZoneOffset.UTC)
            fail("no exception from a failing stream")
        } catch (e: IOException) {
            assertEquals("read failed", e.message)
        }
        try {
            ShareRules.save(dir, "sea.jpg", "image/jpeg", now, { throw IOException("no stream") }, zone = ZoneOffset.UTC)
            fail("no exception without a stream")
        } catch (e: IOException) {
            assertEquals("no stream", e.message)
        }
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun save_overTheCap_leavesNothing() {
        val dir = tmp.newFolder()
        try {
            save(dir, data = ByteArray(3 * 1024 * 1024 + 1), cap = 3L * 1024 * 1024)
            fail("no exception over the cap")
        } catch (e: TxtException) {
            assertEquals(Txt(R.string.share_too_big, 3), e.txt)
        }
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun save_finalNameTakenMeanwhile_failsWithAUserText_keepsTheOtherFile() {
        val dir = tmp.newFolder()
        val taken = File(dir, "sea.jpg")
        val open = { taken.writeText("another app"); ByteArrayInputStream(ByteArray(1_000)) }
        try {
            ShareRules.save(dir, "sea.jpg", "image/jpeg", now, open, zone = ZoneOffset.UTC)
            fail("no exception when the final name is taken")
        } catch (e: TxtException) {
            assertEquals(Txt(R.string.share_err_save), e.txt)
            assertTrue(e.cause?.message.orEmpty().contains("sea.jpg.part")) // the file name only in the log
        }
        assertEquals(listOf("sea.jpg"), dir.list()!!.toList())
        assertEquals("another app", taken.readText())
    }

    @Test
    fun save_cancelled_leavesNothing() {
        val dir = tmp.newFolder()
        val cancel = IllegalStateException("cancelled")
        try {
            save(dir, data = ByteArray(1_000_000)) { throw cancel }
            fail("check did not stop the copy")
        } catch (e: IllegalStateException) {
            assertTrue(e === cancel)
        }
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }
}
