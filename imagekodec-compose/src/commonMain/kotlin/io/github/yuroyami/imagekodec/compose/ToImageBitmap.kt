package io.github.yuroyami.imagekodec.compose

import androidx.compose.ui.graphics.ImageBitmap
import io.github.yuroyami.imagekodec.KiteBitmap

/**
 * Convert a decoded [KiteBitmap] (non-premultiplied ARGB_8888 in an IntArray) to
 * a Compose [ImageBitmap].
 *
 * Android goes through `android.graphics.Bitmap`; every other target goes
 * through one shared Skiko path, which premultiplies into Skia's native N32
 * raster in one pass. Either way it copies every pixel, so for a large bitmap
 * call it off the main thread, as [KiteImage] and [KiteAnimatedImage] do.
 *
 * Fidelity contract (both paths: Compose's backing store premultiplies):
 * opaque pixels are bit-exact, semi-transparent channels may wobble ±1 from the
 * premultiply round-trip, and RGB under alpha 0 is not preserved. None of the
 * three is visible.
 */
public expect fun KiteBitmap.toImageBitmap(): ImageBitmap
