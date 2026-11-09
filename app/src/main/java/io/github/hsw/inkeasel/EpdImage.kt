package io.github.hsw.inkeasel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect

/**
 * Image preparation for one frame's [FrameFormat]: decode, fit the whole picture inside the panel, dither onto
 * its inks with paper (white) bars around it, pack. The pixel work and the geometry are in [FrameFormat], free
 * of Android types.
 */
object EpdImage {
    /** Decodes [bytes], subsampling large originals while they stay at least as large as their place on the panel of [fmt]. */
    fun decode(bytes: ByteArray, fmt: FrameFormat): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sample = if (bounds.outWidth > 0 && bounds.outHeight > 0) fmt.sampleSize(bounds.outWidth, bounds.outHeight) else 1
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: throw TxtException(Txt(R.string.upload_err_decode))
    }

    /**
     * [src] scaled to its place on the panel of [fmt] ([FrameFormat.fit]): the whole picture, no crop. Only the
     * picture's own pixels, not the panel: [dither] centres it and puts the paper around it.
     */
    fun fit(src: Bitmap, fmt: FrameFormat): Bitmap {
        val at = fmt.fit(src.width, src.height)
        val out = Bitmap.createBitmap(at.width, at.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, Rect(0, 0, src.width, src.height), Rect(0, 0, at.width, at.height), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /**
     * Floyd–Steinberg of the fitted [img] onto the inks of [fmt], centred on the panel with paper around it
     * ([FrameFormat.dither]); one ink index per pixel of the panel, row-major.
     */
    fun dither(img: Bitmap, fmt: FrameFormat): ByteArray {
        val at = fmt.centred(img.width, img.height)
        val px = IntArray(at.width * at.height).also { img.getPixels(it, 0, at.width, 0, 0, at.width, at.height) }
        return fmt.dither(px, at)
    }

    /** The payload for the frame ([FrameFormat.pack]; E6 1024×600: 307 200 bytes). */
    fun pack(indices: ByteArray, fmt: FrameFormat): ByteArray = fmt.pack(indices)

    /** Decode, fit, dither and pack [bytes] for [fmt]; the intermediate bitmaps are freed at once. */
    fun render(bytes: ByteArray, fmt: FrameFormat): ByteArray = pack(indicesOf(bytes, fmt), fmt)

    /** Decode, fit and dither; each bitmap is recycled as soon as the next step has its pixels (a TV has little memory). */
    private fun indicesOf(bytes: ByteArray, fmt: FrameFormat): ByteArray {
        val src = decode(bytes, fmt)
        val fitted = try { fit(src, fmt) } finally { src.recycle() }
        return try { dither(fitted, fmt) } finally { fitted.recycle() }
    }
}
