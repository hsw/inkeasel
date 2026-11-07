package io.github.hsw.inkeasel.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import io.github.hsw.inkeasel.nowplaying.Playing.State

class SessionViewTest {
    private val pkg = "com.example.player"
    private val id = "com.example.player#1"
    private val k1 = "https://cdn.example/film1-wide.jpg"
    private val k2 = "https://cdn.example/film2-wide.jpg"
    private val buffering = 6
    private val fastForward = 4

    private fun meta(art: String? = k1, title: String = "Film") =
        SessionView.Meta(mapOf(MetaKeys.TITLE to title, MetaKeys.ART_URI to art), null, null)

    private fun playing(key: String = k1, title: String = "Film") =
        Playing(pkg, key, title, Art.Url(key), State.PLAYING, id)

    @Test
    fun firstUpdate_isReported_withSessionId() {
        val v = SessionView(pkg, id)
        assertNull(v.event)
        assertEquals(playing(), v.update(meta(), PlaybackCodes.PLAYING))
        assertEquals(playing(), v.event)
        val paused = SessionView(pkg, id)
        assertEquals(State.PAUSED, paused.update(meta(), PlaybackCodes.PAUSED)!!.state)
    }

    @Test
    fun noPlaybackStateAtFirst_isStopped() {
        val v = SessionView(pkg, id)
        assertEquals(Playing(pkg, k1, "Film", Art.Url(k1), State.STOPPED, id), v.update(meta(), null))
        assertEquals(playing(), v.update(state = PlaybackCodes.PLAYING))
    }

    @Test
    fun bufferingAtFirst_isReportedAsPlaying() {
        // e.g. the snapshot after a process restart or a reconnect while the player rebuffers
        val v = SessionView(pkg, id)
        assertEquals(playing(), v.update(meta(), buffering))
        assertNull(v.update(state = PlaybackCodes.PLAYING)) // it was already playing
    }

    @Test
    fun bufferingAtFirst_thenArtCleared_isReportedAsStoppedWithoutKey() {
        val v = SessionView(pkg, id)
        v.update(meta(), buffering)
        assertEquals(Playing(pkg, null, "Clip", null, State.STOPPED, id), v.update(meta(null, "Clip")))
    }

    @Test
    fun nullPlaybackStateAfterPlaying_isReportedAsStopped() {
        val v = SessionView(pkg, id)
        v.update(meta(), PlaybackCodes.PLAYING)
        assertEquals(Playing(pkg, k1, "Film", Art.Url(k1), State.STOPPED, id), v.update(state = null))
        assertEquals(playing(), v.update(state = PlaybackCodes.PLAYING))
    }

    @Test
    fun seekOrRebuffer_isNotReported() {
        val v = SessionView(pkg, id)
        v.update(meta(), PlaybackCodes.PLAYING)
        assertNull(v.update(state = buffering))
        assertNull(v.update(state = fastForward))
        assertNull(v.update(state = PlaybackCodes.PLAYING))
        v.update(state = PlaybackCodes.PAUSED)
        assertNull(v.update(state = buffering)) // a paused player that buffers is still paused
        assertEquals(State.PAUSED, v.event!!.state)
    }

    @Test
    fun itemChangeDuringRebuffer_isReportedAsPlayingTheNewKey() {
        val v = SessionView(pkg, id)
        v.update(meta(), PlaybackCodes.PLAYING)
        v.update(state = buffering)
        assertEquals(playing(k2, "Next"), v.update(meta(k2, "Next")))
    }

    @Test
    fun artlessUpdates_areReportedOnce() {
        val v = SessionView(pkg, id)
        v.update(meta(), PlaybackCodes.PLAYING)
        assertEquals(State.STOPPED, v.update(meta(null, "Clip"))!!.state)
        assertNull(v.update(meta(null, "Clip 2")))
        assertNull(v.update(state = PlaybackCodes.PLAYING))
        assertEquals(playing(k2, "Next"), v.update(meta(k2, "Next"))) // a picture again
    }

    @Test
    fun titleOnlyChange_orAnEndsKeyChange_isNotReported_butIsKept() {
        val v = SessionView(pkg, id)
        v.update(meta(), PlaybackCodes.PLAYING)
        assertNull(v.update(meta(title = "Episode 2"))) // episodes of a series share one poster
        assertEquals("Episode 2", v.event!!.title)
        v.update(state = PlaybackCodes.PAUSED)
        assertNull(v.update(meta(k2))) // still the same end: a repeated end would restart the revert retry bound
        assertEquals(k2, v.event!!.key)
        assertEquals(playing(k2), v.update(state = PlaybackCodes.PLAYING))
    }

    @Test
    fun pausedToStopped_isReported() {
        val v = SessionView(pkg, id)
        v.update(meta(), PlaybackCodes.PAUSED)
        assertEquals(State.STOPPED, v.update(state = PlaybackCodes.STOPPED)!!.state)
    }
}
