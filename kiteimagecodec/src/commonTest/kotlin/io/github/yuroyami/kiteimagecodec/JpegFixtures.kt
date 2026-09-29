package io.github.yuroyami.kiteimagecodec

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

/**
 * A gray 64 by 8 baseline JPEG: eight blocks in four restart intervals of two. The DC table has two
 * one-bit codes, "0" for category 0 and "1" for category 6, and the AC table only end of block.
 *  - a full interval: block A is "1" + 111111 + "0" (DC +63, then end of block) and block B is "0" + "0"
 *  - the second interval is short: block A only, and block B is not in the file
 */
internal fun restartIntervalJpeg(): ByteArray {
    val full = byteArrayOf(0xFE.toByte(), 0x3F)
    val short = byteArrayOf(0xFE.toByte())
    fun rst(n: Int) = byteArrayOf(0xFF.toByte(), (0xD0 + n).toByte())
    val entropy = full + rst(0) + short + rst(1) + full + rst(2) + full
    return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
        jpegSegment(0xDB, byteArrayOf(0) + ByteArray(64) { 1 }) +
        jpegSegment(0xC0, byteArrayOf(8, 0, 8, 0, 64, 1, 1, 0x11, 0)) +
        jpegSegment(0xC4, byteArrayOf(0x00, 2) + ByteArray(15) + byteArrayOf(0, 6)) +
        jpegSegment(0xC4, byteArrayOf(0x10, 1) + ByteArray(15) + byteArrayOf(0)) +
        jpegSegment(0xDD, byteArrayOf(0, 2)) +
        jpegSegment(0xDA, byteArrayOf(1, 1, 0x00, 0, 63, 0)) +
        entropy + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
}
