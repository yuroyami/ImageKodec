package io.github.yuroyami.kiteimage.compose

import androidx.compose.ui.graphics.ImageBitmap
import io.github.yuroyami.kiteimage.KiteBitmap

/**
 * Convert a decoded [KiteBitmap] (non-premultiplied ARGB_8888 in an IntArray) to
 * a Compose [ImageBitmap].
 *
 * Android goes through `android.graphics.Bitmap`; every other target goes
 * through one shared Skiko path (an UNPREMUL RGBA_8888 raster: straight alpha,
 * exactly what [KiteBitmap] holds).
 *
 * Fidelity contract (both paths: Compose's backing store premultiplies):
 * opaque pixels are bit-exact, semi-transparent channels may wobble ±1 from the
 * premultiply round-trip, and RGB under alpha 0 is not preserved. None of the
 * three is visible.
 */
public expect fun KiteBitmap.toImageBitmap(): ImageBitmap
