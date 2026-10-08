package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.CcittFax
import io.github.yuroyami.imagekodec.codec.CcittOptions
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.imageio.plugins.tiff.TIFFDirectory
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CcittMixedOracleTest {
    // A table, because the C2 compiler of JDK 11 on x64 vectorises a loop that reverses the bits of
    // each byte of an array in place, and the vector code gives wrong bytes.
    private val reversedBits = ByteArray(256) { (Integer.reverse(it) ushr 24).toByte() }

    private fun temp(ext: String) = File.createTempFile("imagekodec-mixed-fax", ext).apply { deleteOnExit() }
    private fun run(vararg args: String) {
        val log = temp(".log")
        val process = ProcessBuilder(args.toList()).redirectErrorStream(true).redirectOutput(log).start()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        assertTrue(finished, "fax oracle timed out")
        assertEquals(0, process.exitValue(), log.readText())
    }

    @Test
    fun theFourByteReportVectorIsAnAllWhiteLibtiffPage() {
        assumeTrue("libtiff not installed", Tools.hasAll("tiff2rgba"))
        val input = temp(".tif").apply { writeBytes(faxTiff(hex("001cc005"), 3, t4Options = 1)) }
        val decoded = temp(".tif")
        run(Tools.require("tiff2rgba").path, "-c", "none", input.path, decoded.path)
        val reference = assertNotNull(ImageIO.read(decoded))
        val ours = ImageKodec.decode(input.readBytes())
        for (y in 0..1) for (x in 0..7) {
            assertEquals(-1, reference.getRGB(x, y))
            assertEquals(reference.getRGB(x, y), ours[x, y])
        }
    }

    @Test
    fun libtiffMixedStripsAndTilesMatchTheSourceAndIndependentReader() {
        assumeTrue("libtiff not installed", Tools.hasAll("tiffcp", "tiff2rgba"))
        for (width in listOf(13, 64, 1024)) {
            val source = BufferedImage(width, 32, BufferedImage.TYPE_BYTE_BINARY)
            for (y in 0 until source.height) for (x in 0 until width) {
                val black = when (y % 8) {
                    0 -> (x and 1) != 0
                    1 -> ((x + y) and 1) != 0
                    2 -> x >= width / 3 + y % 3 && x < 2 * width / 3 + y % 3
                    3 -> x == 0 || x == width - 1
                    4 -> false
                    5 -> true
                    6 -> (x + y).mod(13) < 6
                    else -> ((x * 17 + y * 31) xor (x / 7)) and 7 < 3
                }
                source.setRGB(x, y, if (black) 0xff000000.toInt() else -1)
            }
            val input = temp(".tif")
            assertTrue(ImageIO.write(source, "TIFF", input))
            for (fill in listOf(false, true)) for (order in listOf("msb2lsb", "lsb2msb")) {
                for (tiled in listOf(false, true)) {
                    val compressed = temp(".tif")
                    val decoded = temp(".tif")
                    val args = mutableListOf(Tools.require("tiffcp").path, "-c",
                        "g3:2d" + if (fill) ":fill" else "", "-f", order)
                    args += if (tiled) listOf("-t", "-w", "32", "-l", "32") else listOf("-s", "-r", "5")
                    args += listOf(input.path, compressed.path)
                    run(*args.toTypedArray())
                    run(Tools.require("tiff2rgba").path, "-c", "none", compressed.path, decoded.path)
                    val reference = assertNotNull(ImageIO.read(decoded))
                    val bytes = compressed.readBytes()
                    val ours = ImageKodec.decode(bytes)
                    assertTrue(ImageKodec.probe(bytes).isDecodable)
                    for (y in 0 until source.height) for (x in 0 until width) {
                        val label = "$width/$fill/$order/$tiled at $x,$y"
                        assertEquals(source.getRGB(x, y), reference.getRGB(x, y), "libtiff $label")
                        assertEquals(source.getRGB(x, y), ours[x, y], "ImageKodec $label")
                    }
                    if (!tiled) checkPublicStripApi(source, compressed, bytes)
                }
            }
        }
    }

    private fun checkPublicStripApi(source: BufferedImage, file: File, bytes: ByteArray) {
        val reader = ImageIO.getImageReadersByFormatName("TIFF").next()
        try {
            ImageIO.createImageInputStream(file).use { input ->
                reader.input = input
                val fields = TIFFDirectory.createFromMetadata(reader.getImageMetadata(0))
                val offsets = assertNotNull(fields.getTIFFField(273))
                val counts = assertNotNull(fields.getTIFFField(279))
                val rowsPerStrip = assertNotNull(fields.getTIFFField(278)).getAsInt(0)
                val fillOrder = fields.getTIFFField(266)?.getAsInt(0) ?: 1
                val photo = assertNotNull(fields.getTIFFField(262)).getAsInt(0)
                val rowBytes = (source.width + 7) / 8
                for (strip in 0 until offsets.count) {
                    val firstRow = strip * rowsPerStrip
                    val rows = minOf(rowsPerStrip, source.height - firstRow)
                    val at = offsets.getAsLong(strip).toInt()
                    val data = bytes.copyOfRange(at, at + counts.getAsLong(strip).toInt())
                    if (fillOrder == 2) for (i in data.indices) data[i] = reversedBits[data[i].toInt() and 255]
                    for (k in listOf(1, 2, 4)) {
                        val packed = CcittFax.decode(data, k, CcittOptions(source.width, rows, false, true, false, true))
                        assertEquals(rows * rowBytes, packed.size)
                        for (y in 0 until rows) for (x in 0 until source.width) {
                            val bit = (packed[y * rowBytes + x / 8].toInt() ushr (7 - x % 8)) and 1
                            val black = bit == if (photo == 0) 1 else 0
                            assertEquals(source.getRGB(x, firstRow + y) == 0xff000000.toInt(), black,
                                "public K=$k strip=$strip at $x,$y")
                        }
                    }
                }
            }
        } finally { reader.dispose() }
    }
}
