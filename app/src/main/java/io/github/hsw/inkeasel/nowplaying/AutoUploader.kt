package io.github.hsw.inkeasel.nowplaying

/**
 * The production [Uploader] of the [FrameController]: every automatic request (a poster, the revert to the
 * default) goes straight to the process-wide upload engine through [submit]. It is called under the
 * controller's lock, so the stamp [submit] takes ([FrameController.manualUploads]) matches the decision.
 * Free of Android types.
 *
 * - Without the Bluetooth permissions ([permitted]) nothing is submitted: the request is reported failed at
 *   once ([refuse]).
 * - While the notification listener is bound ([listenerBound]) the system keeps the process alive, so the
 *   request runs in the process without a foreground service (unheld work).
 * - Otherwise (access granted but the listener not bound yet, the binding lost, a controller timer firing
 *   after a disconnect) the request is submitted as held work and [startHold] starts the upload service,
 *   which keeps the process in the foreground until the engine has reported it. A refused start (a
 *   background start on Android 12+) is only logged: the request is already in the engine, which uploads it
 *   and reports it once, so it is not reported failed a second time.
 */
class AutoUploader(
    private val permitted: () -> Boolean,
    private val listenerBound: () -> Boolean,
    private val submit: (art: Art, title: String, key: String, hold: Boolean) -> Unit,
    private val startHold: () -> Unit,
    private val refuse: (key: String) -> Unit,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) : Uploader {
    override fun upload(art: Art, title: String, key: String) {
        if (!permitted()) {
            log("no Bluetooth permission: auto upload not started: $key", null)
            refuse(key)
            return
        }
        val hold = !listenerBound()
        submit(art, title, key, hold)
        if (!hold) return
        try {
            startHold()
        } catch (e: RuntimeException) { // e.g. a background start refused by the system
            log("listener not bound and the hold service not started; the upload runs without it: $key", e)
        }
    }
}
