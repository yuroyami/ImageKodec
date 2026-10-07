package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.imagekodec.ImageDecodeException

/** TIFF 6.0: ExtraSamples describes each component after the photometric colors. */
internal class TiffAlpha(samples: Int, photometric: Int, extras: LongArray?) {
    val sample: Int
    val associated: Boolean

    init {
        val colors = if (photometric == 2 || photometric == 6) 3 else 1
        if (extras == null) {
            // Preserve the existing straight-alpha fallback for omitted metadata.
            sample = if (photometric in 0..3 && samples > colors) colors else -1
            associated = false
        } else {
            if (extras.size != samples - colors) {
                throw ImageDecodeException("TIFF: ExtraSamples requires ${samples - colors} values, got ${extras.size}")
            }
            if (extras.any { it !in 0L..2L } || extras.count { it != 0L } > 1) {
                throw ImageDecodeException("TIFF: ExtraSamples has invalid or competing alpha descriptors")
            }
            val index = extras.indexOfFirst { it != 0L }
            sample = if (index >= 0) colors + index else -1
            associated = index >= 0 && extras[index] == 1L
        }
    }
}
