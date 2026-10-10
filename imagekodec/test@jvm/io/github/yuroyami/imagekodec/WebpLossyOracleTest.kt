package io.github.yuroyami.imagekodec

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The lossy WebP decoder against libwebp itself (#11). VP8 is a fully specified decoder and dwebp
 * writes its output with a fixed upsampler, so every pixel must match: there is no tolerance.
 *
 * cwebp writes stills across its settings: quality, the simple and the strong loop filter, filter
 * strength and sharpness, segments, noise shaping, method and presets, and with transparency every
 * ALPH method, filter and level quantization. libvpx, through ffmpeg, writes key frames cwebp never
 * does, with up to eight token partitions and its own mode and filter choices. img2webp writes lossy
 * animations, which anim_dump renders frame by frame. Skips when the tools are absent.
 */
class WebpLossyOracleTest {

    private val dir: File = Files.createTempDirectory("imagekodec-vp8").toFile().apply { deleteOnExit() }

    private fun run(vararg args: String) {
        val log = File(dir, "log.txt")
        val process = ProcessBuilder(args.toList()).redirectErrorStream(true).redirectOutput(log).start()
        val finished = process.waitFor(120, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        assertTrue(finished, "${args.first()} timed out")
        assertEquals(0, process.exitValue(), "${args.joinToString(" ")}: ${log.readText()}")
    }

    /** A `dwebp -pam` or `anim_dump -pam` file as ARGB. */
    private fun readPam(file: File): KiteBitmap {
        val bytes = file.readBytes()
        val marker = "ENDHDR\n".encodeToByteArray()
        val at = (0..bytes.size - marker.size).first { i -> marker.indices.all { bytes[i + it] == marker[it] } } + marker.size
        val header = String(bytes, 0, at)
        fun field(name: String) = header.lineSequence().first { it.startsWith("$name ") }.substringAfter(' ').trim().toInt()
        val w = field("WIDTH")
        val h = field("HEIGHT")
        val depth = field("DEPTH")
        return KiteBitmap(w, h, IntArray(w * h) { i ->
            val o = at + i * depth
            val a = if (depth == 4) bytes[o + 3].toInt() and 0xFF else 0xFF
            (a shl 24) or ((bytes[o].toInt() and 0xFF) shl 16) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or (bytes[o + 2].toInt() and 0xFF)
        })
    }

    private fun assertSamePixels(expected: KiteBitmap, actual: KiteBitmap, label: String) {
        assertEquals(expected.width to expected.height, actual.width to actual.height, "$label size")
        var differ = 0
        var first = -1
        for (i in expected.argb.indices) if (expected.argb[i] != actual.argb[i]) {
            if (first < 0) first = i
            differ++
        }
        if (differ > 0) {
            val x = first % expected.width
            val y = first / expected.width
            fail(
                "$label: $differ of ${expected.argb.size} pixels differ, first at ($x, $y): " +
                    "dwebp ${expected.argb[first].toUInt().toString(16)}, ours ${actual.argb[first].toUInt().toString(16)}",
            )
        }
    }

    /** Decode [webp] with dwebp and with ImageKodec, and require the same pixels. */
    private fun check(label: String, webp: File) {
        val pam = File(dir, "out.pam")
        run(Tools.require("dwebp").path, webp.path, "-pam", "-o", pam.path)
        assertSamePixels(readPam(pam), ImageKodec.decode(webp.readBytes()), label)
    }

    private fun source(name: String, bitmap: KiteBitmap): File =
        File(dir, "$name.png").apply { writeBytes(ImageKodec.encodePng(bitmap)) }

    /** Smooth shading with fine noise and a few hard edges, as a photograph has. */
    private fun photo(w: Int, h: Int, seed: Int): KiteBitmap {
        val r = Random(seed)
        return KiteBitmap(w, h, IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val n = r.nextInt(-12, 13)
            val edge = if ((x / 23 + y / 17) % 3 == 0) 60 else 0
            argb(0xFF, (x * 255 / w + n + edge).coerceIn(0, 255), (y * 255 / h - n).coerceIn(0, 255), ((x xor y) and 0xFF + n).coerceIn(0, 255))
        })
    }

    /** Thin dark strokes on light ground, as text and line art have. */
    private fun lines(w: Int, h: Int): KiteBitmap = KiteBitmap(w, h, IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        val ink = (y % 9 < 2 && (x / 5) % 3 != 0) || (x % 13 == 0) || ((x + 2 * y) % 31 < 2)
        if (ink) argb(0xFF, 20, 30, 90) else argb(0xFF, 250, 248, 235)
    })

    /** Colour with opacity that ramps, cuts out in holes and is half-transparent in a band. */
    private fun translucent(w: Int, h: Int, seed: Int): KiteBitmap {
        val r = Random(seed)
        return KiteBitmap(w, h, IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val a = when {
                (x - w / 3) * (x - w / 3) + (y - h / 2) * (y - h / 2) < (h / 5) * (h / 5) -> 0
                y in h / 4 until h / 3 -> 128
                else -> (x * 255 / maxOf(1, w - 1)).coerceIn(0, 255)
            }
            argb(a, (x * 7 + r.nextInt(8)) and 0xFF, (y * 5) and 0xFF, 160)
        })
    }

    @Test
    fun cwebpStillsMatchDwebpAtEverySetting() {
        assumeTrue("libwebp tools not installed", Tools.hasAll("cwebp", "dwebp"))
        val images = listOf(
            "photo" to photo(333, 211, 1),
            "lines" to lines(64, 64),
            "one" to KiteBitmap(1, 1, intArrayOf(argb(0xFF, 200, 30, 60))),
            "tiny" to photo(2, 3, 2),
            "odd" to photo(17, 9, 3),
            "macroblock" to photo(16, 16, 4),
            "past" to photo(33, 17, 5),
        )
        val settings = listOf(
            listOf("-q", "75"),
            listOf("-q", "0"),
            listOf("-q", "100"),
            listOf("-q", "40", "-nostrong"),
            listOf("-q", "60", "-f", "100", "-sharpness", "7"),
            listOf("-q", "60", "-f", "100", "-sharpness", "3", "-strong"),
            listOf("-q", "60", "-f", "0"),
            listOf("-q", "70", "-segments", "1"),
            listOf("-q", "70", "-sns", "100", "-segments", "4"),
            listOf("-q", "30", "-sns", "0"),
            listOf("-q", "80", "-m", "0"),
            listOf("-q", "80", "-m", "6", "-pass", "3"),
            listOf("-preset", "text", "-q", "90"),
            listOf("-preset", "photo", "-q", "50"),
            listOf("-q", "65", "-af"),
        )
        for ((name, bitmap) in images) {
            val png = source(name, bitmap)
            for (args in settings) {
                val webp = File(dir, "$name.webp")
                run(Tools.require("cwebp").path, "-quiet", *args.toTypedArray(), png.path, "-o", webp.path)
                check("$name ${args.joinToString(" ")}", webp)
            }
        }
    }

    @Test
    fun transparentStillsMatchDwebpForEveryAlphaCoding() {
        assumeTrue("libwebp tools not installed", Tools.hasAll("cwebp", "dwebp"))
        val images = listOf("translucent" to translucent(99, 51, 6), "small" to translucent(5, 7, 7))
        val settings = listOf(
            listOf("-q", "70"),
            listOf("-q", "70", "-alpha_method", "0"),
            listOf("-q", "70", "-alpha_method", "0", "-alpha_filter", "none"),
            listOf("-q", "70", "-alpha_filter", "fast"),
            listOf("-q", "70", "-alpha_filter", "best"),
            listOf("-q", "70", "-alpha_q", "50"),
            listOf("-q", "70", "-alpha_q", "0", "-alpha_method", "0", "-alpha_filter", "best"),
            listOf("-q", "90", "-exact"),
        )
        for ((name, bitmap) in images) {
            val png = source(name, bitmap)
            for (args in settings) {
                val webp = File(dir, "$name.webp")
                run(Tools.require("cwebp").path, "-quiet", *args.toTypedArray(), png.path, "-o", webp.path)
                check("$name ${args.joinToString(" ")}", webp)
            }
        }
    }

    /** The first frame of an IVF file, wrapped as a simple-format WebP. */
    private fun ivfKeyFrameAsWebp(ivf: File): ByteArray {
        val bytes = ivf.readBytes()
        val headerLength = (bytes[6].toInt() and 0xFF) or ((bytes[7].toInt() and 0xFF) shl 8)
        val at = headerLength
        val size = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)
        val frame = bytes.copyOfRange(at + 12, at + 12 + size)
        fun le32(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())
        val chunk = "VP8 ".encodeToByteArray() + le32(frame.size) + frame + (if (frame.size % 2 == 1) byteArrayOf(0) else ByteArray(0))
        return "RIFF".encodeToByteArray() + le32(4 + chunk.size) + "WEBP".encodeToByteArray() + chunk
    }

    @Test
    fun libvpxKeyFramesWithSeveralTokenPartitionsMatchDwebp() {
        assumeTrue("libwebp tools or ffmpeg not installed", Tools.hasAll("dwebp", "ffmpeg"))
        // ffmpeg passes -slices to libvpx as the token partition count. Constant quality leaves libvpx's
        // loop filter off on a key frame; a tight bitrate turns it on, at levels 3, 7 and 36 here, with
        // the reference and mode filter deltas that cwebp never writes.
        val png = source("vpx", photo(320, 240, 9))
        val settings = listOf(
            listOf("-slices", "1", "-crf", "10", "-b:v", "0"),
            listOf("-slices", "2", "-b:v", "20k", "-qmin", "40", "-qmax", "63"),
            listOf("-slices", "4", "-b:v", "50k", "-qmin", "20", "-qmax", "50"),
            listOf("-slices", "8", "-b:v", "10k", "-qmin", "63", "-qmax", "63"),
        )
        for (args in settings) {
            val ivf = File(dir, "vpx.ivf")
            run(
                Tools.require("ffmpeg").path, "-y", "-loglevel", "error", "-i", png.path, "-pix_fmt", "yuv420p",
                "-c:v", "libvpx", *args.toTypedArray(), "-frames:v", "1", "-f", "ivf", ivf.path,
            )
            val webp = File(dir, "vpx.webp").apply { writeBytes(ivfKeyFrameAsWebp(ivf)) }
            check("libvpx ${args.joinToString(" ")}", webp)
        }
    }

    @Test
    fun lossyAnimationsMatchAnimDumpFrameByFrame() {
        assumeTrue("libwebp tools not installed", Tools.hasAll("img2webp", "anim_dump"))
        val frames = (0 until 4).map { i ->
            source("frame$i", if (i % 2 == 0) photo(48, 40, 10 + i) else translucent(48, 40, 10 + i))
        }
        for (mode in listOf(listOf("-lossy", "-q", "60"), listOf("-mixed"))) {
            val webp = File(dir, "anim.webp")
            run(Tools.require("img2webp").path, "-loop", "2", *mode.toTypedArray(), "-d", "80", *frames.map { it.path }.toTypedArray(), "-o", webp.path)
            val out = File(dir, "dump").apply { deleteRecursively(); mkdirs() }
            run(Tools.require("anim_dump").path, "-folder", out.path, "-prefix", "f", "-pam", webp.path)
            val animation = ImageKodec.decodeAnimation(webp.readBytes())
            val dumped = out.listFiles()!!.filter { it.name.endsWith(".pam") }.sortedBy { it.name }
            assertEquals(dumped.size, animation.frames.size, "frames with $mode")
            for ((i, file) in dumped.withIndex()) {
                // anim_dump sets every fully transparent pixel to 0 before it writes a frame, so its frames
                // compare (CleanupTransparentPixels in examples/anim_util.c); the decoder keeps their colour.
                val ours = animation.frames[i].bitmap
                val cleaned = KiteBitmap(ours.width, ours.height, IntArray(ours.argb.size) { j -> ours.argb[j].let { if (it ushr 24 == 0) 0 else it } })
                assertSamePixels(readPam(file), cleaned, "frame $i with $mode")
            }
        }
    }
}
