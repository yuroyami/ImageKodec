package io.github.yuroyami.imagekodec

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TIFFs whose strips and tiles are JPEG (Technical Note 2, compression 7), written
 * by ImageMagick and by libtiff's `tiffcp`, against libtiff's own reading of them:
 * `tiff2rgba` reads through `TIFFReadRGBAImage`, which has libjpeg convert YCbCr to
 * RGB, as ImageMagick and Pillow do (#8). JPEG decoders differ in the last bits of
 * their inverse DCT and color conversion, so the comparison allows a mean and a
 * worst difference, the way the JPEG oracle tests do. Skips cleanly without the tools.
 */
class TiffJpegOracleTest {

    private fun tools(): Boolean = Tools.hasAll("magick", "tiffcp", "tiff2rgba", "tiffinfo", "cjpeg")

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
        File.createTempFile("kite-tiff-jpeg-$name", ext).apply { deleteOnExit() }

    /** Smooth gradients with hard-edged blocks, at sides that leave partial MCUs and tiles. */
    private fun sourcePng(w: Int = 101, h: Int = 75): File {
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val block = (x / 13 + y / 11) % 3
            px[y * w + x] = argb(
                0xFF,
                x * 255 / (w - 1),
                if (block == 0) 30 else y * 255 / (h - 1),
                if (block == 2) 230 else (x + y) * 255 / (w + h - 2),
            )
        }
        return temp("src", ".png").apply { writeBytes(ImageKodec.encodePng(KiteBitmap(w, h, px))) }
    }

    private fun tiffinfo(file: File): String = run(Tools.require("tiffinfo").path, file.path).second

    /** Our decode of [tiff] against libtiff's, within a mean and a worst channel difference. */
    private fun compare(name: String, tiff: File, meanLimit: Double = 0.2, worstLimit: Int = 4): String {
        val info = tiffinfo(tiff)
        assertTrue("Compression Scheme: JPEG" in info, "$name is not a JPEG TIFF:\n$info")
        val reference = temp("$name-rgba", ".tif")
        val (code, text) = run(Tools.require("tiff2rgba").path, "-c", "none", tiff.path, reference.path)
        assertEquals(0, code, "tiff2rgba failed: $text")
        val theirs = ImageKodec.decode(reference.readBytes())
        val bytes = tiff.readBytes()
        assertTrue(ImageKodec.probe(bytes).isDecodable, "$name should probe as decodable")
        val ours = ImageKodec.decode(bytes)
        assertEquals(theirs.width, ours.width, "$name width")
        assertEquals(theirs.height, ours.height, "$name height")
        var total = 0L
        var worst = 0
        for (i in ours.argb.indices) for (shift in intArrayOf(24, 16, 8, 0)) {
            val d = abs(((ours.argb[i] ushr shift) and 0xFF) - ((theirs.argb[i] ushr shift) and 0xFF))
            total += d
            worst = maxOf(worst, d)
        }
        val mean = total.toDouble() / (ours.argb.size * 4)
        val report = "$name: mean $mean, worst $worst"
        assertTrue(mean <= meanLimit && worst <= worstLimit, report)
        return report
    }

    private fun magick(name: String, vararg options: String): File {
        val out = temp(name, ".tif")
        val (code, text) = run(Tools.require("magick").path, sourcePng().path, *options, out.path)
        assertEquals(0, code, "magick failed: $text")
        return out
    }

    private fun tiffcp(name: String, source: File, vararg options: String): File {
        val out = temp(name, ".tif")
        val (code, text) = run(Tools.require("tiffcp").path, *options, source.path, out.path)
        assertEquals(0, code, "tiffcp failed: $text")
        return out
    }

    /**
     * A one-strip TIFF around a whole JPEG file that `cjpeg` wrote with chroma [sampling]
     * (such as `2x1`), with no JPEGTables: the strip carries its own tables.
     */
    private fun wrappedJpeg(name: String, sampling: String): File {
        val ppm = temp(name, ".ppm")
        assertEquals(0, run(Tools.require("magick").path, sourcePng().path, ppm.path).first)
        val jpeg = temp(name, ".jpg")
        val (code, text) = run(Tools.require("cjpeg").path, "-quality", "90", "-sample", "$sampling,1x1,1x1",
            "-outfile", jpeg.path, ppm.path)
        assertEquals(0, code, "cjpeg failed: $text")
        val stream = jpeg.readBytes()
        val (h, v) = sampling.split("x").map { it.toInt() }
        val probe = ImageKodec.probe(stream)
        val fields = listOf(
            256 to (4 to probe.width), 257 to (4 to probe.height), 258 to (3 to 8), 259 to (3 to 7),
            262 to (3 to 6), 273 to (4 to -1), 277 to (3 to 3), 278 to (4 to probe.height),
            279 to (4 to stream.size), 530 to (3 to -2),
        )
        val ifd = 8
        val bitsAt = ifd + 2 + fields.size * 12 + 4
        val stripAt = bitsAt + 6
        val out = ByteArray(stripAt + stream.size)
        var at = 0
        fun u16(x: Int) { out[at++] = x.toByte(); out[at++] = (x ushr 8).toByte() }
        fun u32(x: Int) { u16(x); u16(x ushr 16) }
        out[0] = 'I'.code.toByte(); out[1] = 'I'.code.toByte(); at = 2; u16(42); u32(ifd)
        u16(fields.size)
        for ((tag, field) in fields) {
            val (type, value) = field
            u16(tag); u16(type)
            when (tag) {
                258 -> { u32(3); u32(bitsAt) }
                530 -> { u32(2); u16(h); u16(v) }
                273 -> { u32(1); u32(stripAt) }
                else -> { u32(1); if (type == 3) { u16(value); u16(0) } else u32(value) }
            }
        }
        u32(0)
        u16(8); u16(8); u16(8)
        stream.copyInto(out, stripAt)
        return temp(name, ".tif").apply { writeBytes(out) }
    }

    @Test
    fun imageMagickJpegTiffsMatchLibtiff() {
        assumeTrue("TIFF tools not installed", tools())
        val reports = listOf(
            // ImageMagick 6 keeps RGB samples in the JPEG stream unless told to store YCbCr.
            compare("magick-rgb", magick("rgb", "-type", "TrueColor", "-compress", "JPEG", "-quality", "85")),
            compare("magick-ycbcr", magick("ycbcr", "-type", "TrueColor", "-colorspace", "YCbCr", "-compress", "JPEG")),
            compare("magick-gray", magick("gray", "-colorspace", "Gray", "-compress", "JPEG")),
        )
        println(reports.joinToString("\n"))
    }

    @Test
    fun wholeJpegStripsAtEverySubsamplingMatchLibtiff() {
        assumeTrue("TIFF tools not installed", tools())
        val reports = listOf("1x1", "2x1", "1x2", "2x2", "4x1").map {
            compare("cjpeg-$it", wrappedJpeg("cjpeg-$it", it))
        }
        println(reports.joinToString("\n"))
    }

    @Test
    fun libtiffJpegStripsAndTilesMatchLibtiff() {
        assumeTrue("TIFF tools not installed", tools())
        val raw = magick("raw", "-type", "TrueColor", "-depth", "8", "-compress", "none")
        val reports = listOf(
            // YCbCr in strips of 16 rows, the last one short.
            compare("tiffcp-strips", tiffcp("strips", raw, "-c", "jpeg:90", "-r", "16")),
            // RGB samples, no color conversion inside the JPEG stream.
            compare("tiffcp-rgb", tiffcp("rgb", raw, "-c", "jpeg:r:90", "-r", "32")),
            // Tiles padded past the right and bottom edges.
            compare("tiffcp-tiles", tiffcp("tiles", raw, "-c", "jpeg:90", "-t", "-w", "32", "-l", "32")),
            compare("tiffcp-rgb-tiles", tiffcp("rgb-tiles", raw, "-c", "jpeg:r", "-t", "-w", "48", "-l", "16")),
        )
        println(reports.joinToString("\n"))
    }
}
