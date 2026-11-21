package io.github.yuroyami.imagekodec.heic

import io.github.yuroyami.imagekodec.ExperimentalHeifCodecApi
import io.github.yuroyami.imagekodec.HeifBuilder
import io.github.yuroyami.imagekodec.HeifCodec
import io.github.yuroyami.imagekodec.HeifCodecInfo
import io.github.yuroyami.imagekodec.HeifCodecSession
import io.github.yuroyami.imagekodec.HeifPlanes
import io.github.yuroyami.imagekodec.ImageFormat
import io.github.yuroyami.imagekodec.ImageKodec
import io.github.yuroyami.imagekodec.Tools
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HEIC image sequences against ffmpeg (#157). ffmpeg with libx265 writes a short sequence into an MP4,
 * whose `ftyp` is rewritten to the brands of a HEVC image sequence (`msf1`, `hevc`), as MP4Box's
 * `-brand` would. The frames of `decodeAnimation` must be ffmpeg's decode of the MP4, in the same
 * order, sample for sample after both go through the same conversion to RGB: ffmpeg's planes reach
 * the core through a codec that hands them over as they are.
 */
@OptIn(ExperimentalHeifCodecApi::class)
class HeicSequenceOracleTest {

    init {
        ImageKodecHeic.install()
    }

    private fun run(vararg args: String): Pair<Int, ByteArray> {
        val proc = ProcessBuilder(args.toList()).redirectErrorStream(true).start()
        val out = proc.inputStream.readBytes()
        if (!proc.waitFor(300, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return -1 to out
        }
        return proc.exitValue() to out
    }

    private fun temp(ext: String) = File.createTempFile("kite-heics", ext).apply { deleteOnExit() }

    private fun requireTools() {
        assumeTrue("ffmpeg not installed", Tools.hasAll("ffmpeg", "timeout"))
        val encoders = run(Tools.require("ffmpeg").path, "-hide_banner", "-encoders").second.decodeToString()
        if ("libx265" !in encoders) {
            if (System.getenv(Tools.REQUIRE_ENV) == "1") error("ffmpeg lacks libx265, and ${Tools.REQUIRE_ENV}=1 forbids skipping")
            assumeTrue("ffmpeg lacks libx265", false)
        }
    }

    /** ffmpeg under a hard kill, as x265 3.5 can hang for good (AGENTS.md). */
    private fun ffmpeg(vararg args: String) {
        val (code, log) = run(
            Tools.require("timeout").path, "-s", "KILL", "120", Tools.require("ffmpeg").path, "-hide_banner", "-loglevel", "error", *args,
        )
        assertEquals(0, code, "ffmpeg ${args.joinToString(" ")}: ${log.decodeToString()}")
    }

    /** 12 frames of 128 by 96 in 4:2:0 coded by x265 with [params], into an MP4 with [extra] options. */
    private fun mp4(params: String, vararg extra: String): ByteArray {
        val out = temp(".mp4")
        ffmpeg(
            "-f", "lavfi", "-i", "testsrc2=size=128x96:rate=10", "-frames:v", "12", "-pix_fmt", "yuv420p",
            "-c:v", "libx265", "-x265-params", "log-level=none:$params", *extra, "-y", out.path,
        )
        return out.readBytes()
    }

    private fun u32(b: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v
    }

    private fun fourcc(b: ByteArray, at: Int) = b.copyOfRange(at, at + 4).decodeToString()

    /** Where the payload of the first box of [type] starts, found by its type bytes. */
    private fun find(b: ByteArray, type: String): Int {
        val t = type.encodeToByteArray()
        for (i in 4..b.size - 4) if ((0 until 4).all { b[i + it] == t[it] }) return i + 4
        return -1
    }

    /**
     * [mp4] with the brands of a HEVC image sequence: major `msf1`, then `msf1`, `hevc` and `iso8` for
     * as many compatible brands as ffmpeg wrote. The box keeps its size, so no chunk offset moves.
     */
    private fun asHeifSequence(mp4: ByteArray): ByteArray {
        val out = mp4.copyOf()
        assertEquals("ftyp", fourcc(out, 4))
        val size = u32(out, 0).toInt()
        val brands = listOf("msf1", "hevc", "iso8")
        "msf1".encodeToByteArray().copyInto(out, 8)
        var at = 16
        var k = 0
        while (at + 4 <= size) {
            brands[minOf(k, brands.size - 1)].encodeToByteArray().copyInto(out, at)
            at += 4
            k++
        }
        assertTrue(k >= 2, "ffmpeg's ftyp has room for $k compatible brands")
        return out
    }

    /** ffmpeg's decode of [file], every frame in presentation order as 4:2:0 planes of 8 bits. */
    private fun ffmpegFrames(file: ByteArray, width: Int, height: Int): List<Array<ShortArray>> {
        val input = temp(".mp4").apply { writeBytes(file) }
        val out = temp(".yuv")
        ffmpeg("-i", input.path, "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", "yuv420p", "-y", out.path)
        val raw = out.readBytes()
        val sizes = intArrayOf(width * height, width * height / 4, width * height / 4)
        val frame = sizes.sum()
        assertEquals(0, raw.size % frame)
        return List(raw.size / frame) { f ->
            var at = f * frame
            Array(3) { c -> ShortArray(sizes[c]) { (raw[at + it].toInt() and 0xFF).toShort() }.also { at += sizes[c] } }
        }
    }

    /**
     * A codec `ffyu` configured by `hvcC` whose one-byte samples name a frame of [frames]: what the
     * HEVC codec reads from the same `hvcC` decides the colour and range, so only the planes differ.
     */
    private class Replay(val frames: List<Array<ShortArray>>, val width: Int, val height: Int) : HeifCodec {
        override val itemType = "ffyu"
        override val sampleEntryTypes = setOf("ffyu")
        override val configurationType = "hvcC"

        override fun inspect(configuration: ByteArray?, data: ByteArray?): HeifCodecInfo = HevcCodec.inspect(configuration, null)

        override fun open(configuration: ByteArray?): HeifCodecSession {
            val fullRange = HevcCodec.inspect(configuration, null).fullRange ?: false
            return object : HeifCodecSession {
                override fun decode(sample: ByteArray, cancellationCheck: (() -> Unit)?): HeifPlanes = HeifPlanes(
                    width, height, 8, 8, 1, 1, frames[sample[0].toInt()],
                    intArrayOf(width, width / 2, width / 2), fullRange,
                )
            }
        }
    }

    /** The `hvcC` box of [mp4], header included. */
    private fun hvcC(mp4: ByteArray): ByteArray {
        val at = find(mp4, "hvcC") - 8
        return mp4.copyOfRange(at, at + u32(mp4, at).toInt())
    }

    /** The composition offsets of [mp4]'s `ctts`, one a sample, or null without one. */
    private fun compositionOffsets(mp4: ByteArray): List<Long>? {
        val at = find(mp4, "ctts").takeIf { it >= 0 } ?: return null
        val runs = u32(mp4, at + 4).toInt()
        return (0 until runs).flatMap { r -> List(u32(mp4, at + 8 + 8 * r).toInt()) { u32(mp4, at + 12 + 8 * r) } }
    }

    @Test
    fun aSequenceWithBPicturesPlaysAsFfmpegDecodesIt() {
        requireTools()
        for (tag in listOf(emptyArray(), arrayOf("-tag:v", "hvc1"))) {
            val mp4 = mp4("keyint=30:bframes=3:b-adapt=0", *tag)
            val entry = if (tag.isEmpty()) "hev1" else "hvc1"
            assertTrue(find(mp4, entry) >= 0, "ffmpeg writes a '$entry' sample entry")
            // ffmpeg writes the handler of a video track, which the reader takes as it takes 'pict'.
            assertEquals("vide", fourcc(mp4, find(mp4, "hdlr") + 8))
            // B pictures: the samples show in another order than they decode.
            val offsets = compositionOffsets(mp4)
            assertTrue(offsets != null && offsets.toSet().size > 1, "ctts offsets $offsets")
            val data = asHeifSequence(mp4)
            assertEquals(ImageFormat.HEIC, ImageFormat.sniff(data))
            val info = ImageKodec.probe(data)
            assertTrue(info.isDecodable, info.unsupportedReason)
            assertEquals(12, info.frameCount)
            assertEquals(128 to 96, info.width to info.height)

            val frames = ffmpegFrames(mp4, 128, 96)
            assertEquals(12, frames.size)
            ImageKodec.registerHeifCodec(Replay(frames, 128, 96))
            val reference = HeifBuilder("msf1", listOf("msf1")).apply {
                primary = null
                addTrack(1, "ffyu", 128, 96, hvcC(mp4), List(12) { byteArrayOf(it.toByte()) }, handler = "vide")
            }.build()
            val expected = ImageKodec.decodeAnimation(reference).frames.map { it.bitmap.argb }
            val animation = ImageKodec.decodeAnimation(data)
            assertEquals(12, animation.frames.size)
            for (k in 0 until 12) assertContentEquals(expected[k], animation.frames[k].bitmap.argb, "$entry frame $k")
            assertEquals(List(12) { 100 }, animation.frames.map { it.delayMillis })
            assertEquals(1L, animation.loopCount, "ffmpeg's edit list does not repeat")
            assertContentEquals(expected[0], ImageKodec.decode(data).argb)
        }
    }

    @Test
    fun aSequenceOfPPicturesKeepsItsUnevenDurations() {
        requireTools()
        // Frame n at 20 n (n + 1) milliseconds: gaps of 40, 80, 120 and so on.
        val mp4 = mp4(
            "keyint=30:bframes=0", "-vf", "settb=1/1000,setpts=N*(N+1)*0.02/TB", "-fps_mode", "passthrough",
            "-enc_time_base", "1:1000", "-video_track_timescale", "1000",
        )
        // The encoder's time base is the frame rate's unless set, and would round every time to 100 ms.
        assertEquals(null, compositionOffsets(mp4)?.takeIf { it.toSet().size > 1 }, "P pictures alone need no reordering")
        val data = asHeifSequence(mp4)
        val animation = ImageKodec.decodeAnimation(data)
        assertEquals(12, animation.frames.size)
        assertEquals(List(11) { 40 * (it + 1) }, animation.frames.dropLast(1).map { it.delayMillis })
        val frames = ffmpegFrames(mp4, 128, 96)
        ImageKodec.registerHeifCodec(Replay(frames, 128, 96))
        val reference = HeifBuilder("msf1", listOf("msf1")).apply {
            primary = null
            addTrack(1, "ffyu", 128, 96, hvcC(mp4), List(frames.size) { byteArrayOf(it.toByte()) }, handler = "vide")
        }.build()
        val expected = ImageKodec.decodeAnimation(reference).frames.map { it.bitmap.argb }
        assertEquals(12, expected.size)
        for (k in 0 until 12) assertContentEquals(expected[k], animation.frames[k].bitmap.argb, "frame $k")
    }
}
