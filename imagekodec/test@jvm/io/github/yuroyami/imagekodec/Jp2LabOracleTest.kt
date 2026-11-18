package io.github.yuroyami.imagekodec

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The CIELab colour space of a JP2 header (#187) against lcms2: JP2 files whose colr box says
 * CIELab, with the default parameters and with its own, decode to the sRGB that lcms2 gives for
 * the same L*a*b*, through a D50 Lab profile, relative colorimetric (`tools/icc_lcms.c
 * convert-lab`). The mapping of samples to L*a*b* is restated here from T.801, apart from the
 * decoder's. OpenJPEG 2.5.0's `opj_decompress` converts CIELab too, but to colours far from any
 * L*a*b* reading of the samples, so it is no oracle for it.
 */
class Jp2LabOracleTest {

    private val oracle: File? by lazy {
        val source = listOf(File("../tools/icc_lcms.c"), File("tools/icc_lcms.c")).firstOrNull { it.isFile } ?: return@lazy null
        val out = File.createTempFile("icc_lcms", ".bin").apply { deleteOnExit() }
        val cc = Tools.find("cc") ?: return@lazy null
        val process = ProcessBuilder(cc.path, "-O2", "-o", out.path, source.path, "-llcms2", "-lm").redirectErrorStream(true).start()
        val log = process.inputStream.readBytes().decodeToString()
        if (process.waitFor() != 0) {
            if (System.getenv(Tools.REQUIRE_ENV) == "1") error("could not build tools/icc_lcms.c against lcms2: $log")
            return@lazy null
        }
        out
    }

    /** lcms2's sRGB, 0 to 1 and unclipped, for each L*a*b* triple of [lab]. */
    private fun lcms(lab: DoubleArray): FloatArray {
        val input = ByteBuffer.allocate(lab.size * 8).order(ByteOrder.nativeOrder())
        for (v in lab) input.putDouble(v)
        val process = ProcessBuilder(oracle!!.path, "convert-lab").start()
        val writer = Thread { process.outputStream.use { it.write(input.array()) } }.apply { start() }
        val out = process.inputStream.readBytes()
        writer.join()
        assertEquals(0, process.waitFor(), process.errorStream.readBytes().decodeToString())
        val floats = ByteBuffer.wrap(out).order(ByteOrder.nativeOrder()).asFloatBuffer()
        return FloatArray(floats.remaining()).also { floats.get(it) }
    }

    /** [file] with its colr box's enumerated colour space set to CIELab (14), and [parameters] after it when given. */
    private fun asLab(file: ByteArray, parameters: LongArray?): ByteArray {
        val at = String(file, Charsets.ISO_8859_1).indexOf("colr") - 4
        val out = ByteArrayOutputStream()
        fun u32(v: Long) { for (s in intArrayOf(24, 16, 8, 0)) out.write((v ushr s).toInt() and 0xFF) }
        val extra = if (parameters == null) 0 else 28
        val jp2h = String(file, Charsets.ISO_8859_1).indexOf("jp2h") - 4
        out.write(file, 0, jp2h)
        u32(ByteBuffer.wrap(file, jp2h, 4).int.toLong() + extra)
        out.write(file, jp2h + 4, at - jp2h - 4)
        u32(15L + extra)
        out.write(file, at + 4, 7) // "colr", method 1, precedence, approximation
        u32(14)
        parameters?.forEach { u32(it) }
        out.write(file, at + 15, file.size - at - 15)
        return out.toByteArray()
    }

    /** The L*a*b* of samples [s] of [bits] bits under [parameters], or T.801's defaults. */
    private fun lab(s: IntArray, bits: Int, parameters: LongArray?): DoubleArray {
        val max = ((1L shl bits) - 1).toDouble()
        val p = parameters ?: longArrayOf(100, 0, 170, 1L shl (bits - 1), 200, (1L shl (bits - 2)) + (1L shl (bits - 3)), 0x00443530)
        return DoubleArray(3) { k -> p[2 * k] * (s[k] - p[2 * k + 1]) / max }
    }

    private fun srgbOf(v: Float, scale: Int): Int = (v.toDouble().coerceIn(0.0, 1.0) * scale + 0.5).toInt()

    @Test
    fun cielabDecodesToTheSrgbOfLcms2() {
        assumeTrue("lcms2 or a C compiler not installed", oracle != null)
        val w = 64
        val h = 48
        val random = kotlin.random.Random(187)
        for (bits in listOf(8, 12, 16)) {
            val max = (1 shl bits) - 1
            val raw = Array(w * h) { i ->
                // Every L* step along x; a* and b* across their range, with noise.
                intArrayOf(
                    (i % w) * max / (w - 1),
                    ((i / w) * max / (h - 1) + random.nextInt(-max / 8, max / 8)).coerceIn(0, max),
                    random.nextInt(0, max + 1),
                )
            }
            val samples = ShortArray(w * h * 3) { j -> raw[j / 3][j % 3].let { v -> if (bits == 16) v else (v shl (16 - bits)) or (v shr (2 * bits - 16)) }.toShort() }
            val plain = ImageKodec.encodeJp2Wide(KiteBitmap16(w, h, 3, samples), Jp2EncodeOptions(lossless = true, bitsPerSample = bits, levels = 3))
            for (parameters in listOf(null, longArrayOf(100, 16, 255, 1L shl (bits - 1), 220, 1L shl (bits - 1), 0x00443635))) {
                val file = asLab(plain, parameters)
                val info = ImageKodec.probe(file)
                assertTrue(info.isDecodable, "${info.unsupportedReason}")
                val expected = lcms(DoubleArray(w * h * 3).also { all -> for (i in 0 until w * h) lab(raw[i], bits, parameters).copyInto(all, i * 3) })
                val narrow = ImageKodec.decode(file).argb
                val wide = ImageKodec.decode16(file).samples
                var worst8 = 0
                var worst16 = 0
                var sum8 = 0L
                for (i in 0 until w * h) for (k in 0 until 3) {
                    val d8 = kotlin.math.abs(((narrow[i] ushr (16 - 8 * k)) and 0xFF) - srgbOf(expected[i * 3 + k], 255))
                    val d16 = kotlin.math.abs((wide[i * 3 + k].toInt() and 0xFFFF) - srgbOf(expected[i * 3 + k], 65535))
                    worst8 = maxOf(worst8, d8)
                    worst16 = maxOf(worst16, d16)
                    sum8 += d8
                }
                val tag = "CIELab $bits bits, ${if (parameters == null) "default parameters" else "its own parameters"}"
                println("[#187] $tag against lcms2: worst 8-bit difference $worst8, mean ${"%.4f".format(sum8.toDouble() / (w * h * 3))}, worst 16-bit difference $worst16")
                assertTrue(worst8 <= 1, "$tag: an 8-bit sample differs by $worst8")
                // decode16 keeps each sample inside the 8-bit level decode gives, which moves it by up to 255.
                assertTrue(worst16 <= 256, "$tag: a 16-bit sample differs by $worst16")
            }
        }
    }
}
