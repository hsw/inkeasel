package io.github.hsw.inkeasel.catalog

import java.security.MessageDigest

/**
 * Image URLs of a Wikimedia Commons file, built from its name alone (no API request), as MediaWiki names them
 * on [HOST]. Pure.
 *
 * - The original: `/wikipedia/commons/<h1>/<h2>/<File>`, where `<h2>` is the first two hex digits of the MD5
 *   of the name's UTF-8 bytes (spaces as underscores) and `<h1>` the first of them.
 * - A thumbnail: `/wikipedia/commons/thumb/<h1>/<h2>/<File>/<W>px-<File>` at a standard width ([WIDTHS]; any
 *   other is refused); a name longer than [LONG_NAME_BYTES] bytes is `<W>px-thumbnail.<ext>` instead; a TIFF's
 *   is `lossy-page1-<W>px-<File>.jpg`, an SVG's `<W>px-<File>.png`.
 * - The path segments are percent-encoded as MediaWiki does it (`wfUrlencode`): `(),;:@$!*~` stay as they are,
 *   so the URLs are the ones the Commons API hands out and share its caches.
 */
object CommonsUrls {
    const val HOST = "upload.wikimedia.org"

    private const val BASE = "https://$HOST/wikipedia/commons"

    /** Wikimedia's standard thumbnail widths (mediawiki.org "Common thumbnail sizes"). */
    val WIDTHS = listOf(20, 40, 60, 120, 250, 330, 500, 960, 1280, 1920, 3840)

    /** Commons' `abbrvThreshold`: a longer name is `thumbnail.<ext>` in the thumbnail's own name. */
    const val LONG_NAME_BYTES = 160

    /** Formats Commons serves as they are, so a request wider than the original gets the original. */
    private val AS_IS = setOf("jpg", "jpeg", "png", "webp", "gif")

    /** Multi-page formats, rendered to JPEG from their first page. */
    private val PAGED = setOf("tif", "tiff")

    /** The file name as a Commons page title key: trimmed, spaces as underscores. */
    fun key(file: String): String = file.trim().replace(' ', '_')

    /** The smallest standard width that covers [width] px, [WIDTHS]' last at most. */
    fun step(width: Int): Int = WIDTHS.firstOrNull { it >= width } ?: WIDTHS.last()

    /** The original file. */
    fun original(file: String): String {
        val k = key(file)
        return "$BASE/${hashPath(k)}/${encode(k)}"
    }

    /** The thumbnail [width] px wide; [width] must be one of [WIDTHS]. */
    fun thumb(file: String, width: Int): String {
        require(width in WIDTHS) { "not a standard thumbnail width: $width" }
        val k = key(file)
        val ext = extension(k)
        val name = if (k.toByteArray(Charsets.UTF_8).size > LONG_NAME_BYTES) "thumbnail.$ext" else k
        val thumbName = when (ext) {
            in PAGED -> "lossy-page1-${width}px-$name.jpg"
            "svg" -> "${width}px-$name.png"
            else -> "${width}px-$name"
        }
        return "$BASE/thumb/${hashPath(k)}/${encode(k)}/${encode(thumbName)}"
    }

    /**
     * What to load for [width] px of [file], whose original is [originalWidth] px wide (`null`: unknown): the
     * thumbnail at [step] of [width]; a JPEG, PNG, WebP or GIF no wider than that step as its original (Commons
     * does not scale a picture up); a TIFF narrower than it at the largest standard width it has; an SVG at any
     * width (it scales).
     */
    fun url(file: String, width: Int, originalWidth: Int?): String {
        val step = step(width)
        val ext = extension(key(file))
        val ow = originalWidth?.takeIf { it > 0 }
        return when {
            ow != null && ow <= step && ext in AS_IS -> original(file)
            ow != null && ow < step && ext in PAGED -> thumb(file, WIDTHS.lastOrNull { it <= ow } ?: WIDTHS.first())
            else -> thumb(file, step)
        }
    }

    /** The extension, lowercase (`""` without one). */
    private fun extension(key: String): String =
        key.substringAfterLast('.', "").lowercase()

    private fun hashPath(key: String): String {
        val hex = MessageDigest.getInstance("MD5").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "${hex[0]}/${hex.substring(0, 2)}"
    }

    private const val HEX = "0123456789ABCDEF"

    /** UTF-8 percent-encoding of everything but letters, digits and `-_.~();:@$!*,` (MediaWiki's set). */
    internal fun encode(s: String): String = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if (c < 0x80 && (c.toChar().isLetterOrDigit() || c.toChar() in "-_.~();:@$!*,")) {
                append(c.toChar())
            } else {
                append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
            }
        }
    }
}
