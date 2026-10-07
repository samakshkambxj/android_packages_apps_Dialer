// Calculator glue between the View hierarchy and OriginSU's Compose
// FloatingBottomBar: a miuix Backdrop fed by a live snapshot of the real
// View content behind the pill.
//
// The snapshot mirrors the previous View-pipeline behavior: captured on
// every tree pre-draw with the bar hidden, pixel-compared, and the host is
// only invalidated when pixels actually changed, so a static screen idles.
// Mapping is relative by construction (the snapshot covers the bar bounds
// outset by the sampling margin), so no window-coordinate math is needed.

package com.android.dialer.glass.navbar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.GraphicsLayerScope
import top.yukonga.miuix.kmp.blur.Backdrop
import kotlin.math.max
import kotlin.math.roundToInt

/** Sampling margin around the pill for blur/lens edge sampling (miuix uses 40dp). */
internal const val SNAPSHOT_MARGIN_DP = 40f
internal const val SNAPSHOT_DOWNSCALE = 6

class SnapshotBackdrop : Backdrop {
    override val isCoordinatesDependent: Boolean = false
    override val offsetResidualX: Float = 0f
    override val offsetResidualY: Float = 0f

    /** Latest snapshot frame; written on the main thread by SnapshotCapture. */
    var frame: SnapshotCapture.Frame? = null

    // NOTE: miuix passes a layerBlock for press-stretch scaling of the
    // sampled backdrop. It is intentionally not applied here: the bar and
    // indicator press visuals (scale, lens, highlight, inner shadow) are
    // drawn by FloatingBottomBar itself; only the static texture stretch is
    // skipped, which is imperceptible under the moving pill.
    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
        downscaleFactor: Int,
    ) {
        val frame = frame ?: return
        // The capture pool may drop buffers on resize; never draw a dead
        // bitmap (the render thread can lag one frame behind).
        if (frame.bitmap.isRecycled) {
            return
        }
        val image = frame.image
        val margin = frame.marginPx
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset(-margin.roundToInt(), -margin.roundToInt()),
            dstSize = IntSize(
                (size.width + margin * 2f).roundToInt(),
                (size.height + margin * 2f).roundToInt(),
            ),
            filterQuality = FilterQuality.Medium,
        )
    }
}

class SnapshotCapture(private val host: View) {
    class Frame(
        val bitmap: Bitmap,
        val image: ImageBitmap,
        val marginPx: Float,
    )

    var onFrame: ((Frame) -> Unit)? = null

    private var bitmap: Bitmap? = null
    private var scratch: Bitmap? = null
    private var image: ImageBitmap? = null
    private var attached = false
    private val canvas = Canvas()
    private val captureRect = Rect()

    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        refresh()
        true
    }

    fun attach() {
        if (attached) return
        host.viewTreeObserver.addOnPreDrawListener(preDrawListener)
        attached = true
    }

    fun detach() {
        if (!attached) return
        try {
            host.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
        } catch (_: Throwable) {
        }
        attached = false
        recycle()
    }

    private fun recycle() {
        // Never call Bitmap.recycle() here: the published Frame wraps one of
        // these buffers and the render thread may still reference it after a
        // resize (e.g. sheet open/close animations resizing a glass host).
        // Dropping the refs lets GC free the native memory safely instead.
        bitmap = null
        scratch = null
        image = null
    }

    /** Returns true when the snapshot changed and the host must redraw. */
    private fun refresh(): Boolean {
        val parent = host.parent as? View ?: return false
        if (!host.isShown || host.width <= 0 || host.height <= 0) return false
        try {
            captureRect.set(0, 0, host.width, host.height)
            if (parent is ViewGroup) {
                parent.offsetDescendantRectToMyCoords(host, captureRect)
            } else {
                captureRect.offset(host.left, host.top)
            }
            val density = host.resources.displayMetrics.density
            val margin = (SNAPSHOT_MARGIN_DP * density).roundToInt()
            captureRect.inset(-margin, -margin)

            val bw = max(1, captureRect.width() / SNAPSHOT_DOWNSCALE)
            val bh = max(1, captureRect.height() / SNAPSHOT_DOWNSCALE)
            if (bitmap == null || bitmap!!.width != bw || bitmap!!.height != bh) {
                recycle()
                bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                scratch = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            }
            val target = bitmap ?: return false
            val staging = scratch ?: return false

            canvas.setBitmap(staging)
            canvas.save()
            val scale = 1f / SNAPSHOT_DOWNSCALE
            canvas.scale(scale, scale)
            canvas.translate(-captureRect.left.toFloat(), -captureRect.top.toFloat())
            // The bar is hidden while sampling so it never feeds back into
            // its own backdrop (INVISIBLE keeps layout, only skips draw).
            host.visibility = View.INVISIBLE
            try {
                parent.draw(canvas)
            } finally {
                host.visibility = View.VISIBLE
            }
            canvas.restore()
            canvas.setBitmap(null)

            if (image == null || !staging.sameAs(target)) {
                val swap = bitmap
                bitmap = scratch
                scratch = swap
                val fresh = bitmap ?: return false
                val freshImage = fresh.asImageBitmap()
                image = freshImage
                onFrame?.invoke(Frame(fresh, freshImage, margin.toFloat()))
                return true
            }
            return false
        } catch (_: Throwable) {
            return false
        }
    }
}
