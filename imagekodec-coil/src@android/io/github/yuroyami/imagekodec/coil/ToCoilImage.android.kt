package io.github.yuroyami.imagekodec.coil

import android.graphics.Bitmap
import coil3.Image
import coil3.asImage
import io.github.yuroyami.imagekodec.KiteBitmap

internal actual fun KiteBitmap.toCoilImage(shareable: Boolean): Image =
    Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888).asImage(shareable)
