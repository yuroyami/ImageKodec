package io.github.yuroyami.kiteimage.compose

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.github.yuroyami.kiteimage.KiteBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * Skiko path (JVM, iOS, macOS, JS, WASM). UNPREMUL, not OPAQUE/PREMUL: the core
 * hands over straight (non-premultiplied) alpha, and Skia premultiplies on draw;
 * same choice KitePDF's compose viewer landed on after transparent PDF logos
 * rendered on grey boxes.
 */
public actual fun KiteBitmap.toImageBitmap(): ImageBitmap {
    val rgba = ByteArray(argb.size * 4)
    var at = 0
    for (p in argb) {
        rgba[at++] = ((p ushr 16) and 0xFF).toByte()   // R
        rgba[at++] = ((p ushr 8) and 0xFF).toByte()    // G
        rgba[at++] = (p and 0xFF).toByte()             // B
        rgba[at++] = ((p ushr 24) and 0xFF).toByte()   // A
    }
    val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
    return Image.makeRaster(info, rgba, width * 4).toComposeImageBitmap()
}
