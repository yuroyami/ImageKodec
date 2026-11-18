package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The JPEG 2000 encoder on every target (#180): lossless files decode to their source exactly at
 * every size, channel count and depth, lossy ones decode close to it, the options refuse what the
 * format cannot hold, a cancellation check stops the encode, and fixed pictures give the same bytes
 * everywhere, which the committed hashes hold.
 */
class Jp2EncoderTest {

    /** A deterministic picture with gradients, edges and noise, [alpha] varying when asked. */
    private fun picture(w: Int, h: Int, gray: Boolean = false, alpha: Boolean = false, seed: Int = 1): KiteBitmap {
        var state = seed * 7919 + 13
        fun next(): Int {
            state = state * 1103515245 + 12345
            return (state ushr 16) and 0x7FFF
        }
        val argb = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val n = next() % 9 - 4
            val edge = if ((x / 5 + y / 3) % 2 == 0) 60 else 0
            val r = (x * 200 / maxOf(1, w) + edge + n).coerceIn(0, 255)
            val g = (y * 220 / maxOf(1, h) + n).coerceIn(0, 255)
            val b = ((x + y) * 3 + edge / 2 + n).coerceIn(0, 255)
            val a = if (alpha) ((x * 37 + y * 11) and 0xFF) else 0xFF
            if (gray) (a shl 24) or (r * 0x010101) else (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return KiteBitmap(w, h, argb)
    }

    private fun psnr(a: KiteBitmap, b: KiteBitmap): Double {
        var se = 0L
        for (i in a.argb.indices) for (shift in intArrayOf(0, 8, 16, 24)) {
            val d = ((a.argb[i] ushr shift) and 0xFF) - ((b.argb[i] ushr shift) and 0xFF)
            se += d * d
        }
        if (se == 0L) return 99.0
        val mse = se.toDouble() / (a.argb.size * 4)
        // 10 log10(255^2 / mse), with the logarithm counted out in halvings so no Float literal is compared.
        var ratio = 255.0 * 255.0 / mse
        var db = 0.0
        while (ratio >= 2.0) { ratio /= 2.0; db += 3.0103 }
        return db
    }

    private val sizes = listOf(1 to 1, 1 to 9, 9 to 1, 2 to 2, 31 to 17, 17 to 31, 64 to 3, 65 to 33)

    @Test
    fun losslessReturnsEveryPictureExactly() {
        for ((w, h) in sizes) for (gray in listOf(false, true)) for (alpha in listOf(false, true)) {
            val bitmap = picture(w, h, gray, alpha, seed = w * 31 + h)
            val bytes = ImageKodec.encodeJp2(bitmap, Jp2EncodeOptions(lossless = true))
            assertEquals(ImageFormat.JP2, ImageKodec.detect(bytes))
            val back = ImageKodec.decode(bytes)
            assertContentEquals(bitmap.argb, back.argb, "${w}x$h gray=$gray alpha=$alpha")
        }
    }

    @Test
    fun losslessHoldsFlatTransparentAndManyColouredPictures() {
        val flat = KiteBitmap(40, 30, IntArray(1200) { 0xFF336699.toInt() })
        val clear = KiteBitmap(13, 7, IntArray(91) { 0 })
        val many = KiteBitmap(64, 64, IntArray(4096) { (0xFF shl 24) or (it * 4099) })
        val few = KiteBitmap(20, 20, IntArray(400) { if ((it / 3) % 2 == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() })
        for ((name, bitmap) in listOf("flat" to flat, "clear" to clear, "many" to many, "few" to few)) {
            for (codestream in listOf(false, true)) {
                val bytes = ImageKodec.encodeJp2(bitmap, Jp2EncodeOptions(lossless = true, codestreamOnly = codestream))
                assertContentEquals(bitmap.argb, ImageKodec.decode(bytes).argb, "$name codestream=$codestream")
            }
        }
        // A big flat picture codes to a few hundred bytes, which the file pads to the size the decoder's
        // expansion guard expects of it.
        val big = KiteBitmap(2048, 1024, IntArray(2048 * 1024) { 0xFFFFFFFF.toInt() })
        val bytes = ImageKodec.encodeJp2(big, Jp2EncodeOptions(lossless = true, levels = 3))
        assertContentEquals(big.argb, ImageKodec.decode(bytes).argb)
    }

    @Test
    fun losslessHoldsEveryDepthOfAWideBitmap() {
        for (bits in listOf(1, 4, 7, 8, 9, 12, 15, 16)) for (channels in 1..4) {
            val w = 11 + bits
            val h = 5 + channels
            val max = (1 shl bits) - 1
            val samples = ShortArray(w * h * channels) { i ->
                val v = ((i * 2654435761L) ushr 7).toInt() and max
                // As decode16 widens a sample of this depth.
                val wide = when {
                    bits == 16 -> v
                    bits >= 8 -> (v shl (16 - bits)) or (v shr (2 * bits - 16))
                    else -> (v * 255 / max) * 257
                }
                wide.toShort()
            }
            val bitmap = KiteBitmap16(w, h, channels, samples)
            val bytes = ImageKodec.encodeJp2Wide(bitmap, Jp2EncodeOptions(lossless = true, bitsPerSample = bits))
            assertEquals(bits, ImageKodec.probe(bytes).bitDepth, "$bits bits")
            val back = ImageKodec.decode16(bytes)
            assertEquals(channels, back.channels)
            assertContentEquals(samples, back.samples, "$bits bits, $channels channels")
        }
    }

    @Test
    fun lossyDecodesCloseToTheSource() {
        for ((w, h) in sizes) for (alpha in listOf(false, true)) {
            val bitmap = picture(w, h, alpha = alpha, seed = w + h)
            val bytes = ImageKodec.encodeJp2(bitmap, 90)
            val back = ImageKodec.decode(bytes)
            assertEquals(w, back.width)
            assertEquals(h, back.height)
            assertTrue(psnr(bitmap, back) >= 33.0, "${w}x$h alpha=$alpha: ${psnr(bitmap, back)} dB")
        }
        // More quality is never a smaller file, and the ratios of the layers are kept.
        val bitmap = picture(96, 64, seed = 5)
        val low = ImageKodec.encodeJp2(bitmap, 20)
        val high = ImageKodec.encodeJp2(bitmap, 90)
        assertTrue(low.size < high.size, "${low.size} bytes at 20, ${high.size} at 90")
        val raw = 96 * 64 * 3
        for (ratio in listOf(8.0, 16.0, 40.0)) {
            val bytes = ImageKodec.encodeJp2(bitmap, Jp2EncodeOptions(ratios = listOf(ratio)))
            assertTrue(bytes.size <= raw / ratio, "ratio $ratio gave ${bytes.size} bytes")
        }
    }

    @Test
    fun everyOptionDecodes() {
        val bitmap = picture(45, 37, alpha = true, seed = 3)
        val options = listOf(
            Jp2EncodeOptions(layers = 4, progression = Jp2Progression.RLCP),
            Jp2EncodeOptions(ratios = listOf(30.0, 10.0), progression = Jp2Progression.RPCL, precinctSize = 16),
            Jp2EncodeOptions(lossless = true, progression = Jp2Progression.PCRL, precinctSize = 8, layers = 2),
            Jp2EncodeOptions(lossless = true, progression = Jp2Progression.CPRL, tileWidth = 16, tileHeight = 20),
            Jp2EncodeOptions(lossless = true, bypass = true, resetContexts = true, terminateEachPass = true, verticalCausal = true, segmentationSymbols = true, codeBlockWidth = 8, codeBlockHeight = 16),
            Jp2EncodeOptions(lossless = true, sopMarkers = true, ephMarkers = true, levels = 0),
            Jp2EncodeOptions(lossless = true, effort = 3),
            Jp2EncodeOptions(lossless = true, effort = 1, ratios = listOf(20.0)),
        )
        for (o in options) {
            val bytes = ImageKodec.encodeJp2(bitmap, o)
            val back = ImageKodec.decode(bytes)
            if (o.lossless) assertContentEquals(bitmap.argb, back.argb, "$o") else assertTrue(psnr(bitmap, back) > 20.0, "$o")
            for (r in listOf(2, 4)) {
                val reduced = ImageKodec.decodeReduced(bytes, r)
                assertEquals((45 + r - 1) / r, reduced.width, "$o reduced by $r")
            }
        }
    }

    @Test
    fun metadataIsWrittenAndReadBack() {
        val exif = byteArrayOf(0x4D, 0x4D, 0, 42, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0)
        val xmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>"
        val metadata = ImageMetadata(exif = exif, xmp = xmp)
        val bytes = ImageKodec.encodeJp2(picture(8, 8), Jp2EncodeOptions(), metadata)
        val back = ImageKodec.readMetadata(bytes)
        assertContentEquals(exif, back.exif)
        assertEquals(xmp, back.xmp)
        // A bare codestream has nowhere to put it.
        assertTrue(ImageKodec.readMetadata(ImageKodec.encodeJp2(picture(8, 8), Jp2EncodeOptions(codestreamOnly = true), metadata)).isEmpty)
    }

    @Test
    fun optionsOutOfRangeAreRefused() {
        assertFailsWith<IllegalArgumentException> { Jp2EncodeOptions(quality = 101) }
        assertFailsWith<IllegalArgumentException> { Jp2EncodeOptions(quality = -1) }
        assertFailsWith<IllegalArgumentException> { Jp2EncodeOptions(layers = 0) }
        assertFailsWith<IllegalArgumentException> { Jp2EncodeOptions(ratios = listOf(10.0, 20.0)) }
        assertFailsWith<IllegalArgumentException> { Jp2EncodeOptions(ratios = listOf(0.5)) }
        assertFailsWith<IllegalArgumentException> { Jp2EncodeOptions(ratios = listOf(20.0, 10.0), layers = 3) }
        assertFailsWith<IllegalArgumentException> { Jp2EncodeOptions(levels = 33) }
        assertFailsWith<IllegalArgumentException> { Jp2EncodeOptions(codeBlockWidth = 48) }
        assertFailsWith<IllegalArgumentException> { Jp2EncodeOptions(codeBlockWidth = 1024, codeBlockHeight = 8) }
        assertFailsWith<IllegalArgumentException> { Jp2EncodeOptions(precinctSize = 3) }
        assertFailsWith<IllegalArgumentException> { Jp2EncodeOptions(effort = 4) }
        assertFailsWith<IllegalArgumentException> { Jp2EncodeOptions(bitsPerSample = 17) }
        assertFailsWith<IllegalArgumentException> { Jp2EncodeOptions(tileWidth = -1) }
        assertFailsWith<IllegalArgumentException> { ImageKodec.encodeJp2(picture(4, 4), 200) }
        assertFailsWith<IllegalArgumentException> { ImageKodec.encodeJp2(picture(4, 4), Jp2EncodeOptions(bitsPerSample = 12)) }
        assertFailsWith<IllegalArgumentException> { ImageKodec.encodeJp2(picture(300, 300), Jp2EncodeOptions(tileWidth = 1, tileHeight = 1)) }
    }

    private class Stop : RuntimeException("stop")

    @Test
    fun aCancellationCheckStopsTheEncode() {
        val bitmap = picture(64, 64, seed = 9)
        var calls = 0
        val thrown = assertFailsWith<Stop> {
            ImageKodec.encodeJp2(bitmap, Jp2EncodeOptions(), cancellationCheck = { if (++calls == 5) throw Stop() })
        }
        assertEquals("stop", thrown.message)
        assertEquals(5, calls)
        var wideCalls = 0
        assertFailsWith<Stop> {
            ImageKodec.encodeJp2Wide(KiteBitmap16(8, 8, 1, ShortArray(64)), Jp2EncodeOptions(), cancellationCheck = { if (++wideCalls == 1) throw Stop() })
        }
        // A check that never throws changes nothing.
        assertContentEquals(ImageKodec.encodeJp2(bitmap, Jp2EncodeOptions()), ImageKodec.encodeJp2(bitmap, Jp2EncodeOptions(), cancellationCheck = {}))
    }

    /** FNV-1a over [bytes], in Int arithmetic so that it is the same on every target. */
    private fun fnv(bytes: ByteArray): Int {
        var h = 0x811C9DC5.toInt()
        for (b in bytes) h = (h xor (b.toInt() and 0xFF)) * 16777619
        return h
    }

    @Test
    fun theSamePictureGivesTheSameBytesOnEveryTarget() {
        val bitmap = picture(57, 43, alpha = true, seed = 11)
        val wide = KiteBitmap16(29, 23, 3, ShortArray(29 * 23 * 3) { (it * 40503).toShort() })
        val files = listOf(
            "lossy" to ImageKodec.encodeJp2(bitmap, 75),
            "layers" to ImageKodec.encodeJp2(bitmap, Jp2EncodeOptions(ratios = listOf(40.0, 12.0), progression = Jp2Progression.RPCL, precinctSize = 16)),
            "lossless" to ImageKodec.encodeJp2(bitmap, Jp2EncodeOptions(lossless = true, layers = 2)),
            "styles" to ImageKodec.encodeJp2(bitmap, Jp2EncodeOptions(bypass = true, terminateEachPass = true, verticalCausal = true, codeBlockWidth = 16, codeBlockHeight = 16)),
            "wide lossy" to ImageKodec.encodeJp2Wide(wide, Jp2EncodeOptions(bitsPerSample = 12)),
        )
        val hashes = files.map { (name, bytes) -> "$name ${bytes.size} ${fnv(bytes).toUInt().toString(16)}" }
        assertEquals(EXPECTED, hashes, hashes.joinToString("\n"))
    }

    private companion object {
        /** The output of the JVM, which every other target must give byte for byte. */
        val EXPECTED = listOf(
            "lossy 3366 9400cc5d",
            "layers 810 aa3f0ac9",
            "lossless 5133 db82e05e",
            "styles 4336 911d068f",
            "wide lossy 1741 3bdc0425",
        )
    }
}
