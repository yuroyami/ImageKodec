package io.github.yuroyami.imagekodec

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The PNG writer with options (#182): every colour type and bit depth, interlaced and not, reads
 * back through [ImageKodec.decode] and [ImageKodec.decode16] to exactly what it was given, and
 * [PngColorType.Auto] picks the type the pixels need.
 */
class PngEncodeOptionsTest {

    private class Header(val width: Int, val height: Int, val depth: Int, val colorType: Int, val interlace: Int)

    private fun header(png: ByteArray): Header {
        fun be(at: Int) = ((png[at].toInt() and 255) shl 24) or ((png[at + 1].toInt() and 255) shl 16) or
            ((png[at + 2].toInt() and 255) shl 8) or (png[at + 3].toInt() and 255)
        assertEquals("IHDR", png.copyOfRange(12, 16).decodeToString())
        return Header(be(16), be(20), png[24].toInt(), png[25].toInt(), png[28].toInt())
    }

    /** The chunk types of [png] in order. */
    private fun chunks(png: ByteArray): List<String> {
        val out = ArrayList<String>()
        var at = 8
        while (at + 8 <= png.size) {
            val len = ((png[at].toInt() and 255) shl 24) or ((png[at + 1].toInt() and 255) shl 16) or
                ((png[at + 2].toInt() and 255) shl 8) or (png[at + 3].toInt() and 255)
            out += png.copyOfRange(at + 4, at + 8).decodeToString()
            at += 12 + len
        }
        return out
    }

    private fun fnv(bytes: ByteArray): Long {
        var h = -0x340d631b7bdddcdbL
        for (b in bytes) h = (h xor (b.toLong() and 255)) * 0x100000001b3L
        return h
    }

    // --- Pictures of each kind ----------------------------------------------------------------

    /** Gray with values that fit [depth] bits, in a pattern that uses all of them. */
    private fun grayAt(depth: Int, w: Int, h: Int, seed: Int = 1): KiteBitmap {
        val levels = (1 shl minOf(depth, 8)) - 1
        val r = Random(seed)
        return KiteBitmap(w, h, IntArray(w * h) { i -> gray(((i % w + i / w + r.nextInt(3)) % (levels + 1)) * 255 / levels) })
    }

    /** [colours] distinct colours, translucent ones among them when [alpha]. */
    private fun paletted(colours: Int, w: Int, h: Int, alpha: Boolean): KiteBitmap {
        // Green is the index, so the colours are distinct.
        val table = IntArray(colours) { c ->
            val a = if (alpha && c % 3 == 0) (c * 37) and 0xFF else 0xFF
            argb(a, (c * 53) and 0xFF, c, (c * 29 + 7) and 0xFF)
        }
        val r = Random(colours)
        return KiteBitmap(w, h, IntArray(w * h) { i -> if (i < colours) table[i] else table[r.nextInt(colours)] })
    }

    private fun photo(w: Int, h: Int, alpha: Boolean): KiteBitmap {
        val r = Random(w * 31 + h)
        return KiteBitmap(w, h, IntArray(w * h) { i ->
            argb(if (alpha) r.nextInt(256) else 0xFF, (i * 7) and 0xFF, r.nextInt(256), (i / w * 5) and 0xFF)
        })
    }

    /** [base] with every pixel whose index is a multiple of 5 made the transparent colour [key]. */
    private fun keyed(base: KiteBitmap, key: Int): KiteBitmap =
        KiteBitmap(base.width, base.height, IntArray(base.argb.size) { i -> if (i % 5 == 0) key else base.argb[i] or (0xFF shl 24) })

    private val sizes = listOf(1 to 1, 1 to 9, 9 to 1, 2 to 2, 3 to 3, 5 to 1, 1 to 5, 4 to 4, 7 to 3, 17 to 11)

    private fun roundTrip(name: String, bitmap: KiteBitmap, options: PngEncodeOptions): ByteArray {
        val png = ImageKodec.encodePng(bitmap, options)
        val back = ImageKodec.decode(png)
        assertEquals(bitmap.width, back.width, name)
        assertEquals(bitmap.height, back.height, name)
        assertContentEquals(bitmap.argb, back.argb, "$name with $options")
        assertEquals(if (options.interlace) 1 else 0, header(png).interlace, name)
        return png
    }

    // --- Auto ---------------------------------------------------------------------------------

    @Test
    fun autoPicksTheSmallestExactType() {
        // (picture, colour type, depth) Auto must choose.
        val cases = listOf(
            Triple("gray 1", grayAt(1, 17, 11), 0 to 1),
            Triple("gray 2", grayAt(2, 17, 11), 0 to 2),
            Triple("gray 4", grayAt(4, 17, 11), 0 to 4),
            Triple("gray 8", grayAt(8, 64, 11), 0 to 8),
            Triple("gray 1 keyed", keyed(grayAt(1, 17, 11), argb(0, 0, 0, 0)).let { b ->
                // Black is the key, so no opaque pixel may be black.
                KiteBitmap(b.width, b.height, IntArray(b.argb.size) { i -> if (b.argb[i] == argb(0xFF, 0, 0, 0)) argb(0xFF, 255, 255, 255) else b.argb[i] })
            }, 0 to 1),
            Triple("palette 2", paletted(2, 17, 11, alpha = false), 3 to 1),
            Triple("palette 4", paletted(4, 17, 11, alpha = false), 3 to 2),
            Triple("palette 16", paletted(16, 17, 11, alpha = true), 3 to 4),
            Triple("palette 200", paletted(200, 40, 30, alpha = true), 3 to 8),
            Triple("rgb", photo(40, 30, alpha = false), 2 to 8),
            Triple("rgb keyed", keyed(photo(40, 30, alpha = false), argb(0, 1, 2, 3)), 2 to 8),
            Triple("rgba", photo(40, 30, alpha = true), 6 to 8),
        )
        for ((name, bitmap, expected) in cases) {
            for (effort in 1..3) for (interlace in listOf(false, true)) {
                val png = roundTrip(name, bitmap, PngEncodeOptions(effort = effort, interlace = interlace))
                val h = header(png)
                if (effort == 1) assertEquals(expected, h.colorType to h.depth, "$name at effort $effort")
            }
        }
    }

    @Test
    fun keyedPixelsCarryTRNS() {
        val keyedRgb = ImageKodec.encodePng(keyed(photo(20, 10, alpha = false), argb(0, 9, 8, 7)), PngEncodeOptions())
        assertTrue("tRNS" in chunks(keyedRgb))
        // Two colours under alpha 0 cannot be one key, so the file keeps an alpha channel.
        val two = keyed(photo(20, 10, alpha = false), argb(0, 9, 8, 7)).let { b ->
            KiteBitmap(b.width, b.height, b.argb.copyOf().also { it[1] = argb(0, 1, 1, 1) })
        }
        assertEquals(6, header(roundTrip("two keys", two, PngEncodeOptions())).colorType)
        // An opaque pixel of the key's colour forbids the key as well.
        val clash = keyed(photo(20, 10, alpha = false), argb(0, 9, 8, 7)).let { b ->
            KiteBitmap(b.width, b.height, b.argb.copyOf().also { it[1] = argb(0xFF, 9, 8, 7) })
        }
        assertEquals(6, header(roundTrip("clash", clash, PngEncodeOptions())).colorType)
    }

    // --- Fixed types at every depth -----------------------------------------------------------

    @Test
    fun everyFixedTypeAndDepthRoundTrips() {
        val cases = listOf(
            PngColorType.Gray to listOf(1, 2, 4, 8, 16),
            PngColorType.GrayAlpha to listOf(8, 16),
            PngColorType.Rgb to listOf(8, 16),
            PngColorType.Rgba to listOf(8, 16),
            PngColorType.Palette to listOf(1, 2, 4, 8),
        )
        for ((type, depths) in cases) for (depth in depths) for ((w, h) in sizes) {
            val bitmap = when (type) {
                PngColorType.Gray -> grayAt(depth, w, h)
                PngColorType.GrayAlpha -> KiteBitmap(w, h, grayAt(8, w, h).argb.mapIndexed { i, p -> p and 0xFFFFFF or (((i * 41) and 0xFF) shl 24) }.toIntArray())
                PngColorType.Palette -> paletted(minOf(1 shl depth, w * h), w, h, alpha = true)
                PngColorType.Rgba -> photo(w, h, alpha = true)
                else -> photo(w, h, alpha = false)
            }
            for (interlace in listOf(false, true)) {
                val png = roundTrip("$type $depth ${w}x$h", bitmap, PngEncodeOptions(colorType = type, bitDepth = depth, interlace = interlace))
                assertEquals(depth, header(png).depth, "$type $depth ${w}x$h")
                // decode16 reads the same picture: its high bytes are decode's pixels.
                val wide = ImageKodec.decode16(png)
                assertContentEquals(bitmap.argb, wide.toBitmap().argb, "$type $depth ${w}x$h decode16")
            }
        }
    }

    @Test
    fun fixedTypesRefuseWhatTheyCannotHold() {
        val colour = photo(8, 8, alpha = false)
        val translucent = photo(8, 8, alpha = true)
        val refusals = listOf(
            "not gray" to { ImageKodec.encodePng(colour, PngEncodeOptions(colorType = PngColorType.Gray)) },
            "4 bits" to { ImageKodec.encodePng(grayAt(8, 64, 2), PngEncodeOptions(colorType = PngColorType.Gray, bitDepth = 4)) },
            "partial alpha" to { ImageKodec.encodePng(translucent, PngEncodeOptions(colorType = PngColorType.Rgb)) },
            "256 colours" to { ImageKodec.encodePng(photo(40, 40, alpha = false), PngEncodeOptions(colorType = PngColorType.Palette)) },
            "indexes" to { ImageKodec.encodePng(paletted(5, 8, 8, alpha = false), PngEncodeOptions(colorType = PngColorType.Palette, bitDepth = 2)) },
            "16-bit samples" to {
                ImageKodec.encodePng16(KiteBitmap16(2, 1, 3, shortArrayOf(1, 2, 3, 4, 5, 6)), PngEncodeOptions(colorType = PngColorType.Rgb, bitDepth = 8))
            },
            "gray icc" to {
                val icc = ByteArray(128).also { "GRAY".encodeToByteArray().copyInto(it, 16) }
                ImageKodec.encodePng(colour, PngEncodeOptions(colorType = PngColorType.Rgb), ImageMetadata(icc = icc))
            },
        )
        for ((name, call) in refusals) {
            val e = assertFailsWith<IllegalArgumentException>(name) { call() }
            assertTrue(e.message.orEmpty().isNotEmpty(), name)
        }
        for (bad in listOf(
            { PngEncodeOptions(effort = 0) },
            { PngEncodeOptions(effort = 4) },
            { PngEncodeOptions(bitDepth = 8) },
            { PngEncodeOptions(colorType = PngColorType.Gray, bitDepth = 3) },
            { PngEncodeOptions(colorType = PngColorType.Palette, bitDepth = 16) },
            { PngEncodeOptions(colorType = PngColorType.Rgb, bitDepth = 4) },
        )) assertFailsWith<IllegalArgumentException> { bad() }
    }

    @Test
    fun anIccProfileKeepsTheColourSpace() {
        val icc = ByteArray(128).also { "RGB ".encodeToByteArray().copyInto(it, 16) }
        val png = ImageKodec.encodePng(grayAt(8, 16, 16), PngEncodeOptions(), ImageMetadata(icc = icc))
        assertTrue(header(png).colorType != 0 && header(png).colorType != 4, "an RGB profile on a gray PNG")
        assertContentEquals(grayAt(8, 16, 16).argb, ImageKodec.decode(png).argb)
        assertEquals(listOf("IHDR", "iCCP"), chunks(png).take(2))
    }

    // --- 16 bits ------------------------------------------------------------------------------

    private fun sixteen(w: Int, h: Int, channels: Int, seed: Int, narrow: (Int) -> Int = { it }): KiteBitmap16 {
        val r = Random(seed)
        return KiteBitmap16(w, h, channels, ShortArray(w * h * channels) { narrow(r.nextInt(65536)).toShort() })
    }

    @Test
    fun sixteenBitsRoundTripThroughDecode16() {
        for (channels in 1..4) for ((w, h) in sizes) for (interlace in listOf(false, true)) {
            val bitmap = sixteen(w, h, channels, w * 7 + h + channels)
            val png = ImageKodec.encodePng16(bitmap, PngEncodeOptions(interlace = interlace))
            val back = ImageKodec.decode16(png)
            assertEquals(channels, back.channels, "$channels channels ${w}x$h")
            assertContentEquals(bitmap.samples, back.samples, "$channels channels ${w}x$h")
            // decode gives the high bytes.
            assertContentEquals(bitmap.toBitmap().argb, ImageKodec.decode(png).argb)
        }
    }

    @Test
    fun sixteenBitAutoNarrowsWhatFits() {
        val widened = { v: Int -> (v ushr 8) * 257 }
        // (picture, colour type, depth) Auto must choose, decode16 keeping its channels.
        val gray4 = KiteBitmap16(9, 5, 1, ShortArray(45) { ((it % 16) * 4369).toShort() })
        val palette = KiteBitmap16(9, 5, 3, ShortArray(135) { i -> (((i / 3) % 6) * 257 * 40 + (i % 3) * 257).toShort() })
        val translucentPalette = KiteBitmap16(9, 5, 4, ShortArray(180) { i -> if (i % 4 == 3) (((i / 4) % 2) * 257 * 100).toShort() else (((i / 4) % 5) * 257 * 50).toShort() })
        val keyedGray = KiteBitmap16(9, 5, 2, ShortArray(90) { i ->
            val p = i / 2
            val transparent = p % 7 == 0
            if (i % 2 == 1) (if (transparent) 0 else 65535).toShort() else (if (transparent) 1234 else 2000 + p * 300).toShort()
        })
        val cases = listOf(
            Triple("gray 4", gray4, 0 to 4),
            Triple("gray 8", sixteen(9, 5, 1, 3, widened), 0 to 8),
            Triple("gray 16", sixteen(9, 5, 1, 3), 0 to 16),
            Triple("gray alpha 8", sixteen(9, 5, 2, 4, widened), 4 to 8),
            Triple("gray keyed 16", keyedGray, 0 to 16),
            Triple("palette", palette, 3 to 4),
            Triple("translucent palette", translucentPalette, 3 to 4),
            Triple("rgb 8", sixteen(40, 30, 3, 5, widened), 2 to 8),
            Triple("rgba 16", sixteen(9, 5, 4, 6), 6 to 16),
        )
        for ((name, bitmap, expected) in cases) for (effort in 1..3) {
            val png = ImageKodec.encodePng16(bitmap, PngEncodeOptions(effort = effort))
            val back = ImageKodec.decode16(png)
            assertEquals(bitmap.channels, back.channels, name)
            assertContentEquals(bitmap.samples, back.samples, "$name at effort $effort")
            if (effort == 1) assertEquals(expected, header(png).colorType to header(png).depth, "$name at effort $effort")
        }
    }

    @Test
    fun aFixedTypeGivesItsOwnChannels() {
        val rgb = KiteBitmap16(3, 2, 3, ShortArray(18) { (it / 3 * 4000).toShort() })
        val gray = ImageKodec.decode16(ImageKodec.encodePng16(rgb, PngEncodeOptions(colorType = PngColorType.Gray)))
        assertEquals(1, gray.channels)
        for (i in 0 until 6) assertEquals(rgb.samples[i * 3], gray.samples[i])
        val rgba = ImageKodec.decode16(ImageKodec.encodePng16(KiteBitmap16(2, 2, 1, shortArrayOf(1, 2, 3, 4)), PngEncodeOptions(colorType = PngColorType.Rgba)))
        assertEquals(4, rgba.channels)
        assertContentEquals(shortArrayOf(1, 1, 1, -1, 2, 2, 2, -1, 3, 3, 3, -1, 4, 4, 4, -1), rgba.samples)
    }

    // --- Size, hashes, cancel, animation ------------------------------------------------------

    @Test
    fun aPaletteOfTwoHundredColoursIsSmallerThanRgb() {
        val bitmap = paletted(200, 128, 96, alpha = false)
        val rgb = ImageKodec.encodePng(bitmap)
        val auto = ImageKodec.encodePng(bitmap, PngEncodeOptions())
        assertEquals(3, header(auto).colorType)
        assertTrue(auto.size < rgb.size, "palette ${auto.size} bytes, RGB ${rgb.size}")
    }

    @Test
    fun outputIsTheSameOnEveryPlatform() {
        val few = paletted(40, 33, 21, alpha = true)
        val sixteen = sixteen(13, 7, 4, 99)
        val got = listOf(
            fnv(ImageKodec.encodePng(grayAt(2, 31, 9), PngEncodeOptions())),
            fnv(ImageKodec.encodePng(few, PngEncodeOptions(interlace = true))),
            fnv(ImageKodec.encodePng(few, PngEncodeOptions(effort = 1))),
            fnv(ImageKodec.encodePng(few, PngEncodeOptions(effort = 3))),
            fnv(ImageKodec.encodePng(PhotoScenes.city(64, 40).bitmap, PngEncodeOptions(effort = 3, interlace = true))),
            fnv(ImageKodec.encodePng16(sixteen, PngEncodeOptions())),
            fnv(ImageKodec.encodePng16(sixteen, PngEncodeOptions(interlace = true, effort = 3))),
            fnv(ImageKodec.encodePng(KiteAnimation(33, 21, List(3) { t -> KiteFrame(KiteBitmap(33, 21, IntArray(33 * 21) { i -> few.argb[(i + 5 * t) % few.argb.size] }), 40, 4) }, 0), PngEncodeOptions(effort = 3))),
        ).map { it.toULong().toString(16) }
        assertEquals(EXPECTED, got)
    }

    @Test
    fun cancellationStopsTheEncode() {
        class Stop : RuntimeException()
        var calls = 0
        val bitmap = PhotoScenes.landscape(64, 64).bitmap
        assertFailsWith<Stop> {
            ImageKodec.encodePng(bitmap, PngEncodeOptions(), cancellationCheck = { if (++calls > 3) throw Stop() })
        }
        assertFailsWith<Stop> {
            ImageKodec.encodePng16(sixteen(64, 64, 3, 1), PngEncodeOptions(), cancellationCheck = { throw Stop() })
        }
        var seen = 0
        ImageKodec.encodePng(bitmap, PngEncodeOptions(), cancellationCheck = { seen++ })
        assertTrue(seen > 64, "the check ran $seen times")
    }

    @Test
    fun animationsTakeOptions() {
        fun frame(t: Int, colours: Boolean, alpha: Boolean) = KiteBitmap(23, 13, IntArray(23 * 13) { i ->
            val x = i % 23
            val y = i / 23
            val inSquare = x - 3 * t in 2..8 && y - t in 1..6
            when {
                inSquare -> if (colours) argb(0xFF, 250, 20 + 30 * t, 40) else gray(255)
                alpha && (x + y) % 4 == 0 -> 0
                colours -> argb(0xFF, (x / 4) * 40, (y / 4) * 60, 90)
                // Gray 0 is left to the transparent pixels, so it can be their key.
                else -> gray(85 * (1 + x / 12))
            }
        })
        val kinds = listOf("palette" to (true to true), "gray keyed" to (false to true), "gray" to (false to false), "palette opaque" to (true to false))
        for ((name, kind) in kinds) for (interlace in listOf(false, true)) for (effort in 1..3) {
            val frames = List(4) { frame(it, kind.first, kind.second) }
            val animation = KiteAnimation(23, 13, frames.mapIndexed { i, b -> KiteFrame(b, 50 + i, 5) }, 3)
            val png = ImageKodec.encodePng(animation, PngEncodeOptions(interlace = interlace, effort = effort))
            val back = ImageKodec.decodeAnimation(png)
            assertEquals(4, back.frames.size, name)
            assertEquals(3, back.loopCount, name)
            for (i in 0 until 4) assertContentEquals(frames[i].argb, back.frames[i].bitmap.argb, "$name frame $i interlace $interlace effort $effort")
            if (kind.first) assertEquals(3, header(png).colorType, name) else assertEquals(0, header(png).colorType, name)
        }
        // A single frame is a still.
        val one = KiteAnimation(23, 13, listOf(KiteFrame(frame(0, true, false), 10, 1)), 0)
        assertContentEquals(ImageKodec.encodePng(one.frames[0].bitmap, PngEncodeOptions()), ImageKodec.encodePng(one, PngEncodeOptions()))
    }

    private companion object {
        val EXPECTED = listOf(
            "5821d5689f9265ed", "9aad5a2171fb07e4", "b5d0c82b59553fcb", "a9b71c8eced0a862",
            "86bf0bf34eac8df6", "cdd09e6499129b7b", "5485a97e155aa7e8", "20fd2fead08f0817",
        )
    }
}
