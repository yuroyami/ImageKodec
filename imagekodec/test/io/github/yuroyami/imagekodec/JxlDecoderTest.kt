package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.jxl.JxlBitReader
import io.github.yuroyami.imagekodec.codec.jxl.JxlBlendingInfo
import io.github.yuroyami.imagekodec.codec.jxl.JxlDecoder
import io.github.yuroyami.imagekodec.codec.jxl.JxlFrameHeader
import io.github.yuroyami.imagekodec.codec.jxl.JxlToc
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JPEG XL through the facade (#42): the fixtures as libjxl's djxl reads them, and files built
 * here around their codestreams for what no encoder writes on request: a codestream split
 * over several boxes, a box with a long or an open size, a preview frame, and headers that
 * ask for more than the file holds.
 */
class JxlDecoderTest {

    private fun fnv(b: KiteBitmap): Long {
        var h = -0x340d631b7bdddcdbL
        for (px in b.argb) for (k in 0 until 4) h = (h xor ((px ushr (24 - 8 * k)) and 255).toLong()) * 0x100000001b3L
        return h
    }

    /** The hash of the samples as 16-bit numbers, high byte first. */
    private fun fnv(b: KiteBitmap16): Long {
        var h = -0x340d631b7bdddcdbL
        for (s in b.samples) {
            h = (h xor ((s.toInt() ushr 8) and 255).toLong()) * 0x100000001b3L
            h = (h xor (s.toInt() and 255).toLong()) * 0x100000001b3L
        }
        return h
    }

    private fun fnv(bytes: ByteArray): Long {
        var h = -0x340d631b7bdddcdbL
        for (b in bytes) h = (h xor (b.toLong() and 255)) * 0x100000001b3L
        return h
    }

    /**
     * Holds [bitmap] to djxl's pixels of a lossy file or of a blended frame. Two decoders
     * round such a sample at different points, so a sample may differ by one step, and only
     * a few of them may.
     */
    private fun assertCloseToDjxl(name: String, bitmap: KiteBitmap, want: ByteArray = JxlFixtures.pixels.getValue(name)) {
        assertEquals(want.size, bitmap.argb.size * 4, "$name size")
        var worst = 0
        var differing = 0
        for (i in bitmap.argb.indices) for (k in 0 until 4) {
            val d = abs(((bitmap.argb[i] ushr (24 - 8 * k)) and 255) - (want[i * 4 + k].toInt() and 255))
            if (d > worst) worst = d
            if (d != 0) differing++
        }
        assertTrue(worst <= 1, "$name: a sample is $worst away from djxl's")
        assertTrue(differing * 50 <= want.size, "$name: $differing of ${want.size} samples differ from djxl's")
    }

    @Test
    fun theFixturesReadAsDjxlReadsThem() {
        class Expect(val width: Int, val height: Int, val depth: Int, val alpha: Boolean, val orientation: Orientation = Orientation.Normal, val frames: Int = 1)
        val expected = mapOf(
            "lossless" to Expect(24, 16, 8, false),
            "alpha" to Expect(24, 16, 8, true),
            "deep" to Expect(20, 12, 16, true),
            "lossy" to Expect(40, 24, 8, false),
            "lossy16" to Expect(24, 16, 16, true),
            "modular" to Expect(24, 16, 8, true),
            "jpeg" to Expect(24, 16, 8, false),
            "features" to Expect(32, 24, 8, false),
            "oriented" to Expect(20, 12, 8, false, Orientation.Transpose),
            "hdr" to Expect(16, 8, 10, false),
            "upsampled" to Expect(24, 16, 8, true),
            "icc" to Expect(24, 16, 8, false),
            "lfframe" to Expect(24, 16, 8, false),
            "patches" to Expect(32, 24, 8, false),
            "palette" to Expect(20, 12, 8, false),
            "float" to Expect(16, 8, 32, false),
            "half" to Expect(16, 8, 16, false),
            "cbycr" to Expect(16, 8, 8, false),
            "hidden" to Expect(16, 8, 8, true),
            "animation" to Expect(16, 12, 8, true, frames = 3),
        )
        // A file deeper than 8 bits is held to djxl through its 16-bit samples.
        val deep = setOf("deep", "hdr", "float", "half")
        assertEquals(expected.keys, JxlFixtures.all.map { it.first }.toSet())
        for ((name, data) in JxlFixtures.all) {
            val e = expected.getValue(name)
            assertEquals(ImageFormat.JXL, ImageFormat.sniff(data), name)
            val info = ImageKodec.probe(data)
            assertEquals(ImageFormat.JXL, info.format, name)
            assertTrue(info.isDecodable, "$name: ${info.unsupportedReason}")
            assertEquals(e.width to e.height, info.width to info.height, "$name size")
            assertEquals(e.depth, info.bitDepth, "$name depth")
            assertEquals(e.alpha, info.hasAlpha, "$name alpha")
            assertEquals(e.orientation, info.orientation, "$name orientation")
            assertEquals(e.frames, info.frameCount, "$name frames")
            // djxl turns the picture the way the file asks.
            val shown = ImageKodec.decode(data, applyOrientation = true)
            assertEquals(info.displayWidth to info.displayHeight, shown.width to shown.height, "$name shown size")
            val shown16 = ImageKodec.decode16(data, applyOrientation = true)
            when (name) {
                "animation" -> assertCloseToDjxl(name, shown, JxlFixtures.animationFrames[0].second)
                in deep -> assertEquals(JxlFixtures.hashes.getValue(name), fnv(shown16), "$name differs from djxl")
                in JxlFixtures.hashes -> assertEquals(JxlFixtures.hashes.getValue(name), fnv(shown), "$name differs from djxl")
                else -> assertCloseToDjxl(name, shown)
            }
            assertContentEquals(shown.argb, shown16.toBitmap().argb, "$name: decode16 does not narrow to decode")
            val stored = ImageKodec.decode(data)
            assertEquals(e.width to e.height, stored.width to stored.height, "$name stored size")
        }
    }

    @Test
    fun hdrConversionKeepsPrecisionUntilNarrowing() {
        val data = JxlFixtures.hdr
        val profile = ImageKodec.probe(data).colorProfile!!
        val source = ImageKodec.decode16(data)
        val expected = ImageKodec.convertToSrgb(source, profile)
        val narrowedFirst = ImageKodec.convertToSrgb(source.toBitmap(), profile)
        assertFalse(expected.toBitmap().argb.contentEquals(narrowedFirst.argb))
        assertContentEquals(expected.samples, ImageKodec.decode16(data, colorTarget = ColorTarget.Srgb()).samples)
        assertContentEquals(expected.toBitmap().argb, ImageKodec.decode(data, colorTarget = ColorTarget.Srgb()).argb)
    }

    @Test
    fun eachFixtureUsesTheCodingToolsItIsHereFor() {
        fun frames(data: ByteArray) = JxlDecoder.frameHeaders(data)
        fun only(data: ByteArray) = frames(data).single()

        only(JxlFixtures.lossless).let { assertTrue(it.modular && it.colorTransform == JxlFrameHeader.NONE) }
        only(JxlFixtures.lossy).let {
            assertTrue(!it.modular && it.colorTransform == JxlFrameHeader.XYB)
            assertTrue(it.loopFilter.gab && it.loopFilter.epfIters > 0)
        }
        only(JxlFixtures.lossy16).let { assertTrue(!it.modular && it.numPasses > 1 && it.hasNoise) }
        only(JxlFixtures.modular).let {
            assertTrue(it.modular && it.colorTransform == JxlFrameHeader.XYB)
            assertEquals(2 to listOf(2), it.upsampling to it.ecUpsampling.toList())
        }
        only(JxlFixtures.jpeg).let { assertTrue(!it.modular && it.colorTransform == JxlFrameHeader.YCBCR && !it.is444) }
        only(JxlFixtures.features).let {
            assertTrue(it.hasSplines && it.hasNoise && it.loopFilter.gab)
            assertEquals(2, it.loopFilter.epfIters)
        }
        only(JxlFixtures.upsampled).let { assertEquals(2 to listOf(4), it.upsampling to it.ecUpsampling.toList()) }
        only(JxlFixtures.cbycr).let { assertTrue(it.modular && it.colorTransform == JxlFrameHeader.YCBCR) }
        assertEquals(3, only(JxlFixtures.hidden).ecUpsampling.size)

        val lf = frames(JxlFixtures.lfframe)
        assertEquals(listOf(JxlFrameHeader.LF_FRAME, JxlFrameHeader.REGULAR_FRAME), lf.map { it.type })
        assertTrue(lf[0].dcLevel == 1 && lf[1].usesLfFrame)

        val patches = frames(JxlFixtures.patches)
        assertEquals(listOf(JxlFrameHeader.REFERENCE_ONLY, JxlFrameHeader.REGULAR_FRAME), patches.map { it.type })
        assertTrue(patches[1].hasPatches && !patches[1].modular)

        val animation = frames(JxlFixtures.animation)
        assertEquals(listOf(40L, 120L, 300L), animation.map { it.duration })
        assertEquals(
            listOf(JxlBlendingInfo.REPLACE, JxlBlendingInfo.BLEND, JxlBlendingInfo.ADD),
            animation.map { it.blending.mode },
        )
        assertEquals(listOf(0 to 0, 4 to 3, -3 to -2), animation.map { it.x0 to it.y0 })
    }

    @Test
    fun theFirstBytesOfAFileAreEnoughToProbeIt() {
        // A bare codestream: the image header is its first bytes.
        val bare = JxlFixtures.lossless.copyOf(12)
        assertEquals(ImageFormat.JXL, ImageFormat.sniff(bare))
        assertEquals(24 to 16, ImageKodec.probe(bare).let { it.width to it.height })
        assertFailsWith<ImageDecodeException> { ImageKodec.decode(bare) }
        // A container: the signature box alone names the format, and the codestream box starts a little later.
        assertEquals(ImageFormat.JXL, ImageFormat.sniff(JxlFixtures.alpha.copyOf(12)))
        val boxed = JxlFixtures.alpha.copyOf(64)
        val info = ImageKodec.probe(boxed)
        assertEquals(24 to 16, info.width to info.height)
        assertTrue(info.hasAlpha)
        assertFailsWith<ImageDecodeException> { ImageKodec.decode(boxed) }
        // A profile that the cut leaves unfinished is left out; the rest is still reported.
        val profiled = ImageKodec.probe(JxlFixtures.icc.copyOf(40))
        assertEquals(24 to 16, profiled.width to profiled.height)
        // An animation cut inside its second frame has one frame to count.
        val second = JxlDecoder.open(JxlFixtures.animation).firstFrame + 20
        assertEquals(1, ImageKodec.probe(JxlFixtures.animation.copyOf(second)).frameCount)
        // Two bytes are a signature and no header.
        assertFailsWith<ImageDecodeException> { ImageKodec.probe(JxlFixtures.lossless.copyOf(2)) }
    }

    @Test
    fun aFileCutShortAnywhereFailsWithADecodeError() {
        for ((name, data) in JxlFixtures.all) {
            for (length in 0 until data.size) {
                val cut = data.copyOf(length)
                try {
                    // A still of an animation needs its first frame only, so the whole animation is asked for.
                    if (name == "animation") ImageKodec.decodeAnimation(cut) else ImageKodec.decode(cut)
                    throw AssertionError("$name cut to $length of ${data.size} bytes still decodes")
                } catch (_: ImageDecodeException) {
                }
                try {
                    ImageKodec.probe(cut)
                } catch (_: ImageDecodeException) {
                }
            }
        }
    }

    private fun be32(v: Long) = ByteArray(4) { (v ushr (24 - 8 * it)).toByte() }

    private fun box(type: String, payload: ByteArray): ByteArray =
        be32(8L + payload.size) + type.encodeToByteArray() + payload

    private val signature = hex("0000000c4a584c200d0a870a")
    private val ftyp = box("ftyp", "jxl ".encodeToByteArray() + ByteArray(4) + "jxl ".encodeToByteArray())

    @Test
    fun aCodestreamInBoxesReadsAsTheBareCodestream() {
        val stream = JxlFixtures.lossless
        val want = ImageKodec.decode(stream).argb
        // The high bit of a part's index marks the last part.
        fun part(index: Long, from: Int, to: Int, last: Boolean = false) =
            box("jxlp", be32(if (last) index or 0x80000000L else index) + stream.copyOfRange(from, to))

        val split = signature + ftyp + part(0, 0, 1) + box("free", ByteArray(7)) + part(1, 1, 300) +
            part(2, 300, stream.size, last = true) + box("Exif", ByteArray(12))
        assertEquals(ImageFormat.JXL, ImageFormat.sniff(split))
        assertContentEquals(want, ImageKodec.decode(split).argb)
        assertEquals(24 to 16, ImageKodec.probe(split).let { it.width to it.height })

        // A box of size 0 runs to the end of the file.
        val open = signature + ftyp + be32(0) + "jxlc".encodeToByteArray() + stream
        assertContentEquals(want, ImageKodec.decode(open).argb)
        // A box of size 1 gives its real size in the 8 bytes after its type.
        val long = signature + ftyp + be32(1) + "jxlc".encodeToByteArray() + be32(0) + be32(16L + stream.size) + stream
        assertContentEquals(want, ImageKodec.decode(long).argb)

        assertFailsWith<ImageDecodeException> { ImageKodec.decode(signature + ftyp) }
        assertFailsWith<ImageDecodeException> { ImageKodec.decode(signature + ftyp + box("jxlp", ByteArray(2))) }
        assertFailsWith<ImageDecodeException> {
            ImageKodec.decode(signature + part(0, 0, 300, last = true) + part(1, 300, stream.size))
        }
        assertFailsWith<ImageDecodeException> { ImageKodec.decode(signature + part(0, 0, 300) + box("jxlc", stream)) }
        assertFailsWith<ImageDecodeException> {
            ImageKodec.decode(signature + be32(1) + "jxlc".encodeToByteArray() + be32(0) + be32(9) + stream)
        }
        // Without the signature box the file is not JPEG XL at all.
        assertNull(ImageFormat.sniff(ftyp + box("jxlc", stream)))
    }

    @Test
    fun aFileWhoseMetadataPassesTheFirstBytesIsStillProbed() {
        // libjxl writes the image header in a first 'jxlp' box, then the metadata, then the rest of the codestream.
        val stream = JxlFixtures.lossless
        val header = JxlDecoder.open(stream).firstFrame
        val file = signature + ftyp + box("jxlp", be32(0) + stream.copyOf(header)) + box("Exif", ByteArray(70_000)) +
            box("jxlp", be32(0x80000001L) + stream.copyOfRange(header, stream.size))
        assertContentEquals(ImageKodec.decode(stream).argb, ImageKodec.decode(file).argb)
        // A loader that looks at the first 64 KiB sees the header box and a part of the metadata.
        val info = ImageKodec.probe(file.copyOf(65_536))
        assertEquals(24 to 16, info.width to info.height)
        assertEquals(1, info.frameCount)
        assertTrue(info.isDecodable)
    }

    @Test
    fun aBoxWhoseEndWrapsAroundIsRefused() {
        // The end of this box wraps around to the start of the file, so a walk that followed it would never end.
        val wrap = signature + be32(1) + "free".encodeToByteArray() + be32(0x7FFFFFFFL) + be32(0xFFFFFFF4L)
        assertFailsWith<ImageDecodeException> { ImageKodec.probe(wrap + box("jxlc", JxlFixtures.lossless)) }
        assertFailsWith<ImageDecodeException> { ImageKodec.decode(wrap + box("jxlc", JxlFixtures.lossless)) }
    }

    @Test
    fun anAnimationPlaysAsDjxlComposesIt() {
        val data = JxlFixtures.animation
        val animation = ImageKodec.decodeAnimation(data)
        assertEquals(16 to 12, animation.width to animation.height)
        assertEquals(0L, animation.loopCount)
        assertEquals(0L, ImageKodec.probe(data).loopCount)
        assertEquals(JxlFixtures.animationFrames.map { it.first }, animation.frames.map { it.delayMillis })
        assertEquals(listOf(40, 120, 300), animation.frames.map { it.delayMillis })
        assertEquals(listOf(4, 12, 30), animation.frames.map { it.delayRawCentiseconds })
        for ((i, frame) in animation.frames.withIndex()) {
            assertCloseToDjxl("animation frame $i", frame.bitmap, JxlFixtures.animationFrames[i].second)
        }
        // The second frame blends by its alpha and the third adds, so each differs from the frame before it.
        assertEquals(3, animation.frames.map { fnv(it.bitmap) }.toSet().size)
        // The still is the first frame of the animation.
        assertEquals(fnv(animation.frames[0].bitmap), fnv(ImageKodec.decode(data)))

        var checks = 0
        val two = ImageKodec.decodeAnimation(data, maxFrames = 2) { checks++ }
        assertEquals(animation.frames.take(2).map { fnv(it.bitmap) }, two.frames.map { fnv(it.bitmap) })
        assertEquals(1, checks)
        // Nothing after the frames that were asked for is read.
        val cut = data.copyOf(data.size - 3)
        assertEquals(2, ImageKodec.decodeAnimation(cut, maxFrames = 2).frames.size)
        assertFailsWith<ImageDecodeException> { ImageKodec.decodeAnimation(cut) }

        val still = ImageKodec.decodeAnimation(JxlFixtures.lossless)
        assertEquals(1, still.frames.size)
        assertEquals(0, still.frames[0].delayMillis)
        assertEquals(1L, still.loopCount)
        assertEquals(fnv(ImageKodec.decode(JxlFixtures.lossless)), fnv(still.frames[0].bitmap))
    }

    @Test
    fun theColourEncodingIsReportedAsCodePointsOrAsTheProfile() {
        fun cicp(data: ByteArray) = ImageKodec.probe(data).colorProfile?.cicp?.toList()
        // BT.709 primaries with the sRGB curve, full range.
        assertEquals(listOf(1, 13, 0, 1), cicp(JxlFixtures.lossless))
        assertEquals(listOf(1, 13, 0, 1), cicp(JxlFixtures.deep))
        assertEquals(listOf(1, 13, 0, 1), cicp(JxlFixtures.lossy))
        // Rec. 2100 primaries with the PQ curve.
        assertEquals(listOf(9, 16, 0, 1), cicp(JxlFixtures.hdr))
        assertNull(ImageKodec.probe(JxlFixtures.lossless).colorProfile?.icc)

        val profile = ImageKodec.probe(JxlFixtures.icc).colorProfile!!
        assertNull(profile.cicp)
        assertEquals(448, profile.icc!!.size)
        assertEquals(JxlFixtures.iccHash, fnv(profile.icc!!), "the profile differs from djxl's")
        assertEquals("RGB", profile.iccColorSpace)
        assertEquals("card", profile.iccDescription)
    }

    /** Writes bits the way JPEG XL packs them: the first bit is the lowest of the first byte. */
    private class Bits {
        private val bytes = ArrayList<Byte>()
        private var used = 8

        fun put(count: Int, value: Long): Bits {
            for (i in 0 until count) {
                if (used == 8) {
                    bytes.add(0)
                    used = 0
                }
                if ((value ushr i) and 1L != 0L) bytes[bytes.size - 1] = (bytes.last().toInt() or (1 shl used)).toByte()
                used++
            }
            return this
        }

        /** Fills the last byte with zeros, as ZeroPadToByte reads it. */
        fun pad(): Bits {
            used = 8
            return this
        }

        fun toByteArray(): ByteArray = bytes.toByteArray()
    }

    @Test
    fun aLoopCountIsReported() {
        // cjxl and jxl_from_tree write no loop count, so the fixture's header is rewritten here. The
        // count starts at bit 43: two bits that say how it is stored, which are 0 for "for ever".
        val data = JxlFixtures.animation
        val first = JxlDecoder.open(data).firstFrame
        assertEquals(8, first)
        fun bit(i: Int) = ((data[i ushr 3].toInt() ushr (i and 7)) and 1).toLong()
        assertEquals(0L, bit(43) + bit(44))
        val header = Bits()
        for (i in 0 until 43) header.put(1, bit(i))
        // Stored in 3 bits: 5 plays.
        header.put(2, 1).put(3, 5)
        // The header ends at bit 59, so the 3 bits more still end before the first frame.
        for (i in 45 until 59) header.put(1, bit(i))
        val file = header.toByteArray() + data.copyOfRange(first, data.size)
        assertEquals(data.size, file.size)
        assertEquals(5L, ImageKodec.probe(file).loopCount)
        val animation = ImageKodec.decodeAnimation(file)
        assertEquals(5L, animation.loopCount)
        assertEquals(3, animation.frames.size)
    }

    @Test
    fun aPreviewFrameIsSkipped() {
        val data = JxlFixtures.lossy
        // The fixture's header is all defaults: its size, then two bits that say so.
        assertContentEquals(hex("ff0a05c8"), data.copyOf(4))
        val frame = data.copyOfRange(JxlDecoder.open(data).firstFrame, data.size)
        assertEquals(data.size - 4, frame.size)
        val header = Bits()
            .put(16, 0x0AFF)
            // 40 by 24, both as a count of 8 pixels less 1, with no fixed ratio.
            .put(1, 1).put(5, 2).put(3, 0).put(5, 4)
            // The image metadata, with its extra fields: no turn, no intrinsic size, a preview.
            .put(1, 0).put(1, 1).put(3, 0).put(1, 0).put(1, 1)
            // The preview is 40 by 24 as well: each side as selector 2 and a count of 8 pixels less 1.
            .put(1, 1).put(2, 2).put(5, 2).put(3, 0).put(2, 2).put(5, 4)
            // No animation, 8-bit whole samples, 16-bit buffers, no extra channel, XYB,
            // the default colour encoding and tone mapping, no extensions, the default transforms.
            .put(1, 0).put(1, 0).put(2, 0).put(1, 1).put(2, 0).put(1, 1).put(1, 1).put(1, 1).put(2, 0).put(1, 1)
            .toByteArray()
        // The frame serves twice: as the preview, which a decoder passes over, and as the image.
        val file = header + frame + frame
        val info = ImageKodec.probe(file)
        assertEquals(40 to 24, info.width to info.height)
        assertEquals(1, info.frameCount)
        assertEquals(1, JxlDecoder.frameHeaders(file).size)
        assertContentEquals(ImageKodec.decode(data).argb, ImageKodec.decode(file).argb)
        assertContentEquals(ImageKodec.decode(data).argb, ImageKodec.decodeAnimation(file).frames.single().bitmap.argb)
        // With the preview announced and only one frame in the file, the image is missing.
        assertFailsWith<ImageDecodeException> { ImageKodec.decode(header + frame) }
    }

    @Test
    fun aFrameOfMoreGroupsThanAnIntCountsIsRefused() {
        // Found by mutation: the second frame's header asks for a size whose group count overflowed,
        // and the negative count sized the table of contents.
        val damaged = JxlFixtures.animation.copyOf()
        for ((at, value) in listOf(3 to 0x76, 29 to 0xAA, 30 to 0x70, 48 to 0x10, 52 to 0xF3, 66 to 0x76, 76 to 0xC5)) {
            damaged[at] = value.toByte()
        }
        // The frames before the damage are still counted.
        assertTrue(ImageKodec.probe(damaged).frameCount >= 1)
        assertFailsWith<ImageDecodeException> { ImageKodec.decodeAnimation(damaged) }
    }

    @Test
    fun anImagePastThePixelCeilingIsRefusedBeforeAnyAllocation() {
        val frame = JxlFixtures.lossy.copyOfRange(4, JxlFixtures.lossy.size)
        // 2^30 by 2^30: the height in 30 bits, less 1, and ratio 1, which is a square.
        val header = Bits().put(16, 0x0AFF).put(1, 0).put(2, 3).put(30, (1L shl 30) - 1).put(3, 1).put(1, 1).put(1, 1).toByteArray()
        val file = header + frame
        val info = ImageKodec.probe(file)
        assertEquals((1 shl 30) to (1 shl 30), info.width to info.height)
        assertFalse(info.isDecodable)
        assertTrue(info.unsupportedReason!!.contains("pixel ceiling"), info.unsupportedReason)
        assertFailsWith<ImageDecodeException> { ImageKodec.decode(file) }
        assertFailsWith<ImageDecodeException> { ImageKodec.decode16(file) }
        assertFailsWith<ImageDecodeException> { ImageKodec.decodeAnimation(file) }
    }

    @Test
    fun aSmallFrameOfAnImagePastThePixelCeilingIsRefused() {
        // The second frame of the animation has its own size and place, so it passes any check of a frame's size.
        val data = JxlFixtures.animation
        val s = JxlDecoder.open(data)
        assertEquals(8, s.firstFrame)
        val br = JxlBitReader(data, s.firstFrame, data.size)
        val second = JxlToc.read(br, JxlFrameHeader.read(br, s.header).numTocEntries).end
        assertTrue(JxlDecoder.frameHeaders(data)[1].customSizeOrOrigin)
        val frames = data.copyOfRange(second, data.size)
        // The frame is sound: under the header it was written for, it is drawn over an empty image.
        assertEquals(16 to 12, ImageKodec.decode(data.copyOf(8) + frames).let { it.width to it.height })
        // The same header for 2^30 by 2^30: the height in 30 bits, less 1, and ratio 1, a square. The rest
        // of the header follows from bit 31 and ends at bit 59.
        val header = Bits().put(16, 0x0AFF).put(1, 0).put(2, 3).put(30, (1L shl 30) - 1).put(3, 1)
        for (bit in 31 until 59) header.put(1, (data[bit / 8].toLong() ushr (bit % 8)) and 1)
        val file = header.toByteArray() + frames
        val info = ImageKodec.probe(file)
        assertEquals((1 shl 30) to (1 shl 30), info.width to info.height)
        assertTrue(info.hasAlpha)
        assertFalse(info.isDecodable)
        // The canvas the frame is blended into has the image's size.
        assertFailsWith<ImageDecodeException> { ImageKodec.decode(file) }
        assertFailsWith<ImageDecodeException> { ImageKodec.decode16(file) }
        assertFailsWith<ImageDecodeException> { ImageKodec.decodeAnimation(file) }
    }

    @Test
    fun anImageOfMoreSamplesThanTheCeilingIsRefused() {
        // 4096 by 4096 is far under the pixel ceiling, but 4096 extra channels of that size are 2^36 samples.
        val header = Bits().put(16, 0x0AFF)
            .put(1, 0).put(2, 1).put(13, 4095).put(3, 1)
            // The image metadata: 8-bit whole samples, 16-bit buffers, then 4096 extra channels, each a default alpha.
            .put(1, 0).put(1, 0).put(1, 0).put(2, 0).put(1, 1).put(2, 3).put(12, 4095)
        repeat(4096) { header.put(1, 1) }
        // XYB, the default colour encoding, no extensions, the default transforms.
        header.put(1, 1).put(1, 1).put(2, 0).put(1, 1)
        val file = header.toByteArray() + JxlFixtures.lossy.copyOfRange(4, JxlFixtures.lossy.size)
        val info = ImageKodec.probe(file)
        assertEquals(4096 to 4096, info.width to info.height)
        assertTrue(info.hasAlpha)
        assertFalse(info.isDecodable)
        assertTrue(info.unsupportedReason!!.contains("sample ceiling"), info.unsupportedReason)
        assertFailsWith<ImageDecodeException> { ImageKodec.decode(file) }
        assertFailsWith<ImageDecodeException> { ImageKodec.decode16(file) }
        assertFailsWith<ImageDecodeException> { ImageKodec.decodeAnimation(file) }
    }

    @Test
    fun aFrameWhosePaddingPassesTheCeilingIsRefused() {
        // 2^28 by 1 is at the pixel ceiling. VarDCT pads it to whole blocks, 8 rows, which is 2^31 samples a channel.
        val header = Bits().put(16, 0x0AFF)
            .put(1, 0).put(2, 0).put(9, 0).put(3, 0).put(2, 3).put(30, (1L shl 28) - 1)
            .put(1, 1).put(1, 1).toByteArray()
        val file = header + JxlFixtures.lossy.copyOfRange(4, JxlFixtures.lossy.size)
        assertEquals((1 shl 28) to 1, ImageKodec.probe(file).let { it.width to it.height })
        val failure = assertFailsWith<ImageDecodeException> { ImageKodec.decode(file) }
        assertTrue(failure.message!!.contains("padded"), failure.message)
    }

    /**
     * A VarDCT frame that asks for more coefficient contexts than [JxlVarDct] allows (#111): HF
     * global's histogram count, times the contexts of the default block context map, times the
     * passes. The count is read after every LF group, so the frame needs sound LF groups; each of
     * them is a Modular image of zeros that the frame's shared tree, a single leaf with a prefix
     * code of one symbol, codes in no bits. Most of the file is its table of contents.
     */
    private fun manyContextsFrame(passes: Int, histograms: Int): ByteArray {
        // 27 by 19 groups of 256 pixels: 513, so the histogram count has 10 bits and reaches 1024.
        val width = 27 * 256
        val height = 19 * 256
        val xBlocks = width / 8
        val yBlocks = height / 8
        val lfAcross = (xBlocks + 255) / 256
        val lfGroups = lfAcross * ((yBlocks + 255) / 256)
        val groups = 27 * 19
        fun bitsFor(v: Int) = 32 - (v - 1).countLeadingZeroBits()
        // Zeros at no cost: no LZ77, every context in cluster 0, a prefix code of one symbol whose
        // hybrid integer split equals the alphabet's 15 bits.
        fun Bits.zeroCode(contexts: Int): Bits {
            put(1, 0)
            if (contexts > 1) put(1, 1).put(2, 0)
            return put(1, 1).put(4, 15).put(1, 0)
        }
        // A Modular stream on the shared tree, with the default weighted predictor parameters and no transforms.
        fun Bits.zeroStream(): Bits = put(1, 1).put(1, 1).put(2, 0)

        val header = Bits().put(16, 0x0AFF)
            // The height and then the width, each as selector 1 and 13 bits less 1, then the default metadata and transforms.
            .put(1, 0).put(2, 1).put(13, height - 1L).put(3, 0).put(2, 1).put(13, width - 1L)
            .put(1, 1).put(1, 1).toByteArray()

        // LF global: the default LF quantization, a global scale and LF quantizer, the default
        // block context map and chroma from luma, and the shared tree with its code.
        val lfGlobal = Bits().put(1, 1).put(2, 0).put(11, 0).put(2, 0).put(1, 1).put(1, 1)
            .put(1, 1).zeroCode(6).zeroCode(1).toByteArray()
        val lfGroupSections = List(lfGroups) { g ->
            val w = minOf(256, xBlocks - (g % lfAcross) * 256)
            val h = minOf(256, yBlocks - (g / lfAcross) * 256)
            // The LF coefficients, then the HF metadata: one DCT8 for each block, all with quantizer 1.
            Bits().put(2, 0).zeroStream().put(bitsFor(w * h), w * h - 1L).zeroStream().toByteArray()
        }
        // HF global: the default dequantization matrices, the histogram count, and the first pass's
        // coefficient orders, all natural. The decoder refuses the count there, before its code.
        val hfGlobal = Bits().put(1, 1).put(10, histograms - 1L).put(2, 2).toByteArray()
        val sections = listOf(lfGlobal) + lfGroupSections + listOf(hfGlobal) + List(groups * passes) { ByteArray(0) }

        val frame = Bits()
            // Not all default: a regular VarDCT frame, no flags, no upsampling, the default quantization matrix scales.
            .put(1, 0).put(2, 0).put(1, 0).put(2, 0).put(2, 0).put(3, 3).put(3, 2)
        // The passes, as selector 3 and 3 bits less 4, with no downsampling and no shifts.
        if (passes >= 4) frame.put(2, 3).put(3, passes - 4L) else frame.put(2, passes - 1L)
        if (passes > 1) {
            frame.put(2, 0)
            repeat(passes - 1) { frame.put(2, 0) }
        }
        // Not cropped, replaced, the last frame, no name, the default loop filter, no extensions.
        frame.put(1, 0).put(2, 0).put(1, 1).put(2, 0).put(1, 1).put(2, 0)
        // The table of contents, in order, every size in 10 bits.
        frame.put(1, 0).pad()
        for (s in sections) frame.put(2, 0).put(10, s.size.toLong())
        frame.pad()
        var file = header + frame.toByteArray()
        for (s in sections) file += s
        return file
    }

    @Test
    fun aFrameOfTooManyCoefficientContextsIsRefused() {
        // 1024 histograms of 15 block contexts of 495 coefficient contexts each are 7,603,200 a
        // pass. Nine passes are 68,428,800, past the 2^26 that JxlVarDct allows.
        val tooMany = manyContextsFrame(passes = 9, histograms = 1024)
        assertTrue(tooMany.size < 8_000, "${tooMany.size} bytes")
        assertEquals(6912 to 4864, ImageKodec.probe(tooMany).let { it.width to it.height })
        val failure = assertFailsWith<ImageDecodeException> { ImageKodec.decode(tooMany) }
        assertTrue(failure.message!!.contains("7603200 coefficient contexts in each of 9 passes"), failure.message)
        // Eight passes are 60,825,600, within the limit: the same frame reads on into its codes,
        // which the file leaves out, so every section before the check is sound.
        val fewer = assertFailsWith<ImageDecodeException> { ImageKodec.decode(manyContextsFrame(passes = 8, histograms = 1024)) }
        assertTrue(fewer.message!!.contains("data ends"), fewer.message)
    }
}
