package io.github.yuroyami.imagekodec

/** One JPEG marker segment: FF, the marker, a 16-bit length that counts itself, then the body. */
internal fun jpegSegment(marker: Int, body: ByteArray): ByteArray =
    byteArrayOf(0xFF.toByte(), marker.toByte(), ((body.size + 2) ushr 8).toByte(), (body.size + 2).toByte()) + body

/**
 * A baseline gray JPEG, [side] by [side], built by hand: one quantization table, one DC and one AC
 * Huffman table that each hold a single one-bit code, and [scans] copies of a scan with no data.
 * A comment segment of [padding] bytes lets a file with few scans pass the input-size budget too.
 * With [frameMarker] 0xC9 the frame is arithmetic coded, and the Huffman tables go unused.
 */
internal fun emptyScanJpeg(side: Int, scans: Int, padding: Int = 0, frameMarker: Int = 0xC0): ByteArray {
    val quantization = jpegSegment(0xDB, byteArrayOf(0) + ByteArray(64) { 1 })
    val frame = jpegSegment(
        frameMarker,
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

/**
 * A progressive gray JPEG, [side] by [side], whose AC scans hold nothing but end-of-band runs:
 * one AC Huffman table with a single one-bit code for EOB14, and each scan nine EOB14 symbols
 * of 32767 blocks each, enough for 262144 blocks. [scans] lists each scan's Ah and Al over the
 * band 1 to 63. Every coefficient stays zero, so the image is flat gray whatever the scans do.
 */
internal fun eobRunJpeg(side: Int, scans: List<Pair<Int, Int>>): ByteArray {
    val quantization = jpegSegment(0xDB, byteArrayOf(0) + ByteArray(64) { 1 })
    val frame = jpegSegment(
        0xC2,
        byteArrayOf(8, (side ushr 8).toByte(), side.toByte(), (side ushr 8).toByte(), side.toByte(), 1, 1, 0x11, 0),
    )
    val table = jpegSegment(0xC4, byteArrayOf(0x10, 1) + ByteArray(15) + byteArrayOf(0xE0.toByte()))
    // Nine times: code 0, then fourteen one bits of run length. Padded with ones, 0xFF stuffed.
    val bits = StringBuilder()
    repeat(9) { bits.append('0').append("1".repeat(14)) }
    while (bits.length % 8 != 0) bits.append('1')
    val data = ArrayList<Byte>()
    for (i in bits.indices step 8) {
        val b = bits.substring(i, i + 8).toInt(2)
        data.add(b.toByte())
        if (b == 0xFF) data.add(0)
    }
    val out = ArrayList<Byte>()
    fun add(b: ByteArray) = b.forEach { out.add(it) }
    add(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
    add(quantization); add(frame); add(table)
    for ((ah, al) in scans) {
        add(jpegSegment(0xDA, byteArrayOf(1, 1, 0x00, 1, 63, ((ah shl 4) or al).toByte())))
        add(data.toByteArray())
    }
    add(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
    return out.toByteArray()
}

/**
 * An arithmetic-coded progressive gray JPEG, [side] by [side], whose scans hold no data: a DC
 * first scan, then one scan over the band 1 to 63 for each Ah and Al pair of [acScans]. A comment
 * segment of [padding] bytes keeps a file with few scans within the input-size budget.
 */
internal fun emptyArithmeticProgressiveJpeg(side: Int, acScans: List<Pair<Int, Int>>, padding: Int = 0): ByteArray {
    val out = ArrayList<Byte>()
    fun add(b: ByteArray) = b.forEach { out.add(it) }
    add(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
    if (padding > 0) add(jpegSegment(0xFE, ByteArray(padding)))
    add(jpegSegment(0xDB, byteArrayOf(0) + ByteArray(64) { 1 }))
    add(jpegSegment(0xCA, byteArrayOf(8, (side ushr 8).toByte(), side.toByte(), (side ushr 8).toByte(), side.toByte(), 1, 1, 0x11, 0)))
    add(jpegSegment(0xDA, byteArrayOf(1, 1, 0x00, 0, 0, 0)))
    for ((ah, al) in acScans) add(jpegSegment(0xDA, byteArrayOf(1, 1, 0x00, 1, 63, ((ah shl 4) or al).toByte())))
    add(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
    return out.toByteArray()
}

/**
 * A lossless gray JPEG, [side] by [side], with [scans] copies of a scan with no data, predictor 1:
 * one DC table whose one-bit code is category 0. A comment segment of [padding] bytes keeps a file
 * with few scans within the input-size budget.
 */
internal fun emptyLosslessJpeg(side: Int, scans: Int, padding: Int = 0): ByteArray {
    val out = ArrayList<Byte>()
    fun add(b: ByteArray) = b.forEach { out.add(it) }
    add(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
    if (padding > 0) add(jpegSegment(0xFE, ByteArray(padding)))
    add(jpegSegment(0xC3, byteArrayOf(8, (side ushr 8).toByte(), side.toByte(), (side ushr 8).toByte(), side.toByte(), 1, 1, 0x11, 0)))
    add(jpegSegment(0xC4, byteArrayOf(0x00, 1) + ByteArray(15) + byteArrayOf(0)))
    repeat(scans) { add(jpegSegment(0xDA, byteArrayOf(1, 1, 0x00, 1, 0, 0))) }
    add(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
    return out.toByteArray()
}
