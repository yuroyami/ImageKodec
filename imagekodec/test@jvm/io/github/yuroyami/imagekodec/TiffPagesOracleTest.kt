package io.github.yuroyami.imagekodec

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multi-page TIFFs written by ImageMagick and joined by libtiff's `tiffcp`, read
 * page by page through [ImageKodec] and through ImageIO's TIFF reader, with
 * `tiffinfo`'s directory count as the page count (#7). Skips cleanly when the
 * tools are absent.
 */
class TiffPagesOracleTest {

    private fun tools(): Boolean = Tools.hasAll("magick", "tiffcp", "tiffinfo")

    private fun run(vararg args: String): Pair<Int, String> {
        val proc = ProcessBuilder(args.toList()).redirectErrorStream(true).start()
        val out = ByteArrayOutputStream()
        proc.inputStream.copyTo(out)
        if (!proc.waitFor(120, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return -1 to ""
        }
        return proc.exitValue() to out.toString()
    }

    private fun temp(name: String, ext: String) =
        File.createTempFile("kite-pages-$name", ext).apply { deleteOnExit() }

    /** A page of its own size and pattern, written with our own PNG encoder. */
    private fun page(index: Int): File {
        val w = 23 + index * 9
        val h = 17 + index * 5
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            argb(0xFF, (x * 255 / (w - 1)) xor (index * 60), (y * 255 / (h - 1)), (index * 80 + x * y) and 0xFF)
        }
        return temp("page$index", ".png").apply { writeBytes(ImageKodec.encodePng(KiteBitmap(w, h, px))) }
    }

    private fun directories(file: File): Int {
        val (code, text) = run(Tools.require("tiffinfo").path, file.path)
        assertEquals(0, code, "tiffinfo failed: $text")
        return Regex("TIFF Directory at offset").findAll(text).count()
    }

    private fun compareEveryPage(name: String, file: File) {
        val bytes = file.readBytes()
        val pages = directories(file)
        assertEquals(pages, ImageKodec.probe(bytes).pageCount, "$name page count")
        val reader = ImageIO.getImageReadersByFormatName("TIFF").next()
        ImageIO.createImageInputStream(file).use { input ->
            reader.input = input
            assertEquals(pages, reader.getNumImages(true), "$name ImageIO page count")
            for (page in 0 until pages) {
                val reference = reader.read(page)
                val ours = ImageKodec.decodePage(bytes, page)
                val info = ImageKodec.probePage(bytes, page)
                assertEquals(reference.width, ours.width, "$name page $page width")
                assertEquals(reference.height, ours.height, "$name page $page height")
                assertEquals(ours.width, info.width, "$name page $page probe width")
                assertEquals(ours.height, info.height, "$name page $page probe height")
                // ImageIO's getRGB treats a one-band gray raster as linear and brightens it, so a
                // gray page compares its stored samples.
                val gray = reference.raster.numBands == 1
                for (y in 0 until reference.height) for (x in 0 until reference.width) {
                    val expected = if (gray) {
                        val v = reference.raster.getSample(x, y, 0)
                        argb(0xFF, v, v, v)
                    } else {
                        reference.getRGB(x, y)
                    }
                    assertEquals(expected, ours[x, y], "$name page $page pixel ($x, $y)")
                }
            }
        }
        reader.dispose()
    }

    @Test
    fun imageMagickPagesMatchImageIoPageByPage() {
        assumeTrue("TIFF tools not installed", tools())
        val magick = Tools.require("magick").path
        val sources = (0 until 3).map { page(it).path }
        for (compression in listOf("none", "LZW", "Zip", "RLE")) {
            val out = temp("magick-$compression", ".tif")
            val (code, text) = run(magick, *sources.toTypedArray(), "-type", "TrueColor", "-depth", "8",
                "-compress", compression, out.path)
            assertEquals(0, code, "magick failed: $text")
            compareEveryPage("magick $compression", out)
        }
    }

    @Test
    fun libtiffJoinedPagesOfMixedKindsMatchImageIo() {
        assumeTrue("TIFF tools not installed", tools())
        val magick = Tools.require("magick").path
        // A gray page, an RGB page and a big-endian page with a predictor, joined by tiffcp.
        val parts = listOf(
            listOf("-colorspace", "Gray", "-depth", "8", "-compress", "LZW"),
            listOf("-type", "TrueColor", "-depth", "8", "-compress", "Zip"),
            listOf("-type", "TrueColor", "-depth", "8", "-endian", "MSB", "-compress", "none"),
        ).mapIndexed { i, options ->
            temp("part$i", ".tif").also { out ->
                val (code, text) = run(magick, page(i).path, *options.toTypedArray(), out.path)
                assertEquals(0, code, "magick failed: $text")
            }
        }
        val joined = temp("joined", ".tif")
        val (code, text) = run(Tools.require("tiffcp").path, *parts.map { it.path }.toTypedArray(), joined.path)
        assertEquals(0, code, "tiffcp failed: $text")
        compareEveryPage("tiffcp", joined)
    }
}
