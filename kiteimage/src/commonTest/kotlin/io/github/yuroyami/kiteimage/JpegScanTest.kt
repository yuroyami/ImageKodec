package io.github.yuroyami.kiteimage

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
        val jpeg = KiteImage.encodeJpeg(source, quality = 90)
        var sos = 0
        while (!(jpeg[sos] == 0xFF.toByte() && jpeg[sos + 1] == 0xDA.toByte())) sos++
        val dataStart = sos + 2 + (((jpeg[sos + 2].toInt() and 0xFF) shl 8) or (jpeg[sos + 3].toInt() and 0xFF))
        val cut = dataStart + (jpeg.size - 2 - dataStart) / 2
        val truncated = jpeg.copyOf(cut) + byteArrayOf(0xFF.toByte(), 0xD9.toByte())

        val bitmap = KiteImage.decode(truncated)
        assertEquals(side, bitmap.height)
        for (x in 0 until side) assertEquals(gray, bitmap[x, side - 1], "last row, x=$x")
        assertTrue((0 until side).any { bitmap[it, 0] != gray }, "the first row was in the file and should not be flat gray")
    }

    @Test
    fun aShortRestartIntervalResyncsAtTheNextMarker() {
        // A gray 64 by 8 image: eight blocks in four restart intervals of two. The DC table has two
        // one-bit codes, "0" for category 0 and "1" for category 6, and the AC table only end of block.
        //   full interval: block A = "1" + 111111 + "0" (DC +63, then EOB), block B = "0" + "0" (no change)
        //   short interval: block A only. Block B is not in the file.
        // Block B of a short interval must stay gray. Decoding zero bits instead carries the DC over
        // and shows +63 there. Every interval after it must still decode.
        val full = byteArrayOf(0xFE.toByte(), 0x3F)
        val short = byteArrayOf(0xFE.toByte())
        fun rst(n: Int) = byteArrayOf(0xFF.toByte(), (0xD0 + n).toByte())
        val entropy = full + rst(0) + short + rst(1) + full + rst(2) + full
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            jpegSegment(0xDB, byteArrayOf(0) + ByteArray(64) { 1 }) +
            jpegSegment(0xC0, byteArrayOf(8, 0, 8, 0, 64, 1, 1, 0x11, 0)) +
            jpegSegment(0xC4, byteArrayOf(0x00, 2) + ByteArray(15) + byteArrayOf(0, 6)) +
            jpegSegment(0xC4, byteArrayOf(0x10, 1) + ByteArray(15) + byteArrayOf(0)) +
            jpegSegment(0xDD, byteArrayOf(0, 2)) +
            jpegSegment(0xDA, byteArrayOf(1, 1, 0x00, 0, 63, 0)) +
            entropy + byteArrayOf(0xFF.toByte(), 0xD9.toByte())

        val bitmap = KiteImage.decode(jpeg)
        fun level(block: Int) = bitmap[block * 8 + 4, 4] and 0xFF
        for (block in intArrayOf(0, 1, 2, 4, 5, 6, 7)) assertTrue(level(block) > 130, "block $block is in the file: ${level(block)}")
        assertEquals(128, level(3), "block 3 is the missing half of the short interval")
    }

    @Test
    fun aFrameWithTooManyScansIsRejected() {
        // 512 scans are allowed. A real progressive file uses ten to twenty.
        assertEquals(8, KiteImage.decode(emptyScanJpeg(side = 8, scans = 512)).width)
        val e = assertFailsWith<ImageDecodeException> { KiteImage.decode(emptyScanJpeg(side = 8, scans = 513)) }
        assertTrue(e.message!!.contains("scans"), "message should name the problem: ${e.message}")
    }
}
