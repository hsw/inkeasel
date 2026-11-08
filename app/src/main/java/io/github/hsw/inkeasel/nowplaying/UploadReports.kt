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
 *
 * Each report is logged; a poster's title, key and error only through [MediaLog].
 */
class UploadReports(
    private val onUploadResult: (key: String, ok: Boolean) -> Unit,
    private val onManualUpload: (Source) -> Unit,
    private val toast: (Txt) -> Unit,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) {
    fun done(req: UploadRequest, ok: Boolean, error: Throwable?) {
        val logged = if (isPoster(req)) MediaLog.error(error, req.key, req.source.value) else error
        log("${kind(req)} upload ${if (ok) "done" else "failed"}: ${describe(req)}", logged)
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
        log("${kind(req)} upload $reason: ${describe(req)}", null)
        if (req.auto) onUploadResult(keyOf(req), false)
    }

    private fun kind(req: UploadRequest) = if (req.auto) "auto" else "manual"

    /** A poster of what plays, not the default or a manual pick: its title, key and error go through [MediaLog]. */
    private fun isPoster(req: UploadRequest) = req.auto && FrameController.isAuto(req.key)

    private fun describe(req: UploadRequest): String {
        val title = if (isPoster(req)) MediaLog.title(req.source.title) else req.source.title
        return "$title (key=${MediaLog.key(req.key)})"
    }

    /** An automatic request always has a key ([UploadRequest.auto]). */
    private fun keyOf(req: UploadRequest): String = checkNotNull(req.key) { "auto upload without key" }
}
