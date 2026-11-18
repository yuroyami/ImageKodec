package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.jxr.JxrDecoder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JPEG XR's 32bppRGBE (#189), written by `JxrEncApp` from a flat Radiance file and read by
 * `JxrDecApp`: the four samples of each pixel must be equal to JxrDecApp's, lossless and lossy.
 */
class JxrRgbeOracleTest {

    private fun run(vararg args: String): Pair<Int, String> {
        val proc = ProcessBuilder(args.toList()).redirectErrorStream(true).start()
        val out = ByteArrayOutputStream()
        proc.inputStream.copyTo(out)
        if (!proc.waitFor(300, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return -1 to ""
        }
        return proc.exitValue() to out.toString()
    }

    private fun temp(ext: String) = File.createTempFile("kite-rgbe", ext).apply { deleteOnExit() }

    /**
     * A flat Radiance picture: scanlines that do not start with 2, 2 are read unencoded. Its pixels
     * run over many exponents, low ones and zero among them, with mantissas both above and below 128.
     */
    private fun hdr(w: Int, h: Int, seed: Int): File {
        val out = ByteArrayOutputStream()
        out.write("#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y $h +X $w\n".encodeToByteArray())
        val random = kotlin.random.Random(seed)
        for (y in 0 until h) for (x in 0 until w) {
            val e = when ((x / 7 + y / 5) % 4) {
                0 -> 0
                1 -> 1 + random.nextInt(3)
                else -> 110 + (x + y) % 30
            }
            val m = IntArray(3) { c -> if (e == 0) 0 else (x * (c + 3) + y * 7 + random.nextInt(9)) and 255 }
            // The largest channel holds the exponent, so its mantissa is at least 128.
            m[(x + y) % 3] = m[(x + y) % 3] or (if (e == 0) 0 else 128)
            out.write(byteArrayOf(m[0].toByte(), m[1].toByte(), m[2].toByte(), e.toByte()))
        }
        return temp(".hdr").apply { writeBytes(out.toByteArray()) }
    }

    /** The samples JxrDecApp writes to a TIFF of one strip, little endian, 8 bits each. */
    private fun decApp(jxr: File, vararg options: String): Pair<Int, ByteArray> {
        val out = temp(".tif")
        val (code, text) = run(Tools.require("JxrDecApp").path, "-i", jxr.path, "-o", out.path, *options)
        assertEquals(0, code, "JxrDecApp failed: $text")
        val bytes = out.readBytes()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val ifd = b.getInt(4)
        var start = 0
        var spp = 1
        for (i in 0 until b.getShort(ifd).toInt()) {
            val at = ifd + 2 + 12 * i
            val tag = b.getShort(at).toInt() and 0xFFFF
            val count = b.getInt(at + 4)
            if (tag == 273) start = if (count == 1) b.getInt(at + 8) else b.getInt(b.getInt(at + 8))
            if (tag == 277) spp = b.getShort(at + 8).toInt()
        }
        return spp to bytes.copyOfRange(start, bytes.size)
    }

    @Test
    fun rgbeMatchesJxrDecApp() {
        assumeTrue("jxrlib's tools are not installed", Tools.hasAll("JxrEncApp", "JxrDecApp"))
        val reports = ArrayList<String>()
        for ((name, options) in listOf(
            "lossless" to listOf("-q", "1"),
            "lossless spatial tiles" to listOf("-q", "1", "-f", "-U", "2", "2"),
            "q0.9" to listOf("-q", "0.9"),
            "q0.5 overlap 2" to listOf("-q", "0.5", "-l", "2"),
            "q20" to listOf("-q", "20"),
        )) {
            val w = 61
            val h = 37
            val jxr = temp(".jxr")
            val (code, text) = run(Tools.require("JxrEncApp").path, "-i", hdr(w, h, name.length).path, "-o", jxr.path, "-c", "16", *options.toTypedArray())
            assertTrue(code == 0 && jxr.length() > 0, "JxrEncApp $name failed: $text")
            val bytes = jxr.readBytes()
            assertTrue(ImageKodec.probe(bytes).isDecodable, "$name: ${ImageKodec.probe(bytes).unsupportedReason}")
            val (spp, want) = decApp(jxr)
            assertEquals(4, spp)
            val got = JxrDecoder.decodeRaw(bytes).raster.samples
            for (i in 0 until w * h * 4) {
                assertEquals(want[i].toInt() and 0xFF, got[i], "$name: sample ${i % 4} of pixel ${i / 4}")
            }
            // JxrDecApp's 24bppRGB of an RGBE file does not follow from its own RGBE samples (pixels
            // of ordinary exponents come out white or black), so the picture is held to the formula of
            // jxrlib's RGBE_RGB96Float, a mantissa times 2^(E - 136), and the sRGB curve, restated here.
            val wide = ImageKodec.decode16(bytes)
            var worst = 0
            for (i in 0 until w * h) for (c in 0 until 3) {
                val e = got[i * 4 + 3]
                val linear = if (e == 0) 0.0 else got[i * 4 + c] * Math.pow(2.0, e - 136.0)
                val encoded = if (linear <= 0.0031308) linear * 12.92 else 1.055 * Math.pow(linear, 1 / 2.4) - 0.055
                val want = (encoded.coerceIn(0.0, 1.0) * 65535 + 0.5).toInt()
                worst = maxOf(worst, abs(want - (wide.samples[i * 3 + c].toInt() and 0xFFFF)))
            }
            assertTrue(worst <= 1, "$name: decode16 is $worst from the RGBE formula")
            reports += "$name: ${w * h * 4} RGBE samples equal to JxrDecApp's, decode16 within $worst of the formula"
        }
        println(reports.joinToString("\n"))
    }
}
