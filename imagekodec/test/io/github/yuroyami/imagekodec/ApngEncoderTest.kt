package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The APNG writer (#64): what it writes reads back as the frames, delays and loop count it was given. */
class ApngEncoderTest {

    /** Frame [t] of a scene: a gradient with a square moving over it, translucent where [translucent]. */
    private fun frame(w: Int, h: Int, t: Int, alpha: Boolean, translucent: Boolean): KiteBitmap = KiteBitmap(w, h, IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        val inSquare = x - 3 * t in 4..12 && y - t in 3..9
        val a = when {
            !alpha -> 0xFF
            inSquare -> if (translucent) 0x80 else 0xFF
            else -> (x * 17 + y * 5) and 0xFF
        }
        if (inSquare) argb(a, 250, 20 + 30 * t, 40) else argb(a, x * 7 and 0xFF, y * 11 and 0xFF, 90)
    })

    private fun animation(frames: List<KiteBitmap>, delays: List<Int>, loops: Long) = KiteAnimation(
        frames[0].width, frames[0].height,
        frames.mapIndexed { i, b -> KiteFrame(b, delays[i], (delays[i] + 5) / 10) },
        loops,
    )

    private fun assertRoundTrip(name: String, a: KiteAnimation) {
        val data = ImageKodec.encodePng(a)
        val back = ImageKodec.decodeAnimation(data)
        assertEquals(a.frames.size, back.frames.size, "$name frames")
        assertEquals(a.loopCount, back.loopCount, "$name loop count")
        for ((i, f) in a.frames.withIndex()) {
            assertContentEquals(f.bitmap.argb, back.frames[i].bitmap.argb, "$name frame $i")
            assertEquals(f.delayMillis, back.frames[i].delayMillis, "$name delay $i")
        }
        // A reader without APNG support shows the first frame.
        assertContentEquals(a.frames[0].bitmap.argb, ImageKodec.decode(data).argb, "$name default image")
        assertEquals(a.frames.size, ImageKodec.probe(data).frameCount, "$name probed frames")
    }

    @Test
    fun translucentFramesReadBackExactly() {
        val frames = List(5) { frame(31, 17, it, alpha = true, translucent = true) }
        assertRoundTrip("translucent", animation(frames, listOf(100, 40, 1000, 33, 70000), 0))
    }

    /** The blend operation of each fcTL in [png]. */
    private fun blends(png: ByteArray): List<Int> {
        val out = ArrayList<Int>()
        var at = 8
        while (at + 8 <= png.size) {
            val len = ((png[at].toInt() and 255) shl 24) or ((png[at + 1].toInt() and 255) shl 16) or
                ((png[at + 2].toInt() and 255) shl 8) or (png[at + 3].toInt() and 255)
            val type = png.copyOfRange(at + 4, at + 8).decodeToString()
            if (type == "fcTL") out += png[at + 8 + 25].toInt()
            at += 12 + len
        }
        return out
    }

    @Test
    fun opaqueChangesOverATranslucentCanvasReadBackExactly() {
        val frames = List(4) { frame(40, 24, it, alpha = true, translucent = false) }
        val a = animation(frames, listOf(50, 50, 50, 50), 3)
        assertRoundTrip("opaque changes", a)
        // Two opaque dots at opposite corners of a translucent gradient change colour: drawing
        // only them over the canvas is smaller than replacing the whole box between them.
        val dots = List(4) { t ->
            val b = frame(40, 24, 0, alpha = true, translucent = false).argb.copyOf()
            for (y in 0 until 3) for (x in 0 until 3) {
                b[y * 40 + x] = argb(0xFF, 60 * t, 10, 200)
                b[(21 + y) * 40 + 37 + x] = argb(0xFF, 10, 60 * t, 90)
            }
            KiteBitmap(40, 24, b)
        }
        val dotted = animation(dots, listOf(60, 60, 60, 60), 0)
        assertRoundTrip("opaque dots", dotted)
        assertEquals(listOf(0, 1, 1, 1), blends(ImageKodec.encodePng(dotted)), "the dots are not drawn over the canvas")
        // A moving square uncovers translucent pixels, which cannot be drawn over.
        assertEquals(listOf(0, 0, 0, 0), blends(ImageKodec.encodePng(a)))
        // Translucent changes cannot be drawn over: they would blend with what is under them.
        val translucent = animation(List(4) { frame(40, 24, it, alpha = true, translucent = true) }, listOf(50, 50, 50, 50), 3)
        assertEquals(listOf(0, 0, 0, 0), blends(ImageKodec.encodePng(translucent)))
    }

    @Test
    fun opaqueFramesAndRepeatsReadBackExactly() {
        val a = frame(20, 12, 0, alpha = false, translucent = false)
        val b = frame(20, 12, 2, alpha = false, translucent = false)
        assertRoundTrip("opaque with a repeat", animation(listOf(a, a, b, b, a), listOf(100, 200, 300, 400, 500), 1))
    }

    @Test
    fun aSmallChangeIsWrittenSmall() {
        val a = frame(200, 120, 0, alpha = true, translucent = false)
        val b = KiteBitmap(200, 120, a.argb.copyOf().also { it[60 * 200 + 100] = argb(0xFF, 1, 2, 3) })
        val two = ImageKodec.encodePng(animation(listOf(a, b), listOf(100, 100), 0))
        val one = ImageKodec.encodePng(a)
        assertTrue(two.size < one.size + 200, "a one-pixel change took ${two.size - one.size} bytes")
    }

    @Test
    fun aSingleFrameIsAPlainPng() {
        val a = frame(9, 7, 0, alpha = true, translucent = true)
        val data = ImageKodec.encodePng(animation(listOf(a), listOf(100), 0))
        assertContentEquals(ImageKodec.encodePng(a), data)
    }

    @Test
    fun framesOfAnotherSizeOrAHugePlayCountAreRefused() {
        val a = frame(9, 7, 0, alpha = false, translucent = false)
        val b = frame(8, 7, 0, alpha = false, translucent = false)
        assertFailsWith<IllegalArgumentException> {
            ImageKodec.encodePng(KiteAnimation(9, 7, listOf(KiteFrame(a, 100, 10), KiteFrame(b, 100, 10)), 0))
        }
        assertFailsWith<IllegalArgumentException> {
            ImageKodec.encodePng(KiteAnimation(9, 7, listOf(KiteFrame(a, 100, 10), KiteFrame(a, 100, 10)), 0x1_0000_0000L))
        }
    }
}
