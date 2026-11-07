package io.github.hsw.inkeasel.nowplaying

import io.github.hsw.inkeasel.R
import io.github.hsw.inkeasel.Txt
import io.github.hsw.inkeasel.txt

/**
 * What `UploadService` does after one upload: [toast] to show (`null` = log only), whether to [report] the
 * result to `FrameController.onUploadResult`, and whether to [remember] the source as the new default
 * (`FrameController.onManualUpload`).
 */
data class UploadOutcome(val toast: Txt?, val report: Boolean, val remember: Boolean) {
    companion object {
        /**
         * An automatic upload (a poster or the revert to the default) never toasts over the film and never
         * becomes the default; it only reports. A manual upload toasts as before and, on success only,
         * becomes the default. [error] is the failure as the user reads it, already in words.
         */
        fun of(auto: Boolean, ok: Boolean, error: String? = null): UploadOutcome = when {
            auto -> UploadOutcome(toast = null, report = true, remember = false)
            ok -> UploadOutcome(toast = Txt(R.string.upload_sent), report = false, remember = true)
            else -> UploadOutcome(
                toast = txt(R.string.upload_failed, error ?: Txt(R.string.upload_error_unknown)),
                report = false,
                remember = false,
            )
        }

        /**
         * What the upload notification calls a picture that has no title of its own ([Source.title] blank):
         * the default picture for a revert (a [key] with [FrameController.DEFAULT_PREFIX]), else just a picture.
         */
        fun untitled(key: String?): Txt =
            Txt(if (key?.startsWith(FrameController.DEFAULT_PREFIX) == true) R.string.upload_default_picture else R.string.upload_picture)
    }
}
