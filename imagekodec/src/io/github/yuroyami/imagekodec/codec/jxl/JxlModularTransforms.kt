package io.github.yuroyami.imagekodec.codec.jxl

/**
 * The forward Modular transforms of a lossless frame (18181-1 H.6): a reversible colour
 * transform (RCT) chosen for the image, and a palette for images of few colours. Each is the
 * inverse of what [JxlTransform] undoes.
 */
internal object JxlModularTransforms {

    /** Channels after the transforms, the transforms in the order the header lists them, and how many channels are meta channels. */
    class Result(val channels: List<JxlChannel>, val transforms: List<JxlEncTransform>, val metaChannels: Int)

    /** The most colours a palette may hold. */
    private const val MAX_PALETTE = 1024

    /**
     * The ways to code [planes]: with the best colour transform, and with a palette when the
     * image has few colours. Effort 1 picks one; above it, both are tried and the caller keeps the smaller.
     */
    fun candidates(planes: List<IntArray>, w: Int, h: Int, colorPlanes: Int, bits: Int, effort: Int): List<Result> {
        val out = ArrayList<Result>()
        // Effort 1 takes a palette for colour images only: a gray image of 256 levels gains little from one.
        val palette = if (effort <= 1 && planes.size < 3) null else palette(planes, w, h, if (effort <= 1) 256 else MAX_PALETTE)
        if (palette != null && effort <= 1) return listOf(palette)
        if (palette != null) out.add(palette)
        out.add(rct(planes, w, h, colorPlanes, bits, effort))
        return out
    }

    private fun channelsOf(planes: List<IntArray>, w: Int, h: Int): ArrayList<JxlChannel> {
        val list = ArrayList<JxlChannel>()
        for (p in planes) {
            val ch = JxlChannel(w, h)
            p.copyInto(ch.data)
            list.add(ch)
        }
        return list
    }

    /** All the planes as one palette of colours and one plane of indices, or null when there are more than [limit] colours. */
    private fun palette(planes: List<IntArray>, w: Int, h: Int, limit: Int): Result? {
        val n = w * h
        val nc = planes.size
        // A colour as one key: up to four samples of 16 bits. CMYK with alpha has five, which no key holds.
        if (nc > 4) return null
        val seen = HashMap<Long, Int>()
        val keys = LongArray(n)
        for (i in 0 until n) {
            var k = 0L
            for (c in 0 until nc) k = (k shl 16) or planes[c][i].toLong()
            keys[i] = k
            if (!seen.containsKey(k)) {
                if (seen.size >= limit) return null
                seen[k] = 0
            }
        }
        // Colours ordered by the sum of their samples, so that near indices are near colours.
        val colors = seen.keys.sortedWith(compareBy<Long>({ key -> (0 until nc).sumOf { c -> (key ushr (16 * (nc - 1 - c))) and 0xFFFF } }, { it }))
        for ((i, k) in colors.withIndex()) seen[k] = i
        val count = colors.size
        val pal = JxlChannel(count, nc, -1, -1)
        for ((i, k) in colors.withIndex()) {
            for (c in 0 until nc) pal.data[c * count + i] = ((k ushr (16 * (nc - 1 - c))) and 0xFFFF).toInt()
        }
        val index = JxlChannel(w, h)
        for (i in 0 until n) index.data[i] = seen[keys[i]]!!
        return Result(listOf(pal, index), listOf(JxlEncTransform(JxlEncTransform.PALETTE, 0, numC = nc, nbColors = count)), 1)
    }

    /** The colour transforms an effort tries, as rct_type values: permutation * 7 + kind. */
    private fun rctCandidates(effort: Int): IntArray = when {
        effort <= 1 -> intArrayOf(0, 6)
        effort == 2 -> intArrayOf(0, 1, 2, 3, 4, 5, 6, 13, 20, 27, 34, 41)
        else -> IntArray(42) { it }
    }

    private fun rct(planes: List<IntArray>, w: Int, h: Int, colorPlanes: Int, bits: Int, effort: Int): Result {
        val channels = channelsOf(planes, w, h)
        if (colorPlanes < 3) return Result(channels, emptyList(), 0)
        val types = rctCandidates(effort)
        // Score each on sampled pairs of rows by the bits of its gradient residuals.
        val step = maxOf(1, (h.toLong() * w / 65536).toInt())
        var best = 0
        var bestCost = Double.MAX_VALUE
        val rows = Array(3) { IntArray(2 * w) }
        val hist = Array(3) { LongArray(128) }
        val cfg = JxlHybridConfig.DEFAULT
        for (type in types) {
            for (hh in hist) hh.fill(0)
            var extra = 0L
            var y = 1
            while (y < h) {
                forward(type, planes[0], planes[1], planes[2], (y - 1) * w, 2 * w, rows)
                for (c in 0 until 3) {
                    val r = rows[c]
                    for (x in 0 until w) {
                        val v = r[w + x]
                        val left = if (x > 0) r[w + x - 1] else r[x]
                        val top = r[x]
                        val tl = if (x > 0) r[x - 1] else top
                        val res = packSigned(v - jxlClampedGradient(top, left, tl))
                        hist[c][cfg.token(res)]++
                        extra += cfg.extraBitCount(res)
                    }
                }
                y += step
            }
            var cost = extra.toDouble()
            for (hh in hist) {
                var total = 0L
                for (v in hh) total += v
                var s = if (total > 0) total * JxlEncMath.log2(total.toDouble()) else 0.0
                for (v in hh) if (v > 0) s -= v * JxlEncMath.log2(v.toDouble())
                cost += s
            }
            if (cost < bestCost) {
                bestCost = cost
                best = type
            }
        }
        if (best == 0) return Result(channels, emptyList(), 0)
        val out = Array(3) { IntArray(w * h) }
        forward(best, planes[0], planes[1], planes[2], 0, w * h, out)
        for (c in 0 until 3) out[c].copyInto(channels[c].data)
        return Result(channels, listOf(JxlEncTransform(JxlEncTransform.RCT, 0, rctType = best)), 0)
    }

    /**
     * Applies colour transform [type] to [n] samples of three planes from [from], into [out].
     * The decoder puts its three results at the places the permutation names, so the inputs
     * are taken from those places.
     */
    fun forward(type: Int, p0: IntArray, p1: IntArray, p2: IntArray, from: Int, n: Int, out: Array<IntArray>) {
        val perm = type / 7
        val kind = type % 7
        val src = arrayOf(p0, p1, p2)
        val a = src[perm % 3]
        val b = src[(perm + 1 + perm / 3) % 3]
        val c = src[(perm + 2 - perm / 3) % 3]
        val o0 = out[0]
        val o1 = out[1]
        val o2 = out[2]
        val second = kind shr 1
        val third = kind and 1
        for (i in 0 until n) {
            val av = a[from + i]
            val bv = b[from + i]
            val cv = c[from + i]
            if (kind == 6) {
                // YCgCo: the decoder's three steps, undone in reverse.
                val co = av - cv
                val tmp = cv + (co shr 1)
                val cg = bv - tmp
                o0[i] = tmp + (cg shr 1)
                o1[i] = co
                o2[i] = cg
            } else {
                o0[i] = av
                o2[i] = if (third != 0) cv - av else cv
                o1[i] = when (second) {
                    1 -> bv - av
                    2 -> bv - ((av + cv) shr 1)
                    else -> bv
                }
            }
        }
    }
}
