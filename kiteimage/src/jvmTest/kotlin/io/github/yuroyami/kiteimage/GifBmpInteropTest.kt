package io.github.yuroyami.kiteimage

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The GIF and BMP writers have to produce files other people's decoders accept,
 * not merely files our own decoder round-trips. ImageIO is the independent
 * reader here: it has no shared code with KiteImage, so agreement means the
 * bytes really are GIF89a and BMP.
 */
class GifBmpInteropTest {

    private fun card(w: Int, h: Int, colors: Int): KiteBitmap {
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val n = (x * h + y) % colors
            px[y * w + x] = argb(0xFF, (n * 37) and 0xFF, (n * 91) and 0xFF, (n * 13) and 0xFF)
        }
        return KiteBitmap(w, h, px)
    }

    @Test
    fun imageIoReadsOurBmpPixelExact() {
        val src = card(23, 11, colors = 200)
        val img = assertNotNull(ImageIO.read(ByteArrayInputStream(KiteImage.encodeBmp(src))))
        assertEquals(23, img.width)
        assertEquals(11, img.height)
        for (y in 0 until 11) for (x in 0 until 23) {
            assertEquals(src[x, y] and 0xFFFFFF, img.getRGB(x, y) and 0xFFFFFF, "($x,$y)")
        }
    }

    @Test
    fun imageIoReadsOurAlphaBmp() {
        val w = 8
        val h = 4
        val src = KiteBitmap(w, h, IntArray(w * h) { i -> argb((i * 17) and 0xFF, i * 7, i * 3, i * 11) })
        val img = assertNotNull(ImageIO.read(ByteArrayInputStream(KiteImage.encodeBmp(src))))
        assertEquals(w, img.width)
        for (y in 0 until h) for (x in 0 until w) {
            assertEquals(src[x, y], img.getRGB(x, y), "($x,$y)")
        }
    }

    @Test
    fun imageIoReadsOurGifPixelExact() {
        // Under 256 colours, so both the palette and the LZW stream must be exact.
        val src = card(37, 19, colors = 240)
        val img = assertNotNull(ImageIO.read(ByteArrayInputStream(KiteImage.encodeGif(src))))
        assertEquals(37, img.width)
        assertEquals(19, img.height)
        for (y in 0 until 19) for (x in 0 until 37) {
            assertEquals(src[x, y], img.getRGB(x, y), "($x,$y)")
        }
    }

    @Test
    fun imageIoReadsOurQuantisedGifCloseEnough() {
        val w = 64
        val h = 48
        val src = KiteBitmap(w, h, IntArray(w * h) { i ->
            argb(0xFF, (i % w) * 4, (i / w) * 5, ((i % w) + (i / w)) * 2)
        })
        val img = assertNotNull(ImageIO.read(ByteArrayInputStream(KiteImage.encodeGif(src, dither = false))))
        var total = 0L
        for (y in 0 until h) for (x in 0 until w) {
            for (shift in intArrayOf(16, 8, 0)) {
                total += abs(((src[x, y] shr shift) and 0xFF) - ((img.getRGB(x, y) shr shift) and 0xFF))
            }
        }
        assertTrue(total.toDouble() / (w * h * 3) < 6.0, "mean channel error too high")
    }

    @Test
    fun imageIoReadsEveryFrameOfOurAnimatedGif() {
        val frames = List(4) { i ->
            KiteFrame(
                KiteBitmap(6, 4, IntArray(24) { argb(0xFF, i * 60, 255 - i * 60, i * 20) }),
                delayMillis = 60,
                delayRawCentiseconds = 6,
            )
        }
        val bytes = KiteImage.encodeGif(KiteAnimation(6, 4, frames, loopCount = 0))

        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        reader.input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
        assertEquals(4, reader.getNumImages(true))
        for (i in 0 until 4) {
            val img = reader.read(i)
            assertEquals(6, img.width)
            assertEquals(4, img.height)
            assertEquals(frames[i].bitmap[0, 0] and 0xFFFFFF, img.getRGB(0, 0) and 0xFFFFFF, "frame $i")
        }
        reader.dispose()
    }

    @Test
    fun imageIoRoundTripsOurGifTransparency() {
        val src = KiteBitmap(4, 1, intArrayOf(argb(0xFF, 255, 0, 0), 0, 0, argb(0xFF, 0, 0, 255)))
        val img = assertNotNull(ImageIO.read(ByteArrayInputStream(KiteImage.encodeGif(src))))
        assertEquals(0, img.getRGB(1, 0) ushr 24, "transparent pixel must stay transparent")
        assertEquals(0xFF, img.getRGB(0, 0) ushr 24)
    }
}
