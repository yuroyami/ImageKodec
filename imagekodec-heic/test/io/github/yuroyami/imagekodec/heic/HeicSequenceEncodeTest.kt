package io.github.yuroyami.imagekodec.heic

import io.github.yuroyami.imagekodec.HeifEncodeOptions
import io.github.yuroyami.imagekodec.ImageFormat
import io.github.yuroyami.imagekodec.ImageKodec
import io.github.yuroyami.imagekodec.KiteAnimation
import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.KiteFrame
import io.github.yuroyami.imagekodec.PictureHash
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `ImageKodec.encodeHeic` of an animation (#177) through this module's HEVC sequence encoder, read back by
 * `decodeAnimation` on every platform: lossless returns every frame exactly through P and B pictures, alpha
 * tracks included; lossy frames stay within a bound with their delays and loop count; the first frame is the
 * still image; fixed inputs keep a committed hash on every target; and a cancellation check stops the encode.
 */
class HeicSequenceEncodeTest {

    init {
        ImageKodecHeic.install()
    }

    /** [frames] frames of [w] by [h] whose gradients and disc move each frame, with varied alpha when [alpha]. */
    private fun moving(w: Int, h: Int, frames: Int, alpha: Boolean = false, delay: Int = 50): KiteAnimation {
        var s = w * 977 + h
        return KiteAnimation(w, h, (0 until frames).map { t ->
            val argb = IntArray(w * h) { i ->
                s = s * 1103515245 + 12345
                val n = (s ushr 16) and 7
                val x = i % w + t
                val y = i / w + (t shr 1)
                val disc = (x - w / 2) * (x - w / 2) + (y - h / 3) * (y - h / 3) < w * h / 8
                val r = (x * 255 / maxOf(1, w) + n).coerceIn(0, 255)
                val g = (y * 255 / maxOf(1, h) + (if (disc) 60 else 0)).coerceIn(0, 255)
                val b = ((x + y) * 3 + n * 2).coerceIn(0, 255)
                val a = if (alpha) (if (disc) 255 else (x * 7 + y * 3) and 0xFF) else 255
                (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            KiteFrame(KiteBitmap(w, h, argb), delay + 10 * t, (delay + 10 * t) / 10)
        }, loopCount = 0)
    }

    private fun gray(a: KiteAnimation) = KiteAnimation(a.width, a.height, a.frames.map { f ->
        KiteFrame(KiteBitmap(f.bitmap.width, f.bitmap.height, IntArray(f.bitmap.argb.size) { i ->
            val p = f.bitmap.argb[i]
            (p and 0xFF000000.toInt()) or (((p ushr 8) and 0xFF) * 0x010101)
        }), f.delayMillis, f.delayRawCentiseconds)
    }, a.loopCount)

    @Test
    fun losslessReturnsEveryFrame() {
        val transparent = KiteAnimation(5, 3, List(3) { KiteFrame(KiteBitmap(5, 3, IntArray(15)), 30, 3) }, 1)
        for ((name, a) in listOf(
            "1x1, one frame" to moving(1, 1, 1), "one row" to moving(19, 1, 3), "one column" to moving(1, 11, 3),
            "odd with alpha, B pictures" to moving(25, 13, 6, alpha = true), "transparent" to transparent,
            "gray, B pictures" to gray(moving(14, 9, 7)), "many colours, two groups" to moving(40, 30, 9),
        )) {
            val file = ImageKodec.encodeHeic(a, HeifEncodeOptions(lossless = true, effort = 1))
            assertEquals(ImageFormat.HEIC, ImageKodec.detect(file), name)
            val back = ImageKodec.decodeAnimation(file)
            assertEquals(a.frames.size, back.frames.size, name)
            for (i in a.frames.indices) {
                assertContentEquals(a.frames[i].bitmap.argb, back.frames[i].bitmap.argb, "$name frame $i")
                assertEquals(a.frames[i].delayMillis, back.frames[i].delayMillis, "$name frame $i delay")
            }
        }
    }

    @Test
    fun lossyFramesStayCloseWithTheirDelaysAndLoopCount() {
        for ((name, a) in listOf("photo" to moving(48, 40, 6), "odd with alpha" to moving(33, 17, 5, alpha = true), "gray" to gray(moving(30, 20, 5)))) {
            for (effort in 1..2) {
                val loop = effort.toLong() + 1
                val source = KiteAnimation(a.width, a.height, a.frames, loop)
                val back = ImageKodec.decodeAnimation(ImageKodec.encodeHeic(source, HeifEncodeOptions(quality = 90, effort = effort)))
                assertEquals(loop, back.loopCount, "$name at effort $effort: loop count")
                assertEquals(source.frames.size, back.frames.size, "$name at effort $effort: frames")
                for ((i, f) in source.frames.withIndex()) {
                    val out = back.frames[i]
                    assertEquals(f.delayMillis, out.delayMillis, "$name at effort $effort: frame $i delay")
                    var sum = 0L
                    for (k in f.bitmap.argb.indices) for (shift in intArrayOf(0, 8, 16, 24)) {
                        sum += abs(((f.bitmap.argb[k] ushr shift) and 0xFF) - ((out.bitmap.argb[k] ushr shift) and 0xFF))
                    }
                    val mean = sum.toDouble() / (f.bitmap.argb.size * 4)
                    assertTrue(mean < 4.0, "$name at effort $effort: frame $i mean error $mean")
                }
            }
        }
    }

    @Test
    fun aKeyFrameIntervalStillDecodes() {
        val a = moving(24, 16, 7)
        val back = ImageKodec.decodeAnimation(ImageKodec.encodeHeic(a, HeifEncodeOptions(lossless = true, effort = 1, keyFrameInterval = 3)))
        for (i in a.frames.indices) assertContentEquals(a.frames[i].bitmap.argb, back.frames[i].bitmap.argb, "frame $i")
    }

    @Test
    fun theFirstFrameIsTheStillImage() {
        val a = moving(20, 12, 5)
        val file = ImageKodec.encodeHeic(a, HeifEncodeOptions(lossless = true, effort = 1))
        assertContentEquals(a.frames[0].bitmap.argb, ImageKodec.decode(file).argb)
        assertEquals(5, ImageKodec.probe(file).frameCount)
    }

    @Test
    fun theBytesOfFixedInputsKeepTheirHash() {
        val files = listOf(
            ImageKodec.encodeHeic(moving(32, 24, 6), HeifEncodeOptions(quality = 50, effort = 1)),
            ImageKodec.encodeHeic(moving(20, 17, 5, alpha = true), HeifEncodeOptions(quality = 70)),
            ImageKodec.encodeHeic(gray(moving(16, 12, 5)), HeifEncodeOptions(quality = 40, effort = 3, keyFrameInterval = 2)),
            ImageKodec.encodeHeic(moving(18, 10, 4), HeifEncodeOptions(lossless = true, effort = 2)),
        )
        val hashes = files.map { f -> PictureHash.md5(f).joinToString("") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') } }
        assertEquals(EXPECTED, hashes)
    }

    @Test
    fun theCancellationCheckStopsTheEncode() {
        val stop = IllegalStateException("stop")
        var calls = 0
        val thrown = runCatching {
            ImageKodec.encodeHeic(moving(70, 40, 4), HeifEncodeOptions(effort = 1), cancellationCheck = { if (++calls == 6) throw stop })
        }.exceptionOrNull()
        assertTrue(thrown === stop, "$thrown")
    }

    @Test
    fun hostileArgumentsThrow() {
        val a = moving(8, 8, 2)
        val wrongSize = KiteAnimation(8, 8, listOf(a.frames[0], KiteFrame(KiteBitmap(7, 8, IntArray(56)), 40, 4)), 0)
        assertFailsWith<IllegalArgumentException> { ImageKodec.encodeHeic(wrongSize, HeifEncodeOptions()) }
        val negative = KiteAnimation(8, 8, listOf(KiteFrame(a.frames[0].bitmap, -1, 0)), 0)
        assertFailsWith<IllegalArgumentException> { ImageKodec.encodeHeic(negative, HeifEncodeOptions()) }
    }

    private companion object {
        /** The MD5 of each file, written on the JVM; every other target must write the same bytes. */
        val EXPECTED = listOf(
            "1f4d3d1f52aa95e78df42059c4652009",
            "558b6a7bf290e98924e026966c9dfd27",
            "d662aa7b224f4db16cdad45498a86607",
            "957dbb4f499f3b5b15faeaf545c8885d",
        )
    }
}
