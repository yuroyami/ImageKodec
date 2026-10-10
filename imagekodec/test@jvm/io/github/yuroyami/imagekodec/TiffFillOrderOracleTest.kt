package io.github.yuroyami.imagekodec

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

class TiffFillOrderOracleTest {
    @Test
    fun libtiffPackedSamplesAgreeAcrossByteCodecsAndFillOrders() {
        requireTools()
        for (bits in listOf(1, 2, 4)) for (codec in listOf("none", "packbits", "lzw", "zip")) {
            for (order in listOf(1, 2)) {
                val source = temp("packed-source").apply { writeBytes(packedFillTiff(bits)) }
                val file = transcode(source, codec, order, tiled = false)
                compare(file, "$bits/$codec/$order")
            }
        }
    }

    @Test
    fun libtiffPredictedSamplesAgreeAcrossByteOrdersTilesAndPlanes() {
        requireTools()
        for (bits in listOf(8, 16)) for (le in listOf(true, false)) {
            for (planar in listOf(false, true)) for (tiled in listOf(false, true)) {
                val source = temp("byte-source").apply {
                    writeBytes(predictorTiff(bits, le, planar, compression = 1, predictor = 1))
                }
                for (codec in listOf("none", "packbits", "lzw:2", "zip:2")) for (order in listOf(1, 2)) {
                    val file = transcode(source, codec, order, tiled, le)
                    // libtiff's RGBA reader compares its rounded allocation size
                    // with raw tile bytes when reversal disables memory mapping.
                    // ImageIO reads those exact files without converting layouts.
                    compare(file, "$bits/$le/$planar/$tiled/$codec/$order", if (bits == 16) 1 else 0,
                        useImageIo = tiled && codec == "none" && order == 2)
                }
            }
        }
    }

    @Test
    fun faxFillOrderAgreesWithLibtiffForBothPolarities() {
        requireTools()
        val row = "000111010".repeat(4).padEnd(40, '0')
        for (photo in listOf(0, 1)) {
            val bytes = faxTiff(faxBits(row + row), 2, photo)
            val source = temp("fax-source").apply { writeBytes(bytes) }
            for (order in listOf(1, 2)) {
                compare(temp("mh").apply { writeBytes(withFillOrder(bytes, order)) }, "MH/$photo/$order")
                for (codec in listOf("g3:1d", "g3:1d:fill", "g4")) {
                    compare(transcode(source, codec, order, tiled = false), "$codec/$photo/$order")
                }
            }
        }
    }

    private fun requireTools() = assumeTrue("TIFF tools not installed", Tools.hasAll("tiffcp", "tiff2rgba"))

    private fun temp(name: String) = File.createTempFile("imagekodec-fill-$name", ".tif").apply { deleteOnExit() }

    private fun run(vararg args: String) {
        val log = File.createTempFile("imagekodec-fill", ".log").apply { deleteOnExit() }
        val process = ProcessBuilder(args.toList()).redirectErrorStream(true).redirectOutput(log).start()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        assertTrue(finished, "TIFF tool timed out")
        assertEquals(0, process.exitValue(), log.readText())
    }

    private fun transcode(source: File, codec: String, order: Int, tiled: Boolean, le: Boolean = true): File {
        val output = temp("encoded")
        val storage = if (tiled) listOf("-t", "-w", "16", "-l", "16") else listOf("-s", "-r", "8")
        run(*(listOf(Tools.require("tiffcp").path, "-c", codec, "-f",
            if (order == 1) "msb2lsb" else "lsb2msb", if (le) "-L" else "-B") +
            storage + listOf(source.path, output.path)).toTypedArray())
        val reader = ImageIO.getImageReadersByFormatName("TIFF").next()
        try {
            ImageIO.createImageInputStream(output).use { input ->
                reader.input = input
                val directory = TIFFDirectory.createFromMetadata(reader.getImageMetadata(0))
                assertEquals(order, assertNotNull(directory.getTIFFField(266)).getAsInt(0))
            }
        } finally {
            reader.dispose()
        }
        return output
    }

    private fun compare(file: File, label: String, tolerance: Int = 0, useImageIo: Boolean = false) {
        val referenceFile = if (useImageIo) file else temp("rgba").also { decoded ->
            try {
                run(Tools.require("tiff2rgba").path, "-c", "none", file.path, decoded.path)
            } catch (e: AssertionError) {
                throw AssertionError("$label: ${e.message}", e)
            }
        }
        val reference = assertNotNull(ImageIO.read(referenceFile))
        val bytes = file.readBytes()
        val bitmap = ImageKodec.decode(bytes)
        assertTrue(ImageKodec.probe(bytes).isDecodable, label)
        assertEquals(reference.width, bitmap.width, label)
        assertEquals(reference.height, bitmap.height, label)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            for (shift in listOf(16, 8, 0, 24)) {
                val difference = abs(((reference.getRGB(x, y) ushr shift) and 255) - ((bitmap[x, y] ushr shift) and 255))
                assertTrue(difference <= tolerance, "$label at $x, $y channel $shift differs by $difference")
            }
        }
    }
}
