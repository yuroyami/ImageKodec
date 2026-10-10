package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A scan walks every block of its image, so the decode time is the number of scans times the image
 * area. The number of scans and the bytes a scan is allowed to leave unread have to follow the file.
 */
class JpegScanTest {

    private val gray = 0xFF808080.toInt()

    @Test
    fun aScanThatRunsOutOfDataLeavesTheRestOfTheImageGray() {
        // Cut the entropy data of a colourful picture in half. The blocks after the cut are not in the
        // file, so they must stay at mid-gray, not decode zero bits into noise.
        val side = 64
        val source = KiteBitmap(side, side, IntArray(side * side) { i ->
            argb(0xFF, (i % side) * 4, (i / side) * 4, ((i % side) xor (i / side)) * 4)
        })
        val jpeg = ImageKodec.encodeJpeg(source, quality = 90)
        var sos = 0
        while (!(jpeg[sos] == 0xFF.toByte() && jpeg[sos + 1] == 0xDA.toByte())) sos++
        val dataStart = sos + 2 + (((jpeg[sos + 2].toInt() and 0xFF) shl 8) or (jpeg[sos + 3].toInt() and 0xFF))
        val cut = dataStart + (jpeg.size - 2 - dataStart) / 2
        val truncated = jpeg.copyOf(cut) + byteArrayOf(0xFF.toByte(), 0xD9.toByte())

        val bitmap = ImageKodec.decode(truncated)
        assertEquals(side, bitmap.height)
        for (x in 0 until side) assertEquals(gray, bitmap[x, side - 1], "last row, x=$x")
        assertTrue((0 until side).any { bitmap[it, 0] != gray }, "the first row was in the file and should not be flat gray")
    }

    @Test
    fun aShortRestartIntervalResyncsAtTheNextMarker() {
        // See restartIntervalJpeg: eight blocks in four intervals, and the second interval is short.
        // Block B of a short interval must stay gray. Decoding zero bits instead carries the DC over
        // and shows +63 there. Every interval after it must still decode.
        val jpeg = restartIntervalJpeg()
        val bitmap = ImageKodec.decode(jpeg)
        fun level(block: Int) = bitmap[block * 8 + 4, 4] and 0xFF
        for (block in intArrayOf(0, 1, 2, 4, 5, 6, 7)) assertTrue(level(block) > 130, "block $block is in the file: ${level(block)}")
        assertEquals(128, level(3), "block 3 is the missing half of the short interval")
    }

    @Test
    fun aFrameWithTooManyScansIsRejected() {
        // 512 scans are allowed. A real progressive file uses ten to twenty.
        assertEquals(8, ImageKodec.decode(emptyScanJpeg(side = 8, scans = 512)).width)
        val e = assertFailsWith<ImageDecodeException> { ImageKodec.decode(emptyScanJpeg(side = 8, scans = 513)) }
        assertTrue(e.message!!.contains("scans"), "message should name the problem: ${e.message}")
    }
}
