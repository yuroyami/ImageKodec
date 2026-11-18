package io.github.yuroyami.imagekodec.codec.jxl

/** A transform listed in a Modular stream's header (18181-1 H.6): a colour transform or a palette. */
internal class JxlEncTransform(val id: Int, val beginC: Int, val rctType: Int = 0, val numC: Int = 0, val nbColors: Int = 0) {
    fun write(bw: JxlBitWriter) {
        bw.u32(id, fv(0), fv(1), fv(2), fv(3))
        bw.u32(beginC, fb(3), fo(6, 8), fo(10, 72), fo(13, 1096))
        if (id == RCT) bw.u32(rctType, fv(6), fb(2), fo(4, 2), fo(6, 10))
        if (id == PALETTE) {
            bw.u32(numC, fv(1), fv(3), fv(4), fo(13, 1))
            bw.u32(nbColors, fo(8, 0), fo(10, 256), fo(12, 1280), fo(16, 5376))
            bw.u32(0, fv(0), fo(8, 1), fo(10, 257), fo(16, 1281))
            bw.write(4, 0)
        }
    }

    companion object {
        const val RCT = 0
        const val PALETTE = 1
    }
}

/**
 * One Modular stream to code: its number in the frame, which a tree may test, the channels it
 * holds after its transforms, and the transforms its header lists. [channels] may hold channels
 * the stream does not code, which give their sizes to the transforms; [codedEnd] is where the
 * coded ones stop.
 */
internal class JxlModStream(
    val streamId: Int,
    val channels: List<JxlChannel>,
    val transforms: List<JxlEncTransform> = emptyList(),
    val codedEnd: Int = channels.size,
)

/**
 * A meta-adaptive tree in the encoder's form. A node with [property] -1 is a leaf with a
 * predictor; an inner node sends a sample whose property is above [splitval] to [left].
 */
internal class JxlEncTree(
    val property: IntArray,
    val splitval: IntArray,
    val left: IntArray,
    val right: IntArray,
    val predictor: IntArray,
    val size: Int,
) {
    /** The context of each leaf: its place among the leaves in the order the tree is written. */
    val leafCtx = IntArray(size)
    val leaves: Int
    private val bfs = IntArray(size)

    init {
        var head = 0
        var tail = 0
        bfs[tail++] = 0
        var leafCount = 0
        while (head < tail) {
            val n = bfs[head++]
            if (property[n] < 0) {
                leafCtx[n] = leafCount++
            } else {
                bfs[tail++] = left[n]
                bfs[tail++] = right[n]
            }
        }
        leaves = leafCount
    }

    /** The largest property any inner node tests, or -1. */
    val maxProperty: Int = (0 until size).maxOfOrNull { property[it] } ?: -1

    /** Writes the tree and its own code (18181-1 H.4.2): every node in breadth-first order. */
    fun write(bw: JxlBitWriter, effort: Int) {
        val tokens = JxlTokens(size * 5)
        for (n in bfs) {
            if (property[n] < 0) {
                tokens.add(1, 0)
                tokens.add(2, predictor[n])
                tokens.add(3, 0)
                tokens.add(4, 0)
                tokens.add(5, 0)
            } else {
                tokens.add(1, property[n] + 1)
                tokens.add(0, packSigned(splitval[n]))
            }
        }
        val code = JxlEntropyEncoder.build(listOf(tokens), 6, minOf(effort, 2), 8)
        code.writeHeader(bw)
        code.write(bw, tokens)
    }

    companion object {
        fun single(predictor: Int) = JxlEncTree(intArrayOf(-1), IntArray(1), IntArray(1), IntArray(1), intArrayOf(predictor), 1)
    }
}

/**
 * The Modular encoder: it learns a tree from the samples, then turns each sample into the
 * difference from its leaf's prediction, with the leaf as context. Properties and predictors
 * are those of 18181-1 H.4 and H.5, computed as [JxlModular] reads them.
 */
internal object JxlModularEncoder {

    private const val NUM_PREDICTORS = 14
    private const val NONREF = 16
    private const val WP_PROP = 15
    private const val ALPHABET = 128

    /** What an effort spends on a tree. */
    class Settings(
        val predictors: IntArray,
        val properties: IntArray,
        val refChannels: Int,
        val maxSamples: Int,
        val bins: Int,
        val maxLeaves: Int,
        val splitPenalty: Double,
    )

    fun settings(effort: Int): Settings = when {
        effort <= 1 -> Settings(intArrayOf(5), intArrayOf(0, 1, 4, 5, 9, 10, 11, 12, 13, 14), 0, 1 shl 16, 16, 32, 48.0)
        effort == 2 -> Settings(
            intArrayOf(5, 6, 1, 2, 4, 0), (0 until 16).toList().toIntArray(), 1, 1 shl 18, 48, 256, 40.0,
        )
        else -> Settings(
            IntArray(NUM_PREDICTORS) { it }, (0 until 16).toList().toIntArray() + IntArray(8) { 16 + it }, 2, 1 shl 19, 96, 1024, 36.0,
        )
    }

    /** The channels of [stream] that are coded, with their indices. */
    private fun coded(stream: JxlModStream): List<Int> =
        (0 until stream.codedEnd).filter { stream.channels[it].w > 0 && stream.channels[it].h > 0 }

    /**
     * Walks channel [chan] of [stream] in raster order and gives [visit] each sample's
     * properties and the predictions of every predictor. [props] must hold [numProps] values.
     */
    private inline fun scan(
        stream: JxlModStream,
        chan: Int,
        numProps: Int,
        useWp: Boolean,
        props: IntArray,
        preds: LongArray,
        visit: (x: Int, y: Int, at: Int, v: Int) -> Unit,
    ) {
        val ch = stream.channels[chan]
        val w = ch.w
        val h = ch.h
        val data = ch.data
        val wanted = maxOf(0, (numProps - NONREF + 3) / 4)
        val refs = ArrayList<IntArray>()
        var j = chan - 1
        while (j >= 0 && refs.size < wanted) {
            val o = stream.channels[j]
            if (o.w == w && o.h == h && o.hshift == ch.hshift && o.vshift == ch.vshift) refs.add(o.data)
            j--
        }
        val wp = if (useWp) JxlWpState(JxlWpHeader(), w) else null
        props.fill(0)
        props[0] = chan
        props[1] = stream.streamId
        for (y in 0 until h) {
            val row = y * w
            props[2] = y
            props[9] = 0
            for (x in 0 until w) {
                val at = row + x
                val left = if (x > 0) data[at - 1] else if (y > 0) data[at - w] else 0
                val top = if (y > 0) data[at - w] else left
                val topleft = if (x > 0 && y > 0) data[at - w - 1] else left
                val topright = if (x + 1 < w && y > 0) data[at - w + 1] else top
                val leftleft = if (x > 1) data[at - 2] else left
                val toptop = if (y > 1) data[at - 2 * w] else top
                val toprightright = if (x + 2 < w && y > 0) data[at - w + 2] else topright
                props[3] = x
                props[4] = if (top > 0) top else -top
                props[5] = if (left > 0) left else -left
                props[6] = top
                props[7] = left
                props[8] = left - props[9]
                props[9] = left + top - topleft
                props[10] = left - topleft
                props[11] = topleft - top
                props[12] = top - topright
                props[13] = top - toptop
                props[14] = left - leftleft
                var wpPred = 0L
                if (wp != null) {
                    wpPred = wp.predict(x, y, top, left, topright, topleft, toptop)
                    props[WP_PROP] = wp.property
                }
                var o = NONREF
                for (r in refs) {
                    if (o >= numProps) break
                    val v = r[at]
                    val vleft = if (x > 0) r[at - 1] else 0
                    val vtop = if (y > 0) r[at - w] else vleft
                    val vtopleft = if (x > 0 && y > 0) r[at - w - 1] else vleft
                    val d = v - jxlClampedGradient(vleft, vtop, vtopleft)
                    props[o] = if (v < 0) -v else v
                    props[o + 1] = v
                    props[o + 2] = if (d < 0) -d else d
                    props[o + 3] = d
                    o += 4
                }
                val l = left.toLong()
                val t = top.toLong()
                val tl = topleft.toLong()
                val tr = topright.toLong()
                preds[0] = 0L
                preds[1] = l
                preds[2] = t
                preds[3] = (l + t) / 2
                val p = l + t - tl
                preds[4] = if (kotlin.math.abs(p - l) < kotlin.math.abs(p - t)) l else t
                preds[5] = jxlClampedGradient(left, top, topleft).toLong()
                preds[6] = wpPred
                preds[7] = tr
                preds[8] = tl
                preds[9] = leftleft.toLong()
                preds[10] = (l + tl) / 2
                preds[11] = (tl + t) / 2
                preds[12] = (t + tr) / 2
                preds[13] = (6L * t - 2L * toptop + 7L * l + leftleft + toprightright + 3L * tr + 8) / 16
                val v = data[at]
                visit(x, y, at, v)
                wp?.update(v, x, y)
            }
        }
    }

    /** How many properties a tree with [maxProp] as its largest property needs computed. */
    private fun propsFor(maxProp: Int): Int =
        if (maxProp >= NONREF) NONREF + ((maxProp - NONREF) / 4 + 1) * 4 else NONREF

    /**
     * Learns a tree from [streams] (18181-1 leaves the method to the encoder). Samples come from
     * every [step]-th row; each property is cut into quantiles; a node splits where the two
     * halves, each with its best predictor, cost the fewest estimated bits, while that saves
     * more than the cost of a split.
     */
    fun learnTree(streams: List<JxlModStream>, effort: Int): JxlEncTree {
        val s = settings(effort)
        var total = 0L
        for (st in streams) for (c in coded(st)) total += st.channels[c].w.toLong() * st.channels[c].h
        if (total == 0L) return JxlEncTree.single(5)
        val step = maxOf(1L, (total + s.maxSamples - 1) / s.maxSamples).toInt()
        val numProps = propsFor(s.properties.max())
        val useWp = s.predictors.contains(PRED_WEIGHTED) || s.properties.contains(WP_PROP)
        val np = s.properties.size
        val npred = s.predictors.size

        // Collect the samples: their properties, and each predictor's token and raw bit count.
        val cap = (total / step + 1024).toInt()
        var n = 0
        val pv = Array(np) { IntArray(cap) }
        val tok = Array(npred) { ByteArray(cap) }
        val extra = Array(npred) { ByteArray(cap) }
        val props = IntArray(numProps)
        val preds = LongArray(NUM_PREDICTORS)
        val cfg = JxlHybridConfig.DEFAULT
        var counter = 0L
        for (st in streams) {
            for (c in coded(st)) {
                scan(st, c, numProps, useWp, props, preds) { _, _, _, v ->
                    if (counter++ % step == 0L && n < cap) {
                        for (k in 0 until np) pv[k][n] = props[s.properties[k]]
                        for (k in 0 until npred) {
                            val r = packSigned((v - preds[s.predictors[k]]).toInt())
                            tok[k][n] = cfg.token(r).toByte()
                            extra[k][n] = cfg.extraBitCount(r).toByte()
                        }
                        n++
                    }
                }
            }
        }
        val scale = step.toDouble()
        val penalty = s.splitPenalty / scale

        // Quantile thresholds of each property, and each sample's bin.
        val thresholds = Array(np) { k -> quantiles(pv[k], n, s.bins) }
        val bin = Array(np) { k ->
            val t = thresholds[k]
            ByteArray(n) { i -> binOf(t, pv[k][i]).toByte() }
        }

        val prop = ArrayList<Int>()
        val split = ArrayList<Int>()
        val left = ArrayList<Int>()
        val right = ArrayList<Int>()
        val pred = ArrayList<Int>()
        fun newNode(): Int {
            prop.add(-1)
            split.add(0)
            left.add(0)
            right.add(0)
            pred.add(5)
            return prop.size - 1
        }

        val all = IntArray(n) { it }
        val hist = LongArray(ALPHABET)
        // The best predictor of samples [from, to) of idx, and the bits its tokens alone take.
        fun bestPredictor(idx: IntArray, from: Int, to: Int): Pair<Int, Double> {
            var best = 0
            var bestCost = Double.MAX_VALUE
            var bestTokens = 0.0
            for (k in 0 until npred) {
                hist.fill(0)
                var ex = 0L
                val tk = tok[k]
                val eb = extra[k]
                for (i in from until to) {
                    val sIdx = idx[i]
                    hist[tk[sIdx].toInt()]++
                    ex += eb[sIdx]
                }
                val tokenBits = entropy(hist, (to - from).toLong())
                val cost = tokenBits + ex
                if (cost < bestCost) {
                    bestCost = cost
                    bestTokens = tokenBits
                    best = k
                }
            }
            return best to bestTokens
        }

        class Work(val node: Int, val from: Int, val to: Int, val predIdx: Int, val tokenBits: Double, val depth: Int)
        val queue = ArrayDeque<Work>()
        val root = newNode()
        val (p0, c0) = bestPredictor(all, 0, n)
        pred[root] = s.predictors[p0]
        queue.addLast(Work(root, 0, n, p0, c0, 0))
        var leaves = 1
        val counts = LongArray(s.bins * ALPHABET)
        val numByBin = LongArray(s.bins)
        val leftH = LongArray(ALPHABET)
        val rightH = LongArray(ALPHABET)
        while (queue.isNotEmpty() && leaves < s.maxLeaves) {
            val wk = queue.removeFirst()
            val count = wk.to - wk.from
            if (count < 16 || wk.depth >= 40) continue
            val tk = tok[wk.predIdx]
            var bestGain = penalty
            var bestProp = -1
            var bestBin = -1
            for (k in 0 until np) {
                val bins = thresholds[k].size + 1
                if (bins < 2) continue
                counts.fill(0, 0, bins * ALPHABET)
                numByBin.fill(0, 0, bins)
                val bk = bin[k]
                for (i in wk.from until wk.to) {
                    val sIdx = all[i]
                    val b = bk[sIdx].toInt()
                    counts[b * ALPHABET + tk[sIdx]]++
                    numByBin[b]++
                }
                // Left holds the bins above the cut, right the bins at or below it.
                rightH.fill(0)
                leftH.fill(0)
                var leftN = 0L
                for (b in 0 until bins) {
                    for (t in 0 until ALPHABET) leftH[t] += counts[b * ALPHABET + t]
                    leftN += numByBin[b]
                }
                var rightN = 0L
                for (b in 0 until bins - 1) {
                    if (numByBin[b] == 0L) continue
                    for (t in 0 until ALPHABET) {
                        val v = counts[b * ALPHABET + t]
                        rightH[t] += v
                        leftH[t] -= v
                    }
                    rightN += numByBin[b]
                    leftN -= numByBin[b]
                    if (leftN == 0L) break
                    // The raw bits do not change with the cut, so only the tokens' bits count.
                    val gain = wk.tokenBits - entropy(leftH, leftN) - entropy(rightH, rightN)
                    if (gain > bestGain) {
                        bestGain = gain
                        bestProp = k
                        bestBin = b
                    }
                }
            }
            if (bestProp < 0) continue
            // Partition the samples: those above the cut first.
            val bk = bin[bestProp]
            var lo = wk.from
            var hi = wk.to - 1
            while (lo <= hi) {
                if (bk[all[lo]] > bestBin) {
                    lo++
                } else {
                    val t = all[lo]
                    all[lo] = all[hi]
                    all[hi] = t
                    hi--
                }
            }
            val l = newNode()
            val r = newNode()
            prop[wk.node] = s.properties[bestProp]
            split[wk.node] = thresholds[bestProp][bestBin]
            left[wk.node] = l
            right[wk.node] = r
            val (pl, cl) = bestPredictor(all, wk.from, lo)
            val (pr, cr) = bestPredictor(all, lo, wk.to)
            pred[l] = s.predictors[pl]
            pred[r] = s.predictors[pr]
            leaves++
            queue.addLast(Work(l, wk.from, lo, pl, cl, wk.depth + 1))
            queue.addLast(Work(r, lo, wk.to, pr, cr, wk.depth + 1))
        }
        val size = prop.size
        return JxlEncTree(prop.toIntArray(), split.toIntArray(), left.toIntArray(), right.toIntArray(), pred.toIntArray(), size)
    }

    private val xLogX = DoubleArray(1 shl 14) { if (it == 0) 0.0 else it * JxlEncMath.log2(it.toDouble()) }

    private fun nLogN(x: Long): Double = if (x < xLogX.size) xLogX[x.toInt()] else x * JxlEncMath.log2(x.toDouble())

    private fun entropy(h: LongArray, total: Long): Double {
        var s = nLogN(total)
        for (c in h) if (c != 0L) s -= nLogN(c)
        return s
    }

    /** Up to [bins] - 1 distinct cut values of the first [n] of [values], at even quantiles. */
    private fun quantiles(values: IntArray, n: Int, bins: Int): IntArray {
        if (n == 0) return IntArray(0)
        val sorted = values.copyOf(n)
        sorted.sort()
        val out = ArrayList<Int>()
        for (q in 1 until bins) {
            val v = sorted[((n.toLong() * q) / bins).toInt().coerceIn(0, n - 1)]
            // A cut at the largest value leaves nothing above it.
            if (v >= sorted[n - 1]) continue
            if (out.isEmpty() || v > out.last()) out.add(v)
        }
        return out.toIntArray()
    }

    /** The bin of [v]: how many cuts lie below it. */
    private fun binOf(t: IntArray, v: Int): Int {
        var lo = 0
        var hi = t.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (t[mid] < v) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** The tokens of [stream] under [tree]: each sample's difference from its leaf's prediction, with the leaf's context. */
    fun tokenize(stream: JxlModStream, tree: JxlEncTree, out: JxlTokens) {
        val numProps = propsFor(tree.maxProperty)
        var useWp = false
        for (i in 0 until tree.size) if (tree.property[i] == WP_PROP || (tree.property[i] < 0 && tree.predictor[i] == PRED_WEIGHTED)) useWp = true
        val props = IntArray(numProps)
        val preds = LongArray(NUM_PREDICTORS)
        val tp = tree.property
        val ts = tree.splitval
        val tl = tree.left
        val tr = tree.right
        for (c in coded(stream)) {
            scan(stream, c, numProps, useWp, props, preds) { _, _, _, v ->
                var node = 0
                while (tp[node] >= 0) node = if (props[tp[node]] > ts[node]) tl[node] else tr[node]
                out.add(tree.leafCtx[node], packSigned((v - preds[tree.predictor[node]]).toInt()))
            }
        }
    }

    /** Writes a stream's header (18181-1 H.2): whether it uses the frame's tree, the default predictor parameters and its transforms. */
    fun writeHeader(bw: JxlBitWriter, stream: JxlModStream, useGlobalTree: Boolean) {
        bw.bool(useGlobalTree)
        bw.bool(true)
        bw.u32(stream.transforms.size, fv(0), fv(1), fo(4, 2), fo(8, 18))
        for (t in stream.transforms) t.write(bw)
    }

    /** Whether [stream] codes any sample, which decides whether its tree, code and data follow its header. */
    fun hasSamples(stream: JxlModStream): Boolean = coded(stream).isNotEmpty()

    /**
     * Writes [stream] whole, with a tree and code of its own: what a stream that does not share
     * the frame's tree holds.
     */
    fun writeStandalone(bw: JxlBitWriter, stream: JxlModStream, effort: Int) {
        if (stream.channels.isEmpty()) return
        writeHeader(bw, stream, false)
        if (!hasSamples(stream)) return
        val tree = learnTree(listOf(stream), effort)
        val tokens = JxlTokens()
        tokenize(stream, tree, tokens)
        tree.write(bw, effort)
        val code = JxlEntropyEncoder.build(listOf(tokens), tree.leaves, effort)
        code.writeHeader(bw)
        code.write(bw, tokens)
    }

    /** Writes a stream that uses the frame's shared tree and [code]; [tokens] are its own. */
    fun writeShared(bw: JxlBitWriter, stream: JxlModStream, code: JxlEntropyCode, tokens: JxlTokens) {
        if (stream.channels.isEmpty()) return
        writeHeader(bw, stream, true)
        if (!hasSamples(stream)) return
        code.write(bw, tokens)
    }
}
