package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.imagekodec.ImageDecodeException
import io.github.yuroyami.imagekodec.UnsupportedImageException
import io.github.yuroyami.imagekodec.internal.Budget

/*
 * CCITT Group 3 / Group 4 (ITU-T T.4 / T.6) facsimile decoding: moved from
 * KitePDF's `/CCITTFaxDecode` filter (the algorithm half; the PdfDictionary
 * parameter-parsing glue stayed behind). Also the compression used by TIFF
 * compressions 2/3/4, which is why it lives in ImageKodec now. JBIG2's MMR
 * regions reuse [decodeGroup4] directly.
 */

/**
 * CCITT fax decoding. Bilevel scan-world compression: there is no standalone
 * container/magic for it, so this is a parameterized codec API, not part of
 * [io.github.yuroyami.imagekodec.ImageKodec.decode]'s sniffing.
 *
 * Output is 1 bit per pixel, packed MSB-first, padded to a byte boundary per
 * row; polarity follows [CcittOptions.blackIs1].
 */
public object CcittFax {
    /**
     * Decode with the mode selection PDF's `/K` uses: negative = pure 2D
     * (Group 4, T.6), zero = pure 1D (Group 3, T.4), positive = mixed
     * 1D/2D (Group 3, T.4). Positive values share the per-row mode-tag
     * interpretation specified by PDF; their numerical cadence is not enforced.
     *
     * @throws ImageDecodeException if [CcittOptions.columns] is outside 1 to 2^24, if
     *   [CcittOptions.rows] is negative, the decoded image would pass 2^28 pixels,
     *   or a Group 3 row is invalid or incomplete
     */
    @Throws(ImageDecodeException::class)
    public fun decode(input: ByteArray, k: Int, options: CcittOptions): ByteArray {
        val reader = BitReader(input)
        return when {
            k < 0 -> decodeGroup4(reader, options)
            k == 0 -> decodeGroup3OneD(reader, options)
            else -> decodeGroup3Mixed(reader, options)
        }
    }
}

/**
 * The parameters fax data does not carry. [columns] is the row width in pixels. [rows] is the
 * row count, or 0 when it is not known and the decode runs until the data ends.
 */
public data class CcittOptions(
    val columns: Int,
    val rows: Int,
    val endOfBlock: Boolean,
    val blackIs1: Boolean,
    val encodedByteAlign: Boolean,
    val endOfLine: Boolean,
)

/* ─── Bit reader (MSB-first) ──────────────────────────────────────────────── */

internal class BitReader(private val bytes: ByteArray) {
    private var bytePos = 0
    private var bitPos = 0   // 0..7, 0 = MSB

    val bitsConsumed: Long get() = bytePos.toLong() * 8 + bitPos

    val bitsLeft: Long get() = maxOf(0L, bytes.size.toLong() * 8 - bitsConsumed)

    fun atEnd(): Boolean = bytePos >= bytes.size

    fun hasBits(n: Int): Boolean = n <= (bytes.size.toLong() - bytePos) * 8 - bitPos

    fun onlyBytePadding(): Boolean = bitPos > 0 && bytePos == bytes.lastIndex && peekBits(8 - bitPos) == 0

    /** Reposition the reader to a previously-recorded (bytePos, bitPos) pair. */
    fun seek(newBytePos: Int, newBitPos: Int) {
        bytePos = newBytePos
        bitPos = newBitPos
    }

    /** Read one bit; returns 0 if past end (terminating safely on truncation). */
    fun readBit(): Int {
        if (bytePos >= bytes.size) return 0
        val b = bytes[bytePos].toInt() and 0xFF
        val bit = (b ushr (7 - bitPos)) and 1
        bitPos++
        if (bitPos == 8) { bitPos = 0; bytePos++ }
        return bit
    }

    fun alignToByte() {
        if (bitPos != 0) { bitPos = 0; bytePos++ }
    }

    /** Peek the next [n] bits (n ≤ 24) without advancing; bits past the end read as 0. */
    fun peekBits(n: Int): Int {
        if (n == 0) return 0
        // Four bytes hold the at most 7 bits already read from the first one and 25 more.
        val word = (byteAt(bytePos) shl 24) or (byteAt(bytePos + 1) shl 16) or
            (byteAt(bytePos + 2) shl 8) or byteAt(bytePos + 3)
        return (word shl bitPos) ushr (32 - n)
    }

    private fun byteAt(i: Int): Int = if (i < bytes.size) bytes[i].toInt() and 0xFF else 0

    fun skipBits(n: Int) {
        bitPos += n
        bytePos += bitPos / 8
        bitPos %= 8
    }
}

/* ─── Modified Huffman tables ─────────────────────────────────────────────── */

/**
 * A code table for white/black runs or 2D codes. Entries are `(code,
 * codeLength, value)` triples (the code occupies the high `codeLength` bits,
 * MSB-first as CCITT packs them). Built once into a flat direct-lookup table
 * indexed by the next [maxLength] bits peeked from the stream, so a code
 * decodes in one peek + one index instead of a bit-by-bit cohort scan.
 * `lenTable[i] == 0` marks an index no code reaches → no-match.
 */
internal class HuffmanTable private constructor(
    private val valTable: IntArray,
    private val lenTable: IntArray,
    val maxLength: Int,
) {

    /**
     * Decode the next code; returns the value, or -1 on no-match. Advances the
     * reader by the matched code length, and not at all on no-match, where
     * every caller aborts the run or the row.
     */
    fun decode(reader: BitReader): Int {
        val peek = reader.peekBits(maxLength)
        val len = lenTable[peek]
        if (len == 0 || !reader.hasBits(len)) return -1
        reader.skipBits(len)
        return valTable[peek]
    }

    companion object {
        fun build(entries: IntArray): HuffmanTable {
            // entries are packed as triples: code, len, value.
            require(entries.size % 3 == 0)
            var maxLen = 0
            var i = 0
            while (i < entries.size) {
                if (entries[i + 1] > maxLen) maxLen = entries[i + 1]
                i += 3
            }
            val size = 1 shl maxLen
            val valTable = IntArray(size)
            val lenTable = IntArray(size)  // 0 = no code reaches this index
            i = 0
            while (i < entries.size) {
                val code = entries[i]
                val len = entries[i + 1]
                val value = entries[i + 2]
                // Stamp every index whose top `len` bits equal this code. A code that is a prefix of
                // another would make one of them unreachable, so the tables must not hold one.
                val base = code shl (maxLen - len)
                val span = 1 shl (maxLen - len)
                for (j in 0 until span) {
                    check(lenTable[base + j] == 0) { "CCITT code ${code.toString(2)} of length $len overlaps another" }
                    lenTable[base + j] = len
                    valTable[base + j] = value
                }
                i += 3
            }
            return HuffmanTable(valTable, lenTable, maxLen)
        }
    }
}

/** Tables built from ITU-T T.4 and T.6 (the public CCITT recommendations). */
@Suppress("LongMethod")  // table data, not branching
internal object CcittFaxTables {

    /** Run-length value sentinel returned by makeup tables to mean "EOL". */
    const val VALUE_EOL = -2

    /**
     * Modified Huffman White-run terminators (run lengths 0..63).
     * Entries: code, length, runLength.
     */
    private val WHITE_TERMINATORS = intArrayOf(
        0b00110101, 8, 0,
        0b000111, 6, 1,
        0b0111, 4, 2,
        0b1000, 4, 3,
        0b1011, 4, 4,
        0b1100, 4, 5,
        0b1110, 4, 6,
        0b1111, 4, 7,
        0b10011, 5, 8,
        0b10100, 5, 9,
        0b00111, 5, 10,
        0b01000, 5, 11,
        0b001000, 6, 12,
        0b000011, 6, 13,
        0b110100, 6, 14,
        0b110101, 6, 15,
        0b101010, 6, 16,
        0b101011, 6, 17,
        0b0100111, 7, 18,
        0b0001100, 7, 19,
        0b0001000, 7, 20,
        0b0010111, 7, 21,
        0b0000011, 7, 22,
        0b0000100, 7, 23,
        0b0101000, 7, 24,
        0b0101011, 7, 25,
        0b0010011, 7, 26,
        0b0100100, 7, 27,
        0b0011000, 7, 28,
        0b00000010, 8, 29,
        0b00000011, 8, 30,
        0b00011010, 8, 31,
        0b00011011, 8, 32,
        0b00010010, 8, 33,
        0b00010011, 8, 34,
        0b00010100, 8, 35,
        0b00010101, 8, 36,
        0b00010110, 8, 37,
        0b00010111, 8, 38,
        0b00101000, 8, 39,
        0b00101001, 8, 40,
        0b00101010, 8, 41,
        0b00101011, 8, 42,
        0b00101100, 8, 43,
        0b00101101, 8, 44,
        0b00000100, 8, 45,
        0b00000101, 8, 46,
        0b00001010, 8, 47,
        0b00001011, 8, 48,
        0b01010010, 8, 49,
        0b01010011, 8, 50,
        0b01010100, 8, 51,
        0b01010101, 8, 52,
        0b00100100, 8, 53,
        0b00100101, 8, 54,
        0b01011000, 8, 55,
        0b01011001, 8, 56,
        0b01011010, 8, 57,
        0b01011011, 8, 58,
        0b01001010, 8, 59,
        0b01001011, 8, 60,
        0b00110010, 8, 61,
        0b00110011, 8, 62,
        0b00110100, 8, 63,
    )

    /** Modified Huffman White-run makeups (64, 128, 192, ...). */
    private val WHITE_MAKEUPS = intArrayOf(
        0b11011, 5, 64,
        0b10010, 5, 128,
        0b010111, 6, 192,
        0b0110111, 7, 256,
        0b00110110, 8, 320,
        0b00110111, 8, 384,
        0b01100100, 8, 448,
        0b01100101, 8, 512,
        0b01101000, 8, 576,
        0b01100111, 8, 640,
        0b011001100, 9, 704,
        0b011001101, 9, 768,
        0b011010010, 9, 832,
        0b011010011, 9, 896,
        0b011010100, 9, 960,
        0b011010101, 9, 1024,
        0b011010110, 9, 1088,
        0b011010111, 9, 1152,
        0b011011000, 9, 1216,
        0b011011001, 9, 1280,
        0b011011010, 9, 1344,
        0b011011011, 9, 1408,
        0b010011000, 9, 1472,
        0b010011001, 9, 1536,
        0b010011010, 9, 1600,
        0b011000, 6, 1664,
        0b010011011, 9, 1728,
        // 1792+ extension codes (shared semantics with black).
        0b00000001000, 11, 1792,
        0b00000001100, 11, 1856,
        0b00000001101, 11, 1920,
        0b000000010010, 12, 1984,
        0b000000010011, 12, 2048,
        0b000000010100, 12, 2112,
        0b000000010101, 12, 2176,
        0b000000010110, 12, 2240,
        0b000000010111, 12, 2304,
        0b000000011100, 12, 2368,
        0b000000011101, 12, 2432,
        0b000000011110, 12, 2496,
        0b000000011111, 12, 2560,
        // EOL: 11 zeros + 1 = 12 bits total.
        0b000000000001, 12, VALUE_EOL,
    )

    /** Modified Huffman Black-run terminators (run lengths 0..63). */
    private val BLACK_TERMINATORS = intArrayOf(
        0b0000110111, 10, 0,
        0b010, 3, 1,
        0b11, 2, 2,
        0b10, 2, 3,
        0b011, 3, 4,
        0b0011, 4, 5,
        0b0010, 4, 6,
        0b00011, 5, 7,
        0b000101, 6, 8,
        0b000100, 6, 9,
        0b0000100, 7, 10,
        0b0000101, 7, 11,
        0b0000111, 7, 12,
        0b00000100, 8, 13,
        0b00000111, 8, 14,
        0b000011000, 9, 15,
        0b0000010111, 10, 16,
        0b0000011000, 10, 17,
        0b0000001000, 10, 18,
        0b00001100111, 11, 19,
        0b00001101000, 11, 20,
        0b00001101100, 11, 21,
        0b00000110111, 11, 22,
        0b00000101000, 11, 23,
        0b00000010111, 11, 24,
        0b00000011000, 11, 25,
        0b000011001010, 12, 26,
        0b000011001011, 12, 27,
        0b000011001100, 12, 28,
        0b000011001101, 12, 29,
        0b000001101000, 12, 30,
        0b000001101001, 12, 31,
        0b000001101010, 12, 32,
        0b000001101011, 12, 33,
        0b000011010010, 12, 34,
        0b000011010011, 12, 35,
        0b000011010100, 12, 36,
        0b000011010101, 12, 37,
        0b000011010110, 12, 38,
        0b000011010111, 12, 39,
        0b000001101100, 12, 40,
        0b000001101101, 12, 41,
        0b000011011010, 12, 42,
        0b000011011011, 12, 43,
        0b000001010100, 12, 44,
        0b000001010101, 12, 45,
        0b000001010110, 12, 46,
        0b000001010111, 12, 47,
        0b000001100100, 12, 48,
        0b000001100101, 12, 49,
        0b000001010010, 12, 50,
        0b000001010011, 12, 51,
        0b000000100100, 12, 52,
        0b000000110111, 12, 53,
        0b000000111000, 12, 54,
        0b000000100111, 12, 55,
        0b000000101000, 12, 56,
        0b000001011000, 12, 57,
        0b000001011001, 12, 58,
        0b000000101011, 12, 59,
        0b000000101100, 12, 60,
        0b000001011010, 12, 61,
        0b000001100110, 12, 62,
        0b000001100111, 12, 63,
    )

    /** Modified Huffman Black-run makeups (64, 128, 192, ...). */
    private val BLACK_MAKEUPS = intArrayOf(
        0b0000001111, 10, 64,
        0b000011001000, 12, 128,
        0b000011001001, 12, 192,
        0b000001011011, 12, 256,
        0b000000110011, 12, 320,
        0b000000110100, 12, 384,
        0b000000110101, 12, 448,
        0b0000001101100, 13, 512,
        0b0000001101101, 13, 576,
        0b0000001001010, 13, 640,
        0b0000001001011, 13, 704,
        0b0000001001100, 13, 768,
        0b0000001001101, 13, 832,
        0b0000001110010, 13, 896,
        0b0000001110011, 13, 960,
        0b0000001110100, 13, 1024,
        0b0000001110101, 13, 1088,
        0b0000001110110, 13, 1152,
        0b0000001110111, 13, 1216,
        0b0000001010010, 13, 1280,
        0b0000001010011, 13, 1344,
        0b0000001010100, 13, 1408,
        0b0000001010101, 13, 1472,
        0b0000001011010, 13, 1536,
        0b0000001011011, 13, 1600,
        0b0000001100100, 13, 1664,
        0b0000001100101, 13, 1728,
        // Shared 1792+ extension codes.
        0b00000001000, 11, 1792,
        0b00000001100, 11, 1856,
        0b00000001101, 11, 1920,
        0b000000010010, 12, 1984,
        0b000000010011, 12, 2048,
        0b000000010100, 12, 2112,
        0b000000010101, 12, 2176,
        0b000000010110, 12, 2240,
        0b000000010111, 12, 2304,
        0b000000011100, 12, 2368,
        0b000000011101, 12, 2432,
        0b000000011110, 12, 2496,
        0b000000011111, 12, 2560,
        0b000000000001, 12, VALUE_EOL,
    )

    /**
     * Every white run code: the terminating codes, the makeup codes and EOL. T.4 makes them one prefix
     * code, so each code of a run is one lookup. A value of 64 or more is a makeup code, and more codes
     * of the run follow it.
     */
    val whiteRuns: HuffmanTable = HuffmanTable.build(WHITE_TERMINATORS + WHITE_MAKEUPS)

    /** Every black run code, as [whiteRuns] holds the white ones. */
    val blackRuns: HuffmanTable = HuffmanTable.build(BLACK_TERMINATORS + BLACK_MAKEUPS)

    /** 2D modes for G4 / G3-2D coding lines (T.6 §2.3, T.4 §2.6). */
    const val MODE_PASS = 0
    const val MODE_HORIZONTAL = 1
    const val MODE_V0 = 2
    const val MODE_VL1 = 3
    const val MODE_VL2 = 4
    const val MODE_VL3 = 5
    const val MODE_VR1 = 6
    const val MODE_VR2 = 7
    const val MODE_VR3 = 8
    const val MODE_EXTENSION = 9

    val twoDimensional: HuffmanTable = HuffmanTable.build(
        intArrayOf(
            0b1, 1, MODE_V0,
            0b011, 3, MODE_VR1,
            0b010, 3, MODE_VL1,
            0b001, 3, MODE_HORIZONTAL,
            0b0001, 4, MODE_PASS,
            0b000011, 6, MODE_VR2,
            0b000010, 6, MODE_VL2,
            0b0000011, 7, MODE_VR3,
            0b0000010, 7, MODE_VL3,
            0b0000001, 7, MODE_EXTENSION,
        ),
    )
}

/* ─── 1D run decoder ──────────────────────────────────────────────────────── */

/**
 * Decode one run (white or black). Walks the makeup chain: a run > 63 is
 * encoded as one or more makeup codes (each a multiple of 64) followed by
 * a terminator (0..63). Sum of all of those is the run length.
 *
 * Returns the run length, or -1 if decoding failed. Returns Int.MIN_VALUE
 * on EOL (caller decides whether that's expected).
 */
internal fun decodeRun(reader: BitReader, isWhite: Boolean): Int {
    val codes = if (isWhite) CcittFaxTables.whiteRuns else CcittFaxTables.blackRuns
    var total = 0
    repeat(100) {
        val value = codes.decode(reader)
        if (value == CcittFaxTables.VALUE_EOL) return Int.MIN_VALUE
        if (value < 0) return -1
        total += value
        if (value < 64) return total
    }
    return -1
}

/* ─── Rows as changing elements ───────────────────────────────────────────── */

/**
 * One row as its changing elements, as T.4 and T.6 define them: the columns whose pixel differs in
 * colour from the pixel before it, the imaginary pixel before column 0 being white. A change at an
 * even index turns the row black and one at an odd index turns it white. Three copies of the row
 * width follow the last change, so a search for the next change of either colour stops there.
 *
 * The decoders build a row with [fill] from left to right, so the work follows the number of
 * changes and not the number of pixels, as in libtiff's and xpdf's fax decoders (#105).
 */
private class Changes(val cols: Int) {
    val at = IntArray(cols + 3)
    var count = 0
        private set
    private var end = 0
    private var color = 0

    /** Starts a new row, with no column decoded yet. */
    fun start() {
        count = 0
        end = 0
        color = 0
    }

    /** Paints the columns from the end of the decoded part until [to] in [color]: 0 white, 1 black. */
    fun fill(to: Int, color: Int) {
        if (to <= end) return
        if (color != this.color) {
            at[count++] = end
            this.color = color
        }
        end = to
    }

    /** Ends the row with its sentinels. */
    fun finish(): Changes {
        at[count] = cols
        at[count + 1] = cols
        at[count + 2] = cols
        return this
    }
}

/**
 * The decoded page, one bit a pixel, MSB first, each row padded with zero bits to a byte. A bit is
 * set for a black pixel when [blackIs1] and for a white one otherwise.
 *
 * Each row costs at least one bit of input, so the page needs no more rows than [bitsLeft] allows,
 * and a declared row count within that sizes the buffer once: the result is that buffer, with no
 * copy, when every declared row decodes.
 */
private class PackedRows(cols: Int, private val blackIs1: Boolean, declaredRows: Int, bitsLeft: Long) {
    private val bytesPerRow = (cols + 7) / 8
    private var buffer = ByteArray(
        bytesPerRow * minOf(if (declaredRows > 0) declaredRows.toLong() else 64L, bitsLeft + 1).toInt(),
    )
    private var rows = 0

    fun add(line: Changes) {
        val base = rows * bytesPerRow
        if (base + bytesPerRow > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, base + bytesPerRow))
        val ink = if (blackIs1) 1 else 0
        val at = line.at
        var start = 0
        var color = 0
        for (k in 0..line.count) {
            val end = at[k]
            if (color == ink) setBits(buffer, base, start, end)
            start = end
            color = color xor 1
        }
        rows++
    }

    fun result(): ByteArray = if (buffer.size == rows * bytesPerRow) buffer else buffer.copyOf(rows * bytesPerRow)
}

/** Sets bits [from] until [to] of the row at [base], whole bytes at a time between the ends. */
private fun setBits(out: ByteArray, base: Int, from: Int, to: Int) {
    if (from >= to) return
    val first = base + (from ushr 3)
    val last = base + ((to - 1) ushr 3)
    val head = 0xFF ushr (from and 7)
    val tail = (0xFF shl (7 - ((to - 1) and 7))) and 0xFF
    if (first == last) {
        out[first] = (out[first].toInt() or (head and tail)).toByte()
        return
    }
    out[first] = (out[first].toInt() or head).toByte()
    if (last > first + 1) out.fill(-1, first + 1, last)
    out[last] = (out[last].toInt() or tail).toByte()
}

/* ─── Group 4 (T.6) decoder ──────────────────────────────────────────────── */

/** Widest row accepted: the ceiling the other decoders use for one side of an image. */
private const val MAX_COLUMNS = 1 shl 24

/**
 * The caller's geometry is a header field like any other, and in a PDF it comes from the file. Fax
 * data can state a row in one bit, so the input size says little about how large the page may be, and
 * the pixel ceiling is what bounds the memory. `Budget.fits` would refuse a blank scanned page.
 */
private fun checkGeometry(opts: CcittOptions) {
    val cols = opts.columns
    if (cols < 1 || cols > MAX_COLUMNS) throw ImageDecodeException("CCITT: $cols columns is outside 1 to $MAX_COLUMNS")
    if (opts.rows < 0) throw ImageDecodeException("CCITT: ${opts.rows} rows")
    if (opts.rows.toLong() * cols > Budget.MAX_PIXELS) {
        throw ImageDecodeException("CCITT: ${opts.rows} rows of $cols columns pass ${Budget.MAX_PIXELS} pixels")
    }
}

/** Throws when one more row, after [rows] decoded ones, would take the page past the pixel ceiling. */
private fun checkRoomForRow(rows: Int, cols: Int) {
    if ((rows + 1).toLong() * cols > Budget.MAX_PIXELS) {
        throw ImageDecodeException("CCITT: more than ${Budget.MAX_PIXELS} pixels of $cols columns")
    }
}

/**
 * Decode Group 4 (T.6) 2D-only encoding. Output is `rows × bytesPerRow`
 * bytes; rows are MSB-packed and zero-padded at the line end.
 */
internal fun decodeGroup4(reader: BitReader, opts: CcittOptions): ByteArray {
    checkGeometry(opts)
    val cols = opts.columns
    // Reference line: implicit all-white (a virtual change at column = cols).
    var reference = Changes(cols).finish()
    var coding = Changes(cols)
    val output = PackedRows(cols, opts.blackIs1, opts.rows, reader.bitsLeft)

    var row = 0
    while (true) {
        if (opts.rows > 0 && row >= opts.rows) break
        if (reader.atEnd()) break
        // EOFB detection: two consecutive EOLs (T.6 §2.2.1). Each EOL is 12 bits.
        if (opts.endOfBlock && peekEofb(reader)) {
            reader.skipBits(24)
            break
        }
        checkRoomForRow(row, cols)
        if (!decodeOneG4Row(reader, reference, coding)) break
        output.add(coding)
        val decoded = coding
        coding = reference
        reference = decoded
        row++
        if (opts.encodedByteAlign) reader.alignToByte()
    }
    return output.result()
}

private fun peekEofb(reader: BitReader): Boolean {
    val v = reader.peekBits(24)
    return v == 0b000000000001000000000001
}

/**
 * Decode one Group 4 row into [coding] against [reference], the previous row, and return whether it
 * decoded. Both rows are lists of changing elements ([Changes]).
 *
 * The decoder walks `a0` (current position on coding line) left-to-right.
 * `b1` is the next changing element on the ref line to the right of `a0`
 * with opposite color to `a0`'s color. `b2` is the next change after `b1`.
 * `a0` never moves left, so neither does the index of the first reference
 * change right of it, and each row reads its reference changes once.
 */
private fun decodeOneG4Row(reader: BitReader, reference: Changes, coding: Changes, strict: Boolean = false): Boolean {
    val cols = coding.cols
    val ref = reference.at
    coding.start()
    var a0 = -1
    var a0Color = 0  // 0 = white (the color of the imaginary element before column 0)
    var safetyHops = 0
    var next = 0     // the first reference change right of a0

    while (a0 < cols) {
        if (safetyHops++ > cols * 4) return false  // pathological; bail

        while (ref[next] <= a0) next++
        // A change at an even index turns the row black, so b1 has the parity of a0's colour.
        val b1Index = if ((next and 1) == a0Color) next else next + 1
        val b1 = ref[b1Index]
        val b2 = ref[b1Index + 1]

        val mode = CcittFaxTables.twoDimensional.decode(reader)
        when (mode) {
            CcittFaxTables.MODE_PASS -> {
                coding.fill(b2, a0Color)
                a0 = b2
                // color unchanged
            }
            CcittFaxTables.MODE_HORIZONTAL -> {
                val r1 = decodeRun(reader, a0Color == 0)
                val r2 = decodeRun(reader, a0Color != 0)
                if (r1 < 0 || r2 < 0) return false
                val start1 = maxOf(a0, 0)
                if (strict && (r1 > cols - start1 || r2 > cols - start1 - r1)) {
                    throw ImageDecodeException("CCITT: horizontal runs exceed $cols pixels")
                }
                val end1 = minOf(cols, start1 + r1)
                coding.fill(end1, a0Color)
                val end2 = minOf(cols, end1 + r2)
                coding.fill(end2, 1 - a0Color)
                a0 = end2
                // After two runs, color is the same as it was before (back to a0Color).
            }
            CcittFaxTables.MODE_V0, CcittFaxTables.MODE_VR1, CcittFaxTables.MODE_VR2,
            CcittFaxTables.MODE_VR3, CcittFaxTables.MODE_VL1, CcittFaxTables.MODE_VL2,
            CcittFaxTables.MODE_VL3 -> {
                val offset = when (mode) {
                    CcittFaxTables.MODE_V0 -> 0
                    CcittFaxTables.MODE_VR1 -> 1
                    CcittFaxTables.MODE_VR2 -> 2
                    CcittFaxTables.MODE_VR3 -> 3
                    CcittFaxTables.MODE_VL1 -> -1
                    CcittFaxTables.MODE_VL2 -> -2
                    else -> -3
                }
                val a1 = b1 + offset
                if (a1 < maxOf(a0, 0) || a1 > cols) {
                    throw ImageDecodeException("CCITT: vertical code moves outside the remaining row")
                }
                coding.fill(a1, a0Color)
                a0 = a1
                a0Color = 1 - a0Color
            }
            CcittFaxTables.MODE_EXTENSION -> {
                if (strict) throw UnsupportedImageException("CCITT: uncompressed 2D extension is not supported")
                // Extension codes signal end-of-page-block or escape sequences.
                // Treat as end of row: emit what we have.
                coding.fill(cols, a0Color)
                coding.finish()
                return true
            }
            else -> return false
        }
    }
    coding.finish()
    return true
}

/* ─── Group 3 1D decoder ──────────────────────────────────────────────────── */

internal fun decodeGroup3OneD(reader: BitReader, opts: CcittOptions): ByteArray {
    checkGeometry(opts)
    val cols = opts.columns
    val line = Changes(cols)
    val output = PackedRows(cols, opts.blackIs1, opts.rows, reader.bitsLeft)
    var rowIndex = 0

    while (true) {
        if (opts.rows > 0 && rowIndex >= opts.rows) break
        if (reader.atEnd() || (opts.rows == 0 && reader.onlyBytePadding())) break
        if (opts.encodedByteAlign) reader.alignToByte()
        if (reader.atEnd()) break
        // An EOL starts with at least eleven zeros; no run code has that prefix.
        // Accept recognizable T.4 framing even without an EndOfLine hint.
        if (opts.endOfLine || reader.peekBits(12) <= 1) consumeOptionalEol(reader)
        checkRoomForRow(rowIndex, cols)
        if (!decodeOneG3Row(reader, line, rowIndex, opts.rows == 0 && opts.endOfBlock)) return output.result()
        output.add(line)
        rowIndex++
    }
    if (opts.rows > 0 && rowIndex != opts.rows) {
        throw ImageDecodeException("CCITT: decoded $rowIndex of ${opts.rows} rows")
    }
    return output.result()
}

/** T.4 EOL tags select a row decoder; PDF does not distinguish positive K values. */
private fun decodeGroup3Mixed(reader: BitReader, opts: CcittOptions): ByteArray {
    checkGeometry(opts)
    val cols = opts.columns
    var reference = Changes(cols).finish()
    var coding = Changes(cols)
    val output = PackedRows(cols, opts.blackIs1, opts.rows, reader.bitsLeft)
    var row = 0
    while (true) {
        if (!opts.endOfBlock && opts.rows > 0 && row >= opts.rows) break
        if (reader.atEnd() || reader.onlyBytePadding()) break
        if (opts.encodedByteAlign) reader.alignToByte()
        if (reader.atEnd()) break
        if (opts.endOfBlock && consumeMixedRtc(reader)) return output.result()
        if (!consumeOptionalEol(reader) && opts.endOfLine) {
            throw ImageDecodeException("CCITT: mixed row $row is missing its EOL")
        }
        if (!reader.hasBits(1)) throw ImageDecodeException("CCITT: mixed row $row is missing its mode tag")
        val oneDimensional = reader.readBit() == 1
        checkRoomForRow(row, cols)
        val decoded = if (oneDimensional) decodeOneG3Row(reader, coding, row)
            else decodeOneG4Row(reader, reference, coding, strict = true)
        if (!decoded) throw ImageDecodeException("CCITT: mixed row $row is invalid or incomplete")
        output.add(coding)
        val previous = reference
        reference = coding
        coding = previous
        row++
    }
    if (opts.rows > 0 && row < opts.rows) {
        throw ImageDecodeException("CCITT: decoded $row of ${opts.rows} mixed rows")
    }
    return output.result()
}

/** Decode one 1D row into [line]; false only at an EOL that [allowEol] lets end the data. */
private fun decodeOneG3Row(reader: BitReader, line: Changes, row: Int, allowEol: Boolean = false): Boolean {
    val cols = line.cols
    line.start()
    var pos = 0
    var color = 0
    while (pos < cols) {
        val run = decodeRun(reader, color == 0)
        if (run == Int.MIN_VALUE && pos == 0 && allowEol) return false
        if (run < 0) throw ImageDecodeException("CCITT: row $row ends after $pos of $cols pixels")
        if (run > cols - pos) throw ImageDecodeException("CCITT: row $row run exceeds $cols pixels")
        line.fill(pos + run, color)
        pos += run
        color = 1 - color
    }
    line.finish()
    return true
}

/** T.4 section 4.2.4 uses six EOL+1 synchronization words for mixed-mode RTC. */
private fun consumeMixedRtc(reader: BitReader): Boolean {
    val saved = reader.bitsConsumed
    repeat(6) {
        if (!consumeOptionalEol(reader) || !reader.hasBits(1) || reader.readBit() != 1) {
            reader.rewindTo(saved)
            return false
        }
    }
    return true
}

private fun consumeOptionalEol(reader: BitReader): Boolean {
    val saved = reader.bitsConsumed
    // T.4 fill is an arbitrary run of zeros; the input bounds the scan.
    while (!reader.atEnd()) {
        if (reader.readBit() != 0) {
            if (reader.bitsConsumed - saved >= 12) return true
            reader.rewindTo(saved)
            return false
        }
    }
    reader.rewindTo(saved)
    return false
}

/* ─── BitReader rewind support ────────────────────────────────────────────── */

internal fun BitReader.rewindBits(n: Int) {
    // Implemented via a reflection-free helper that backs out the read cursor.
    // We re-seek by recomputing positions from total bits-consumed - n.
    val target = bitsConsumed - n
    rewindTo(target)
}

internal fun BitReader.rewindTo(targetBits: Long) {
    val bp = (targetBits / 8).toInt()
    val bb = (targetBits % 8).toInt()
    seek(bp, bb)
}
