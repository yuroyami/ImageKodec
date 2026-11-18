package io.github.yuroyami.imagekodec

import org.junit.Assume.assumeTrue
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The files of [ImageKodec.encodeJpeg] and [ImageKodec.encodeJpeg16] with [JpegEncodeOptions]
 * (#183) read by libjpeg-turbo and ffmpeg.
 *
 * - DCT files: every entropy coding of the same coefficients (sequential with the example or
 *   built tables, progressive, arithmetic, both, with restarts and DAC bounds) must decode in
 *   libjpeg-turbo's `djpeg -dct int` to exactly the samples of the sequential file, and so in
 *   ffmpeg for the Huffman ones, which cannot read arithmetic coding. ImageKodec's decoder,
 *   whose IDCT and upsampling are stb_image's, stays within a few levels of `djpeg`. `djpeg
 *   -verbose` must name the frame type, the components and their sampling.
 * - 12-bit files: libjpeg-turbo 3 (`djpeg3`, built from source as CONTRIBUTING.md says) reads every
 *   process, and a gray file to exactly the samples of [ImageKodec.decode16], since both use
 *   libjpeg's integer IDCT at 12 bits.
 * - Lossless Huffman files: `djpeg3` returns the samples of every precision from 2 to 16 bits,
 *   every predictor and point transform, and so does ffmpeg, except at 16 bits, where it
 *   misreads the difference -32768, and for 9-bit RGB, which it reads through a private
 *   transform (see AGENTS.md). Neither reads arithmetic-coded lossless files, which ImageKodec's
 *   decoder, checked against the T.81 reference software's, reads back instead.
 *
 * [aSizeTable] prints the size and SSIMULACRA 2 of progressive optimized and arithmetic files
 * against libjpeg-turbo's `cjpeg` at the same quality on [PhotoScenes]; it only reports.
 */
class JpegEncodeOracleTest {

    private val dir: File = Files.createTempDirectory("jpeg-encode").toFile().apply { deleteOnExit() }

    private fun run(vararg command: String, stdout: File? = null): String {
        val builder = ProcessBuilder(*command)
        if (stdout != null) builder.redirectOutput(stdout).redirectError(ProcessBuilder.Redirect.PIPE) else builder.redirectErrorStream(true)
        val process = builder.start()
        val log = (if (stdout != null) process.errorStream else process.inputStream).readBytes().decodeToString()
        if (!process.waitFor(300, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("${command.first()} timed out")
        }
        assertEquals(0, process.exitValue(), "${command.joinToString(" ")} failed: $log")
        return log
    }

    private class Pnm(val width: Int, val height: Int, val channels: Int, val max: Int, val samples: IntArray)

    private fun readPnm(data: ByteArray): Pnm {
        var at = 0
        fun token(): String {
            while (data[at].toInt().toChar().isWhitespace()) at++
            val start = at
            while (!data[at].toInt().toChar().isWhitespace()) at++
            return data.copyOfRange(start, at).decodeToString()
        }
        val magic = token()
        val w = token().toInt()
        val h = token().toInt()
        val max = token().toInt()
        at++
        val channels = if (magic == "P5") 1 else 3
        val wide = max > 255
        val samples = IntArray(w * h * channels) {
            if (wide) ((data[at + 2 * it].toInt() and 0xFF) shl 8) or (data[at + 2 * it + 1].toInt() and 0xFF) else data[at + it].toInt() and 0xFF
        }
        return Pnm(w, h, channels, max, samples)
    }

    private fun djpeg(tool: String, jpeg: ByteArray, name: String): Pnm {
        val input = File(dir, "$name.jpg").apply { writeBytes(jpeg) }
        val output = File(dir, "$name.pnm")
        run(Tools.require(tool).path, "-dct", "int", "-nosmooth", "-pnm", "-outfile", output.path, input.path)
        return readPnm(output.readBytes())
    }

    /** ffmpeg's decode of [jpeg] as raw samples of [format]. */
    private fun ffmpeg(jpeg: ByteArray, name: String, format: String): ByteArray {
        val input = File(dir, "$name.jpg").apply { writeBytes(jpeg) }
        val raw = File(dir, "$name.raw")
        run(Tools.require("ffmpeg").path, "-v", "error", "-y", "-i", input.path, "-f", "rawvideo", "-pix_fmt", format, raw.path)
        return raw.readBytes()
    }

    /** `djpeg -verbose`'s report of [jpeg]: the frame marker and each component's sampling, as "0xc2 2x2,1x1,1x1". */
    private fun verbose(tool: String, jpeg: ByteArray, name: String): String {
        val input = File(dir, "$name.jpg").apply { writeBytes(jpeg) }
        val log = run(Tools.require(tool).path, "-verbose", "-outfile", File(dir, "$name.out").path, input.path)
        val sof = Regex("Start Of Frame (0x[0-9a-f]{2})").find(log)?.groupValues?.get(1) ?: "none"
        val sampling = Regex("Component \\d+: (\\d)hx(\\d)v").findAll(log).joinToString(",") { "${it.groupValues[1]}x${it.groupValues[2]}" }
        return "$sof $sampling"
    }

    private fun photo(width: Int, height: Int, seed: Int, gray: Boolean = false): KiteBitmap {
        val random = Random(seed)
        return KiteBitmap(width, height, IntArray(width * height) {
            val x = it % width
            val y = it / width
            val edge = if ((x / 7 + y / 5) % 3 == 0) 70 else 0
            val r = (x * 255 / width + edge + random.nextInt(30)).coerceIn(0, 255)
            val g = if (gray) r else (y * 255 / height + random.nextInt(30)).coerceIn(0, 255)
            val b = if (gray) r else ((x + y) * 128 / (width + height) + edge + random.nextInt(30)).coerceIn(0, 255)
            0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
        })
    }

    private val modes = listOf(
        "baseline" to JpegEncodeOptions(),
        "optimized" to JpegEncodeOptions(optimizeHuffman = true),
        "progressive" to JpegEncodeOptions(progressive = true),
        "progressive restart" to JpegEncodeOptions(progressive = true, restartInterval = 3),
        "arithmetic" to JpegEncodeOptions(arithmetic = true),
        "arithmetic DAC restart" to JpegEncodeOptions(arithmetic = true, arithmeticDcLower = 2, arithmeticDcUpper = 6, arithmeticAcThreshold = 20, restartInterval = 7),
        "arithmetic progressive" to JpegEncodeOptions(arithmetic = true, progressive = true),
        "arithmetic progressive DAC" to JpegEncodeOptions(arithmetic = true, progressive = true, arithmeticDcLower = 1, arithmeticDcUpper = 1, arithmeticAcThreshold = 2, restartInterval = 2),
    )

    private fun with(base: JpegEncodeOptions, mode: JpegEncodeOptions) = JpegEncodeOptions(
        quality = base.quality, subsampling = base.subsampling, grayscale = base.grayscale, encoder = base.encoder, precision = base.precision,
        progressive = mode.progressive, optimizeHuffman = mode.optimizeHuffman && base.precision == 8, arithmetic = mode.arithmetic,
        arithmeticDcLower = mode.arithmeticDcLower, arithmeticDcUpper = mode.arithmeticDcUpper,
        arithmeticAcThreshold = mode.arithmeticAcThreshold, restartInterval = mode.restartInterval,
    )

    private fun expectedMarker(mode: JpegEncodeOptions, precision: Int): String = when {
        mode.arithmetic && mode.progressive -> "0xca"
        mode.arithmetic -> "0xc9"
        mode.progressive -> "0xc2"
        precision == 8 -> "0xc0"
        else -> "0xc1"
    }

    @Test
    fun eightBitFilesDecodeAlikeInLibjpegTurboAndFfmpeg() {
        assumeTrue("djpeg or ffmpeg is not installed", Tools.hasAll("djpeg", "ffmpeg"))
        val bases = listOf(
            Triple("4:2:0", JpegEncodeOptions(quality = 75, subsampling = JpegSubsampling.S420), "2x2,1x1,1x1"),
            Triple("4:2:2", JpegEncodeOptions(quality = 85, subsampling = JpegSubsampling.S422), "2x1,1x1,1x1"),
            Triple("4:4:4", JpegEncodeOptions(quality = 93), "1x1,1x1,1x1"),
            Triple("gray", JpegEncodeOptions(quality = 80, grayscale = JpegGrayscale.Always), "1x1"),
            Triple("jpegli", JpegEncodeOptions(quality = 70, encoder = JpegEncoderKind.Jpegli), "2x2,1x1,1x1"),
            Triple("jpegli gray", JpegEncodeOptions(quality = 90, encoder = JpegEncoderKind.Jpegli, grayscale = JpegGrayscale.Always), "1x1"),
        )
        var files = 0
        for ((w, h) in listOf(77 to 45, 1 to 1, 7 to 9, 31 to 1)) for ((label, base, sampling) in bases) {
            val bitmap = photo(w, h, w + h)
            val gray = base.grayscale == JpegGrayscale.Always
            var reference: Pnm? = null
            var referenceFfmpeg: ByteArray? = null
            for ((modeName, mode) in modes) {
                val name = "${w}x$h $label $modeName"
                val jpeg = ImageKodec.encodeJpeg(bitmap, with(base, mode))
                files++
                assertEquals("${expectedMarker(mode, 8)} $sampling", verbose("djpeg", jpeg, "v"), name)
                val theirs = djpeg("djpeg", jpeg, "d")
                if (reference == null) reference = theirs else assertContentEquals(reference.samples, theirs.samples, "$name: djpeg's samples differ from the sequential file's")
                val ours = ImageKodec.decode(jpeg).argb
                var worst = 0
                for (i in 0 until w * h) {
                    val p = ours[i]
                    if (gray) {
                        worst = maxOf(worst, abs((p and 0xFF) - theirs.samples[i]))
                    } else {
                        worst = maxOf(worst, abs(((p ushr 16) and 0xFF) - theirs.samples[3 * i]), abs(((p ushr 8) and 0xFF) - theirs.samples[3 * i + 1]), abs((p and 0xFF) - theirs.samples[3 * i + 2]))
                    }
                }
                // stb_image's IDCT, colour conversion and upsampling are not libjpeg's.
                assertTrue(worst <= (if (gray || label == "4:4:4") 3 else 40), "$name: ImageKodec and djpeg differ by $worst")
                if (!mode.arithmetic) {
                    val f = ffmpeg(jpeg, "f", if (gray) "gray" else "rgb24")
                    if (referenceFfmpeg == null) referenceFfmpeg = f else assertContentEquals(referenceFfmpeg, f, "$name: ffmpeg's samples differ from the sequential file's")
                }
            }
        }
        println("8-bit DCT: $files files read alike by djpeg, ffmpeg and ImageKodec")
    }

    @Test
    fun twelveBitFilesMatchLibjpegTurbo3() {
        assumeTrue("libjpeg-turbo 3 (djpeg3) is not installed", Tools.hasAll("djpeg3"))
        val random = Random(12)
        for ((channels, base) in listOf(
            1 to JpegEncodeOptions(quality = 90, precision = 12),
            3 to JpegEncodeOptions(quality = 85, precision = 12, subsampling = JpegSubsampling.S444),
            3 to JpegEncodeOptions(quality = 75, precision = 12, subsampling = JpegSubsampling.S420),
        )) for ((w, h) in listOf(45 to 27, 1 to 1, 9 to 2)) {
            val samples = ShortArray(w * h * channels) {
                val x = (it / channels) % w
                val y = (it / channels) / w
                val v = 2048 + 1300 * kotlin.math.sin(x / 4.0 + it % channels) + 500 * kotlin.math.cos(y / 3.0) + random.nextInt(-60, 61)
                (v.toInt().coerceIn(0, 4095) shl 4).toShort()
            }
            val source = KiteBitmap16(w, h, channels, samples)
            var reference: Pnm? = null
            for ((modeName, mode) in modes.drop(1)) {
                val options = with(base, mode)
                val name = "12-bit ${w}x$h $channels channels $modeName"
                val jpeg = ImageKodec.encodeJpeg16(source, options)
                val sampling = if (channels == 1) "1x1" else if (base.subsampling == JpegSubsampling.S420) "2x2,1x1,1x1" else "1x1,1x1,1x1"
                assertEquals("${expectedMarker(mode, 12)} $sampling", verbose("djpeg3", jpeg, "v"), name)
                val theirs = djpeg("djpeg3", jpeg, "d")
                assertEquals(4095, theirs.max, name)
                if (reference == null) reference = theirs else assertContentEquals(reference.samples, theirs.samples, "$name: djpeg3's samples differ")
                if (channels == 1) {
                    val ours = ImageKodec.decode16(jpeg)
                    for (i in 0 until w * h) assertEquals(theirs.samples[i], (ours.samples[i].toInt() and 0xFFFF) shr 4, "$name: sample $i")
                }
            }
        }
    }

    @Test
    fun losslessFilesGiveBackTheirSamplesInLibjpegTurbo3AndFfmpeg() {
        assumeTrue("libjpeg-turbo 3 (djpeg3) or ffmpeg is not installed", Tools.hasAll("djpeg3", "ffmpeg"))
        val random = Random(16)
        var files = 0
        for (bits in 2..16) for (channels in listOf(1, 3)) {
            val w = 23
            val h = 9
            val max = (1 shl bits) - 1
            val raw = IntArray(w * h * channels) {
                val x = (it / channels) % w
                val y = (it / channels) / w
                ((x * 7 + y * 13 + it % channels * 50) * max / 400 + random.nextInt(max / 8 + 1)).coerceIn(0, max)
            }
            val source = KiteBitmap16(w, h, channels, ShortArray(raw.size) { (raw[it] shl (16 - bits)).toShort() })
            for (predictor in 1..7) for (arithmetic in listOf(false, true)) {
                val pt = if (predictor == 2 && bits > 3) 2 else 0
                val restart = if (predictor == 6) w * 3 else 0
                val options = JpegEncodeOptions(lossless = true, precision = bits, predictor = predictor, pointTransform = pt, arithmetic = arithmetic, restartInterval = restart)
                val name = "lossless $bits bits $channels channels $options"
                val jpeg = ImageKodec.encodeJpeg16(source, options)
                files++
                val want = IntArray(raw.size) { (raw[it] shr pt) shl pt }
                if (arithmetic) {
                    // libjpeg-turbo 3 and ffmpeg read no arithmetic-coded lossless file. ImageKodec's
                    // decoder reads the T.81 reference software's (JpegLosslessTest), and these.
                    val ours = ImageKodec.decode16(jpeg)
                    if (bits > 8) {
                        for (i in want.indices) assertEquals(want[i], (ours.samples[i].toInt() and 0xFFFF) shr (16 - bits), "$name: sample $i")
                    }
                    continue
                }
                assertEquals("0xc3 ${List(channels) { "1x1" }.joinToString(",")}", verbose("djpeg3", jpeg, "v"), name)
                val theirs = djpeg("djpeg3", jpeg, "d")
                assertEquals(max, theirs.max, name)
                assertContentEquals(want, theirs.samples, "$name: djpeg3")
                if (bits < 16 && (channels == 1 || bits != 9)) {
                    // ffmpeg shifts gray samples up to fill its format, and leaves RGB ones as they
                    // are; a 9-bit RGB file it reads through the private transform of its ljpeg encoder.
                    val shift = if (channels == 1) (if (bits > 8) 16 - bits else 8 - bits) else 0
                    val format = if (channels == 1) (if (bits > 8) "gray16le" else "gray") else (if (bits > 8) "rgb48le" else "rgb24")
                    val f = ffmpeg(jpeg, "f", format)
                    val got = if (bits > 8) IntArray(f.size / 2) { ((f[2 * it].toInt() and 0xFF) or ((f[2 * it + 1].toInt() and 0xFF) shl 8)) shr shift }
                        else IntArray(f.size) { (f[it].toInt() and 0xFF) shr shift }
                    assertContentEquals(want, got, "$name: ffmpeg")
                }
            }
        }
        println("lossless: $files files give back their samples, half of them Huffman-coded and read by djpeg3")
    }

    /** [bitmap] as a binary PPM. */
    private fun ppm(bitmap: KiteBitmap): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("P6\n${bitmap.width} ${bitmap.height}\n255\n".encodeToByteArray())
        for (p in bitmap.argb) {
            out.write((p ushr 16) and 0xFF)
            out.write((p ushr 8) and 0xFF)
            out.write(p and 0xFF)
        }
        return out.toByteArray()
    }

    private fun decodeWithDjpeg(jpeg: ByteArray): KiteBitmap {
        val pnm = djpeg("djpeg", jpeg, "s")
        return KiteBitmap(pnm.width, pnm.height, IntArray(pnm.width * pnm.height) {
            0xFF000000.toInt() or (pnm.samples[3 * it] shl 16) or (pnm.samples[3 * it + 1] shl 8) or pnm.samples[3 * it + 2]
        })
    }

    @Test
    fun aSizeTable() {
        assumeTrue("cjpeg or djpeg is not installed", Tools.hasAll("cjpeg", "djpeg"))
        val quality = 75
        println("JPEG sizes at quality $quality, 4:2:0, SSIMULACRA 2 of djpeg's decode; cjpeg is libjpeg-turbo's")
        println(String.format("%-10s %22s %22s %22s %22s", "scene", "ours prog+opt", "cjpeg -prog -opt", "ours arithmetic", "cjpeg -arithmetic"))
        val totals = LongArray(4)
        val times = LongArray(4)
        for (scene in PhotoScenes.all()) {
            val input = File(dir, "${scene.name}.ppm").apply { writeBytes(ppm(scene.bitmap)) }
            fun cjpeg(vararg flags: String): ByteArray {
                val out = File(dir, "${scene.name}-c.jpg")
                run(Tools.require("cjpeg").path, "-quality", "$quality", *flags, "-outfile", out.path, input.path)
                return out.readBytes()
            }
            val files = ArrayList<ByteArray>()
            val made: List<() -> ByteArray> = listOf(
                { ImageKodec.encodeJpeg(scene.bitmap, JpegEncodeOptions(quality = quality, progressive = true)) },
                { cjpeg("-progressive", "-optimize") },
                { ImageKodec.encodeJpeg(scene.bitmap, JpegEncodeOptions(quality = quality, arithmetic = true)) },
                { cjpeg("-arithmetic") },
            )
            for ((i, make) in made.withIndex()) {
                make()   // warm up
                val start = System.nanoTime()
                val bytes = make()
                times[i] += System.nanoTime() - start
                files += bytes
                totals[i] += bytes.size.toLong()
            }
            val cells = files.map { String.format("%7d B (s2 %5.2f)", it.size, ImageKodec.ssimulacra2(scene.bitmap, decodeWithDjpeg(it))) }
            println(String.format("%-10s %22s %22s %22s %22s", scene.name, cells[0], cells[1], cells[2], cells[3]))
        }
        println(String.format("%-10s %22s %22s %22s %22s", "total", "${totals[0]} B", "${totals[1]} B", "${totals[2]} B", "${totals[3]} B"))
        println(String.format("%-10s %22s %22s %22s %22s", "time", "${times[0] / 1_000_000} ms", "${times[1] / 1_000_000} ms", "${times[2] / 1_000_000} ms", "${times[3] / 1_000_000} ms"))
        // A loose bound that a regression of the coders would cross: within 10 percent of libjpeg-turbo's.
        assertTrue(totals[0] < totals[1] * 11 / 10, "progressive ${totals[0]} against ${totals[1]}")
        assertTrue(totals[2] < totals[3] * 11 / 10, "arithmetic ${totals[2]} against ${totals[3]}")
    }
}
