package io.github.yuroyami.imagekodec.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import io.github.yuroyami.imagekodec.KiteAnimation
import io.github.yuroyami.imagekodec.KiteBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The [ImageBitmap]s of an animation's frames, converted in frame order away from
 * the composition thread.
 *
 * A conversion allocates and copies four bytes a pixel, about 40 ms for a
 * 12-megapixel frame, which is more than two 60 Hz frames have. So composition only
 * converts a first frame small enough to cost well under a millisecond, where
 * drawing nothing for a frame would be the worse choice, the trade Firefox makes
 * when it decodes a small image at once and a large one off the main thread.
 * [fill] converts the rest on [Dispatchers.Default], and the first [ready] frames
 * are the ones that can draw.
 *
 * [convert] is a seam for tests, which record the thread each conversion runs on.
 */
internal class FrameBitmaps(
    val animation: KiteAnimation,
    first: ImageBitmap? = null,
    private val convert: (KiteBitmap) -> ImageBitmap = KiteBitmap::toPreparedImageBitmap,
) {
    private val bitmaps = arrayOfNulls<ImageBitmap>(animation.frames.size)

    /** How many frames, from the first on, have their bitmap. */
    var ready: Int by mutableIntStateOf(0)
        private set

    init {
        val head = first ?: animation.frames[0].bitmap.takeIf { it.convertsInline }?.let(convert)
        if (head != null) {
            bitmaps[0] = head
            ready = 1
        }
    }

    /** The bitmap of frame [index], once it is among the [ready] ones. */
    operator fun get(index: Int): ImageBitmap? = bitmaps[index]

    /** Converts frames in order on [Dispatchers.Default] until the first [count] are ready. */
    suspend fun fill(count: Int) {
        while (ready < count) {
            val index = ready
            val frame = animation.frames[index].bitmap
            // The array is written before the count, so a reader that sees the count sees the bitmap.
            bitmaps[index] = withContext(Dispatchers.Default) { convert(frame) }
            ready = index + 1
        }
    }
}

/** At most this many pixels convert in composition: a 256 by 256 frame takes about 0.3 ms. */
internal const val INLINE_CONVERSION_PIXELS: Long = 1L shl 16

internal val KiteBitmap.convertsInline: Boolean
    get() = width.toLong() * height <= INLINE_CONVERSION_PIXELS

/**
 * [toImageBitmap], then a hint to start the upload to the GPU: on Android it begins on
 * the render thread at once, as Coil does for its bitmaps, so the first draw does not wait.
 */
internal fun KiteBitmap.toPreparedImageBitmap(): ImageBitmap = toImageBitmap().also { it.prepareToDraw() }

/**
 * Draws nothing at a frame's size, so an image whose bitmap is still converting lays
 * out exactly as it will once the bitmap is there.
 */
internal class BlankPainter(width: Int, height: Int) : Painter() {
    override val intrinsicSize: Size = Size(width.toFloat(), height.toFloat())

    override fun DrawScope.onDraw() = Unit
}
