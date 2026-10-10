package io.github.yuroyami.imagekodec

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TiffFaxOracleTest {
    @Test
    fun libtiffGroup3EolsWithAndWithoutFillAgree() {
        assumeTrue("fax TIFF tools not installed", Tools.hasAll("tiffcp", "tiff2rgba"))
        for (codec in listOf("g3:1d", "g3:1d:fill")) {
            for (width in listOf(1, 7, 13, 97, 3001)) for (photometric in listOf(0, 1)) {
                val input = File.createTempFile("imagekodec-g3-source", ".tif").apply {
                    deleteOnExit(); writeBytes(withPhotometric(encodedFax("CCITT RLE", width), photometric))
                }
                val output = File.createTempFile("imagekodec-g3", ".tif").apply { deleteOnExit() }
                val log = File.createTempFile("imagekodec-g3", ".log").apply { deleteOnExit() }
                val process = ProcessBuilder(Tools.require("tiffcp").path, "-c", codec,
                    "-r", "3", input.path, output.path).redirectErrorStream(true).redirectOutput(log).start()
                val finished = process.waitFor(30, TimeUnit.SECONDS)
                if (!finished) process.destroyForcibly()
                assertTrue(finished, "$codec: libtiff timed out")
                assertEquals(0, process.exitValue(), "$codec: ${log.readText()}")
                // The same one-column ImageIO run-array fault affects T.4.
                compareFax(output.readBytes(), "$codec/$width/$photometric", readOriginal = width != 1)
            }
        }
    }

    @Test
    fun imageIoEncodedModifiedHuffmanAndGroup4AgreeWithLibtiff() {
        assumeTrue("tiff2rgba not installed", Tools.hasAll("tiff2rgba"))
        for (compression in listOf("CCITT RLE", "CCITT T.6")) {
            for (width in listOf(1, 8, 13, 64, 97, 3001, 6001)) for (photometric in listOf(0, 1)) {
                val bytes = withPhotometric(encodedFax(compression, width), photometric)
                // ImageIO's RLE reader faults on its own one-column fixture;
                // libtiff independently checks that case along with every other width.
                compareFax(bytes, "$compression/$width/$photometric",
                    readOriginal = compression != "CCITT RLE" || width != 1)
            }
        }
    }

    private fun encodedFax(compression: String, width: Int): ByteArray {
        val image = BufferedImage(width, 9, BufferedImage.TYPE_BYTE_BINARY)
        for (y in 0 until image.height) for (x in 0 until width) {
            val black = when (y % 3) {
                0 -> x >= width / 3 && x < width * 2 / 3
                1 -> x % 2 == 1
                else -> x == 0 || x == width - 1
            }
            image.setRGB(x, y, if (black) 0xFF000000.toInt() else -1)
        }
        val writer = ImageIO.getImageWritersByFormatName("TIFF").next()
        val bytes = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(bytes).use { output ->
            writer.output = output
            val params = writer.defaultWriteParam
            params.compressionMode = ImageWriteParam.MODE_EXPLICIT
            params.compressionType = compression
            writer.write(null, IIOImage(image, null, null), params)
        }
        writer.dispose()
        return bytes.toByteArray()
    }

    private fun withPhotometric(input: ByteArray, photometric: Int): ByteArray {
        val bytes = input.copyOf()
        val le = bytes[0] == 'I'.code.toByte()
        fun u16(at: Int): Int {
            val a = bytes[at].toInt() and 255
            val b = bytes[at + 1].toInt() and 255
            return if (le) a or (b shl 8) else (a shl 8) or b
        }
        fun u32(at: Int) = if (le) u16(at) or (u16(at + 2) shl 16)
            else (u16(at) shl 16) or u16(at + 2)
        val ifd = u32(4)
        for (i in 0 until u16(ifd)) {
            val at = ifd + 2 + i * 12
            if (u16(at) == 262) {
                assertEquals(3, u16(at + 2))
                assertEquals(1, u32(at + 4))
                bytes[at + 8] = if (le) photometric.toByte() else 0
                bytes[at + 9] = if (le) 0 else photometric.toByte()
                return bytes
            }
        }
        error("ImageIO fixture has no PhotometricInterpretation")
    }

    private fun compareFax(bytes: ByteArray, label: String, readOriginal: Boolean = true) {
        val input = File.createTempFile("imagekodec-fax", ".tif").apply { deleteOnExit(); writeBytes(bytes) }
        val decoded = File.createTempFile("imagekodec-fax-rgba", ".tif").apply { deleteOnExit() }
        val log = File.createTempFile("imagekodec-fax", ".log").apply { deleteOnExit() }
        val process = ProcessBuilder(Tools.require("tiff2rgba").path, "-c", "none", input.path, decoded.path)
            .redirectErrorStream(true).redirectOutput(log).start()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        assertTrue(finished, "$label: libtiff timed out")
        assertEquals(0, process.exitValue(), "$label: ${log.readText()}")
        val reference = assertNotNull(ImageIO.read(decoded))
        val original = if (readOriginal) assertNotNull(ImageIO.read(bytes.inputStream())) else null
        val bitmap = ImageKodec.decode(bytes)
        assertEquals(reference.width, bitmap.width, label)
        assertEquals(reference.height, bitmap.height, label)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            assertEquals(reference.getRGB(x, y), bitmap[x, y], "$label at $x, $y")
            if (original != null) assertEquals(reference.getRGB(x, y), original.getRGB(x, y), "$label references at $x, $y")
        }
    }
}
