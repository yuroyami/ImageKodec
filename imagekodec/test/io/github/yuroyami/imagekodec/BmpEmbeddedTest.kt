package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A BMP whose BITMAPINFOHEADER declares BI_JPEG (4) or BI_PNG (5), with [payload] as the pixel array
 * at [offset]. The header gives [width] by [height] and a bit count of 0, as Windows writes it, and
 * biSizeImage is [size].
 */
internal fun embeddedBmp(
    compression: Int,
    payload: ByteArray,
    width: Int = 1,
    height: Int = 1,
    size: Int = payload.size,
    offset: Int = 54,
): ByteArray {
    val out = ArrayList<Byte>()
    fun u8(v: Int) = out.add((v and 0xFF).toByte())
    fun u16(v: Int) { u8(v); u8(v ushr 8) }
    fun u32(v: Int) { u16(v); u16(v ushr 16) }
    u8('B'.code); u8('M'.code)
    u32(54 + payload.size); u32(0); u32(offset)
    u32(40); u32(width); u32(height); u16(1); u16(0)
    u32(compression); u32(size); u32(0); u32(0); u32(0); u32(0)
    payload.forEach { out.add(it) }
    return out.toByteArray()
}

/** BMP files that carry a whole JPEG or PNG as their pixel array, as printer drivers write them (#17). */
class BmpEmbeddedTest {

    private fun sample(w: Int, h: Int, alpha: Boolean) = KiteBitmap(w, h, IntArray(w * h) { i ->
        argb(if (alpha && i % 3 == 0) 0x80 else 0xFF, i * 7, i * 13, i * 29)
    })

    private val png = ImageKodec.encodePng(sample(9, 5, alpha = true))
    private val opaquePng = ImageKodec.encodePng(sample(6, 7, alpha = false))
    private val jpeg = ImageKodec.encodeJpeg(sample(17, 11, alpha = false), quality = 80)

    private fun assertSameImage(expected: KiteBitmap, actual: KiteBitmap, label: String) {
        assertEquals(expected.width, actual.width, "$label width")
        assertEquals(expected.height, actual.height, "$label height")
        assertContentEquals(expected.argb, actual.argb, label)
    }

    @Test
    fun anEmbeddedJpegOrPngDecodesAsItself() {
        for ((compression, inner) in listOf(5 to png, 5 to opaquePng, 4 to jpeg)) {
            // The outer header's size is not the inner one: the inner file's is what decodes.
            val bmp = embeddedBmp(compression, inner, width = 3, height = 2)
            assertSameImage(ImageKodec.decode(inner), ImageKodec.decode(bmp), "compression $compression")
            assertSameImage(ImageKodec.decode(inner), ImageKodec.decodeAnimation(bmp).frames.single().bitmap, "animation")
            val info = ImageKodec.probe(bmp)
            val innerInfo = ImageKodec.probe(inner)
            assertEquals(ImageFormat.BMP, info.format)
            assertEquals(innerInfo.width, info.width)
            assertEquals(innerInfo.height, info.height)
            assertEquals(innerInfo.bitDepth, info.bitDepth)
            assertEquals(innerInfo.hasAlpha, info.hasAlpha)
            assertTrue(info.isDecodable, info.unsupportedReason)
        }
    }

    @Test
    fun theSizeFieldIsNotTrusted() {
        // ImageMagick reads from bfOffBits to the end of the file, whatever biSizeImage says, and both
        // decoders stop at their own end marker, so a missing, short or long size still decodes.
        for (size in listOf(0, png.size - 10, png.size + 1000)) {
            assertSameImage(ImageKodec.decode(png), ImageKodec.decode(embeddedBmp(5, png, size = size)), "size $size")
        }
    }

    @Test
    fun anEmbeddedJpegReducesInsideItsInverseDct() {
        val bmp = embeddedBmp(4, jpeg)
        for (r in listOf(1, 2, 4, 8)) {
            assertSameImage(ImageKodec.decodeReduced(jpeg, r), ImageKodec.decodeReduced(bmp, r), "1/$r")
        }
        assertSameImage(ImageKodec.decodeReduced(png, 2), ImageKodec.decodeReduced(embeddedBmp(5, png), 2), "png at 1/2")
    }

    @Test
    fun theEmbeddedJpegsOrientationApplies() {
        val rotated = jpegWithTiffHeader(jpeg, tiffHeaderWithOffset(8, true) + hex("010012010300010000000600000000000000"))
        val bmp = embeddedBmp(4, rotated)
        assertEquals(Orientation.Rotate90, ImageKodec.probe(bmp).orientation)
        assertSameImage(ImageKodec.decode(rotated, applyOrientation = true), ImageKodec.decode(bmp, applyOrientation = true), "oriented")
    }

    @Test
    fun aPayloadOfAnotherFormatIsRefusedAndNothingNests() {
        val bmp = ImageKodec.encodeBmp(sample(4, 4, alpha = false))
        val cases = listOf(
            "PNG declared as JPEG" to embeddedBmp(4, png),
            "JPEG declared as PNG" to embeddedBmp(5, jpeg),
            "a BMP inside a BMP" to embeddedBmp(5, bmp),
            "nesting" to embeddedBmp(5, embeddedBmp(5, png)),
            "no payload" to embeddedBmp(5, ByteArray(0)),
            "offset inside the header" to embeddedBmp(5, png, offset = 40),
            "offset past the end" to embeddedBmp(5, png, offset = 54 + png.size),
        )
        for ((label, file) in cases) {
            assertFailsWith<ImageDecodeException>(label) { ImageKodec.decode(file) }
            assertFailsWith<ImageDecodeException>(label) { ImageKodec.decodeReduced(file, 2) }
            assertFailsWith<ImageDecodeException>(label) { ImageKodec.probe(file) }
        }
        val message = assertFailsWith<ImageDecodeException> { ImageKodec.decode(embeddedBmp(5, bmp)) }.message!!
        assertTrue("BI_PNG" in message && "BMP" in message, message)
    }
}
