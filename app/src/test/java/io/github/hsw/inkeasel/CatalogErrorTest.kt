package io.github.hsw.inkeasel

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class CatalogErrorTest {
    @Test
    fun `timeouts read as a source not responding`() {
        val timeout = Txt(R.string.catalog_err_timeout)
        assertEquals(timeout, CatalogError.of(SocketTimeoutException("Read timed out")))
        // OkHttp's call timeout
        assertEquals(timeout, CatalogError.of(InterruptedIOException("timeout")))
    }

    @Test
    fun `no host, refused or no route read as no connection`() {
        val offline = Txt(R.string.catalog_err_offline)
        assertEquals(offline, CatalogError.of(UnknownHostException("Unable to resolve host \"example.org\"")))
        assertEquals(offline, CatalogError.of(ConnectException("Failed to connect")))
        assertEquals(offline, CatalogError.of(NoRouteToHostException("No route to host")))
    }

    @Test
    fun `server errors ask to try later, other HTTP codes are reported`() {
        assertEquals(Txt(R.string.catalog_err_http_server, 504), CatalogError.of(HttpStatusException(504, "https://example.org/a")))
        assertEquals(Txt(R.string.catalog_err_http_server, 500), CatalogError.of(HttpStatusException(500, "https://example.org/a")))
        assertEquals(Txt(R.string.catalog_err_http, 404), CatalogError.of(HttpStatusException(404, "https://example.org/a")))
        assertEquals(Txt(R.string.catalog_err_http, 429), CatalogError.of(HttpStatusException(429, "https://example.org/a")))
    }

    @Test
    fun `HTTP status message keeps code and url`() {
        val e = HttpStatusException(503, "https://example.org/list")
        assertEquals("HTTP 503: https://example.org/list", e.message)
        assertEquals(503, e.code)
        assertEquals(true, e is IOException)
    }

    @Test
    fun `an unparsable answer reads as unreadable`() {
        val e = assertThrows(JSONException::class.java) { JSONObject("<html>Bad gateway</html>") }
        assertEquals(Txt(R.string.catalog_err_unreadable), CatalogError.of(e))
        assertEquals(Txt(R.string.catalog_err_unreadable), CatalogError.of(JSONException("JSONObject[\"data\"] not found.")))
    }

    @Test
    fun `a text failure is shown as it is`() {
        val t = Txt(R.string.bt_off)
        assertEquals(t, CatalogError.of(TxtException(t)))
    }

    @Test
    fun `anything else keeps its message`() {
        assertEquals(Txt(R.string.catalog_failed, "Chain validation failed"), CatalogError.of(SSLHandshakeException("Chain validation failed")))
        assertEquals(Txt(R.string.catalog_failed, "boom"), CatalogError.of(IllegalStateException("boom")))
    }

    @Test
    fun `a missing or blank message falls back to the class name`() {
        assertEquals(Txt(R.string.catalog_failed, "IllegalStateException"), CatalogError.of(IllegalStateException()))
        assertEquals(Txt(R.string.catalog_failed, "IOException"), CatalogError.of(IOException(" ")))
    }
}
