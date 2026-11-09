package io.github.hsw.inkeasel

import org.json.JSONException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException

/**
 * Why a download failed, in words for the user (shown as is, without a prefix); `null` for a failure it has
 * no words for, which the caller puts after its own generic text.
 */
object CatalogError {
    fun of(e: Throwable): Txt? = when (e) {
        // no DNS, nothing listening, no route: the device is offline or the source is gone
        is UnknownHostException, is ConnectException, is NoRouteToHostException -> Txt(R.string.catalog_err_offline)
        // a socket timeout, or OkHttp's call timeout ("timeout"), or the whole-load deadline
        is InterruptedIOException -> Txt(R.string.catalog_err_timeout)
        is HttpStatusException ->
            if (e.code >= 500) Txt(R.string.catalog_err_http_server, e.code) else Txt(R.string.catalog_err_http, e.code)
        is JSONException -> Txt(R.string.catalog_err_unreadable)
        is TxtException -> e.txt
        else -> null
    }
}
