package io.github.yuroyami.imagekodec

import org.junit.Assume.assumeTrue
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 12-bit DCT JPEG (#109) against libjpeg-turbo 3.1, built from source in CI as `cjpeg3` and
 * `djpeg3`, since the packaged 2.1 writes 8 bits only. The inverse DCT is libjpeg's
 * `jpeg_idct_islow` at 12 bits, so where no colour conversion or upsampling stands between
 * them, a gray file or an RGB one stored without a transform, our samples must equal
 * `djpeg -dct int`'s exactly. Converted from YCbCr they differ by the two conversions' rounding,
 * and subsampled by the two upsamplers' too. Every process is covered: Huffman and arithmetic,
 * sequential and progressive, with restarts.
 */
class JpegTwelveBitOracleTest {

    private fun run(vararg command: String, stdout: File? = null): String {
        val builder = ProcessBuilder(*command)
        if (stdout != null) builder.redirectOutput(stdout).redirectError(ProcessBuilder.Redirect.PIPE) else builder.redirectErrorStream(true)
        val process = builder.start()
        val log = (if (stdout != null) process.errorStream else process.inputStream).readBytes().decodeToString()
        assertEquals(0, process.waitFor(), "${command.first()} failed: $log")
        return log
    }

    /** A 12-bit picture as a PGM or PPM: shading, edges and noise. */
    private fun pnm(w: Int, h: Int, channels: Int, seed: Int): ByteArray {
        val random = Random(seed)
        val out = ByteArrayOutputStream()
        out.write("${if (channels == 1) "P5" else "P6"}\n$w $h\n4095\n".encodeToByteArray())
        for (y in 0 until h) for (x in 0 until w) for (c in 0 until channels) {
            val shade = 2048 + 1300 * sin(x / 4.0 + c) + 500 * cos(y / 3.0) + (if ((x / 7 + y / 5) % 2 == 0) 160 else -160)
            val v = (shade + random.nextInt(-60, 61)).toInt().coerceIn(0, 4095)
            out.write(v ushr 8)
            out.write(v and 0xFF)
        }
        return out.toByteArray()
    }

    private fun samples(pnm: ByteArray, count: Int): IntArray {
        val start = pnm.size - 2 * count
        return IntArray(count) { ((pnm[start + 2 * it].toInt() and 0xFF) shl 8) or (pnm[start + 2 * it + 1].toInt() and 0xFF) }
    }

    @Test
    fun twelveBitFilesMatchLibjpegTurbo() {
        assumeTrue("libjpeg-turbo 3 (cjpeg3, djpeg3) is not installed", Tools.hasAll("cjpeg3", "djpeg3"))
        val dir = Files.createTempDirectory("imagekodec-12bit").toFile()
        try {
            val w = 45
            val h = 27
            data class Case(val channels: Int, val options: List<String>, val tolerance: Int)
            val cases = listOf(
                Case(1, listOf("-quality", "90"), 0),
                Case(1, listOf("-quality", "60", "-progressive"), 0),
                Case(1, listOf("-quality", "95", "-arithmetic", "-restart", "2B"), 0),
                Case(1, listOf("-quality", "75", "-arithmetic", "-progressive"), 0),
                Case(3, listOf("-quality", "85", "-rgb"), 0),
                Case(3, listOf("-quality", "70", "-rgb", "-progressive", "-arithmetic"), 0),
                Case(3, listOf("-quality", "90", "-sample", "1x1,1x1,1x1"), 1),
                Case(3, listOf("-quality", "80", "-progressive", "-sample", "1x1,1x1,1x1", "-restart", "1"), 1),
                Case(3, listOf("-quality", "85"), 2),
                Case(3, listOf("-quality", "85", "-arithmetic", "-sample", "2x1,1x1,1x1"), 2),
            )
            for (case in cases) {
                val name = "${case.channels} channels ${case.options.joinToString(" ")}"
                val input = File(dir, if (case.channels == 1) "in.pgm" else "in.ppm").apply { writeBytes(pnm(w, h, case.channels, case.options.size)) }
                val jpeg = File(dir, "out.jpg")
                run(Tools.require("cjpeg3").path, "-precision", "12", *case.options.toTypedArray(), "-outfile", jpeg.path, input.path)
                val ref = File(dir, "ref.pnm")
                run(Tools.require("djpeg3").path, "-dct", "int", "-pnm", "-outfile", ref.path, jpeg.path)
                val theirs = samples(ref.readBytes(), w * h * case.channels)
                val bytes = jpeg.readBytes()
                val info = ImageKodec.probe(bytes)
                assertTrue(info.isDecodable, "$name: ${info.unsupportedReason}")
                assertEquals(12, info.bitDepth, name)
                val wide = ImageKodec.decode16(bytes)
                assertEquals(case.channels, wide.channels, name)
                var worst = 0
                for (i in 0 until w * h * case.channels) {
                    val ours = (wide.samples[i].toInt() and 0xFFFF) shr 4
                    worst = maxOf(worst, abs(ours - theirs[i]))
                }
                assertTrue(worst <= case.tolerance, "$name: a sample differs from djpeg's by $worst")
                val narrow = ImageKodec.decode(bytes)
                assertTrue(narrow.argb.contentEquals(wide.toBitmap().argb), "$name: decode is not decode16's high bytes")
                assertTrue(ImageKodec.decodeReduced(bytes, 4).argb.contentEquals(narrow.reducedBy(4).argb), "$name: reduced")
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
