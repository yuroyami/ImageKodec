package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.internal.flate.InflateException
import io.github.yuroyami.imagekodec.internal.flate.Zlib
import java.util.zip.Deflater
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/**
 * Oracle test for the table-accelerated inflate: everything java.util.zip's
 * Deflater emits (real zlib, all levels/strategies) must decompress to the
 * exact input. Exercises stored blocks (level 0), fixed-Huffman-heavy small
 * payloads, dynamic blocks, long matches across the 32 KiB window, and inputs
 * larger than any single block.
 */
class InflateOracleTest {

    @Test
    fun streamingFlushesMatchZlibAtEveryBlockBoundary() {
        for (strategy in intArrayOf(Deflater.DEFAULT_STRATEGY, Deflater.FILTERED, Deflater.HUFFMAN_ONLY)) {
            for (flush in intArrayOf(Deflater.SYNC_FLUSH, Deflater.FULL_FLUSH)) {
                for (width in intArrayOf(2, 7, 16, 31, 40)) {
                    val random = Random(76 + width)
                    val rows = List(6) { ByteArray(width + 1) { i -> if (i == 0) 0 else (random.nextInt(4) * 37).toByte() } }
                    val expected = rows.reduce { a, b -> a + b }
                    val deflater = Deflater(6)
                    deflater.setStrategy(strategy)
                    val compressed = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    try {
                        for (row in rows) {
                            deflater.setInput(row)
                            var n: Int
                            do {
                                n = deflater.deflate(buffer, 0, buffer.size, flush)
                                compressed.write(buffer, 0, n)
                            } while (!deflater.needsInput() || n == buffer.size)
                        }
                        deflater.finish()
                        while (!deflater.finished()) {
                            val n = deflater.deflate(buffer)
                            compressed.write(buffer, 0, n)
                        }
                    } finally {
                        deflater.end()
                    }
                    val stream = compressed.toByteArray()
                    assertContentEquals(expected, Zlib.decompress(stream, expected.size.toLong()), "strategy=$strategy flush=$flush width=$width")
                    val png = PNG_SIGNATURE + pngHeader(width, rows.size, 8, 0) + pngChunk("IDAT", stream) + pngChunk("IEND", ByteArray(0))
                    val bitmap = ImageKodec.decode(png)
                    for (y in rows.indices) for (x in 0 until width) {
                        kotlin.test.assertEquals(gray(rows[y][x + 1].toInt() and 255), bitmap[x, y])
                    }
                }
            }
        }
    }

    private fun zlibCompress(data: ByteArray, level: Int, strategy: Int = Deflater.DEFAULT_STRATEGY): ByteArray {
        val d = Deflater(level, /* nowrap = */ false)
        d.setStrategy(strategy)
        d.setInput(data)
        d.finish()
        val out = ByteArray(data.size + 1024)
        var len = 0
        while (!d.finished()) {
            len += d.deflate(out, len, out.size - len)
            if (len == out.size) return zlibCompressGrow(data, level, strategy)
        }
        d.end()
        return out.copyOf(len)
    }

    private fun zlibCompressGrow(data: ByteArray, level: Int, strategy: Int): ByteArray {
        val d = Deflater(level, false)
        d.setStrategy(strategy)
        d.setInput(data)
        d.finish()
        val chunks = ArrayList<ByteArray>()
        val buf = ByteArray(64 * 1024)
        while (!d.finished()) {
            val n = d.deflate(buf)
            chunks.add(buf.copyOf(n))
        }
        d.end()
        val total = chunks.sumOf { it.size }
        val out = ByteArray(total)
        var at = 0
        for (c in chunks) { c.copyInto(out, at); at += c.size }
        return out
    }

    private fun roundTrip(data: ByteArray, level: Int, strategy: Int = Deflater.DEFAULT_STRATEGY) {
        val compressed = zlibCompress(data, level, strategy)
        val out = Zlib.decompress(compressed, maximumSize = maxOf(1L, data.size.toLong()))
        assertContentEquals(data, out, "level=$level strategy=$strategy size=${data.size}")
    }

    @Test
    fun storedBlocksLevelZero() {
        roundTrip(ByteArray(100_000) { (it * 31).toByte() }, level = 0)
        roundTrip(ByteArray(1) { 42 }, level = 0)
    }

    @Test
    fun randomDataAllLevels() {
        val rnd = Random(1234)
        val data = ByteArray(200_000).also { rnd.nextBytes(it) }
        for (level in intArrayOf(1, 6, 9)) roundTrip(data, level)
    }

    @Test
    fun repetitiveDataLongBackReferences() {
        // Long matches at many distances, including > 16 KiB back.
        val pattern = "ImageKodec inflates DEFLATE with a fast Huffman table now. ".encodeToByteArray()
        val data = ByteArray(300_000) { pattern[it % pattern.size] }
        for (level in intArrayOf(1, 6, 9)) roundTrip(data, level)
    }

    @Test
    fun textLikeDataFilteredAndHuffmanStrategies() {
        val rnd = Random(99)
        val data = ByteArray(150_000) { (rnd.nextInt(20) + 'a'.code).toByte() }
        roundTrip(data, level = 6, strategy = Deflater.FILTERED)
        roundTrip(data, level = 6, strategy = Deflater.HUFFMAN_ONLY)
    }

    @Test
    fun emptyPayload() {
        roundTrip(ByteArray(0), level = 6)
    }

    @Test
    fun bombGuardAbortsMidStreamNotAfter() {
        // 8 MiB of zeros compresses to a few KiB; a tiny maximumSize must abort
        // the inflate the moment output would exceed it, throwing rather than
        // materialising megabytes first.
        val bomb = zlibCompress(ByteArray(8 * 1024 * 1024), level = 9)
        assertFailsWith<InflateException> {
            Zlib.decompress(bomb, maximumSize = 10_000)
        }
    }
}
