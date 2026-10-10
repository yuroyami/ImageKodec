package io.github.yuroyami.imagekodec

import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExpansionBudgetOracleTest {
    private fun temp(suffix: String) = File.createTempFile("imagekodec-expansion", suffix).apply { deleteOnExit() }

    private fun run(vararg args: String) {
        val log = temp(".log")
        val process = ProcessBuilder(*args).redirectErrorStream(true).redirectOutput(log).start()
        val completed = process.waitFor(120, TimeUnit.SECONDS)
        if (!completed) process.destroyForcibly()
        assertTrue(completed, "oracle timed out")
        assertEquals(0, process.exitValue(), log.readText())
    }

    private fun compare(bytes: ByteArray, reference: BufferedImage, expected: Int) {
        val bitmap = ImageKodec.decode(bytes)
        assertEquals(reference.width, bitmap.width)
        assertEquals(reference.height, bitmap.height)
        assertTrue(ImageKodec.probe(bytes).isDecodable)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val pixel = bitmap[x, y]
            if (pixel != expected || pixel != reference.getRGB(x, y)) {
                assertEquals(expected, pixel, "source at $x/$y")
                assertEquals(reference.getRGB(x, y), pixel, "oracle at $x/$y")
            }
        }
    }

    @Test
    fun constantAndLibwebpWrittenImagesAgreeWithLibwebp() {
        assumeTrue("libwebp tools not installed", Tools.hasAll("cwebp", "dwebp"))
        val webp = temp(".webp")
        val png = temp(".png")
        for (pixel in intArrayOf(-1, 0)) {
            val bytes = constantWebp(2048, 1024, pixel)
            webp.writeBytes(bytes)
            run(Tools.require("dwebp").path, webp.path, "-o", png.path)
            compare(bytes, assertNotNull(ImageIO.read(png)), pixel)
        }
        val source = temp(".png")
        for ((size, pixel) in listOf(2048 to -1, 2048 to 0, 4096 to -1)) {
            val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            if (pixel == -1) for (y in 0 until size) for (x in 0 until size) image.setRGB(x, y, pixel)
            assertTrue(ImageIO.write(image, "PNG", source))
            run(Tools.require("cwebp").path, "-lossless", "-exact", source.path, "-o", webp.path)
            val bytes = webp.readBytes()
            assertTrue(size.toLong() * size > bytes.size * 4096L)
            run(Tools.require("dwebp").path, webp.path, "-o", png.path)
            compare(bytes, assertNotNull(ImageIO.read(png)), pixel)
        }
    }

    @Test
    fun libtiffBlankScannedPagesAndPackedPngAgreeWithImageIo() {
        assumeTrue("libtiff tools not installed", Tools.hasAll("tiffcp", "tiff2rgba"))
        val width = 2480
        val height = 3508
        val page = BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY)
        val graphics = page.createGraphics()
        try {
            graphics.color = java.awt.Color.WHITE
            graphics.fillRect(0, 0, width, height)
        } finally { graphics.dispose() }
        val source = temp(".tif")
        assertTrue(ImageIO.write(page, "TIFF", source))
        val compressed = temp(".tif")
        val rgba = temp(".tif")
        for (compression in listOf("g4", "zip")) {
            run(Tools.require("tiffcp").path, "-c", compression, "-r", "$height", source.path, compressed.path)
            val bytes = compressed.readBytes()
            assertTrue(width.toLong() * height > bytes.size * 4096L, "$compression/${bytes.size}")
            run(Tools.require("tiff2rgba").path, "-c", "none", compressed.path, rgba.path)
            compare(bytes, assertNotNull(ImageIO.read(rgba)), -1)
        }
        val fax = faxTiff(faxBits("1".repeat(1024)), 4, width = 2048, height = 1024)
        compressed.writeBytes(fax)
        run(Tools.require("tiff2rgba").path, "-c", "none", compressed.path, rgba.path)
        compare(fax, assertNotNull(ImageIO.read(rgba)), -1)
        val png = packedBlankPng(4096, 1024, 1)
        compare(png, assertNotNull(ImageIO.read(png.inputStream())), gray(0))
    }
}
