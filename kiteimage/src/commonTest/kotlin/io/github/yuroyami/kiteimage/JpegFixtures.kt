package io.github.yuroyami.kiteimage

/** One JPEG marker segment: FF, the marker, a 16-bit length that counts itself, then the body. */
internal fun jpegSegment(marker: Int, body: ByteArray): ByteArray =
    byteArrayOf(0xFF.toByte(), marker.toByte(), ((body.size + 2) ushr 8).toByte(), (body.size + 2).toByte()) + body

/**
 * A baseline gray JPEG, [side] by [side], built by hand: one quantization table, one DC and one AC
 * Huffman table that each hold a single one-bit code, and [scans] copies of a scan with no data.
 * A comment segment of [padding] bytes lets a file with few scans pass the input-size budget too.
 */
internal fun emptyScanJpeg(side: Int, scans: Int, padding: Int = 0): ByteArray {
    val quantization = jpegSegment(0xDB, byteArrayOf(0) + ByteArray(64) { 1 })
    val frame = jpegSegment(
        0xC0,
        byteArrayOf(8, (side ushr 8).toByte(), side.toByte(), (side ushr 8).toByte(), side.toByte(), 1, 1, 0x11, 0),
    )
    val tables = jpegSegment(0xC4, byteArrayOf(0x00, 1) + ByteArray(15) + byteArrayOf(0)) +   // DC 0: category 0
        jpegSegment(0xC4, byteArrayOf(0x10, 1) + ByteArray(15) + byteArrayOf(0))               // AC 0: end of block
    val scan = jpegSegment(0xDA, byteArrayOf(1, 1, 0x00, 0, 63, 0))
    val out = ArrayList<Byte>()
    fun add(b: ByteArray) = b.forEach { out.add(it) }
    add(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
    if (padding > 0) add(jpegSegment(0xFE, ByteArray(padding)))
    add(quantization); add(frame); add(tables)
    repeat(scans) { add(scan) }
    add(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
    return out.toByteArray()
}
