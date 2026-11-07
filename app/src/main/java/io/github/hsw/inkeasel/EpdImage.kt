package io.github.hsw.inkeasel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect

/**
 * Image preparation for one frame's [FrameFormat]: decode, crop
 * to the panel, dither onto its inks, pack. The pixel work itself is in [FrameFormat], free of Android types.
 */
object EpdImage {
    /** Decodes [bytes], subsampling large originals so the short side still covers the panel of [fmt]. */
    fun decode(bytes: ByteArray, fmt: FrameFormat): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= fmt.width && bounds.outHeight / (sample * 2) >= fmt.height) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: throw TxtException(Txt(R.string.image_decode_failed))
    }

    /** Scales to cover the panel of [fmt] and centre-crops. */
    fun fit(src: Bitmap, fmt: FrameFormat): Bitmap {
        val w = fmt.width
        val h = fmt.height
        val scale = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val cw = (w / scale).toInt().coerceAtMost(src.width)
        val ch = (h / scale).toInt().coerceAtMost(src.height)
        val left = (src.width - cw) / 2
        val top = (src.height - ch) / 2
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, Rect(left, top, left + cw, top + ch), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** Floyd–Steinberg onto the inks of [fmt] ([FrameFormat.dither]); one ink index per pixel, row-major. */
    fun dither(img: Bitmap, fmt: FrameFormat): ByteArray {
        require(img.width == fmt.width && img.height == fmt.height)
        val px = IntArray(fmt.width * fmt.height).also { img.getPixels(it, 0, fmt.width, 0, 0, fmt.width, fmt.height) }
        return fmt.dither(px)
    }

    /** The payload for the frame ([FrameFormat.pack]; E6 1024×600: 307 200 bytes). */
    fun pack(indices: ByteArray, fmt: FrameFormat): ByteArray = fmt.pack(indices)

    /** Decode, crop, dither and pack [bytes] for [fmt]. */
    fun render(bytes: ByteArray, fmt: FrameFormat): ByteArray = pack(dither(fit(decode(bytes, fmt), fmt), fmt), fmt)

    /** What the panel will roughly look like. */
    fun preview(indices: ByteArray, fmt: FrameFormat): Bitmap =
        Bitmap.createBitmap(fmt.previewPixels(indices), fmt.width, fmt.height, Bitmap.Config.ARGB_8888)
}
