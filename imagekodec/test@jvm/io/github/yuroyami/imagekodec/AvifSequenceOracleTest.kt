package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.avif.Av1Decoder
import io.github.yuroyami.imagekodec.codec.avif.Av1EncodeSettings
import io.github.yuroyami.imagekodec.codec.avif.Av1HeifEncoder
import io.github.yuroyami.imagekodec.codec.avif.Av1Picture
import io.github.yuroyami.imagekodec.codec.avif.Av1ToolUse
import io.github.yuroyami.imagekodec.codec.avif.HeifCodecs
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Animated AVIF (#177) against dav1d (through ffmpeg), libavif and ffmpeg. For a matrix of sizes (odd ones among
 * them), depths (8, 10, 12), chroma (4:2:0, 4:2:2, 4:4:4, gray), alpha, efforts, key frame intervals, frame
 * durations and loop counts:
 *
 * - dav1d decodes every frame of the colour track, and of the alpha track, to exactly the encoder's own
 *   reconstruction, sample for sample, and so does ImageKodec's AV1 decoder;
 * - libavif (`tools/avif_rgb.c`, built against the installed library, as `AvifOracleTest` does) reads every frame
 *   within a level of `decodeAnimation`, each frame's duration, and the repetition count that gives the loop count;
 * - `decodeAnimation` gives every frame with its delay and the loop count, each frame within the quality asked
 *   (its PSNR at least a bound, and within 3 dB of the key frame's), and a lossless sequence gives back its frames
 *   exactly;
 * - the stream codes inter frames whose blocks predict from other frames, and where the effort turns it on,
 *   compound blocks, as ImageKodec's decoder counts the tools it reads.
 *
 * Skips cleanly without ffmpeg and its libdav1d, a C compiler or libavif's headers, unless
 * `IMAGEKODEC_REQUIRE_ORACLES=1`.
 */
@OptIn(ExperimentalHeifCodecApi::class)
class AvifSequenceOracleTest {

    private var lastError = ""

    private fun run(vararg args: String): Pair<Int, ByteArray> {
        val proc = ProcessBuilder(listOf("timeout", "-s", "KILL", "300") + args.toList()).redirectErrorStream(false).start()
        val out = ByteArrayOutputStream()
        var stderr = ByteArray(0)
        val err = Thread { stderr = proc.errorStream.readBytes() }.apply { start() }
        proc.inputStream.copyTo(out)
        err.join()
        lastError = stderr.decodeToString().trim()
        if (!proc.waitFor(300, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return -1 to ByteArray(0)
        }
        return proc.exitValue() to out.toByteArray()
    }

    private fun temp(ext: String) = File.createTempFile("kite-avis", ext).apply { deleteOnExit() }

    private fun tools(): Boolean {
        if (!Tools.hasAll("ffmpeg", "cc", "timeout")) return false
        val decoders = run(Tools.require("ffmpeg").path, "-hide_banner", "-decoders").second.decodeToString()
        if ("libdav1d" !in decoders) {
            if (System.getenv(Tools.REQUIRE_ENV) == "1") error("ffmpeg lacks libdav1d, and ${Tools.REQUIRE_ENV}=1 forbids skipping")
            return false
        }
        return reader != null
    }

    /** libavif's reading of a file, built once from `tools/avif_rgb.c`. */
    private val reader: File? by lazy {
        val source = listOf(File("../tools/avif_rgb.c"), File("tools/avif_rgb.c")).firstOrNull { it.isFile } ?: return@lazy null
        val out = temp(".bin")
        val (code, _) = run(Tools.require("cc").path, "-O2", "-o", out.path, source.path, "-lavif")
        if (code != 0) {
            if (System.getenv(Tools.REQUIRE_ENV) == "1") error("could not build tools/avif_rgb.c against libavif: $lastError")
            return@lazy null
        }
        out
    }

    /** One coded frame as the encoder left it: the picture any decoder outputs, and the temporal unit. */
    private class Coded(val picture: Av1Picture, val unit: ByteArray)

    /** The `av01` encoder, recording each track's frames as it codes them. */
    private class Recording : HeifEncoder by Av1HeifEncoder {
        val tracks = ArrayList<ArrayList<Coded>>()

        override fun openSequence(parameters: HeifEncodeParameters, keyFrameInterval: Int): HeifSequenceEncoder {
            val q = if (parameters.lossless) 0 else Av1HeifEncoder.qIndex(parameters.quality)
            val colour = parameters.colorPrimaries?.let { intArrayOf(it, parameters.transferCharacteristics!!, parameters.matrixCoefficients!!) }
            val session = Av1HeifEncoder.openSequence(Av1EncodeSettings(q, parameters.effort, colour), keyFrameInterval)
            val frames = ArrayList<Coded>()
            tracks += frames
            session.onFrame = { c, unit -> frames += Coded(c.lastPicture!!, unit) }
            return session
        }
    }

    private class Case(
        val name: String,
        val w: Int,
        val h: Int,
        val frames: Int,
        val options: HeifEncodeOptions,
        val alpha: Boolean = false,
        val gray: Boolean = false,
        val loop: Long = 0,
        /** The least PSNR, in dB, of every frame's RGB against its source; ignored when lossless. */
        val psnr: Double = 30.0,
        val compound: Boolean = false,
        /** Sensor noise drawn afresh each frame, which averaging two references reduces. */
        val noise: Boolean = false,
    )

    /** A panning scene of [c]'s size, with an opacity ramp when [Case.alpha], gray when [Case.gray], durations rising. */
    private fun animation(c: Case): KiteAnimation {
        val scene = PhotoScenes.landscape(c.w + 3 * c.frames + 8, c.h + c.frames + 8).bitmap
        val pan = AnimatedScenes.pan(scene, c.w, c.h, c.frames, radius = maxOf(3, minOf(c.w, c.h) / 5))
        val random = kotlin.random.Random(c.w * 31 + c.h)
        val frames = pan.frames.mapIndexed { t, f ->
            val argb = IntArray(c.w * c.h) { i ->
                var p = f.bitmap.argb[i]
                if (c.noise) {
                    val n = random.nextInt(-8, 9)
                    p = (0xFF shl 24) or ((((p ushr 16) and 255) + n).coerceIn(0, 255) shl 16) or
                        ((((p ushr 8) and 255) + n).coerceIn(0, 255) shl 8) or ((p and 255) + n).coerceIn(0, 255)
                }
                if (c.gray) {
                    val g = (((p ushr 16) and 255) * 77 + ((p ushr 8) and 255) * 150 + (p and 255) * 29) shr 8
                    p = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
                }
                if (c.alpha) {
                    // A ramp that moves with the frame, reaching 0 at one edge.
                    val a = (((i % c.w) + t * 2) * 255 / (c.w + 2 * c.frames)).coerceIn(0, 255)
                    p = (a shl 24) or (p and 0xFFFFFF)
                }
                p
            }
            KiteFrame(KiteBitmap(c.w, c.h, argb), 40 + 15 * t, 4 + (15 * t) / 10)
        }
        return KiteAnimation(c.w, c.h, frames, c.loop)
    }

    private fun pixFmt(p: Av1Picture, identity: Boolean): String {
        val depth = p.bitDepth
        if (p.monochrome) return if (depth == 8) "gray" else "gray${depth}le"
        val base = if (identity) "gbrp" else if (p.subX == 1 && p.subY == 1) "yuv420p" else if (p.subX == 1) "yuv422p" else "yuv444p"
        return if (depth == 8) base else "$base${depth}le"
    }

    /** dav1d's frames of track [stream] of [file], checked sample for sample against [coded]. */
    private fun checkDav1d(label: String, file: File, stream: Int, coded: List<Coded>, identity: Boolean) {
        val first = coded[0].picture
        val (code, raw) = run(
            Tools.require("ffmpeg").path, "-hide_banner", "-loglevel", "error", "-c:v", "libdav1d", "-i", file.path,
            "-map", "0:$stream", "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", pixFmt(first, identity), "-",
        )
        assertEquals(0, code, "$label: dav1d could not decode track $stream: $lastError")
        val bytes = if (first.bitDepth > 8) 2 else 1
        var at = 0
        for ((f, c) in coded.withIndex()) {
            val p = c.picture
            for (plane in p.planes.indices) {
                val pw = if (plane == 0) p.width else (p.width + p.subX) shr p.subX
                val ph = if (plane == 0) p.height else (p.height + p.subY) shr p.subY
                for (y in 0 until ph) for (x in 0 until pw) {
                    val v = if (bytes == 1) raw[at].toInt() and 0xFF else (raw[at].toInt() and 0xFF) or ((raw[at + 1].toInt() and 0xFF) shl 8)
                    val e = p.planes[plane][y * p.strides[plane] + x].toInt()
                    if (v != e) error("$label: dav1d differs in track $stream, frame $f, plane $plane at ($x, $y): $e coded, $v read")
                    at += bytes
                }
            }
        }
        assertEquals(raw.size, at, "$label: dav1d's output of track $stream has another number of frames")
    }

    /** ImageKodec's AV1 decoder on the temporal units of [coded], each frame equal to the reconstruction; its tool counts. */
    private fun checkOwnDecoder(label: String, coded: List<Coded>): Av1ToolUse {
        val use = Av1ToolUse()
        val d = Av1Decoder()
        d.toolUse = use
        for ((f, c) in coded.withIndex()) {
            val got = d.decodeTemporalUnit(c.unit) ?: error("$label: frame $f shows no picture")
            val p = c.picture
            for (plane in p.planes.indices) {
                val pw = if (plane == 0) p.width else (p.width + p.subX) shr p.subX
                val ph = if (plane == 0) p.height else (p.height + p.subY) shr p.subY
                for (y in 0 until ph) for (x in 0 until pw) {
                    assertEquals(p.planes[plane][y * p.strides[plane] + x], got.planes[plane][y * got.strides[plane] + x], "$label: own decoder, frame $f plane $plane ($x, $y)")
                }
            }
        }
        return use
    }

    /** libavif's frames, repetition count and durations of [file]. */
    private class LibavifSequence(val repetitionCount: Int, val timescale: Long, val durations: List<Long>, val frames: List<IntArray>, val w: Int, val channels: Int)

    private fun libavif(label: String, file: File): LibavifSequence {
        val out = temp(".raw")
        val (code, _) = run(reader!!.path, file.path, "8", out.path, "all")
        assertEquals(0, code, "$label: libavif could not read the sequence: $lastError")
        val b = out.readBytes()
        fun u32(at: Int) = (b[at].toInt() and 255) or ((b[at + 1].toInt() and 255) shl 8) or ((b[at + 2].toInt() and 255) shl 16) or ((b[at + 3].toInt() and 255) shl 24)
        val count = u32(0)
        var at = 12
        val durations = ArrayList<Long>()
        val frames = ArrayList<IntArray>()
        var w = 0
        var channels = 0
        repeat(count) {
            durations += u32(at).toLong() and 0xFFFFFFFFL
            w = u32(at + 4)
            val h = u32(at + 8)
            channels = u32(at + 12)
            val base = at + 16
            frames += IntArray(w * h * channels) { i -> b[base + i].toInt() and 255 }
            at = base + w * h * channels
        }
        return LibavifSequence(u32(4), u32(8).toLong() and 0xFFFFFFFFL, durations, frames, w, channels)
    }

    private fun psnr(a: KiteBitmap, b: KiteBitmap): Double {
        var se = 0.0
        for (i in a.argb.indices) for (shift in intArrayOf(0, 8, 16)) {
            val e = ((a.argb[i] ushr shift) and 255) - ((b.argb[i] ushr shift) and 255)
            se += e.toDouble() * e
        }
        val mse = se / (a.argb.size * 3)
        return if (mse == 0.0) 99.0 else 10 * log10(255.0 * 255.0 / mse)
    }

    private fun check(c: Case) {
        val source = animation(c)
        val recording = Recording()
        ImageKodec.registerHeifEncoder(recording)
        val data = try {
            ImageKodec.encodeAvif(source, c.options)
        } finally {
            HeifCodecs.reset()
        }
        val file = temp(".avif").apply { writeBytes(data) }
        val colour = recording.tracks[0]
        assertEquals(c.frames, colour.size, "${c.name}: colour frames coded")
        val identity = c.options.lossless && !c.gray
        checkDav1d(c.name, file, 0, colour, identity)
        val use = checkOwnDecoder(c.name, colour)
        if (c.alpha) {
            val alpha = recording.tracks[1]
            checkDav1d("${c.name} alpha", file, 1, alpha, false)
            checkOwnDecoder("${c.name} alpha", alpha)
        } else {
            assertEquals(1, recording.tracks.size, "${c.name}: an alpha track for opaque frames")
        }
        // The stream predicts from other frames, as the decoder reads it.
        val interval = c.options.keyFrameInterval
        val keys = if (interval == 0) 1 else (c.frames + interval - 1) / interval
        assertEquals(c.frames - keys, use["inter frame"], "${c.name}: inter frames")
        assertTrue(use["single"] + use["compound"] > 0, "${c.name}: no block predicts from another frame: ${use.counts}")
        if (c.compound) assertTrue(use["compound"] > 0, "${c.name}: no compound block: ${use.counts}")
        println("${c.name}: ${data.size} bytes, tools ${use.counts.toSortedMap()}")

        val animation = ImageKodec.decodeAnimation(data)
        assertEquals(c.frames, animation.frames.size, "${c.name}: frames")
        assertEquals(c.loop, animation.loopCount, "${c.name}: loop count")
        assertContentEquals(animation.frames[0].bitmap.argb, ImageKodec.decode(data).argb, "${c.name}: the primary item is the first frame")
        val ref = libavif(c.name, file)
        assertEquals(c.frames, ref.frames.size, "${c.name}: libavif's frame count")
        // libavif counts the plays after the first, -1 for ever.
        assertEquals(if (c.loop == 0L) -1 else (c.loop - 1).toInt(), ref.repetitionCount, "${c.name}: libavif's repetition count")
        val first = psnr(source.frames[0].bitmap, animation.frames[0].bitmap)
        for ((i, frame) in animation.frames.withIndex()) {
            val want = source.frames[i]
            assertEquals(want.delayMillis, frame.delayMillis, "${c.name}: frame $i delay")
            assertEquals(want.delayMillis.toLong() * ref.timescale / 1000, ref.durations[i], "${c.name}: libavif's duration of frame $i")
            val got = frame.bitmap
            if (c.options.lossless) {
                assertContentEquals(want.bitmap.argb, got.argb, "${c.name}: lossless frame $i")
            } else {
                val p = psnr(want.bitmap, got)
                println("${c.name}: frame $i ${"%.2f".format(p)} dB")
                assertTrue(p >= c.psnr, "${c.name}: frame $i at ${"%.2f".format(p)} dB, below ${c.psnr}")
                assertTrue(p >= first - 3, "${c.name}: frame $i at ${"%.2f".format(p)} dB, more than 3 dB below the key frame's ${"%.2f".format(first)}")
            }
            // libavif's frame, at the coded size, against decodeAnimation's crop of it.
            val lib = ref.frames[i]
            for (y in 0 until c.h) for (x in 0 until c.w) {
                val p = got.argb[y * c.w + x]
                val at = (y * ref.w + x) * ref.channels
                val ours = intArrayOf((p ushr 16) and 255, (p ushr 8) and 255, p and 255, p ushr 24)
                for (k in 0 until ref.channels) {
                    if (kotlin.math.abs(lib[at + k] - ours[k]) > 1) error("${c.name}: libavif reads frame $i at ($x, $y) channel $k as ${lib[at + k]}, ImageKodec as ${ours[k]}")
                }
            }
        }
    }

    @Test
    fun matrix() {
        if (!tools()) return
        val cases = listOf(
            Case("64x48 8-bit 4:2:0 effort 2", 64, 48, 6, HeifEncodeOptions(quality = 60), loop = 0, psnr = 26.0, compound = true, noise = true),
            Case("45x37 10-bit 4:4:4 alpha effort 1", 45, 37, 5, HeifEncodeOptions(quality = 50, chroma = HeifChroma.Yuv444, bitDepth = 10, effort = 1), alpha = true, loop = 1, psnr = 31.0),
            Case("33x29 12-bit 4:2:0 effort 3", 33, 29, 4, HeifEncodeOptions(quality = 70, bitDepth = 12, effort = 3), loop = 3, psnr = 25.5),
            Case("41x23 8-bit 4:2:2 key every 2", 41, 23, 5, HeifEncodeOptions(quality = 55, chroma = HeifChroma.Yuv422, keyFrameInterval = 2), loop = 2, psnr = 29.0),
            Case("40x30 gray effort 2", 40, 30, 4, HeifEncodeOptions(quality = 40), gray = true, loop = 5, psnr = 33.0),
            Case("30x20 lossless alpha", 30, 20, 4, HeifEncodeOptions(lossless = true), alpha = true, loop = 0),
            Case("27x19 lossless gray 10-bit", 27, 19, 3, HeifEncodeOptions(lossless = true, bitDepth = 10), gray = true, loop = 1),
        )
        for (c in cases) check(c)
    }
}
