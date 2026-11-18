package io.github.yuroyami.imagekodec.codec.jxl

/**
 * The integers of one entropy-coded stream, each with its context, in the order a decoder
 * reads them. A value is an unsigned integer below 2^31.
 */
internal class JxlTokens(capacity: Int = 1024) {
    var ctx = IntArray(maxOf(16, capacity))
        private set
    var value = IntArray(maxOf(16, capacity))
        private set
    var size = 0
        private set

    fun add(context: Int, v: Int) {
        if (size == ctx.size) {
            ctx = ctx.copyOf(size * 2)
            value = value.copyOf(size * 2)
        }
        ctx[size] = context
        value[size] = v
        size++
    }
}

/** How an integer splits into a token and raw bits (ISO/IEC 18181-1, C.2.4 HybridUintConfig). */
internal class JxlHybridConfig(val split: Int, val msb: Int, val lsb: Int) {
    private val splitToken = 1 shl split

    fun token(v: Int): Int {
        if (v < splitToken) return v
        val n = floorLog2(v)
        val m = v - (1 shl n)
        return splitToken +
            ((n - split) shl (msb + lsb)) +
            ((m ushr (n - msb)) shl lsb) +
            (m and ((1 shl lsb) - 1))
    }

    /** The number of raw bits after the token of [v]. */
    fun extraBitCount(v: Int): Int = if (v < splitToken) 0 else floorLog2(v) - msb - lsb

    /** The raw bits after the token of [v]. */
    fun extraBits(v: Int): Int {
        if (v < splitToken) return 0
        val n = extraBitCount(v)
        return (v ushr lsb) and ((1 shl n) - 1)
    }

    companion object {
        val DEFAULT = JxlHybridConfig(4, 2, 0)
    }
}

/**
 * A finished entropy code: which cluster each context uses, how each cluster splits its
 * integers, and the ANS distribution of each cluster. [header] holds its bits as
 * 18181-1 C.2.1 lays them out; [write] codes a stream with it.
 */
internal class JxlEntropyCode internal constructor(
    val numContexts: Int,
    val contextMap: IntArray,
    val configs: Array<JxlHybridConfig>,
    /** The ANS frequency of each token, summing to 4096, for each cluster. */
    private val freqs: Array<IntArray>,
    val header: JxlBitWriter,
) {
    private val numClusters = configs.size

    // For each cluster: the first slot of each token, and the ANS state remainder of each slot.
    private val cumul = Array(numClusters) { c -> IntArray(freqs[c].size + 1).also { s -> for (t in freqs[c].indices) s[t + 1] = s[t] + freqs[c][t] } }
    private val slots = Array(numClusters) { IntArray(ANS_TAB) }

    init {
        // The decoder's own reading of the header gives the alias table, so the two cannot disagree.
        val bytes = header.toByteArray()
        val code = JxlCode.read(JxlBitReader(bytes, 0, bytes.size), numContexts)
        val logEntry = ANS_LOG_TAB - code.logAlphaSize
        val entryMask = (1 shl logEntry) - 1
        for (c in 0 until numClusters) {
            if (code.onlySymbol(c) >= 0) {
                for (r in 0 until ANS_TAB) slots[c][r] = r
                continue
            }
            for (res in 0 until ANS_TAB) {
                val i = (c shl code.logAlphaSize) + (res ushr logEntry)
                val pos = res and entryMask
                val symbol: Int
                val offset: Int
                if (pos >= code.aliasCutoff[i]) {
                    symbol = code.aliasRight[i]
                    offset = code.aliasOffsets1[i] + pos
                } else {
                    symbol = res ushr logEntry
                    offset = pos
                }
                slots[c][cumul[c][symbol] + offset] = res
            }
        }
    }

    /** Writes [header] to [bw]. */
    fun writeHeader(bw: JxlBitWriter) = bw.appendBits(header)

    /** Writes [tokens] as one ANS stream: the final state first, then the words and raw bits in the decoder's order. */
    fun write(bw: JxlBitWriter, tokens: JxlTokens) {
        val n = tokens.size
        val words = IntArray(n)
        val emitted = BooleanArray(n)
        var state = ANS_SIGNATURE.toLong() shl 16
        for (i in n - 1 downTo 0) {
            val c = contextMap[tokens.ctx[i]]
            val t = configs[c].token(tokens.value[i])
            val f = freqs[c][t].toLong()
            check(f > 0) { "JPEG XL: token $t of cluster $c has no probability" }
            if (state >= f shl 20) {
                words[i] = (state and 0xFFFF).toInt()
                emitted[i] = true
                state = state ushr 16
            }
            state = ((state / f) shl ANS_LOG_TAB) + slots[c][cumul[c][t] + (state % f).toInt()]
        }
        bw.write(32, state)
        for (i in 0 until n) {
            if (emitted[i]) bw.write(16, words[i])
            val cfg = configs[contextMap[tokens.ctx[i]]]
            val v = tokens.value[i]
            val nb = cfg.extraBitCount(v)
            if (nb > 0) bw.write(nb, cfg.extraBits(v))
        }
    }

    /** The bits [write] would give [tokens], without the 32 of the state, to a fraction of a bit. */
    fun estimateBits(tokens: JxlTokens): Double {
        var bits = 0.0
        for (i in 0 until tokens.size) {
            val c = contextMap[tokens.ctx[i]]
            val v = tokens.value[i]
            val cfg = configs[c]
            bits += ANS_LOG_TAB - JxlEntropyEncoder.log2Int(freqs[c][cfg.token(v)]) + cfg.extraBitCount(v)
        }
        return bits
    }

    internal companion object {
        const val ANS_LOG_TAB = 12
        const val ANS_TAB = 1 shl ANS_LOG_TAB
        const val ANS_SIGNATURE = 0x13
    }
}

/**
 * Builds the entropy code of a set of streams that share it: clusters the contexts' histograms,
 * picks a hybrid integer configuration for each cluster and normalizes its ANS distribution.
 * The design is this file's own: a greedy clustering by estimated bits, then refinement.
 */
internal object JxlEntropyEncoder {

    private const val MAX_ALPHABET = 256

    /** How many value and configuration pairs the choice of configurations looks at. */
    private const val CONFIG_BUDGET = 1L shl 24

    /** The hybrid configurations an effort tries for each cluster. */
    private fun candidates(effort: Int): Array<JxlHybridConfig> = when {
        effort <= 1 -> arrayOf(JxlHybridConfig.DEFAULT)
        effort == 2 -> arrayOf(
            JxlHybridConfig(4, 2, 0), JxlHybridConfig(4, 1, 0), JxlHybridConfig(0, 0, 0), JxlHybridConfig(2, 1, 0),
            JxlHybridConfig(5, 2, 0), JxlHybridConfig(4, 1, 1),
        )
        else -> buildList {
            for (split in 0..7) for (msb in 0..minOf(split, 3)) for (lsb in 0..minOf(split - msb, 2)) add(JxlHybridConfig(split, msb, lsb))
        }.toTypedArray()
    }

    // x * log2(x) for small integers, computed once.
    private const val TABLE = 1 shl 14
    private val xLogX = DoubleArray(TABLE) { if (it == 0) 0.0 else it * JxlEncMath.log2(it.toDouble()) }
    private val log2Table = DoubleArray(4097) { if (it == 0) 0.0 else JxlEncMath.log2(it.toDouble()) }

    /** log2 of an ANS frequency, 1 to 4096. */
    fun log2Int(f: Int): Double = log2Table[f]

    private fun nLogN(x: Long): Double = if (x < TABLE) xLogX[x.toInt()] else x * JxlEncMath.log2(x.toDouble())

    /** The bits of a histogram's symbols under an ideal code: N log N less the sum of n log n. */
    private fun entropyBits(h: LongArray, total: Long): Double {
        var s = nLogN(total)
        for (c in h) if (c != 0L) s -= nLogN(c)
        return s
    }

    /** A rough cost of a histogram's description, for deciding whether a cluster pays for itself. */
    private fun headerEstimate(h: LongArray): Double {
        var nonzero = 0
        for (c in h) if (c != 0L) nonzero++
        return 12.0 + nonzero * 6.0
    }

    /**
     * The code for [streams], whose contexts number [numContexts]. [maxClusters] bounds the
     * number of distributions; a smaller bound saves header bits on small streams.
     */
    fun build(streams: List<JxlTokens>, numContexts: Int, effort: Int, maxClusters: Int = 64): JxlEntropyCode {
        val cfg0 = JxlHybridConfig.DEFAULT
        // Histograms of the default tokens, for clustering.
        val hist = Array(numContexts) { LongArray(128) }
        val totals = LongArray(numContexts)
        for (s in streams) {
            for (i in 0 until s.size) {
                val c = s.ctx[i]
                val t = cfg0.token(s.value[i])
                hist[c][t]++
                totals[c]++
            }
        }
        val map = cluster(hist, totals, minOf(maxClusters, MAX_ALPHABET))
        val numClusters = (map.maxOrNull() ?: 0) + 1

        // The configuration of each cluster, from the values themselves.
        val cands = candidates(effort)
        val configs = Array(numClusters) { cfg0 }
        if (cands.size > 1) {
            val chist = Array(numClusters) { Array(cands.size) { LongArray(MAX_ALPHABET + 1) } }
            val extra = Array(numClusters) { LongArray(cands.size) }
            // A sample of the values decides, so a large image does not try every configuration on every value.
            var all = 0L
            for (s in streams) all += s.size
            val stride = maxOf(1L, all * cands.size / CONFIG_BUDGET).toInt()
            for (s in streams) {
                for (i in 0 until s.size step stride) {
                    val c = map[s.ctx[i]]
                    val v = s.value[i]
                    for (k in cands.indices) {
                        val t = cands[k].token(v)
                        chist[c][k][minOf(t, MAX_ALPHABET)]++
                        extra[c][k] += cands[k].extraBitCount(v).toLong()
                    }
                }
            }
            // Tokens grow with the value, so the largest value of a cluster bounds its alphabet.
            val maxValue = IntArray(numClusters)
            for (s in streams) for (i in 0 until s.size) {
                val c = map[s.ctx[i]]
                if (s.value[i] > maxValue[c]) maxValue[c] = s.value[i]
            }
            for (c in 0 until numClusters) {
                var best = Double.MAX_VALUE
                for (k in cands.indices) {
                    val h = chist[c][k]
                    if (h[MAX_ALPHABET] != 0L || cands[k].token(maxValue[c]) >= MAX_ALPHABET) continue
                    var total = 0L
                    for (x in h) total += x
                    val cost = entropyBits(h, total) + extra[c][k] + headerEstimate(h)
                    if (cost < best) {
                        best = cost
                        configs[c] = cands[k]
                    }
                }
            }
        }

        // The final histograms of tokens.
        val counts = Array(numClusters) { IntArray(MAX_ALPHABET) }
        var maxToken = 0
        for (s in streams) {
            for (i in 0 until s.size) {
                val c = map[s.ctx[i]]
                val t = configs[c].token(s.value[i])
                require(t < MAX_ALPHABET) { "JPEG XL: token $t past the alphabet" }
                counts[c][t]++
                if (t > maxToken) maxToken = t
            }
        }
        var logAlpha = 5
        while ((1 shl logAlpha) <= maxToken) logAlpha++
        val header = JxlBitWriter()
        header.bool(false) // no LZ77
        if (numContexts > 1) writeContextMap(header, map, effort)
        header.bool(false) // ANS, not prefix codes
        header.write(2, logAlpha - 5)
        for (c in 0 until numClusters) {
            val cfg = configs[c]
            header.write(ceilLog2(logAlpha + 1), cfg.split)
            if (cfg.split != logAlpha) {
                header.write(ceilLog2(cfg.split + 1), cfg.msb)
                header.write(ceilLog2(cfg.split - cfg.msb + 1), cfg.lsb)
            }
        }
        val freqs = Array(numClusters) { c -> writeHistogram(header, counts[c]) }
        return JxlEntropyCode(numContexts, map, configs, freqs, header)
    }

    /**
     * Groups the contexts into clusters: each context, the busiest first, joins the cluster it
     * costs the fewest bits in, or starts its own when that is cheaper; then each context moves
     * to its best cluster once more. Empty contexts join cluster 0.
     */
    private fun cluster(hist: Array<LongArray>, totals: LongArray, maxClusters: Int): IntArray {
        val n = hist.size
        val map = IntArray(n)
        val order = (0 until n).filter { totals[it] > 0 }.sortedByDescending { totals[it] }
        if (order.isEmpty()) return map
        val alphabet = hist[0].size
        val ch = ArrayList<LongArray>()
        val ct = ArrayList<Long>()
        val cost = ArrayList<Double>()
        val merged = LongArray(alphabet)

        fun mergedCost(k: Int, h: LongArray, total: Long): Double {
            val a = ch[k]
            for (i in 0 until alphabet) merged[i] = a[i] + h[i]
            return entropyBits(merged, ct[k] + total) + headerEstimate(merged)
        }

        for (ctx in order) {
            val h = hist[ctx]
            val own = entropyBits(h, totals[ctx]) + headerEstimate(h)
            var best = -1
            var bestDelta = if (ch.size < maxClusters) own else Double.MAX_VALUE
            for (k in ch.indices) {
                val delta = mergedCost(k, h, totals[ctx]) - cost[k]
                if (delta < bestDelta) {
                    bestDelta = delta
                    best = k
                }
            }
            if (best < 0) {
                ch.add(h.copyOf())
                ct.add(totals[ctx])
                cost.add(own)
                map[ctx] = ch.size - 1
            } else {
                val a = ch[best]
                for (i in 0 until alphabet) a[i] += h[i]
                ct[best] = ct[best] + totals[ctx]
                cost[best] = cost[best] + bestDelta
                map[ctx] = best
            }
        }
        // One refinement: every context moves to the cluster whose distribution codes it in the fewest bits.
        if (ch.size > 1) {
            val bitsOf = Array(ch.size) { k ->
                val a = ch[k]
                val total = ct[k].toDouble()
                DoubleArray(alphabet) { i -> if (a[i] == 0L) 30.0 else JxlEncMath.log2(total / a[i]) }
            }
            for (ctx in order) {
                val h = hist[ctx]
                var best = map[ctx]
                var bestBits = Double.MAX_VALUE
                for (k in ch.indices) {
                    var b = 0.0
                    val t = bitsOf[k]
                    for (i in 0 until alphabet) if (h[i] != 0L) b += h[i] * t[i]
                    if (b < bestBits) {
                        bestBits = b
                        best = k
                    }
                }
                map[ctx] = best
            }
        }
        // Number the clusters in order of first use, which the context map needs: none skipped.
        val renumber = IntArray(ch.size) { -1 }
        var next = 0
        for (ctx in 0 until n) {
            if (totals[ctx] == 0L) continue
            val k = map[ctx]
            if (renumber[k] < 0) renumber[k] = next++
            map[ctx] = renumber[k]
        }
        for (ctx in 0 until n) if (totals[ctx] == 0L) map[ctx] = 0
        return map
    }

    /** Writes a context map (18181-1 C.2.2): plainly when that is short, else entropy coded, with move-to-front when it helps. */
    private fun writeContextMap(bw: JxlBitWriter, map: IntArray, effort: Int) {
        val clusters = map.max() + 1
        val plainBits = if (clusters == 1) 0 else ceilLog2(clusters)
        val candidates = ArrayList<JxlBitWriter>()
        if (plainBits <= 3) {
            val w = JxlBitWriter()
            w.bool(true)
            w.write(2, plainBits)
            if (plainBits != 0) for (v in map) w.write(plainBits, v)
            candidates.add(w)
        }
        for (mtf in listOf(false, true)) {
            val values = if (mtf) moveToFront(map) else map
            val tokens = JxlTokens(values.size)
            for (v in values) tokens.add(0, v)
            val code = build(listOf(tokens), 1, minOf(effort, 2), 1)
            val w = JxlBitWriter()
            w.bool(false)
            w.bool(mtf)
            code.writeHeader(w)
            code.write(w, tokens)
            candidates.add(w)
        }
        bw.appendBits(candidates.minByOrNull { it.bitCount }!!)
    }

    private fun moveToFront(v: IntArray): IntArray {
        val mtf = IntArray(256) { it }
        return IntArray(v.size) { i ->
            val value = v[i]
            var index = 0
            while (mtf[index] != value) index++
            if (index != 0) {
                mtf.copyInto(mtf, 1, 0, index)
                mtf[0] = value
            }
            index
        }
    }

    private fun writeVarLenUint8(bw: JxlBitWriter, v: Int) {
        if (v == 0) {
            bw.bool(false)
            return
        }
        bw.bool(true)
        val n = floorLog2(v)
        bw.write(3, n)
        if (n > 0) bw.write(n, v - (1 shl n))
    }

    /**
     * Normalizes [counts] to a distribution that sums to 4096, writes it (18181-1 C.2.5) in the
     * cheapest form this finds, and returns the frequencies written.
     */
    fun writeHistogram(bw: JxlBitWriter, counts: IntArray): IntArray {
        var total = 0L
        var used = 0
        var last = -1
        for (i in counts.indices) {
            if (counts[i] != 0) {
                total += counts[i]
                used++
                last = i
            }
        }
        if (used <= 1) {
            // One symbol, or none: the whole range goes to it.
            val s = maxOf(last, 0)
            bw.bool(true)
            bw.bool(false)
            writeVarLenUint8(bw, s)
            return IntArray(s + 1).also { it[s] = ANS_TAB }
        }
        val exact = normalize(counts, total, last + 1)
        if (used == 2) {
            val w = JxlBitWriter()
            var s0 = -1
            var s1 = -1
            for (i in 0..last) if (exact[i] != 0) if (s0 < 0) s0 = i else s1 = i
            w.bool(true)
            w.bool(true)
            writeVarLenUint8(w, s0)
            writeVarLenUint8(w, s1)
            w.write(12, exact[s0])
            bw.appendBits(w)
            return exact
        }
        var best: JxlBitWriter? = null
        var bestFreq = exact
        var bestCost = Double.MAX_VALUE
        for (shift in 13 downTo 0) {
            val freq = roundToShift(exact, shift) ?: continue
            val w = JxlBitWriter()
            writeGeneral(w, freq, shift)
            var dataBits = 0.0
            for (i in 0..last) if (counts[i] != 0) dataBits += counts[i] * (ANS_LOG_TAB - log2Table[freq[i]])
            val cost = w.bitCount + dataBits
            if (cost < bestCost) {
                bestCost = cost
                best = w
                bestFreq = freq
            }
        }
        bw.appendBits(best!!)
        return bestFreq
    }

    private const val ANS_LOG_TAB = 12
    private const val ANS_TAB = 1 shl ANS_LOG_TAB

    /** Counts scaled to sum to 4096, every used symbol at least 1; the largest one takes what rounding leaves. */
    private fun normalize(counts: IntArray, total: Long, size: Int): IntArray {
        val out = IntArray(size)
        var sum = 0
        var maxAt = 0
        for (i in 0 until size) {
            if (counts[i] == 0) continue
            out[i] = maxOf(1, ((counts[i].toLong() * ANS_TAB * 2 + total) / (2 * total)).toInt())
            sum += out[i]
            if (counts[i] > counts[maxAt]) maxAt = i
        }
        out[maxAt] += ANS_TAB - sum
        // Many rare symbols rounded up to 1 can leave the largest one too small: take from the next largest.
        while (out[maxAt] < 1) {
            var k = -1
            for (i in 0 until size) if (i != maxAt && out[i] > 1 && (k < 0 || out[i] > out[k])) k = i
            out[k]--
            out[maxAt]++
        }
        return out
    }

    /** The bit length code (1 + floor log2) of a frequency, 0 for none. */
    private fun logCode(f: Int): Int = if (f == 0) 0 else floorLog2(f) + 1

    private fun precision(code: Int, shift: Int): Int =
        minOf(code - 1, shift - ((ANS_LOG_TAB - (code - 1)) shr 1)).coerceAtLeast(0)

    /**
     * [exact] with each frequency but the implied one rounded to what [shift] can store, or null
     * when the implied one would not be the first of the longest bit length.
     */
    private fun roundToShift(exact: IntArray, shift: Int): IntArray? {
        // The implied frequency is the first of the longest bit length.
        var omit = -1
        var omitCode = 0
        for (i in exact.indices) {
            val c = logCode(exact[i])
            if (c > omitCode) {
                omitCode = c
                omit = i
            }
        }
        val out = exact.copyOf()
        var sum = 0
        for (i in out.indices) {
            if (i == omit || out[i] == 0) continue
            val code = logCode(out[i])
            if (code > 1) {
                val drop = code - 1 - precision(code, shift)
                if (drop > 0) {
                    val step = 1 shl drop
                    var r = ((out[i] + step / 2) / step) * step
                    val lo = 1 shl (code - 1)
                    val hi = (1 shl code) - 1
                    if (r > hi) r -= step
                    if (r < lo) r = lo
                    out[i] = r
                }
            }
            sum += out[i]
        }
        val rest = ANS_TAB - sum
        if (rest < 1) return null
        val restCode = logCode(rest)
        for (i in out.indices) {
            if (i == omit) continue
            val c = logCode(out[i])
            if (c > restCode || (i < omit && c == restCode)) return null
        }
        out[omit] = rest
        return out
    }

    private val logCountCode: IntArray by lazy {
        // Code bits and length of each bit length value, read off the decoder's table.
        val codes = IntArray(14) { -1 }
        for (p in 0 until 128) {
            val len = LOG_COUNT_CODE[p * 2].toInt()
            val value = LOG_COUNT_CODE[p * 2 + 1].toInt()
            if (codes[value] < 0) codes[value] = (len shl 8) or (p and ((1 shl len) - 1))
        }
        codes
    }

    private fun writeGeneral(w: JxlBitWriter, freq: IntArray, shift: Int) {
        w.bool(false)
        w.bool(false)
        // shift + 1 as a unary bit length, at most 3, and the bits below its top bit.
        val v = shift + 1
        val log = floorLog2(v)
        for (i in 0 until log) w.bool(true)
        if (log < 3) w.bool(false)
        if (log > 0) w.write(log, v - (1 shl log))
        val length = maxOf(3, freq.size)
        writeVarLenUint8(w, length - 3)
        var omit = -1
        var omitCode = 0
        val codes = IntArray(length) { if (it < freq.size) logCode(freq[it]) else 0 }
        for (i in 0 until length) {
            if (codes[i] > omitCode) {
                omitCode = codes[i]
                omit = i
            }
            val c = logCountCode[codes[i]]
            w.write(c ushr 8, c and 0xFF)
        }
        for (i in 0 until length) {
            val code = codes[i]
            if (i == omit || code <= 1) continue
            val p = precision(code, shift)
            val f = freq[i]
            w.write(p, (f - (1 shl (code - 1))) ushr (code - 1 - p))
        }
    }
}
