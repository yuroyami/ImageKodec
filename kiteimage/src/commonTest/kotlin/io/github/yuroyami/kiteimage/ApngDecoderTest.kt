package io.github.yuroyami.kiteimage

import io.github.yuroyami.kiteimage.internal.flate.Crc32
import io.github.yuroyami.kiteimage.internal.flate.Zlib
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * APNG vectors are built here rather than pasted as hex: the animation chunks
 * are a thin layer over ordinary PNG, so a builder that writes `acTL`/`fcTL`/
 * `fdAT` by the spec is both the vector source and a readable restatement of
 * the format. Every expectation below is a hand-computed composite.
 */
class ApngDecoderTest {

    // --- builder ----------------------------------------------------------------

    private class Sub(
        val width: Int,
        val height: Int,
        val x: Int = 0,
        val y: Int = 0,
        val pixels: IntArray,                  // 0xAARRGGBB, row-major
        val delayNum: Int = 10,
        val delayDen: Int = 100,
        val dispose: Int = 0,
        val blend: Int = 0,
    )

    private fun chunk(type: String, body: ByteArray): ByteArray {
        val out = ByteArray(12 + body.size)
        val n = body.size
        out[0] = ((n ushr 24) and 0xFF).toByte()
        out[1] = ((n ushr 16) and 0xFF).toByte()
        out[2] = ((n ushr 8) and 0xFF).toByte()
        out[3] = (n and 0xFF).toByte()
        for (i in 0..3) out[4 + i] = type[i].code.toByte()
        body.copyInto(out, 8)
        val crc = Crc32()
        crc.update(ByteArray(4) { type[it].code.toByte() })
        crc.update(body)
        val v = crc.value()
        out[8 + n] = ((v ushr 24) and 0xFF).toByte()
        out[9 + n] = ((v ushr 16) and 0xFF).toByte()
        out[10 + n] = ((v ushr 8) and 0xFF).toByte()
        out[11 + n] = (v and 0xFF).toByte()
        return out
    }

    private fun be32(v: Int) = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte(),
    )

    private fun be16(v: Int) = byteArrayOf(((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

    /** RGBA scanlines with filter type 0, zlib-wrapped: the payload of IDAT/fdAT. */
    private fun imageData(sub: Sub): ByteArray {
        val rowBytes = sub.width * 4
        val raw = ByteArray(sub.height * (1 + rowBytes))
        var o = 0
        for (y in 0 until sub.height) {
            raw[o++] = 0                                       // filter: None
            for (x in 0 until sub.width) {
                val p = sub.pixels[y * sub.width + x]
                raw[o++] = ((p ushr 16) and 0xFF).toByte()
                raw[o++] = ((p ushr 8) and 0xFF).toByte()
                raw[o++] = (p and 0xFF).toByte()
                raw[o++] = ((p ushr 24) and 0xFF).toByte()
            }
        }
        return Zlib.compress(raw)
    }

    private fun fctl(seq: Int, sub: Sub): ByteArray =
        be32(seq) + be32(sub.width) + be32(sub.height) + be32(sub.x) + be32(sub.y) +
            be16(sub.delayNum) + be16(sub.delayDen) +
            byteArrayOf(sub.dispose.toByte(), sub.blend.toByte())

    /**
     * Build an APNG. [defaultImage] becomes the IDAT; when [defaultIsFirstFrame]
     * it is also animation frame 0 and [frames] supplies the rest, otherwise the
     * whole of [frames] is the animation and IDAT is a still fallback.
     */
    private fun apng(
        width: Int,
        height: Int,
        defaultImage: Sub,
        frames: List<Sub>,
        defaultIsFirstFrame: Boolean,
        loops: Int = 0,
        declaredFrames: Int = -1,
    ): ByteArray {
        val out = ArrayList<Byte>()
        fun add(b: ByteArray) = b.forEach { out.add(it) }

        add(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        add(chunk("IHDR", be32(width) + be32(height) + byteArrayOf(8, 6, 0, 0, 0)))

        val animationFrames = if (defaultIsFirstFrame) 1 + frames.size else frames.size
        add(chunk("acTL", be32(if (declaredFrames >= 0) declaredFrames else animationFrames) + be32(loops)))

        var seq = 0
        if (defaultIsFirstFrame) add(chunk("fcTL", fctl(seq++, defaultImage)))
        add(chunk("IDAT", imageData(defaultImage)))
        for (f in frames) {
            add(chunk("fcTL", fctl(seq++, f)))
            add(chunk("fdAT", be32(seq++) + imageData(f)))
        }
        add(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    private fun solid(w: Int, h: Int, color: Int, x: Int = 0, y: Int = 0, delayNum: Int = 10, dispose: Int = 0, blend: Int = 0) =
        Sub(w, h, x, y, IntArray(w * h) { color }, delayNum = delayNum, dispose = dispose, blend = blend)

    private val red = argb(0xFF, 0xFF, 0, 0)
    private val green = argb(0xFF, 0, 0xFF, 0)
    private val blue = argb(0xFF, 0, 0, 0xFF)

    // --- tests ------------------------------------------------------------------

    @Test
    fun plainPngIsASingleFrameAnimation() {
        val still = KiteImage.encodePng(KiteBitmap(3, 2, IntArray(6) { red }))
        val anim = KiteImage.decodeAnimation(still)
        assertEquals(1, anim.frames.size)
        assertEquals(3, anim.width)
        assertEquals(2, anim.height)
        assertEquals(1, anim.loopCount)
        assertTrue(!anim.isAnimated)
    }

    @Test
    fun defaultImageCanBeFrameZero() {
        val bytes = apng(
            width = 2, height = 1,
            defaultImage = solid(2, 1, red),
            frames = listOf(solid(2, 1, green)),
            defaultIsFirstFrame = true,
            loops = 3,
        )
        val anim = KiteImage.decodeAnimation(bytes)
        assertEquals(2, anim.frames.size)
        assertEquals(3, anim.loopCount)
        assertEquals(red, anim.frames[0].bitmap[0, 0])
        assertEquals(green, anim.frames[1].bitmap[0, 0])
    }

    @Test
    fun defaultImageCanSitOutsideTheAnimation() {
        val bytes = apng(
            width = 2, height = 1,
            defaultImage = solid(2, 1, red),
            frames = listOf(solid(2, 1, green), solid(2, 1, blue)),
            defaultIsFirstFrame = false,
        )
        // decode() shows what a non-APNG viewer shows: the default image.
        assertEquals(red, KiteImage.decode(bytes)[0, 0])

        val anim = KiteImage.decodeAnimation(bytes)
        assertEquals(2, anim.frames.size)
        assertEquals(green, anim.frames[0].bitmap[0, 0])
        assertEquals(blue, anim.frames[1].bitmap[0, 0])
    }

    @Test
    fun frameRectsCompositeOntoTheCanvas() {
        val bytes = apng(
            width = 2, height = 2,
            defaultImage = solid(2, 2, red),
            frames = listOf(solid(1, 1, green, x = 1, y = 1)),
            defaultIsFirstFrame = true,
        )
        val anim = KiteImage.decodeAnimation(bytes)
        val f1 = anim.frames[1].bitmap
        assertEquals(red, f1[0, 0])
        assertEquals(red, f1[1, 0])
        assertEquals(red, f1[0, 1])
        assertEquals(green, f1[1, 1], "only the frame rect changes")
    }

    @Test
    fun disposeBackgroundClearsOnlyThatRect() {
        val bytes = apng(
            width = 2, height = 1,
            defaultImage = solid(2, 1, red),
            frames = listOf(
                solid(1, 1, green, x = 0, y = 0, dispose = 1),   // clears its own pixel after showing
                solid(1, 1, blue, x = 1, y = 0),
            ),
            defaultIsFirstFrame = true,
        )
        val anim = KiteImage.decodeAnimation(bytes)
        assertEquals(green, anim.frames[1].bitmap[0, 0])
        // Frame 1 disposed to background, so (0,0) is transparent when frame 2 draws.
        assertEquals(0, anim.frames[2].bitmap[0, 0])
        assertEquals(blue, anim.frames[2].bitmap[1, 0])
    }

    @Test
    fun disposePreviousRestoresTheCanvas() {
        val bytes = apng(
            width = 2, height = 1,
            defaultImage = solid(2, 1, red),
            frames = listOf(
                solid(1, 1, green, x = 0, y = 0, dispose = 2),   // undo after showing
                solid(1, 1, blue, x = 1, y = 0),
            ),
            defaultIsFirstFrame = true,
        )
        val anim = KiteImage.decodeAnimation(bytes)
        assertEquals(green, anim.frames[1].bitmap[0, 0])
        assertEquals(red, anim.frames[2].bitmap[0, 0], "canvas restored to the pre-frame state")
        assertEquals(blue, anim.frames[2].bitmap[1, 0])
    }

    @Test
    fun blendSourceOverwritesAlphaInsteadOfCompositing() {
        val translucent = argb(0x80, 0, 0, 0xFF)
        val bytes = apng(
            width = 1, height = 1,
            defaultImage = solid(1, 1, red),
            frames = listOf(Sub(1, 1, pixels = intArrayOf(translucent), blend = 0)),
            defaultIsFirstFrame = true,
        )
        val anim = KiteImage.decodeAnimation(bytes)
        assertEquals(translucent, anim.frames[1].bitmap[0, 0], "SOURCE replaces, alpha and all")
    }

    @Test
    fun blendOverCompositesAgainstWhatIsAlreadyThere() {
        val translucent = argb(0x80, 0, 0, 0xFF)
        val bytes = apng(
            width = 1, height = 1,
            defaultImage = solid(1, 1, red),
            frames = listOf(Sub(1, 1, pixels = intArrayOf(translucent), blend = 1)),
            defaultIsFirstFrame = true,
        )
        val p = KiteImage.decodeAnimation(bytes).frames[1].bitmap[0, 0]
        // Source-over of 50% blue on opaque red, worked by hand from the spec's
        // formula: alpha saturates to 255, red keeps 127/255, blue gains 128/255.
        assertEquals(0xFF, p ushr 24, "alpha")
        assertEquals(127, (p ushr 16) and 0xFF, "red")
        assertEquals(0, (p ushr 8) and 0xFF, "green")
        assertEquals(128, p and 0xFF, "blue")
    }

    @Test
    fun delaysConvertToMillisecondsAndKeepTheirRawCentiseconds() {
        val bytes = apng(
            width = 1, height = 1,
            defaultImage = Sub(1, 1, pixels = intArrayOf(red), delayNum = 5, delayDen = 100),   // 50 ms
            frames = listOf(Sub(1, 1, pixels = intArrayOf(green), delayNum = 1, delayDen = 2)), // 500 ms
            defaultIsFirstFrame = true,
        )
        val anim = KiteImage.decodeAnimation(bytes)
        assertEquals(50, anim.frames[0].delayMillis)
        assertEquals(5, anim.frames[0].delayRawCentiseconds)
        assertEquals(500, anim.frames[1].delayMillis)
        assertEquals(550, anim.durationMillis)
    }

    @Test
    fun zeroDelayGetsTheBrowserClamp() {
        val bytes = apng(
            width = 1, height = 1,
            defaultImage = Sub(1, 1, pixels = intArrayOf(red), delayNum = 0, delayDen = 100),
            frames = listOf(Sub(1, 1, pixels = intArrayOf(green), delayNum = 0, delayDen = 0)),
            defaultIsFirstFrame = true,
        )
        val anim = KiteImage.decodeAnimation(bytes)
        assertEquals(100, anim.frames[0].delayMillis, "0 cs renders as fast as possible → 100 ms")
        assertEquals(100, anim.frames[1].delayMillis, "delay_den 0 means 100")
    }

    @Test
    fun probeAgreesWithTheAnimationDecoder() {
        val bytes = apng(
            width = 4, height = 3,
            defaultImage = solid(4, 3, red),
            frames = listOf(solid(4, 3, green), solid(4, 3, blue)),
            defaultIsFirstFrame = true,
            loops = 7,
        )
        val info = KiteImage.probe(bytes)
        val anim = KiteImage.decodeAnimation(bytes)
        assertEquals(anim.frames.size, info.frameCount)
        assertEquals(anim.loopCount, info.loopCount)
        assertEquals(4, info.width)
        assertEquals(3, info.height)
        assertTrue(info.isAnimated)
        assertTrue(info.hasAlpha, "colour type 6")
    }

    @Test
    fun frameLeavingTheCanvasIsRejected() {
        val bytes = apng(
            width = 2, height = 2,
            defaultImage = solid(2, 2, red),
            frames = listOf(solid(2, 2, green, x = 1, y = 0)),   // 2 wide at x=1 in a 2-wide canvas
            defaultIsFirstFrame = true,
        )
        val e = assertFailsWith<ImageDecodeException> { KiteImage.decodeAnimation(bytes) }
        assertTrue(e.message!!.contains("canvas"), "message should name the problem: ${e.message}")
    }

    @Test
    fun frameCountMismatchIsRejected() {
        val bytes = apng(
            width = 1, height = 1,
            defaultImage = solid(1, 1, red),
            frames = listOf(solid(1, 1, green)),
            defaultIsFirstFrame = true,
            declaredFrames = 9,
        )
        assertFailsWith<ImageDecodeException> { KiteImage.decodeAnimation(bytes) }
    }

    @Test
    fun badDisposeOpIsRejected() {
        val bytes = apng(
            width = 1, height = 1,
            defaultImage = solid(1, 1, red),
            frames = listOf(solid(1, 1, green, dispose = 7)),
            defaultIsFirstFrame = true,
        )
        assertFailsWith<ImageDecodeException> { KiteImage.decodeAnimation(bytes) }
    }

    @Test
    fun stillDecodeIgnoresTheAnimationChunks() {
        val bytes = apng(
            width = 2, height = 1,
            defaultImage = solid(2, 1, red),
            frames = listOf(solid(2, 1, green)),
            defaultIsFirstFrame = false,
        )
        val bm = KiteImage.decode(bytes)
        assertEquals(2, bm.width)
        assertEquals(red, bm[0, 0])
        assertEquals(red, bm[1, 0])
    }

    @Test
    fun cancellationStopsBetweenFrames() {
        class Abort : RuntimeException()
        val bytes = apng(
            width = 1, height = 1,
            defaultImage = solid(1, 1, red),
            frames = listOf(solid(1, 1, green), solid(1, 1, blue)),
            defaultIsFirstFrame = true,
        )
        var calls = 0
        assertFailsWith<Abort> {
            KiteImage.decodeAnimation(bytes) {
                calls++
                throw Abort()
            }
        }
        assertEquals(1, calls)
    }
}
