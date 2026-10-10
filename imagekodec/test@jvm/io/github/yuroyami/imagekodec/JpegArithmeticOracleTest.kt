package io.github.yuroyami.imagekodec

import org.junit.Assume.assumeTrue
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Arithmetic-coded JPEG (#19) against the Huffman file it was made from. `jpegtran -arithmetic`
 * and `tools/arith_jpeg.c` re-encode a file's quantized coefficients without touching them, so
 * the arithmetic file must decode to exactly the pixels of the Huffman one, reduced decodes and
 * components included: any difference is the entropy decoder's. `arith_jpeg` also sets the DAC
 * conditioning, which jpegtran leaves at its defaults. djpeg decoding both files the same is
 * checked too, so a test tool that changed the coefficients could not pass unnoticed.
 */
class JpegArithmeticOracleTest {

    private fun run(vararg command: String, stdin: File? = null, stdout: File? = null): String {
        val builder = ProcessBuilder(*command)
        if (stdin != null) builder.redirectInput(stdin)
        if (stdout != null) builder.redirectOutput(stdout) else builder.redirectErrorStream(true)
        if (stdout != null) builder.redirectError(ProcessBuilder.Redirect.PIPE)
        val process = builder.start()
        val log = (if (stdout != null) process.errorStream else process.inputStream).readBytes().decodeToString()
        assertEquals(0, process.waitFor(), "${command.first()} failed: $log")
        return log
    }

    /** The libjpeg transcoder, built once from `tools/arith_jpeg.c`. */
    private val transcoder: File? by lazy {
        val source = listOf(File("../tools/arith_jpeg.c"), File("tools/arith_jpeg.c")).firstOrNull { it.isFile } ?: return@lazy null
        val out = File.createTempFile("arith_jpeg", ".bin").apply { deleteOnExit() }
        val process = ProcessBuilder(Tools.require("cc").path, "-O2", "-o", out.path, source.path, "-ljpeg")
            .redirectErrorStream(true).start()
        val log = process.inputStream.readBytes().decodeToString()
        if (process.waitFor() != 0) {
            if (System.getenv(Tools.REQUIRE_ENV) == "1") error("could not build tools/arith_jpeg.c against libjpeg: $log")
            return@lazy null
        }
        out
    }

    /** A photo-like picture as a binary PPM: shading, texture, hard edges and noise, [w] by [h]. */
    private fun ppm(w: Int, h: Int, seed: Int): ByteArray {
        val random = Random(seed)
        val out = ByteArrayOutputStream()
        out.write("P6\n$w $h\n255\n".encodeToByteArray())
        for (y in 0 until h) for (x in 0 until w) {
            val shade = (sin(x / 11.0) * 60 + sin(y / 17.0) * 50 + 128).toInt()
            val edge = if ((x / 13 + y / 9) % 2 == 0) 50 else -50
            val noise = random.nextInt(-20, 21)
            out.write((shade + edge + noise).coerceIn(0, 255))
            out.write((x * 255 / w + noise).coerceIn(0, 255))
            out.write((y * 255 / h - edge / 2 + noise / 2).coerceIn(0, 255))
        }
        return out.toByteArray()
    }

    private fun cjpeg(ppm: File, dir: File, vararg options: String): File {
        val out = File(dir, "huffman.jpg")
        run(Tools.require("cjpeg").path, *options, "-outfile", out.path, ppm.path)
        return out
    }

    private fun djpeg(jpeg: File, dir: File): ByteArray {
        val out = File(dir, "djpeg.pnm")
        run(Tools.require("djpeg").path, "-pnm", "-outfile", out.path, jpeg.path)
        return out.readBytes()
    }

    private fun sameDecode(name: String, huffman: ByteArray, arithmetic: ByteArray) {
        val info = ImageKodec.probe(arithmetic)
        assertTrue(info.isDecodable, "$name: ${info.unsupportedReason}")
        for (reduction in listOf(1, 2, 4, 8)) {
            val expected = ImageKodec.decodeReduced(huffman, reduction)
            val actual = ImageKodec.decodeReduced(arithmetic, reduction)
            assertEquals(expected.width, actual.width, "$name 1/$reduction: width")
            assertEquals(expected.height, actual.height, "$name 1/$reduction: height")
            val at = expected.argb.indices.firstOrNull { expected.argb[it] != actual.argb[it] }
            assertEquals(null, at, "$name 1/$reduction: first pixel that differs from the Huffman decode")
        }
        assertContentEquals(
            ImageKodec.decodeJpegComponents(huffman).samples,
            ImageKodec.decodeJpegComponents(arithmetic).samples,
            "$name: components",
        )
    }

    @Test
    fun arithmeticFilesDecodeToTheirHuffmanOriginals() {
        assumeTrue("cjpeg, djpeg, jpegtran or cc is not installed", Tools.hasAll("cjpeg", "djpeg", "jpegtran", "cc"))
        assumeTrue("tools/arith_jpeg.c did not build", transcoder != null)
        val dir = Files.createTempDirectory("imagekodec-arith").toFile()
        try {
            val sources = listOf(
                "77x53" to File(dir, "a.ppm").apply { writeBytes(ppm(77, 53, 1)) },
                "160x120" to File(dir, "b.ppm").apply { writeBytes(ppm(160, 120, 2)) },
            )
            val encodings = listOf(
                "4:2:0 q90" to arrayOf("-quality", "90"),
                "4:4:4 q100" to arrayOf("-quality", "100", "-sample", "1x1,1x1,1x1"),
                "4:2:2 q75" to arrayOf("-quality", "75", "-sample", "2x1,1x1,1x1"),
                "4:4:0 q85" to arrayOf("-quality", "85", "-sample", "1x2,1x1,1x1"),
                "4:1:1 q60" to arrayOf("-quality", "60", "-sample", "4x1,1x1,1x1"),
                "gray q95" to arrayOf("-quality", "95", "-grayscale"),
                "q20" to arrayOf("-quality", "20"),
            )
            // jpegtran's own option sets, and arith_jpeg's: L U K restart progressive.
            val transcodes = listOf(
                "sequential" to listOf("-arithmetic"),
                "progressive" to listOf("-arithmetic", "-progressive"),
                "sequential, restart 3" to listOf("-arithmetic", "-restart", "3B"),
                "progressive, restart 1" to listOf("-arithmetic", "-progressive", "-restart", "1B"),
                "conditioning 2 6 20" to listOf("2", "6", "20", "0", "0"),
                "conditioning 0 0 1, progressive, restart 5" to listOf("0", "0", "1", "5", "1"),
                "conditioning 15 15 63" to listOf("15", "15", "63", "2", "0"),
                "conditioning 1 5 3, progressive" to listOf("1", "5", "3", "0", "1"),
            )
            var cases = 0
            for ((size, ppm) in sources) for ((encoding, options) in encodings) {
                val huffman = cjpeg(ppm, dir, *options)
                val pixels = djpeg(huffman, dir)
                for ((transcode, args) in transcodes) {
                    val name = "$size $encoding, $transcode"
                    val arithmetic = File(dir, "arith.jpg")
                    if (args.first() == "-arithmetic") {
                        run(Tools.require("jpegtran").path, *args.toTypedArray(), "-outfile", arithmetic.path, huffman.path)
                    } else {
                        run(transcoder!!.path, *args.toTypedArray(), stdin = huffman, stdout = arithmetic)
                    }
                    assertContentEquals(pixels, djpeg(arithmetic, dir), "$name: the transcode changed djpeg's pixels")
                    sameDecode(name, huffman.readBytes(), arithmetic.readBytes())
                    cases++
                }
            }
            assertEquals(sources.size * encodings.size * transcodes.size, cases)
        } finally {
            dir.deleteRecursively()
        }
    }
}
