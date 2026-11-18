package io.github.yuroyami.imagekodec.codec

/**
 * The MQ arithmetic encoder of T.800 Annex C (C.2), the same coder as T.88 Annex E.2, the
 * counterpart of [MqDecoder], whose state table it shares. Contexts are the same packed
 * `(stateIndex shl 1) or MPS` slots the decoder uses.
 *
 * The register C holds 28 bits as C.2 lays it out: 8 output bits above a spacer bit and 3 more,
 * then the 16 bits that line up with the interval A. [ct] counts the shifts left before the next
 * byte goes out. The byte buffer keeps a dummy byte at index 0 in place of the byte before the
 * start that C.2.8 points BP at, so the output is `buf[1..bp]`.
 */
internal class MqEncoder(capacity: Int = 256) {
    private var buf = ByteArray(maxOf(16, capacity))
    private var bp = 0
    private var a = 0x8000
    private var c = 0
    private var ct = 12

    /** The bytes written so far, the byte that may still take a carry included. */
    val written: Int get() = bp

    /** Shifts left before the next byte goes out; with [written] it fixes how far the coded bits reach. */
    val shiftsLeft: Int get() = ct

    fun encode(cx: IntArray, pos: Int, symbol: Int) {
        val i = cx[pos] shr 1
        val mps = cx[pos] and 1
        val qe = MqDecoder.QE[i]
        a -= qe
        if (symbol == mps) {
            // CODEMPS (C.2.4): the conditional exchange keeps the larger subinterval for the MPS.
            if (a and 0x8000 == 0) {
                if (a < qe) a = qe else c += qe
                cx[pos] = (MqDecoder.NMPS[i] shl 1) or mps
                renormalize()
            } else {
                c += qe
            }
        } else {
            // CODELPS (C.2.5).
            if (a < qe) c += qe else a = qe
            val next = if (MqDecoder.SW[i] == 1) 1 - mps else mps
            cx[pos] = (MqDecoder.NLPS[i] shl 1) or next
            renormalize()
        }
    }

    private fun renormalize() {
        do {
            a = a shl 1
            c = c shl 1
            ct--
            if (ct == 0) byteOut()
        } while (a and 0x8000 == 0)
    }

    /** BYTEOUT (C.2.6): a byte after 0xFF carries 7 bits, so no carry can reach the 0xFF. */
    private fun byteOut() {
        if (bp + 2 > buf.size) buf = buf.copyOf(buf.size * 2)
        val b = buf[bp].toInt() and 0xFF
        if (b == 0xFF) {
            stuffed()
        } else if (c < 0x8000000) {
            plain()
        } else {
            val carried = b + 1
            buf[bp] = carried.toByte()
            if (carried == 0xFF) {
                c = c and 0x7FFFFFF
                stuffed()
            } else {
                plain()
            }
        }
    }

    private fun stuffed() {
        bp++
        buf[bp] = (c ushr 20).toByte()
        c = c and 0xFFFFF
        ct = 7
    }

    private fun plain() {
        bp++
        buf[bp] = (c ushr 19).toByte()
        c = c and 0x7FFFF
        ct = 8
    }

    /**
     * FLUSH (C.2.9): SETBITS puts as many 1 bits into C as the interval allows, and two byte-outs
     * write them. A last byte of 0xFF is dropped: a decoder reads 1 bits past the end, which is what
     * it held, and a segment that ends in 0xFF would read as a marker with the bytes after it.
     */
    fun flush(): ByteArray {
        val top = c + a
        c = c or 0xFFFF
        if (c >= top) c -= 0x8000
        c = c shl ct
        byteOut()
        c = c shl ct
        byteOut()
        var n = bp
        while (n > 0 && buf[n].toInt() and 0xFF == 0xFF) n--
        return buf.copyOfRange(1, n + 1)
    }

    /**
     * The JBIG2 end of [flush]: the bytes, then the marker 0xFF 0xAC that ends the data, as T.88 E.2.9
     * notes. A last 0xFF has already been dropped by [flush]; the marker supplies it.
     */
    fun finish(): ByteArray {
        val data = flush()
        val out = data.copyOf(data.size + 2)
        out[data.size] = 0xFF.toByte()
        out[data.size + 1] = 0xAC.toByte()
        return out
    }

    companion object {
        /**
         * The bytes of [data], the flushed output of a coder, that a decoder needs to decode every
         * symbol coded before a point where [written] bytes were out and [shiftsLeft] shifts were left.
         * The final code value lies in the interval [C, C + A) of that point, and a decoder fills
         * missing bytes with 1 bits, so the cut is safe once it keeps every bit down to the lowest bit
         * of C: `27 - shiftsLeft` bits past the written bytes, at 8 a byte, or 7 after a 0xFF.
         */
        fun truncationLength(data: ByteArray, written: Int, shiftsLeft: Int): Int {
            var need = 27 - shiftsLeft
            var at = written
            var afterFf = at >= 1 && at - 1 < data.size && data[at - 1].toInt() and 0xFF == 0xFF
            while (need > 0 && at < data.size) {
                need -= if (afterFf) 7 else 8
                afterFf = data[at].toInt() and 0xFF == 0xFF
                at++
            }
            return minOf(at, data.size)
        }
    }
}
