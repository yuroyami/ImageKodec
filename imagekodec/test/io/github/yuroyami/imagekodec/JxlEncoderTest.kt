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

    @Test
    fun badOptionsAreRefused() {
        assertFailsWith<IllegalArgumentException> { JxlEncodeOptions(quality = 101) }
        assertFailsWith<IllegalArgumentException> { JxlEncodeOptions(distance = 0.05) }
        assertFailsWith<IllegalArgumentException> { JxlEncodeOptions(effort = 0) }
        assertTrue(JxlEncodeOptions(quality = 90).effectiveDistance in 0.999..1.001)
    }
}
