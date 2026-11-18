package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The BMP writer's depths (#184): palettes of 1, 4 and 8 bits, raw and run-length coded, read back
 * exactly through ImageKodec's decoder, 16-bit 5-5-5 reads back as its rounding, and the default
 * keeps its bytes. `BmpEncodeOptionsOracleTest` holds the same files to ImageIO and ImageMagick.
 */
class BmpEncodeOptionsTest {

    /** [colours] colours in runs, in alternating pairs, and in noise, so that every RLE mode is met. */
    private fun picture(w: Int, h: Int, colours: Int, seed: Long = 1): KiteBitmap {
        val random = BilevelPages.Random(seed)
        val palette = IntArray(colours) { argb(255, (it * 97 + 3) and 0xFF, (it * 31 + 7) and 0xFF, (it * 13) and 0xFF) }
        return KiteBitmap(w, h, IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            when ((y + x / 11) % 3) {
                0 -> palette[(x / 5 + y) % colours]
                1 -> palette[if (x and 1 == 0) 0 else colours - 1]
                else -> palette[random.next(colours)]
            }
        })
    }

    private val sizes = listOf(1 to 1, 1 to 7, 7 to 1, 2 to 3, 37 to 23, 300 to 5, 600 to 3)

    @Test
    fun theDefaultKeepsItsBytes() {
        for (alpha in listOf(false, true)) {
            val card = EncoderFixtures.card(37, 23, alpha, gray = false)
            assertContentEquals(ImageKodec.encodeBmp(card), ImageKodec.encodeBmp(card, BmpEncodeOptions()))
        }
    }

    @Test
    fun paletteDepthsReadBackExactly() {
        for ((bits, colours) in listOf(1 to 1, 1 to 2, 4 to 3, 4 to 16, 8 to 17, 8 to 256)) {
            for (rle in listOf(false, true)) {
                if (rle && bits == 1) continue
                for ((w, h) in sizes) {
                    val src = picture(w, h, colours, seed = (w * h + colours).toLong())
                    val data = ImageKodec.encodeBmp(src, BmpEncodeOptions(bits, rle))
                    assertEquals(ImageFormat.BMP, ImageKodec.detect(data))
                    assertContentEquals(src.argb, ImageKodec.decode(data).argb, "$bits bits, $colours colours, rle $rle, ${w}x$h")
                }
            }
        }
    }

    @Test
    fun runLengthCodingShrinksFlatPictures() {
        val flat = picture(400, 60, 12).let { b -> KiteBitmap(400, 60, IntArray(400 * 60) { b.argb[(it / 400) * 400 + (it % 400) / 40 * 40] }) }
        for (bits in listOf(4, 8)) {
            val raw = ImageKodec.encodeBmp(flat, BmpEncodeOptions(bits))
            val rle = ImageKodec.encodeBmp(flat, BmpEncodeOptions(bits, rle = true))
            assertContentEquals(flat.argb, ImageKodec.decode(rle).argb)
            assertTrue(rle.size * 4 < raw.size, "$bits bits: RLE ${rle.size} bytes against ${raw.size}")
        }
    }

    @Test
    fun directDepthsReadBackAsWritten() {
        for ((w, h) in sizes) {
            val card = EncoderFixtures.card(w, h, alpha = false, gray = false)
            assertContentEquals(card.argb, ImageKodec.decode(ImageKodec.encodeBmp(card, BmpEncodeOptions(24))).argb)
            assertContentEquals(card.argb, ImageKodec.decode(ImageKodec.encodeBmp(card, BmpEncodeOptions(32))).argb)
            val alpha = EncoderFixtures.card(w, h, alpha = true, gray = false)
            assertContentEquals(alpha.argb, ImageKodec.decode(ImageKodec.encodeBmp(alpha, BmpEncodeOptions(32))).argb)
            // 16 bits round each channel to the nearest of 32 levels, which the decoder spreads back over 0..255.
            val back = ImageKodec.decode(ImageKodec.encodeBmp(card, BmpEncodeOptions(16))).argb
            for (i in card.argb.indices) {
                fun level(v: Int) = ((v * 31 + 127) / 255).let { (it * 255 + 15) / 31 }
                val p = card.argb[i]
                assertEquals(argb(255, level(p ushr 16 and 0xFF), level(p ushr 8 and 0xFF), level(p and 0xFF)), back[i], "${w}x$h pixel $i")
            }
        }
        // Every 5-bit level survives a second trip unchanged.
        val levels = KiteBitmap(32, 1, IntArray(32) { val v = (it * 255 + 15) / 31; argb(255, v, 255 - v, v / 2) })
        val once = ImageKodec.decode(ImageKodec.encodeBmp(levels, BmpEncodeOptions(16)))
        assertContentEquals(once.argb, ImageKodec.decode(ImageKodec.encodeBmp(once, BmpEncodeOptions(16))).argb)
        assertEquals(levels.argb[5] and 0xFF0000, once.argb[5] and 0xFF0000)
    }

    @Test
    fun picturesTheDepthCannotHoldAreRefused() {
        val alpha = EncoderFixtures.card(9, 9, alpha = true, gray = false)
        for (bits in listOf(1, 4, 8, 16, 24)) assertFailsWith<IllegalArgumentException>("$bits") { ImageKodec.encodeBmp(alpha, BmpEncodeOptions(bits)) }
        assertFailsWith<IllegalArgumentException> { ImageKodec.encodeBmp(picture(20, 20, 3), BmpEncodeOptions(1)) }
        assertFailsWith<IllegalArgumentException> { ImageKodec.encodeBmp(picture(20, 20, 17), BmpEncodeOptions(4, rle = true)) }
        assertFailsWith<IllegalArgumentException> { ImageKodec.encodeBmp(KiteBitmap(257, 1, IntArray(257) { argb(255, it and 0xFF, it shr 8, 0) }), BmpEncodeOptions(8)) }
        assertFailsWith<IllegalArgumentException> { BmpEncodeOptions(2) }
        assertFailsWith<IllegalArgumentException> { BmpEncodeOptions(24, rle = true) }
        assertFailsWith<IllegalArgumentException> { BmpEncodeOptions(null, rle = true) }
    }

    @Test
    fun theOutputIsTheSameOnEveryTarget() {
        val hashes = listOf(
            ImageKodec.encodeBmp(picture(37, 23, 2), BmpEncodeOptions(1)),
            ImageKodec.encodeBmp(picture(37, 23, 16), BmpEncodeOptions(4)),
            ImageKodec.encodeBmp(picture(37, 23, 16), BmpEncodeOptions(4, rle = true)),
            ImageKodec.encodeBmp(picture(37, 23, 200), BmpEncodeOptions(8)),
            ImageKodec.encodeBmp(picture(37, 23, 200), BmpEncodeOptions(8, rle = true)),
            ImageKodec.encodeBmp(EncoderFixtures.card(37, 23, alpha = false, gray = false), BmpEncodeOptions(16)),
        ).map { BilevelPages.fnv(it).toULong() }
        assertEquals(EXPECTED_HASHES, hashes)
    }

    private companion object {
        val EXPECTED_HASHES = listOf(
            0x52cde111b98b19d3uL, 0x768984d70192aa23uL, 0x05b8da14b2ebd5a7uL, 0x73af851238bf97aduL, 0x8058c085a50d02ccuL, 0x74ac0a5b34045105uL,
        )
    }
}
