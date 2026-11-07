package io.github.hsw.inkeasel.nowplaying

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ArtCacheTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val pixels = ByteArray(640 * 360 * 4) { (it % 251).toByte() }

    private fun cache() = ArtCache(File(tmp.root, "nowplaying"))

    // --- key and naming ---

    @Test
    fun sameBytes_sameKeyAndFileName() {
        val k1 = ArtCache.keyOf(pixels)
        val k2 = ArtCache.keyOf(pixels.copyOf())
        assertEquals(k1, k2)
        assertEquals(ArtCache.fileName(k1), ArtCache.fileName(k2))
        assertEquals(cache().fileFor(k1), cache().fileFor(k2))
    }

    @Test
    fun differentBytes_differentKey() {
        val other = pixels.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertNotEquals(ArtCache.keyOf(pixels), ArtCache.keyOf(other))
        assertNotEquals(ArtCache.keyOf(ByteArray(4)), ArtCache.keyOf(ByteArray(8)))
    }

    @Test
    fun key_isLowerCaseSha1Hex() {
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", ArtCache.keyOf(ByteArray(0)))
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", ArtCache.keyOf("abc".toByteArray()))
        assertTrue(ArtCache.keyOf(pixels).matches(Regex("[0-9a-f]{40}")))
    }

    @Test
    fun fileName_isKeyDotPng_inTheCacheDir() {
        val key = ArtCache.keyOf(pixels)
        assertEquals("$key.png", ArtCache.fileName(key))
        assertEquals(File(tmp.root, "nowplaying/$key.png"), cache().fileFor(key))
    }

    // --- store ---

    @Test
    fun store_writesMissingFileAndCreatesDir() {
        val key = ArtCache.keyOf(pixels)
        val f = cache().store(key) { it.write(byteArrayOf(1, 2, 3)); true }!!
        assertEquals(cache().fileFor(key), f)
        assertArrayEquals(byteArrayOf(1, 2, 3), f.readBytes())
        assertFalse(File(f.path + ".tmp").exists())
    }

    @Test
    fun store_doesNotRewriteExistingFile() {
        val key = ArtCache.keyOf(pixels)
        val c = cache()
        c.store(key) { it.write(byteArrayOf(1)); true }
        var calls = 0
        val f = c.store(key) { calls++; it.write(byteArrayOf(9, 9)); true }!!
        assertEquals(0, calls)
        assertArrayEquals(byteArrayOf(1), f.readBytes())
    }

    @Test
    fun store_failedEncode_leavesNoFile() {
        val key = ArtCache.keyOf(pixels)
        assertNull(cache().store(key) { it.write(byteArrayOf(1)); false })
        assertNull(cache().store(key) { it.write(byteArrayOf(1)); throw IOException("disk full") })
        assertEquals(emptyList<String>(), File(tmp.root, "nowplaying").list()!!.toList())
        // a later good encode still creates it
        assertTrue(cache().store(key) { it.write(byteArrayOf(2)); true }!!.isFile)
    }

    @Test
    fun store_writerThrowingNonIoException_leavesNoFile() {
        val key = ArtCache.keyOf(pixels)
        assertNull(cache().store(key) { it.write(byteArrayOf(1)); throw IllegalStateException("recycled bitmap") })
        assertEquals(emptyList<String>(), File(tmp.root, "nowplaying").list()!!.toList())
    }

    @Test
    fun store_fileAppearedDuringWrite_returnsItAndLeavesNoTmp() {
        val key = ArtCache.keyOf(pixels)
        val c = cache()
        val f = c.store(key) { out ->
            out.write(byteArrayOf(1))
            c.fileFor(key).writeBytes(byteArrayOf(7)) // a concurrent writer finished first
            true
        }!!
        assertEquals(c.fileFor(key), f)
        assertTrue(f.isFile)
        assertEquals(listOf(f.name), File(tmp.root, "nowplaying").list()!!.toList())
    }

    @Test
    fun store_keepsAtMostMaxFiles_newestAndReusedFirst() {
        val dir = File(tmp.root, "nowplaying")
        val c = ArtCache(dir, maxFiles = 3)
        val keys = (0 until 5).map { "%040d".format(it) }
        fun put(i: Int, at: Long) = c.store(keys[i]) { it.write(byteArrayOf(i.toByte())); true }!!.setLastModified(at)
        put(0, 1_000_000)
        put(1, 2_000_000)
        put(2, 3_000_000)
        c.fileFor(keys[0]).setLastModified(4_000_000) // as if reused (store hit) after 1 and 2
        put(3, 5_000_000) // 4 files > 3: the oldest (1) goes
        assertEquals(setOf(0, 2, 3).map { ArtCache.fileName(keys[it]) }.toSet(), dir.list()!!.toSet())
        c.store(keys[4]) { it.write(byteArrayOf(4)); true } // new file, mtime now: 2 goes
        assertEquals(setOf(0, 3, 4).map { ArtCache.fileName(keys[it]) }.toSet(), dir.list()!!.toSet())
    }

    @Test
    fun store_neverPrunesKeysInUse() {
        val dir = File(tmp.root, "nowplaying")
        val c = ArtCache(dir, maxFiles = 3)
        val keys = (0 until 6).map { "%040d".format(it) }
        fun put(i: Int, at: Long, inUse: Set<String> = emptySet()) =
            c.store(keys[i], inUse) { it.write(byteArrayOf(i.toByte())); true }!!.setLastModified(at)
        put(0, 1_000_000) // a poster waiting for its start or its queued upload: the oldest file
        put(1, 2_000_000)
        put(2, 3_000_000)
        put(3, 4_000_000, setOf(keys[0])) // newer artwork (paused sessions): 1 goes, not 0
        assertEquals(setOf(0, 2, 3).map { ArtCache.fileName(keys[it]) }.toSet(), dir.list()!!.toSet())
        put(4, 5_000_000, setOf(keys[0], keys[2])) // two in use + the new one fill the cache: 3 goes
        assertEquals(setOf(0, 2, 4).map { ArtCache.fileName(keys[it]) }.toSet(), dir.list()!!.toSet())
        put(5, 6_000_000, setOf(keys[0], keys[2], keys[4])) // more in use than room: kept, over the limit
        assertEquals(setOf(0, 2, 4, 5).map { ArtCache.fileName(keys[it]) }.toSet(), dir.list()!!.toSet())
        put(1, 7_000_000) // nothing in use any more: back to the 3 newest
        assertEquals(setOf(4, 5, 1).map { ArtCache.fileName(keys[it]) }.toSet(), dir.list()!!.toSet())
    }

    @Test
    fun store_hit_marksTheFileAsRecent() {
        val key = ArtCache.keyOf(pixels)
        val c = cache()
        c.store(key) { it.write(byteArrayOf(1)); true }!!.setLastModified(1_000_000)
        val f = c.store(key) { true }!!
        assertTrue(f.lastModified() > 1_000_000)
    }

    @Test
    fun store_differentKeys_differentFiles() {
        val c = cache()
        val a = c.store("a".repeat(40)) { it.write(byteArrayOf(1)); true }!!
        val b = c.store("b".repeat(40)) { it.write(byteArrayOf(2)); true }!!
        assertNotEquals(a, b)
        assertEquals(2, File(tmp.root, "nowplaying").list()!!.size)
    }
}
