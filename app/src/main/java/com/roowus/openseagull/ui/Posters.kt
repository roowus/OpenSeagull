package com.roowus.openseagull.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/**
 * Turning OpenPigeon's poster art into something that can cross a process boundary.
 *
 * ## Why bitmaps at all, when OpenPigeon does not need them
 *
 * OpenPigeon draws its own picker with `ImageProvider(game.gamePoster(null))` — a bare resource
 * id. That is nearly free for them: a [android.widget.RemoteViews] carries the package name it was
 * built with, so the host inflates it against *their* APK and resolves the id there. The image
 * never travels; only an integer does.
 *
 * OpenSeagull cannot use that shortcut, and the reason is the whole architecture. Our RemoteViews
 * is built with *our* package name, because our layouts live in our APK. An OpenPigeon drawable id
 * resolved against our table does not fail — it silently returns an unrelated image (see
 * `InstalledOpenPigeon`). So the poster has to be resolved on our side, against their [Resources],
 * and then sent as pixels.
 *
 * ## Why the size cap is not arbitrary
 *
 * Pixels are expensive to send. A page of ten posters at full cell resolution (roughly 240×180 in
 * `ARGB_8888`) is about 1.7 MB — comfortably past the ~1 MB a binder transaction is allowed. In
 * practice [Bitmap] parcels larger than a few kilobytes travel out-of-band through ashmem rather
 * than in the transaction buffer, so the true ceiling is higher than that arithmetic suggests, but
 * "higher than 1 MB by an amount nobody has measured" is not a budget.
 *
 * So posters are capped on their long edge, preserving aspect ratio, and [approximateBytes] exists
 * so the caller can log what a page actually costs. That turns the limit into something observed
 * on-device rather than argued about here.
 */
internal object Posters {

    /**
     * Longest edge, in pixels, of a poster sent to the host.
     *
     * 192 px is about 64 dp on a 3× phone — under the ~80 dp a cell gets on a 5-column keyboard,
     * so the host scales it up slightly rather than down. That is a deliberate trade of a little
     * sharpness for a page that is roughly a tenth the size of the uncapped version.
     */
    const val MaxEdgePx = 192

    /**
     * Rasterise [drawable] at no more than [MaxEdgePx] on its long edge.
     *
     * Returns `null` for a drawable with no intrinsic size — a colour or a shape rather than a
     * picture. That is a real thing to find in a foreign resource table, and it is not worth
     * inventing dimensions for.
     *
     * A [BitmapDrawable] is not short-circuited to its own bitmap: that bitmap is full-resolution
     * and owned by their [Resources], and handing it straight to RemoteViews would send every one
     * of its pixels. Drawing it through a [Canvas] at the capped size is the point of the exercise.
     */
    fun rasterise(drawable: Drawable, maxEdgePx: Int = MaxEdgePx): Bitmap? {
        val srcWidth = drawable.intrinsicWidth
        val srcHeight = drawable.intrinsicHeight
        if (srcWidth <= 0 || srcHeight <= 0) return null

        val scale = minOf(
            1f,
            maxEdgePx.toFloat() / maxOf(srcWidth, srcHeight).toFloat(),
        )
        val width = (srcWidth * scale).toInt().coerceAtLeast(1)
        val height = (srcHeight * scale).toInt().coerceAtLeast(1)

        // ARGB_8888 rather than RGB_565: several posters have transparent corners, and 565 would
        // render those as black once the host composites them onto its own background.
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        return bitmap
    }

    /** Bytes this bitmap will cost to send, for the page-budget log. */
    fun approximateBytes(bitmap: Bitmap): Int = bitmap.allocationByteCount
}
