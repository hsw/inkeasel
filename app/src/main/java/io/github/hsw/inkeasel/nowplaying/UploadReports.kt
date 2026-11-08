package io.github.hsw.inkeasel.nowplaying

import io.github.hsw.inkeasel.Txt

/**
 * What the upload engine does with the one report each request gets from [UploadEngineCore]. Free of Android
 * types, so the controller tests run these rules, not a copy of them.
 *
 * - After an upload ([done]), as [UploadOutcome] decides: a manual one toasts ([toast]) and, on success,
 *   becomes the default ([onManualUpload]); an automatic one only reports to the controller ([onUploadResult]).
 * - A request dropped unsent ([dropped]): an automatic one is reported to the controller as failed, so it can
 *   retry or reconcile; a manual one is simply superseded by the newer pick and reported to nobody.
 */
class UploadReports(
    private val onUploadResult: (key: String, ok: Boolean) -> Unit,
    private val onManualUpload: (Source) -> Unit,
    private val toast: (Txt) -> Unit,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) {
    fun done(req: UploadRequest, ok: Boolean, error: Throwable?) {
        log("${kind(req)} upload ${if (ok) "done" else "failed"}: ${req.source.title} (key=${req.key})", error)
        val out = UploadOutcome.of(req.auto, ok, error)
        out.toast?.let(toast)
        if (out.report) onUploadResult(keyOf(req), ok)
        if (out.remember) onManualUpload(req.source)
    }

    fun dropped(req: UploadRequest, why: UploadEngineCore.Drop) {
        val reason = when (why) {
            UploadEngineCore.Drop.REPLACED -> "replaced by a newer request"
            UploadEngineCore.Drop.STALE ->
                "dropped (a manual upload came after it, the poster is off, or its poster plays again)"
        }
        log("${kind(req)} upload $reason: ${req.source.title} (key=${req.key})", null)
        if (req.auto) onUploadResult(keyOf(req), false)
    }

    private fun kind(req: UploadRequest) = if (req.auto) "auto" else "manual"

    /** An automatic request always has a key ([UploadRequest.auto]). */
    private fun keyOf(req: UploadRequest): String = checkNotNull(req.key) { "auto upload without key" }
}
