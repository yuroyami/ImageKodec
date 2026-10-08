// Altered Kotlin port of parts of libjxl 0.11.1 (lib/jxl/dec_ans.cc, dec_ans.h, ans_common.cc,
// dec_huffman.cc, huffman_table.cc, dec_context_map.cc, coeff_order.cc).
// Copyright (c) the JPEG XL Project Authors. All rights reserved.
// Use of the original source is governed by a BSD-style license; see NOTICE.

package io.github.yuroyami.imagekodec.codec.jxl

private const val ANS_LOG_TAB_SIZE = 12
private const val ANS_TAB_SIZE = 1 shl ANS_LOG_TAB_SIZE
private const val ANS_SIGNATURE = 0x13
private const val PREFIX_MAX_BITS = 15
private const val HUFFMAN_TABLE_BITS = 8
private const val WINDOW_SIZE = 1 shl 20
private const val WINDOW_MASK = WINDOW_SIZE - 1
private const val WINDOW_START = 1 shl 10
private const val NUM_SPECIAL_DISTANCES = 120
private const val MAX_CLUSTERS = 256

/** The (x, y) pairs LZ77 distances below 120 stand for, in units of one sample and one row. */
private val SPECIAL_DISTANCES = byteArrayOf(
    0, 1, 1, 0, 1, 1, -1, 1, 0, 2, 2, 0, 1, 2, -1, 2, 2, 1, -2, 1, 2, 2, -2, 2, 0, 3, 3, 0, 1, 3, -1, 3,
    3, 1, -3, 1, 2, 3, -2, 3, 3, 2, -3, 2, 0, 4, 4, 0, 1, 4, -1, 4, 4, 1, -4, 1, 3, 3, -3, 3, 2, 4, -2, 4,
    4, 2, -4, 2, 0, 5, 3, 4, -3, 4, 4, 3, -4, 3, 5, 0, 1, 5, -1, 5, 5, 1, -5, 1, 2, 5, -2, 5, 5, 2, -5, 2,
    4, 4, -4, 4, 3, 5, -3, 5, 5, 3, -5, 3, 0, 6, 6, 0, 1, 6, -1, 6, 6, 1, -6, 1, 2, 6, -2, 6, 6, 2, -6, 2,
    4, 5, -4, 5, 5, 4, -5, 4, 3, 6, -3, 6, 6, 3, -6, 3, 0, 7, 7, 0, 1, 7, -1, 7, 5, 5, -5, 5, 7, 1, -7, 1,
    4, 6, -4, 6, 6, 4, -6, 4, 2, 7, -2, 7, 7, 2, -7, 2, 3, 7, -3, 7, 7, 3, -7, 3, 5, 6, -5, 6, 6, 5, -6, 5,
    8, 0, 4, 7, -4, 7, 7, 4, -7, 4, 8, 1, 8, 2, 6, 6, -6, 6, 8, 3, 5, 7, -5, 7, 7, 5, -7, 5, 8, 4, 6, 7,
    -6, 7, 7, 6, -7, 6, 8, 5, 7, 7, -7, 7, 8, 6, 8, 7,
)

/** The fixed prefix code for the logarithm of each ANS frequency: code length, then value. */
private val LOG_COUNT_CODE = byteArrayOf(
    3, 10, 7, 12, 3, 7, 4, 3, 3, 6, 3, 8, 3, 9, 4, 5, 3, 10, 4, 4, 3, 7, 4, 1, 3, 6, 3, 8, 3, 9, 4, 2,
    3, 10, 5, 0, 3, 7, 4, 3, 3, 6, 3, 8, 3, 9, 4, 5, 3, 10, 4, 4, 3, 7, 4, 1, 3, 6, 3, 8, 3, 9, 4, 2,
    3, 10, 6, 11, 3, 7, 4, 3, 3, 6, 3, 8, 3, 9, 4, 5, 3, 10, 4, 4, 3, 7, 4, 1, 3, 6, 3, 8, 3, 9, 4, 2,
    3, 10, 5, 0, 3, 7, 4, 3, 3, 6, 3, 8, 3, 9, 4, 5, 3, 10, 4, 4, 3, 7, 4, 1, 3, 6, 3, 8, 3, 9, 4, 2,
    3, 10, 7, 13, 3, 7, 4, 3, 3, 6, 3, 8, 3, 9, 4, 5, 3, 10, 4, 4, 3, 7, 4, 1, 3, 6, 3, 8, 3, 9, 4, 2,
    3, 10, 5, 0, 3, 7, 4, 3, 3, 6, 3, 8, 3, 9, 4, 5, 3, 10, 4, 4, 3, 7, 4, 1, 3, 6, 3, 8, 3, 9, 4, 2,
    3, 10, 6, 11, 3, 7, 4, 3, 3, 6, 3, 8, 3, 9, 4, 5, 3, 10, 4, 4, 3, 7, 4, 1, 3, 6, 3, 8, 3, 9, 4, 2,
    3, 10, 5, 0, 3, 7, 4, 3, 3, 6, 3, 8, 3, 9, 4, 5, 3, 10, 4, 4, 3, 7, 4, 1, 3, 6, 3, 8, 3, 9, 4, 2,
)

private val CODE_LENGTH_CODE_ORDER = intArrayOf(1, 2, 3, 4, 0, 5, 17, 6, 16, 7, 8, 9, 10, 11, 12, 13, 14, 15)

/** The fixed prefix code for the lengths of the code length code: bits shl 8 or value. */
private val CODE_LENGTH_LENGTH_CODE = intArrayOf(
    0x200, 0x204, 0x203, 0x302, 0x200, 0x204, 0x203, 0x401,
    0x200, 0x204, 0x203, 0x302, 0x200, 0x204, 0x203, 0x405,
)

internal fun floorLog2(v: Int): Int = 31 - v.countLeadingZeroBits()

internal fun ceilLog2(v: Int): Int = if (v <= 1) 0 else 32 - (v - 1).countLeadingZeroBits()

/**
 * The entropy code of one stream: what each context's cluster is, how a token widens into
 * an integer, the ANS distributions or prefix codes of every cluster, and the LZ77
 * parameters. [JxlSymbolReader] reads integers with it.
 */
internal class JxlCode private constructor(
    val contextMap: IntArray,
    val usePrefix: Boolean,
    val logAlphaSize: Int,
    private val numClusters: Int,
) {
    // How a token widens into an integer, for each cluster.
    val splitExponent = IntArray(numClusters)
    val msbInToken = IntArray(numClusters)
    val lsbInToken = IntArray(numClusters)

    // ANS: one alias table of 1 shl logAlphaSize entries for each cluster.
    var aliasCutoff = IntArray(0)
    var aliasRight = IntArray(0)
    var aliasFreq0 = IntArray(0)
    var aliasOffsets1 = IntArray(0)
    var aliasFreq1 = IntArray(0)

    /** The cluster's only symbol when its ANS distribution has one, else -1. */
    var singleSymbol = IntArray(0)

    /** The only symbol [cluster] can give, or -1 when it can give several or the code is a prefix code. */
    fun onlySymbol(cluster: Int): Int = if (usePrefix) -1 else singleSymbol[cluster]

    // Prefix codes: a two-level table for each cluster, each entry bits shl 16 or value.
    var huffman: Array<IntArray> = emptyArray()

    var lz77 = false
    var lzMinSymbol = 0
    var lzMinLength = 0
    var lzSplitExponent = 0
    var lzMsbInToken = 0
    var lzLsbInToken = 0
    var lzCluster = 0

    companion object {
        /** Reads the code of a stream that has [numContexts] contexts. */
        fun read(br: JxlBitReader, numContexts: Int, disallowLz77: Boolean = false): JxlCode {
            var contexts = numContexts
            val lz77 = br.bool()
            var lzMinSymbol = 0
            var lzMinLength = 0
            var lzConfig = 0
            if (lz77) {
                lzMinSymbol = br.u32(fv(224), fv(512), fv(4096), fo(15, 8))
                lzMinLength = br.u32(fv(3), fv(4), fo(2, 5), fo(8, 9))
                contexts++
                lzConfig = readUintConfig(br, 8)
                if (disallowLz77) jxlFail("LZ77 in a stream that must not use it")
            }
            val contextMap = IntArray(contexts)
            val numClusters = if (contexts > 1) readContextMap(br, contextMap) else 1
            val usePrefix = br.bool()
            val logAlphaSize = if (usePrefix) PREFIX_MAX_BITS else br.bits(2) + 5
            val code = JxlCode(contextMap, usePrefix, logAlphaSize, numClusters)
            code.lz77 = lz77
            code.lzMinSymbol = lzMinSymbol
            code.lzMinLength = lzMinLength
            code.lzSplitExponent = lzConfig and 0xFF
            code.lzMsbInToken = (lzConfig ushr 8) and 0xFF
            code.lzLsbInToken = lzConfig ushr 16
            code.lzCluster = contextMap[contexts - 1]
            for (c in 0 until numClusters) {
                val config = readUintConfig(br, logAlphaSize)
                code.splitExponent[c] = config and 0xFF
                code.msbInToken[c] = (config ushr 8) and 0xFF
                code.lsbInToken[c] = config ushr 16
            }
            if (usePrefix) code.readPrefixCodes(br) else code.readAnsCodes(br)
            return code
        }

        /** split_exponent, msb_in_token shl 8 and lsb_in_token shl 16 of one hybrid integer configuration. */
        private fun readUintConfig(br: JxlBitReader, logAlphaSize: Int): Int {
            val split = br.bits(ceilLog2(logAlphaSize + 1))
            var msb = 0
            var lsb = 0
            if (split != logAlphaSize) {
                msb = br.bits(ceilLog2(split + 1))
                if (msb > split) jxlFail("invalid hybrid integer configuration")
                lsb = br.bits(ceilLog2(split - msb + 1))
            }
            if (lsb + msb > split) jxlFail("invalid hybrid integer configuration")
            return split or (msb shl 8) or (lsb shl 16)
        }

        /** Fills [map] with the cluster of each context and returns the number of clusters. */
        fun readContextMap(br: JxlBitReader, map: IntArray): Int {
            if (br.bool()) {
                val bitsPerEntry = br.bits(2)
                if (bitsPerEntry != 0) for (i in map.indices) map[i] = br.bits(bitsPerEntry)
            } else {
                val useMtf = br.bool()
                val code = read(br, 1, disallowLz77 = map.size <= 2)
                val reader = JxlSymbolReader(code, br)
                for (i in map.indices) {
                    val cluster = reader.read(0)
                    if (cluster < 0 || cluster >= MAX_CLUSTERS) jxlFail("context map names cluster $cluster")
                    map[i] = cluster
                }
                reader.checkFinalState()
                if (useMtf) inverseMoveToFront(map)
            }
            val clusters = map.max() + 1
            val seen = BooleanArray(clusters)
            for (c in map) seen[c] = true
            if (seen.any { !it }) jxlFail("context map skips a cluster")
            return clusters
        }

        private fun inverseMoveToFront(v: IntArray) {
            val mtf = IntArray(256) { it }
            for (i in v.indices) {
                val index = v[i]
                val value = mtf[index]
                v[i] = value
                if (index != 0) {
                    mtf.copyInto(mtf, 1, 0, index)
                    mtf[0] = value
                }
            }
        }

        private fun varLenUint8(br: JxlBitReader): Int {
            if (!br.bool()) return 0
            val n = br.bits(3)
            return if (n == 0) 1 else br.bits(n) + (1 shl n)
        }

        private fun varLenUint16(br: JxlBitReader): Int {
            if (!br.bool()) return 0
            val n = br.bits(4)
            return if (n == 0) 1 else br.bits(n) + (1 shl n)
        }

        /** The frequencies of one ANS distribution, which sum to 4096. */
        private fun readHistogram(br: JxlBitReader): IntArray {
            val range = ANS_TAB_SIZE
            if (br.bool()) {
                val two = br.bool()
                val s0 = varLenUint8(br)
                if (!two) return IntArray(s0 + 1).also { it[s0] = range }
                val s1 = varLenUint8(br)
                if (s0 == s1) jxlFail("ANS distribution names one symbol twice")
                val counts = IntArray(maxOf(s0, s1) + 1)
                counts[s0] = br.bits(ANS_LOG_TAB_SIZE)
                counts[s1] = range - counts[s0]
                return counts
            }
            if (br.bool()) {
                val alphabet = varLenUint8(br) + 1
                return IntArray(alphabet) { range / alphabet + if (it < range % alphabet) 1 else 0 }
            }
            var log = 0
            while (log < 3 && br.bool()) log++
            val shift = (br.bits(log) or (1 shl log)) - 1
            if (shift > ANS_LOG_TAB_SIZE + 1) jxlFail("invalid ANS distribution shift")
            val length = varLenUint8(br) + 3
            val counts = IntArray(length)
            val logCounts = IntArray(length)
            val same = IntArray(length)
            var omitLog = -1
            var omitPos = -1
            var i = 0
            while (i < length) {
                val index = br.peek(7) * 2
                br.consume(LOG_COUNT_CODE[index].toInt())
                logCounts[i] = LOG_COUNT_CODE[index + 1].toInt()
                if (logCounts[i] == ANS_LOG_TAB_SIZE + 1) {
                    val run = varLenUint8(br)
                    same[i] = run + 5
                    i += run + 4
                    continue
                }
                if (logCounts[i] > omitLog) {
                    omitLog = logCounts[i]
                    omitPos = i
                }
                i++
            }
            if (omitPos < 0) jxlFail("invalid ANS distribution")
            var total = 0
            var prev = 0
            var numSame = 0
            for (k in 0 until length) {
                if (same[k] != 0) {
                    numSame = same[k] - 1
                    prev = if (k > 0) counts[k - 1] else 0
                }
                if (numSame > 0) {
                    counts[k] = prev
                    numSame--
                } else {
                    val code = logCounts[k]
                    if (k == omitPos || code == 0) continue
                    if (code == 1) {
                        counts[k] = 1
                    } else {
                        // How many bits of the frequency are stored, libjxl's GetPopulationCountPrecision.
                        val precision = minOf(code - 1, shift - ((ANS_LOG_TAB_SIZE - (code - 1)) shr 1)).coerceAtLeast(0)
                        counts[k] = (1 shl (code - 1)) + (br.bits(precision) shl (code - 1 - precision))
                    }
                }
                total += counts[k]
            }
            counts[omitPos] = range - total
            if (counts[omitPos] <= 0) jxlFail("ANS frequencies sum past their range")
            return counts
        }
    }

    private fun readAnsCodes(br: JxlBitReader) {
        val tableSize = 1 shl logAlphaSize
        val total = numClusters * tableSize
        aliasCutoff = IntArray(total)
        aliasRight = IntArray(total)
        aliasFreq0 = IntArray(total)
        aliasOffsets1 = IntArray(total)
        aliasFreq1 = IntArray(total)
        singleSymbol = IntArray(numClusters)
        for (c in 0 until numClusters) {
            var counts = readHistogram(br)
            if (counts.size > tableSize) jxlFail("ANS alphabet of ${counts.size} symbols is too large")
            var size = counts.size
            while (size > 0 && counts[size - 1] == 0) size--
            if (size == 0) {
                counts = intArrayOf(ANS_TAB_SIZE)
                size = 1
            }
            initAliasTable(counts, size, c * tableSize)
        }
    }

    /** Builds the alias table of one cluster at [base], as libjxl's InitAliasTable lays it out. */
    private fun initAliasTable(counts: IntArray, size: Int, base: Int) {
        val tableSize = 1 shl logAlphaSize
        val entrySize = ANS_TAB_SIZE shr logAlphaSize
        var sum = 0
        var single = -1
        for (s in 0 until size) {
            sum += counts[s]
            if (counts[s] == ANS_TAB_SIZE) single = s
        }
        if (sum != ANS_TAB_SIZE) jxlFail("ANS frequencies do not sum to their range")
        singleSymbol[base / tableSize] = single
        if (single != -1) {
            for (i in 0 until tableSize) {
                aliasRight[base + i] = single
                aliasCutoff[base + i] = 0
                aliasOffsets1[base + i] = entrySize * i
                aliasFreq0[base + i] = 0
                aliasFreq1[base + i] = ANS_TAB_SIZE
            }
            return
        }
        val cutoffs = IntArray(tableSize)
        val underfull = IntArray(tableSize)
        val overfull = IntArray(tableSize)
        var under = 0
        var over = 0
        for (i in 0 until tableSize) {
            cutoffs[i] = if (i < size) counts[i] else 0
            if (cutoffs[i] > entrySize) overfull[over++] = i else if (cutoffs[i] < entrySize) underfull[under++] = i
        }
        while (over > 0) {
            val o = overfull[--over]
            if (under == 0) jxlFail("invalid ANS distribution")
            val u = underfull[--under]
            cutoffs[o] -= entrySize - cutoffs[u]
            aliasRight[base + u] = o
            aliasOffsets1[base + u] = cutoffs[o]
            if (cutoffs[o] < entrySize) underfull[under++] = o else if (cutoffs[o] > entrySize) overfull[over++] = o
        }
        for (i in 0 until tableSize) {
            if (cutoffs[i] == entrySize) {
                aliasRight[base + i] = i
                aliasOffsets1[base + i] = 0
                aliasCutoff[base + i] = 0
            } else {
                aliasOffsets1[base + i] -= cutoffs[i]
                aliasCutoff[base + i] = cutoffs[i]
            }
            val right = aliasRight[base + i]
            aliasFreq0[base + i] = if (i < size) counts[i] else 0
            aliasFreq1[base + i] = if (right < size) counts[right] else 0
        }
    }

    private fun readPrefixCodes(br: JxlBitReader) {
        val sizes = IntArray(numClusters) { varLenUint16(br) + 1 }
        huffman = Array(numClusters) { c ->
            if (sizes[c] > 1) readPrefixCode(br, sizes[c]) else IntArray(1 shl HUFFMAN_TABLE_BITS)
        }
    }

    private fun readPrefixCode(br: JxlBitReader, alphabetSize: Int): IntArray {
        val skip = br.bits(2)
        if (skip == 1) return readSimplePrefixCode(br, alphabetSize)
        val lengthLengths = IntArray(18)
        var space = 32
        var numCodes = 0
        var i = skip
        while (i < 18 && space > 0) {
            val entry = CODE_LENGTH_LENGTH_CODE[br.peek(4)]
            br.consume(entry ushr 8)
            val v = entry and 0xFF
            lengthLengths[CODE_LENGTH_CODE_ORDER[i]] = v
            if (v != 0) {
                space -= 32 shr v
                numCodes++
            }
            i++
        }
        if (numCodes != 1 && space != 0) jxlFail("invalid prefix code length code")
        val lengths = readCodeLengths(br, lengthLengths, alphabetSize)
        val table = IntArray(alphabetSize + 376)
        val used = buildHuffmanTable(table, HUFFMAN_TABLE_BITS, lengths, alphabetSize)
        if (used == 0) jxlFail("invalid prefix code")
        return table
    }

    private fun readCodeLengths(br: JxlBitReader, lengthLengths: IntArray, numSymbols: Int): IntArray {
        val table = IntArray(32)
        if (buildHuffmanTable(table, 5, lengthLengths, 18) == 0) jxlFail("invalid prefix code length code")
        val lengths = IntArray(numSymbols)
        var symbol = 0
        var prevLength = 8
        var repeat = 0
        var repeatLength = 0
        var space = 32768
        while (symbol < numSymbols && space > 0) {
            val entry = table[br.peek(5)]
            br.consume(entry ushr 16)
            val length = entry and 0xFFFF
            if (length < 16) {
                repeat = 0
                lengths[symbol++] = length
                if (length != 0) {
                    prevLength = length
                    space -= 32768 shr length
                }
            } else {
                val extraBits = length - 14
                val newLength = if (length == 16) prevLength else 0
                if (repeatLength != newLength) {
                    repeat = 0
                    repeatLength = newLength
                }
                val oldRepeat = repeat
                if (repeat > 0) repeat = (repeat - 2) shl extraBits
                repeat += br.bits(extraBits) + 3
                val delta = repeat - oldRepeat
                if (symbol + delta > numSymbols) jxlFail("prefix code lengths run past the alphabet")
                lengths.fill(repeatLength, symbol, symbol + delta)
                symbol += delta
                if (repeatLength != 0) space -= delta shl (15 - repeatLength)
            }
        }
        if (space != 0) jxlFail("prefix code is not complete")
        return lengths
    }

    private fun readSimplePrefixCode(br: JxlBitReader, alphabetSize: Int): IntArray {
        val maxBits = if (alphabetSize > 1) floorLog2(alphabetSize - 1) + 1 else 0
        var count = br.bits(2) + 1
        val s = IntArray(4)
        for (i in 0 until count) {
            s[i] = br.bits(maxBits)
            if (s[i] >= alphabetSize) jxlFail("prefix code symbol outside its alphabet")
        }
        for (i in 0 until count - 1) for (j in i + 1 until count) {
            if (s[i] == s[j]) jxlFail("prefix code names one symbol twice")
        }
        if (count == 4) count += br.bits(1)
        fun swap(i: Int, j: Int) {
            val t = s[j]
            s[j] = s[i]
            s[i] = t
        }
        fun e(bits: Int, symbol: Int) = (bits shl 16) or symbol
        val table = IntArray(1 shl HUFFMAN_TABLE_BITS)
        var size = 1
        when (count) {
            1 -> table[0] = e(0, s[0])
            2 -> {
                if (s[0] > s[1]) swap(0, 1)
                table[0] = e(1, s[0])
                table[1] = e(1, s[1])
                size = 2
            }
            3 -> {
                if (s[1] > s[2]) swap(1, 2)
                table[0] = e(1, s[0])
                table[2] = e(1, s[0])
                table[1] = e(2, s[1])
                table[3] = e(2, s[2])
                size = 4
            }
            4 -> {
                for (i in 0 until 3) for (j in i + 1 until 4) if (s[i] > s[j]) swap(i, j)
                table[0] = e(2, s[0])
                table[2] = e(2, s[1])
                table[1] = e(2, s[2])
                table[3] = e(2, s[3])
                size = 4
            }
            else -> {
                if (s[2] > s[3]) swap(2, 3)
                table[0] = e(1, s[0])
                table[1] = e(2, s[1])
                table[2] = e(1, s[0])
                table[3] = e(3, s[2])
                table[4] = e(1, s[0])
                table[5] = e(2, s[1])
                table[6] = e(1, s[0])
                table[7] = e(3, s[3])
                size = 8
            }
        }
        while (size != table.size) {
            table.copyInto(table, size, 0, size)
            size = size shl 1
        }
        return table
    }

    /**
     * Builds the two-level lookup table of a prefix code from its code [lengths] and returns
     * how many entries it used, or 0 when the lengths describe no code. Brotli's construction.
     */
    private fun buildHuffmanTable(root: IntArray, rootBits: Int, lengths: IntArray, numSymbols: Int): Int {
        val count = IntArray(PREFIX_MAX_BITS + 1)
        for (i in 0 until numSymbols) count[lengths[i]]++
        val offset = IntArray(PREFIX_MAX_BITS + 1)
        var maxLength = 1
        var sum = 0
        for (len in 1..PREFIX_MAX_BITS) {
            offset[len] = sum
            if (count[len] != 0) {
                sum += count[len]
                maxLength = len
            }
        }
        if (sum == 0) return 0
        val sorted = IntArray(numSymbols)
        for (symbol in 0 until numSymbols) {
            if (lengths[symbol] != 0) sorted[offset[lengths[symbol]]++] = symbol
        }
        var tableBits = rootBits
        var tableSize = 1 shl tableBits
        var totalSize = tableSize
        if (offset[PREFIX_MAX_BITS] == 1) {
            for (key in 0 until totalSize) root[key] = sorted[0]
            return totalSize
        }
        // Kraft's sum decides whether the lengths are a complete code, so no walk leaves the table.
        var kraft = 0L
        for (len in 1..PREFIX_MAX_BITS) kraft += count[len].toLong() shl (PREFIX_MAX_BITS - len)
        if (kraft != 1L shl PREFIX_MAX_BITS) return 0

        fun nextKey(key: Int, len: Int): Int {
            var step = 1 shl (len - 1)
            while (key and step != 0) step = step shr 1
            return (key and (step - 1)) + step
        }

        fun replicate(at: Int, step: Int, end: Int, code: Int) {
            var e = end
            do {
                e -= step
                root[at + e] = code
            } while (e > 0)
        }

        if (tableBits > maxLength) {
            tableBits = maxLength
            tableSize = 1 shl tableBits
        }
        var key = 0
        var symbol = 0
        var bits = 1
        var step = 2
        do {
            while (count[bits] != 0) {
                replicate(key, step, tableSize, (bits shl 16) or sorted[symbol++])
                key = nextKey(key, bits)
                count[bits]--
            }
            step = step shl 1
        } while (++bits <= tableBits)
        while (totalSize != tableSize) {
            root.copyInto(root, tableSize, 0, tableSize)
            tableSize = tableSize shl 1
        }
        val mask = totalSize - 1
        var low = -1
        var table = 0
        var len = rootBits + 1
        step = 2
        while (len <= maxLength) {
            while (count[len] != 0) {
                if ((key and mask) != low) {
                    table += tableSize
                    // The width of the next second-level table.
                    var l = len
                    var left = 1 shl (l - rootBits)
                    while (l < PREFIX_MAX_BITS) {
                        if (left <= count[l]) break
                        left -= count[l]
                        l++
                        left = left shl 1
                    }
                    tableBits = l - rootBits
                    tableSize = 1 shl tableBits
                    totalSize += tableSize
                    if (totalSize > root.size) return 0
                    low = key and mask
                    root[low] = ((tableBits + rootBits) shl 16) or (table - low)
                }
                replicate(table + (key shr rootBits), step, tableSize, ((len - rootBits) shl 16) or sorted[symbol++])
                key = nextKey(key, len)
                count[len]--
            }
            len++
            step = step shl 1
        }
        return totalSize
    }
}

/**
 * Reads the integers of one entropy-coded stream of [code] from [br]. [distanceMultiplier]
 * is the width of the image the stream describes, for LZ77's two-dimensional distances,
 * or 0 when the stream is not an image.
 */
internal class JxlSymbolReader(private val code: JxlCode, private val br: JxlBitReader, distanceMultiplier: Int = 0) {
    private val usePrefix = code.usePrefix
    private var state = ANS_SIGNATURE shl 16
    private val logAlphaSize = code.logAlphaSize
    private val logEntrySize = ANS_LOG_TAB_SIZE - logAlphaSize
    private val entrySizeMinus1 = (1 shl logEntrySize) - 1
    private val contextMap = code.contextMap

    // The last values decoded, for LZ77 copies. It grows as values come, up to WINDOW_SIZE.
    private var window: IntArray? = if (code.lz77) IntArray(WINDOW_START) else null
    private val lzThreshold = if (code.lz77) code.lzMinSymbol else Int.MAX_VALUE
    private var numDecoded = 0
    private var numToCopy = 0
    private var copyPos = 0
    private val specialDistances: IntArray? = if (code.lz77 && distanceMultiplier != 0) {
        IntArray(NUM_SPECIAL_DISTANCES) {
            maxOf(1, SPECIAL_DISTANCES[it * 2] + distanceMultiplier * SPECIAL_DISTANCES[it * 2 + 1])
        }
    } else {
        null
    }

    init {
        if (!usePrefix) state = br.bits(32)
    }

    /** True when the stream uses LZ77, so a run of equal values cannot be skipped. */
    val usesLz77: Boolean get() = window != null

    private fun readToken(cluster: Int): Int {
        if (usePrefix) {
            val table = code.huffman[cluster]
            var index = br.peek(HUFFMAN_TABLE_BITS)
            var entry = table[index]
            val bits = entry ushr 16
            if (bits > HUFFMAN_TABLE_BITS) {
                // A root entry of a longer code holds the offset of its second-level table.
                br.consume(HUFFMAN_TABLE_BITS)
                index += (entry and 0xFFFF) + br.peek(bits - HUFFMAN_TABLE_BITS)
                entry = table[index]
            }
            br.consume(entry ushr 16)
            return entry and 0xFFFF
        }
        val res = state and (ANS_TAB_SIZE - 1)
        val i = (cluster shl logAlphaSize) + (res ushr logEntrySize)
        val pos = res and entrySizeMinus1
        val symbol: Int
        val offset: Int
        val freq: Int
        if (pos >= code.aliasCutoff[i]) {
            symbol = code.aliasRight[i]
            offset = code.aliasOffsets1[i] + pos
            freq = code.aliasFreq1[i]
        } else {
            symbol = res ushr logEntrySize
            offset = pos
            freq = code.aliasFreq0[i]
        }
        state = freq * (state ushr ANS_LOG_TAB_SIZE) + offset
        if (state ushr 16 == 0) state = (state shl 16) or br.bits(16)
        return symbol
    }

    private fun hybrid(split: Int, msb: Int, lsb: Int, tokenIn: Int): Int {
        val splitToken = 1 shl split
        if (tokenIn < splitToken) return tokenIn
        val nbits = (split - (msb + lsb) + ((tokenIn - splitToken) ushr (msb + lsb))) and 31
        val low = tokenIn and ((1 shl lsb) - 1)
        val token = tokenIn ushr lsb
        val bits = br.bits(nbits).toLong() and 0xFFFFFFFFL
        val high = ((1 shl msb) or (token and ((1 shl msb) - 1))).toLong()
        return ((((high shl nbits) or bits) shl lsb) or low.toLong()).toInt()
    }

    /** The next integer of context [ctx], as an unsigned 32-bit value. */
    fun read(ctx: Int): Int = readClustered(contextMap[ctx])

    /** The next integer of cluster [cluster], for a caller that mapped the context itself. */
    fun readClustered(cluster: Int): Int {
        val w = window
        if (w == null) {
            return hybrid(code.splitExponent[cluster], code.msbInToken[cluster], code.lsbInToken[cluster], readToken(cluster))
        }
        // The copy length is an unsigned 32-bit count, as in libjxl, so it is compared with zero only.
        if (numToCopy != 0) return copy()
        val token = readToken(cluster)
        if (token >= lzThreshold) {
            numToCopy = hybrid(code.lzSplitExponent, code.lzMsbInToken, code.lzLsbInToken, token - lzThreshold) + code.lzMinLength
            val lz = code.lzCluster
            var d = hybrid(code.splitExponent[lz], code.msbInToken[lz], code.lsbInToken[lz], readToken(lz)).toLong() and 0xFFFFFFFFL
            val special = specialDistances
            d = if (special != null) {
                if (d < NUM_SPECIAL_DISTANCES) special[d.toInt()].toLong() else d + 1 - NUM_SPECIAL_DISTANCES
            } else {
                d + 1
            }
            // A distance past what was decoded, or past the window, is held to it.
            val decoded = numDecoded.toLong() and 0xFFFFFFFFL
            if (d > decoded) d = decoded
            if (d > WINDOW_SIZE) d = WINDOW_SIZE.toLong()
            copyPos = numDecoded - d.toInt()
            if (d == 0L) {
                // A copy with nothing decoded gives zeros. A part of the window that is not allocated yet is zero as well.
                val fill = if (numToCopy < 0 || numToCopy > w.size) w.size else numToCopy
                w.fill(0, 0, fill)
            }
            // A length that wrapped past 32 bits gives one zero, as libjxl does.
            if ((numToCopy.toLong() and 0xFFFFFFFFL) < code.lzMinLength) return 0
            return copy()
        }
        val v = hybrid(code.splitExponent[cluster], code.msbInToken[cluster], code.lsbInToken[cluster], token)
        slot(numDecoded and WINDOW_MASK)[numDecoded++ and WINDOW_MASK] = v
        return v
    }

    /** The window, grown to hold index [at]. */
    private fun slot(at: Int): IntArray {
        val w = window!!
        if (at < w.size) return w
        val grown = w.copyOf(minOf(WINDOW_SIZE, maxOf(w.size * 2, at + 1)))
        window = grown
        return grown
    }

    /** One value of an LZ77 copy. It never reads past the value it is about to write. */
    private fun copy(): Int {
        val to = numDecoded++ and WINDOW_MASK
        val w = slot(to)
        val from = copyPos++ and WINDOW_MASK
        val v = if (from < w.size) w[from] else 0
        numToCopy--
        w[to] = v
        return v
    }

    /** An ANS stream ends in the state it began with; a prefix-coded stream has nothing to check. */
    fun checkFinalState() {
        if (state != ANS_SIGNATURE shl 16) jxlFail("entropy-coded stream does not end in its final state")
    }
}

/** The context of a permutation's value [v]: its bit length, held to 7. */
private fun permutationContext(v: Int): Int = minOf(if (v == 0) 0 else floorLog2(v) + 1, 7)

internal const val JXL_PERMUTATION_CONTEXTS = 8

/**
 * Reads a permutation of [size] values whose first [skip] stay in place, with [reader], and
 * writes it to [order] when it is not null. It is stored as a Lehmer code.
 */
internal fun jxlReadPermutation(reader: JxlSymbolReader, skip: Int, size: Int, order: IntArray?) {
    val end = (reader.read(permutationContext(size)).toLong() and 0xFFFFFFFFL) + skip
    if (end > size) jxlFail("permutation of $end values in a space of $size")
    val lehmer = IntArray(size)
    var last = 0
    for (i in skip until end.toInt()) {
        val v = reader.read(permutationContext(last))
        if (v < 0 || v >= size - i) jxlFail("invalid Lehmer code")
        lehmer[i] = v
        last = v
    }
    if (order == null) return
    // A Fenwick tree of the values not yet used finds the lehmer[i]-th of them in log time.
    var padded = 1
    while (padded < size) padded = padded shl 1
    val tree = IntArray(padded + 1)
    for (i in 1..padded) {
        tree[i] += if (i <= size) 1 else 0
        val parent = i + (i and -i)
        if (parent <= padded) tree[parent] += tree[i]
    }
    for (i in 0 until size) {
        var rank = lehmer[i] + 1
        var next = 0
        var bit = padded
        while (bit != 0) {
            val candidate = next + bit
            bit = bit shr 1
            if (candidate <= padded && tree[candidate] < rank) {
                next = candidate
                rank -= tree[candidate]
            }
        }
        order[i] = next
        var at = next + 1
        while (at <= padded) {
            tree[at]--
            at += at and -at
        }
    }
}

/** Reads a permutation that has its own entropy code, as the table of contents of a frame does. */
internal fun jxlDecodePermutation(br: JxlBitReader, skip: Int, size: Int, order: IntArray?) {
    val code = JxlCode.read(br, JXL_PERMUTATION_CONTEXTS)
    val reader = JxlSymbolReader(code, br)
    jxlReadPermutation(reader, skip, size, order)
    reader.checkFinalState()
}
