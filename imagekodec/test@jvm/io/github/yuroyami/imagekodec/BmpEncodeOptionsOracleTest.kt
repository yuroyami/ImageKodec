package io.github.yuroyami.imagekodec

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The BMP writer's depths (#184) read by ImageIO and ImageMagick: every palette depth, raw and
 * run-length coded, and 16, 24 and 32 bits, must read to the pixels ImageKodec's decoder gives,
 * exactly, but for 16 bits, which each reader may spread from 5 bits to 8 its own way. ImageIO
 * always runs; ImageMagick skips cleanly when it is missing.
 */
class BmpEncodeOptionsOracleTest {

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

    private fun picture(w: Int, h: Int, colours: Int, seed: Long): KiteBitmap {
        val random = BilevelPages.Random(seed)
        val palette = IntArray(colours) { argb(255, (it * 97 + 3) and 0xFF, (it * 31 + 7) and 0xFF, (it * 13) and 0xFF) }
        return KiteBitmap(w, h, IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            when ((y + x / 11) % 3) {
                0 -> palette[(x / 5 + y) % colours]
                1 -> palette[if (x and 1 == 0) 0 else colours - 1]
                else -> palette[random.next(colours)]
            }
        })
    }

    private fun variants(): List<Pair<String, Pair<KiteBitmap, BmpEncodeOptions>>> {
        val out = ArrayList<Pair<String, Pair<KiteBitmap, BmpEncodeOptions>>>()
        for ((w, h) in listOf(1 to 1, 7 to 3, 37 to 23, 301 to 17)) {
            for ((bits, colours) in listOf(1 to 2, 4 to 16, 4 to 5, 8 to 200)) for (rle in listOf(false, true)) {
                if (rle && bits == 1) continue
                out += "$bits bits rle $rle ${w}x$h" to (picture(w, h, colours, (w + bits).toLong()) to BmpEncodeOptions(bits, rle))
            }
            out += "16 bits ${w}x$h" to (EncoderFixtures.card(w, h, alpha = false, gray = false) to BmpEncodeOptions(16))
            out += "24 bits ${w}x$h" to (EncoderFixtures.card(w, h, alpha = false, gray = false) to BmpEncodeOptions(24))
            out += "32 bits ${w}x$h" to (EncoderFixtures.card(w, h, alpha = true, gray = false) to BmpEncodeOptions(32))
        }
        return out
    }

    private fun assertClose(name: String, want: KiteBitmap, got: IntArray, slack: Int) {
        for (i in want.argb.indices) for (s in intArrayOf(0, 8, 16, 24)) {
            val d = kotlin.math.abs(((want.argb[i] ushr s) and 0xFF) - ((got[i] ushr s) and 0xFF))
            if (d > slack) throw AssertionError("$name: pixel $i is ${got[i].toUInt().toString(16)}, ImageKodec reads ${want.argb[i].toUInt().toString(16)}")
        }
    }

    @Test
    fun imageIoReadsEveryDepth() {
        for ((name, pair) in variants()) {
            val (src, options) = pair
            val data = ImageKodec.encodeBmp(src, options)
            val ours = ImageKodec.decode(data)
            if (options.bitsPerPixel != 16) assertEquals(src.argb.toList(), ours.argb.toList(), "$name: ImageKodec")
            val image = assertNotNull(ImageIO.read(ByteArrayInputStream(data)), "$name: ImageIO has no reader")
            assertEquals(src.width to src.height, image.width to image.height, "$name: size")
            val io = IntArray(src.width * src.height) { image.getRGB(it % src.width, it / src.width) }
            assertClose("$name: ImageIO", ours, io, if (options.bitsPerPixel == 16) 4 else 0)
        }
    }

    @Test
    fun imageMagickReadsEveryDepth() {
        assumeTrue("ImageMagick not installed", Tools.hasAll("magick"))
        for ((name, pair) in variants()) {
            val (src, options) = pair
            val data = ImageKodec.encodeBmp(src, options)
            val file = File.createTempFile("kite-bmp-options", ".bmp").apply { deleteOnExit(); writeBytes(data) }
            val (code, raw) = run(Tools.require("magick").path, "bmp:${file.path}", "-depth", "8", "rgba:-")
            assertEquals(0, code, "$name: ImageMagick could not read it")
            assertEquals(src.width * src.height * 4, raw.size, "$name: ImageMagick's size")
            val px = IntArray(src.width * src.height) {
                argb(raw[it * 4 + 3].toInt() and 255, raw[it * 4].toInt() and 255, raw[it * 4 + 1].toInt() and 255, raw[it * 4 + 2].toInt() and 255)
            }
            assertClose("$name: ImageMagick", ImageKodec.decode(data), px, if (options.bitsPerPixel == 16) 4 else 0)
        }
        assertTrue(variants().isNotEmpty())
    }
}
