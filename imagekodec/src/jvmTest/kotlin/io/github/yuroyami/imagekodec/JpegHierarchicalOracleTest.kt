package io.github.yuroyami.imagekodec

import org.junit.Assume.assumeTrue
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hierarchical JPEG (#19) against the T.81 reference software, `t81jpeg` (see
 * [JpegLosslessOracleTest]), which writes every kind: lossless pyramids, DCT pyramids sequential
 * and progressive, Huffman and arithmetic, subsampled, and a DCT frame finished by a lossless one.
 *
 * Its decoder is held to within a bound, not exactly, and each bound has a cause. It keeps a
 * frame's samples in fixed point on their way to the next frame, where T.81 hands on integer
 * samples: doubling a reference, it averages the exact half samples of the horizontal pass where
 * T.81 truncates them first (J.1.1.2), so a lossless pyramid differs by 1 at most; and it adds a
 * final lossless frame to the DCT frame's fixed-point output cut down, where an integer decoder
 * rounds it, which with the two inverse DCTs' own rounding moves a sample by up to 10. Its chroma
 * upsampling differs from ours at a padded edge. It cannot read back its own lossless pyramids at
 * 10 or 16 bits, so those come from `JpegHierarchicalTest`, which holds lossless pyramids written
 * to the letter of Annex J to an exact round trip at every precision.
 */
class JpegHierarchicalOracleTest {

    private fun run(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val log = process.inputStream.readBytes().decodeToString()
        assertEquals(0, process.waitFor(), "${command.first()} failed: $log")
        return log
    }

    /** A smooth picture with edges, [channels] samples a pixel of [bits] bits, as a PGM or PPM. */
    private fun pnm(w: Int, h: Int, channels: Int, bits: Int): ByteArray {
        val max = (1 shl bits) - 1
        val out = ByteArrayOutputStream()
        out.write("${if (channels == 1) "P5" else "P6"}\n$w $h\n$max\n".encodeToByteArray())
        for (y in 0 until h) for (x in 0 until w) for (c in 0 until channels) {
            val shade = 0.5 + 0.3 * sin(x / 3.0 + c) + 0.15 * cos(y / 2.5) + (if ((x / 6 + y / 5) % 2 == 0) 0.05 else -0.05)
            val v = (shade * max).toInt().coerceIn(0, max)
            if (bits > 8) out.write(v ushr 8)
            out.write(v and 0xFF)
        }
        return out.toByteArray()
    }

    /** The samples of a PGM or PPM, 2 bytes each above 8 bits. */
    private fun samples(pnm: ByteArray, count: Int, bits: Int): IntArray {
        val bytes = if (bits > 8) 2 else 1
        val start = pnm.size - count * bytes
        return IntArray(count) { i ->
            if (bits > 8) ((pnm[start + 2 * i].toInt() and 0xFF) shl 8) or (pnm[start + 2 * i + 1].toInt() and 0xFF)
            else pnm[start + i].toInt() and 0xFF
        }
    }

    @Test
    fun hierarchiesMatchTheReferenceDecoderWithinTheirBounds() {
        assumeTrue("t81jpeg is not installed", Tools.hasAll("t81jpeg"))
        val dir = Files.createTempDirectory("imagekodec-hierarchical").toFile()
        try {
            // The largest difference a sample may show, and the mean, in levels.
            data class Case(val options: String, val channels: Int, val bits: Int, val worst: Int, val mean: Int = 1)
            val cases = listOf(
                Case("-p -a -y 3 -c", 3, 8, 1),
                Case("-p -h -y 2 -c", 3, 8, 1),
                Case("-p -a -y 2 -c", 1, 12, 1),
                Case("-p -a -y 3 -c", 1, 12, 1),
                Case("-q 80 -h -y 2", 3, 8, 3),
                Case("-q 80 -a -y 3", 1, 8, 3),
                Case("-q 80 -h -v -y 2", 3, 8, 3),
                Case("-q 80 -a -v -y 2", 3, 8, 3),
                Case("-q 80 -h -y 1", 3, 8, 3),
                Case("-q 75 -a -y 3 -s 1x1,2x2,2x2", 3, 8, 8, mean = 2),
                Case("-q 90 -h -y 0 -c", 3, 8, 10),
                Case("-q 95 -a -y 0 -c", 1, 8, 10),
                Case("-q 85 -h -y 2", 1, 12, 48),
                Case("-q 90 -a -y 0 -c", 1, 12, 160),
            )
            for ((w, h) in listOf(21 to 13, 32 to 24)) for (case in cases) {
                val name = "${w}x$h ${case.options}, ${case.bits} bits"
                val ext = if (case.channels == 1) "pgm" else "ppm"
                val input = File(dir, "in.$ext").apply { writeBytes(pnm(w, h, case.channels, case.bits)) }
                val jpeg = File(dir, "out.jpg")
                val reference = File(dir, "ref.$ext")
                run(Tools.require("t81jpeg").path, *case.options.split(" ").toTypedArray(), input.path, jpeg.path)
                assertTrue(jpeg.length() > 0, "$name: t81jpeg wrote nothing")
                reference.delete()
                run(Tools.require("t81jpeg").path, jpeg.path, reference.path)
                assertTrue(reference.length() > 0, "$name: t81jpeg could not read its own file")
                val theirs = samples(reference.readBytes(), w * h * case.channels, case.bits)
                val bytes = jpeg.readBytes()
                val info = ImageKodec.probe(bytes)
                assertTrue(info.isDecodable, "$name: ${info.unsupportedReason}")
                assertEquals(w, info.width, name)
                assertEquals(h, info.height, name)
                val ours = ImageKodec.decode16(bytes)
                var worst = 0
                var total = 0L
                for (i in 0 until w * h) for (c in 0 until case.channels) {
                    val v = (ours.samples[i * ours.channels + c].toInt() and 0xFFFF) shr (16 - case.bits)
                    val d = abs(v - theirs[i * case.channels + c])
                    worst = maxOf(worst, d)
                    total += d
                }
                assertTrue(worst <= case.worst, "$name: a sample differs from the reference decoder by $worst")
                assertTrue(total <= w * h * case.channels * case.mean, "$name: the samples differ by $total in all")
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
