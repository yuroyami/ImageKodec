package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.Jbig2Writer.Glyph
import io.github.yuroyami.imagekodec.Jbig2Writer.Instance
import io.github.yuroyami.imagekodec.Jbig2Writer.NewSymbol
import io.github.yuroyami.imagekodec.codec.Jbig2Decoder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * The JBIG2 layouts #189 adds: symbol dictionaries that refine and aggregate symbols, arithmetic
 * and Huffman coded, written by [Jbig2Writer], and immediate generic regions of unknown length,
 * rewritten from ImageKodec's encoder. Each page must decode to the page the writer drew.
 */
class Jbig2AggregateTest {

    internal class Fixture(val stream: ByteArray, val width: Int, val height: Int, val expected: ByteArray, val dictionaryEnd: Int = 0)

    /**
     * A page of the new symbols of a refining and aggregating dictionary: four glyphs in a plain
     * dictionary, then single refinements with and without offsets, and aggregates of two and
     * three instances, one of them refined, one of them of a symbol the dictionary made itself.
     */
    internal fun aggregates(seed: Int = 1): Fixture {
        val random = Random(seed)
        fun glyph(w: Int, h: Int) = Glyph(w, h, IntArray(w * h) { if (random.nextInt(3) == 0) 1 else 0 })
        fun flipped(g: Glyph) = Glyph(g.w, g.h, g.bits.copyOf().also { b -> repeat(3) { b[random.nextInt(b.size)] = b[random.nextInt(b.size)] xor 1 } })
        val known = listOf(glyph(5, 7), glyph(6, 7), glyph(7, 9), glyph(8, 9))
        val wider = Glyph(9, 9, IntArray(81) { if (random.nextInt(2) == 0) 1 else 0 })
        val symbols = listOf(
            NewSymbol(5, 7, listOf(Instance(0, 0, 0, target = flipped(known[0])))),
            NewSymbol(6, 7, listOf(Instance(1, 0, 0, target = flipped(known[1]), rdx = 1, rdy = -1))),
            NewSymbol(13, 9, listOf(Instance(0, 0, 1), Instance(1, 6, 2))),
            NewSymbol(16, 11, listOf(Instance(2, 0, 0, target = flipped(known[2])), Instance(3, 8, 2), Instance(0, 2, 4))),
            // An instance of the dictionary's own first symbol, and one refined to another size.
            NewSymbol(21, 12, listOf(Instance(4, 0, 0), Instance(3, 7, 3, target = wider, rdx = -1))),
        )
        val all = ArrayList(known)
        for (sym in symbols) all += Jbig2Writer.render(sym, all)
        val decoded = all.subList(known.size, all.size).toList()
        val width = 64
        val height = 40
        val place = decoded.indices.map { k -> Instance(k, (k % 3) * 22, (k / 3) * 18 + k) }
        val dictionaries = Jbig2Writer.pageInfo(width, height) +
            Jbig2Writer.plainDictionary(1, known) +
            Jbig2Writer.refinementDictionary(2, listOf(1), known, symbols, decoded, huffman = false)
        val stream = dictionaries + Jbig2Writer.textRegionSegment(3, listOf(2), width, height, decoded, place)
        return Fixture(stream, width, height, Jbig2Writer.page(width, height, decoded, place), dictionaries.size)
    }

    /** ImageKodec's JBIG2 of a page, its generic region rewritten to an unknown length that its row count ends. */
    internal fun unknownLength(mmr: Boolean, unknownHeight: Boolean): Fixture {
        val w = 37
        val h = 23
        val page = KiteBitmap(w, h, IntArray(w * h) { i -> if (((i % w) * (i / w) + i / 7) % 5 == 0) 0xFF000000.toInt() else -1 })
        val coded = ImageKodec.encodeJbig2(page, Jbig2EncodeOptions(mmr = mmr))
        // jbig2dec finds the end of MMR data at the first two zero bytes, so the data must not end in one.
        check(!mmr || coded.last() != 0.toByte())
        val expected = assertNotNull(Jbig2Decoder.decode(coded, null, w, h))
        // Page information (11 + 19 bytes), then the region: its data length at 37, its height at 45.
        val out = coded.copyOf()
        for (i in 37 until 41) out[i] = -1
        if (unknownHeight) for (i in 45 until 49) out[i] = -1
        val end = if (mmr) byteArrayOf(0, 0) else ByteArray(0)  // the MQ data already ends with 0xFF 0xAC
        return Fixture(out + end + beBytes(h), w, h, expected)
    }

    @Test
    fun refiningAndAggregatingDictionariesDecode() {
        for (seed in 1..10) {
            val f = aggregates(seed)
            assertContentEquals(f.expected, Jbig2Decoder.decodeChecked(f.stream, null, f.width, f.height), "seed $seed")
        }
    }

    @Test
    fun genericRegionsOfUnknownLengthEndAtTheirRowCount() {
        for (mmr in listOf(false, true)) for (unknownHeight in listOf(false, true)) {
            val f = unknownLength(mmr, unknownHeight)
            assertContentEquals(f.expected, Jbig2Decoder.decodeChecked(f.stream, null, f.width, f.height), "mmr $mmr")
            // Another segment may follow the row count.
            val more = f.stream + Jbig2Writer.segment(9, 50, emptyList(), ByteArray(0))
            assertContentEquals(f.expected, Jbig2Decoder.decodeChecked(more, null, f.width, f.height))
        }
    }

    @Test
    fun damagedNewLayoutsAreDecodeErrors() {
        // An unknown length without its end marker, and on a segment type that may not have one.
        val f = unknownLength(mmr = false, unknownHeight = false)
        val marker = f.stream.size - 6
        assertFailsWith<ImageDecodeException> { Jbig2Decoder.decodeChecked(f.stream.copyOf(marker), null, f.width, f.height) }
        val dictionary = Jbig2Writer.segment(1, 0, emptyList(), ByteArray(20), length = 0xFFFFFFFFL)
        assertFailsWith<ImageDecodeException> { Jbig2Decoder.decodeChecked(Jbig2Writer.pageInfo(8, 8) + dictionary, null, 8, 8) }
        // A Huffman refinement dictionary stays refused, by name.
        val known = listOf(Glyph(3, 3, IntArray(9) { it % 2 }))
        val sym = listOf(NewSymbol(3, 3, listOf(Instance(0, 0, 0))))
        val refused = Jbig2Writer.pageInfo(8, 8) + Jbig2Writer.plainDictionary(1, known) +
            Jbig2Writer.refinementDictionary(2, listOf(1), known, sym, known, huffman = true)
        val e = assertFailsWith<UnsupportedImageException> { Jbig2Decoder.decodeChecked(refused, null, 8, 8) }
        kotlin.test.assertTrue("Huffman refinement" in e.message.orEmpty())
    }
}
