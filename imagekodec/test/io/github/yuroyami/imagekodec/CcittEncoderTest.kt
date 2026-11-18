package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.CcittFax
import io.github.yuroyami.imagekodec.codec.CcittOptions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [ImageKodec.encodeCcitt] and [CcittFax.encode] (#185): every page of [BilevelPages] in every coding
 * and framing must come back exactly from [CcittFax.decode], told the row count or, where the data
 * ends with EOFB or RTC, not told it. libtiff reads the same data in `CcittEncodeOracleTest`.
 */
class CcittEncoderTest {

    private class Framing(val k: Int, val byteAlign: Boolean, val blackIs1: Boolean, val endOfLine: Boolean, val endOfBlock: Boolean) {
        val options = CcittEncodeOptions(k, byteAlign, blackIs1, endOfLine, endOfBlock)
        override fun toString() = options.toString()
    }

    private val framings: List<Framing> = buildList {
        for (k in intArrayOf(-1, 0, 1, 2, 4)) for (align in listOf(false, true)) for (blackIs1 in listOf(false, true)) {
            for (eol in if (k < 0) listOf(false) else listOf(false, true)) for (eob in listOf(false, true)) {
                add(Framing(k, align, blackIs1, eol, eob))
            }
        }
    }

    private fun decode(data: ByteArray, page: BilevelPages.Page, f: Framing, rows: Int): ByteArray =
        CcittFax.decode(
            data, f.k,
            CcittOptions(
                columns = page.width, rows = rows, endOfBlock = f.endOfBlock, blackIs1 = f.blackIs1,
                encodedByteAlign = f.byteAlign, endOfLine = f.endOfLine,
            ),
        )

    @Test
    fun everyPageRoundTripsInEveryCodingAndFraming() {
        for (page in BilevelPages.edges) {
            val bitmap = page.bitmap()
            for (f in framings) {
                val label = "${page.name} $f"
                val data = ImageKodec.encodeCcitt(bitmap, f.options)
                val expected = page.packed(f.blackIs1)
                assertContentEquals(expected, decode(data, page, f, page.height), label)
                // EOFB and RTC mark the end, so a reader that is not told the row count stops there.
                if (f.endOfBlock) assertContentEquals(expected, decode(data, page, f, 0), "$label without a row count")
            }
        }
    }

    @Test
    fun thePackedEntryPointIgnoresThePaddingAndMirrorsDecode() {
        val page = BilevelPages.text(157, 61, 5)
        for (blackIs1 in listOf(false, true)) for (k in intArrayOf(-1, 0, 3)) {
            val packed = page.packed(blackIs1)
            // Set every padding bit: the 3 bits after column 157 of each 20-byte row.
            val dirty = packed.copyOf()
            for (y in 0 until page.height) dirty[y * 20 + 19] = (dirty[y * 20 + 19].toInt() or 0x07).toByte()
            for (rows in intArrayOf(0, page.height)) {
                val options = CcittOptions(
                    columns = page.width, rows = rows, endOfBlock = true, blackIs1 = blackIs1,
                    encodedByteAlign = false, endOfLine = false,
                )
                val data = CcittFax.encode(dirty, k, options)
                assertContentEquals(packed, CcittFax.decode(data, k, options), "k=$k blackIs1=$blackIs1 rows=$rows")
                val viaBitmap = ImageKodec.encodeCcitt(page.bitmap(), CcittEncodeOptions(k = k, blackIs1 = blackIs1))
                assertContentEquals(viaBitmap, data, "k=$k blackIs1=$blackIs1 rows=$rows: the two entry points agree")
            }
        }
    }

    @Test
    fun aPixelIsBlackWhereItsLumaOverWhiteIsBelowHalf() {
        val pixels = intArrayOf(
            0xFF7F7F7F.toInt(), // luma 127: black
            0xFF808080.toInt(), // luma 128: white
            0x00000000,         // transparent black: white
            0x80000000.toInt(), // black at alpha 128 over white: 127, black
            0x7F000000,         // black at alpha 127 over white: 128, white
            0xFFFF0000.toInt(), // red, luma 76: black
            0xFF00FF00.toInt(), // green, luma 149: white
        )
        val bitmap = KiteBitmap(pixels.size, 1, pixels)
        val data = ImageKodec.encodeCcitt(bitmap, CcittEncodeOptions(blackIs1 = true))
        val row = CcittFax.decode(data, -1, CcittOptions(pixels.size, 1, true, true, false, false))
        assertEquals(0b10010100, row[0].toInt() and 0xFF)
    }

    @Test
    fun argumentsTheFormatCannotHoldAreRefused() {
        assertFailsWith<IllegalArgumentException> { CcittEncodeOptions(k = -1, endOfLine = true) }
        fun options(columns: Int, rows: Int, eol: Boolean = false) = CcittOptions(columns, rows, true, false, false, eol)
        for ((label, call) in listOf<Pair<String, () -> Unit>>(
            "no columns" to { CcittFax.encode(ByteArray(4), -1, options(0, 1)) },
            "too wide" to { CcittFax.encode(ByteArray(8193), -1, options(65537, 1)) },
            "negative rows" to { CcittFax.encode(ByteArray(4), -1, options(8, -1)) },
            "short input" to { CcittFax.encode(ByteArray(4), -1, options(9, 3)) },
            "EOL in Group 4" to { CcittFax.encode(ByteArray(4), -1, options(8, 1, eol = true)) },
            "too wide a bitmap" to { ImageKodec.encodeCcitt(KiteBitmap(65537, 1, IntArray(65537))) },
        )) {
            val e = assertFailsWith<IllegalArgumentException>(label) { call() }
            assertTrue(e.message!!.startsWith("CCITT"), "$label: ${e.message}")
        }
        // The widest row is accepted and comes back.
        val wide = CcittFax.encode(ByteArray(8192), -1, options(65536, 1))
        assertContentEquals(ByteArray(8192), CcittFax.decode(wide, -1, options(65536, 1)))
    }

    @Test
    fun theOutputIsTheSameOnEveryTarget() {
        val page = BilevelPages.text(1728, 120, 21).bitmap()
        val hashes = listOf(
            CcittEncodeOptions() to 3439856337545416903L,
            CcittEncodeOptions(k = 0, endOfLine = true) to -1349286065561360492L,
            CcittEncodeOptions(k = 4, endOfLine = true) to -4979241365044757848L,
            CcittEncodeOptions(k = 0, encodedByteAlign = true, endOfBlock = false) to 3227018762326018831L,
        )
        val got = hashes.map { (options, _) -> BilevelPages.fnv(ImageKodec.encodeCcitt(page, options)) }
        for ((i, pair) in hashes.withIndex()) assertEquals(pair.second, got[i], pair.first.toString())
    }

    @Test
    fun theCancellationCheckStopsTheEncode() {
        class Stop : RuntimeException()
        val page = BilevelPages.text(300, 200, 2).bitmap()
        var calls = 0
        assertFailsWith<Stop> {
            ImageKodec.encodeCcitt(page, CcittEncodeOptions()) { if (++calls == 3) throw Stop() }
        }
        assertEquals(3, calls)
        var all = 0
        ImageKodec.encodeCcitt(page, CcittEncodeOptions()) { all++ }
        assertTrue(all >= 4, "the check runs every 64 rows, $all times")
    }
}
