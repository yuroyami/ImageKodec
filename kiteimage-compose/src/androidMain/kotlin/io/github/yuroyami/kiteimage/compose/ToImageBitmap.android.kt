package io.github.yuroyami.kiteimage.compose

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.yuroyami.kiteimage.KiteBitmap

/**
 * Android path. `createBitmap(IntArray, …)` takes non-premultiplied ARGB color
 * ints, exactly [KiteBitmap]'s layout, and premultiplies internally.
 */
public actual fun KiteBitmap.toImageBitmap(): ImageBitmap =
    Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
