package io.github.yuroyami.kiteimagecodec

import io.github.yuroyami.kiteimagecodec.codec.JpegDecoder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A reduced decode: a JPEG shrinks inside its inverse DCT, and other formats average blocks
 * of the full decode. `JpegReducedOracleTest` compares the JPEG path against libjpeg's
 * `djpeg -scale`.
 */
class JpegReducedTest {

    /** A gradient with a soft band across it, [w] by [h]: smooth, as a photo or a scan is. */
    private fun sample(w: Int, h: Int): KiteBitmap = KiteBitmap(w, h, IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        val band = if (abs(x - y) < w / 6) 60 else 0
        argb(0xFF, x * 200 / w + band / 2, y * 200 / h + 20, (x + y) * 120 / (w + h) + band)
    })

    @Test
    fun theReducedTablesAreTheDerivedValues() {
        // C(k) cos((2p+1) k pi / 2n) in 1/8192ths. Every target must derive the same integers.
        assertContentEquals(
            intArrayOf(5793, 5793, 5793, 5793, 7568, 3135, -3135, -7568, 5793, -5793, -5793, 5793, 3135, -7568, 7568, -3135),
            JpegDecoder.REDUCED_4,
        )
        assertContentEquals(intArrayOf(5793, 5793, 5793, -5793), JpegDecoder.REDUCED_2)
    }

    @Test
    fun aReducedDecodeHasTheRoundedUpSize() {
        for (quality in listOf(85, 95)) {
            val jpeg = KiteImageCodec.encodeJpeg(sample(37, 29), quality)
            for (r in listOf(1, 2, 4, 8)) {
                val bitmap = KiteImageCodec.decodeReduced(jpeg, r)
                assertEquals((37 + r - 1) / r, bitmap.width, "quality $quality, reduced by $r")
                assertEquals((29 + r - 1) / r, bitmap.height, "quality $quality, reduced by $r")
            }
        }
    }

    @Test
    fun aReducedDecodeStaysCloseToTheAverageOfTheFullDecode() {
        // Quality 85 subsamples the chroma 4:2:0 and quality 95 keeps it 4:4:4.
        for (quality in listOf(85, 95)) {
            val jpeg = KiteImageCodec.encodeJpeg(sample(64, 48), quality)
            val full = KiteImageCodec.decode(jpeg)
            for (r in listOf(2, 4, 8)) {
                val reduced = KiteImageCodec.decodeReduced(jpeg, r)
                val average = full.reducedBy(r)
                var total = 0L
                var worst = 0
                for (i in reduced.argb.indices) {
                    for (shift in intArrayOf(16, 8, 0)) {
                        val d = abs(((reduced.argb[i] shr shift) and 0xFF) - ((average.argb[i] shr shift) and 0xFF))
                        total += d
                        worst = maxOf(worst, d)
                    }
                }
                val mean = total.toDouble() / (reduced.argb.size * 3)
                assertTrue(mean < 2.0 && worst <= 24, "quality $quality, reduced by $r: mean $mean, worst $worst")
            }
        }
    }

    @Test
    fun aFlatGreyReducesToItsOwnValue() {
        // At quality 100 every quantiser is 1, so a flat block's DC is exactly 8 (v - 128). Each
        // reduced IDCT must round it back to v, including below 128, where truncation is one low.
        for (v in intArrayOf(0, 1, 37, 100, 127, 128, 129, 200, 254, 255)) {
            val jpeg = KiteImageCodec.encodeJpeg(KiteBitmap(24, 16, IntArray(24 * 16) { argb(0xFF, v, v, v) }), quality = 100)
            for (r in listOf(1, 2, 4, 8)) {
                val bitmap = KiteImageCodec.decodeReduced(jpeg, r)
                for (p in bitmap.argb) assertEquals(argb(0xFF, v, v, v), p, "grey $v, reduced by $r")
            }
        }
    }

    @Test
    fun theLastRowAndColumnOfAnOddSizeSurvive() {
        // 37 by 29 is no multiple of 8, so the last output row and column hold a part block. Paint
        // only the last row and column black: each must come out clearly darker than its neighbour.
        // At an eighth the part block averages with the encoder's padding, as in libjpeg, to 128.
        val w = 37
        val h = 29
        val bitmap = KiteBitmap(w, h, IntArray(w * h) { i ->
            if (i % w == w - 1 || i / w == h - 1) argb(0xFF, 0, 0, 0) else argb(0xFF, 255, 255, 255)
        })
        val jpeg = KiteImageCodec.encodeJpeg(bitmap, quality = 100)
        for (r in listOf(2, 4, 8)) {
            val out = KiteImageCodec.decodeReduced(jpeg, r)
            fun grey(x: Int, y: Int) = (out.argb[y * out.width + x] shr 8) and 0xFF
            val last = out.height - 1
            assertTrue(grey(0, last) + 64 <= grey(0, last - 1), "reduced by $r: last row ${grey(0, last)}, above ${grey(0, last - 1)}")
            val right = out.width - 1
            assertTrue(grey(right, 0) + 64 <= grey(right - 1, 0), "reduced by $r: last column ${grey(right, 0)}, left ${grey(right - 1, 0)}")
        }
    }

    @Test
    fun aBlockAverageWeighsColourByAlphaAndRounds() {
        // Opaque 10 and 11 average to 10.5, which rounds up. A clear pixel adds no colour, only coverage.
        val bitmap = KiteBitmap(3, 2, intArrayOf(
            argb(0xFF, 10, 20, 30), argb(0x00, 200, 200, 200), argb(0x80, 100, 50, 0),
            argb(0xFF, 11, 21, 31), argb(0x00, 200, 200, 200), argb(0x80, 100, 50, 0),
        ))
        val out = bitmap.reducedBy(2)
        assertEquals(2, out.width)
        assertEquals(1, out.height)
        assertEquals(argb(0x80, 11, 21, 31), out.argb[0])
        assertEquals(argb(0x80, 100, 50, 0), out.argb[1])
    }

    @Test
    fun otherFormatsAverageTheFullDecode() {
        val png = KiteImageCodec.encodePng(sample(9, 7))
        val reduced = KiteImageCodec.decodeReduced(png, 4)
        assertEquals(3, reduced.width)
        assertEquals(2, reduced.height)
        assertContentEquals(KiteImageCodec.decode(png).reducedBy(4).argb, reduced.argb)
        assertContentEquals(KiteImageCodec.decode(png).argb, KiteImageCodec.decodeReduced(png, 1).argb)
    }

    @Test
    fun onlyPowersOfTwoUpToEightReduce() {
        val png = KiteImageCodec.encodePng(sample(9, 7))
        for (r in listOf(0, 3, 16)) assertFailsWith<IllegalArgumentException> { KiteImageCodec.decodeReduced(png, r) }
    }
}
