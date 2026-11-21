package io.github.yuroyami.imagekodec.codec.avif

/**
 * The levels the AV1 encoder's search chose for transform blocks, kept for a repeat (#176). Choosing a transform
 * block's levels is a function of the block's residual, the coefficient contexts beside it, the transform types it
 * may take, its place, size and plane, the size and intra direction of the block it belongs to, and the CDFs, which
 * stay frozen while a superblock is searched. The search codes one transform block with the same inputs many times
 * over: the chroma of each luma candidate, the luma of each chroma candidate, and the winners again when a partition
 * is replayed. So each result is kept until the superblock is committed, the last [WAYS] for each place and size, and
 * a call whose inputs match one exactly takes its result, which is the result it would compute.
 */
internal class Av1LevelMemo {

    /** One result, with everything it was chosen from. */
    class Entry {
        var extra = 0
        var residual = IntArray(16)
        val ctx = IntArray(64)
        val types = IntArray(17)
        var levels = IntArray(16)
        var n = 0
        var eob = 0
        var type = 0
        /** The residual the decoder's inverse transform made of [levels], once it has made it. */
        var reconstructed = IntArray(16)
        var hasReconstructed = false
    }

    private val entries = HashMap<Int, ArrayList<Entry>>()
    private val free = ArrayList<Entry>()

    /** Forgets every result, as the CDFs they were priced on have changed. */
    fun forget() {
        for (list in entries.values) free.addAll(list)
        entries.clear()
    }

    /**
     * The result kept under [key] (the block's place, size and plane) whose [extra] (the block's kind and intra
     * direction), transform types ([types], -1 ended), first [ctxSize] contexts of [ctx] and first [size] samples of
     * [residual] all match; null for none.
     */
    fun recall(key: Int, extra: Int, residual: IntArray, size: Int, ctx: IntArray, ctxSize: Int, types: IntArray): Entry? {
        val list = entries[key] ?: return null
        next@ for (k in list.indices.reversed()) {
            val m = list[k]
            if (m.extra != extra) continue
            var t = 0
            while (true) {
                if (m.types[t] != types[t]) continue@next
                if (types[t] < 0) break
                t++
            }
            for (i in 0 until ctxSize) if (m.ctx[i] != ctx[i]) continue@next
            val res = m.residual
            for (i in 0 until size) if (res[i] != residual[i]) continue@next
            return m
        }
        return null
    }

    /** Keeps the result [eob], [type] and the first [n] of [levels], chosen from the inputs [recall] matches. */
    fun remember(key: Int, extra: Int, residual: IntArray, size: Int, ctx: IntArray, ctxSize: Int, types: IntArray, levels: IntArray, n: Int, eob: Int, type: Int): Entry {
        val list = entries.getOrPut(key) { ArrayList(WAYS) }
        val m = if (list.size == WAYS) list.removeAt(0) else if (free.isNotEmpty()) free.removeAt(free.size - 1) else Entry()
        m.extra = extra
        if (m.residual.size < size) m.residual = IntArray(size)
        residual.copyInto(m.residual, 0, 0, size)
        ctx.copyInto(m.ctx, 0, 0, ctxSize)
        var t = 0
        while (true) {
            m.types[t] = types[t]
            if (types[t] < 0) break
            t++
        }
        if (m.levels.size < n) m.levels = IntArray(n)
        levels.copyInto(m.levels, 0, 0, n)
        m.n = n
        m.eob = eob
        m.type = type
        m.hasReconstructed = false
        list += m
        return m
    }

    companion object {
        /** How many results are kept for each place and size of transform block. */
        const val WAYS = 4
    }
}
