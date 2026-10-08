package io.github.yuroyami.imagekodec

/**
 * A lossless JPEG writer for the tests, written from T.81 Annex H: one Huffman-coded scan over
 * [channels] components sampled 1 by 1 and interleaved, with the predictor, point transform and
 * restart interval asked for. The DC table gives each of the 17 difference categories a 5-bit
 * code. Three components carry the ids R, G and B, so a decoder reads them as RGB.
 *
 * [samples] holds [channels] samples a pixel, each below 2^[precision]. The restart interval
 * counts MCUs and must hold whole rows, as H.1.1 asks.
 */
internal fun losslessJpeg(
    samples: IntArray, width: Int, height: Int, channels: Int, precision: Int,
    predictor: Int, pointTransform: Int = 0, restartInterval: Int = 0,
): ByteArray {
    require(restartInterval % width == 0) { "a lossless restart interval holds whole rows" }
    val out = ArrayList<Byte>()
    fun add(b: ByteArray) = b.forEach { out.add(it) }
    add(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
    val ids = if (channels == 3) listOf('R'.code, 'G'.code, 'B'.code) else (1..channels).toList()
    add(jpegSegment(0xC3, byteArrayOf(precision.toByte(), (height ushr 8).toByte(), height.toByte(), (width ushr 8).toByte(), width.toByte(), channels.toByte()) +
        ids.flatMap { listOf(it.toByte(), 0x11, 0) }.toByteArray()))
    val counts = ByteArray(16).also { it[4] = 17 }
    add(jpegSegment(0xC4, byteArrayOf(0x00) + counts + ByteArray(17) { it.toByte() }))
    if (restartInterval > 0) add(jpegSegment(0xDD, byteArrayOf((restartInterval ushr 8).toByte(), restartInterval.toByte())))
    add(jpegSegment(0xDA, byteArrayOf(channels.toByte()) + ids.flatMap { listOf(it.toByte(), 0x00) }.toByteArray() +
        byteArrayOf(predictor.toByte(), 0, pointTransform.toByte())))

    var acc = 0L
    var bits = 0
    fun put(value: Int, n: Int) {
        acc = (acc shl n) or (value.toLong() and ((1L shl n) - 1))
        bits += n
        while (bits >= 8) {
            val b = ((acc shr (bits - 8)) and 0xFF).toInt()
            out.add(b.toByte())
            if (b == 0xFF) out.add(0)
            bits -= 8
        }
    }
    fun flush() {
        if (bits > 0) put((1 shl (8 - bits)) - 1, 8 - bits)
    }

    val x = IntArray(samples.size) { samples[it] shr pointTransform }
    var firstRow = 0
    var restarts = 0
    var mcus = 0
    for (row in 0 until height) {
        for (col in 0 until width) {
            for (c in 0 until channels) {
                fun at(xx: Int, yy: Int) = x[(yy * width + xx) * channels + c]
                val prediction = when {
                    row == firstRow -> if (col == 0) 1 shl (precision - pointTransform - 1) else at(col - 1, row)
                    col == 0 -> at(col, row - 1)
                    else -> {
                        val ra = at(col - 1, row)
                        val rb = at(col, row - 1)
                        val rc = at(col - 1, row - 1)
                        when (predictor) {
                            1 -> ra
                            2 -> rb
                            3 -> rc
                            4 -> ra + rb - rc
                            5 -> ra + ((rb - rc) shr 1)
                            6 -> rb + ((ra - rc) shr 1)
                            else -> (ra + rb) shr 1
                        }
                    }
                }
                var d = (at(col, row) - prediction) and 0xFFFF
                if (d >= 0x8000) d -= 0x10000
                val category = if (d == -32768) 16 else 32 - kotlin.math.abs(d).countLeadingZeroBits()
                put(category, 5)
                if (category in 1..15) put(if (d < 0) d - 1 else d, category)
            }
            mcus++
            if (restartInterval > 0 && mcus % restartInterval == 0 && mcus < width * height) {
                flush()
                out.add(0xFF.toByte())
                out.add((0xD0 + (restarts++ and 7)).toByte())
                firstRow = row + 1
            }
        }
    }
    flush()
    add(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
    return out.toByteArray()
}
