package io.github.yuroyami.imagekodec.internal.brotli

/** A Brotli stream that breaks a rule of RFC 7932, or ends before its last meta-block. */
internal class BrotliException(message: String) : Exception("Brotli: $message")

/**
 * A Brotli decoder (RFC 7932), for the `brob` boxes of a JPEG XL file (ISO/IEC 18181-2, 9.1),
 * which hold an `Exif` or `xml ` box compressed. Written from the RFC: the window and meta-block
 * headers (9.1, 9.2), prefix codes simple and complex (3.4, 3.5), block switches (6), context
 * modeling (7), the insert-and-copy and distance codes (4, 5) and the static dictionary with its
 * transforms (8, Appendices A and B).
 *
 * The whole output stays in one array, so a distance reaches back as far as the window allows
 * and a ring buffer is not needed. Every read is checked: a stream that breaks a rule throws
 * [BrotliException], and the output never grows past the caller's ceiling.
 */
internal object Brotli {

    /** Decompresses [data], throwing [BrotliException] past [maximumSize] bytes of output. */
    fun decompress(data: ByteArray, maximumSize: Int): ByteArray = Decoder(data, maximumSize).run()

    private class Bits(private val data: ByteArray) {
        private var at = 0
        private var buffer = 0
        private var count = 0

        /** The next [n] bits, 0 to 24, the first read in the lowest bit (RFC 7932, 2). */
        fun read(n: Int): Int {
            if (n == 0) return 0
            while (count <= 24 && at < data.size) {
                buffer = buffer or ((data[at++].toInt() and 0xFF) shl count)
                count += 8
            }
            if (count < n) throw BrotliException("the stream ends inside a meta-block")
            val v = buffer and ((1 shl n) - 1)
            buffer = buffer ushr n
            count -= n
            return v
        }

        /** Skips to the next byte; the bits skipped have to be 0 (9.2). */
        fun align() {
            if (read(count and 7) != 0) throw BrotliException("padding bits that are not 0")
        }

        /** The next [n] bytes, after [align]. */
        fun bytes(n: Int, out: Output) {
            var left = n
            while (left > 0 && count >= 8) {
                out.add(read(8))
                left--
            }
            if (left > data.size - at) throw BrotliException("the stream ends inside an uncompressed meta-block")
            out.add(data, at, left)
            at += left
        }

        fun skip(n: Int) {
            var left = n
            while (left > 0 && count >= 8) {
                read(8)
                left--
            }
            if (left > data.size - at) throw BrotliException("the stream ends inside metadata")
            at += left
        }
    }

    private class Output(private val limit: Int) {
        var data = ByteArray(minOf(limit, 4096).coerceAtLeast(16))
        var size = 0

        private fun room(n: Int) {
            if (n > limit - size) throw BrotliException("more than $limit bytes of output")
            if (size + n > data.size) {
                var grown = data.size
                while (grown < size + n) grown = if (grown > limit / 2) limit else grown * 2
                data = data.copyOf(grown)
            }
        }

        fun add(b: Int) {
            room(1)
            data[size++] = b.toByte()
        }

        fun add(src: ByteArray, from: Int, n: Int) {
            room(n)
            src.copyInto(data, size, from, from + n)
            size += n
        }

        /** Copies [n] bytes from [distance] back, one at a time, since the two may overlap. */
        fun copy(distance: Int, n: Int) {
            room(n)
            var from = size - distance
            repeat(n) { data[size++] = data[from++] }
        }

        fun last(back: Int): Int = if (size >= back) data[size - back].toInt() and 0xFF else 0
    }

    /**
     * A canonical prefix code (3.2): the count of codes of each length, and the symbols in code
     * order. One symbol alone takes no bits.
     */
    private class Code(val counts: IntArray, val symbols: IntArray, val single: Int)

    private fun Bits.symbol(code: Code): Int {
        if (code.single >= 0) return code.single
        var value = 0
        var first = 0
        var index = 0
        for (length in 1..15) {
            value = value or read(1)
            val count = code.counts[length]
            if (value - first < count) return code.symbols[index + value - first]
            index += count
            first = (first + count) shl 1
            value = value shl 1
        }
        throw BrotliException("a code that is not in its prefix code")
    }

    /** The code of [lengths], which have to fill the code space exactly, or name one symbol alone. */
    private fun code(lengths: IntArray): Code {
        val counts = IntArray(16)
        var used = 0
        var only = -1
        for ((s, l) in lengths.withIndex()) if (l != 0) {
            counts[l]++
            used++
            only = s
        }
        if (used == 1) return Code(counts, intArrayOf(only), only)
        var left = 1
        for (l in 1..15) {
            left = (left shl 1) - counts[l]
            if (left < 0) throw BrotliException("an over-subscribed prefix code")
        }
        if (left != 0) throw BrotliException("an incomplete prefix code")
        val offsets = IntArray(16)
        for (l in 1 until 15) offsets[l + 1] = offsets[l] + counts[l]
        val symbols = IntArray(used)
        for ((s, l) in lengths.withIndex()) if (l != 0) symbols[offsets[l]++] = s
        return Code(counts, symbols, -1)
    }

    /** The order of the code length code lengths (3.5). */
    private val CODE_LENGTH_ORDER = intArrayOf(1, 2, 3, 4, 0, 5, 17, 6, 16, 7, 8, 9, 10, 11, 12, 13, 14, 15)

    /** Reads a prefix code over [alphabet] symbols (3.4, 3.5). */
    private fun Bits.prefixCode(alphabet: Int): Code {
        val hskip = read(2)
        val lengths = IntArray(alphabet)
        if (hskip == 1) {
            // A simple prefix code: one to four symbols of fixed lengths.
            var bits = 0
            while ((1 shl bits) < alphabet) bits++
            val n = read(2) + 1
            val symbols = IntArray(n) { read(bits) }
            for ((i, s) in symbols.withIndex()) {
                if (s >= alphabet) throw BrotliException("symbol $s of a simple code past its alphabet of $alphabet")
                for (k in 0 until i) if (symbols[k] == s) throw BrotliException("a simple code that repeats a symbol")
            }
            val shape = when (n) {
                1 -> return Code(IntArray(16), symbols, symbols[0])
                2 -> intArrayOf(1, 1)
                3 -> intArrayOf(1, 2, 2)
                else -> if (read(1) == 0) intArrayOf(2, 2, 2, 2) else intArrayOf(1, 2, 3, 3)
            }
            for (i in 0 until n) lengths[symbols[i]] = shape[i]
            return code(lengths)
        }
        // A complex prefix code: first the lengths of the code length code, in a fixed code of their own.
        val codeLengths = IntArray(18)
        var space = 32
        var nonZero = 0
        for (i in hskip until 18) {
            val v = when (read(2)) {
                0 -> 0
                1 -> 4
                2 -> 3
                else -> if (read(1) == 0) 2 else if (read(1) == 0) 1 else 5
            }
            codeLengths[CODE_LENGTH_ORDER[i]] = v
            if (v != 0) {
                space -= 32 shr v
                nonZero++
                if (space <= 0) break
            }
        }
        if (nonZero != 1 && space != 0) throw BrotliException("a code length code that does not fill its space")
        val lengthCode = code(codeLengths)
        var symbol = 0
        var previous = 8
        var repeat = 0
        var repeatLength = 0
        var left = 32768
        while (symbol < alphabet && left > 0) {
            val s = symbol(lengthCode)
            if (s < 16) {
                repeat = 0
                lengths[symbol++] = s
                if (s != 0) {
                    previous = s
                    left -= 32768 shr s
                }
            } else {
                // 16 repeats the last length that is not 0, 17 repeats 0; a run of either grows the one before (3.5).
                val extra = if (s == 16) 2 else 3
                val length = if (s == 16) previous else 0
                if (length != repeatLength) {
                    repeat = 0
                    repeatLength = length
                }
                val old = repeat
                if (repeat > 0) repeat = (repeat - 2) shl extra
                repeat += read(extra) + 3
                val added = repeat - old
                if (added > alphabet - symbol) throw BrotliException("code lengths past the alphabet")
                repeat(added) { lengths[symbol++] = length }
                if (length != 0) left -= added * (32768 shr length)
            }
        }
        if (left != 0) throw BrotliException("code lengths that do not fill the code space")
        return code(lengths)
    }

    /** A number from 1 to 256 (9.2: NBLTYPES and NTREES). */
    private fun Bits.count256(): Int {
        if (read(1) == 0) return 1
        val n = read(3)
        return if (n == 0) 2 else (1 shl n) + read(n) + 1
    }

    /** Block length codes (6, Table 3): extra bits each, the first base 1 and each next base the one before plus its range. */
    private val BLOCK_EXTRA = intArrayOf(2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 6, 6, 7, 8, 9, 10, 11, 12, 13, 24)
    private val BLOCK_BASE = bases(1, BLOCK_EXTRA)

    /** Insert length codes (5, Table 4). */
    private val INSERT_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 7, 8, 9, 10, 12, 14, 24)
    private val INSERT_BASE = bases(0, INSERT_EXTRA)

    /** Copy length codes (5, Table 4). */
    private val COPY_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 7, 8, 9, 10, 24)
    private val COPY_BASE = bases(2, COPY_EXTRA)

    private fun bases(first: Int, extra: IntArray): IntArray {
        val out = IntArray(extra.size)
        out[0] = first
        for (i in 1 until extra.size) out[i] = out[i - 1] + (1 shl extra[i - 1])
        return out
    }

    /** The insert and copy code ranges of each block of 64 insert-and-copy symbols (5): the first two use the last distance. */
    private val CELL_INSERT = intArrayOf(0, 0, 0, 0, 8, 8, 0, 16, 8, 16, 16)
    private val CELL_COPY = intArrayOf(0, 8, 0, 8, 0, 8, 16, 0, 16, 8, 16)

    /** The last distance (0) or the one before (1) each of the 16 short distance codes starts from, and what it adds (4). */
    private val SHORT_FROM = intArrayOf(0, 1, 2, 3, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1)
    private val SHORT_ADD = intArrayOf(0, 0, 0, 0, -1, 1, -2, 2, -3, 3, -1, 1, -2, 2, -3, 3)

    /**
     * The literal context lookup tables of 7.1. UTF8 mode takes LUT0 of the last byte and LUT1 of
     * the one before; signed mode takes LUT2 of each.
     */
    private val LUT0: IntArray
    private val LUT1: IntArray
    private val LUT2: IntArray

    init {
        // LUT0 for the ASCII bytes, as 7.1 lists them: a class for spaces, punctuation, digits,
        // vowels and consonants in each case. Above 127, continuation bytes give 0 and 1 in turn and
        // lead bytes 2 and 3.
        val ascii = intArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 4, 4, 0, 0, 4, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            8, 12, 16, 12, 12, 20, 12, 16, 24, 28, 12, 12, 32, 12, 36, 12,
            44, 44, 44, 44, 44, 44, 44, 44, 44, 44, 32, 32, 24, 40, 28, 12,
            12, 48, 52, 52, 52, 48, 52, 52, 52, 48, 52, 52, 52, 52, 52, 48,
            52, 52, 52, 52, 52, 48, 52, 52, 52, 52, 52, 24, 12, 28, 12, 12,
            12, 56, 60, 60, 60, 56, 60, 60, 60, 56, 60, 60, 60, 60, 60, 56,
            60, 60, 60, 60, 60, 56, 60, 60, 60, 60, 60, 24, 12, 28, 12, 0,
        )
        LUT0 = IntArray(256) { if (it < 128) ascii[it] else if (it < 192) it and 1 else 2 + (it and 1) }
        // LUT1: 0 for control bytes and the space, 1 for punctuation, 2 for digits and capitals, 3 for
        // small letters; above 127, 0 up to 0xDF and 2 for the lead bytes of three and four bytes.
        LUT1 = IntArray(256) {
            when (it) {
                in 0..32, 127 -> 0
                in 48..57, in 65..90 -> 2
                in 97..122 -> 3
                in 128..223 -> 0
                in 224..255 -> 2
                else -> 1
            }
        }
        // LUT2: 0, then 1..15, 16..63, 64..127, 128..191, 192..239, 240..254 and 255.
        LUT2 = IntArray(256) {
            when (it) {
                0 -> 0
                in 1..15 -> 1
                in 16..63 -> 2
                in 64..127 -> 3
                in 128..191 -> 4
                in 192..239 -> 5
                in 240..254 -> 6
                else -> 7
            }
        }
    }

    /** The bits of a word index for each word length (Appendix A, NDBITS); 0 where there are no words. */
    private val WORD_BITS = intArrayOf(0, 0, 0, 0, 10, 10, 11, 11, 10, 10, 10, 10, 10, 9, 9, 8, 7, 7, 8, 7, 7, 6, 6, 5, 5)

    /** Where the words of each length start in the dictionary: those of every shorter length come before. */
    private val WORD_OFFSET = IntArray(WORD_BITS.size + 1).also { o ->
        for (l in 4 until WORD_BITS.size) o[l + 1] = o[l] + l * (1 shl WORD_BITS[l])
    }

    /**
     * A word transform of Appendix B: a prefix, the kind, and a suffix. The kinds are 0 for the
     * word as it is, 1 to 9 to leave out that many last bytes, 10 to make the first letter a
     * capital, 11 to make every letter one, and 12 to 20 to leave out 1 to 9 first bytes.
     */
    private class Transform(prefix: String, val kind: Int, suffix: String) {
        val prefix = prefix.encodeToByteArray()
        val suffix = suffix.encodeToByteArray()
    }

    private fun T(prefix: String, kind: Int, suffix: String) = Transform(prefix, kind, suffix)

    /** The 121 transforms of RFC 7932 Appendix B, in order. */
    private val TRANSFORMS: Array<Transform> = arrayOf(
        T("", 0, ""), T("", 0, " "), T(" ", 0, " "), T("", 12, ""),
        T("", 10, " "), T("", 0, " the "), T(" ", 0, ""), T("s ", 0, " "),
        T("", 0, " of "), T("", 10, ""), T("", 0, " and "), T("", 13, ""),
        T("", 1, ""), T(", ", 0, " "), T("", 0, ", "), T(" ", 10, " "),
        T("", 0, " in "), T("", 0, " to "), T("e ", 0, " "), T("", 0, "\""),
        T("", 0, "."), T("", 0, "\">"), T("", 0, "\n"), T("", 3, ""),
        T("", 0, "]"), T("", 0, " for "), T("", 14, ""), T("", 2, ""),
        T("", 0, " a "), T("", 0, " that "), T(" ", 10, ""), T("", 0, ". "),
        T(".", 0, ""), T(" ", 0, ", "), T("", 15, ""), T("", 0, " with "),
        T("", 0, "'"), T("", 0, " from "), T("", 0, " by "), T("", 16, ""),
        T("", 17, ""), T(" the ", 0, ""), T("", 4, ""), T("", 0, ". The "),
        T("", 11, ""), T("", 0, " on "), T("", 0, " as "), T("", 0, " is "),
        T("", 7, ""), T("", 1, "ing "), T("", 0, "\n\t"), T("", 0, ":"),
        T(" ", 0, ". "), T("", 0, "ed "), T("", 20, ""), T("", 18, ""),
        T("", 6, ""), T("", 0, "("), T("", 10, ", "), T("", 8, ""),
        T("", 0, " at "), T("", 0, "ly "), T(" the ", 0, " of "), T("", 5, ""),
        T("", 9, ""), T(" ", 10, ", "), T("", 10, "\""), T(".", 0, "("),
        T("", 11, " "), T("", 10, "\">"), T("", 0, "=\""), T(" ", 0, "."),
        T(".com/", 0, ""), T(" the ", 0, " of the "), T("", 10, "'"), T("", 0, ". This "),
        T("", 0, ","), T(".", 0, " "), T("", 10, "("), T("", 10, "."),
        T("", 0, " not "), T(" ", 0, "=\""), T("", 0, "er "), T(" ", 11, " "),
        T("", 0, "al "), T(" ", 11, ""), T("", 0, "='"), T("", 11, "\""),
        T("", 10, ". "), T(" ", 0, "("), T("", 0, "ful "), T(" ", 10, ". "),
        T("", 0, "ive "), T("", 0, "less "), T("", 11, "'"), T("", 0, "est "),
        T(" ", 10, "."), T("", 11, "\">"), T(" ", 0, "='"), T("", 10, ","),
        T("", 0, "ize "), T("", 11, "."), T(" ", 0, ""), T(" ", 0, ","),
        T("", 10, "=\""), T("", 11, "=\""), T("", 0, "ous "), T("", 11, ", "),
        T("", 10, "='"), T(" ", 10, ","), T(" ", 11, "=\""), T(" ", 11, ", "),
        T("", 11, ","), T("", 11, "("), T("", 11, ". "), T(" ", 11, "."),
        T("", 11, "='"), T(" ", 11, ". "), T(" ", 10, "=\""), T(" ", 11, "='"),
        T(" ", 10, "='"),
    )

    /** Makes the UTF-8 character at [at] a capital as Appendix B's ToUpperCase does, and gives its length. */
    private fun upper(w: ByteArray, at: Int, end: Int): Int {
        val c = w[at].toInt() and 0xFF
        if (end - at == 1 || c < 0xC0) {
            if (c in 'a'.code..'z'.code) w[at] = (c xor 32).toByte()
            return 1
        }
        if (c < 0xE0 || end - at == 2) {
            w[at + 1] = (w[at + 1].toInt() xor 32).toByte()
            return 2
        }
        w[at + 2] = (w[at + 2].toInt() xor 5).toByte()
        return 3
    }

    /** One category of blocks (6): literals, insert-and-copy commands or distances. */
    private class Blocks(val types: Int) {
        var typeCode: Code? = null
        var countCode: Code? = null
        var type = 0
        var last = 0
        var beforeLast = 1
        var left = 1 shl 24
    }

    private class Decoder(data: ByteArray, maximumSize: Int) {
        val bits = Bits(data)
        val out = Output(maximumSize)
        val distances = intArrayOf(4, 11, 15, 16)
        var window = 0

        fun run(): ByteArray {
            window = windowBits()
            while (true) {
                val last = bits.read(1) == 1
                if (last && bits.read(1) == 1) break
                val nibbles = when (val n = bits.read(2)) {
                    3 -> 0
                    else -> 4 + n
                }
                if (nibbles == 0) {
                    if (bits.read(1) != 0) throw BrotliException("a reserved bit that is set")
                    val skipBytes = bits.read(2)
                    var skip = 0
                    for (i in 0 until skipBytes) {
                        val b = bits.read(8)
                        if (i == skipBytes - 1 && skipBytes > 1 && b == 0) throw BrotliException("a metadata length with a byte too many")
                        skip = skip or (b shl (8 * i))
                    }
                    if (skipBytes > 0) skip++
                    bits.align()
                    bits.skip(skip)
                    if (last) break
                    continue
                }
                var length = 0
                for (i in 0 until nibbles) {
                    val nibble = bits.read(4)
                    if (i == nibbles - 1 && nibbles > 4 && nibble == 0) throw BrotliException("a meta-block length with a nibble too many")
                    length = length or (nibble shl (4 * i))
                }
                length++
                val uncompressed = !last && bits.read(1) == 1
                if (uncompressed) {
                    bits.align()
                    bits.bytes(length, out)
                } else {
                    metaBlock(length)
                }
                if (last) break
            }
            return out.data.copyOf(out.size)
        }

        /** WBITS (9.1): 10 to 24. */
        private fun windowBits(): Int {
            if (bits.read(1) == 0) return 16
            val n = bits.read(3)
            if (n != 0) return 17 + n
            val m = bits.read(3)
            if (m == 1) throw BrotliException("a large window, which RFC 7932 does not define")
            return if (m != 0) 8 + m else 17
        }

        private fun blocks(): Blocks {
            val b = Blocks(bits.count256())
            if (b.types >= 2) {
                b.typeCode = bits.prefixCode(b.types + 2)
                b.countCode = bits.prefixCode(26)
                b.left = blockCount(b)
            }
            return b
        }

        private fun blockCount(b: Blocks): Int {
            val s = bits.symbol(b.countCode!!)
            return BLOCK_BASE[s] + bits.read(BLOCK_EXTRA[s])
        }

        /** Takes one item of [b], switching to its next block first when this one is used up. */
        private fun take(b: Blocks) {
            if (b.left == 0) {
                val s = bits.symbol(b.typeCode ?: throw BrotliException("a block that runs out with one block type"))
                var t = when (s) {
                    0 -> b.beforeLast
                    1 -> b.last + 1
                    else -> s - 2
                }
                if (t >= b.types) t -= b.types
                b.beforeLast = b.last
                b.last = t
                b.type = t
                b.left = blockCount(b)
            }
            b.left--
        }

        private fun contextMap(size: Int, trees: Int): IntArray {
            val map = IntArray(size)
            if (trees < 2) return map
            val rleMax = if (bits.read(1) == 1) bits.read(4) + 1 else 0
            val code = bits.prefixCode(trees + rleMax)
            var i = 0
            while (i < size) {
                val s = bits.symbol(code)
                when {
                    s == 0 -> map[i++] = 0
                    s <= rleMax -> {
                        val run = (1 shl s) + bits.read(s)
                        if (run > size - i) throw BrotliException("a context map run past its end")
                        repeat(run) { map[i++] = 0 }
                    }
                    else -> map[i++] = s - rleMax
                }
            }
            if (bits.read(1) == 1) {
                // The inverse move-to-front transform (7.3).
                val mtf = IntArray(256) { it }
                for (k in map.indices) {
                    val index = map[k]
                    val v = mtf[index]
                    map[k] = v
                    for (j in index downTo 1) mtf[j] = mtf[j - 1]
                    mtf[0] = v
                }
            }
            return map
        }

        private fun metaBlock(length: Int) {
            val literals = blocks()
            val commands = blocks()
            val distanceBlocks = blocks()
            val postfix = bits.read(2)
            val direct = bits.read(4) shl postfix
            val modes = IntArray(literals.types) { bits.read(2) }
            val literalTrees = bits.count256()
            val literalMap = contextMap(64 * literals.types, literalTrees)
            val distanceTrees = bits.count256()
            val distanceMap = contextMap(4 * distanceBlocks.types, distanceTrees)
            val literalCodes = Array(literalTrees) { bits.prefixCode(256) }
            val commandCodes = Array(commands.types) { bits.prefixCode(704) }
            val distanceCodes = Array(distanceTrees) { bits.prefixCode(16 + direct + (48 shl postfix)) }
            val maxBackward = (1 shl window) - 16
            var left = length
            while (left > 0) {
                take(commands)
                val command = bits.symbol(commandCodes[commands.type])
                val cell = command shr 6
                val insertCode = CELL_INSERT[cell] + ((command shr 3) and 7)
                val copyCode = CELL_COPY[cell] + (command and 7)
                val insert = INSERT_BASE[insertCode] + bits.read(INSERT_EXTRA[insertCode])
                val copy = COPY_BASE[copyCode] + bits.read(COPY_EXTRA[copyCode])
                if (insert > left) throw BrotliException("an insert of $insert bytes where $left are left")
                repeat(insert) {
                    take(literals)
                    val p1 = out.last(1)
                    val p2 = out.last(2)
                    val context = when (modes[literals.type]) {
                        0 -> p1 and 0x3F
                        1 -> p1 shr 2
                        2 -> LUT0[p1] or LUT1[p2]
                        else -> (LUT2[p1] shl 3) or LUT2[p2]
                    }
                    out.add(bits.symbol(literalCodes[literalMap[64 * literals.type + context]]))
                }
                left -= insert
                // A meta-block can end after the literals of its last command (9.3).
                if (left == 0) break
                val code: Int
                val distance: Int
                if (cell < 2) {
                    code = 0
                    distance = distances[0]
                } else {
                    take(distanceBlocks)
                    val context = if (copy > 4) 3 else copy - 2
                    code = bits.symbol(distanceCodes[distanceMap[4 * distanceBlocks.type + context]])
                    distance = distance(code, postfix, direct)
                }
                val reach = minOf(maxBackward, out.size)
                if (distance > reach) {
                    dictionaryWord(distance - reach - 1, copy, left).let { left -= it }
                } else {
                    if (copy > left) throw BrotliException("a copy of $copy bytes where $left are left")
                    out.copy(distance, copy)
                    left -= copy
                    if (code != 0) {
                        distances[3] = distances[2]
                        distances[2] = distances[1]
                        distances[1] = distances[0]
                        distances[0] = distance
                    }
                }
            }
        }

        /** The distance of distance code [code] (4). */
        private fun distance(code: Int, postfix: Int, direct: Int): Int {
            if (code < 16) {
                val d = distances[SHORT_FROM[code]] + SHORT_ADD[code]
                if (d <= 0) throw BrotliException("a distance of $d")
                return d
            }
            if (code < 16 + direct) return code - 15
            val c = code - direct - 16
            val extraBits = 1 + (c shr (postfix + 1))
            val high = c shr postfix
            val low = c and ((1 shl postfix) - 1)
            val offset = ((2 + (high and 1)) shl extraBits) - 4
            return ((offset + bits.read(extraBits)) shl postfix) + low + direct + 1
        }

        /** Writes word [id] of length [length] of the static dictionary, transformed (8), and gives the bytes written. */
        private fun dictionaryWord(id: Int, length: Int, left: Int): Int {
            if (length !in 4..24) throw BrotliException("a dictionary word of length $length")
            val wordBits = WORD_BITS[length]
            val index = id and ((1 shl wordBits) - 1)
            val t = id shr wordBits
            if (t >= TRANSFORMS.size) throw BrotliException("dictionary transform $t")
            val transform = TRANSFORMS[t]
            val from = WORD_OFFSET[length] + index * length
            val word = BrotliDictionary.data.copyOfRange(from, from + length)
            var start = 0
            var end = length
            when (transform.kind) {
                in 1..9 -> end = maxOf(0, length - transform.kind)
                in 12..20 -> start = minOf(length, transform.kind - 11)
                10 -> if (length > 0) upper(word, 0, end)
                11 -> {
                    var at = 0
                    while (at < end) at += upper(word, at, end)
                }
            }
            val total = transform.prefix.size + (end - start) + transform.suffix.size
            if (total > left) throw BrotliException("a dictionary word of $total bytes where $left are left")
            out.add(transform.prefix, 0, transform.prefix.size)
            out.add(word, start, end - start)
            out.add(transform.suffix, 0, transform.suffix.size)
            return total
        }
    }
}
