package io.github.hsw.inkeasel.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.hsw.inkeasel.nowplaying.Playing.State

class PlayingTest {
    private val pkg = "com.example.player"
    private val poster = "https://cdn.example/poster-wide.jpg"

    private fun meta(state: Int = PlaybackCodes.PLAYING, bitmapKey: String? = null, bitmapPath: String? = null,
                     strings: Array<Pair<String, String?>> = emptyArray()) =
        Playing.fromMetadata(pkg, mapOf(*strings), bitmapKey, bitmapPath, state)

    // --- state mapping ---

    @Test
    fun mapState_playingPausedStopped() {
        assertEquals(State.PLAYING, Playing.mapState(3))
        assertEquals(State.PAUSED, Playing.mapState(2))
        assertEquals(State.STOPPED, Playing.mapState(0))
        assertEquals(State.STOPPED, Playing.mapState(1))
        assertEquals(State.STOPPED, Playing.mapState(7))
    }

    @Test
    fun mapState_transientStatesAreIgnored() {
        for (code in listOf(4, 5, 6, 8, 9, 10, 11)) assertNull("code $code", Playing.mapState(code))
    }

    @Test
    fun mapState_unknownCodeIsIgnored() {
        assertNull(Playing.mapState(-1))
        assertNull(Playing.mapState(42))
    }

    @Test
    fun effectiveState_transientKeepsLastRealState() {
        val buffering = 6
        val fastForward = 4
        assertEquals(3, Playing.effectiveState(null, 3))
        assertEquals(3, Playing.effectiveState(3, buffering)) // a rebuffering player still plays
        assertEquals(2, Playing.effectiveState(2, fastForward))
        assertEquals(2, Playing.effectiveState(3, 2))
        // first seen seeking or buffering (e.g. the snapshot after a reconnect): playback is under way
        assertEquals(3, Playing.effectiveState(null, buffering))
        assertEquals(3, Playing.effectiveState(null, fastForward))
        assertNull(Playing.effectiveState(3, null)) // no state at all
    }

    // --- art priority and keys ---

    @Test
    fun artUri_isUsedWithUriAsKey() {
        val p = meta(strings = arrayOf(MetaKeys.ART_URI to poster, MetaKeys.TITLE to "Film"))!!
        assertEquals(Playing(pkg, poster, "Film", Art.Url(poster), State.PLAYING), p)
    }

    @Test
    fun artPriority_artUriBeatsAlbumArtUriBeatsDisplayIconUri() {
        val a = "https://x/art.jpg"
        val b = "https://x/album.jpg"
        val c = "https://x/icon.jpg"
        assertEquals(a, meta(strings = arrayOf(MetaKeys.DISPLAY_ICON_URI to c, MetaKeys.ALBUM_ART_URI to b,
            MetaKeys.ART_URI to a), bitmapKey = "k", bitmapPath = "/c/k.png")!!.key)
        assertEquals(b, meta(strings = arrayOf(MetaKeys.DISPLAY_ICON_URI to c, MetaKeys.ALBUM_ART_URI to b),
            bitmapKey = "k", bitmapPath = "/c/k.png")!!.key)
        assertEquals(c, meta(strings = arrayOf(MetaKeys.DISPLAY_ICON_URI to c),
            bitmapKey = "k", bitmapPath = "/c/k.png")!!.key)
    }

    @Test
    fun anyUriBeatsBitmap() {
        val p = meta(strings = arrayOf(MetaKeys.DISPLAY_ICON_URI to poster), bitmapKey = "abc", bitmapPath = "/c/abc.png")!!
        assertEquals(Art.Url(poster), p.art)
    }

    @Test
    fun bitmap_isUsedWithBitmapKey() {
        val p = meta(bitmapKey = "0123abcd", bitmapPath = "/cache/nowplaying/0123abcd.png")!!
        assertEquals("0123abcd", p.key)
        assertEquals(Art.File("/cache/nowplaying/0123abcd.png"), p.art)
        assertEquals(State.PLAYING, p.state)
    }

    @Test
    fun contentUri_isSkippedInFavourOfBitmap() {
        val p = meta(strings = arrayOf(MetaKeys.ART_URI to "content://com.other.app/art/1"),
            bitmapKey = "k1", bitmapPath = "/c/k1.png")!!
        assertEquals("k1", p.key)
        assertEquals(Art.File("/c/k1.png"), p.art)
    }

    @Test
    fun contentUri_isSkippedInFavourOfLowerPriorityHttpUri() {
        val p = meta(strings = arrayOf(MetaKeys.ART_URI to "content://com.other.app/art/1",
            MetaKeys.DISPLAY_ICON_URI to poster))!!
        assertEquals(poster, p.key)
    }

    @Test
    fun nonHttpUris_areSkipped() {
        for (uri in listOf("file:///data/data/other/a.jpg", "android.resource://other/drawable/a", "CONTENT://x/y")) {
            val p = meta(strings = arrayOf(MetaKeys.ART_URI to uri), bitmapKey = "k", bitmapPath = "/c/k.png")!!
            assertEquals(uri, "k", p.key)
        }
        assertEquals("HTTPS://X/A.JPG", meta(strings = arrayOf(MetaKeys.ART_URI to "HTTPS://X/A.JPG"))!!.key)
    }

    @Test
    fun plainHttpUri_fallsThroughToBitmap() { // cleartext is not permitted, so it cannot be fetched
        val http = "http://x/a.jpg"
        val p = meta(strings = arrayOf(MetaKeys.ART_URI to http), bitmapKey = "k", bitmapPath = "/c/k.png")!!
        assertEquals("k", p.key)
        assertEquals(Art.File("/c/k.png"), p.art)
        assertNull(meta(strings = arrayOf(MetaKeys.ART_URI to http)))
        assertTrue(Playing.needsBitmap(mapOf(MetaKeys.ART_URI to http)))
        val https = meta(strings = arrayOf(MetaKeys.ART_URI to http, MetaKeys.DISPLAY_ICON_URI to poster))!!
        assertEquals(poster, https.key)
    }

    @Test
    fun key_ignoresTitle() {
        val e1 = meta(strings = arrayOf(MetaKeys.ART_URI to poster, MetaKeys.TITLE to "S1 E1"))!!
        val e2 = meta(strings = arrayOf(MetaKeys.ART_URI to poster, MetaKeys.TITLE to "S1 E2"))!!
        assertEquals(e1.key, e2.key)
        val b1 = meta(bitmapKey = "same", bitmapPath = "/c/same.png", strings = arrayOf(MetaKeys.TITLE to "A"))!!
        val b2 = meta(bitmapKey = "same", bitmapPath = "/c/same.png", strings = arrayOf(MetaKeys.TITLE to "B"))!!
        assertEquals(b1.key, b2.key)
    }

    @Test
    fun title_prefersDisplayTitleThenTitle() {
        assertEquals("Shown", meta(strings = arrayOf(MetaKeys.ART_URI to poster,
            MetaKeys.TITLE to "Raw", MetaKeys.DISPLAY_TITLE to "Shown"))!!.title)
        assertEquals("Raw", meta(strings = arrayOf(MetaKeys.ART_URI to poster, MetaKeys.TITLE to "Raw"))!!.title)
        assertEquals("", meta(strings = arrayOf(MetaKeys.ART_URI to poster))!!.title)
    }

    // --- metadata edge cases ---

    @Test
    fun playingWithoutArt_isNull() {
        assertNull(meta(strings = arrayOf(MetaKeys.TITLE to "Film")))
        assertNull(meta(strings = arrayOf(MetaKeys.ART_URI to "content://x/1")))
        assertNull(meta(bitmapKey = "k", bitmapPath = null)) // bitmap not cached
        assertNull(meta(bitmapKey = null, bitmapPath = "/c/k.png"))
    }

    @Test
    fun pausedOrStoppedWithoutArt_isEventWithNullKey() {
        for (code in listOf(PlaybackCodes.PAUSED, PlaybackCodes.STOPPED, PlaybackCodes.NONE, PlaybackCodes.ERROR)) {
            val p = meta(state = code, strings = arrayOf(MetaKeys.TITLE to "Film"))!!
            assertNull(p.key)
            assertNull(p.art)
            assertEquals(pkg, p.pkg)
            assertEquals(Playing.mapState(code), p.state)
        }
    }

    @Test
    fun pausedWithArt_keepsKey() {
        val p = meta(state = PlaybackCodes.PAUSED, strings = arrayOf(MetaKeys.ART_URI to poster))!!
        assertEquals(State.PAUSED, p.state)
        assertEquals(poster, p.key)
    }

    @Test
    fun transientState_isNullEvenWithArt() {
        for (code in listOf(4, 5, 6, 8, 9, 10, 11)) {
            assertNull("code $code", meta(state = code, strings = arrayOf(MetaKeys.ART_URI to poster)))
        }
    }

    @Test
    fun emptyStrings_areTreatedAsAbsent() {
        val p = meta(strings = arrayOf(MetaKeys.ART_URI to "", MetaKeys.ALBUM_ART_URI to "  ",
            MetaKeys.DISPLAY_ICON_URI to poster, MetaKeys.DISPLAY_TITLE to "", MetaKeys.TITLE to "Film"))!!
        assertEquals(poster, p.key)
        assertEquals("Film", p.title)
        assertNull(meta(strings = arrayOf(MetaKeys.ART_URI to ""), bitmapKey = "", bitmapPath = ""))
        assertNull(meta(strings = arrayOf(MetaKeys.ART_URI to null), bitmapKey = "k", bitmapPath = ""))
    }

    @Test
    fun needsBitmap_onlyWithoutUsableHttpUri() {
        assertTrue(Playing.needsBitmap(emptyMap()))
        assertTrue(Playing.needsBitmap(mapOf(MetaKeys.ART_URI to "content://media/1", MetaKeys.TITLE to "Film")))
        assertTrue(Playing.needsBitmap(mapOf(MetaKeys.ART_URI to " ", MetaKeys.ALBUM_ART_URI to null)))
        assertFalse(Playing.needsBitmap(mapOf(MetaKeys.ART_URI to "content://media/1", MetaKeys.DISPLAY_ICON_URI to poster)))
        assertFalse(Playing.needsBitmap(mapOf(MetaKeys.ART_URI to poster)))
    }

    // --- session reports (NowPlayingListener) ---

    private fun session(state: Int? = PlaybackCodes.PLAYING,
                        strings: Map<String, String?> = mapOf(MetaKeys.TITLE to "Next")) =
        Playing.fromSession(pkg, strings, null, null, state)

    @Test
    fun fromSession_withArt_isFromMetadata() {
        val strings = mapOf(MetaKeys.ART_URI to poster, MetaKeys.TITLE to "Film")
        for (code in listOf(PlaybackCodes.PLAYING, PlaybackCodes.PAUSED, PlaybackCodes.STOPPED)) {
            assertEquals(Playing.fromMetadata(pkg, strings, null, null, code), session(code, strings))
        }
        assertEquals(Playing(pkg, null, "Next", null, State.PAUSED), session(PlaybackCodes.PAUSED))
    }

    @Test
    fun fromSession_artlessPlaying_isStoppedWithoutKey() {
        assertEquals(Playing(pkg, null, "Next", null, State.STOPPED), session())
    }

    @Test
    fun fromSession_noPlaybackState_isStopped() {
        val strings = mapOf(MetaKeys.ART_URI to poster, MetaKeys.TITLE to "Film")
        assertEquals(Playing(pkg, poster, "Film", Art.Url(poster), State.STOPPED), session(null, strings))
    }
}
