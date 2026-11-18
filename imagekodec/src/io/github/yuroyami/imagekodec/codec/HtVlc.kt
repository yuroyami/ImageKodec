package io.github.yuroyami.imagekodec.codec

/**
 * What the HT block decoder and encoder share (ITU-T T.814): the CxtVLC codes of
 * [HtVlcTables] as lookups, and the MEL coder's exponents.
 *
 * A decode lookup entry, for a context and the next 7 bits of the VLC stream (first bit lowest),
 * packs bits 0 to 2 the codeword length, 4 to 7 rho, 8 u_off, 12 to 15 e_k and 16 to 19 e_1.
 */
internal object HtVlc {

    /** The decode lookup of the first line-pair, 8 contexts of 128 entries. */
    val decodeInitial: IntArray = decodeLookup(HtVlcTables.INITIAL)

    /** The decode lookup of every later line-pair. */
    val decodeLater: IntArray = decodeLookup(HtVlcTables.LATER)

    /**
     * The encode lookup of the first line-pair: for context `c`, rho and u_off, at
     * `(c * 16 + rho) * 2 + u_off`, the rows of [HtVlcTables.INITIAL] that code them.
     */
    val encodeInitial: Array<IntArray> = encodeLookup(HtVlcTables.INITIAL)

    /** The encode lookup of every later line-pair. */
    val encodeLater: Array<IntArray> = encodeLookup(HtVlcTables.LATER)

    /** The MEL coder's exponent of each of its 13 states (T.814). */
    val MEL_EXPONENT: IntArray = intArrayOf(0, 0, 0, 1, 1, 1, 2, 2, 2, 3, 3, 4, 5)

    fun context(row: Int): Int = row ushr 23
    fun rho(row: Int): Int = (row ushr 19) and 15
    fun uOff(row: Int): Int = (row ushr 18) and 1
    fun ek(row: Int): Int = (row ushr 14) and 15
    fun e1(row: Int): Int = (row ushr 10) and 15
    fun codeword(row: Int): Int = (row ushr 3) and 0x7F
    fun length(row: Int): Int = row and 7

    private fun decodeLookup(rows: IntArray): IntArray {
        val out = IntArray(8 * 128)
        for (row in rows) {
            val n = length(row)
            val entry = n or (rho(row) shl 4) or (uOff(row) shl 8) or (ek(row) shl 12) or (e1(row) shl 16)
            // Every 7-bit input whose low n bits are the codeword decodes to this row.
            var rest = 0
            while (rest < 1 shl (7 - n)) {
                val i = (context(row) shl 7) or (rest shl n) or codeword(row)
                check(out[i] == 0) { "two HT codewords share the input $i" }
                out[i] = entry
                rest++
            }
        }
        check(out.all { it != 0 }) { "an HT context leaves an input without a codeword" }
        return out
    }

    private fun encodeLookup(rows: IntArray): Array<IntArray> {
        val lists = Array(8 * 16 * 2) { ArrayList<Int>() }
        for (row in rows) lists[(context(row) * 16 + rho(row)) * 2 + uOff(row)] += row
        return Array(lists.size) { lists[it].toIntArray() }
    }
}
