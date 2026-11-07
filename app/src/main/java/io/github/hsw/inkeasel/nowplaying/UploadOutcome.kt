package io.github.hsw.inkeasel.nowplaying

/**
 * What `UploadService` does after one upload: [toast] to show (`null` = log only), whether to [report] the
 * result to `FrameController.onUploadResult`, and whether to [remember] the source as the new default
 * (`FrameController.onManualUpload`).
 */
data class UploadOutcome(val toast: String?, val report: Boolean, val remember: Boolean) {
    companion object {
        /**
         * An automatic upload (a poster or the revert to the default) never toasts over the film and never
         * becomes the default; it only reports. A manual upload toasts as before and, on success only,
         * becomes the default.
         */
        fun of(auto: Boolean, ok: Boolean, error: String? = null): UploadOutcome = when {
            auto -> UploadOutcome(toast = null, report = true, remember = false)
            ok -> UploadOutcome(toast = "Фото отправлено", report = false, remember = true)
            else -> UploadOutcome(toast = "Ошибка отправки: ${error ?: "неизвестная ошибка"}", report = false, remember = false)
        }
    }
}
