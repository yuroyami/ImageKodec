package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.internal.flate.Zlib

/** PNGs written byte by byte with colour chunks, for the colour conversion tests (#108). */
internal object PngColorFiles {
    private val chunks = ColorProfileTest()

    /**
     * A [width] by [height] PNG of [channels] samples a pixel (1 gray, 3 RGB) at [depth] (8 or
     * 16) bits, [samples] row by row, with [extra] chunks after its IHDR.
     */
    fun png(width: Int, height: Int, channels: Int, depth: Int, samples: IntArray, vararg extra: ByteArray): ByteArray {
        require(samples.size == width * height * channels)
        val bytesPerSample = depth / 8
        val raw = ByteArray(height * (1 + width * channels * bytesPerSample))
        var at = 0
        for (y in 0 until height) {
            raw[at++] = 0
            for (i in 0 until width * channels) {
                val v = samples[y * width * channels + i]
                if (depth == 16) raw[at++] = (v ushr 8).toByte()
                raw[at++] = v.toByte()
            }
        }
        val ihdr = chunks.be32(width) + chunks.be32(height) + byteArrayOf(depth.toByte(), (if (channels == 1) 0 else 2).toByte(), 0, 0, 0)
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        return signature + chunks.chunk("IHDR", ihdr) + extra.fold(ByteArray(0)) { a, b -> a + b } +
            chunks.chunk("IDAT", Zlib.compress(raw)) + chunks.chunk("IEND", ByteArray(0))
    }

    fun cicp(primaries: Int, transfer: Int, matrix: Int = 0, fullRange: Boolean = true): ByteArray =
        chunks.chunk("cICP", byteArrayOf(primaries.toByte(), transfer.toByte(), matrix.toByte(), if (fullRange) 1 else 0))

    fun gama(gamma: Int): ByteArray = chunks.chunk("gAMA", chunks.be32(gamma))

    /** `cHRM` of white, red, green and blue x and y. */
    fun chrm(vararg xy: Double): ByteArray =
        chunks.chunk("cHRM", xy.map { chunks.be32(kotlin.math.round(it * 100_000).toInt()) }.reduce { a, b -> a + b })

    fun srgb(intent: Int = 0): ByteArray = chunks.chunk("sRGB", byteArrayOf(intent.toByte()))

    fun iccp(icc: ByteArray): ByteArray =
        chunks.chunk("iCCP", "p".encodeToByteArray() + byteArrayOf(0, 0) + Zlib.compress(icc))

    /** `cLLi`: maximum content and frame-average light, in cd/m². */
    fun clli(maxContent: Double, maxFrameAverage: Double): ByteArray =
        chunks.chunk("cLLi", chunks.be32((maxContent * 10000).toInt()) + chunks.be32((maxFrameAverage * 10000).toInt()))

    /** `mDCv` with BT.2020 primaries, D65 white, and [maxNits] and [minNits]. */
    fun mdcv(maxNits: Double, minNits: Double): ByteArray {
        val xy = intArrayOf(35400, 14600, 8500, 39850, 6550, 2300, 15635, 16450)
        val body = xy.map { byteArrayOf((it ushr 8).toByte(), it.toByte()) }.reduce { a, b -> a + b } +
            chunks.be32((maxNits * 10000).toInt()) + chunks.be32((minNits * 10000).toInt())
        return chunks.chunk("mDCv", body)
    }
}
