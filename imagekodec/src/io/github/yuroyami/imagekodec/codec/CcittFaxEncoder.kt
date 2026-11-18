package io.github.yuroyami.imagekodec.codec

/*
 * CCITT Group 3 and Group 4 encoding, written from ITU-T T.4 (1D Modified Huffman, 2D Modified READ,
 * EOL and RTC) and ITU-T T.6 (Modified Modified READ and EOFB). The code tables are the ones the
 * decoder reads, [CcittFaxTables], so the two directions share one copy of T.4's tables. The
 * framing follows the decoder's reading of the PDF parameters, which `CcittFax.decode` documents.
 */

/** The widest row the encoder writes. A run that long takes 27 codes, and the decoder reads up to 100 for one run. */
internal const val CCITT_MAX_ENCODE_COLUMNS = 1 shl 16

/** An MSB-first bit sink, as T.4 packs its codes. */
internal class BitWriter(capacity: Int) {
    private var buffer = ByteArray(maxOf(16, capacity))
    private var size = 0
    private var acc = 0
    private var count = 0

    /** Appends the low [length] bits of [code], the highest first; [length] is at most 24. */
    fun write(code: Int, length: Int) {
        acc = (acc shl length) or (code and ((1 shl length) - 1))
        count += length
        while (count >= 8) {
            count -= 8
            put(acc ushr count)
        }
        acc = acc and ((1 shl count) - 1)
    }

    /** Pads the last byte with zero bits, which T.4 calls fill and every decoder skips. */
    fun align() {
        if (count > 0) write(0, 8 - count)
    }

    private fun put(byte: Int) {
        if (size == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
        buffer[size++] = byte.toByte()
    }

    fun toByteArray(): ByteArray {
        align()
        return buffer.copyOf(size)
    }
}

/** The T.4 run codes as encoder lookups: the terminating code of each run below 64, and each makeup code. */
private class RunCodes(terminators: IntArray, makeups: IntArray) {
    val termCode = IntArray(64)
    val termLength = IntArray(64)

    /** Indexed by run / 64, from 1 (64) to 40 (2560). */
    val makeupCode = IntArray(41)
    val makeupLength = IntArray(41)

    init {
        for (i in terminators.indices step 3) {
            termCode[terminators[i + 2]] = terminators[i]
            termLength[terminators[i + 2]] = terminators[i + 1]
        }
        for (i in makeups.indices step 3) {
            val run = makeups[i + 2]
            if (run < 0) continue // the EOL entry
            makeupCode[run / 64] = makeups[i]
            makeupLength[run / 64] = makeups[i + 1]
        }
        check((0 until 64).all { termLength[it] > 0 } && (1..40).all { makeupLength[it] > 0 }) { "CCITT run table has a gap" }
    }
}

private val whiteCodes = RunCodes(CcittFaxTables.WHITE_TERMINATORS, CcittFaxTables.WHITE_MAKEUPS)
private val blackCodes = RunCodes(CcittFaxTables.BLACK_TERMINATORS, CcittFaxTables.BLACK_MAKEUPS)

/** The 2D mode codes, indexed by mode. */
private val modeCode = IntArray(10)
private val modeLength = IntArray(10).also { lengths ->
    val codes = CcittFaxTables.TWO_DIMENSIONAL_CODES
    for (i in codes.indices step 3) {
        modeCode[codes[i + 2]] = codes[i]
        lengths[codes[i + 2]] = codes[i + 1]
    }
}

/** The vertical modes by a1 - b1, from -3 to 3. */
private val verticalModes = intArrayOf(
    CcittFaxTables.MODE_VL3, CcittFaxTables.MODE_VL2, CcittFaxTables.MODE_VL1, CcittFaxTables.MODE_V0,
    CcittFaxTables.MODE_VR1, CcittFaxTables.MODE_VR2, CcittFaxTables.MODE_VR3,
)

private const val EOL = 1 // eleven zeros and a one, T.4 4.1.2
private const val EOL_LENGTH = 12

/**
 * Writes one run as T.4 4.1.1 codes it: the makeup code of 2560 as often as the run holds more than
 * 2559, then the makeup code of the largest multiple of 64 left, if any, then a terminating code.
 */
private fun writeRun(out: BitWriter, run: Int, white: Boolean) {
    val codes = if (white) whiteCodes else blackCodes
    var left = run
    while (left > 2559) {
        out.write(codes.makeupCode[40], codes.makeupLength[40])
        left -= 2560
    }
    if (left >= 64) {
        out.write(codes.makeupCode[left / 64], codes.makeupLength[left / 64])
        left = left and 63
    }
    out.write(codes.termCode[left], codes.termLength[left])
}

private fun writeMode(out: BitWriter, mode: Int) = out.write(modeCode[mode], modeLength[mode])

/**
 * Fills [at] with the changing elements of the row at [base] of [input], as T.4 4.2.1.3.1 defines
 * them: each column whose colour differs from the one before it, the column before 0 being white.
 * Three copies of [cols] follow, so the coder's look-ahead stops there. Returns the count.
 * [inkBit] is the bit value of a black pixel. Whole bytes of one colour are passed over at once.
 */
private fun changingElements(input: ByteArray, base: Int, cols: Int, inkBit: Int, at: IntArray): Int {
    val flip = if (inkBit == 1) 0 else 0xFF
    var n = 0
    var color = 0
    var x = 0
    while (x < cols) {
        if ((x and 7) == 0 && cols - x >= 8) {
            val byte = (input[base + (x ushr 3)].toInt() and 0xFF) xor flip
            if (byte == (if (color == 0) 0 else 0xFF)) {
                x += 8
                continue
            }
        }
        val bit = (((input[base + (x ushr 3)].toInt() and 0xFF) xor flip) ushr (7 - (x and 7))) and 1
        if (bit != color) {
            at[n++] = x
            color = bit
        }
        x++
    }
    at[n] = cols
    at[n + 1] = cols
    at[n + 2] = cols
    return n
}

/** Codes a row as T.4 4.1 one-dimensional runs, white first, from its changing elements. */
private fun encodeOneD(out: BitWriter, changes: IntArray, count: Int) {
    var start = 0
    for (i in 0..count) {
        writeRun(out, changes[i] - start, (i and 1) == 0)
        start = changes[i]
    }
}

/**
 * Codes a row against [reference], the row above it, in the two-dimensional coding of T.4 4.2.1.3
 * and T.6 2.2: pass mode where b2 lies before a1, vertical mode where a1 is within three columns of
 * b1, horizontal mode otherwise. Both rows are changing elements with their sentinels.
 */
private fun encodeTwoD(out: BitWriter, coding: IntArray, reference: IntArray, cols: Int) {
    var a0 = -1
    var color = 0 // the colour of a0: white before the row
    var ia = 0 // the first change on the coding row right of a0
    var ib = 0 // the first change on the reference row right of a0
    while (a0 < cols) {
        while (coding[ia] <= a0) ia++
        val a1 = coding[ia]
        while (reference[ib] <= a0) ib++
        // A change at an even index turns the row black, so b1, which turns it away from a0's
        // colour, sits at the index whose parity is that colour.
        val b1Index = if ((ib and 1) == color) ib else ib + 1
        val b1 = reference[b1Index]
        val b2 = reference[b1Index + 1]
        if (b2 < a1) {
            writeMode(out, CcittFaxTables.MODE_PASS)
            a0 = b2
        } else if (a1 - b1 in -3..3) {
            writeMode(out, verticalModes[a1 - b1 + 3])
            a0 = a1
            color = color xor 1
        } else {
            val a2 = coding[ia + 1]
            writeMode(out, CcittFaxTables.MODE_HORIZONTAL)
            writeRun(out, a1 - maxOf(a0, 0), color == 0)
            writeRun(out, a2 - a1, color != 0)
            a0 = a2
        }
    }
}

/**
 * Encodes [rows] rows of [cols] pixels, packed MSB first and padded to a byte each, a set bit black
 * when [blackIs1]. [k] chooses the coding as `CcittFax.decode` reads it: negative is Group 4 (T.6),
 * 0 is Group 3 one-dimensional, and a positive [k] is Group 3 two-dimensional with one 1D row and
 * then up to k - 1 2D rows (T.4 4.2.1). [cancel] runs every 64 rows.
 *
 * The framing matches the decoder's reading of the PDF parameters: with [encodedByteAlign], each
 * row starts on a byte, its EOL included, and in Group 4 the end of block too; with [endOfLine], a
 * Group 3 row starts with EOL; with [endOfBlock], Group 4 ends with EOFB (T.6 2.4) and Group 3 with
 * RTC, six EOLs, each followed by the tag bit 1 in two-dimensional coding (T.4 4.1.4 and 4.2.4).
 */
internal fun encodeCcittRows(
    input: ByteArray, cols: Int, rows: Int, k: Int, blackIs1: Boolean,
    encodedByteAlign: Boolean, endOfLine: Boolean, endOfBlock: Boolean,
    cancel: (() -> Unit)? = null,
): ByteArray {
    val bytesPerRow = (cols + 7) / 8
    val out = BitWriter(rows / 8 + 64)
    var reference = IntArray(cols + 3)
    var coding = IntArray(cols + 3)
    // The row above the first is white: its only changing element is the end of the row.
    reference.fill(cols)
    val ink = if (blackIs1) 1 else 0
    for (row in 0 until rows) {
        if ((row and 63) == 0) cancel?.invoke()
        val count = changingElements(input, row * bytesPerRow, cols, ink, coding)
        if (k < 0) {
            encodeTwoD(out, coding, reference, cols)
            if (encodedByteAlign) out.align()
        } else {
            if (encodedByteAlign) out.align()
            if (endOfLine) out.write(EOL, EOL_LENGTH)
            val oneD = k == 0 || row % k == 0
            if (k > 0) out.write(if (oneD) 1 else 0, 1)
            if (oneD) encodeOneD(out, coding, count) else encodeTwoD(out, coding, reference, cols)
        }
        val coded = coding
        coding = reference
        reference = coded
    }
    if (endOfBlock) {
        if (k < 0) {
            out.write(EOL, EOL_LENGTH)
            out.write(EOL, EOL_LENGTH)
        } else {
            if (encodedByteAlign) out.align()
            repeat(6) {
                out.write(EOL, EOL_LENGTH)
                if (k > 0) out.write(1, 1)
            }
        }
    }
    return out.toByteArray()
}
