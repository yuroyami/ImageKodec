package io.github.yuroyami.imagekodec

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The APNG writer (#64) read by two other implementations: Pillow, which also reports each
 * frame's duration and the loop count, and ffmpeg's APNG decoder. Both composite the frames
 * themselves, so a wrong rectangle, blend or disposal shows as wrong pixels. Skips cleanly
 * without python3 with Pillow or ffmpeg.
 */
class ApngOracleTest {

    private fun run(vararg args: String): Pair<Int, ByteArray> {
        val proc = ProcessBuilder(args.toList()).redirectErrorStream(false).start()
        val out = ByteArrayOutputStream()
        val err = Thread { proc.errorStream.readBytes() }.apply { start() }
        proc.inputStream.copyTo(out)
        err.join()
        if (!proc.waitFor(120, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return -1 to ByteArray(0)
        }
        return proc.exitValue() to out.toByteArray()
    }

    private fun tools(): Boolean {
        if (!Tools.hasAll("python3", "ffmpeg")) return false
        val pillow = run(Tools.require("python3").path, "-c", "import PIL").first == 0
        if (!pillow && System.getenv(Tools.REQUIRE_ENV) == "1") error("python3 has no Pillow, and ${Tools.REQUIRE_ENV}=1 forbids skipping")
        return pillow
    }

    /** Pillow's reading: the loop count, then each frame's duration in milliseconds and its RGBA samples. */
    private val pillowScript = """
import sys, struct
from PIL import Image
im = Image.open(sys.argv[1])
out = sys.stdout.buffer
out.write(struct.pack('<ii', im.n_frames, im.info.get('loop', -1)))
for i in range(im.n_frames):
    im.seek(i)
    rgba = im.convert('RGBA')
    out.write(struct.pack('<i', int(round(im.info.get('duration', 0)))))
    out.write(rgba.tobytes())
""".trimIndent()

    private fun scene(w: Int, h: Int, t: Int, translucent: Boolean): KiteBitmap = KiteBitmap(w, h, IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        val inSquare = x - 5 * t in 6..30 && y - 2 * t in 4..20
        val inDot = (x - 50 + 4 * t) * (x - 50 + 4 * t) + (y - 30) * (y - 30) < 40
        when {
            inSquare -> argb(if (translucent) 0x90 else 0xFF, 240, 30 + 25 * t, 50)
            inDot -> argb(0xFF, 20, 200, 255 - 20 * t)
            else -> argb((x * 3 + y * 7) and 0xFF or 0x20, x * 4 and 0xFF, y * 6 and 0xFF, 120)
        }
    })

    private fun check(name: String, animation: KiteAnimation) {
        val file = File.createTempFile("kite-apng", ".png").apply { deleteOnExit() }
        file.writeBytes(ImageKodec.encodePng(animation))
        val w = animation.width
        val h = animation.height

        val (code, pil) = run(Tools.require("python3").path, "-c", pillowScript, file.path)
        assertEquals(0, code, "$name: Pillow could not read the APNG")
        fun i32(at: Int) = (pil[at].toInt() and 255) or ((pil[at + 1].toInt() and 255) shl 8) or
            ((pil[at + 2].toInt() and 255) shl 16) or (pil[at + 3].toInt() shl 24)
        assertEquals(animation.frames.size, i32(0), "$name: Pillow's frame count")
        assertEquals(animation.loopCount, i32(4).toLong(), "$name: Pillow's loop count")
        var at = 8
        for ((n, frame) in animation.frames.withIndex()) {
            assertEquals(frame.delayMillis, i32(at), "$name: Pillow's duration of frame $n")
            at += 4
            for (p in 0 until w * h) {
                val px = argb(pil[at + 3].toInt() and 255, pil[at].toInt() and 255, pil[at + 1].toInt() and 255, pil[at + 2].toInt() and 255)
                at += 4
                if (px != frame.bitmap.argb[p]) {
                    throw AssertionError("$name: Pillow reads frame $n pixel (${p % w}, ${p / w}) as ${px.toUInt().toString(16)}, written ${frame.bitmap.argb[p].toUInt().toString(16)}")
                }
            }
        }

        val (ffCode, raw) = run(
            Tools.require("ffmpeg").path, "-hide_banner", "-loglevel", "error", "-f", "apng", "-i", file.path,
            "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", "rgba", "-",
        )
        assertEquals(0, ffCode, "$name: ffmpeg could not read the APNG")
        assertEquals(animation.frames.size * w * h * 4, raw.size, "$name: ffmpeg's frames")
        for ((n, frame) in animation.frames.withIndex()) {
            for (p in 0 until w * h) {
                val o = (n * w * h + p) * 4
                val px = argb(raw[o + 3].toInt() and 255, raw[o].toInt() and 255, raw[o + 1].toInt() and 255, raw[o + 2].toInt() and 255)
                if (px != frame.bitmap.argb[p]) {
                    throw AssertionError("$name: ffmpeg reads frame $n pixel (${p % w}, ${p / w}) as ${px.toUInt().toString(16)}, written ${frame.bitmap.argb[p].toUInt().toString(16)}")
                }
            }
        }
    }

    private fun animation(frames: List<KiteBitmap>, delays: List<Int>, loops: Long) = KiteAnimation(
        frames[0].width, frames[0].height, frames.mapIndexed { i, b -> KiteFrame(b, delays[i], (delays[i] + 5) / 10) }, loops,
    )

    @Test
    fun translucentMotion() {
        assumeTrue("python3 with Pillow or ffmpeg not installed", tools())
        check("translucent motion", animation(List(6) { scene(64, 40, it, translucent = true) }, listOf(100, 40, 250, 33, 1000, 70), 0))
    }

    @Test
    fun opaqueChangesDrawnOver() {
        assumeTrue("python3 with Pillow or ffmpeg not installed", tools())
        check("opaque changes", animation(List(5) { scene(64, 40, it, translucent = false) }, List(5) { 80 }, 2))
    }

    @Test
    fun opaqueDotsDrawnOverATranslucentCanvas() {
        assumeTrue("python3 with Pillow or ffmpeg not installed", tools())
        val frames = List(5) { t ->
            val b = scene(64, 40, 0, translucent = true).argb.copyOf()
            for (y in 0 until 4) for (x in 0 until 4) {
                b[y * 64 + x] = argb(0xFF, 50 * t, 10, 200)
                b[(36 + y) * 64 + 60 + x] = argb(0xFF, 10, 50 * t, 90)
            }
            KiteBitmap(64, 40, b)
        }
        check("opaque dots", animation(frames, List(5) { 60 }, 0))
    }

    @Test
    fun anOpaqueAnimationWithRepeatedFrames() {
        assumeTrue("python3 with Pillow or ffmpeg not installed", tools())
        val frames = List(4) { t -> KiteBitmap(48, 32, scene(48, 32, t % 2, translucent = false).argb.map { it or (0xFF shl 24) }.toIntArray()) }
        check("opaque repeats", animation(frames + frames[3], listOf(100, 100, 120, 140, 160), 1))
    }

    @Test
    fun aGifRoundTripsThroughApng() {
        assumeTrue("python3 with Pillow or ffmpeg not installed", tools())
        val gif = ImageKodec.encodeGif(animation(List(4) { scene(40, 30, it, translucent = false) }, listOf(100, 200, 300, 400), 0))
        check("from a GIF", ImageKodec.decodeAnimation(gif))
    }
}
