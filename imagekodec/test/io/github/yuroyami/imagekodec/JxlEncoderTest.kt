package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The JPEG XL encoder (#179) read back by ImageKodec's own decoder: lossless files at every
 * effort, depth and channel count return their input exactly, lossy files stay close to it,
 * animations keep their frames, delays and play count, and fixed inputs give committed bytes
 * on every target.
 */
class JxlEncoderTest {

    private companion object {
        val HASHES = listOf(0xf8bda472d0429c1uL, 0x23ac0c09a67fa1c0uL, 0x19a4e04592567cf8uL, 0x30dcef7ea70290c1uL)
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun photo(w: Int, h: Int, alpha: Boolean, seed: Int = 0x2545F491): KiteBitmap {
        var state = seed
        return KiteBitmap(w, h, IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            state = state xor (state shl 13); state = state xor (state ushr 17); state = state xor (state shl 5)
            val n = state and 7
            argb(
                if (alpha) (x * 5 + y * 3 + n) and 0xFF else 0xFF,
                (x * 255 / maxOf(1, w - 1) + n) and 0xFF, (y * 255 / maxOf(1, h - 1) + n) and 0xFF, ((x + y) * 2 + n) and 0xFF,
            )
        })
    }

    private fun lossless(effort: Int) = JxlEncodeOptions(lossless = true, effort = effort)

    private fun roundTrip(name: String, b: KiteBitmap, effort: Int = 2): ByteArray {
        val data = ImageKodec.encodeJxl(b, lossless(effort))
        assertEquals(ImageFormat.JXL, ImageKodec.detect(data), name)
        assertContentEquals(b.argb, ImageKodec.decode(data).argb, "$name, effort $effort")
        return data
    }

    @Test
    fun losslessEdgeCasesReadBackExactly() {
        for (effort in 1..3) {
            roundTrip("one pixel", KiteBitmap(1, 1, intArrayOf(argb(0x12, 0x34, 0x56, 0x78))), effort)
            roundTrip("one row", KiteBitmap(5, 1, IntArray(5) { argb(0xFF, it * 50, 0, 0) }), effort)
            roundTrip("one column", KiteBitmap(1, 9, IntArray(9) { argb(0xFF, 0, it * 20, 0) }), effort)
            roundTrip("odd photo", photo(97, 61, alpha = false), effort)
            roundTrip("alpha", photo(64, 80, alpha = true), effort)
            roundTrip("transparent", KiteBitmap(13, 7, IntArray(91)), effort)
            roundTrip("gray", KiteBitmap(31, 17, IntArray(31 * 17) { val v = (it * 7) and 0xFF; argb(0xFF, v, v, v) }), effort)
        }
    }

    @Test
    fun losslessColourCountsReadBackExactly() {
        for (colors in listOf(1, 2, 3, 17, 256, 257, 1000, 1025)) {
            val w = 61
            val h = 37
            val b = KiteBitmap(w, h, IntArray(w * h) { i ->
                val c = (i * 7919L % colors).toInt()
                argb(if (c % 3 == 0) 0x80 else 0xFF, c * 37 and 0xFF, (c shr 2) * 91 and 0xFF, c * 13 and 0xFF)
            })
            roundTrip("$colors colours", b)
        }
    }

    @Test
    fun losslessImagesOfSeveralGroupsReadBackExactly() {
        roundTrip("several groups", photo(530, 270, alpha = true), effort = 1)
        roundTrip("several groups", photo(300, 520, alpha = false), effort = 2)
    }

    @Test
    fun lossless16BitReadsBackExactly() {
        for (channels in 1..4) {
            val w = 45
            val h = 29
            var state = 77
            val samples = ShortArray(w * h * channels) {
                state = state * 1103515245 + 12345
                ((it * 523 + (state ushr 20)) and 0xFFFF).toShort()
            }
            val b = KiteBitmap16(w, h, channels, samples)
            for (effort in 1..3) {
                val data = ImageKodec.encodeJxl16(b, lossless(effort))
                val back = ImageKodec.decode16(data)
                assertEquals(channels, back.channels, "$channels channels")
                assertContentEquals(samples, back.samples, "$channels channels, effort $effort")
            }
        }
    }

    /**
     * The mean difference of the colour channels between two bitmaps of one size, in levels.
     * The largest difference says little: XYB quantizes blue coarsely, and a blue edge of 255
     * levels in the test pictures comes back with an error of a hundred levels or more.
     */
    private fun meanError(a: KiteBitmap, b: KiteBitmap): Double {
        var sum = 0L
        for (i in a.argb.indices) for (s in 0 until 24 step 8) {
            sum += kotlin.math.abs(((a.argb[i] ushr s) and 0xFF) - ((b.argb[i] ushr s) and 0xFF))
        }
        return sum / (3.0 * a.argb.size)
    }

    @Test
    fun lossyEdgeCasesStayCloseToTheirSource() {
        val cases = listOf(
            "one pixel" to KiteBitmap(1, 1, intArrayOf(argb(0xFF, 0x34, 0x56, 0x78))),
            "one row" to photo(37, 1, alpha = false),
            "one column" to photo(1, 29, alpha = false),
            "odd photo" to photo(97, 61, alpha = false),
            "alpha" to photo(64, 80, alpha = true),
            "transparent" to KiteBitmap(13, 7, IntArray(91)),
            "several groups" to photo(300, 270, alpha = false),
        )
        for (effort in 1..3) for ((name, b) in cases) {
            val data = ImageKodec.encodeJxl(b, JxlEncodeOptions(quality = 95, effort = effort))
            val back = ImageKodec.decode(data)
            assertEquals(b.width to b.height, back.width to back.height, name)
            // Alpha is lossless next to lossy colour.
            for (i in b.argb.indices) assertEquals(b.argb[i] ushr 24, back.argb[i] ushr 24, "$name alpha at $i")
            val e = meanError(b, back)
            assertTrue(e <= 5.0, "$name, effort $effort: $e levels away on average")
        }
        val deep = KiteBitmap16(33, 17, 3, ShortArray(33 * 17 * 3) { ((it * 977) and 0x7FFF).toShort() })
        val back = ImageKodec.decode16(ImageKodec.encodeJxl16(deep, JxlEncodeOptions(distance = 0.5)))
        assertEquals(3, back.channels)
    }

    @Test
    fun animationsKeepFramesDelaysAndPlayCount() {
        val a = photo(40, 30, alpha = true, seed = 1)
        val b = KiteBitmap(40, 30, a.argb.copyOf().also { for (y in 5 until 12) for (x in 8 until 20) it[y * 40 + x] = argb(0xFF, 9, 200, 30) })
        val c = photo(40, 30, alpha = false, seed = 3)
        val anim = KiteAnimation(40, 30, listOf(KiteFrame(a, 100, 10), KiteFrame(b, 250, 25), KiteFrame(b, 40, 4), KiteFrame(c, 70, 7)), 3)
        val data = ImageKodec.encodeJxl(anim, JxlEncodeOptions(lossless = true))
        val back = ImageKodec.decodeAnimation(data)
        assertEquals(4, back.frames.size)
        assertEquals(3L, back.loopCount)
        for (i in 0 until 4) {
            assertContentEquals(anim.frames[i].bitmap.argb, back.frames[i].bitmap.argb, "frame $i")
            assertEquals(anim.frames[i].delayMillis, back.frames[i].delayMillis, "delay $i")
        }
        val lossy = ImageKodec.decodeAnimation(ImageKodec.encodeJxl(anim, JxlEncodeOptions(quality = 95)))
        assertEquals(4, lossy.frames.size)
        for (i in 0 until 4) assertTrue(meanError(anim.frames[i].bitmap, lossy.frames[i].bitmap) <= 5.0, "lossy frame $i: ${meanError(anim.frames[i].bitmap, lossy.frames[i].bitmap)}")
    }

    @Test
    fun cancellationStopsTheEncode() {
        class Stop : RuntimeException()
        for (lossless in listOf(true, false)) {
            var calls = 0
            assertFailsWith<Stop> {
                ImageKodec.encodeJxl(photo(300, 300, alpha = true), JxlEncodeOptions(lossless = lossless), ImageMetadata()) {
                    if (++calls == 3) throw Stop()
                }
            }
        }
    }

    private fun fnv(data: ByteArray): Long {
        var h = -0x340d631b7bdddcdbL
        for (b in data) h = (h xor (b.toLong() and 0xFF)) * 0x100000001b3L
        return h
    }

    /** Fixed inputs give the same bytes on every target, so the encoder's arithmetic is the same everywhere. */
    @Test
    fun outputIsTheSameOnEveryTarget() {
        val photo = photo(83, 57, alpha = true, seed = 99)
        val got = listOf(
            fnv(ImageKodec.encodeJxl(photo, JxlEncodeOptions(lossless = true, effort = 1))),
            fnv(ImageKodec.encodeJxl(photo, JxlEncodeOptions(lossless = true, effort = 3))),
            fnv(ImageKodec.encodeJxl(photo, JxlEncodeOptions(quality = 80, effort = 1))),
            fnv(ImageKodec.encodeJxl(photo, JxlEncodeOptions(quality = 60, effort = 3))),
        )
        println("JXL ENCODER HASHES ${got.joinToString { "0x" + it.toULong().toString(16) + "uL" }}")
        assertEquals(HASHES, got.map { it.toULong() })
    }

    @Test
    fun badOptionsAreRefused() {
        assertFailsWith<IllegalArgumentException> { JxlEncodeOptions(quality = 101) }
        assertFailsWith<IllegalArgumentException> { JxlEncodeOptions(distance = 0.05) }
        assertFailsWith<IllegalArgumentException> { JxlEncodeOptions(effort = 0) }
        assertTrue(JxlEncodeOptions(quality = 90).effectiveDistance in 0.999..1.001)
    }
}
