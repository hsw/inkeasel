package io.github.hsw.inkeasel

import org.json.JSONException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException

/** Why a catalog tab could not load, in words for the user (shown as is, without a prefix). */
object CatalogError {
    fun of(e: Throwable): Txt = when (e) {
        is TxtException -> e.txt
        // no DNS, nothing listening, no route: the device is offline or the source is gone
        is UnknownHostException, is ConnectException, is NoRouteToHostException -> Txt(R.string.catalog_err_offline)
        // a socket timeout, or OkHttp's call timeout ("timeout"), or the whole-load deadline
        is InterruptedIOException -> Txt(R.string.catalog_err_timeout)
        is HttpStatusException ->
            if (e.code >= 500) txt(R.string.catalog_err_http_server, e.code) else txt(R.string.catalog_err_http, e.code)
        is JSONException -> Txt(R.string.catalog_err_unreadable)
        else -> txt(R.string.catalog_failed, e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
    }
}
