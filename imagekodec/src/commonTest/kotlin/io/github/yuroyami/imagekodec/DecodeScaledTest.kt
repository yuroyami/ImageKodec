package io.github.yuroyami.imagekodec

import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [ImageKodec.decodeScaled] and [ImageKodec.decodeDownscaledTo] give the sizes of [scaled] and
 * [downscaledTo] after a full decode, and reach them through a reduced decode where the format has
 * one (#13).
 */
class DecodeScaledTest {

    /** Smooth shading with fine texture, as a photo has: a reduced IDCT and a box filter differ on it a little. */
    private fun photo(w: Int, h: Int) = KiteBitmap(w, h, IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        val t = (8 * sin(x * 0.9) * sin(y * 0.7)).toInt()
        argb(0xFF, (x * 200 / w + 20 + t).coerceIn(0, 255), (y * 180 / h + 40 - t).coerceIn(0, 255), ((x + y) * 150 / (w + h) + 60).coerceIn(0, 255))
    })

    private val jpeg = ImageKodec.encodeJpeg(photo(401, 299), quality = 92)

    /** The same JPEG with EXIF orientation 6: it displays 299 by 401. */
    private val rotated = jpegWithTiffHeader(jpeg, tiffHeaderWithOffset(8, true) + hex("010012010300010000000600000000000000"))

    /** Mean and largest difference of all channels, which must have the same size. */
    private fun difference(a: KiteBitmap, b: KiteBitmap, label: String): Pair<Double, Int> {
        assertEquals(a.width to a.height, b.width to b.height, label)
        var sum = 0L
        var worst = 0
        for (i in a.argb.indices) for (shift in intArrayOf(0, 8, 16, 24)) {
            val d = abs(((a.argb[i] ushr shift) and 0xFF) - ((b.argb[i] ushr shift) and 0xFF))
            sum += d
            if (d > worst) worst = d
        }
        return sum.toDouble() / (a.argb.size * 4) to worst
    }

    @Test
    fun aJpegComesOutAtTheSizeOfAFullDecodeThenScaledAndNearlyItsPixels() {
        for (applyOrientation in listOf(false, true)) for (file in listOf(jpeg, rotated)) {
            for ((w, h) in listOf(200 to 200, 100 to 100, 50 to 80, 30 to 30, 401 to 10, 1000 to 1000)) {
                val label = "${w}x$h oriented=$applyOrientation exif=${file === rotated}"
                val expected = ImageKodec.decode(file, applyOrientation).scaled(w, h)
                val actual = ImageKodec.decodeScaled(file, w, h, applyOrientation)
                val (mean, worst) = difference(expected, actual, label)
                // The reduced inverse DCT is not a box filter, and its chroma is not upsampled the way the
                // full decode's is: measured 1.3 to 2.5 levels on average and 9 at worst, about what
                // libjpeg-turbo's scaled decode differs by.
                assertTrue(mean <= 3.0 && worst <= 12, "$label: mean $mean, worst $worst")
            }
        }
    }

    @Test
    fun theLargestReductionThatStillCoversTheTargetIsTheOneUsed() {
        // 401 by 299 to fit 50 by 50 is 50 by 37. An eighth is 51 by 38, which covers it, so the result is
        // exactly that reduced decode filtered once more. 52 by 52 needs 52 by 38 and so a quarter.
        assertContentEquals(ImageKodec.decodeReduced(jpeg, 8).downscaledTo(50, 37).argb, ImageKodec.decodeScaled(jpeg, 50, 50).argb)
        assertContentEquals(ImageKodec.decodeReduced(jpeg, 4).downscaledTo(52, 38).argb, ImageKodec.decodeScaled(jpeg, 52, 52).argb)
        // An exact reduced size needs no filter at all.
        assertContentEquals(ImageKodec.decodeReduced(jpeg, 2).argb, ImageKodec.decodeDownscaledTo(jpeg, 201, 150).argb)
        // With the orientation applied, the target is compared with the sides it lands on.
        val oriented = ImageKodec.decodeReduced(rotated, 8).oriented(Orientation.Rotate90)
        assertContentEquals(oriented.downscaledTo(37, 50).argb, ImageKodec.decodeScaled(rotated, 50, 50, applyOrientation = true).argb)
    }

    @Test
    fun anImageThatAlreadyFitsDecodesInFull() {
        assertContentEquals(ImageKodec.decode(jpeg).argb, ImageKodec.decodeScaled(jpeg, 401, 299).argb)
        assertContentEquals(ImageKodec.decode(jpeg).argb, ImageKodec.decodeDownscaledTo(jpeg, 401, 299).argb)
    }

    @Test
    fun jpeg2000DropsTheWaveletLevelsThatStillCoverTheTarget() {
        // A wavelet's low band is not a box average, so on this 32 by 24 test card a reduced level
        // differs from the filtered full decode by more than a JPEG does. The reduced levels themselves
        // match OpenJPEG's reduce option, so what is checked here is the size and the level chosen.
        val jp2 = hex(JP2)
        for ((box, reduction) in listOf(Pair(16 to 16, 2), Pair(8 to 8, 4), Pair(5 to 3, 8), Pair(9 to 9, 2), Pair(32 to 24, 1))) {
            val (w, h) = box
            val expected = ImageKodec.decode(jp2).scaled(w, h)
            val actual = ImageKodec.decodeScaled(jp2, w, h)
            assertEquals(expected.width to expected.height, actual.width to actual.height, "jp2 ${w}x$h")
            assertContentEquals(ImageKodec.decodeReduced(jp2, reduction).downscaledTo(actual.width, actual.height).argb, actual.argb, "jp2 ${w}x$h")
        }
    }

    @Test
    fun aBmpHoldingAJpegReducesAsTheJpegDoes() {
        val bmp = embeddedBmp(4, jpeg)
        assertContentEquals(ImageKodec.decodeScaled(jpeg, 60, 60).argb, ImageKodec.decodeScaled(bmp, 60, 60).argb)
    }

    @Test
    fun otherFormatsDecodeInFullAndFilterOnce() {
        val source = photo(97, 61)
        for (file in listOf(ImageKodec.encodePng(source), ImageKodec.encodeBmp(source), ImageKodec.encodeGif(source))) {
            assertContentEquals(ImageKodec.decode(file).scaled(40, 40).argb, ImageKodec.decodeScaled(file, 40, 40).argb)
            assertContentEquals(ImageKodec.decode(file).downscaledTo(50, 20).argb, ImageKodec.decodeDownscaledTo(file, 50, 20).argb)
        }
        val png = ImageKodec.encodePng(source)
        assertContentEquals(ImageKodec.decode(png).argb, ImageKodec.decodeScaled(png, 500, 500).argb)
    }

    @Test
    fun badSizesAreRefused() {
        for ((w, h) in listOf(0 to 10, 10 to 0, -1 to -1)) {
            assertFailsWith<IllegalArgumentException> { ImageKodec.decodeScaled(jpeg, w, h) }
            assertFailsWith<IllegalArgumentException> { ImageKodec.decodeDownscaledTo(jpeg, w, h) }
        }
        // A size past the image is an upscale, which downscaledTo refuses, as is a side that only the
        // orientation would make fit.
        assertFailsWith<IllegalArgumentException> { ImageKodec.decodeDownscaledTo(jpeg, 402, 299) }
        assertFailsWith<IllegalArgumentException> { ImageKodec.decodeDownscaledTo(rotated, 300, 10, applyOrientation = true) }
        assertFailsWith<IllegalArgumentException> { ImageKodec.decodeDownscaledTo(rotated, 10, 300, applyOrientation = false) }
        assertEquals(299 to 401, ImageKodec.decodeDownscaledTo(rotated, 299, 401, applyOrientation = true).let { it.width to it.height })
        assertFailsWith<ImageDecodeException> { ImageKodec.decodeScaled(jpeg.copyOf(100), 10, 10) }
        assertFailsWith<ImageDecodeException> { ImageKodec.decodeScaled(ByteArray(16), 10, 10) }
    }
}
