package io.github.yuroyami.imagekodec.codec.avif

/**
 * Where each 8 by 8 window of the source luma occurs, for the intra block copy search of the AV1 encoder (#176):
 * every window that is not flat, at every position, by a hash of its samples. A block whose top left window has
 * the same hash as an earlier window may be a copy of what lies there, which the search then checks sample by
 * sample.
 *
 * The hash is a polynomial in two bases taken over Int arithmetic, which wraps the same on every target: each row
 * of 8 samples first, rolled along the row, then 8 of those rows, rolled down the column. The windows are kept as
 * (hash, position) pairs sorted together, so the windows of one hash are a run in raster order. A flat window is
 * left out, since a flat block is coded better by DC prediction or a palette than by a copy.
 */
internal class Av1BlockHash(private val luma: ShortArray, private val stride: Int, val width: Int, val height: Int) {

    /** The hash of the window at each position whose window fits, a row of [cols] apart. */
    private val cols = width - 7
    private val rows = height - 7
    private val hashes = IntArray(maxOf(0, cols) * maxOf(0, rows))

    /** (hash shl 32) or position of every window that is not flat, sorted. */
    private val sorted: LongArray

    init {
        if (cols > 0 && rows > 0) {
            val rowHash = IntArray(cols * height)
            var p7 = 1
            for (k in 0 until 7) p7 *= ROW_BASE
            for (y in 0 until height) {
                val at = y * stride
                var h = 0
                for (k in 0 until 8) h = h * ROW_BASE + luma[at + k]
                rowHash[y * cols] = h
                for (x in 1 until cols) {
                    h = (h - luma[at + x - 1] * p7) * ROW_BASE + luma[at + x + 7]
                    rowHash[y * cols + x] = h
                }
            }
            var q7 = 1
            for (k in 0 until 7) q7 *= COLUMN_BASE
            for (x in 0 until cols) {
                var h = 0
                for (k in 0 until 8) h = h * COLUMN_BASE + rowHash[k * cols + x]
                hashes[x] = h
                for (y in 1 until rows) {
                    h = (h - rowHash[(y - 1) * cols + x] * q7) * COLUMN_BASE + rowHash[(y + 7) * cols + x]
                    hashes[y * cols + x] = h
                }
            }
        }
        var n = 0
        for (i in hashes.indices) if (!flat(i)) n++
        sorted = LongArray(n)
        n = 0
        for (i in hashes.indices) if (!flat(i)) sorted[n++] = (hashes[i].toLong() shl 32) or i.toLong()
        sorted.sort()
    }

    /** Whether the window at [i] holds one value: its hash is that of a window of its first sample alone. */
    private fun flat(i: Int): Boolean {
        val v = luma[(i / cols) * stride + i % cols].toInt()
        return hashes[i] == flatHash(v)
    }

    private fun flatHash(v: Int): Int {
        var row = 0
        for (k in 0 until 8) row = row * ROW_BASE + v
        var h = 0
        for (k in 0 until 8) h = h * COLUMN_BASE + row
        return h
    }

    /**
     * Calls [visit] with the positions (x, y) of the windows with the hash of the window at ([x], [y]), from the
     * nearest before it in raster order back to the first, until [visit] returns false or [limit] were visited.
     */
    inline fun earlier(x: Int, y: Int, limit: Int, visit: (Int, Int) -> Boolean) {
        val at = run(x, y) ?: return
        var i = at.second
        var n = 0
        while (i >= at.first && n < limit) {
            val pos = (sortedAt(i) and 0xFFFFFFFFL).toInt()
            n++
            if (!visit(pos % columns, pos / columns)) return
            i--
        }
    }

    val columns: Int get() = cols

    fun sortedAt(i: Int): Long = sorted[i]

    /**
     * The run of [sorted] holding the hash of the window at ([x], [y]) and before it in raster order: its first index
     * and the last index before the window itself, or null for none.
     */
    fun run(x: Int, y: Int): Pair<Int, Int>? {
        if (x < 0 || y < 0 || x >= cols || y >= rows) return null
        val i = y * cols + x
        if (flat(i)) return null
        val key = (hashes[i].toLong() shl 32) or i.toLong()
        // This window's own entry, then the first entry of its hash.
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < key) lo = mid + 1 else hi = mid
        }
        val self = lo
        if (self <= 0) return null
        val h = hashes[i]
        lo = 0
        hi = self
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if ((sorted[mid] shr 32).toInt() < h) lo = mid + 1 else hi = mid
        }
        return if (lo <= self - 1) lo to self - 1 else null
    }

    /**
     * How many of the 8 by 8 blocks on the grid of 8 that are not flat have an earlier window with their samples, in
     * 1/256 of them: how much of the picture repeats.
     */
    fun repeatedShare(): Int {
        var blocks = 0
        var repeated = 0
        var y = 0
        while (y < rows) {
            var x = 0
            while (x < cols) {
                if (!flat(y * cols + x)) {
                    blocks++
                    var found = false
                    earlier(x, y, 8) { px, py ->
                        found = same(px, py, x, y, 8, 8)
                        !found
                    }
                    if (found) repeated++
                }
                x += 8
            }
            y += 8
        }
        return if (blocks == 0) 0 else repeated * 256 / blocks
    }

    /** Whether the [w] by [h] luma at ([ax], [ay]) and at ([bx], [by]) are the same, over the part inside the picture. */
    fun same(ax: Int, ay: Int, bx: Int, by: Int, w: Int, h: Int): Boolean {
        val ww = minOf(w, width - maxOf(ax, bx))
        val hh = minOf(h, height - maxOf(ay, by))
        for (j in 0 until hh) {
            val a = (ay + j) * stride + ax
            val b = (by + j) * stride + bx
            for (i in 0 until ww) if (luma[a + i] != luma[b + i]) return false
        }
        return true
    }

    companion object {
        private const val ROW_BASE = 1000003
        private const val COLUMN_BASE = 998244353

        /** The largest picture whose windows are hashed, 4 Mi samples: two Ints and a Long a sample. */
        const val MAX_SAMPLES = 1 shl 22
    }
}
