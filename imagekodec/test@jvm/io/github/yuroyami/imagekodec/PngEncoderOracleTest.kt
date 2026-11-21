package io.github.yuroyami.imagekodec

import java.awt.image.IndexColorModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import org.junit.Assume.assumeTrue
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The PNG writer with options (#182) read by four other implementations: `pngcheck` checks
 * every chunk, its order and its CRC, and ImageIO, Pillow and ImageMagick (through libpng) read
 * each file to its samples, which must be those ImageKodec wrote, at 16 bits where the reader
 * keeps them. Every colour type, bit depth and interlace is covered, at 8 and 16 bits a sample.
 * Skips without `pngcheck`, ImageMagick, or python3 with Pillow, and fails instead under
 * `IMAGEKODEC_REQUIRE_ORACLES=1`.
 */
class PngEncoderOracleTest {

    private companion object {
        fun hasTrns(png: ByteArray): Boolean {
            var at = 8
            while (at + 8 <= png.size) {
                val len = ((png[at].toInt() and 255) shl 24) or ((png[at + 1].toInt() and 255) shl 16) or
                    ((png[at + 2].toInt() and 255) shl 8) or (png[at + 3].toInt() and 255)
                if (png.copyOfRange(at + 4, at + 8).decodeToString() == "tRNS") return true
                at += 12 + len
            }
            return false
        }
    }

    private fun run(vararg args: String): Pair<Int, ByteArray> {
        val proc = ProcessBuilder(args.toList()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val out = ByteArrayOutputStream()
        proc.inputStream.copyTo(out)
        if (!proc.waitFor(120, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return -1 to ByteArray(0)
        }
        return proc.exitValue() to out.toByteArray()
    }

    private fun tools(): Boolean {
        if (!Tools.hasAll("pngcheck", "python3", "magick")) return false
        val pillow = run(Tools.require("python3").path, "-c", "import PIL").first == 0
        if (!pillow && System.getenv(Tools.REQUIRE_ENV) == "1") error("python3 has no Pillow, and ${Tools.REQUIRE_ENV}=1 forbids skipping")
        return pillow
    }

    /** One file to check: its name, its bytes, and the samples it holds as 16-bit RGBA. */
    private class Case(val name: String, val png: ByteArray, val rgba16: IntArray) {
        /**
         * Pillow narrows 16-bit colour to 8 bits and then leaves a 16-bit tRNS colour key
         * unapplied, and it compares a gray key under 8 bits with the gray widened to 8, so it
         * never matches. Its alpha is not checked there; ImageMagick's is.
         */
        val pillowAlpha: Boolean = !(hasTrns(png) && ((png[24].toInt() == 16 && png[25].toInt() == 2) || (png[24].toInt() < 8 && png[25].toInt() == 0)))

        /** ImageIO applies a gray tRNS colour key at 8 and 16 bits only, and reads one under 8 bits as opaque; ImageMagick's alpha is checked there. */
        val imageIoAlpha: Boolean = !(png[24].toInt() < 8 && png[25].toInt() == 0 && hasTrns(png))
    }

    /** 16-bit RGBA, four ints a pixel, of an 8-bit bitmap. */
    private fun rgba16(b: KiteBitmap): IntArray = IntArray(b.argb.size * 4) { i ->
        val p = b.argb[i / 4]
        when (i % 4) {
            0 -> ((p ushr 16) and 255) * 257
            1 -> ((p ushr 8) and 255) * 257
            2 -> (p and 255) * 257
            else -> (p ushr 24) * 257
        }
    }

    /** 16-bit RGBA of a 16-bit bitmap: gray repeated, a missing alpha opaque. */
    private fun rgba16(b: KiteBitmap16): IntArray = IntArray(b.width * b.height * 4) { i ->
        val px = i / 4
        val c = i % 4
        val ch = b.channels
        fun s(k: Int) = b.samples[px * ch + k].toInt() and 0xFFFF
        when {
            c == 3 -> if (ch == 2) s(1) else if (ch == 4) s(3) else 65535
            ch <= 2 -> s(0)
            else -> s(c)
        }
    }

    private fun cases(): List<Case> {
        val out = ArrayList<Case>()
        val r = Random(182)
        for ((w, h) in listOf(1 to 1, 5 to 3, 9 to 1, 1 to 7, 37 to 23)) {
            val pixels = w * h
            val gray2 = KiteBitmap(w, h, IntArray(pixels) { gray(r.nextInt(4) * 85) })
            val gray8 = KiteBitmap(w, h, IntArray(pixels) { gray(r.nextInt(256)) })
            val grayAlpha = KiteBitmap(w, h, IntArray(pixels) { argb(r.nextInt(256), 0, 0, 0).let { a -> a or (r.nextInt(256) * 0x010101) } })
            val keyedGray = KiteBitmap(w, h, IntArray(pixels) { i -> if (i % 4 == 1) argb(0, 17, 17, 17) else gray(34 * (1 + r.nextInt(7))) })
            val table = IntArray(13) { argb(if (it < 4) it * 60 else 255, r.nextInt(256), it * 19, r.nextInt(256)) }
            val palette = KiteBitmap(w, h, IntArray(pixels) { table[r.nextInt(13)] })
            val rgb = KiteBitmap(w, h, IntArray(pixels) { argb(255, r.nextInt(256), r.nextInt(256), r.nextInt(256)) })
            val keyedRgb = KiteBitmap(w, h, IntArray(pixels) { i -> if (i % 3 == 0) argb(0, 1, 2, 3) else argb(255, 100 + r.nextInt(100), r.nextInt(256), r.nextInt(256)) })
            val rgba = KiteBitmap(w, h, IntArray(pixels) { r.nextInt() })
            val bitmaps = listOf("gray2" to gray2, "gray8" to gray8, "grayAlpha" to grayAlpha, "keyedGray" to keyedGray, "palette" to palette, "rgb" to rgb, "keyedRgb" to keyedRgb, "rgba" to rgba)
            for ((name, b) in bitmaps) for (interlace in listOf(false, true)) {
                val types = listOf(PngColorType.Auto to null) + when (name) {
                    "gray2" -> listOf(PngColorType.Gray to 2, PngColorType.Gray to 16, PngColorType.Palette to 4, PngColorType.GrayAlpha to 16)
                    "palette" -> listOf(PngColorType.Palette to 8, PngColorType.Rgba to 16)
                    "keyedRgb" -> listOf(PngColorType.Rgb to 16)
                    "rgb" -> listOf(PngColorType.Rgb to 16)
                    else -> emptyList()
                }
                for ((type, depth) in types) {
                    val png = ImageKodec.encodePng(b, PngEncodeOptions(colorType = type, bitDepth = depth, interlace = interlace))
                    out += Case("$name ${w}x$h $type $depth${if (interlace) " interlaced" else ""}", png, rgba16(b))
                }
            }
            for (channels in 1..4) for (interlace in listOf(false, true)) {
                val b16 = KiteBitmap16(w, h, channels, ShortArray(pixels * channels) { r.nextInt(65536).toShort() })
                out += Case("16-bit $channels channels ${w}x$h${if (interlace) " interlaced" else ""}", ImageKodec.encodePng16(b16, PngEncodeOptions(interlace = interlace)), rgba16(b16))
            }
            val keyed16 = KiteBitmap16(w, h, 4, ShortArray(pixels * 4) { i -> if ((i / 4) % 3 == 0) (if (i % 4 == 3) 0 else 777).toShort() else if (i % 4 == 3) -1 else (1000 + r.nextInt(60000)).toShort() })
            out += Case("16-bit keyed ${w}x$h", ImageKodec.encodePng16(keyed16, PngEncodeOptions()), rgba16(keyed16))
        }
        return out
    }

    private val pillowScript = """
import sys, struct
from PIL import Image
im = Image.open(sys.argv[1])
im.load()
out = sys.stdout.buffer
if im.mode in ('I', 'I;16', 'I;16B'):
    out.write(b'G')
    out.write(struct.pack('<%dI' % (im.width * im.height), *[v & 0xFFFFFFFF for v in im.getdata()]))
else:
    out.write(b'C')
    out.write(im.convert('RGBA').tobytes())
""".trimIndent()

    @Test
    fun pngcheckImageIoAndPillowReadEveryTypeAndDepth() {
        assumeTrue("pngcheck or python3 with Pillow not installed", tools())
        val dir = kotlin.io.path.createTempDirectory("kite-png").toFile()
        try {
            var checked = 0
            for (case in cases()) {
                val file = File(dir, "case.png").apply { writeBytes(case.png) }
                val (code, text) = run(Tools.require("pngcheck").path, "-v", file.path)
                assertEquals(0, code, "${case.name}: pngcheck says ${text.decodeToString()}")
                assertTrue("No errors detected" in text.decodeToString(), "${case.name}: ${text.decodeToString()}")

                val image = ImageIO.read(ByteArrayInputStream(case.png))
                val raster = image.raster
                val cm = image.colorModel
                val bits = cm.getComponentSize(0)
                val sample = IntArray(raster.numBands)
                for (p in 0 until image.width * image.height) {
                    raster.getPixel(p % image.width, p / image.width, sample)
                    val got = if (cm is IndexColorModel) {
                        val c = cm.getRGB(sample[0])
                        intArrayOf((c ushr 16) and 255, (c ushr 8) and 255, c and 255, c ushr 24).map { it * 257 }
                    } else {
                        val scale = { v: Int -> (v.toLong() * 65535 / ((1 shl bits) - 1)).toInt() }
                        when (raster.numBands) {
                            1 -> listOf(scale(sample[0]), scale(sample[0]), scale(sample[0]), 65535)
                            2 -> listOf(scale(sample[0]), scale(sample[0]), scale(sample[0]), scale(sample[1]))
                            3 -> listOf(scale(sample[0]), scale(sample[1]), scale(sample[2]), 65535)
                            else -> sample.map(scale)
                        }
                    }
                    val want = case.rgba16.copyOfRange(p * 4, p * 4 + 4).toList().let { if (case.imageIoAlpha) it else it.take(3) + got[3] }
                    assertEquals(want, got, "${case.name}: ImageIO reads pixel $p")
                }

                val (pilCode, pil) = run(Tools.require("python3").path, "-W", "ignore", "-c", pillowScript, file.path)
                assertEquals(0, pilCode, "${case.name}: Pillow could not read the file")
                for (p in 0 until image.width * image.height) {
                    if (pil[0] == 'G'.code.toByte()) {
                        // A 16-bit gray file stays 16-bit in Pillow; it keeps no alpha there.
                        val at = 1 + p * 4
                        val v = (pil[at].toInt() and 255) or ((pil[at + 1].toInt() and 255) shl 8)
                        assertEquals(case.rgba16[p * 4], v, "${case.name}: Pillow reads gray of pixel $p")
                    } else {
                        // Pillow narrows 16-bit colour to its high byte.
                        val got = (0 until 4).map { pil[1 + p * 4 + it].toInt() and 255 }
                        val want = (0 until 4).map { case.rgba16[p * 4 + it] ushr 8 }.let { if (case.pillowAlpha) it else it.take(3) + got[3] }
                        assertEquals(want, got, "${case.name}: Pillow reads pixel $p")
                    }
                }
                // ImageMagick reads through libpng, at 16 bits a sample, alpha included everywhere.
                val (imCode, im) = run(Tools.require("magick").path, file.path, "-depth", "16", "-endian", "MSB", "rgba:-")
                assertEquals(0, imCode, "${case.name}: ImageMagick could not read the file")
                assertEquals(image.width * image.height * 8, im.size, "${case.name}: ImageMagick's samples")
                for (i in 0 until image.width * image.height * 4) {
                    val v = ((im[2 * i].toInt() and 255) shl 8) or (im[2 * i + 1].toInt() and 255)
                    assertEquals(case.rgba16[i], v, "${case.name}: ImageMagick reads sample ${i % 4} of pixel ${i / 4}")
                }
                checked++
            }
            println("PNG oracle: $checked files read by pngcheck, ImageIO, Pillow and ImageMagick")
        } finally {
            dir.deleteRecursively()
        }
    }
}
