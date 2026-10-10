package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.imagekodec.internal.Budget

/** Header inspection and raster allocation must make the same size decision. */
internal object Jp2Limits {
    fun sizeRefusal(width: Int, height: Int, inputBytes: Int): String? =
        if (Budget.fits(width, height, inputBytes)) null
        else "JPEG 2000: SIZ image ${width}x$height exceeds safety limits (${Budget.pixelCap(inputBytes)}-pixel budget)"
}
