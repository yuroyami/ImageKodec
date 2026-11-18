package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.avif.HeifCodecs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Image sequences of any codec through the HEIF reader (#157): which of track and primary item a file
 * shows, frames in presentation order from `ctts`, their durations, an alpha track, and the hostile
 * sample tables that must fail as decode errors.
 */
@OptIn(ExperimentalHeifCodecApi::class)
class HeifSequenceTest {

    @AfterTest
    fun forgetTheTestCodec() = HeifCodecs.reset()

    /**
     * An invented codec `fake` configured by `fakC`, which gives the size as two 16-bit numbers. A
     * sample is two bytes: a gray level and the picture's rank in output order. Like HEVC with B
     * pictures, a session holds a picture back until every picture of a lower rank has come out.
     */
    private object ReorderingCodec : HeifCodec {
        override val itemType = "fake"
        override val sampleEntryTypes = setOf("fake")
        override val configurationType = "fakC"

        private fun size(c: ByteArray?): Pair<Int, Int> {
            val b = c ?: throw ImageDecodeException("no fakC")
            return (((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)) to (((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF))
        }

        override fun inspect(configuration: ByteArray?, data: ByteArray?): HeifCodecInfo {
            val (w, h) = size(configuration)
            return HeifCodecInfo(w, h, 8, 8, chromaFormat = 3, colorPrimaries = 1, transferCharacteristics = 13, matrixCoefficients = 6, fullRange = true)
        }

        override fun open(configuration: ByteArray?): HeifCodecSession = object : HeifCodecSession {
            private val held = HashMap<Int, HeifPlanes>()
            private var nextRank = 0

            private fun picture(sample: ByteArray): HeifPlanes {
                val (w, h) = size(configuration)
                val y = ShortArray(w * h) { (sample[0].toInt() and 0xFF).toShort() }
                val c = ShortArray(w * h) { 128 }
                return HeifPlanes(w, h, 8, 8, 0, 0, arrayOf(y, c, c.copyOf()), intArrayOf(w, w, w), fullRange = true)
            }

            override fun decode(sample: ByteArray, cancellationCheck: (() -> Unit)?): HeifPlanes = picture(sample)

            override fun decodeInOutputOrder(sample: ByteArray, cancellationCheck: (() -> Unit)?): List<HeifPlanes> {
                // A negative rank is a picture the codec skips, as HEVC skips the RASL pictures of a first CRA.
                if (sample[1] < 0) return emptyList()
                held[sample[1].toInt()] = picture(sample)
                val out = ArrayList<HeifPlanes>()
                while (true) out += held.remove(nextRank)?.also { nextRank++ } ?: return out
            }

            override fun flush(): List<HeifPlanes> = held.keys.sorted().map { held.remove(it)!! }
        }
    }

    private fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun box(type: String, payload: ByteArray) = be32(8 + payload.size) + type.encodeToByteArray() + payload
    private fun fullBox(type: String, payload: ByteArray) = box(type, ByteArray(4) + payload)
    private fun ispe(w: Int, h: Int) = box("ispe", ByteArray(4) + be32(w) + be32(h)) to false
    private fun fakC(w: Int, h: Int) = box("fakC", be16(w) + be16(h))
    private fun sample(level: Int, rank: Int) = byteArrayOf(level.toByte(), rank.toByte())
    private fun gray(level: Int, alpha: Int = 255) = (alpha shl 24) or (level * 0x010101)

    /** A primary item of level 10 and, unless [withTrack] is false, a track of levels 20 and 30. */
    private fun itemAndTrack(major: String, brands: List<String>, withTrack: Boolean = true) = HeifBuilder(major, brands).apply {
        add(1, "fake", sample(10, 0), listOf(ispe(6, 4), fakC(6, 4) to true))
        if (withTrack) addTrack(1, "fake", 6, 4, fakC(6, 4), listOf(sample(20, 0), sample(30, 1)))
    }.build()

    @Test
    fun theMajorBrandChoosesBetweenTheTrackAndThePrimaryItemAsLibavifDoes() {
        ImageKodec.registerHeifCodec(ReorderingCodec)
        // libavif's avifDecoderReset: 'avif' shows the primary item, 'avis' the track, any other brand the track.
        val cases = listOf(
            Triple("avif", listOf("avif", "avis", "mif1", "msf1"), listOf(10)),
            Triple("avis", listOf("avif", "avis", "mif1", "msf1"), listOf(20, 30)),
            Triple("heic", listOf("mif1", "heic", "msf1", "hevc"), listOf(20, 30)),
            Triple("msf1", listOf("msf1", "hevc"), listOf(20, 30)),
            Triple("mif1", listOf("mif1", "msf1", "miaf"), listOf(20, 30)),
        )
        for ((major, brands, levels) in cases) {
            val data = itemAndTrack(major, brands)
            val animation = ImageKodec.decodeAnimation(data)
            assertEquals(levels.map { gray(it) }, animation.frames.map { it.bitmap[0, 0] }, major)
            assertEquals(levels.size, ImageKodec.probe(data).frameCount, major)
            assertEquals(gray(levels[0]), ImageKodec.decode(data)[0, 0], major)
        }
        // Without a track every brand shows the primary item.
        for (major in listOf("avif", "avis", "heic", "msf1")) {
            val data = itemAndTrack(major, listOf("mif1", "msf1", "avif", "heic"), withTrack = false)
            assertEquals(listOf(gray(10)), ImageKodec.decodeAnimation(data).frames.map { it.bitmap[0, 0] }, major)
        }
        assertEquals(ImageFormat.AVIF, ImageKodec.probe(itemAndTrack("avis", listOf("avis", "msf1"))).format)
        assertEquals(ImageFormat.HEIC, ImageKodec.probe(itemAndTrack("msf1", listOf("msf1", "hevc"))).format)
    }

    /**
     * Four pictures that decode as an IDR, a P and two B pictures do: ranks 0, 3, 2 and 1 in decoding
     * order, levels 10 to 40 by rank. Composition times 2, 6, 5 and 3 put them back in rank order.
     */
    private fun reordered(handler: String = "vide") = HeifBuilder("msf1", listOf("msf1", "iso8")).apply {
        primary = null
        addTrack(
            1, "fake", 6, 4, fakC(6, 4),
            listOf(sample(10, 0), sample(40, 3), sample(30, 2), sample(20, 1)),
            durations = listOf(1, 1, 1, 4), handler = handler,
            ctts = listOf(1 to 2, 1 to 5, 1 to 3, 1 to 0),
        )
    }.build()

    @Test
    fun framesComeInPresentationOrderWithTheirCompositionDurations() {
        ImageKodec.registerHeifCodec(ReorderingCodec)
        for (handler in listOf("vide", "pict")) {
            val animation = ImageKodec.decodeAnimation(reordered(handler))
            assertEquals(listOf(10, 20, 30, 40).map { gray(it) }, animation.frames.map { it.bitmap[0, 0] }, handler)
            // From one composition time to the next, and the last frame its own stts duration.
            assertEquals(listOf(100, 200, 100, 100), animation.frames.map { it.delayMillis }, handler)
            assertEquals(listOf(10, 20, 10, 10), animation.frames.map { it.delayRawCentiseconds }, handler)
            assertEquals(6 to 4, animation.width to animation.height)
        }
        val info = ImageKodec.probe(reordered())
        assertEquals(4, info.frameCount)
        assertEquals(6 to 4, info.width to info.height)
        assertEquals(ImageFormat.HEIC, info.format)
        // A shorter decode stops early, and the check runs once a sample after the first.
        assertEquals(listOf(10, 20).map { gray(it) }, ImageKodec.decodeAnimation(reordered(), maxFrames = 2).frames.map { it.bitmap[0, 0] })
        var checks = 0
        ImageKodec.decodeAnimation(reordered(), cancellationCheck = { checks++ })
        assertEquals(3, checks)
    }

    /**
     * A sequence cut at a CRA picture: its two RASL pictures come first in output order and the codec
     * skips them. The frames shown are the CRA and the P picture, with the timing of the last two
     * samples by composition time, not of the first two.
     */
    @Test
    fun skippedLeadingPicturesLeaveTheLaterFramesWithTheirOwnTiming() {
        ImageKodec.registerHeifCodec(ReorderingCodec)
        val data = HeifBuilder("msf1", listOf("msf1")).apply {
            primary = null
            // Decoding order: CRA, RASL, RASL, P. Composition times 4, 2, 3 and 5.
            addTrack(
                1, "fake", 6, 4, fakC(6, 4),
                listOf(sample(30, 0), sample(10, -1), sample(20, -1), sample(40, 1)),
                durations = listOf(1, 1, 1, 7), ctts = listOf(1 to 4, 2 to 1, 1 to 2), repeat = false,
            )
        }.build()
        val animation = ImageKodec.decodeAnimation(data)
        assertEquals(listOf(30, 40).map { gray(it) }, animation.frames.map { it.bitmap[0, 0] })
        assertEquals(listOf(100, 700), animation.frames.map { it.delayMillis })
    }

    @Test
    fun unevenDurationsWithoutCtts() {
        ImageKodec.registerHeifCodec(ReorderingCodec)
        val data = HeifBuilder("msf1", listOf("msf1")).apply {
            primary = null
            addTrack(1, "fake", 6, 4, fakC(6, 4), List(3) { sample(10 * it, it) }, durations = listOf(3, 1, 6), repeat = false)
        }.build()
        val animation = ImageKodec.decodeAnimation(data)
        assertEquals(listOf(300, 100, 600), animation.frames.map { it.delayMillis })
        assertEquals(1L, animation.loopCount, "an edit list that does not repeat plays once")
    }

    @Test
    fun anAlphaTrackAppliesToEachFrame() {
        ImageKodec.registerHeifCodec(ReorderingCodec)
        val urn = "urn:mpeg:mpegB:cicp:systems:auxiliary:alpha".encodeToByteArray() + byteArrayOf(0)
        val data = HeifBuilder("msf1", listOf("msf1", "hevc")).apply {
            primary = null
            val ctts = listOf(1 to 2, 1 to 5, 1 to 3, 1 to 0)
            addTrack(1, "fake", 6, 4, fakC(6, 4), listOf(sample(10, 0), sample(40, 3), sample(30, 2), sample(20, 1)), ctts = ctts, repeat = true, editDuration = 2)
            addTrack(
                2, "fake", 6, 4, fakC(6, 4) + fullBox("auxi", urn),
                listOf(sample(200, 0), sample(50, 3), sample(100, 2), sample(150, 1)),
                handler = "auxv", ctts = ctts, references = listOf("auxl" to intArrayOf(1)),
            )
        }.build()
        val info = ImageKodec.probe(data)
        assertTrue(info.hasAlpha)
        val animation = ImageKodec.decodeAnimation(data)
        assertEquals(
            listOf(gray(10, 200), gray(20, 150), gray(30, 100), gray(40, 50)),
            animation.frames.map { it.bitmap[0, 0] },
        )
        assertEquals(2L, animation.loopCount, "a repeating edit list of half the track plays twice, as libavif counts")
    }

    @Test
    fun anHevcSequenceWithoutTheModuleNamesTheModule() {
        // The core alone: an hvc1 track of the size its sample entry gives, and an hvcC that no codec reads.
        val hvcC = box("hvcC", ByteArray(23).also { it[0] = 1; it[16] = 0xFD.toByte(); it[17] = 0xFA.toByte(); it[18] = 0xFA.toByte() })
        val data = HeifBuilder("msf1", listOf("msf1", "hevc")).apply {
            primary = null
            addTrack(1, "hvc1", 64, 48, hvcC, listOf(ByteArray(10), ByteArray(10)), handler = "vide")
        }.build()
        assertEquals(ImageFormat.HEIC, ImageFormat.sniff(data))
        val info = ImageKodec.probe(data)
        assertEquals(ImageFormat.HEIC, info.format)
        assertFalse(info.isDecodable)
        assertTrue(info.unsupportedReason!!.contains("imagekodec-heic"), info.unsupportedReason)
        assertEquals(64 to 48, info.width to info.height)
        assertEquals(2, info.frameCount)
        assertEquals(10, info.bitDepth)
        val e = assertFailsWith<UnsupportedImageException> { ImageKodec.decodeAnimation(data) }
        assertTrue(e.message!!.contains("imagekodec-heic"), e.message)
        assertFailsWith<UnsupportedImageException> { ImageKodec.decode(data) }
    }

    @Test
    fun hostileSampleTablesFailAsDecodeErrors() {
        ImageKodec.registerHeifCodec(ReorderingCodec)
        fun track(ctts: List<Pair<Int, Int>>?, version: Int = 0) = HeifBuilder("msf1", listOf("msf1")).apply {
            primary = null
            addTrack(1, "fake", 6, 4, fakC(6, 4), listOf(sample(10, 0), sample(20, 1)), ctts = ctts, cttsVersion = version)
        }.build()
        val hostile = mapOf(
            "a ctts longer than the samples" to track(listOf(3 to 0)),
            "a ctts of a run past the samples" to track(listOf(1 to 0, -1 to 0)),
            "a ctts shorter than the samples" to track(listOf(1 to 0)),
            "a negative offset before the start" to track(listOf(1 to 0, 1 to -5), version = 1),
            "a negative offset read from version 0" to track(listOf(1 to 0, 1 to -5), version = 0),
        )
        for ((name, data) in hostile) {
            val e = assertFailsWith<ImageDecodeException>(name) { ImageKodec.decodeAnimation(data) }
            assertTrue(e.message!!.contains("ctts") || e.message!!.contains("composed"), "$name: ${e.message}")
            assertFailsWith<ImageDecodeException>(name) { ImageKodec.probe(data) }
        }
        // A negative offset that keeps every composition time at or after 0 is fine (version 1).
        val earlier = track(listOf(1 to 0, 1 to -1), version = 1)
        assertEquals(listOf(gray(10), gray(20)), ImageKodec.decodeAnimation(earlier).frames.map { it.bitmap[0, 0] })
        // The last sample cut off by the end of the file: probe reads the table, the animation fails.
        val whole = track(null)
        val cut = whole.copyOf(whole.size - 1)
        assertEquals(2, ImageKodec.probe(cut).frameCount)
        val e = assertFailsWith<ImageDecodeException> { ImageKodec.decodeAnimation(cut) }
        assertTrue(e.message!!.contains("past the end"), e.message)
    }
}
