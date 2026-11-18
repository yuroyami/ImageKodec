package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.Vp8Decoder
import io.github.yuroyami.imagekodec.codec.Vp8Encoder
import io.github.yuroyami.imagekodec.codec.Vp8Import
import io.github.yuroyami.imagekodec.internal.flate.Crc32
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The lossy WebP writer, its alpha and the animated WebP writer (#178), on every target. */
class WebpLossyEncoderTest {

    private fun photo(w: Int, h: Int, alpha: Boolean = false, seed: Int = 0x2545F491): KiteBitmap {
        var state = seed
        return KiteBitmap(w, h, IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            state = state xor (state shl 13); state = state xor (state ushr 17); state = state xor (state shl 5)
            val n = state and 3
            argb(
                if (alpha) (x * 5 + y * 3) and 0xFF else 0xFF,
                (x * 255 / maxOf(1, w - 1) + n) and 0xFF, (y * 255 / maxOf(1, h - 1) + n) and 0xFF, ((x * y) / 7 + n) and 0xFF,
            )
        })
    }

    /** The mean absolute difference of the colour channels of the visible pixels. */
    private fun meanError(a: KiteBitmap, b: KiteBitmap): Double {
        var sum = 0L
        var n = 0
        for (i in a.argb.indices) {
            if (a.argb[i] ushr 24 == 0) continue
            for (shift in 0..16 step 8) {
                val d = ((a.argb[i] ushr shift) and 0xFF) - ((b.argb[i] ushr shift) and 0xFF)
                sum += if (d < 0) -d else d
                n++
            }
        }
        return if (n == 0) 0.0 else sum.toDouble() / n
    }

    /** The encoder predicts from exactly the pixels the decoder rebuilds, at every effort. */
    @Test
    fun theEncodersReconstructionIsTheDecoders() {
        val pictures = listOf(photo(48, 40), photo(17, 33), PhotoScenes.bokeh(70, 50).bitmap, photo(1, 1), photo(130, 3))
        for ((n, b) in pictures.withIndex()) for (quality in listOf(0, 30, 75, 100)) for (effort in 1..3) {
            val planes = Vp8Import.planes(b.argb, b.width, b.height, sharp = false)
            val r = Vp8Encoder.encodeForTest(planes, quality, effort, filterOff = true)
            val d = Vp8Decoder.decode(r.bytes, 0, r.bytes.size)
            val what = "picture $n at quality $quality, effort $effort"
            assertContentEquals(r.y, d.y, "$what: Y")
            assertContentEquals(r.u, d.u, "$what: U")
            assertContentEquals(r.v, d.v, "$what: V")
        }
    }

    @Test
    fun edgeSizesReadBackCloseToTheirSource() {
        val pictures = listOf(
            PhotoScenes.bokeh(1, 1), PhotoScenes.bokeh(1, 37), PhotoScenes.bokeh(53, 1), PhotoScenes.foliage(31, 17),
            PhotoScenes.landscape(16, 16), PhotoScenes.night(33, 65), PhotoScenes.landscape(97, 61),
        ).map { it.bitmap }
        for (b in pictures) for (effort in 1..3) {
            val data = ImageKodec.encodeWebp(b, WebpEncodeOptions(quality = 90, effort = effort))
            assertEquals(ImageFormat.WEBP, ImageKodec.detect(data))
            val back = ImageKodec.decode(data)
            assertEquals(b.width to b.height, back.width to back.height, "$b")
            assertTrue(back.argb.all { it ushr 24 == 0xFF }, "$b: an opaque picture reads back opaque")
            val error = meanError(b, back)
            assertTrue(error < 8.0, "$b at effort $effort: mean error $error")
        }
    }

    @Test
    fun qualityOrdersSizeAndError() {
        val b = PhotoScenes.portrait(96, 128).bitmap
        val sizes = listOf(0, 25, 50, 75, 100).map { ImageKodec.encodeWebp(b, it).size }
        assertEquals(sizes.sorted(), sizes, "sizes $sizes")
        val low = meanError(b, ImageKodec.decode(ImageKodec.encodeWebp(b, 10)))
        val high = meanError(b, ImageKodec.decode(ImageKodec.encodeWebp(b, 95)))
        assertTrue(high < low && high < 2.5, "mean error $high at 95, $low at 10")
    }

    @Test
    fun fewColoursAndManyReadBack() {
        val three = KiteBitmap(40, 30, IntArray(1200) { i -> listOf(argb(255, 200, 30, 30), argb(255, 20, 200, 40), argb(255, 10, 10, 220))[(i % 40) / 14] })
        val many = PhotoScenes.foliage(64, 64).bitmap
        for (b in listOf(three, many)) {
            val back = ImageKodec.decode(ImageKodec.encodeWebp(b, 100))
            assertTrue(meanError(b, back) < 4.0, "mean error ${meanError(b, back)}")
        }
    }

    @Test
    fun alphaIsExactByDefaultWithEveryFilter() {
        val b = photo(45, 38, alpha = true)
        for (filter in WebpAlphaFilter.entries) {
            val back = ImageKodec.decode(ImageKodec.encodeWebp(b, WebpEncodeOptions(quality = 60, alphaFilter = filter)))
            for (i in b.argb.indices) assertEquals(b.argb[i] ushr 24, back.argb[i] ushr 24, "$filter: alpha of pixel $i")
        }
    }

    @Test
    fun reducedAlphaKeepsOpaqueAndClearPixels() {
        val b = photo(64, 32, alpha = true)
        for (aq in listOf(0, 20, 50, 90)) {
            val back = ImageKodec.decode(ImageKodec.encodeWebp(b, WebpEncodeOptions(quality = 70, alphaQuality = aq)))
            val levels = back.argb.map { it ushr 24 }.toSet()
            assertTrue(levels.size <= 2 + aq * 254 / 100, "$aq: ${levels.size} levels")
            for (i in b.argb.indices) {
                val a = b.argb[i] ushr 24
                if (a == 0 || a == 255) assertEquals(a, back.argb[i] ushr 24, "$aq: pixel $i")
            }
        }
    }

    @Test
    fun aFullyTransparentPictureReadsBackTransparent() {
        val clear = KiteBitmap(20, 20, IntArray(400) { 0x00FF0000 })
        val back = ImageKodec.decode(ImageKodec.encodeWebp(clear, 75))
        assertTrue(back.argb.all { it ushr 24 == 0 })
    }

    @Test
    fun metadataGoesInTheExtendedFormat() {
        val meta = ImageMetadata(exif = byteArrayOf(0x4D, 0x4D, 0, 42, 0, 0, 0, 8, 0, 0), xmp = "<x:xmpmeta/>", icc = ByteArray(5) { it.toByte() })
        val data = ImageKodec.encodeWebp(photo(20, 10), WebpEncodeOptions(quality = 50), meta)
        val read = ImageKodec.readMetadata(data)
        assertContentEquals(meta.exif, read.exif)
        assertEquals(meta.xmp, read.xmp)
        assertContentEquals(meta.icc, read.icc)
        assertEquals(20 to 10, ImageKodec.decode(data).let { it.width to it.height })
    }

    @Test
    fun theLosslessOptionWritesWhatTheLosslessCallWrites() {
        val b = photo(30, 20, alpha = true)
        assertContentEquals(ImageKodec.encodeWebp(b), ImageKodec.encodeWebp(b, WebpEncodeOptions(lossless = true)))
    }

    /** A square that moves over a gradient, with a translucent corner, a still frame and a frame of its own. */
    private fun animation(): KiteAnimation {
        val w = 41
        val h = 29
        fun frame(t: Int): KiteBitmap = KiteBitmap(w, h, IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            when {
                x < 4 && y < 4 -> argb(0x80, 10, 20, 30)
                x in (3 + t * 5) until (13 + t * 5) && y in (5 + t * 2) until (15 + t * 2) -> argb(255, 240, 20, 20)
                else -> argb(255, x * 6, y * 8, 100)
            }
        })
        val frames = listOf(frame(0), frame(1), frame(1), frame(2), PhotoScenes.bokeh(w, h).bitmap, frame(3))
        return KiteAnimation(w, h, frames.mapIndexed { i, b -> KiteFrame(b, 100 + i * 30, (100 + i * 30) / 10) }, loopCount = 3)
    }

    @Test
    fun aLosslessAnimationReadsBackExactly() {
        val anim = animation()
        val back = ImageKodec.decodeAnimation(ImageKodec.encodeWebp(anim, WebpEncodeOptions(lossless = true)))
        assertEquals(anim.frames.size, back.frames.size)
        assertEquals(3L, back.loopCount)
        for ((i, f) in anim.frames.withIndex()) {
            assertContentEquals(f.bitmap.argb, back.frames[i].bitmap.argb, "frame $i")
            assertEquals(f.delayMillis, back.frames[i].delayMillis, "delay of frame $i")
        }
    }

    @Test
    fun aLossyAnimationReadsBackWithinItsQuality() {
        val anim = animation()
        for (effort in 1..3) {
            val back = ImageKodec.decodeAnimation(ImageKodec.encodeWebp(anim, WebpEncodeOptions(quality = 85, effort = effort)))
            assertEquals(anim.frames.size, back.frames.size)
            assertEquals(3L, back.loopCount)
            for ((i, f) in anim.frames.withIndex()) {
                val error = meanError(f.bitmap, back.frames[i].bitmap)
                assertTrue(error < 6.0, "effort $effort, frame $i: mean error $error")
                for (j in f.bitmap.argb.indices) assertEquals(f.bitmap.argb[j] ushr 24, back.frames[i].bitmap.argb[j] ushr 24, "frame $i alpha $j")
                assertEquals(f.delayMillis, back.frames[i].delayMillis)
            }
        }
    }

    @Test
    fun aSingleFrameAnimationIsAStill() {
        val b = photo(12, 9)
        val anim = KiteAnimation(12, 9, listOf(KiteFrame(b, 100, 10)), 0)
        assertContentEquals(ImageKodec.encodeWebp(b, WebpEncodeOptions(quality = 40)), ImageKodec.encodeWebp(anim, WebpEncodeOptions(quality = 40)))
    }

    @Test
    fun outOfRangeArgumentsAreRefused() {
        assertFailsWith<IllegalArgumentException> { WebpEncodeOptions(quality = 101) }
        assertFailsWith<IllegalArgumentException> { WebpEncodeOptions(quality = -1) }
        assertFailsWith<IllegalArgumentException> { WebpEncodeOptions(effort = 0) }
        assertFailsWith<IllegalArgumentException> { WebpEncodeOptions(effort = 4) }
        assertFailsWith<IllegalArgumentException> { WebpEncodeOptions(alphaQuality = 101) }
        assertFailsWith<IllegalArgumentException> { ImageKodec.encodeWebp(photo(2, 2), 200) }
        assertFailsWith<IllegalArgumentException> { ImageKodec.encodeWebp(KiteBitmap(16384, 1, IntArray(16384)), 75) }
        val anim = animation()
        assertFailsWith<IllegalArgumentException> {
            ImageKodec.encodeWebp(KiteAnimation(anim.width, anim.height, anim.frames, 70000), WebpEncodeOptions())
        }
        assertFailsWith<IllegalArgumentException> {
            ImageKodec.encodeWebp(KiteAnimation(anim.width, anim.height, anim.frames + KiteFrame(photo(3, 3), 10, 1), 0), WebpEncodeOptions())
        }
        assertFailsWith<IllegalArgumentException> {
            ImageKodec.encodeWebp(KiteAnimation(anim.width, anim.height, anim.frames + KiteFrame(anim.frames[0].bitmap, 1 shl 24, 1), 0), WebpEncodeOptions())
        }
    }

    @Test
    fun aCancellationCheckStopsTheEncode() {
        class Stop : RuntimeException()
        var calls = 0
        assertFailsWith<Stop> {
            ImageKodec.encodeWebp(PhotoScenes.city(160, 160).bitmap, WebpEncodeOptions(), cancellationCheck = { if (++calls == 3) throw Stop() })
        }
        assertEquals(3, calls)
        calls = 0
        ImageKodec.encodeWebp(animation(), WebpEncodeOptions(quality = 50), cancellationCheck = { calls++ })
        assertTrue(calls > 6, "$calls checks")
    }

    /** The same bytes on every target: the encoder uses integer arithmetic only. */
    @Test
    fun theOutputIsTheSameOnEveryTarget() {
        val scene = PhotoScenes.foliage(72, 56).bitmap
        val hashes = listOf(
            Crc32.of(ImageKodec.encodeWebp(scene, WebpEncodeOptions(quality = 75, effort = 1))),
            Crc32.of(ImageKodec.encodeWebp(scene, WebpEncodeOptions(quality = 75, effort = 2))),
            Crc32.of(ImageKodec.encodeWebp(scene, WebpEncodeOptions(quality = 30, effort = 3, sharpYuv = true))),
            Crc32.of(ImageKodec.encodeWebp(photo(37, 23, alpha = true), WebpEncodeOptions(quality = 60, alphaQuality = 40))),
            Crc32.of(ImageKodec.encodeWebp(animation(), WebpEncodeOptions(quality = 70))),
        )
        assertEquals(EXPECTED_HASHES, hashes.map { it.toString(16) })
    }

    private companion object {
        val EXPECTED_HASHES = listOf("3aaa62f9", "1d900fe9", "6802c714", "a8404d3c", "e2f6d21e")
    }
}
