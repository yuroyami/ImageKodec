package io.github.yuroyami.kiteimage.coil

import coil3.Image
import coil3.asImage
import io.github.yuroyami.kiteimage.KiteBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/** Skiko path (JVM, iOS, macOS, JS, WASM): UNPREMUL raster, same contract as
 *  kiteimage-compose's toImageBitmap. */
internal actual fun KiteBitmap.toCoilImage(shareable: Boolean): Image {
    val rgba = ByteArray(argb.size * 4)
    var at = 0
    for (p in argb) {
        rgba[at++] = ((p ushr 16) and 0xFF).toByte()   // R
        rgba[at++] = ((p ushr 8) and 0xFF).toByte()    // G
        rgba[at++] = (p and 0xFF).toByte()             // B
        rgba[at++] = ((p ushr 24) and 0xFF).toByte()   // A
    }
    val bitmap = Bitmap()
    bitmap.allocPixels(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL))
    bitmap.installPixels(rgba)
    bitmap.setImmutable()
    return bitmap.asImage(shareable)
}
