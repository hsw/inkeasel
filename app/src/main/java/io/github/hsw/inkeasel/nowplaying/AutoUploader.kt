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
 * - When the listener unbinds ([listenerLost]), unheld work still queued or in flight is made held
 *   ([holdAll]) and the hold starts for it, as above. Both run under one lock, so a request is either
 *   submitted unheld before the promotion (and promoted) or decided after it (and held).
 *
 * A queued automatic request that the new one replaces is reported failed by the engine from inside
 * [submit], so [FrameController.onUploadResult] runs re-entrantly under the controller's lock.
 */
class AutoUploader(
    private val permitted: () -> Boolean,
    private val listenerBound: () -> Boolean,
    private val submit: (art: Art, title: String, key: String, hold: Boolean) -> Unit,
    private val startHold: () -> Unit,
    private val refuse: (key: String) -> Unit,
    private val holdAll: () -> Boolean,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) : Uploader {
    private val lock = Any() // lock order: the controller's, then this, then the engine's

    override fun upload(art: Art, title: String, key: String) {
        if (!permitted()) {
            log("no Bluetooth permission: auto upload not started: $key", null)
            refuse(key)
            return
        }
        val hold = synchronized(lock) {
            val hold = !listenerBound()
            submit(art, title, key, hold)
            hold
        }
        if (hold) startHoldSafe("listener not bound and the hold service not started; the upload runs without it: $key")
    }

    /**
     * The notification listener has unbound (its flag is already cleared): automatic work it left unheld,
     * queued or in flight, is made held, and the hold service started for it.
     */
    fun listenerLost() {
        val promoted = synchronized(lock) { holdAll() }
        if (promoted) startHoldSafe("listener unbound during an upload and the hold service not started; it runs without it")
    }

    private fun startHoldSafe(refused: String) {
        try {
            startHold()
        } catch (e: RuntimeException) { // e.g. a background start refused by the system
            log(refused, e)
        }
    }
}
