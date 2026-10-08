package io.github.yuroyami.imagekodec

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The TIFF writer (#9) read by libtiff and ImageMagick: `tiffinfo` must list one directory a
 * page with Deflate and the horizontal predictor, `tiff2rgba` must read opaque pages exactly
 * (it premultiplies alpha), and ImageMagick must read every page, at 8 and 16 bits, exactly.
 * Skips cleanly without them.
 */
class TiffEncoderOracleTest {

    private fun run(vararg args: String): Pair<Int, ByteArray> {
        val proc = ProcessBuilder(args.toList()).redirectErrorStream(false).start()
        val out = ByteArrayOutputStream()
        val err = Thread { proc.errorStream.readBytes() }.apply { start() }
        proc.inputStream.copyTo(out)
        err.join()
        if (!proc.waitFor(120, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return -1 to ByteArray(0)
        }
        return proc.exitValue() to out.toByteArray()
    }

    private fun tools(): Boolean = Tools.hasAll("tiffinfo", "tiff2rgba", "magick")

    private fun temp(ext: String) = File.createTempFile("kite-tiff-out", ext).apply { deleteOnExit() }

    private fun card(w: Int, h: Int, alpha: Boolean, gray: Boolean, seed: Int): KiteBitmap = KiteBitmap(w, h, IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        val a = if (alpha) (x * 13 + y * 7 + seed) and 0xFF else 0xFF
        val r = (x * 255 / (w - 1) + seed) and 0xFF
        if (gray) argb(a, r, r, r) else argb(a, r, (y * 255 / (h - 1)) xor seed and 0xFF, (x * y + seed) and 0xFF)
    })

    /** ImageMagick's reading of [page] of [file] as 8- or 16-bit RGBA samples. */
    private fun magick(file: File, page: Int, depth: Int): ByteArray {
        val (code, raw) = run(Tools.require("magick").path, "${file.path}[$page]", "-depth", "$depth", "rgba:-")
        assertEquals(0, code, "ImageMagick could not read page $page")
        return raw
    }

    private fun assertMagick8(name: String, file: File, page: Int, want: KiteBitmap) {
        val raw = magick(file, page, 8)
        assertEquals(want.width * want.height * 4, raw.size, "$name: ImageMagick's size")
        for (i in want.argb.indices) {
            val px = argb(raw[i * 4 + 3].toInt() and 255, raw[i * 4].toInt() and 255, raw[i * 4 + 1].toInt() and 255, raw[i * 4 + 2].toInt() and 255)
            if (px != want.argb[i]) throw AssertionError("$name: ImageMagick reads pixel $i as ${px.toUInt().toString(16)}, written ${want.argb[i].toUInt().toString(16)}")
        }
    }

    @Test
    fun libtiffAndImageMagickReadEveryPage() {
        assumeTrue("libtiff tools or ImageMagick not installed", tools())
        val pages = listOf(
            card(71, 53, alpha = false, gray = false, seed = 0),
            card(300, 90, alpha = true, gray = false, seed = 40),
            card(33, 17, alpha = false, gray = true, seed = 90),
            card(45, 21, alpha = true, gray = true, seed = 7),
        )
        val file = temp(".tif").apply { writeBytes(ImageKodec.encodeTiff(pages)) }
        val (code, info) = run(Tools.require("tiffinfo").path, file.path)
        assertEquals(0, code, "tiffinfo failed")
        val text = info.decodeToString()
        assertEquals(pages.size, Regex("TIFF Directory at offset").findAll(text).count(), "tiffinfo's directories:\n$text")
        assertEquals(pages.size, Regex("Compression Scheme: AdobeDeflate").findAll(text).count(), "Deflate on every page:\n$text")
        assertEquals(pages.size, Regex("Predictor: horizontal differencing").findAll(text).count(), "the predictor on every page:\n$text")
        assertTrue("Extra Samples: 1<unassoc-alpha>" in text, "unassociated alpha:\n$text")
        for ((i, page) in pages.withIndex()) assertMagick8("page $i", file, i, page)

        // tiff2rgba reads the opaque pages exactly, through libtiff's own raster reader.
        for (i in listOf(0, 2)) {
            val single = temp(".tif").apply { writeBytes(ImageKodec.encodeTiff(pages[i])) }
            val rgba = temp(".tif")
            assertEquals(0, run(Tools.require("tiff2rgba").path, "-c", "none", single.path, rgba.path).first, "tiff2rgba failed on page $i")
            val read = ImageKodec.decode(rgba.readBytes())
            assertEquals(pages[i].argb.toList(), read.argb.toList(), "tiff2rgba's page $i")
        }
    }

    @Test
    fun imageMagickReadsSixteenBitPages() {
        assumeTrue("libtiff tools or ImageMagick not installed", tools())
        for (channels in 1..4) {
            val w = 37
            val h = 23
            val samples = ShortArray(w * h * channels) { i -> ((i * 2654435761L) ushr 9).toInt().toShort() }
            val file = temp(".tif").apply { writeBytes(ImageKodec.encodeTiff16(KiteBitmap16(w, h, channels, samples))) }
            val raw = magick(file, 0, 16)
            assertEquals(w * h * 8, raw.size, "$channels channels: ImageMagick's size")
            for (p in 0 until w * h) {
                fun got(c: Int) = (raw[p * 8 + 2 * c].toInt() and 255) or ((raw[p * 8 + 2 * c + 1].toInt() and 255) shl 8)
                fun want(c: Int) = samples[p * channels + c].toInt() and 0xFFFF
                val expected = when (channels) {
                    1 -> listOf(want(0), want(0), want(0), 65535)
                    2 -> listOf(want(0), want(0), want(0), want(1))
                    3 -> listOf(want(0), want(1), want(2), 65535)
                    else -> listOf(want(0), want(1), want(2), want(3))
                }
                assertEquals(expected, (0 until 4).map { got(it) }, "$channels channels, pixel $p")
            }
        }
    }
}
