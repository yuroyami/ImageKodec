package io.github.yuroyami.imagekodec

/**
 * How [ImageKodec.encodeCcitt] writes fax data. The parameters are the ones PDF's `CCITTFaxDecode`
 * filter and [io.github.yuroyami.imagekodec.codec.CcittFax.decode] take, with PDF's defaults except
 * for [k], so a reader given the same values reads the data back.
 *
 * - [k] selects the coding: negative is Group 4 (ITU-T T.6), the default and the smallest; 0 is
 *   Group 3 one-dimensional (T.4 Modified Huffman); a positive value is Group 3 two-dimensional,
 *   each row coded alone followed by up to `k - 1` rows coded against the row above. T.4 uses 2 at
 *   standard resolution and 4 at fine.
 * - [encodedByteAlign] starts each row on a byte, its EOL included. TIFF compression 2 is Group 3
 *   one-dimensional with this set and no EOL.
 * - [blackIs1] is the polarity the reader is told: it does not change the coded data.
 * - [endOfLine] writes an EOL before each Group 3 row, as TIFF compression 3 and fax machines expect.
 *   T.6 has no EOL, so Group 4 refuses it.
 * - [endOfBlock] ends the data with EOFB in Group 4 and RTC in Group 3, so a reader given no row
 *   count knows where the page ends.
 *
 * @throws IllegalArgumentException if [k] is negative and [endOfLine] is set
 */
public class CcittEncodeOptions @Throws(IllegalArgumentException::class) public constructor(
    public val k: Int = -1,
    public val encodedByteAlign: Boolean = false,
    public val blackIs1: Boolean = false,
    public val endOfLine: Boolean = false,
    public val endOfBlock: Boolean = true,
) {
    init {
        require(k >= 0 || !endOfLine) { "CCITT: Group 4 (k < 0) has no EOL, so endOfLine must be false" }
    }

    override fun toString(): String =
        "CcittEncodeOptions(k=$k, encodedByteAlign=$encodedByteAlign, blackIs1=$blackIs1, " +
            "endOfLine=$endOfLine, endOfBlock=$endOfBlock)"
}

/**
 * How [ImageKodec.encodeJbig2] writes a page. Every choice is lossless: it changes the size and the
 * time, never the pixels.
 *
 * - [template] is the generic region template of ITU-T T.88 6.2.5.3, from 0 to 3, with its nominal
 *   adaptive pixels. Template 0 looks at 16 pixels around each one and codes text smallest; 1, 2 and
 *   3 look at 13, 10 and 10 and code faster with fewer contexts to learn.
 * - [typicalPrediction] (TPGDON, T.88 6.2.5.7) codes a row equal to the row above in one bit. It
 *   makes the blank rows of a page nearly free and costs one bit a row elsewhere.
 * - [mmr] codes the region with T.6 (Group 4) instead of the arithmetic coder. It is faster to read
 *   and larger, and it leaves [template] and [typicalPrediction] unused.
 * - [standalone] writes a JBIG2 file (T.88 Annex D, sequential organisation), with its file header
 *   and its end-of-page and end-of-file segments. The default is the embedded stream that PDF's
 *   `JBIG2Decode` filter takes, which has none of them and needs no global stream.
 *
 * @throws IllegalArgumentException if [template] is outside 0 to 3
 */
public class Jbig2EncodeOptions @Throws(IllegalArgumentException::class) public constructor(
    public val template: Int = 0,
    public val typicalPrediction: Boolean = true,
    public val mmr: Boolean = false,
    public val standalone: Boolean = false,
) {
    init {
        require(template in 0..3) { "JBIG2: template $template is outside 0 to 3" }
    }

    override fun toString(): String =
        "Jbig2EncodeOptions(template=$template, typicalPrediction=$typicalPrediction, mmr=$mmr, standalone=$standalone)"
}
