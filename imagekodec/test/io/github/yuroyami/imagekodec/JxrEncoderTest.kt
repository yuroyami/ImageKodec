package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The JPEG XR encoder (#181) on every target: lossless files decode to the input at every depth
 * and channel count, lossy files decode close to it, edge cases round-trip, the output is the
 * same bytes everywhere, and the cancellation check stops the encode. `JxrEncodeOracleTest`
 * holds the files to jxrlib's `JxrDecApp`.
 */
class JxrEncoderTest {

    private fun scene(w: Int, h: Int, seed: Int, alpha: Boolean = false, gray: Boolean = false): KiteBitmap {
        var s = seed
        fun rnd(): Int {
            s = s * 1103515245 + 12345
            return (s ushr 16) and 0xFF
        }
        val argb = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val noise = if (x in w / 4 until w / 2) rnd() else 0
            val r = (x * 7 + y * 3 + noise) and 0xFF
            val g = if (gray) r else (x * 2 + y * 9 + noise / 2) and 0xFF
            val b = if (gray) r else (255 - x * 5 + y) and 0xFF
            val a = if (alpha) (x * 11 + y * 13) and 0xFF else 0xFF
            (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return KiteBitmap(w, h, argb)
    }

    private fun fnv(d: ByteArray): Long {
        var h = -0x340d631b7bdddcdbL
        for (b in d) {
            h = h xor (b.toLong() and 0xFF)
            h *= 0x100000001b3L
        }
        return h
    }

    @Test
    fun losslessRoundTripsInEveryOverlapMode() {
        for ((w, h) in listOf(1 to 1, 16 to 16, 37 to 21, 1 to 40, 40 to 1, 70 to 50)) {
            for (alpha in listOf(false, true)) for (gray in listOf(false, true)) {
                val src = scene(w, h, w * 31 + h, alpha, gray)
                for (overlap in 0..2) {
                    val jxr = ImageKodec.encodeJxr(src, JxrEncodeOptions(lossless = true, overlap = overlap, planarAlpha = overlap != 1))
                    val back = ImageKodec.decode(jxr)
                    assertEquals(w, back.width)
                    assertContentEquals(src.argb, back.argb, "$w x $h alpha $alpha gray $gray overlap $overlap")
                }
            }
        }
    }

    @Test
    fun losslessEdgeCases() {
        val cases = listOf(
            "transparent" to KiteBitmap(19, 7, IntArray(19 * 7)),
            "one colour" to KiteBitmap(33, 17, IntArray(33 * 17) { 0xFF336699.toInt() }),
            "two colours" to KiteBitmap(20, 20, IntArray(400) { if ((it / 20 + it) % 3 == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }),
            "many colours" to KiteBitmap(64, 64, IntArray(4096) { 0xFF000000.toInt() or (it * 4099) }),
            "extremes" to KiteBitmap(18, 18, IntArray(324) { if (it % 2 == 0) 0 else -1 }),
        )
        for ((name, src) in cases) {
            for (planar in listOf(true, false)) {
                val back = ImageKodec.decode(ImageKodec.encodeJxr(src, JxrEncodeOptions(lossless = true, planarAlpha = planar)))
                assertContentEquals(src.argb, back.argb, name)
            }
        }
    }

    @Test
    fun sixteenBitLosslessRoundTrips() {
        for (channels in 1..4) {
            for ((w, h) in listOf(1 to 1, 23 to 18)) {
                val samples = ShortArray(w * h * channels) { i -> ((i * 2654435761L) ushr 13).toInt().toShort() }
                val src = KiteBitmap16(w, h, channels, samples)
                for (overlap in 0..2) {
                    val back = ImageKodec.decode16(ImageKodec.encodeJxr16(src, JxrEncodeOptions(lossless = true, overlap = overlap)))
                    // Gray with alpha comes back as RGBA, its gray in all three colour channels.
                    val want = if (channels != 2) samples else ShortArray(w * h * 4) { i -> samples[(i / 4) * 2 + if (i % 4 == 3) 1 else 0] }
                    assertContentEquals(want, back.samples, "$channels channels, $w x $h, overlap $overlap")
                }
            }
        }
    }

    @Test
    fun lossyDecodesCloseToTheInput() {
        val src = PhotoScenes.bokeh(96, 80).bitmap
        var last = 0.0
        for (q in listOf(10, 50, 90)) {
            val jxr = ImageKodec.encodeJxr(src, q)
            val score = ImageKodec.ssimulacra2(src, ImageKodec.decode(jxr))
            assertTrue(score > last, "quality $q scores $score, not above $last")
            last = score
        }
        assertTrue(last > 80, "quality 90 scores $last")
        // 16-bit samples of the same picture land near the 8-bit encode.
        val wide = KiteBitmap16.widened(src, false)
        val back = ImageKodec.decode16(ImageKodec.encodeJxr16(wide, JxrEncodeOptions(quality = 90))).toBitmap()
        assertTrue(ImageKodec.ssimulacra2(src, back) > last - 5, "16-bit quality 90 scores ${ImageKodec.ssimulacra2(src, back)}")
    }

    @Test
    fun outputIsTheSameBytesOnEveryTarget() {
        val src = scene(45, 37, 7, alpha = true)
        val hashes = listOf(
            fnv(ImageKodec.encodeJxr(src, JxrEncodeOptions(lossless = true))),
            fnv(ImageKodec.encodeJxr(src, 75)),
            fnv(ImageKodec.encodeJxr(src, JxrEncodeOptions(quality = 30, planarAlpha = false, tileSize = 32, frequencyOrder = true))),
            fnv(ImageKodec.encodeJxr16(KiteBitmap16.widened(src, true), JxrEncodeOptions(quality = 60))),
        )
        assertEquals(EXPECTED_HASHES, hashes)
    }

    @Test
    fun cancellationCheckStopsTheEncode() {
        class Stop : RuntimeException("stop")
        val src = scene(64, 64, 3)
        var calls = 0
        val plain = ImageKodec.encodeJxr(src, JxrEncodeOptions(quality = 60), cancellationCheck = { calls++ })
        assertContentEquals(ImageKodec.encodeJxr(src, JxrEncodeOptions(quality = 60)), plain)
        assertTrue(calls >= 4, "the check ran $calls times")
        val stop = Stop()
        var seen = 0
        val thrown = assertFailsWith<Stop> {
            ImageKodec.encodeJxr(src, JxrEncodeOptions(quality = 60), cancellationCheck = {
                if (++seen == 3) throw stop
            })
        }
        assertSame(stop, thrown)
    }

    @Test
    fun argumentsOutOfRangeAreRefused() {
        assertFailsWith<IllegalArgumentException> { JxrEncodeOptions(quality = 101) }
        assertFailsWith<IllegalArgumentException> { JxrEncodeOptions(overlap = 3) }
        assertFailsWith<IllegalArgumentException> { JxrEncodeOptions(tileSize = 20) }
        assertFailsWith<IllegalArgumentException> { JxrEncodeOptions(lossless = true, tileQualities = intArrayOf(50)) }
        assertFailsWith<IllegalArgumentException> { ImageKodec.encodeJxr(scene(8, 8, 1), -1) }
        assertFailsWith<IllegalArgumentException> {
            ImageKodec.encodeJxr(scene(40, 40, 1), JxrEncodeOptions(tileSize = 16, tileQualities = intArrayOf(50, 60)))
        }
    }

    private companion object {
        val EXPECTED_HASHES = listOf(756758378346208647L, -5206909071300560925L, -2366584170920094713L, 8295265910212390764L)
    }
}
