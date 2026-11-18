package io.github.yuroyami.imagekodec.heic

import io.github.yuroyami.imagekodec.HeifChroma
import io.github.yuroyami.imagekodec.HeifEncodeOptions
import io.github.yuroyami.imagekodec.ImageKodec
import io.github.yuroyami.imagekodec.KiteAnimation
import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.KiteFrame
import io.github.yuroyami.imagekodec.Tools
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `encodeHeic` of an animation (#177) against ffmpeg and libheif. ffmpeg reads the `hvc1` track of the file: its
 * frames come out in presentation order, so P and B pictures, the composition offsets (`ctts`) and the edit list
 * are all on trial. A lossless file, coded through the identity matrix, must give every source frame exactly in
 * ffmpeg's RGB; a lossy one, in 4:4:4, must give ffmpeg's RGB within a level on average of `decodeAnimation`'s; each frame's
 * presentation time must be the sum of the delays before it, from 0. heif-convert reads the primary item, which
 * must be the first frame.
 */
class HeicSequenceEncodeOracleTest {

    init {
        ImageKodecHeic.install()
    }

    private fun run(vararg args: String): Pair<Int, ByteArray> {
        val proc = ProcessBuilder(args.toList()).redirectErrorStream(false).start()
        val out = proc.inputStream.readBytes()
        val err = proc.errorStream.readBytes()
        if (!proc.waitFor(300, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return -1 to err
        }
        return proc.exitValue() to (if (proc.exitValue() == 0) out else err)
    }

    private fun temp(ext: String) = File.createTempFile("kite-heics-enc", ext).apply { deleteOnExit() }

    private fun moving(w: Int, h: Int, frames: Int, gray: Boolean = false): KiteAnimation {
        var s = w * 31 + h
        return KiteAnimation(w, h, (0 until frames).map { t ->
            val argb = IntArray(w * h) { i ->
                s = s * 1103515245 + 12345
                val n = (s ushr 16) and 15
                val x = i % w + 2 * t
                val y = i / w + t
                val disc = (x - w / 2) * (x - w / 2) + (y - h / 3) * (y - h / 3) < w * h / 10
                val r = (x * 255 / maxOf(1, w) + n).coerceIn(0, 255)
                val g = (y * 255 / maxOf(1, h) + (if (disc) 70 else 0)).coerceIn(0, 255)
                val b = ((x + y) * 3 + n * 2).coerceIn(0, 255)
                if (gray) 0xFF000000.toInt() or (g * 0x010101) else 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
            }
            KiteFrame(KiteBitmap(w, h, argb), 40 + 20 * (t % 3), (40 + 20 * (t % 3)) / 10)
        }, loopCount = 0)
    }

    /** The index of the first video stream of [file] that holds [n] frames: the colour track, not an item. */
    private fun track(file: File, n: Int): Int {
        val (code, out) = run(
            Tools.require("ffprobe").path, "-v", "error", "-select_streams", "v", "-count_frames",
            "-show_entries", "stream=index,nb_read_frames", "-of", "csv=p=0", file.path,
        )
        assertEquals(0, code, "ffprobe: ${out.decodeToString()}")
        val streams = out.decodeToString().lines().filter { it.isNotBlank() }.map { l -> l.split(",").map { it.trim() } }
        return streams.firstOrNull { it.getOrNull(1) == "$n" }?.get(0)?.toInt()
            ?: error("no stream of $n frames in ${streams.joinToString { it.joinToString("/") }}")
    }

    /** ffmpeg's decode of [stream] of [file] as RGB, one bitmap a frame in presentation order. */
    private fun ffmpegRgb(file: File, stream: Int, w: Int, h: Int): List<IntArray> {
        val out = temp(".rgb")
        val (code, log) = run(
            Tools.require("ffmpeg").path, "-hide_banner", "-loglevel", "error", "-err_detect", "crccheck", "-i", file.path,
            "-map", "0:$stream", "-sws_flags", "accurate_rnd+full_chroma_int", "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", "rgb24", "-y", out.path,
        )
        assertEquals(0, code, "ffmpeg: ${log.decodeToString()}")
        val raw = out.readBytes()
        val size = w * h * 3
        assertEquals(0, raw.size % size, "ffmpeg's output is not whole frames")
        return List(raw.size / size) { f ->
            IntArray(w * h) { i ->
                val o = f * size + i * 3
                0xFF000000.toInt() or ((raw[o].toInt() and 255) shl 16) or ((raw[o + 1].toInt() and 255) shl 8) or (raw[o + 2].toInt() and 255)
            }
        }
    }

    /** The presentation time in milliseconds of each frame of [stream] of [file], in presentation order. */
    private fun times(file: File, stream: Int): List<Long> {
        val (code, out) = run(
            Tools.require("ffprobe").path, "-v", "error", "-select_streams", "$stream", "-show_entries",
            "frame=best_effort_timestamp_time", "-of", "csv=p=0", file.path,
        )
        assertEquals(0, code, "ffprobe: ${out.decodeToString()}")
        return out.decodeToString().lines().filter { it.isNotBlank() }.map { Math.round(it.trim().trimEnd(',').toDouble() * 1000) }
    }

    private fun check(name: String, a: KiteAnimation, options: HeifEncodeOptions) {
        val bytes = ImageKodec.encodeHeic(a, options)
        val file = temp(".heic").apply { writeBytes(bytes) }
        val n = a.frames.size
        val stream = track(file, n)
        val theirs = ffmpegRgb(file, stream, a.width, a.height)
        assertEquals(n, theirs.size, "$name: ffmpeg's frames")
        val ours = ImageKodec.decodeAnimation(bytes)
        for (f in 0 until n) {
            val want = if (options.lossless) a.frames[f].bitmap.argb else ours.frames[f].bitmap.argb
            var sum = 0L
            var worst = 0
            for (i in want.indices) for (shift in intArrayOf(0, 8, 16)) {
                val d = abs(((want[i] ushr shift) and 255) - ((theirs[f][i] ushr shift) and 255))
                sum += d
                worst = maxOf(worst, d)
            }
            val mean = sum.toDouble() / (want.size * 3)
            if (options.lossless) assertEquals(0, worst, "$name: ffmpeg's frame $f differs from the source")
            else assertTrue(mean <= 1.0, "$name: ffmpeg's frame $f is $mean from ours on average, $worst at worst")
        }
        var at = 0L
        val want = a.frames.map { f -> at.also { at += f.delayMillis } }
        assertEquals(want, times(file, stream), "$name: presentation times")
        if (options.lossless) {
            val png = temp(".png")
            val (code, log) = run(Tools.require("heif-convert").path, file.path, png.path)
            assertEquals(0, code, "$name: heif-convert: ${log.decodeToString()}")
            val still = ImageKodec.decode(png.readBytes())
            assertTrue(a.frames[0].bitmap.argb.contentEquals(still.argb), "$name: heif-convert's primary item is not the first frame")
        }
    }

    @Test
    fun ffmpegAndLibheifReadTheSequence() {
        assumeTrue("ffmpeg or libheif is not installed", Tools.hasAll("ffmpeg", "ffprobe", "heif-convert"))
        for (effort in 1..3) check("lossless 37x21, 9 frames, effort $effort", moving(37, 21, 9), HeifEncodeOptions(lossless = true, effort = effort))
        check("lossless gray 24x18, 6 frames", moving(24, 18, 6, gray = true), HeifEncodeOptions(lossless = true, effort = 2))
        check("lossless 32x16, key frame every 3", moving(32, 16, 8), HeifEncodeOptions(lossless = true, effort = 1, keyFrameInterval = 3))
        // Lossy files are 4:4:4, as ffmpeg upsamples chroma otherwise than the core does; HevcInterOracleTest holds
        // the streams of every chroma format to ffmpeg sample for sample.
        for (effort in 1..2) {
            check("lossy 64x48, 10 frames, effort $effort", moving(64, 48, 10), HeifEncodeOptions(quality = 60, effort = effort, chroma = HeifChroma.Yuv444))
        }
        check("lossy 33x17, 5 frames", moving(33, 17, 5), HeifEncodeOptions(quality = 70, chroma = HeifChroma.Yuv444))
    }
}
