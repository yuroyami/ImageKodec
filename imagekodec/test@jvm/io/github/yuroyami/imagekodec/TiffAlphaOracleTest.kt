package io.github.yuroyami.imagekodec

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.imageio.plugins.tiff.TIFFDirectory
import org.junit.Assume.assumeTrue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TiffAlphaOracleTest {
    @Test
    fun independentReadersUnassociateNativeGrayAndRgbSamples() {
        assumeTrue("ImageMagick not installed", Tools.hasAll("magick"))
        for (bits in listOf(8, 16)) for (photo in listOf(0, 1, 2)) {
            for (le in listOf(true, false)) for (planar in listOf(false, true)) {
                for (descriptor in listOf(1, 2)) {
                    val label = "$bits/$photo/$le/$planar/$descriptor"
                    val bytes = alphaTiff(bits, photo, le, planar, intArrayOf(descriptor))
                    // ImageMagick's GrayAlpha import ignores WhiteIsZero, while
                    // ImageIO inverts the alpha sample along with the gray one.
                    // Check native unassociation independently, then invert color
                    // according to TIFF's photometric rule without altering alpha.
                    val referenceBytes = if (photo == 0) alphaTiff(bits, 1, le, planar, intArrayOf(descriptor)) else bytes
                    // ImageMagick also omits alpha for separate gray planes.
                    // Read those layouts directly through ImageIO's color model;
                    // normalized gray avoids its separate sRGB transfer curve.
                    val reference = if (planar) imageIoRgba(referenceBytes, photo) else magickRgba(referenceBytes, label)
                    assertEquals(6 * 4, reference.size)
                    val bitmap = ImageKodec.decode(bytes)
                    for (x in 0..5) {
                        if (x == 0 && descriptor == 1) {
                            assertEquals(0, bitmap[x, 0], "$label: zero associated alpha")
                            continue
                        }
                        for ((c, shift) in listOf(16, 8, 0, 24).withIndex()) {
                            val stored = reference[x * 4 + c].toInt() and 255
                            val expected = if (photo == 0 && c < 3) 255 - stored else stored
                            val difference = abs(expected - ((bitmap[x, 0] ushr shift) and 255))
                            assertTrue(difference <= if (bits == 16) 1 else 0,
                                "$label at $x channel $c differs by $difference")
                        }
                    }
                }
            }
        }
    }

    private fun imageIoRgba(bytes: ByteArray, photo: Int): ByteArray {
        val image = assertNotNull(ImageIO.read(bytes.inputStream()))
        return ByteArray(6 * 4) { i ->
            val x = i / 4
            val c = i % 4
            if (photo == 2) {
                (image.getRGB(x, 0) ushr intArrayOf(16, 8, 0, 24)[c]).toByte()
            } else {
                val components = image.colorModel.getNormalizedComponents(image.raster.getDataElements(x, 0, null), null, 0)
                ((components[if (c == 3) 1 else 0] * 255 + 0.5f).toInt().coerceIn(0, 255)).toByte()
            }
        }
    }

    private fun magickRgba(bytes: ByteArray, label: String): ByteArray {
        val input = File.createTempFile("imagekodec-alpha", ".tif").apply { deleteOnExit(); writeBytes(bytes) }
        val output = File.createTempFile("imagekodec-alpha", ".rgba").apply { deleteOnExit() }
        val log = File.createTempFile("imagekodec-alpha", ".log").apply { deleteOnExit() }
        val process = ProcessBuilder(Tools.require("magick").path, input.path,
            "-depth", "8", "rgba:${output.path}").redirectErrorStream(true).redirectOutput(log).start()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        assertTrue(finished, "$label: ImageMagick timed out")
        assertEquals(0, process.exitValue(), "$label: ${log.readText()}")
        return output.readBytes()
    }

    @Test
    fun imageIoWritesAndReadsBothAlphaKinds() {
        for ((type, descriptor) in listOf(BufferedImage.TYPE_4BYTE_ABGR_PRE to 1, BufferedImage.TYPE_4BYTE_ABGR to 2)) {
            val image = BufferedImage(31, 9, type)
            for (y in 0 until image.height) for (x in 0 until image.width) {
                val a = (x * 11 + y * 23) and 255
                image.setRGB(x, y, if (a == 0 && descriptor == 1) 0 else
                    argb(a, (x * 43 + y * 37) and 255, (x * 13 + y * 83) and 255, (x * 71 + y * 19) and 255))
            }
            val stream = ByteArrayOutputStream()
            assertTrue(ImageIO.write(image, "TIFF", stream))
            val bytes = stream.toByteArray()
            val reader = ImageIO.getImageReadersByFormatName("TIFF").next()
            try {
                ImageIO.createImageInputStream(bytes.inputStream()).use { input ->
                    reader.input = input
                    val field = assertNotNull(TIFFDirectory.createFromMetadata(reader.getImageMetadata(0)).getTIFFField(338))
                    assertEquals(descriptor, field.getAsInt(0))
                }
            } finally {
                reader.dispose()
            }
            val reference = assertNotNull(ImageIO.read(bytes.inputStream()))
            val bitmap = ImageKodec.decode(bytes)
            for (y in 0 until image.height) for (x in 0 until image.width) {
                assertEquals(reference.getRGB(x, y), bitmap[x, y], "$descriptor at $x, $y")
            }
        }
    }
}
