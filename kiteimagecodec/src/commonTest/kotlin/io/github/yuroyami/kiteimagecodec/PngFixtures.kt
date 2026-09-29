package io.github.yuroyami.kiteimagecodec

import io.github.yuroyami.kiteimagecodec.internal.flate.Crc32

/** Building blocks for PNG files that a test has to assemble by hand. */

internal val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

internal fun beBytes(v: Int) = byteArrayOf(
    ((v ushr 24) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte(),
    ((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte(),
)

/** One PNG chunk: length, type, body and the CRC-32 over type and body. */
internal fun pngChunk(type: String, body: ByteArray): ByteArray {
    val typeBytes = ByteArray(4) { type[it].code.toByte() }
    val crc = Crc32()
    crc.update(typeBytes)
    crc.update(body)
    return beBytes(body.size) + typeBytes + body + beBytes(crc.value().toInt())
}

/** The `IHDR` chunk for a non-interlaced image. */
internal fun pngHeader(width: Int, height: Int, bitDepth: Int, colorType: Int): ByteArray =
    pngChunk("IHDR", beBytes(width) + beBytes(height) + byteArrayOf(bitDepth.toByte(), colorType.toByte(), 0, 0, 0))
