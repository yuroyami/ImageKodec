package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.Jbig2Decoder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * JBIG2 data carries no size in the PDF, so the caller passes the width and height, and every
 * segment header states sizes of its own. None of them may be trusted.
 */
class Jbig2DecoderTest {

    /** One segment: number 0, the [type], no referred-to segments, page 1, then [data]. */
    private fun segment(type: Int, data: ByteArray) =
        beBytes(0) + byteArrayOf(type.toByte(), 0, 1) + beBytes(data.size) + data

    /** A page information segment (type 48) for a [width] by [height] page. */
    private fun page(width: Int, height: Int) =
        segment(48, beBytes(width) + beBytes(height) + beBytes(0) + beBytes(0) + byteArrayOf(0, 0, 0))

    @Test
    fun aBlankPageDecodes() {
        assertNotNull(Jbig2Decoder.decode(page(8, 8), null, 8, 8))
    }

    @Test
    fun aSizeThatIsNotPositiveOrPastTheCeilingIsNull() {
        val page = page(8, 8)
        assertNull(Jbig2Decoder.decode(page, null, 0, 8))
        assertNull(Jbig2Decoder.decode(page, null, 8, 0))
        assertNull(Jbig2Decoder.decode(page, null, 8, -1))
        assertNull(Jbig2Decoder.decode(page, null, 1 shl 20, 1 shl 20))
    }

    @Test
    fun aPageInformationSegmentThatClaimsMoreThanTheCeilingCostsOnlyTheCallersPage() {
        // 2^20 by 2^10 is 2^30 pixels, one byte each: a gigabyte for a 30-byte stream. Only the
        // caller's 8 by 8 pixels are read out, so only they are kept (#102).
        assertContentEquals(ByteArray(8) { -1 }, Jbig2Decoder.decode(page(1 shl 20, 1 shl 10), null, 8, 8))
    }

    @Test
    fun aRegionFarWiderThanThePageIsRefused() {
        // Region information: 2^20 by 4 at the origin, then generic flags, template 0 and its four
        // adaptive pixels. Every column of a row moves the arithmetic decoder, so the width could not
        // be cut to the page, and decoding it would cost 4 million pixels for a 64-pixel page.
        val region = beBytes(1 shl 20) + beBytes(4) + beBytes(0) + beBytes(0) + byteArrayOf(0, 0) +
            byteArrayOf(3, -1, -3, -1, 2, -2, -2, -2) + ByteArray(16)
        val stream = page(8, 8) + segment(38, region)
        assertNull(Jbig2Decoder.decode(stream, null, 8, 8))
        assertFailsWith<ImageDecodeException> { Jbig2Decoder.decodeChecked(stream, null, 8, 8) }
        // Four times the page and 1024 more is still decoded.
        val wide = beBytes(4 * 8 + 1024) + beBytes(4) + beBytes(0) + beBytes(0) + byteArrayOf(0, 0) +
            byteArrayOf(3, -1, -3, -1, 2, -2, -2, -2) + ByteArray(16)
        assertNotNull(Jbig2Decoder.decodeChecked(page(8, 8) + segment(38, wide), null, 8, 8))
    }

    @Test
    fun aSegmentHeaderCutOffInsideItsReferenceListIsNull() {
        // The flags byte says five referred-to segments, and the data ends before the page number. An
        // unchecked reader indexes past the array, which is a trap on webassembly, not an exception.
        val header = byteArrayOf(0, 0, 0, 0, 0x30, 0xA0.toByte(), 0, 0, 0, 0, 0)
        assertNull(Jbig2Decoder.decode(header, null, 8, 8))
    }

    @Test
    fun aGenericRegionFromJbig2encDecodesExactly() {
        for ((name, stream) in listOf("template 0" to GENERIC, "with TPGDON" to GENERIC_TPGD)) {
            val page = Jbig2Decoder.decode(hex(stream), null, Jbig2Page.WIDTH, Jbig2Page.HEIGHT)
            assertNotNull(page, name)
            assertContentEquals(Jbig2Page.packed(), page, name)
        }
    }

    @Test
    fun aSymbolDictionaryAndATextRegionFromJbig2encDecodeExactly() {
        val page = Jbig2Decoder.decode(hex(TEXT_PAGE), hex(SYMBOL_DICT), Jbig2Page.WIDTH, Jbig2Page.HEIGHT)
        assertNotNull(page)
        assertContentEquals(hex(SYMBOL_PAGE_BY_JBIG2DEC), page)
    }

    @Test
    fun anMmrGenericRegionDecodesExactly() {
        // Group 4 data that ImageIO wrote for a 64 by 24 page of bars and dots, in an immediate generic
        // region with the MMR flag set: the path JBIG2 shares with the CCITT decoder.
        val page = Jbig2Decoder.decode(hex(JBIG2_MMR), null, 64, 24)
        assertNotNull(page)
        for (y in 0 until 24) for (x in 0 until 64) {
            val ink = (x in 4..9 && y in 3..20) || (y in 10..12 && x in 14..40) ||
                ((x + y) % 11 == 0 && x > 44) || (x in 20..30 && y in 16..19 && (x + y) % 2 == 0)
            // A set bit is white.
            assertEquals(!ink, (page[y * 8 + x / 8].toInt() ushr (7 - x % 8)) and 1 == 1, "($x, $y)")
        }
    }

    /**
     * Random coded data behind a valid symbol dictionary header. Before the export runs were bounded,
     * 12 random bytes were enough to keep the decoder in the export walk for good: seed 29 with 3 new
     * symbols, one of the 1,200 here, never finished. `Jbig2WorkTest` runs it and two more against a
     * clock.
     */
    @Test
    fun aSymbolDictionaryOfRandomCodedDataEnds() {
        for (template in intArrayOf(0, 2)) for (newSymbols in intArrayOf(0, 1, 3)) for (seed in 0 until 200) {
            Jbig2Decoder.decode(randomSymbolDictionary(template, newSymbols, seed), null, 8, 8)
        }
    }

    /** Flips and cuts in the jbig2enc streams: whatever the damage, the decode returns. */
    @Test
    fun damagedJbig2encStreamsReturn() {
        val streams = listOf(hex(GENERIC) to null, hex(GENERIC_TPGD) to null, hex(TEXT_PAGE) to hex(SYMBOL_DICT))
        val rnd = Random(40)
        for ((data, globals) in streams) {
            for (cut in data.indices) Jbig2Decoder.decode(data.copyOf(cut), globals, Jbig2Page.WIDTH, Jbig2Page.HEIGHT)
            repeat(300) {
                val bad = data.copyOf()
                repeat(1 + rnd.nextInt(4)) { bad[rnd.nextInt(bad.size)] = rnd.nextInt().toByte() }
                Jbig2Decoder.decode(bad, globals, Jbig2Page.WIDTH, Jbig2Page.HEIGHT)
            }
            if (globals != null) repeat(300) {
                val bad = globals.copyOf()
                repeat(1 + rnd.nextInt(4)) { bad[rnd.nextInt(bad.size)] = rnd.nextInt().toByte() }
                Jbig2Decoder.decode(data, bad, Jbig2Page.WIDTH, Jbig2Page.HEIGHT)
            }
        }
    }
}

/**
 * Segment 1, a symbol dictionary: arithmetic, generic [template], 5 symbols to export, [newSymbols]
 * new ones, then 12 bytes of coded data drawn from [seed]. Kotlin's seeded [Random] gives the same
 * bytes on every target.
 */
internal fun randomSymbolDictionary(template: Int, newSymbols: Int, seed: Int): ByteArray {
    val rnd = Random(seed)
    val at = if (template == 0) byteArrayOf(3, -1, -3, -1, 2, -2, -2, -2) else byteArrayOf(2, -1)
    val body = byteArrayOf(0, (template shl 2).toByte()) + at + beBytes(5) + beBytes(newSymbols) +
        ByteArray(12) { rnd.nextInt().toByte() }
    return beBytes(1) + byteArrayOf(0, 0, 1) + beBytes(body.size) + body
}

// jbig2enc 0.29 (Apache-2.0) output for [Jbig2Page], PDF-ready (`-p`, no file header).
// `jbig2 -p page.png`: page information, one generic region, end of page.
/**
 * A page information segment, an immediate generic region whose data is the Group 4 coding ImageIO
 * writes for a 64 by 24 bitmap, with the MMR flag set, and an end of page.
 */
internal val JBIG2_MMR = "00000000300001000000130000004000000018000000000000000000000000000001260001000000590000004000000018000000000000000000012b0a9529b2497492e925d24ba497492e925cd865a497c52fa5c52e92e925c9d11d11d11d11d11d24976924924924ba4924924474925da492492492e2222292514928a54a0020020000000231000100000000"

internal val GENERIC = "00000000300001000000130000009d0000003d000000000000000001000000000001260001000000b80000009d000000" +
    "3d0000000000000000000003fffdff02fefefeaa635993f776d4a7911ae9ccbfa07924665e710bf791da6c62e042a06f" +
    "4870847b0f3142601076da0acf3ba4fa0a79a4fac2e17a0dc1a8566a2d420bb0915b2bb2964289383dc4b027af30c759" +
    "adcfaa567d5b708c86c9a011a033c24609bd56c95adc89ad0f196515fe81c8d030d9e96b0754a1441ab3c10ab18c2517" +
    "0ed3ba1cae522afb239d6e7c4ce111526f3d7e633723ddd7221db62d68b63fffac"

// `jbig2 -p -d page.png`: the same with typical prediction (TPGDON).
internal val GENERIC_TPGD = "00000000300001000000130000009d0000003d000000000000000001000000000001260001000000b50000009d000000" +
    "3d0000000000000000000803fffdff02fefefea9aa07ca0159c128891ba27c1a0e344cccaae220e8d489eb8cbdaba042" +
    "3fda0817d53d48fba2d4dfb5e5c7cf5e6236201dd362d8cf4c0c6e3beed2eaf95b7787d0d6e282c34d11b3b8cc8a3218" +
    "9e213ec746135eb7d9936b628a7fff7fff7ac56085484f6bde3b7ff8ca163d42ccc45a54795990ef3abd84664c88ebeb" +
    "fb99ae8514dfb12beea1bd863682e7ab9124c9e3f30aec53e1b5653fffac"

// `jbig2 -s -p -b sym page.png`: the global symbol dictionary (sym.sym) of 5 symbols...
internal val SYMBOL_DICT = "000000000001000000003e000003fffdff02fefefe00000005000000054259e6d359ba8fa513e575a3975718dee84bd8" +
    "3ed51856824d4eb35d42db0a895bdd41d76b93ae640d9fffac"

// ...and the page stream (sym.0000): page information and a text region that places them, some a
// pixel away from where [Jbig2Page] has them.
internal val TEXT_PAGE = "00000001300001000000130000009d0000003d00000000000000000000000000000206220001000000320000009d0000" +
    "003d00000000000000000000000000001b9eec5176accec0a5a77736db94906f3bcfd094dcc49016d931ffac"

// jbig2dec 0.20 (`jbig2dec -e -t pbm sym.sym sym.0000`) on the two streams above, inverted to a set
// bit white and with the row padding cleared, the layout [Jbig2Decoder] returns.
private val SYMBOL_PAGE_BY_JBIG2DEC = "fffffffffffffffffffffffffffffffffffffff8fffffffffffffffffffffffffffffffffffffff8ffffffffffffffff" +
    "fffffffffffffffffffffff8e003f8fffc007ff7fff7ff800fc7ffe003ffbff8effbf8ffffeffff7ffe3ffbfefc7ffff" +
    "7fffbff8effbf8ffffeffff7ffc1ffbfefc7ffff7fffbff8effbf8ffffeffff7ff80ffbfefc7ffff7fffbff8effbf8ff" +
    "ffeffff7ff007fbfefc7ffff7fffbff8effbf8ffffeffe003e003fbfefc7ffff7ff001f8effbf8ffffeffff7ff007fbf" +
    "efc7ffff7fffbff8effbf8ffffeffff7ff80ffbfefc7ffff7fffbff8effbf800ffeffff7ffc1ffbfefc007ff7fffbff8" +
    "effbf800ffeffff7ffe3ffbfefc007ff7fffbff8e003f800ffeffff7fff7ff800fc007ff7fffbff8ffffffffffffffff" +
    "fffffffffffffffffffffff8fffffffffffffffffffffffffffffffffffffff8ffffffffffffffffffffffffffffffff" +
    "fffffff8fffffffffffffffffffffffffffffffffffffff8fffffffffffffffffffffffffffffffffffffff8ffffffff" +
    "fffffffffffffffffffffffffffffff8fffffffffffffffffffffffffffffffffffffff8ffffffffffffffffffffffff" +
    "fffffffffffffff8fffffffffffffffffffffffffffffffffffffff8e003ffdfffeffe003f1fff800ffeffff7ff001f8" +
    "ff7fffdfffc7feffbf1ffffdfffefffe3ff7fdf8ff7fffdfff83feffbf1ffffdfffefffc1ff7fdf8ff7fffdfff01feff" +
    "bf1ffffdfffefff80ff7fdf8ff7fffdffe00feffbf1ffffdfffefff007f7fdf8ff7ff800fc007effbf1ffffdffc007e0" +
    "03f7fdf8ff7fffdffe00feffbf1ffffdfffefff007f7fdf8ff7fffdfff01feffbf1ffffdfffefff80ff7fdf8ff7fffdf" +
    "ff83feffbf001ffdfffefffc1ff7fdf8ff7fffdfffc7feffbf001ffdfffefffe3ff7fdf8ff7fffdfffeffe003f001ffd" +
    "fffeffff7ff001f8fffffffffffffffffffffffffffffffffffffff8fffffffffffffffffffffffffffffffffffffff8" +
    "fffffffffffffffffffffffffffffffffffffff8fffffffffffffffffffffffffffffffffffffff8ffffffffffffffff" +
    "fffffffffffffffffffffff8fffffffffffffffffffffffffffffffffffffff8ffffffffffffffffffffffffffffffff" +
    "fffffff8ff7ffffffffffffffffffffffffffffffffffff8fe3ff800fc7ffe003ffbfffdffc007e3fff001f8fc1ffbfe" +
    "fc7ffff7fffbfff8ffdff7e3ffffbff8f80ffbfefc7ffff7fffbfff07fdff7e3ffffbff8f007fbfefc7ffff7fffbffe0" +
    "3fdff7e3ffffbff8e003fbfefc7ffff7fffbffc01fdff7e3ffffbff8f007fbfefc7ffff7ff001f800fdff7e3ffffbff8" +
    "f80ffbfefc7ffff7fffbffc01fdff7e3ffffbff8fc1ffbfefc7ffff7fffbffe03fdff7e3ffffbff8fe3ffbfefc007ff7" +
    "fffbfff07fdff7e003ffbff8ff7ffbfefc007ff7fffbfff8ffdff7e003ffbff8fffff800fc007ff7fffbfffdffc007e0" +
    "03ffbff8fffffffffffffffffffffffffffffffffffffff8fffffffffffffffffffffffffffffffffffffff8ffffffff" +
    "fffffffffffffffffffffffffffffff8fffffffffffffffffffffffffffffffffffffff8ffffffffffffffffffffffff" +
    "fffffffffffffff8fffffffffffffffffffffffffffffffffffffff8fffffffffffffffffffffffffffffffffffffff8" +
    "fffffffffffffffffffffffffffffffffffffff8"
