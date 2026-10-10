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
    out.addAll(byteArrayOf(0xFF.toByte(), 0xD8.toByte()).toList())
    out.addAll(losslessFrame(0xC3, samples, width, height, channels, precision, predictor, pointTransform, restartInterval).toList())
    out.addAll(byteArrayOf(0xFF.toByte(), 0xD9.toByte()).toList())
    return out.toByteArray()
}

/**
 * A lossless hierarchical JPEG (T.81 Annex J) of [samples]: a DHP segment, a lossless frame of the
 * image halved [levels] times, then a differential lossless frame for each doubling, each the
 * difference between the image at that size and the frame before it doubled by the EXP segment
 * as J.1.1.2 doubles it. [vertical] false halves and doubles across only. [refinements]
 * differential frames at full size with no EXP follow, each coding a zero difference.
 */
internal fun hierarchicalLosslessJpeg(
    samples: IntArray, width: Int, height: Int, channels: Int, precision: Int,
    levels: Int, vertical: Boolean = true, refinements: Int = 0,
): ByteArray {
    // The pyramid, full size last: each level keeps every other sample of the one above it.
    val sizes = ArrayList<Pair<Int, Int>>()
    val images = ArrayList<IntArray>()
    sizes.add(width to height)
    images.add(samples)
    repeat(levels) {
        val (w, h) = sizes.last()
        val nw = (w + 1) / 2
        val nh = if (vertical) (h + 1) / 2 else h
        val src = images.last()
        images.add(IntArray(nw * nh * channels) { i ->
            val c = i % channels
            val x = (i / channels) % nw
            val y = (i / channels) / nw
            src[((if (vertical) 2 * y else y) * w + 2 * x) * channels + c]
        })
        sizes.add(nw to nh)
    }
    sizes.reverse()
    images.reverse()

    val out = ArrayList<Byte>()
    fun add(b: ByteArray) = b.forEach { out.add(it) }
    add(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
    add(jpegSegment(0xDE, frameHeader(width, height, channels, precision)))
    add(losslessFrame(0xC3, images[0], sizes[0].first, sizes[0].second, channels, precision, 1, 0, 0))
    var reference = images[0]
    for (k in 1..levels) {
        val (rw, rh) = sizes[k - 1]
        val (w, h) = sizes[k]
        add(jpegSegment(0xDF, byteArrayOf((0x10 or (if (vertical) 1 else 0)).toByte())))
        val up = upsampleT81(reference, rw, rh, channels, vertical, w, h)
        val diff = IntArray(up.size) { (images[k][it] - up[it]) and 0xFFFF }
        add(losslessFrame(0xC7, diff, w, h, channels, precision, 0, 0, 0))
        reference = images[k]
    }
    repeat(refinements) { add(losslessFrame(0xC7, IntArray(samples.size), width, height, channels, precision, 0, 0, 0)) }
    add(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
    return out.toByteArray()
}

/** [reference], [rw] by [rh], doubled across and, with [vertical], down, as T.81 J.1.1.2 asks, cut to [w] by [h]. */
private fun upsampleT81(reference: IntArray, rw: Int, rh: Int, channels: Int, vertical: Boolean, w: Int, h: Int): IntArray {
    fun at(x: Int, y: Int, c: Int) = reference[(y * rw + x) * channels + c]
    fun across(x: Int, y: Int, c: Int): Int =
        if (x % 2 == 0) at(x / 2, y, c) else (at(x / 2, y, c) + at(minOf(x / 2 + 1, rw - 1), y, c)) shr 1
    return IntArray(w * h * channels) { i ->
        val c = i % channels
        val x = (i / channels) % w
        val y = (i / channels) / w
        when {
            !vertical -> across(x, y, c)
            y % 2 == 0 -> across(x, y / 2, c)
            else -> (across(x, y / 2, c) + across(x, minOf(y / 2 + 1, rh - 1), c)) shr 1
        }
    }
}

private fun frameHeader(width: Int, height: Int, channels: Int, precision: Int): ByteArray {
    val ids = if (channels == 3) listOf('R'.code, 'G'.code, 'B'.code) else (1..channels).toList()
    return byteArrayOf(precision.toByte(), (height ushr 8).toByte(), height.toByte(), (width ushr 8).toByte(), width.toByte(), channels.toByte()) +
        ids.flatMap { listOf(it.toByte(), 0x11, 0) }.toByteArray()
}

/**
 * One lossless frame of type [marker] with its table and scan. Predictor 0 codes each sample as
 * it is, as a differential frame does; any other predicts it from the samples before it.
 */
private fun losslessFrame(
    marker: Int, samples: IntArray, width: Int, height: Int, channels: Int, precision: Int,
    predictor: Int, pointTransform: Int, restartInterval: Int,
): ByteArray {
    val out = ArrayList<Byte>()
    fun add(b: ByteArray) = b.forEach { out.add(it) }
    val ids = if (channels == 3) listOf('R'.code, 'G'.code, 'B'.code) else (1..channels).toList()
    add(jpegSegment(marker, frameHeader(width, height, channels, precision)))
    val counts = ByteArray(16).also { it[4] = 17 }
    add(jpegSegment(0xC4, byteArrayOf(0x00) + counts + ByteArray(17) { it.toByte() }))
    add(jpegSegment(0xDD, byteArrayOf((restartInterval ushr 8).toByte(), restartInterval.toByte())))
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
                    predictor == 0 -> 0
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
    return out.toByteArray()
}
