package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.HtBlockDecoder
import io.github.yuroyami.imagekodec.codec.HtBlockEncoder
import io.github.yuroyami.imagekodec.codec.JpxDecoder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * HTJ2K (#187) on every target: the committed files decode to their sources or to committed hashes,
 * the HT encoder's lossless files return every picture exactly and its lossy ones come close,
 * fixed pictures give the same bytes everywhere, a cancellation check stops the encode, and damaged
 * HT blocks throw [ImageDecodeException], never another exception, a trap or an endless loop.
 */
class Jp2HtTest {

    /** The picture each fixture of [Jp2HtFixtures] was encoded from: gradients, edges, noise and alpha with holes. */
    fun picture(w: Int, h: Int, channels: Int, bits: Int, seed: Int): KiteBitmap16 {
        val max = (1 shl bits) - 1
        val random = kotlin.random.Random(seed)
        val samples = ShortArray(w * h * channels)
        for (y in 0 until h) for (x in 0 until w) for (c in 0 until channels) {
            val alpha = channels % 2 == 0 && c == channels - 1
            val v = if (alpha) {
                if ((x / 3 + y / 2) % 3 == 0) 0.0 else (x + y).toDouble() / (w + h)
            } else {
                val gradient = (x * (c + 1) + y * (3 - c)).toDouble() / (w * 2 + h * 3)
                val edge = if ((x / 5 + y / 4 + c) % 2 == 0) 0.25 else 0.0
                (gradient * 0.7 + edge + random.nextInt(-3, 4) / 64.0).coerceIn(0.0, 1.0)
            }
            val q = (v * max + 0.5).toInt()
            samples[(y * w + x) * channels + c] = (if (bits == 16) q else (q shl (16 - bits)) or (q shr (2 * bits - 16))).toShort()
        }
        return KiteBitmap16(w, h, channels, samples)
    }

    /** FNV-1a over [bytes], in Int arithmetic so that it is the same on every target. */
    private fun fnv(bytes: ByteArray): Int {
        var h = 0x811C9DC5.toInt()
        for (b in bytes) h = (h xor (b.toInt() and 0xFF)) * 16777619
        return h
    }

    private fun hash(argb: IntArray): String = fnv(ByteArray(argb.size * 4) { i -> (argb[i / 4] shr (24 - 8 * (i % 4))).toByte() }).toUInt().toString(16)

    // ---- the committed files --------------------------------------------------------------------

    @Test
    fun theLosslessFixturesDecodeToTheirSources() {
        val rgba = ImageKodec.decode16(Jp2HtFixtures.rgbaLossless)
        assertContentEquals(picture(24, 16, 4, 8, 1).samples, rgba.samples)
        val gray = ImageKodec.decode16(Jp2HtFixtures.gray12)
        assertEquals(1, gray.channels)
        assertContentEquals(picture(30, 22, 1, 12, 3).samples, gray.samples)
    }

    @Test
    fun theLossyFixtureDecodesToItsCommittedHash() {
        val bitmap = ImageKodec.decode(Jp2HtFixtures.rgbLossy)
        assertEquals(40, bitmap.width)
        assertEquals(28, bitmap.height)
        assertEquals(LOSSY_FIXTURE_HASH, hash(bitmap.argb))
        // Every reduction of the files decodes.
        for (file in listOf(Jp2HtFixtures.rgbaLossless, Jp2HtFixtures.rgbLossy, Jp2HtFixtures.gray12)) {
            for (r in listOf(2, 4)) {
                val reduced = ImageKodec.decodeReduced(file, r)
                assertTrue(reduced.width >= 1 && reduced.height >= 1)
            }
        }
    }

    @Test
    fun aJphFileIsDetectedAndProbedAsJpeg2000() {
        val file = Jp2HtFixtures.rgbaLossless
        assertEquals("jph ", file.copyOfRange(20, 24).decodeToString())
        assertEquals(ImageFormat.JP2, ImageKodec.detect(file))
        val info = ImageKodec.probe(file)
        assertEquals(ImageFormat.JP2, info.format)
        assertTrue(info.isDecodable, "${info.unsupportedReason}")
        assertTrue(info.hasAlpha)
        assertEquals(12, ImageKodec.probe(Jp2HtFixtures.gray12).bitDepth)
        assertTrue(ImageKodec.probe(Jp2HtFixtures.rgbLossy).isDecodable)
    }

    // ---- the encoder ----------------------------------------------------------------------------

    private val ht = Jp2EncodeOptions(lossless = true, highThroughput = true)

    private fun argbPicture(w: Int, h: Int, gray: Boolean, alpha: Boolean, seed: Int): KiteBitmap =
        picture(w, h, (if (gray) 1 else 3) + (if (alpha) 1 else 0), 8, seed).toBitmap()

    @Test
    fun losslessHtReturnsEveryPictureExactly() {
        for ((w, h) in listOf(1 to 1, 1 to 9, 9 to 1, 2 to 2, 31 to 17, 17 to 31, 64 to 3, 65 to 33)) {
            for (gray in listOf(false, true)) for (alpha in listOf(false, true)) {
                val bitmap = argbPicture(w, h, gray, alpha, seed = w * 31 + h)
                val bytes = ImageKodec.encodeJp2(bitmap, ht)
                assertContentEquals(bitmap.argb, ImageKodec.decode(bytes).argb, "${w}x$h gray=$gray alpha=$alpha")
            }
        }
    }

    @Test
    fun losslessHtHoldsFlatTransparentAndManyColouredPictures() {
        val transparent = KiteBitmap(13, 7, IntArray(91))
        val flat = KiteBitmap(20, 20, IntArray(400) { 0xFF336699.toInt() })
        // 4096 colours, more than a palette holds, and 3 of them, fewer.
        val many = KiteBitmap(64, 64, IntArray(4096) { 0xFF000000.toInt() or ((it * 0x1003) and 0xFFFFFF) })
        val few = KiteBitmap(30, 10, IntArray(300) { listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())[it % 3] })
        for (bitmap in listOf(transparent, flat, many, few)) {
            for (options in listOf(ht, Jp2EncodeOptions(lossless = true, levels = 0, codeBlockWidth = 8, codeBlockHeight = 4, verticalCausal = true, highThroughput = true))) {
                assertContentEquals(bitmap.argb, ImageKodec.decode(ImageKodec.encodeJp2(bitmap, options)).argb)
            }
        }
    }

    @Test
    fun losslessHtHoldsEveryDepthOfAWideBitmap() {
        for (bits in listOf(1, 5, 10, 12, 16)) for (channels in 1..4) {
            val picture = picture(19, 13, channels, bits, seed = bits + channels)
            val bytes = ImageKodec.encodeJp2Wide(picture, Jp2EncodeOptions(lossless = true, bitsPerSample = bits, levels = 2, highThroughput = true))
            val back = ImageKodec.decode16(bytes)
            assertEquals(channels, back.channels)
            if (bits >= 8) assertContentEquals(picture.samples, back.samples, "$bits bits, $channels channels")
        }
    }

    private fun psnr(a: KiteBitmap, b: KiteBitmap): Double {
        var se = 0L
        for (i in a.argb.indices) for (shift in intArrayOf(0, 8, 16, 24)) {
            val d = ((a.argb[i] ushr shift) and 0xFF) - ((b.argb[i] ushr shift) and 0xFF)
            se += d * d
        }
        if (se == 0L) return 99.0
        var ratio = 255.0 * 255.0 * a.argb.size * 4 / se
        var db = 0.0
        while (ratio >= 2.0) { ratio /= 2.0; db += 3.0103 }
        return db
    }

    @Test
    fun lossyHtDecodesCloseToTheSource() {
        val bitmap = argbPicture(65, 33, gray = false, alpha = true, seed = 4)
        var last = 0.0
        for (quality in listOf(20, 60, 90)) {
            val bytes = ImageKodec.encodeJp2(bitmap, Jp2EncodeOptions(quality = quality, highThroughput = true))
            val db = psnr(bitmap, ImageKodec.decode(bytes))
            assertTrue(db > 20.0 && db >= last - 0.5, "quality $quality: $db dB after $last")
            last = db
        }
        val small = ImageKodec.encodeJp2(bitmap, Jp2EncodeOptions(ratios = listOf(30.0), highThroughput = true))
        assertTrue(small.size <= 65 * 33 * 4 / 30 + 64, "ratio 30 gave ${small.size} bytes")
    }

    @Test
    fun htOptionsOutOfRangeAreRefused() {
        assertFailsWith<IllegalArgumentException> { Jp2EncodeOptions(highThroughput = true, layers = 2) }
        assertFailsWith<IllegalArgumentException> { Jp2EncodeOptions(highThroughput = true, ratios = listOf(20.0, 10.0)) }
        assertFailsWith<IllegalArgumentException> { Jp2EncodeOptions(highThroughput = true, lossless = true, ratios = listOf(20.0)) }
        assertFailsWith<IllegalArgumentException> { Jp2EncodeOptions(highThroughput = true, bypass = true) }
        assertFailsWith<IllegalArgumentException> { Jp2EncodeOptions(highThroughput = true, resetContexts = true) }
        assertFailsWith<IllegalArgumentException> { Jp2EncodeOptions(highThroughput = true, terminateEachPass = true) }
        assertFailsWith<IllegalArgumentException> { Jp2EncodeOptions(highThroughput = true, segmentationSymbols = true) }
        Jp2EncodeOptions(highThroughput = true, verticalCausal = true, ratios = listOf(20.0))
    }

    private class Stop : RuntimeException("stop")

    @Test
    fun aCancellationCheckStopsTheHtEncode() {
        val bitmap = argbPicture(64, 64, gray = false, alpha = false, seed = 9)
        for (lossless in listOf(false, true)) {
            var calls = 0
            assertFailsWith<Stop> {
                ImageKodec.encodeJp2(bitmap, Jp2EncodeOptions(lossless = lossless, highThroughput = true), cancellationCheck = { if (++calls == 5) throw Stop() })
            }
            assertEquals(5, calls)
        }
        val options = Jp2EncodeOptions(highThroughput = true)
        assertContentEquals(ImageKodec.encodeJp2(bitmap, options), ImageKodec.encodeJp2(bitmap, options, cancellationCheck = {}))
    }

    @Test
    fun theSameHtPictureGivesTheSameBytesOnEveryTarget() {
        val bitmap = argbPicture(57, 43, gray = false, alpha = true, seed = 11)
        val files = listOf(
            "lossy" to ImageKodec.encodeJp2(bitmap, Jp2EncodeOptions(highThroughput = true)),
            "ratio" to ImageKodec.encodeJp2(bitmap, Jp2EncodeOptions(ratios = listOf(25.0), progression = Jp2Progression.RPCL, precinctSize = 16, highThroughput = true)),
            "lossless" to ImageKodec.encodeJp2(bitmap, Jp2EncodeOptions(lossless = true, highThroughput = true)),
            "causal" to ImageKodec.encodeJp2(bitmap, Jp2EncodeOptions(verticalCausal = true, codeBlockWidth = 16, codeBlockHeight = 16, quality = 90, highThroughput = true)),
            "wide" to ImageKodec.encodeJp2Wide(picture(29, 23, 3, 12, 5), Jp2EncodeOptions(bitsPerSample = 12, tileWidth = 16, tileHeight = 16, highThroughput = true)),
        )
        val hashes = files.map { (name, bytes) -> "$name ${bytes.size} ${fnv(bytes).toUInt().toString(16)}" }
        assertEquals(EXPECTED, hashes, hashes.joinToString("\n"))
    }

    // ---- damaged blocks -------------------------------------------------------------------------

    /** Runs [block], which may succeed or throw [ImageDecodeException] and nothing else. */
    private fun mustFailCleanly(label: String, block: () -> Unit) {
        try {
            block()
        } catch (_: ImageDecodeException) {
        } catch (e: Throwable) {
            fail("$label: ${e::class.simpleName}: ${e.message}")
        }
    }

    @Test
    fun damagedHtBlocksThrowDecodeExceptions() {
        // Random cleanup and refinement segments of every length up to a few hundred bytes, on blocks of every shape.
        var state = 12345
        fun next(): Int { state = state * 1103515245 + 12345; return (state ushr 8) and 0xFFFF }
        val shapes = listOf(1 to 1, 2 to 2, 3 to 5, 64 to 64, 1024 to 4, 4 to 1024, 17 to 33)
        for (k in 0 until 400) {
            val (w, h) = shapes[k % shapes.size]
            val cleanup = ByteArray(next() % 300) { next().toByte() }
            val refinement = ByteArray(next() % 40) { next().toByte() }
            val passes = 1 + k % 3
            val p = (k / 3) % 12
            mustFailCleanly("random block $k, ${w}x$h, ${cleanup.size} bytes, $passes passes at plane $p") {
                HtBlockDecoder.decode(cleanup, refinement, passes, w, h, p, k % 2 == 0, IntArray(w * h), IntArray(w * h), IntArray(w * h))
            }
        }
        // A cleanup segment cut short, at every length, from a real block.
        val values = IntArray(64 * 64) { ((it * 7919) % 255) - 127 }
        val set = HtBlockEncoder(stripeCausal = false).encode(values, 0, 64, 64, 64, 1, 3)!!
        for (n in 0 until set.cleanup.size) {
            mustFailCleanly("cleanup cut to $n bytes") {
                HtBlockDecoder.decode(set.cleanup.copyOf(n), set.sigProp + set.magRef, 3, 64, 64, 1, false, IntArray(4096), IntArray(4096), IntArray(4096))
            }
        }
        // Scup larger than the segment, and smaller than its two bytes.
        val bad = set.cleanup.copyOf()
        bad[bad.size - 1] = 0xFE.toByte()
        assertFailsWith<ImageDecodeException> { HtBlockDecoder.decode(bad, null, 1, 64, 64, 1, false, IntArray(4096), IntArray(4096), IntArray(4096)) }
        assertFailsWith<ImageDecodeException> { HtBlockDecoder.decode(byteArrayOf(0x01, 0x00), null, 1, 4, 4, 0, false, IntArray(16), IntArray(16), IntArray(16)) }
        assertFailsWith<ImageDecodeException> { HtBlockDecoder.decode(byteArrayOf(0x05), null, 1, 4, 4, 0, false, IntArray(16), IntArray(16), IntArray(16)) }
    }

    @Test
    fun damagedHtFilesThrowDecodeExceptions() {
        for ((name, file) in listOf("rgba" to Jp2HtFixtures.rgbaLossless, "lossy" to Jp2HtFixtures.rgbLossy, "gray12" to Jp2HtFixtures.gray12)) {
            // Cut at every 7th byte, and every 5th byte past the headers set to 0x00 and to 0xFF.
            for (n in 0 until file.size step 7) mustFailCleanly("$name cut to $n") { JpxDecoder.decodeChecked(file.copyOf(n), 1) }
            for (i in 150 until file.size step 5) for (v in intArrayOf(0x00, 0xFF)) {
                val copy = file.copyOf()
                copy[i] = v.toByte()
                mustFailCleanly("$name byte $i set to $v") { JpxDecoder.decodeChecked(copy, 1) }
            }
        }
    }

    // ---- what stays refused ---------------------------------------------------------------------

    /** The index of the code-block style byte of the first COD segment of [file]. */
    private fun styleByte(file: ByteArray): Int {
        for (i in 0 until file.size - 1) {
            if ((file[i].toInt() and 0xFF) == 0xFF && (file[i + 1].toInt() and 0xFF) == 0x52) return i + 12
        }
        fail("no COD segment")
    }

    @Test
    fun theMixedStyleIsRefusedByDecodeAndProbe() {
        val file = Jp2HtFixtures.rgbLossy.copyOf()
        val at = styleByte(file)
        assertEquals(0x40, file[at].toInt() and 0xFF)
        file[at] = 0xC0.toByte()
        val e = assertFailsWith<UnsupportedImageException> { JpxDecoder.decodeChecked(file, 1) }
        assertTrue(e.message!!.contains("MIXED"), e.message)
        val info = ImageKodec.probe(file)
        assertTrue(!info.isDecodable && info.unsupportedReason!!.contains("MIXED"), "${info.unsupportedReason}")
    }

    /** A gray 8-bit codestream of [w] by [h], no wavelet levels, one HT code-block with [passes] passes in segments of [segments] passes and [lengths] bytes. */
    private fun oneBlock(w: Int, h: Int, zeroPlanes: Int, passes: Int, segments: List<Int>, lengths: List<Int>, data: ByteArray): ByteArray {
        val header = ArrayList<Int>()
        var acc = 0
        var count = 0
        var room = 8
        fun bit(b: Int) {
            acc = (acc shl 1) or b
            if (++count == room) { header += acc; room = if (acc == 0xFF) 7 else 8; acc = 0; count = 0 }
        }
        fun bits(v: Int, n: Int) { for (i in n - 1 downTo 0) bit((v ushr i) and 1) }
        bit(1); bit(1)                       // a packet with data; the block is in layer 0
        repeat(zeroPlanes) { bit(0) }; bit(1)
        when {
            passes == 1 -> bit(0)
            passes == 2 -> bits(2, 2)
            else -> { bits(3, 2); bits(passes - 3, 2) }
        }
        bits(0b1110, 4)                     // Lblock 3 + 3
        for ((k, n) in segments.withIndex()) bits(lengths[k], 6 + (31 - n.countLeadingZeroBits()))
        if (count > 0) header += acc shl (room - count)
        val body = header.map { it.toByte() }.toByteArray() + data
        fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
        fun u32(v: Int) = u16(v ushr 16) + u16(v)
        val cbe = maxOf(2, 32 - (maxOf(w, h) - 1).countLeadingZeroBits()).coerceAtMost(6)
        return u16(0xFF4F) +
            u16(0xFF51) + u16(41) + u16(0x4000) + u32(w) + u32(h) + u32(0) + u32(0) + u32(w) + u32(h) + u32(0) + u32(0) + u16(1) + byteArrayOf(7, 1, 1) +
            u16(0xFF50) + u16(8) + u32(0x00020000) + u16(0) +
            u16(0xFF52) + u16(12) + byteArrayOf(0, 0) + u16(1) + byteArrayOf(0, 0, (cbe - 2).toByte(), (cbe - 2).toByte(), 0x40, 1) +
            u16(0xFF5C) + u16(4) + byteArrayOf(0x20, 0x40) +
            u16(0xFF90) + u16(10) + u16(0) + u32(14 + body.size) + byteArrayOf(0, 1) + u16(0xFF93) + body + u16(0xFFD9)
    }

    @Test
    fun aHandBuiltHtBlockDecodesAndPlaceholderPassesAreRefused() {
        val values = IntArray(16) { (it * 37 % 31) - 15 }
        val set = HtBlockEncoder(stripeCausal = false).encode(values, 0, 4, 4, 4, 0, 1)!!
        // Mb is 1 guard bit + 8 - 1 = 8, so 7 zero planes put the cleanup pass on plane 0.
        val file = oneBlock(4, 4, 7, 1, listOf(1), listOf(set.cleanup.size), set.cleanup)
        val gray = ImageKodec.decode(file)
        for (i in 0 until 16) assertEquals(values[i] + 128, gray.argb[i] and 0xFF, "sample $i")
        // Four passes: a cleanup pass, a refinement segment, and a second HT set's cleanup.
        val four = oneBlock(4, 4, 7, 4, listOf(1, 2, 1), listOf(set.cleanup.size, 0, 0), set.cleanup)
        val e = assertFailsWith<UnsupportedImageException> { JpxDecoder.decodeChecked(four, 1) }
        assertTrue(e.message!!.contains("more than one HT set"), e.message)
        // A length past the end of the data reads what there is.
        mustFailCleanly("a length past the data") { JpxDecoder.decodeChecked(oneBlock(4, 4, 7, 1, listOf(1), listOf(60), set.cleanup), 1) }
    }

    private companion object {
        /** ImageKodec's decode of [Jp2HtFixtures.rgbLossy] on the JVM, within one level of `opj_decompress`'s. */
        const val LOSSY_FIXTURE_HASH = "1ff4875"

        /** The output of the JVM, which every other target must give byte for byte. */
        val EXPECTED = listOf(
            "lossy 5875 536edc4a",
            "ratio 392 5e884120",
            "lossless 9843 e69875c4",
            "causal 7077 15671a20",
            "wide 2187 f28df08f",
        )
    }
}
