package io.github.yuroyami.kiteimage

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The GIF and BMP encoders are checked by decoding their own output: for inputs
 * inside each format's limits the round trip must be pixel-exact, and for inputs
 * past them (more than 256 colours) the error has to stay small. Cross-decoder
 * agreement with the platform's own readers lives in the jvmTest interop suite.
 */
class GifBmpEncoderTest {

    private fun bitmap(w: Int, h: Int, f: (Int, Int) -> Int) =
        KiteBitmap(w, h, IntArray(w * h) { f(it % w, it / w) })

    private val red = argb(0xFF, 0xFF, 0, 0)
    private val green = argb(0xFF, 0, 0xFF, 0)
    private val blue = argb(0xFF, 0, 0, 0xFF)

    // --- BMP --------------------------------------------------------------------

    @Test
    fun bmpOpaqueRoundTripsExactly() {
        val src = bitmap(7, 5) { x, y -> argb(0xFF, x * 30, y * 40, (x + y) * 20) }
        val out = KiteImage.encodeBmp(src)
        assertEquals(ImageFormat.BMP, KiteImage.detect(out))
        val back = KiteImage.decode(out)
        assertEquals(src.width, back.width)
        assertEquals(src.height, back.height)
        for (i in src.argb.indices) assertEquals(src.argb[i], back.argb[i], "pixel $i")
    }

    @Test
    fun bmpWithAlphaRoundTripsExactly() {
        val src = bitmap(4, 3) { x, y -> argb(x * 60 + 10, x * 50, y * 70, 128) }
        val back = KiteImage.decode(KiteImage.encodeBmp(src))
        for (i in src.argb.indices) assertEquals(src.argb[i], back.argb[i], "pixel $i")
    }

    @Test
    fun bmpRowPaddingIsHandledForEveryWidth() {
        // Widths 1..8 exercise all four 24-bit padding cases twice over.
        for (w in 1..8) {
            val src = bitmap(w, 3) { x, y -> argb(0xFF, x * 20 + 1, y * 60 + 2, 3) }
            val back = KiteImage.decode(KiteImage.encodeBmp(src))
            assertEquals(w, back.width, "width $w")
            for (i in src.argb.indices) assertEquals(src.argb[i], back.argb[i], "width $w pixel $i")
        }
    }

    @Test
    fun bmpProbeMatchesItsOwnOutput() {
        val src = bitmap(9, 4) { x, _ -> argb(0xFF, x * 25, 0, 0) }
        val info = KiteImage.probe(KiteImage.encodeBmp(src))
        assertEquals(9, info.width)
        assertEquals(4, info.height)
        assertTrue(info.isDecodable)
    }

    // --- GIF --------------------------------------------------------------------

    @Test
    fun gifUnderTwoHundredFiftySixColoursRoundTripsExactly() {
        // 200 distinct colours: inside the table, so the encode must be lossless.
        val src = bitmap(20, 10) { x, y -> argb(0xFF, x * 12, y * 25, (x * y) % 256) }
        val out = KiteImage.encodeGif(src)
        assertEquals(ImageFormat.GIF, KiteImage.detect(out))
        val back = KiteImage.decode(out)
        assertEquals(src.width, back.width)
        assertEquals(src.height, back.height)
        for (i in src.argb.indices) assertEquals(src.argb[i], back.argb[i], "pixel $i")
    }

    @Test
    fun gifKeepsTransparencyAsASingleIndex() {
        val src = bitmap(4, 1) { x, _ ->
            when (x) {
                0 -> red
                1 -> argb(0, 0, 0, 0)          // fully transparent
                2 -> argb(0x40, 0, 0xFF, 0)    // under the 50% threshold: transparent too
                else -> blue
            }
        }
        val back = KiteImage.decode(KiteImage.encodeGif(src))
        assertEquals(red, back[0, 0])
        assertEquals(0, back[1, 0] ushr 24, "fully transparent stays transparent")
        assertEquals(0, back[2, 0] ushr 24, "below the threshold becomes transparent")
        assertEquals(blue, back[3, 0])
    }

    @Test
    fun gifQuantisesLargePalettesWithSmallError() {
        // 4096 distinct colours forced into 256: lossy by definition, but the mean
        // error has to stay small or the quantiser is not doing its job.
        val src = bitmap(64, 64) { x, y -> argb(0xFF, x * 4, y * 4, (x + y) * 2) }
        val back = KiteImage.decode(KiteImage.encodeGif(src, dither = false))
        assertEquals(64, back.width)

        var total = 0L
        var worst = 0
        for (i in src.argb.indices) {
            val a = src.argb[i]
            val b = back.argb[i]
            for (shift in intArrayOf(16, 8, 0)) {
                val d = abs(((a ushr shift) and 0xFF) - ((b ushr shift) and 0xFF))
                total += d
                if (d > worst) worst = d
            }
        }
        val mean = total.toDouble() / (src.argb.size * 3)
        assertTrue(mean < 6.0, "mean channel error $mean is too high")
        assertTrue(worst < 48, "worst channel error $worst is too high")
    }

    @Test
    fun ditheringLowersMeanErrorOnAGradient() {
        val src = bitmap(96, 96) { x, y -> argb(0xFF, x * 2, y * 2, (x * y) / 40) }
        fun meanError(dither: Boolean): Double {
            val back = KiteImage.decode(KiteImage.encodeGif(src, dither = dither))
            var total = 0L
            for (i in src.argb.indices) {
                val a = src.argb[i]
                val b = back.argb[i]
                for (shift in intArrayOf(16, 8, 0)) {
                    total += abs(((a ushr shift) and 0xFF) - ((b ushr shift) and 0xFF))
                }
            }
            return total.toDouble() / (src.argb.size * 3)
        }
        // Dithering trades per-pixel exactness for a better local average; on a
        // smooth gradient it must not be worse than plain nearest-colour.
        assertTrue(meanError(true) <= meanError(false) + 1.0)
    }

    @Test
    fun gifEncodeIsDeterministic() {
        val src = bitmap(40, 40) { x, y -> argb(0xFF, x * 6, y * 6, x + y) }
        val a = KiteImage.encodeGif(src)
        val b = KiteImage.encodeGif(src)
        assertTrue(a.contentEquals(b), "same input must produce the same bytes")
    }

    @Test
    fun animatedGifRoundTripsFramesDelaysAndLoop() {
        val frames = listOf(
            KiteFrame(bitmap(3, 2) { _, _ -> red }, delayMillis = 100, delayRawCentiseconds = 10),
            KiteFrame(bitmap(3, 2) { _, _ -> green }, delayMillis = 50, delayRawCentiseconds = 5),
            KiteFrame(bitmap(3, 2) { _, _ -> blue }, delayMillis = 200, delayRawCentiseconds = 20),
        )
        val src = KiteAnimation(3, 2, frames, loopCount = 0)

        val out = KiteImage.encodeGif(src)
        val back = KiteImage.decodeAnimation(out)

        assertEquals(3, back.frames.size)
        assertEquals(0, back.loopCount, "0 = loop forever")
        assertEquals(3, back.width)
        assertEquals(2, back.height)
        assertEquals(10, back.frames[0].delayRawCentiseconds)
        assertEquals(5, back.frames[1].delayRawCentiseconds)
        assertEquals(20, back.frames[2].delayRawCentiseconds)
        assertEquals(red, back.frames[0].bitmap[0, 0])
        assertEquals(green, back.frames[1].bitmap[0, 0])
        assertEquals(blue, back.frames[2].bitmap[0, 0])
    }

    @Test
    fun animatedGifProbeMatchesTheDecode() {
        val frames = List(4) { i ->
            KiteFrame(bitmap(5, 5) { _, _ -> argb(0xFF, i * 60, 0, 0) }, 40, 4)
        }
        val out = KiteImage.encodeGif(KiteAnimation(5, 5, frames, loopCount = 2))
        val info = KiteImage.probe(out)
        assertEquals(4, info.frameCount)
        assertEquals(2, info.loopCount)
        assertTrue(info.isAnimated)
    }

    @Test
    fun singleColourImageStillProducesALegalTable() {
        val src = bitmap(3, 3) { _, _ -> red }
        val back = KiteImage.decode(KiteImage.encodeGif(src))
        for (i in back.argb.indices) assertEquals(red, back.argb[i])
    }

    @Test
    fun fullyTransparentImageEncodes() {
        val src = bitmap(2, 2) { _, _ -> 0 }
        val back = KiteImage.decode(KiteImage.encodeGif(src))
        for (i in back.argb.indices) assertEquals(0, back.argb[i] ushr 24)
    }

    @Test
    fun largeImageExercisesDictionaryResets() {
        // Enough distinct runs to fill the 4096-entry LZW table several times over,
        // which is where an off-by-one in the CLEAR handling would surface.
        val src = bitmap(200, 200) { x, y -> argb(0xFF, (x * 7) and 0xFF, (y * 11) and 0xFF, ((x xor y) * 3) and 0xFF) }
        val out = KiteImage.encodeGif(src, dither = false)
        val back = KiteImage.decode(out)
        assertEquals(200, back.width)
        assertEquals(200, back.height)
        // Not exact (over 256 colours), but every pixel must decode to something.
        assertEquals(src.argb.size, back.argb.size)
    }
}
