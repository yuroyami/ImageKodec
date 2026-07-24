package io.github.yuroyami.kiteimage.internal.flate

/**
 * Raw DEFLATE (RFC 1951) decompressor: a pure-Kotlin port of Mark Adler's
 * `puff()` (the canonical reference inflate, bundled with zlib at
 * `contrib/puff/puff.c`), accelerated with a table-driven Huffman fast path.
 * Vendored from KiteArchive, which carried it over from the KiteTorrent port,
 * which itself took it from libtorrent's `src/puff.cpp`. Kept `internal`: PNG
 * IDAT needs an inflater and the KiteImage core takes zero dependencies: swap
 * for the `kitearchive` artifact once it is on Maven Central.
 *
 * Differences from the C, all behaviour-preserving:
 *
 *  - puff writes into a caller-supplied fixed-size buffer and returns `1`
 *    ("output space exhausted") when it's too small; we grow the output array on
 *    demand instead, so error code `1` never occurs here. Callers that know the
 *    final size pass [inflate]'s `sizeHint` to preallocate it, and `maxOutput`
 *    to abort *mid-stream* the moment the output would exceed it: the
 *    decompression-bomb guard fires before the memory is committed, not after.
 *
 *  - puff signals "ran past the end of input" via `setjmp`/`longjmp`; we model
 *    that non-local exit with a private [OutOfInput] throwable caught at the top
 *    of [inflate], mapping to [InflateError.DATA_DID_NOT_TERMINATE].
 *
 *  - puff's "scan only" mode (`dest == NULL`) is omitted; we always materialise
 *    the output.
 *
 *  - puff decodes every Huffman symbol bit-by-bit. We additionally build a
 *    [FAST_BITS]-wide lookup table per Huffman code (the standard zlib/stb
 *    acceleration): one masked read resolves any code of ≤ [FAST_BITS] bits,
 *    which is nearly every symbol in real streams; longer codes and the last
 *    few bytes of input fall back to puff's exact bit-by-bit walk.
 *
 * Bits are consumed LSB-first within each input byte; Huffman codes are read
 * MSB-first and accumulated in reversed order so canonical codes compare as plain
 * integers (see [decodeSlow]). The fast table is therefore indexed by
 * *bit-reversed* canonical codes: the low bit of the index is the first bit off
 * the wire, which is the code's MSB.
 *
 * This object is stateless and thread-safe; all mutable inflate state lives in the
 * private [State] created per call.
 */
internal object Inflate {

    // Maximums fixed by the deflate format (puff.c #defines).
    private const val MAXBITS = 15        // maximum bits in a code
    private const val MAXLCODES = 286     // maximum number of literal/length codes
    private const val MAXDCODES = 30      // maximum number of distance codes
    private const val MAXCODES = MAXLCODES + MAXDCODES
    private const val FIXLCODES = 288     // number of fixed literal/length codes

    // Fast-table width: 9 bits covers the fixed literal table and virtually all
    // dynamic-block symbols; same choice as zlib's ENOUGH/stb's ZFAST_BITS.
    private const val FAST_BITS = 9
    private const val FAST_SIZE = 1 shl FAST_BITS
    private const val FAST_MASK = FAST_SIZE - 1

    // Size base for length codes 257..285 (puff `lens[]`).
    private val LENS = shortArrayOf(
        3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31,
        35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258,
    )
    // Extra bits for length codes 257..285 (puff `lext[]`).
    private val LEXT = shortArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2,
        3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0,
    )
    // Offset base for distance codes 0..29 (puff `dists[]`).
    private val DISTS = shortArrayOf(
        1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193,
        257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145,
        8193, 12289, 16385, 24577,
    )
    // Extra bits for distance codes 0..29 (puff `dext[]`).
    private val DEXT = shortArrayOf(
        0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
        7, 7, 8, 8, 9, 9, 10, 10, 11, 11,
        12, 12, 13, 13,
    )
    // Permutation of the code length codes in a dynamic block (puff `order[]`).
    private val ORDER = intArrayOf(
        16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15,
    )

    /**
     * Inflate a raw DEFLATE stream (no gzip/zlib wrapper) starting at [offset]
     * in [input] and return the decompressed bytes.
     *
     * [sizeHint] preallocates the output buffer when the caller knows the final
     * size (PNG/TIFF compute it exactly); 0 means "unknown, grow by doubling".
     * [maxOutput] aborts with [InflateError.INFLATED_DATA_TOO_LARGE] as soon as
     * the output *would* grow past it, before allocating: the bomb guard runs
     * during decompression, not after.
     *
     * @throws InflateException with the faithful [InflateError] on malformed or
     *   truncated input: the same failure taxonomy puff returns numerically.
     */
    fun inflate(
        input: ByteArray,
        offset: Int = 0,
        sizeHint: Int = 0,
        maxOutput: Long = Long.MAX_VALUE,
    ): ByteArray {
        val s = State(input, offset, sizeHint, maxOutput)
        try {
            var last: Int
            do {
                last = s.bits(1)            // one if last block
                val type = s.bits(2)        // block type 0..3
                val err = when (type) {
                    0 -> stored(s)
                    1 -> fixed(s)
                    2 -> dynamic(s)
                    else -> InflateError.INVALID_BLOCK_TYPE.code   // type == 3
                }
                if (err != 0) throw InflateException(InflateError.fromPuff(err))
            } while (last == 0)
        } catch (_: OutOfInput) {
            throw InflateException(InflateError.DATA_DID_NOT_TERMINATE)
        }
        return s.output()
    }

    /** Non-local "out of input" signal, replacing puff's setjmp/longjmp. */
    private class OutOfInput : Throwable()

    /**
     * Per-call inflate state. Holds the input cursor + bit buffer and the growable
     * output. Mirrors puff's `struct state`, except [out] grows instead of being a
     * fixed buffer, and growth past [maxOutput] throws instead of erroring later.
     */
    private class State(
        private val inBuf: ByteArray,
        startOffset: Int,
        sizeHint: Int,
        private val maxOutput: Long,
    ) {
        private var incnt = startOffset // input cursor
        var bitbuf = 0                  // bit buffer
        var bitcnt = 0                  // number of valid low bits in bitbuf

        private var out = ByteArray(
            when {
                sizeHint > 0 -> minOf(sizeHint.toLong(), maxOutput).toInt().coerceAtLeast(16)
                else -> 256
            },
        )
        var outcnt = 0
            private set

        val inAvailable: Int get() = inBuf.size - incnt

        /** No copy when the size hint was exact: the common PNG/TIFF path. */
        fun output(): ByteArray = if (outcnt == out.size) out else out.copyOf(outcnt)

        fun nextByte(): Int {
            if (incnt == inBuf.size) throw OutOfInput()
            return inBuf[incnt++].toInt() and 0xff
        }

        fun rawByte(): Int = inBuf[incnt++].toInt() and 0xff

        fun bits(need: Int): Int {
            var value = bitbuf
            while (bitcnt < need) {
                value = value or (nextByte() shl bitcnt)
                bitcnt += 8
            }
            bitbuf = value ushr need
            bitcnt -= need
            return value and ((1 shl need) - 1)
        }

        fun ensure(extra: Int) {
            val needed = outcnt + extra
            if (needed > out.size) {
                if (needed.toLong() > maxOutput) {
                    throw InflateException(InflateError.INFLATED_DATA_TOO_LARGE)
                }
                var cap = if (out.size == 0) 256 else out.size
                while (cap < needed) cap = cap shl 1
                out = out.copyOf(minOf(cap.toLong(), maxOutput).toInt().coerceAtLeast(needed))
            }
        }

        fun writeByte(b: Int) {
            ensure(1)
            out[outcnt++] = b.toByte()
        }

        /** Copy [len] bytes from [dist] back in the output (LZ77 back-reference). */
        fun copyBack(dist: Int, len: Int) {
            ensure(len)
            var src = outcnt - dist
            var n = len
            while (n-- > 0) {
                out[outcnt++] = out[src++]
            }
        }

        /** Copy [len] verbatim bytes straight from the input (stored block). */
        fun copyStored(len: Int) {
            ensure(len)
            var n = len
            while (n-- > 0) {
                out[outcnt++] = inBuf[incnt++]
            }
        }
    }

    /**
     * Canonical Huffman code as puff builds it (count-of-each-length + symbols
     * in canonical order), plus the [FAST_BITS] lookup table: `fast[next 9 wire
     * bits] = (symbol shl 4) or codeLength`, 0 = code longer than [FAST_BITS]
     * (fall back to the bit-by-bit walk).
     */
    private class Huffman(maxSymbols: Int) {
        val count = ShortArray(MAXBITS + 1)
        val symbol = ShortArray(maxSymbols)
        val fast = ShortArray(FAST_SIZE)

        /** Fill [fast] from the canonical code assignment (RFC 1951 §3.2.2). */
        fun buildFastTable() {
            // First canonical code of each length.
            val nextCode = IntArray(MAXBITS + 1)
            var c = 0
            for (len in 1..MAXBITS) {
                c = (c + count[len - 1]) shl 1
                nextCode[len] = c
            }
            var symIdx = 0
            for (len in 1..MAXBITS) {
                repeat(count[len].toInt()) {
                    if (len <= FAST_BITS) {
                        val sym = symbol[symIdx].toInt()
                        // Wire order = code MSB first, index bit 0 = first wire
                        // bit, so index by the bit-reversed code; every index
                        // sharing those low `len` bits decodes to this symbol.
                        var rev = 0
                        var v = nextCode[len]
                        repeat(len) {
                            rev = (rev shl 1) or (v and 1)
                            v = v ushr 1
                        }
                        val entry = ((sym shl 4) or len).toShort()
                        var i = rev
                        while (i < FAST_SIZE) {
                            fast[i] = entry
                            i += 1 shl len
                        }
                    }
                    nextCode[len]++
                    symIdx++
                }
            }
        }
    }

    private fun stored(s: State): Int {
        s.bitbuf = 0
        s.bitcnt = 0

        if (s.inAvailable < 4) return InflateError.DATA_DID_NOT_TERMINATE.code
        var len = s.rawByte()
        len = len or (s.rawByte() shl 8)
        val nlen0 = s.rawByte()
        val nlen1 = s.rawByte()
        if (nlen0 != (len.inv() and 0xff) || nlen1 != ((len.inv() ushr 8) and 0xff)) {
            return InflateError.INVALID_STORED_BLOCK_LENGTH.code
        }

        if (s.inAvailable < len) return InflateError.DATA_DID_NOT_TERMINATE.code
        s.copyStored(len)
        return 0
    }

    /**
     * Decode one symbol: fast path first (one masked table read resolves codes
     * of ≤ [FAST_BITS] bits), puff's bit-by-bit walk for long codes or when the
     * input is too near its end to fill the peek window.
     */
    private fun decode(s: State, h: Huffman): Int {
        // Top up the peek window to FAST_BITS. If the input ends first, take the
        // exact slow path: it consumes bit-by-bit and throws OutOfInput only if
        // the stream truly ends mid-code.
        while (s.bitcnt < FAST_BITS) {
            if (s.inAvailable == 0) return decodeSlow(s, h)
            s.bitbuf = s.bitbuf or (s.rawByte() shl s.bitcnt)
            s.bitcnt += 8
        }
        val entry = h.fast[s.bitbuf and FAST_MASK].toInt()
        if (entry != 0) {
            val len = entry and 15
            s.bitbuf = s.bitbuf ushr len
            s.bitcnt -= len
            return entry ushr 4
        }
        return decodeSlow(s, h)
    }

    /** puff's exact bit-by-bit canonical walk (unchanged semantics). */
    private fun decodeSlow(s: State, h: Huffman): Int {
        var bitbuf = s.bitbuf
        var left = s.bitcnt
        var code = 0
        var first = 0
        var index = 0
        var len = 1
        var nextIdx = 1

        while (true) {
            while (left > 0) {
                left--
                code = code or (bitbuf and 1)
                bitbuf = bitbuf ushr 1
                val count = h.count[nextIdx++].toInt()
                if (code - count < first) {
                    s.bitbuf = bitbuf
                    s.bitcnt = (s.bitcnt - len) and 7
                    return h.symbol[index + (code - first)].toInt()
                }
                index += count
                first += count
                first = first shl 1
                code = code shl 1
                len++
            }
            left = (MAXBITS + 1) - len
            if (left == 0) break
            bitbuf = s.nextByte()
            if (left > 8) left = 8
        }
        return InflateError.INVALID_LITERAL_OR_DISTANCE_CODE.code
    }

    private fun construct(h: Huffman, length: ShortArray, n: Int): Int {
        // Zero the fast table here, not in buildFastTable: the all-lengths-zero
        // early return below must not leave a reused Huffman's stale table live.
        h.fast.fill(0)
        for (len in 0..MAXBITS) h.count[len] = 0
        for (symbol in 0 until n) h.count[length[symbol].toInt()]++
        if (h.count[0].toInt() == n) return 0

        var left = 1
        for (len in 1..MAXBITS) {
            left = left shl 1
            left -= h.count[len].toInt()
            if (left < 0) return left
        }

        val offs = ShortArray(MAXBITS + 1)
        offs[1] = 0
        for (len in 1 until MAXBITS) {
            offs[len + 1] = (offs[len] + h.count[len]).toShort()
        }

        for (symbol in 0 until n) {
            val l = length[symbol].toInt()
            if (l != 0) {
                h.symbol[offs[l].toInt()] = symbol.toShort()
                offs[l]++
            }
        }

        h.buildFastTable()
        return left
    }

    private fun codes(s: State, lencode: Huffman, distcode: Huffman): Int {
        var symbol: Int
        do {
            symbol = decode(s, lencode)
            if (symbol < 0) return symbol
            when {
                symbol < 256 -> {
                    s.writeByte(symbol)
                }
                symbol > 256 -> {
                    var sym = symbol - 257
                    if (sym >= 29) return InflateError.INVALID_LITERAL_OR_DISTANCE_CODE.code
                    val len = LENS[sym].toInt() + s.bits(LEXT[sym].toInt())

                    sym = decode(s, distcode)
                    if (sym < 0) return sym
                    val dist = DISTS[sym].toInt() + s.bits(DEXT[sym].toInt())
                    if (dist > s.outcnt) return InflateError.DISTANCE_TOO_FAR_BACK.code
                    s.copyBack(dist, len)
                }
                // symbol == 256 -> end of block, loop terminates
            }
        } while (symbol != 256)
        return 0
    }

    private fun fixed(s: State): Int {
        val lengths = ShortArray(FIXLCODES)
        var symbol = 0
        while (symbol < 144) { lengths[symbol] = 8; symbol++ }
        while (symbol < 256) { lengths[symbol] = 9; symbol++ }
        while (symbol < 280) { lengths[symbol] = 7; symbol++ }
        while (symbol < FIXLCODES) { lengths[symbol] = 8; symbol++ }
        val lencode = Huffman(FIXLCODES)
        construct(lencode, lengths, FIXLCODES)

        for (i in 0 until MAXDCODES) lengths[i] = 5
        val distcode = Huffman(MAXDCODES)
        construct(distcode, lengths, MAXDCODES)

        return codes(s, lencode, distcode)
    }

    private fun dynamic(s: State): Int {
        val lengths = ShortArray(MAXCODES)

        val nlen = s.bits(5) + 257
        val ndist = s.bits(5) + 1
        val ncode = s.bits(4) + 4
        if (nlen > MAXLCODES || ndist > MAXDCODES) {
            return InflateError.TOO_MANY_LENGTH_OR_DISTANCE_CODES.code
        }

        var index = 0
        while (index < ncode) {
            lengths[ORDER[index]] = s.bits(3).toShort()
            index++
        }
        while (index < 19) {
            lengths[ORDER[index]] = 0
            index++
        }

        val lencode = Huffman(MAXLCODES)
        var err = construct(lencode, lengths, 19)
        if (err != 0) return InflateError.CODE_LENGTHS_CODES_INCOMPLETE.code

        index = 0
        while (index < nlen + ndist) {
            var symbol = decode(s, lencode)
            if (symbol < 0) return symbol
            if (symbol < 16) {
                lengths[index++] = symbol.toShort()
            } else {
                var repeatLen = 0
                when (symbol) {
                    16 -> {
                        if (index == 0) return InflateError.REPEAT_LENGTHS_WITH_NO_FIRST_LENGTH.code
                        repeatLen = lengths[index - 1].toInt()
                        symbol = 3 + s.bits(2)
                    }
                    17 -> symbol = 3 + s.bits(3)
                    else -> symbol = 11 + s.bits(7)
                }
                if (index + symbol > nlen + ndist) {
                    return InflateError.REPEAT_MORE_THAN_SPECIFIED_LENGTHS.code
                }
                while (symbol-- > 0) lengths[index++] = repeatLen.toShort()
            }
        }

        if (lengths[256].toInt() == 0) return InflateError.MISSING_END_OF_BLOCK_CODE.code

        err = construct(lencode, lengths, nlen)
        if (err != 0 && (err < 0 || nlen != lencode.count[0].toInt() + lencode.count[1].toInt())) {
            return InflateError.INVALID_LITERAL_LENGTH_CODE_LENGTHS.code
        }

        val distLengths = ShortArray(MAXDCODES)
        for (i in 0 until ndist) distLengths[i] = lengths[nlen + i]
        val distcode = Huffman(MAXDCODES)
        err = construct(distcode, distLengths, ndist)
        if (err != 0 && (err < 0 || ndist != distcode.count[0].toInt() + distcode.count[1].toInt())) {
            return InflateError.INVALID_DISTANCE_CODE_LENGTHS.code
        }

        return codes(s, lencode, distcode)
    }
}
