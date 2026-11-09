package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.internal.flate.ByteArrayBuilder

/**
 * A lossless WebP file: the RIFF container around one `VP8L` chunk from [Vp8lEncoder]. With
 * metadata, the extended form: `VP8X`, `ICCP`, the image, `EXIF` and `XMP ` (#129).
 */
internal object WebpEncoder {

    fun encode(bitmap: KiteBitmap, metadata: io.github.yuroyami.imagekodec.ImageMetadata? = null): ByteArray {
        if (metadata != null && !metadata.isEmpty) return extended(bitmap, metadata)
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

    private fun extended(bitmap: KiteBitmap, metadata: io.github.yuroyami.imagekodec.ImageMetadata): ByteArray {
        val chunks = ArrayList<Pair<String, ByteArray>>()
        var flags = 0
        if (bitmap.hasTransparency()) flags = flags or 0x10
        metadata.icc?.let { flags = flags or 0x20 }
        metadata.exif?.let { flags = flags or 0x08 }
        metadata.xmp?.let { flags = flags or 0x04 }
        val vp8x = ByteArray(10)
        vp8x[0] = flags.toByte()
        val w = bitmap.width - 1
        val h = bitmap.height - 1
        vp8x[4] = w.toByte(); vp8x[5] = (w ushr 8).toByte(); vp8x[6] = (w ushr 16).toByte()
        vp8x[7] = h.toByte(); vp8x[8] = (h ushr 8).toByte(); vp8x[9] = (h ushr 16).toByte()
        chunks += "VP8X" to vp8x
        metadata.icc?.let { chunks += "ICCP" to it }
        chunks += "VP8L" to Vp8lEncoder.encode(bitmap)
        metadata.exif?.let { chunks += "EXIF" to it }
        metadata.xmp?.let { chunks += "XMP " to it.encodeToByteArray() }
        val body = chunks.sumOf { 8 + it.second.size + (it.second.size and 1) }
        val out = ByteArrayBuilder(body + 12)
        out.append("RIFF".encodeToByteArray())
        le32(out, 4 + body)
        out.append("WEBP".encodeToByteArray())
        for ((type, data) in chunks) {
            out.append(type.encodeToByteArray())
            le32(out, data.size)
            out.append(data)
            if (data.size and 1 != 0) out.append(0)
        }
        return out.toByteArray()
    }

    private fun le32(out: ByteArrayBuilder, v: Int) {
        out.append(byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte()))
    }
}
