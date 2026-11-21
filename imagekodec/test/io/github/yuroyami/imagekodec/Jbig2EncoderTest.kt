package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.Jbig2Decoder
import io.github.yuroyami.imagekodec.codec.MqDecoder
import io.github.yuroyami.imagekodec.codec.MqEncoder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [ImageKodec.encodeJbig2] (#185): every page of [BilevelPages], in every template, with and without
 * typical prediction, with MMR, and as an embedded stream and a file, must come back exactly from
 * [Jbig2Decoder]. jbig2dec reads the same streams in `Jbig2EncodeOracleTest`.
 */
class Jbig2EncoderTest {

    private val variants: List<Jbig2EncodeOptions> = buildList {
        for (standalone in listOf(false, true)) {
            for (template in 0..3) for (tp in listOf(false, true)) add(Jbig2EncodeOptions(template, tp, false, standalone))
            add(Jbig2EncodeOptions(mmr = true, standalone = standalone))
        }
    }

    /** The embedded stream inside a file: [Jbig2Decoder] reads segments, not the file header. */
    private fun embedded(data: ByteArray, standalone: Boolean): ByteArray {
        if (!standalone) return data
        val header = byteArrayOf(0x97.toByte(), 0x4A, 0x42, 0x32, 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0, 0, 0, 1)
        assertContentEquals(header, data.copyOf(13), "the file header of T.88 D.4")
        return data.copyOfRange(13, data.size)
    }

    /** The type of each segment, walking the headers this encoder writes (T.88 7.2). */
    private fun segmentTypes(stream: ByteArray): List<Int> {
        val types = ArrayList<Int>()
        var at = 0
        while (at < stream.size) {
            types += stream[at + 4].toInt() and 0x3F
            val length = ((stream[at + 7].toInt() and 0xFF) shl 24) or ((stream[at + 8].toInt() and 0xFF) shl 16) or
                ((stream[at + 9].toInt() and 0xFF) shl 8) or (stream[at + 10].toInt() and 0xFF)
            at += 11 + length
        }
        assertEquals(stream.size, at, "the segments fill the stream")
        return types
    }

    @Test
    fun everyPageRoundTripsInEveryVariant() {
        for (page in BilevelPages.edges) {
            val bitmap = page.bitmap()
            val expected = page.packed(blackIs1 = false)
            for (options in variants) {
                val label = "${page.name} $options"
                val stream = embedded(ImageKodec.encodeJbig2(bitmap, options), options.standalone)
                val decoded = assertNotNull(Jbig2Decoder.decode(stream, null, page.width, page.height), label)
                assertContentEquals(expected, decoded, label)
            }
        }
    }

    @Test
    fun theStreamHoldsThePageAndTheRegionAndTheFileEndsThePage() {
        val bitmap = BilevelPages.text(157, 61, 3).bitmap()
        assertEquals(listOf(48, 38), segmentTypes(ImageKodec.encodeJbig2(bitmap)))
        val file = ImageKodec.encodeJbig2(bitmap, Jbig2EncodeOptions(standalone = true))
        assertEquals(listOf(48, 38, 49, 51), segmentTypes(embedded(file, true)))
    }

    @Test
    fun typicalPredictionMakesBlankRowsNearlyFree() {
        val page = BilevelPages.text(1728, 400, 9).bitmap()
        val with = ImageKodec.encodeJbig2(page, Jbig2EncodeOptions(typicalPrediction = true)).size
        val without = ImageKodec.encodeJbig2(page, Jbig2EncodeOptions(typicalPrediction = false)).size
        val mmr = ImageKodec.encodeJbig2(page, Jbig2EncodeOptions(mmr = true)).size
        assertTrue(with <= without, "typical prediction: $with bytes, without: $without")
        assertTrue(without < mmr, "arithmetic: $without bytes, MMR: $mmr")
    }

    @Test
    fun theMqCoderRoundTripsSkewedAndEvenBits() {
        // Long runs of the likely symbol push the interval to its edge and make carries and 0xFF
        // bytes, the cases where BYTEOUT stuffs a bit; even bits make many short renormalizations.
        for (skew in intArrayOf(2, 7, 60, 4000)) {
            val random = BilevelPages.Random(skew.toLong())
            val bits = IntArray(60000) { if (random.next(skew) == 0) 1 else 0 }
            val contexts = IntArray(bits.size) { random.next(4) }
            val encoder = MqEncoder()
            val encodeState = IntArray(4)
            for (i in bits.indices) encoder.encode(encodeState, contexts[i], bits[i])
            val data = encoder.finish()
            assertEquals(0xAC, data.last().toInt() and 0xFF, "the data ends with the marker 0xFF 0xAC")
            val decoder = MqDecoder(data, 0, data.size)
            val decodeState = IntArray(4)
            for (i in bits.indices) assertEquals(bits[i], decoder.bit(decodeState, contexts[i]), "skew $skew, bit $i")
        }
    }

    @Test
    fun aTemplateOutsideZeroToThreeIsRefused() {
        for (template in intArrayOf(-1, 4)) {
            val e = assertFailsWith<IllegalArgumentException> { Jbig2EncodeOptions(template = template) }
            assertTrue(e.message!!.contains("template"), e.message)
        }
    }

    @Test
    fun theOutputIsTheSameOnEveryTarget() {
        val page = BilevelPages.text(1728, 120, 21).bitmap()
        val hashes = listOf(
            Jbig2EncodeOptions() to 6822326670944981253L,
            Jbig2EncodeOptions(template = 1, typicalPrediction = false) to -5654323516374818856L,
            Jbig2EncodeOptions(template = 2) to -3623713946221759228L,
            Jbig2EncodeOptions(template = 3, standalone = true) to 6836938708347794525L,
            Jbig2EncodeOptions(mmr = true) to 3306762732260702619L,
        )
        val got = hashes.map { (options, _) -> BilevelPages.fnv(ImageKodec.encodeJbig2(page, options)) }
        for ((i, pair) in hashes.withIndex()) assertEquals(pair.second, got[i], pair.first.toString())
    }

    @Test
    fun theCancellationCheckStopsTheEncode() {
        class Stop : RuntimeException()
        val page = BilevelPages.text(300, 200, 2).bitmap()
        for (options in listOf(Jbig2EncodeOptions(), Jbig2EncodeOptions(mmr = true))) {
            var calls = 0
            assertFailsWith<Stop>(options.toString()) {
                ImageKodec.encodeJbig2(page, options) { if (++calls == 6) throw Stop() }
            }
            assertEquals(6, calls)
        }
    }
}
