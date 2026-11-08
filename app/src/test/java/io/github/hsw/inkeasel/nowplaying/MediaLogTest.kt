package io.github.hsw.inkeasel.nowplaying

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter

/** What plays, as the log names it: as it is with [MediaLog.details], a placeholder without. */
class MediaLogTest {
    private val url = "https://cdn.example/film1-wide.jpg"
    private val sha = "0123456789abcdef0123456789abcdef01234567"
    private val default = FrameController.DEFAULT_PREFIX + "/sdcard/pictures/sea.jpg"
    private val manual = Source(Source.Type.URL, "https://catalog.example/sea.jpg", "Sea").manualKey()

    @After
    fun restore() {
        MediaLog.details = true
    }

    @Test
    fun debugUnitTests_detailsOnByDefault() {
        assertTrue(MediaLog.details)
    }

    @Test
    fun detailsOn_everythingAsItIs() {
        MediaLog.details = true
        assertEquals("Film", MediaLog.title("Film"))
        assertEquals(url, MediaLog.key(url))
        assertEquals(sha, MediaLog.key(sha))
        assertNull(MediaLog.key(null))
        val e = IOException("HTTP 404: $url")
        assertSame(e, MediaLog.error(e, url))
    }

    @Test
    fun detailsOff_titleAndPosterKeysHidden_ownPicksStay() {
        MediaLog.details = false
        assertEquals(MediaLog.TITLE, MediaLog.title("Film"))
        assertEquals(MediaLog.TITLE, MediaLog.title(""))
        assertEquals(MediaLog.KEY, MediaLog.key(url))
        assertEquals(MediaLog.KEY, MediaLog.key(sha))
        assertEquals(default, MediaLog.key(default))
        assertEquals(manual, MediaLog.key(manual))
        assertNull(MediaLog.key(null))
    }

    @Test
    fun detailsOff_errorMessagesRedacted_classAndStackKept() {
        MediaLog.details = false
        val path = "/data/cache/nowplaying/$sha.png"
        val e = IOException("download failed", IOException("HTTP 404: $url; also $path"))
        val r = checkNotNull(MediaLog.error(e, sha, url, path, null, ""))
        val printed = StringWriter().also { r.printStackTrace(PrintWriter(it)) }.toString()
        assertFalse(printed, printed.contains(url) || printed.contains(sha) || printed.contains("film1"))
        assertTrue(printed, printed.startsWith("java.io.IOException: download failed"))
        assertTrue(printed, printed.contains("Caused by: java.io.IOException: HTTP 404: <key>; also <key>"))
        assertEquals(e.stackTrace.toList(), r.stackTrace.toList())
        assertNull(MediaLog.error(null, url))
    }

    @Test
    fun detailsOff_uploadReports_posterHidden_manualAndDefaultNamed() {
        MediaLog.details = false
        val logs = mutableListOf<String>()
        val errors = mutableListOf<Throwable?>()
        val reports = UploadReports(
            onUploadResult = { _, _ -> },
            onManualUpload = {},
            toast = {},
            log = { msg, e -> logs += msg; errors += e },
        )
        val poster = UploadRequest.auto(Art.Url(url), "Secret Film", url, 0)
        reports.done(poster, ok = false, error = IOException("HTTP 404: $url"))
        reports.dropped(poster, UploadEngineCore.Drop.REPLACED)
        val revert = UploadRequest.auto(Art.File("/sdcard/pictures/sea.jpg"), "", default, 0)
        reports.done(revert, ok = true, error = null)
        val pick = Source(Source.Type.URL, "https://catalog.example/sea.jpg", "Sea")
        reports.done(UploadRequest.manual(pick, Art.Url(pick.value)), ok = true, error = null)

        assertEquals(
            listOf(
                "auto upload failed: <title> (key=<key>)",
                "auto upload replaced by a newer request: <title> (key=<key>)",
                "auto upload done: sea (key=$default)",
                "manual upload done: Sea (key=null)",
            ),
            logs,
        )
        assertEquals("java.io.IOException: HTTP 404: <key>", errors[0].toString())
    }
}
