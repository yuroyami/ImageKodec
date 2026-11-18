package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.avif.HeifCodecs
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `encodeAvif` and `encodeHeic` of an animation (#177) on every target: AV1 image sequences read back by the
 * core's AVIF decoder. Lossless returns every frame exactly (edge sizes, gray, alpha, fully transparent frames, fewer
 * and more than 256 colours); lossy frames stay within a bound; delays and the loop count come back; fixed inputs
 * keep a committed hash, which also holds every target to the same bytes; the cancellation check stops an encode;
 * and `encodeHeic` without an HEVC sequence encoder says what is missing.
 */
@OptIn(ExperimentalHeifCodecApi::class)
class AvifSequenceEncodeTest {

    @AfterTest
    fun restore() {
        HeifCodecs.reset()
    }

    /** [frames] frames of [w] by [h] whose gradients and disc move a little each frame, with varied alpha when [alpha]. */
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

    /** Four colours in blocks that move, as a chart scrolled. */
    private fun fewColours(w: Int, h: Int, frames: Int) = KiteAnimation(w, h, (0 until frames).map { t ->
        KiteFrame(KiteBitmap(w, h, IntArray(w * h) { i ->
            val c = ((i % w + t) / 3 + (i / w) / 2) % 4
            intArrayOf(0xFF102030.toInt(), 0xFFF0E0D0.toInt(), 0xFF20C040.toInt(), 0xFFC02060.toInt())[c]
        }), 40, 4)
    }, loopCount = 2)

    @Test
    fun losslessReturnsEveryFrame() {
        val transparent = KiteAnimation(5, 3, List(2) { KiteFrame(KiteBitmap(5, 3, IntArray(15)), 30, 3) }, 1)
        for ((name, a) in listOf(
            "1x1, one frame" to moving(1, 1, 1), "one row" to moving(19, 1, 3), "one column" to moving(1, 11, 3),
            "odd with alpha" to moving(25, 13, 3, alpha = true), "transparent" to transparent, "gray" to gray(moving(14, 9, 3)),
            "gray with alpha" to gray(moving(14, 9, 2, alpha = true)), "four colours" to fewColours(23, 17, 3), "many colours" to moving(40, 30, 3),
        )) {
            val back = ImageKodec.decodeAnimation(ImageKodec.encodeAvif(a, HeifEncodeOptions(lossless = true, effort = 1)))
            assertEquals(a.frames.size, back.frames.size, name)
            for (i in a.frames.indices) assertContentEquals(a.frames[i].bitmap.argb, back.frames[i].bitmap.argb, "$name frame $i")
        }
    }

    @Test
    fun lossyFramesStayCloseWithTheirDelaysAndLoopCount() {
        for ((name, a) in listOf("photo" to moving(48, 40, 4), "odd with alpha" to moving(33, 17, 3, alpha = true), "gray" to gray(moving(30, 20, 3)))) {
            for (effort in 1..2) {
                val loop = effort.toLong() + 1
                val source = KiteAnimation(a.width, a.height, a.frames, loop)
                val back = ImageKodec.decodeAnimation(ImageKodec.encodeAvif(source, HeifEncodeOptions(quality = 90, effort = effort)))
                assertEquals(loop, back.loopCount, "$name at effort $effort: loop count")
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
        val a = moving(24, 16, 5)
        val back = ImageKodec.decodeAnimation(ImageKodec.encodeAvif(a, HeifEncodeOptions(lossless = true, effort = 1, keyFrameInterval = 2)))
        for (i in a.frames.indices) assertContentEquals(a.frames[i].bitmap.argb, back.frames[i].bitmap.argb, "frame $i")
    }

    @Test
    fun theFirstFrameIsTheStillImage() {
        val a = moving(20, 12, 3)
        val file = ImageKodec.encodeAvif(a, HeifEncodeOptions(lossless = true, effort = 1))
        assertContentEquals(a.frames[0].bitmap.argb, ImageKodec.decode(file).argb)
        assertEquals(3, ImageKodec.probe(file).frameCount)
    }

    @Test
    fun theBytesOfFixedInputsKeepTheirHash() {
        val files = listOf(
            ImageKodec.encodeAvif(moving(32, 24, 4), HeifEncodeOptions(quality = 50, effort = 1)),
            ImageKodec.encodeAvif(moving(20, 17, 3, alpha = true), HeifEncodeOptions(quality = 70)),
            ImageKodec.encodeAvif(gray(moving(16, 12, 3)), HeifEncodeOptions(quality = 40, effort = 3, keyFrameInterval = 2)),
            ImageKodec.encodeAvif(fewColours(18, 10, 3), HeifEncodeOptions(lossless = true, effort = 2)),
        )
        val hashes = files.map { f -> PictureHash.md5(f).joinToString("") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') } }
        assertEquals(EXPECTED, hashes)
    }

    @Test
    fun theCancellationCheckStopsTheEncode() {
        val stop = IllegalStateException("stop")
        var calls = 0
        val thrown = runCatching {
            ImageKodec.encodeAvif(moving(70, 40, 4), HeifEncodeOptions(effort = 1), cancellationCheck = { if (++calls == 6) throw stop })
        }.exceptionOrNull()
        assertTrue(thrown === stop, "$thrown")
    }

    @Test
    fun hostileArgumentsThrow() {
        val a = moving(8, 8, 2)
        val wrongSize = KiteAnimation(8, 8, listOf(a.frames[0], KiteFrame(KiteBitmap(7, 8, IntArray(56)), 40, 4)), 0)
        assertFailsWith<IllegalArgumentException> { ImageKodec.encodeAvif(wrongSize, HeifEncodeOptions()) }
        val negative = KiteAnimation(8, 8, listOf(KiteFrame(a.frames[0].bitmap, -1, 0)), 0)
        assertFailsWith<IllegalArgumentException> { ImageKodec.encodeAvif(negative, HeifEncodeOptions()) }
        assertFailsWith<IllegalArgumentException> { HeifEncodeOptions(keyFrameInterval = -1) }
    }

    @Test
    fun heicSequencesSayWhatIsMissing() {
        val a = moving(8, 8, 2)
        val none = assertFailsWith<UnsupportedImageException> { ImageKodec.encodeHeic(a, HeifEncodeOptions()) }
        assertTrue("imagekodec-heic" in (none.message ?: ""), none.message)
        // An HEVC encoder that codes still pictures only, which says the sequence encoder is missing.
        ImageKodec.registerHeifEncoder(object : HeifEncoder {
            override val itemType = "hvc1"
            override val configurationType = "hvcC"
            override val alphaUrn = "urn:mpeg:hevc:2015:auxid:1"
            override fun encode(planes: HeifPlanes, parameters: HeifEncodeParameters, cancellationCheck: (() -> Unit)?): HeifCodedPicture =
                error("not reached")
        })
        val stills = assertFailsWith<UnsupportedImageException> { ImageKodec.encodeHeic(a, HeifEncodeOptions()) }
        assertTrue("sequence encoder" in (stills.message ?: ""), stills.message)
    }

    private companion object {
        /** The MD5 of each file, written on the JVM; every other target must write the same bytes. */
        val EXPECTED = listOf(
            "f2be3b466699bbc93437c09e6cd93dec",
            "201251ae378e0d74bd7500bd43538473",
            "ed114b491ba7f3dd537c126f2abb1075",
            "1e3d58903b35634ac0607c44d87f67d9",
        )
    }
}
