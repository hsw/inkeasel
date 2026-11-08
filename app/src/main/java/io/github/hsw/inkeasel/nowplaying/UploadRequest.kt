package io.github.hsw.inkeasel.nowplaying

import java.io.File

/**
 * One request of the upload engine (`UploadEngine`, [UploadEngineCore]): what to send and how it is named
 * ([source]), the picture ([art]), and for an automatic one ([auto]) the controller's [key] and [stamp]
 * ([FrameController.manualUploads] when it was decided). Built by [manual] or [auto], so an automatic request
 * always has its key. Free of Android types.
 */
class UploadRequest private constructor(
    val source: Source,
    val art: Art,
    val auto: Boolean,
    val key: String?,
    val stamp: Long,
) {
    companion object {
        /** A manual upload: no key and no stamp, since a manual request is never stale ([FrameController.isStale]). */
        fun manual(source: Source, art: Art) = UploadRequest(source, art, auto = false, key = null, stamp = 0)

        /**
         * An automatic upload of the [FrameController] ([key], [stamp]); [title] is the film's, or empty for the
         * default picture (a file is then named by itself, for the log).
         */
        fun auto(art: Art, title: String, key: String, stamp: Long): UploadRequest {
            val source = when (art) {
                is Art.Url -> Source(Source.Type.URL, art.url, title)
                is Art.File -> Source(Source.Type.PATH, art.path, title.ifBlank { File(art.path).nameWithoutExtension })
            }
            return UploadRequest(source, art, auto = true, key = key, stamp = stamp)
        }
    }
}
