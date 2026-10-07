package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.JpegCmykFixtures.BLOCKS
import io.github.yuroyami.imagekodec.codec.JpegDecoder
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [ImageKodec.decodeJpegComponents]: the samples a JPEG stores, before the color conversion. */
class JpegComponentsTest {

    /** A gradient with a soft band across it: smooth, as a photo is. */
    private fun sample(w: Int, h: Int): KiteBitmap = KiteBitmap(w, h, IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        val band = if (abs(x - y) < w / 6) 60 else 0
        argb(0xFF, x * 200 / w + band / 2, y * 200 / h + 20, (x + y) * 120 / (w + h) + band)
    })

    /**
     * Every kind of JPEG the suites hold: ffmpeg's baseline and ImageIO's progressive vectors,
     * libjpeg's CMYK and YCCK, cjpeg's unequal sampling, this library's own 4:2:0 and 4:4:4, and
     * the hand-built restart-interval and table-reuse files.
     */
    private fun corpus(): List<Pair<String, ByteArray>> =
        JpegVectors.all.map { (name, hex) -> name to hex(hex) } +
            listOf(
                "CMYK, transform 0" to JpegCmykFixtures.blocksAdobeCmyk,
                "YCCK, transform 2" to JpegCmykFixtures.blocksAdobeYcck,
                "CMYK, no marker" to JpegCmykFixtures.blocksBare,
                "CMYK split, transform 0" to JpegCmykFixtures.splitAdobeCmyk,
                "CMYK split, no marker" to JpegCmykFixtures.splitBare,
                "encoder 4:2:0" to ImageKodec.encodeJpeg(sample(37, 29), 85),
                "encoder 4:4:4" to ImageKodec.encodeJpeg(sample(37, 29), 95),
                "restart intervals" to restartIntervalJpeg(),
                "table reuse" to quantizationJpeg(progressive = false),
                "table reuse, progressive" to quantizationJpeg(progressive = true),
            ) +
            JpegSamplingFixtures.all.map { (name, bytes) -> "luma $name" to bytes }

    @Test
    fun theDecodersColorConversionOfTheComponentsGivesTheReducedPixels() {
        for ((name, bytes) in corpus()) {
            for (r in listOf(1, 2, 4, 8)) {
                val components = ImageKodec.decodeJpegComponents(bytes, r)
                val internal = JpegDecoder.decodeComponents(bytes, r.countTrailingZeroBits())
                assertContentEquals(internal.components.samples, components.samples, "$name at 1/$r")
                val pixels = ImageKodec.decodeReduced(bytes, r)
                assertEquals(pixels.width, components.width, "$name at 1/$r")
                assertEquals(pixels.height, components.height, "$name at 1/$r")
                assertContentEquals(pixels.argb, JpegDecoder.toBitmap(components, internal.model).argb, "$name at 1/$r")
            }
        }
    }

    @Test
    fun cmykSamplesComeBackAsStored() {
        // libjpeg wrote flat 8 by 8 blocks at quality 100 with no subsampling, so every sample is
        // the stored value within a level, and stays so at every reduction: no reduced pixel
        // straddles two blocks.
        for ((jpeg, transform) in listOf(JpegCmykFixtures.blocksAdobeCmyk to 0, JpegCmykFixtures.blocksBare to -1)) {
            for (r in listOf(1, 2, 4, 8)) {
                val c = ImageKodec.decodeJpegComponents(jpeg, r)
                assertEquals(4, c.componentCount)
                assertEquals(transform, c.adobeTransform)
                assertEquals(32 / r, c.width)
                assertEquals(16 / r, c.height)
                for (y in 0 until c.height) for (x in 0 until c.width) {
                    val block = BLOCKS[(y * r / 8) * 4 + x * r / 8]
                    for (k in 0 until 4) {
                        assertTrue(abs(c[x, y, k] - block[k]) <= 1, "transform $transform at 1/$r, ($x, $y) component $k: ${c[x, y, k]}, stored ${block[k]}")
                    }
                }
            }
        }
    }

    @Test
    fun ycckSamplesAreTheStoredYccAndK() {
        // libjpeg's cmyk_ycck_convert complements C, M and Y to R, G and B, takes them to YCbCr
        // with the JFIF matrix, and passes K through.
        val c = ImageKodec.decodeJpegComponents(JpegCmykFixtures.blocksAdobeYcck)
        assertEquals(2, c.adobeTransform)
        for (y in 0 until 16) for (x in 0 until 32) {
            val block = BLOCKS[(y / 8) * 4 + x / 8]
            val r = 255 - block[0]
            val g = 255 - block[1]
            val b = 255 - block[2]
            val expected = intArrayOf(
                (0.299 * r + 0.587 * g + 0.114 * b).roundToInt(),
                (-0.168736 * r - 0.331264 * g + 0.5 * b + 128).roundToInt().coerceIn(0, 255),
                (0.5 * r - 0.418688 * g - 0.081312 * b + 128).roundToInt().coerceIn(0, 255),
                block[3],
            )
            for (k in 0 until 4) {
                assertTrue(abs(c[x, y, k] - expected[k]) <= 1, "($x, $y) component $k: ${c[x, y, k]}, expected ${expected[k]}")
            }
        }
    }

    @Test
    fun grayAndColorFilesKeepTheirComponentCount() {
        val gray = ImageKodec.decodeJpegComponents(hex(JpegVectors.pgray))
        assertEquals(1, gray.componentCount)
        assertEquals(-1, gray.adobeTransform)
        val color = ImageKodec.decodeJpegComponents(hex(JpegVectors.j420))
        assertEquals(3, color.componentCount)
        assertEquals(color.width * color.height * 3, color.samples.size)
    }

    @Test
    fun whatDecodeReducedRefusesIsRefusedTheSameWay() {
        val png = ImageKodec.encodePng(sample(9, 7))
        assertFailsWith<ImageDecodeException> { ImageKodec.decodeJpegComponents(png) }
        val jpeg = hex(JpegVectors.j444)
        for (r in listOf(0, 3, 16)) assertFailsWith<IllegalArgumentException> { ImageKodec.decodeJpegComponents(jpeg, r) }
        // A 12-bit frame: the precision byte follows the frame header's marker and length.
        val sof = (0 until jpeg.size - 1).first { jpeg[it] == 0xFF.toByte() && jpeg[it + 1] == 0xC0.toByte() }
        val twelveBit = jpeg.copyOf().also { it[sof + 4] = 12 }
        val refused = assertFailsWith<UnsupportedImageException> { ImageKodec.decodeReduced(twelveBit, 2) }
        val same = assertFailsWith<UnsupportedImageException> { ImageKodec.decodeJpegComponents(twelveBit, 2) }
        assertEquals(refused.message, same.message)
        val truncated = jpeg.copyOf(40)
        assertEquals(
            assertFailsWith<ImageDecodeException> { ImageKodec.decodeReduced(truncated, 2) }.message,
            assertFailsWith<ImageDecodeException> { ImageKodec.decodeJpegComponents(truncated, 2) }.message,
        )
    }

    @Test
    fun theConstructorChecksTheLayout() {
        assertEquals(7, JpegComponents(1, 1, 3, byteArrayOf(1, 7, 3), -1)[0, 0, 1])
        assertFailsWith<IllegalArgumentException> { JpegComponents(0, 1, 1, ByteArray(0), -1) }
        assertFailsWith<IllegalArgumentException> { JpegComponents(1, 1, 2, ByteArray(2), -1) }
        assertFailsWith<IllegalArgumentException> { JpegComponents(2, 2, 3, ByteArray(11), -1) }
        assertFailsWith<IllegalArgumentException> { JpegComponents(1, 1, 1, ByteArray(1), 256) }
        // 65536 * 65536 * 4 wraps an Int to 0, so an empty array must not pass.
        assertFailsWith<IllegalArgumentException> { JpegComponents(65536, 65536, 4, ByteArray(0), -1) }
        val c = JpegComponents(2, 1, 1, byteArrayOf(5, -1), 0)
        assertEquals(255, c[1, 0, 0])
        assertFailsWith<IllegalArgumentException> { c[2, 0, 0] }
        assertFailsWith<IllegalArgumentException> { c[0, 0, 1] }
    }
}
