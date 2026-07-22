package io.github.yuroyami.kiteimage

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Vectors from `gen_gif_vectors.py`: a minimal independent GIF writer whose LZW
 * stream is literal-only with periodic CLEAR codes (the "uncompressed GIF"
 * technique), so these pin container semantics: color tables, interlacing,
 * transparency, disposal compositing, delays, loop count. Dictionary-growth LZW
 * paths are pinned by the jvmTest ImageIO round-trip, which uses a real encoder.
 *
 * Palette used throughout: 0=red, 1=green, 2=blue, 3=yellow.
 */
class GifDecoderTest {

    private val STATIC_3X2 = "47494638396103000200910000ff000000ff000000ffffff002c00000000030002000002050443710453003b"
    private val INTERLACED_8X8 = "47494638396108000800a200000000001111112222223333334444445555556666667777772c0000000008000800400327080000080044484444482222282262686666681611181111383333383355585555587777787797003b"
    private val STATIC_TRANSPARENT_2X2 = "47494638396102000200910000ff000000ff000000ffffff0021f90401000001002c000000000200020000020404c37005003b"
    private val ANIM_KEEP_2F = "47494638396102000200910000ff000000ff000000ffffff0021ff0b4e45545343415045322e30030103000021f90404050000002c0000000002000200000204044110050021f90404000000002c000000000200020000020414455105003b"
    private val ANIM_DISPOSE_BG = "47494638396102000200910000ff000000ff000000ffffff0021ff0b4e45545343415045322e30030100000021f904080a0000002c0000000002000200000204044110050021f904000a0000002c01000100010001000002025401003b"
    private val ANIM_DISPOSE_PREV = "47494638396102000200910000ff000000ff000000ffffff0021f904040a0000002c0000000002000200000204044110050021f9040c0a0000002c000000000100010000020254010021f904040a0000002c01000100010001000002024c01003b"
    private val ANIM_LCT = "47494638396101000200910000ff000000ff000000ffffff0021f90404000000002c000000000100020000020204530021f90404000000002c000000000100020080ffffff00000002020c51003b"
    private val ANIM_TRANSPARENT_OVERLAY = "47494638396102000200910000ff000000ff000000ffffff0021f90404000000002c0000000002000200000204044371050021f90405000001002c000000000200020000020414c33005003b"
    private val GIF87A_1X2 = "47494638376101000200910000ff000000ff000000ffffff002c00000000010002000002021c55003b"
    private val TRUNCATED = "47494638396103000200910000ff000000ff000000ff"
    private val NO_COLOR_TABLE = "474946383961010001000000002c00000000010001000002024401003b"

    private val red = argb(0xFF, 0xFF, 0, 0)
    private val green = argb(0xFF, 0, 0xFF, 0)
    private val blue = argb(0xFF, 0, 0, 0xFF)
    private val yellow = argb(0xFF, 0xFF, 0xFF, 0)

    @Test
    fun staticFrame() {
        val bm = KiteImage.decode(hex(STATIC_3X2))
        assertEquals(3, bm.width)
        assertEquals(2, bm.height)
        assertContentEquals(intArrayOf(red, green, blue, yellow, red, green), bm.argb)
    }

    @Test
    fun staticIsSingleFrameAnimation() {
        val anim = KiteImage.decodeAnimation(hex(STATIC_3X2))
        assertFalse(anim.isAnimated)
        assertEquals(1, anim.frames.size)
        assertEquals(1, anim.loopCount)
    }

    @Test
    fun interlacedRowsComeBackInNaturalOrder() {
        // Pixel value = row index (8 grays); interlaced storage must undo to natural.
        val bm = KiteImage.decode(hex(INTERLACED_8X8))
        for (y in 0 until 8) {
            val v = 0x11 * y
            for (x in 0 until 8) {
                assertEquals(argb(0xFF, v, v, v), bm[x, y], "($x,$y)")
            }
        }
    }

    @Test
    fun transparentIndexLeavesCanvasEmpty() {
        val bm = KiteImage.decode(hex(STATIC_TRANSPARENT_2X2))
        assertContentEquals(intArrayOf(red, 0, 0, yellow), bm.argb)
        assertTrue(bm.hasTransparency())
    }

    @Test
    fun twoFramesDelaysAndLoopCount() {
        val anim = KiteImage.decodeAnimation(hex(ANIM_KEEP_2F))
        assertTrue(anim.isAnimated)
        assertEquals(2, anim.frames.size)
        assertEquals(3, anim.loopCount)

        assertContentEquals(IntArray(4) { red }, anim.frames[0].bitmap.argb)
        assertContentEquals(IntArray(4) { blue }, anim.frames[1].bitmap.argb)

        // 5 cs → 50 ms honest; 0 cs → browser-clamped 100 ms, raw preserved.
        assertEquals(50, anim.frames[0].delayMillis)
        assertEquals(5, anim.frames[0].delayRawCentiseconds)
        assertEquals(100, anim.frames[1].delayMillis)
        assertEquals(0, anim.frames[1].delayRawCentiseconds)
        assertEquals(150, anim.durationMillis)
    }

    @Test
    fun disposeToBackgroundClearsOnlyThatRect() {
        val anim = KiteImage.decodeAnimation(hex(ANIM_DISPOSE_BG))
        assertEquals(0, anim.loopCount)   // NETSCAPE 0 = forever
        // f1: full red, disposal=2. Presented as-is.
        assertContentEquals(IntArray(4) { red }, anim.frames[0].bitmap.argb)
        // Before f2, f1's rect (everything) clears to transparent; f2 = blue at (1,1).
        assertContentEquals(intArrayOf(0, 0, 0, blue), anim.frames[1].bitmap.argb)
    }

    @Test
    fun disposeToPreviousRestoresCanvas() {
        val anim = KiteImage.decodeAnimation(hex(ANIM_DISPOSE_PREV))
        // f1 full red (keep)
        assertContentEquals(IntArray(4) { red }, anim.frames[0].bitmap.argb)
        // f2: blue 1x1 at (0,0), disposal=restore-previous → presented red with blue corner
        assertContentEquals(intArrayOf(blue, red, red, red), anim.frames[1].bitmap.argb)
        // f3: canvas restored to full red first, then green at (1,1)
        assertContentEquals(intArrayOf(red, red, red, green), anim.frames[2].bitmap.argb)
    }

    @Test
    fun localColorTableOverridesGlobal() {
        val anim = KiteImage.decodeAnimation(hex(ANIM_LCT))
        val white = argb(0xFF, 0xFF, 0xFF, 0xFF)
        val black = argb(0xFF, 0, 0, 0)
        assertContentEquals(intArrayOf(red, green), anim.frames[0].bitmap.argb)
        assertContentEquals(intArrayOf(black, white), anim.frames[1].bitmap.argb)
    }

    @Test
    fun transparentOverlayShowsPreviousFrameThrough() {
        val anim = KiteImage.decodeAnimation(hex(ANIM_TRANSPARENT_OVERLAY))
        assertContentEquals(intArrayOf(red, green, blue, yellow), anim.frames[0].bitmap.argb)
        // f2 covers the full rect but only (0,0) is opaque (blue); index 1 is transparent.
        assertContentEquals(intArrayOf(blue, green, blue, yellow), anim.frames[1].bitmap.argb)
    }

    @Test
    fun gif87aWithoutAnyExtensions() {
        val bm = KiteImage.decode(hex(GIF87A_1X2))
        assertContentEquals(intArrayOf(yellow, blue), bm.argb)
    }

    @Test
    fun truncatedThrowsDecodeError() {
        assertFailsWith<ImageDecodeException> { KiteImage.decode(hex(TRUNCATED)) }
    }

    @Test
    fun missingColorTableThrows() {
        val e = assertFailsWith<ImageDecodeException> { KiteImage.decode(hex(NO_COLOR_TABLE)) }
        assertTrue("color table" in e.message!!)
    }

    @Test
    fun decodeAnimationOnStaticFormatWrapsSingleFrame() {
        // decodeAnimation must be total over static formats too.
        val anim = KiteImage.decodeAnimation(hexBmp())
        assertEquals(1, anim.frames.size)
        assertEquals(0, anim.frames[0].delayMillis)
        assertFalse(anim.isAnimated)
    }

    /** Tiny 1x1 24-bit BMP built inline. */
    private fun hexBmp(): ByteArray {
        val out = ArrayList<Byte>()
        fun u8(v: Int) = out.add((v and 0xFF).toByte())
        fun u16(v: Int) { u8(v); u8(v ushr 8) }
        fun u32(v: Int) { u16(v); u16(v ushr 16) }
        u8('B'.code); u8('M'.code); u32(58); u32(0); u32(54)
        u32(40); u32(1); u32(1); u16(1); u16(24); u32(0); u32(0); u32(0); u32(0); u32(0); u32(0)
        u8(0x30); u8(0x20); u8(0x10); u8(0)   // one BGR pixel + row padding
        return out.toByteArray()
    }
}
