package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.JpegDecoder
import org.junit.Assume.assumeTrue
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Lossless JPEG (#19), Huffman (SOF3) and arithmetic (SOF11), against two encoders and ffmpeg's
 * decoder. A lossless codec gives back what went in, so the source itself is the oracle.
 *
 * `t81jpeg` is the `jpeg` tool of Thomas Richter's libjpeg, the T.81 and ISO/IEC 18477 reference
 * software, built at release 1.70 with `tools/t81-libjpeg.patch`, which lets the environment pick
 * the predictor and the point transform. It writes every precision from 2 to 16 bits, both
 * codings and restart intervals, including ones that end in the middle of a row. With a point
 * transform it starts its prediction at 2^(P-1) rather than T.81's 2^(P-Pt-1) (H.1.2.1), in its
 * encoder and its decoder alike, so its file does not decode to its source by T.81. ffmpeg's
 * decoder, which follows T.81 there as libjpeg-turbo does, gives the samples such a file holds,
 * and the arithmetic file must give the same samples as the Huffman one, since t81jpeg codes the
 * same differences in both. ffmpeg's `ljpeg` encoder writes the subsampled YCbCr of video.
 */
class JpegLosslessOracleTest {

    private fun run(vararg command: String, env: Map<String, String> = emptyMap()): String {
        val builder = ProcessBuilder(*command).redirectErrorStream(true)
        builder.environment().putAll(env)
        val process = builder.start()
        val log = process.inputStream.readBytes().decodeToString()
        assertEquals(0, process.waitFor(), "${command.first()} failed: $log")
        return log
    }

    /** A [w] by [h] picture of [channels] samples of [bits] bits: ramps, edges and noise reaching both ends. */
    private fun source(w: Int, h: Int, channels: Int, bits: Int, seed: Int): IntArray {
        val max = (1 shl bits) - 1
        val random = Random(seed)
        return IntArray(w * h * channels) { i ->
            val c = i % channels
            val x = (i / channels) % w
            val y = (i / channels) / w
            val ramp = (x.toLong() * max / (w - 1) + y.toLong() * max / (3 * (h - 1)) * (c + 1)).toInt()
            val edge = if ((x / 5 + y / 4 + c) % 3 == 0) max / 2 else 0
            when {
                (x + y) % 17 == 0 -> 0
                (x * y) % 23 == 1 -> max
                else -> ((ramp + edge + random.nextInt(0, max / 16 + 2)) % (max + 1))
            }
        }
    }

    /** [samples] as a binary PGM or PPM, 2 bytes a sample above 8 bits. */
    private fun pnm(samples: IntArray, w: Int, h: Int, channels: Int, bits: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("${if (channels == 1) "P5" else "P6"}\n$w $h\n${(1 shl bits) - 1}\n".encodeToByteArray())
        for (v in samples) {
            if (bits > 8) out.write(v ushr 8)
            out.write(v and 0xFF)
        }
        return out.toByteArray()
    }

    private fun t81(input: File, output: File, predictor: Int, pointTransform: Int, vararg options: String) {
        run(
            Tools.require("t81jpeg").path, *options, input.path, output.path,
            env = mapOf("T81_PREDICTOR" to "$predictor", "T81_POINT_TRANSFORM" to "$pointTransform"),
        )
        assertTrue(output.length() > 0, "t81jpeg wrote nothing for ${options.joinToString(" ")}")
    }

    /**
     * ffmpeg's decode of the gray lossless [jpeg] of [bits] bits, as the samples themselves: ffmpeg
     * shifts them up to fill its 8- or 16-bit pixel format.
     */
    private fun ffmpegGray(jpeg: File, bits: Int, dir: File): IntArray {
        val raw = File(dir, "ffmpeg.gray")
        val format = if (bits > 8) "gray16le" else "gray"
        run(Tools.require("ffmpeg").path, "-v", "error", "-y", "-i", jpeg.path, "-f", "rawvideo", "-pix_fmt", format, raw.path)
        val d = raw.readBytes()
        return if (bits > 8) {
            IntArray(d.size / 2) { ((d[2 * it].toInt() and 0xFF) or ((d[2 * it + 1].toInt() and 0xFF) shl 8)) shr (16 - bits) }
        } else {
            IntArray(d.size) { (d[it].toInt() and 0xFF) shr (8 - bits) }
        }
    }

    /** [v] of [bits] bits as `decode16` widens it. */
    private fun widened(v: Int, bits: Int): Int = when {
        bits >= 8 -> (v shl (16 - bits)) or (v shr (2 * bits - 16).coerceAtLeast(0))
        else -> v * 255 / ((1 shl bits) - 1) * 257
    }

    /** [v] of [bits] bits as `decode` narrows it. */
    private fun narrowed(v: Int, bits: Int): Int = if (bits >= 8) v shr (bits - 8) else v * 255 / ((1 shl bits) - 1)

    /** [jpeg] must decode to [want], gray samples of [bits] bits, through `decode16` and `decode` alike. */
    private fun checkGray(name: String, jpeg: ByteArray, want: IntArray, w: Int, h: Int, bits: Int) {
        val info = ImageKodec.probe(jpeg)
        assertTrue(info.isDecodable, "$name: ${info.unsupportedReason}")
        assertEquals(bits, info.bitDepth, name)
        val wide = ImageKodec.decode16(jpeg)
        val narrow = ImageKodec.decode(jpeg)
        assertEquals(w, wide.width, name)
        assertEquals(h, wide.height, name)
        for (i in 0 until w * h) {
            val got16 = wide.samples[i * wide.channels].toInt() and 0xFFFF
            assertEquals(widened(want[i], bits), got16, "$name: sample $i of decode16")
            assertEquals(narrowed(want[i], bits), narrow.argb[i] and 0xFF, "$name: sample $i of decode")
        }
        assertTrue(narrow.argb.contentEquals(wide.toBitmap().argb), "$name: decode is not decode16's high bytes")
    }

    @Test
    fun grayAtEveryPrecisionPredictorAndCoding() {
        assumeTrue("t81jpeg or ffmpeg is not installed", Tools.hasAll("t81jpeg", "ffmpeg"))
        val dir = Files.createTempDirectory("imagekodec-lossless").toFile()
        try {
            val w = 37
            val h = 29
            var cases = 0
            for (bits in listOf(2, 5, 8, 10, 12, 16)) {
                val src = source(w, h, 1, bits, bits)
                val input = File(dir, "in.pgm").apply { writeBytes(pnm(src, w, h, 1, bits)) }
                for (predictor in 1..7) {
                    // Whole rows between restarts, rows and a part, a part of a row, and none. A restart
                    // inside a row is t81jpeg's own reading, so those files carry no point transform,
                    // whose samples come from ffmpeg.
                    val restart = when (predictor) {
                        2 -> arrayOf("-z", "$w")
                        5 -> arrayOf("-z", "${w + 9}")
                        6 -> arrayOf("-z", "11")
                        else -> emptyArray()
                    }
                    // t81jpeg's first prediction sends T.81's reading of a file with a point transform
                    // outside the sample range, where the averaging predictors, and at 16 bits the shift
                    // itself, give each decoder its own wrap. So only predictors 1 to 4 below 16 bits
                    // take one here, and JpegLosslessTest writes conformant ones at every predictor.
                    val pt = when {
                        bits == 16 -> 0
                        predictor == 1 -> 1
                        predictor == 3 -> bits - 1
                        else -> 0
                    }
                    val huffman = File(dir, "huffman.jpg").also { t81(input, it, predictor, pt, "-p", "-c", *restart) }
                    val arithmetic = File(dir, "arithmetic.jpg").also { t81(input, it, predictor, pt, "-p", "-c", "-a", *restart) }
                    val name = "$bits bits, predictor $predictor, Pt $pt ${restart.joinToString(" ")}"
                    val want = if (pt == 0) src else ffmpegGray(huffman, bits, dir)
                    // ffmpeg reads a restart inside a row otherwise, so it checks the files without one,
                    // and below 16 bits, where it misreads the category 16 difference of -32768.
                    if (pt == 0 && predictor != 5 && predictor != 6 && bits < 16) {
                        assertTrue(src.contentEquals(ffmpegGray(huffman, bits, dir)), "$name: ffmpeg does not give the source back")
                    }
                    checkGray("$name, Huffman", huffman.readBytes(), want, w, h, bits)
                    checkGray("$name, arithmetic", arithmetic.readBytes(), want, w, h, bits)
                    cases++
                }
            }
            assertEquals(6 * 7, cases)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rgbWithoutAColourTransformComesBackExactly() {
        assumeTrue("t81jpeg is not installed", Tools.hasAll("t81jpeg"))
        val dir = Files.createTempDirectory("imagekodec-lossless-rgb").toFile()
        try {
            val w = 23
            val h = 18
            for (bits in listOf(8, 12, 16)) for (arithmetic in listOf(false, true)) for (predictor in listOf(1, 4, 7)) {
                val src = source(w, h, 3, bits, 100 + bits)
                val input = File(dir, "in.ppm").apply { writeBytes(pnm(src, w, h, 3, bits)) }
                val out = File(dir, "out.jpg")
                t81(input, out, predictor, 0, "-p", "-c", "-z", "${2 * w}", *(if (arithmetic) arrayOf("-a") else emptyArray()))
                val name = "RGB $bits bits, predictor $predictor, ${if (arithmetic) "arithmetic" else "Huffman"}"
                val jpeg = out.readBytes()
                val wide = ImageKodec.decode16(jpeg)
                val narrow = ImageKodec.decode(jpeg)
                assertEquals(3, wide.channels, name)
                for (i in 0 until w * h) for (c in 0 until 3) {
                    val v = src[i * 3 + c]
                    assertEquals(widened(v, bits), wide.samples[i * 3 + c].toInt() and 0xFFFF, "$name: pixel $i channel $c")
                    assertEquals(narrowed(v, bits), (narrow.argb[i] shr (16 - 8 * c)) and 0xFF, "$name: pixel $i channel $c")
                }
                // The reduced decode averages the full one, as for the formats without a DCT.
                assertTrue(ImageKodec.decodeReduced(jpeg, 2).argb.contentEquals(narrow.reducedBy(2).argb), name)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * t81jpeg's YCbCr files, full and subsampled: the arithmetic file's planes must equal the
     * Huffman file's, and subsampled ones must equal ffmpeg's decode, which reads a 4:4:4 file as
     * RGB. At 4:4:4 the colour must match t81jpeg's own within the rounding of the two
     * conversions; subsampled, its upsampling differs from ours and ffmpeg's at a padded edge, so
     * only the planes are compared there.
     */
    @Test
    fun colourTransformedFilesMatchFfmpegsPlanesAndTheReferenceColour() {
        assumeTrue("t81jpeg or ffmpeg is not installed", Tools.hasAll("t81jpeg", "ffmpeg"))
        val dir = Files.createTempDirectory("imagekodec-lossless-ycc").toFile()
        try {
            val w = 31
            val h = 21
            val src = source(w, h, 3, 8, 7)
            val input = File(dir, "in.ppm").apply { writeBytes(pnm(src, w, h, 3, 8)) }
            for (sampling in listOf(null, "1x1,2x2,2x2", "1x1,2x1,2x1", "1x1,1x2,1x2")) {
                val options = if (sampling == null) arrayOf("-p") else arrayOf("-p", "-s", sampling)
                val huffman = File(dir, "huffman.jpg").also { t81(input, it, 4, 0, *options) }
                val arithmetic = File(dir, "arithmetic.jpg").also { t81(input, it, 4, 0, *options, "-a") }
                val ours = JpegDecoder.decodePlanes(huffman.readBytes())
                val coded = JpegDecoder.decodePlanes(arithmetic.readBytes())
                val ffmpeg = if (sampling == null) {
                    null
                } else {
                    val raw = File(dir, "ffmpeg.yuv")
                    run(Tools.require("ffmpeg").path, "-v", "error", "-y", "-i", huffman.path, "-f", "rawvideo", raw.path)
                    raw.readBytes()
                }
                var at = 0
                for (k in ours.planes.indices) {
                    val a = ours.planes[k]
                    val b = coded.planes[k]
                    for (y in 0 until a.height) for (x in 0 until a.width) {
                        val v = a.samples[y * a.stride + x].toInt() and 0xFF
                        assertEquals(v, b.samples[y * b.stride + x].toInt() and 0xFF, "$sampling: arithmetic plane $k at ($x, $y)")
                        if (ffmpeg != null) assertEquals(ffmpeg[at++].toInt() and 0xFF, v, "$sampling: plane $k at ($x, $y) against ffmpeg")
                    }
                }
                if (ffmpeg != null) assertEquals(ffmpeg.size, at, "$sampling: plane sizes")
                if (sampling == null) {
                    val ref = File(dir, "ref.ppm")
                    run(Tools.require("t81jpeg").path, arithmetic.path, ref.path)
                    val reference = ref.readBytes().let { it.copyOfRange(it.size - w * h * 3, it.size) }
                    val rgb = ImageKodec.decode(arithmetic.readBytes())
                    for (i in 0 until w * h) for (c in 0 until 3) {
                        val d = abs(((rgb.argb[i] shr (16 - 8 * c)) and 0xFF) - (reference[i * 3 + c].toInt() and 0xFF))
                        assertTrue(d <= 2, "pixel $i channel $c differs from t81jpeg's colour by $d")
                    }
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun ffmpegsSubsampledPlanesComeBackExactly() {
        assumeTrue("ffmpeg is not installed", Tools.hasAll("ffmpeg"))
        val dir = Files.createTempDirectory("imagekodec-ljpeg").toFile()
        try {
            val ffmpeg = Tools.require("ffmpeg").path
            for (format in listOf("yuv420p", "yuv422p", "yuv444p", "yuvj420p", "yuvj422p")) for (predictor in 1..3) {
                val name = "$format, predictor $predictor"
                val jpeg = File(dir, "out.jpg")
                val raw = File(dir, "out.yuv")
                val input = arrayOf("-f", "lavfi", "-i", "testsrc2=size=38x30:duration=0.04", "-frames:v", "1", "-pix_fmt", format)
                run(ffmpeg, "-v", "error", "-y", *input, "-c:v", "ljpeg", "-pred", "$predictor", "-strict", "-1", jpeg.path)
                run(ffmpeg, "-v", "error", "-y", *input, "-f", "rawvideo", raw.path)
                val planes = JpegDecoder.decodePlanes(jpeg.readBytes())
                val yuv = raw.readBytes()
                var at = 0
                for ((k, plane) in planes.planes.withIndex()) {
                    for (y in 0 until plane.height) for (x in 0 until plane.width) {
                        assertEquals(
                            yuv[at++].toInt() and 0xFF, plane.samples[y * plane.stride + x].toInt() and 0xFF,
                            "$name: plane $k at ($x, $y)",
                        )
                    }
                }
                assertEquals(yuv.size, at, "$name: plane sizes")
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
