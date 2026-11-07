package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.Jbig2Decoder
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** A halftone region's work is bounded by its region, not by its grid (#128). */
class Jbig2HalftoneTest {

    private fun be16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

    /** A segment numbered [num] of [type] that refers to [refs], on page 1. */
    private fun seg(num: Int, type: Int, refs: List<Int>, data: ByteArray): ByteArray =
        beBytes(num) + byteArrayOf(type.toByte(), (refs.size shl 5).toByte()) +
            ByteArray(refs.size) { refs[it].toByte() } + byteArrayOf(1) + beBytes(data.size) + data

    /** A 100 by 100 page, a dictionary of one [pattern] by [pattern] pattern, and a [grid] by [grid] halftone. */
    private fun stream(grid: Int, pattern: Int, step: Int): ByteArray {
        val page = beBytes(100) + beBytes(100) + beBytes(0) + beBytes(0) + byteArrayOf(0) + be16(0)
        val dict = byteArrayOf(0, pattern.toByte(), pattern.toByte()) + beBytes(0) + ByteArray(4)
        val halftone = beBytes(100) + beBytes(100) + beBytes(0) + beBytes(0) + byteArrayOf(0, 0) +
            beBytes(grid) + beBytes(grid) + beBytes(0) + beBytes(0) + be16(step * 256) + be16(0) + ByteArray(4)
        return seg(0, 48, emptyList(), page) + seg(1, 16, emptyList(), dict) + seg(2, 22, listOf(1), halftone)
    }

    @Test
    fun aGridThatTilesItsRegionDecodes() {
        assertNotNull(Jbig2Decoder.decodeChecked(stream(grid = 10, pattern = 10, step = 10), null, 100, 100))
    }

    @Test
    fun aGridThatCoversItsRegionManyTimesOverIsRefusedAtOnce() {
        val start = TimeSource.Monotonic.markNow()
        // Every cell at the origin: 2^26 cells of one pattern, or 256 of 255 by 255.
        for ((grid, pattern) in listOf(8192 to 1, 8192 to 255, 16 to 255)) {
            val e = assertFailsWith<ImageDecodeException>("$grid by $grid of $pattern") {
                Jbig2Decoder.decodeChecked(stream(grid, pattern, step = 0), null, 100, 100)
            }
            assertTrue(e.message!!.contains("halftone grid"), "message: ${e.message}")
        }
        assertTrue(start.elapsedNow().inWholeSeconds < 5, "the refusals took ${start.elapsedNow()}")
    }
}
