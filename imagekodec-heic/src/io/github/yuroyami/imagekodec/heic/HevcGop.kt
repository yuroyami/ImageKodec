package io.github.yuroyami.imagekodec.heic

/** One picture of a planned sequence, in decoding order. */
internal class GopPicture(
    /** The frame's index in presentation order. */
    val frame: Int,
    /** PicOrderCntVal: the frame's distance from the IDR picture that starts its period. */
    val poc: Int,
    val sliceType: Int,
    val idr: Boolean,
    /** 0 for an I or P picture, and for a B picture its depth in the pyramid, 1 for the middle one. */
    val layer: Int,
    /** The picture order counts of RefPicList0 and RefPicList1, as 8.3.4 builds them from [rps]. */
    val refL0: IntArray,
    val refL1: IntArray,
    /** Later pictures refer to it. */
    val reference: Boolean,
) {
    /** The reference picture set (8.3.2): every picture this one or a later one refers to. Null for an IDR picture. */
    var rps: ShortTermRps? = null
}

/**
 * The coding order and references of a sequence of frames, and what its SPS has to say of them: the decoded
 * picture buffer it needs (sps_max_dec_pic_buffering_minus1), its reordering (sps_max_num_reorder_pics) and
 * the bits of slice_pic_order_cnt_lsb.
 */
internal class GopPlan(
    val pictures: List<GopPicture>,
    val maxDecPicBufferingMinus1: Int,
    val maxNumReorder: Int,
    val log2MaxPocLsb: Int,
)

/**
 * Plans the pictures of a sequence. Each period of [irapPeriod] frames (the whole sequence for 0) starts with an
 * IDR picture. The frames after it go in groups of [gopSize]: the last frame of a group is a P picture that refers
 * to the pictures before it, and the frames between two such anchors are B pictures of a dyadic pyramid, the
 * middle one first, each referring to the nearest coded pictures on both sides. A group of 1 is IPPP. A picture
 * refers to the [refsPerList] nearest earlier reference pictures in list 0 and, in a B picture, as many later ones
 * in list 1. No picture refers across an IDR picture, so every period decodes on its own.
 */
internal object GopPlanner {

    fun plan(frames: Int, gopSize: Int, irapPeriod: Int, refsPerList: Int): GopPlan {
        require(frames >= 1) { "HEVC: a sequence of $frames frames is empty" }
        require(gopSize >= 1) { "HEVC: a group of $gopSize pictures is empty" }
        require(irapPeriod >= 0) { "HEVC: an IRAP period of $irapPeriod frames is negative" }
        require(refsPerList >= 1) { "HEVC: $refsPerList references per list" }
        val out = ArrayList<GopPicture>(frames)
        val period = if (irapPeriod == 0) frames else irapPeriod
        var start = 0
        while (start < frames) {
            val end = minOf(frames, start + period)
            planPeriod(start, end, gopSize, refsPerList, out)
            start = end
        }
        // The reference picture set of each picture: what it and the later pictures of its period refer to.
        var periodStart = 0
        for (k in out.indices) {
            val p = out[k]
            if (p.idr) {
                periodStart = k
                continue
            }
            val decoded = HashSet<Int>()
            for (j in periodStart until k) decoded += out[j].poc
            val keep = HashSet<Int>()
            var j = k
            while (j < out.size && !out[j].idr) {
                for (r in out[j].refL0) if (r in decoded) keep += r
                for (r in out[j].refL1) if (r in decoded) keep += r
                j++
            }
            val used = (p.refL0 + p.refL1).toSet()
            val before = keep.filter { it < p.poc }.sortedDescending()
            val after = keep.filter { it > p.poc }.sorted()
            p.rps = ShortTermRps(
                IntArray(before.size) { before[it] - p.poc }, BooleanArray(before.size) { before[it] in used },
                IntArray(after.size) { after[it] - p.poc }, BooleanArray(after.size) { after[it] in used },
            )
        }
        // sps_max_num_reorder_pics: the most pictures that precede a picture in decoding order and follow it in output order.
        var reorder = 0
        for (k in out.indices) {
            var n = 0
            for (j in 0 until k) if (out[j].frame > out[k].frame) n++
            reorder = maxOf(reorder, n)
        }
        val dpb = maxOf(simulateBuffer(out, reorder), reorder + 1)
        // 8.3.1 recovers PicOrderCntMsb while two pictures in a row differ by less than half of MaxPicOrderCntLsb.
        var maxStep = 1
        for (k in 1 until out.size) if (!out[k].idr) maxStep = maxOf(maxStep, kotlin.math.abs(out[k].poc - out[k - 1].poc))
        val log2Lsb = maxOf(4, ceilLog2(2 * maxStep + 1))
        require(log2Lsb <= 16) { "HEVC: a group of $gopSize pictures passes the picture order count's 16 bits" }
        return GopPlan(out, dpb - 1, reorder, log2Lsb)
    }

    /** Plans the frames [start] to [end] - 1, an IDR picture and its groups, into [out] in decoding order. */
    private fun planPeriod(start: Int, end: Int, gopSize: Int, refs: Int, out: MutableList<GopPicture>) {
        out += GopPicture(start, 0, SliceType.I, true, 0, IntArray(0), IntArray(0), true)
        // The reference pictures of the period decoded so far, by picture order count.
        val available = ArrayList<Int>()
        available += 0
        fun before(poc: Int): IntArray = available.filter { it < poc }.sortedDescending().take(refs).toIntArray()
        fun after(poc: Int): IntArray = available.filter { it > poc }.sorted().take(refs).toIntArray()
        var prev = start
        while (prev + 1 < end) {
            val anchor = minOf(prev + gopSize, end - 1)
            val apoc = anchor - start
            out += GopPicture(anchor, apoc, SliceType.P, false, 0, before(apoc), IntArray(0), true)
            available += apoc
            // The pyramid between the two anchors, the middle first, then each half in turn.
            fun pyramid(lo: Int, hi: Int, layer: Int) {
                if (hi - lo < 2) return
                val mid = (lo + hi) / 2
                val poc = mid - start
                val reference = hi - lo > 2
                out += GopPicture(mid, poc, SliceType.B, false, layer, before(poc), after(poc), reference)
                if (reference) available += poc
                pyramid(lo, mid, layer + 1)
                pyramid(mid, hi, layer + 1)
            }
            pyramid(prev, anchor, 1)
            prev = anchor
        }
    }

    /**
     * The decoded picture buffer size the plan needs: C.5.2.2 and C.5.2.3 run over [pictures] with [reorder] as
     * sps_max_num_reorder_pics and no bound on the buffer, and the most pictures it holds while a picture is
     * decoded, the current one included. With that as sps_max_dec_pic_buffering_minus1 + 1, a decoder outputs
     * pictures only as the reorder count asks, in order.
     */
    private fun simulateBuffer(pictures: List<GopPicture>, reorder: Int): Int {
        class Held(val poc: Int, var reference: Boolean, var waiting: Boolean)
        val held = ArrayList<Held>()
        var most = 1
        fun bump() {
            val first = held.filter { it.waiting }.minByOrNull { it.poc } ?: return
            first.waiting = false
            if (!first.reference) held.remove(first)
        }
        for (p in pictures) {
            if (p.idr) {
                held.clear()
            } else {
                val rps = p.rps!!
                val kept = HashSet<Int>()
                for (d in rps.deltaPocS0) kept += p.poc + d
                for (d in rps.deltaPocS1) kept += p.poc + d
                for (h in held) h.reference = h.poc in kept
                held.removeAll { !it.waiting && !it.reference }
                while (held.count { it.waiting } > reorder) bump()
            }
            most = maxOf(most, held.size + 1)
            held += Held(p.poc, true, true)
            while (held.count { it.waiting } > reorder) bump()
        }
        return most
    }
}
