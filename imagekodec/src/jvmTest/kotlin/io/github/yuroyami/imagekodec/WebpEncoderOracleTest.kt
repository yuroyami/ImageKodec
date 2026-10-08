package io.github.yuroyami.imagekodec

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The lossless WebP writer (#12) read by libwebp: `dwebp` must decode every file to the source's
 * pixels exactly, and each must be smaller than ImageKodec's PNG of the same image. Skips cleanly
 * without dwebp and ffmpeg.
 */
class WebpEncoderOracleTest {

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

    private fun temp(ext: String) = File.createTempFile("kite-webp-out", ext).apply { deleteOnExit() }

    /** A frame of one of ffmpeg's test sources, as RGBA. */
    private fun source(graph: String): KiteBitmap {
        val png = temp(".png")
        assertEquals(0, run(Tools.require("ffmpeg").path, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", graph, "-frames:v", "1", png.path).first, graph)
        return ImageKodec.decode(png.readBytes())
    }

    private fun check(name: String, b: KiteBitmap) {
        val webp = temp(".webp").apply { writeBytes(ImageKodec.encodeWebp(b)) }
        val pam = temp(".pam")
        val (code, _) = run(Tools.require("dwebp").path, "-quiet", webp.path, "-pam", "-o", pam.path)
        assertEquals(0, code, "$name: dwebp could not decode")
        val bytes = pam.readBytes()
        // The PAM header ends with ENDHDR and a newline; RGBA samples follow.
        val header = bytes.decodeToString(0, minOf(bytes.size, 200))
        val start = header.indexOf("ENDHDR\n") + 7
        assertEquals(b.width * b.height * 4, bytes.size - start, "$name: dwebp's size")
        for (i in b.argb.indices) {
            val o = start + i * 4
            val px = argb(bytes[o + 3].toInt() and 255, bytes[o].toInt() and 255, bytes[o + 1].toInt() and 255, bytes[o + 2].toInt() and 255)
            if (px != b.argb[i]) throw AssertionError("$name: dwebp reads pixel $i as ${px.toUInt().toString(16)}, written ${b.argb[i].toUInt().toString(16)}")
        }
        assertTrue(webp.length() < ImageKodec.encodePng(b).size, "$name: ${webp.length()} bytes is not smaller than the PNG")
    }

    @Test
    fun dwebpReadsEveryKindOfImage() {
        assumeTrue("dwebp or ffmpeg not installed", Tools.hasAll("dwebp", "ffmpeg"))
        check("fractal", source("mandelbrot=s=400x300"))
        check("test pattern", source("testsrc2=s=320x240"))
        check("colour bars, a palette", source("smptebars=s=200x120"))
        check("gradient", source("gradients=s=300x200:c0=red:c1=blue"))
        check("translucent", source("mandelbrot=s=257x131,format=rgba,geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':a='X'"))
        check("odd size", source("testsrc=s=203x117"))
    }
}
