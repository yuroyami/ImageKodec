package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.internal.flate.ByteArrayBuilder

/** A lossless WebP file: the RIFF container around one `VP8L` chunk from [Vp8lEncoder]. */
internal object WebpEncoder {

    fun encode(bitmap: KiteBitmap): ByteArray {
        val vp8l = Vp8lEncoder.encode(bitmap)
        val padded = vp8l.size + (vp8l.size and 1)
        val out = ByteArrayBuilder(padded + 20)
        out.append("RIFF".encodeToByteArray())
        le32(out, 4 + 8 + padded)
        out.append("WEBP".encodeToByteArray())
        out.append("VP8L".encodeToByteArray())
        le32(out, vp8l.size)
        out.append(vp8l)
        if (vp8l.size and 1 != 0) out.append(0)
        return out.toByteArray()
    }

    private fun le32(out: ByteArrayBuilder, v: Int) {
        out.append(byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte()))
    }
}
