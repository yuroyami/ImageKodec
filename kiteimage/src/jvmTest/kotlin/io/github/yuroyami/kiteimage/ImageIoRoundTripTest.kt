package io.github.yuroyami.kiteimage

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Round-trip against a real, unrelated encoder: ImageIO writes the file, KiteImage
 * decodes it, and every pixel must equal the ARGB values the image was built from.
 * PNG is lossless, so any mismatch is a decoder bug (ImageIO's PNG writer also
 * picks row filters adaptively: this exercises filter paths on organic data, not
 * just the hand-filtered vectors).
 *
 * Grayscale BufferedImage types are deliberately absent: TYPE_BYTE_GRAY routes
 * through Java2D color management and stops being a pixel-identity comparison.
 * The commonTest vectors own the gray paths.
 */
class ImageIoRoundTripTest {

    private fun encode(img: BufferedImage, format: String): ByteArray {
        val out = ByteArrayOutputStream()
        check(ImageIO.write(img, format, out)) { "ImageIO has no $format writer" }
        return out.toByteArray()
    }

    private fun assertDecodesIdentically(img: BufferedImage, format: String) {
        val decoded = KiteImage.decode(encode(img, format))
        assertEquals(img.width, decoded.width)
        assertEquals(img.height, decoded.height)
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                val expected = img.getRGB(x, y)
                val actual = decoded[x, y]
                assertEquals(
                    expected, actual,
                    "$format ${img.width}x${img.height} pixel ($x,$y): " +
                        "expected ${expected.toUInt().toString(16)}, got ${actual.toUInt().toString(16)}",
                )
            }
        }
    }

    private fun randomArgb(w: Int, h: Int, seed: Long, opaque: Boolean): BufferedImage {
        val rng = Random(seed)
        val type = if (opaque) BufferedImage.TYPE_INT_RGB else BufferedImage.TYPE_INT_ARGB
        val img = BufferedImage(w, h, type)
        for (y in 0 until h) for (x in 0 until w) {
            val a = if (opaque) 0xFF else rng.nextInt(256)
            img.setRGB(x, y, (a shl 24) or rng.nextInt(1 shl 24))
        }
        return img
    }

    @Test
    fun pngRgbaRandomSizes() {
        // Odd widths matter: they hit sub-byte row boundaries and filter edge cases.
        for ((w, h) in listOf(1 to 1, 7 to 3, 33 to 1, 64 to 64, 2 to 129)) {
            assertDecodesIdentically(randomArgb(w, h, seed = w * 1000L + h, opaque = false), "png")
        }
    }

    @Test
    fun pngOpaqueRgb() {
        for ((w, h) in listOf(1 to 1, 5 to 5, 31 to 17)) {
            assertDecodesIdentically(randomArgb(w, h, seed = w * 7L + h, opaque = true), "png")
        }
    }

    @Test
    fun pngIndexedPalette() {
        // TYPE_BYTE_INDEXED uses a fixed 256-color sRGB palette: colorspace-neutral.
        val img = BufferedImage(16, 16, BufferedImage.TYPE_BYTE_INDEXED)
        val rng = Random(42)
        for (y in 0 until 16) for (x in 0 until 16) {
            img.setRGB(x, y, 0xFF shl 24 or rng.nextInt(1 shl 24))
        }
        assertDecodesIdentically(img, "png")
    }

    @Test
    fun pngOneBitBlackAndWhite() {
        val img = BufferedImage(19, 7, BufferedImage.TYPE_BYTE_BINARY)
        for (y in 0 until 7) for (x in 0 until 19) {
            img.setRGB(x, y, if ((x + y) % 3 == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }
        assertDecodesIdentically(img, "png")
    }

    @Test
    fun pngGradientsExerciseAdaptiveFilters() {
        // Smooth data makes ImageIO's filter chooser pick Sub/Up/Average/Paeth
        // rather than None: the exact paths the hand vectors pin individually.
        val img = BufferedImage(48, 48, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 48) for (x in 0 until 48) {
            img.setRGB(x, y, (0xFF shl 24) or (x * 5 shl 16) or (y * 5 shl 8) or ((x + y) * 2))
        }
        assertDecodesIdentically(img, "png")
    }

    @Test
    fun bmp24ViaImageIo() {
        for ((w, h) in listOf(1 to 1, 3 to 2, 8 to 8, 13 to 5)) {
            assertDecodesIdentically(randomArgb(w, h, seed = 99L * w + h, opaque = true), "bmp")
        }
    }
}
