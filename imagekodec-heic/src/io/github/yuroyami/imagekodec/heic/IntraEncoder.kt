package io.github.yuroyami.imagekodec.heic

/** A transform tree node of a coding unit as the encoder decided it: its split, and at a leaf its levels. */
internal class EncTu(val x: Int, val y: Int, val log2: Int, val depth: Int, val blkIdx: Int) {
    var children: Array<EncTu>? = null

    /** The coded block flags of a leaf: bit t for block t (two chroma blocks in 4:2:2). */
    var cbfY = 0
    var cbfCb = 0
    var cbfCr = 0

    /** transform_skip_flag of each block of a leaf, in the order of [levels]. */
    var skip = 0

    /** The signed levels of a leaf's blocks, raster order: luma, Cb 0, Cb 1, Cr 0, Cr 1; null for a block of zeros. */
    val levels: Array<IntArray?> = arrayOfNulls(5)
}

/** A coding unit as the encoder decided it. */
internal class EncCu(val x: Int, val y: Int, val log2: Int, val depth: Int) {
    var bypass = false
    /** PartMode is PART_NxN: four luma prediction blocks. */
    var nxn = false
    /** IntraPredModeY of each prediction block. */
    val lumaModes = IntArray(4)
    /** intra_chroma_pred_mode of each chroma prediction block, 0 to 4. */
    val chromaSyntax = IntArray(4)
    lateinit var tu: EncTu

    /** The QP the unit was quantized at: the slice's, or its quantization group's under adaptive QP. */
    var targetQp = 0
    /** QpY as the decoder derives it (8.6.1), set once the CTB is decided. */
    var qpY = 0
    /** The unit codes cu_qp_delta_abs, [qpDelta], in its first transform unit with a coded block. */
    var deltaHere = false
    var qpDelta = 0
}

/** The search an effort level buys (see [HevcEncoderSettings.effort]). */
internal class EffortPlan(effort: Int, lossless: Boolean) {
    /** Luma modes ranked by SATD that go on to a full rate-distortion test, for blocks of 8 and less and for larger ones. */
    val rdModesSmall = when (effort) { 1 -> 2; 2 -> 4; else -> 8 }
    val rdModesLarge = when (effort) { 1 -> 1; 2 -> 3; else -> 5 }
    /**
     * The SATD ranking tries every modeStep-th angle with planar and DC, then the angles around the best two:
     * every fourth at effort 1, every second at effort 2, and all 35 modes at effort 3.
     */
    val modeStep = when (effort) { 1 -> 4; 2 -> 2; else -> 1 }
    /** The first most probable mode joins the full tests when the ranking left it out. */
    val addMpm = effort >= 2
    /** max_transform_hierarchy_depth_intra: how far the residual quadtree may split below a prediction block. */
    val transformDepth = if (effort >= 3) 2 else 1
    /** The residual quadtree splits are tried, not only the prediction block's own size. */
    val tryTransformSplit = effort >= 2
    /** Coding units of 64. */
    val try64 = effort >= 2
    /** The chroma modes tested in full: the derived one, and at effort 2 the best of the others by SATD. */
    val chromaModes = when (effort) { 1 -> 1; 2 -> 3; else -> 5 }
    /** Transform skip for 4x4 blocks. */
    val transformSkip = effort >= 2 && !lossless
    /** Rate-distortion optimised quantisation, else rounding with a dead zone. */
    val rdoq = effort >= 2
    /** A split is not tried below a coding unit whose residual codes nothing, a uniform area. */
    val earlySkip = effort <= 2
}

/** A rectangle of the reconstruction kept to put back after a trial: any of the three planes. */
private class RegionStore(size: Int) {
    val data = Array(3) { ShortArray(size) }
    var x = 0
    var y = 0
    var w = 0
    var h = 0
    var cw = 0
    var ch = 0
    var comps = 0
    var first = 0
}

/**
 * Encodes the CTUs of an intra picture: it decides each CTU's coding quadtree, prediction modes, transform tree
 * and levels by rate and distortion, reconstructs it in [rec], a [PictureDecoder] whose prediction, residual and
 * loop filters are the decoder's own, and writes the decisions as slice data with the same syntax code that
 * its rate estimates run (7.3.8).
 */
internal class IntraEncoder(
    private val rec: PictureDecoder,
    /** The source picture, padded to the coded size, laid out as [rec]'s planes. */
    private val orig: Array<ShortArray>,
    private val qp: Int,
    private val lossless: Boolean,
    private val plan: EffortPlan,
    /** The QP offset of each block of 1 shl [qgLog2] luma samples in raster order under adaptive QP, or null. */
    private val aqOffsets: IntArray? = null,
    /** Log2MinCuQpDeltaSize, the size of a quantization group. */
    private val qgLog2: Int = 6,
) {
    private val sps = rec.sps
    private val pps = rec.pps
    private val cat = rec.chromaArrayType
    private val subW = rec.subWShift
    private val subH = rec.subHShift
    private val width = rec.width
    private val height = rec.height
    private val strides = rec.strides
    private val ctbLog2 = sps.ctbLog2
    private val minCbLog2 = sps.minCbLog2
    private val maxTbLog2 = sps.maxTbLog2
    private val w4 = width shr 2
    private val bitDepthY = sps.bitDepthY
    private val bitDepthC = sps.bitDepthC
    private val comps = if (cat == 0) 1 else 3

    /** The Lagrange multiplier of the current QP for squared errors of [bitDepthY]-bit samples, in 1/256 (lambda * 256). */
    private var lambda: Long = 0
    private var sqrtLambda: Int = 0

    /** Qp′Y, Qp′Cb and Qp′Cr of 8.6.1 for the current QP. */
    private val qpPrime = IntArray(3)
    private var currentQp = -1

    init {
        setQp(qp)
    }

    /** Makes [q] the QP of the decisions that follow: its Qp′ values and its Lagrange multiplier. */
    private fun setQp(q: Int) {
        if (q == currentQp) return
        currentQp = q
        lambda = HevcEncoderTables.LAMBDA_Q8[q].toLong() shl (2 * (bitDepthY - 8))
        sqrtLambda = HevcEncoderTables.SQRT_LAMBDA_Q8[q] shl (bitDepthY - 8)
        qpPrime[0] = q + sps.qpBdOffsetY
        for (c in 1..2) {
            val qpi = (q + (if (c == 1) pps.cbQpOffset else pps.crQpOffset)).coerceIn(-sps.qpBdOffsetC, 57)
            val qpc = if (cat == 1) {
                if (qpi < 30) qpi else if (qpi > 43) qpi - 6 else HevcTables.QPC_30_TO_43[qpi - 30]
            } else {
                minOf(qpi, 51)
            }
            qpPrime[c] = qpc + sps.qpBdOffsetC
        }
    }

    private val qgPerRow = (width + (1 shl qgLog2) - 1) shr qgLog2

    /**
     * The QP of a coding unit of 1 shl [log2] at ([x], [y]) under adaptive QP: its quantization group's, or for a
     * unit larger than a group, the rounded mean of the groups it covers.
     */
    private fun targetQp(x: Int, y: Int, log2: Int): Int {
        val offsets = aqOffsets ?: return qp
        if (log2 <= qgLog2) return (qp + offsets[(y shr qgLog2) * qgPerRow + (x shr qgLog2)]).coerceIn(0, 51)
        var sum = 0
        var n = 0
        val size = 1 shl log2
        var yy = y
        while (yy < minOf(y + size, height)) {
            var xx = x
            while (xx < minOf(x + size, width)) {
                sum += offsets[(yy shr qgLog2) * qgPerRow + (xx shr qgLog2)]
                n++
                xx += 1 shl qgLog2
            }
            yy += 1 shl qgLog2
        }
        val mean = if (sum >= 0) (2 * sum + n) / (2 * n) else -((-2 * sum + n) / (2 * n))
        return (qp + mean).coerceIn(0, 51)
    }

    /** qPY_PREV of 8.6.1: QpY of the last coding unit in decoding order, SliceQpY at the start of the slice. */
    private var lastQpY = qp

    /** The rate estimate, whose states follow the coding units as they are decided. */
    private val est = CabacEncoder(true).also { Ctx.init(it.states, 0, qp) }
    private val quant = Quantizer(est)
    private val forward = ForwardTransform()
    private val inverse = InverseTransform()

    /** CtDepth and IntraPredModeY of each 4x4 unit, as decided so far. */
    private val depthMap = ByteArray(w4 * (height shr 2))
    private val modeMap = ByteArray(w4 * (height shr 2))

    private val resid = IntArray(32 * 32)
    private val coef = IntArray(32 * 32)
    private val levelsA = IntArray(32 * 32)
    private val levelsB = IntArray(32 * 32)
    private val scaled = IntArray(32 * 32)
    private val rbuf = IntArray(32 * 32)
    private val snapshot = ByteArray(Ctx.COUNT)

    private val ctbSize = 1 shl ctbLog2
    private val ctbChroma = (ctbSize shr subW) * (ctbSize shr subH)
    private val nodeSave = Array(4) { RegionStore(ctbSize * ctbSize) }
    private val nodeStates = Array(4) { ByteArray(Ctx.COUNT) }
    private val nodeStart = Array(4) { ByteArray(Ctx.COUNT) }
    private val cuStart = Array(4) { ByteArray(Ctx.COUNT) }
    private val partSave = RegionStore(64)
    private val modeSave = Array(7) { RegionStore(ctbSize * ctbSize) }
    private val tuSave = Array(6) { RegionStore(32 * 32) }
    private val chromaSave = RegionStore(maxOf(ctbChroma, 1))
    private val chromaBest = RegionStore(maxOf(ctbChroma, 1))
    private val blockSave = RegionStore(32 * 32)

    /** The decided coding units of each CTB, in raster order, until the slice data writer takes them. */
    private val decided = arrayOfNulls<List<EncCu>>(sps.picSizeInCtbs)

    // ---------------------------------------------------------------- analysis

    /** Decides and reconstructs the CTB [rs]; the CTBs before it in raster order are done. */
    fun analyseCtb(rs: Int) {
        rec.beginEncodedCtb(rs)
        val x0 = (rs % sps.picWidthInCtbs) shl ctbLog2
        val y0 = (rs / sps.picWidthInCtbs) shl ctbLog2
        val cus = evalNode(x0, y0, ctbLog2, 0)
        for (cu in cus) finalise(cu)
        decided[rs] = cus
    }

    private var qgPred = 0
    private var qgDelta = 0
    private var qgCoded = false

    /**
     * Records [cu] in the reconstruction's state, which deblocking reads, with QpY derived as the decoder does
     * (8.6.1): qPY_PRED from the groups to the left and above inside the CTB or from qPY_PREV, and CuQpDeltaVal
     * coded by the first unit of the group that has a coded block. A unit before it keeps qPY_PRED, which no
     * residual of it reads.
     */
    private fun finalise(cu: EncCu) {
        if (aqOffsets != null) {
            val qgMask = (1 shl qgLog2) - 1
            if (cu.log2 >= qgLog2 || (cu.x and qgMask == 0 && cu.y and qgMask == 0)) {
                val xQg = cu.x and qgMask.inv()
                val yQg = cu.y and qgMask.inv()
                val m = ctbSize - 1
                val a = if (xQg and m != 0) rec.qpY[(yQg shr 2) * w4 + ((xQg - 1) shr 2)].toInt() else lastQpY
                val b = if (yQg and m != 0) rec.qpY[((yQg - 1) shr 2) * w4 + (xQg shr 2)].toInt() else lastQpY
                qgPred = (a + b + 1) shr 1
                qgDelta = 0
                qgCoded = false
            }
            cu.deltaHere = false
            if (!qgCoded && hasResidual(cu.tu)) {
                qgDelta = cu.targetQp - qgPred
                qgCoded = true
                cu.deltaHere = true
                cu.qpDelta = qgDelta
            }
            cu.qpY = qgPred + qgDelta
        } else {
            cu.qpY = qp
        }
        lastQpY = cu.qpY
        rec.markEncodedUnit(cu.x, cu.y, cu.log2, cu.depth, cu.bypass, cu.qpY)
        fun walk(t: EncTu) {
            val ch = t.children
            if (ch != null) ch.forEach { walk(it) } else rec.markEncodedTransform(t.x, t.y, t.log2, t.cbfY != 0)
        }
        walk(cu.tu)
    }

    private var nodeCost = 0L

    /**
     * Decides the coding quadtree node of 1 shl [log2] at ([x], [y]) and depth [depth]. Leaves the decision
     * reconstructed and the estimate's states past it; returns its coding units and sets [nodeCost].
     */
    private fun evalNode(x: Int, y: Int, log2: Int, depth: Int): List<EncCu> {
        val size = 1 shl log2
        val inside = x + size <= width && y + size <= height
        if (!inside) return evalSplit(x, y, log2, depth, coded = false)
        val canSplit = log2 > minCbLog2
        est.states.copyInto(nodeStart[depth])
        if (log2 == 6) {
            // A 64 coding unit predicts four 32 transform blocks in turn, which no single SATD can rank, so its
            // children decide first and offer their modes.
            val split = evalSplit(x, y, log2, depth, coded = true)
            val splitCost = nodeCost
            if (!plan.try64) return split
            save(nodeSave[depth], x, y, log2, 7)
            est.states.copyInto(nodeStates[depth])
            nodeStart[depth].copyInto(est.states)
            val hints = IntArray(6)
            var nh = 0
            for (cu in split) {
                val m = cu.lumaModes[0]
                var seen = false
                for (i in 0 until nh) if (hints[i] == m) seen = true
                if (!seen && nh < 4) hints[nh++] = m
            }
            val leaf = evalLeaf(x, y, log2, depth, canSplit, hints.copyOf(nh))
            if (nodeCost < splitCost) return leaf
            restore(nodeSave[depth])
            nodeStates[depth].copyInto(est.states)
            stamp(split)
            nodeCost = splitCost
            return split
        }
        val leaf = evalLeaf(x, y, log2, depth, canSplit, null)
        val leafCost = nodeCost
        if (!canSplit) return leaf
        if (plan.earlySkip && leaf.size == 1 && !hasResidual(leaf[0].tu)) return leaf
        save(nodeSave[depth], x, y, log2, 7)
        est.states.copyInto(nodeStates[depth])
        nodeStart[depth].copyInto(est.states)
        val split = evalSplit(x, y, log2, depth, coded = true)
        if (nodeCost < leafCost) return split
        restore(nodeSave[depth])
        nodeStates[depth].copyInto(est.states)
        stamp(leaf)
        nodeCost = leafCost
        return leaf
    }

    /** The unit's QP; for the rate estimate, a delta as if from the slice QP, which [finalise] makes exact. */
    private fun markQp(cu: EncCu) {
        cu.targetQp = currentQp
        cu.deltaHere = aqOffsets != null
        cu.qpDelta = currentQp - qp
    }

    private fun lumaResidual(t: EncTu): Boolean {
        val ch = t.children ?: return t.cbfY != 0
        return ch.any { lumaResidual(it) }
    }

    private fun hasResidual(t: EncTu): Boolean {
        val ch = t.children ?: return t.cbfY != 0 || t.cbfCb != 0 || t.cbfCr != 0
        return ch.any { hasResidual(it) }
    }

    private fun evalSplit(x: Int, y: Int, log2: Int, depth: Int, coded: Boolean): List<EncCu> {
        var cost = 0L
        if (coded) {
            est.cost = 0
            est.encode(splitContext(x, y, depth), 1)
            cost = lambda * est.cost
        }
        val half = 1 shl (log2 - 1)
        val out = ArrayList<EncCu>()
        for (k in 0 until 4) {
            val cx = x + (k and 1) * half
            val cy = y + (k shr 1) * half
            if (cx >= width || cy >= height) continue
            out += evalNode(cx, cy, log2 - 1, depth + 1)
            cost += nodeCost
        }
        nodeCost = cost
        return out
    }

    /** split_cu_flag's ctxInc, 9.3.4.2.2: the left and upper neighbours that are deeper. */
    private fun splitContext(x: Int, y: Int, depth: Int): Int {
        var inc = 0
        if (x > 0 && depthMap[(y shr 2) * w4 + ((x - 1) shr 2)] > depth) inc++
        if (y > 0 && depthMap[((y - 1) shr 2) * w4 + (x shr 2)] > depth) inc++
        return Ctx.SPLIT_CU + inc
    }

    /** Stamps the maps with the decided units [cus], after a trial of something else wrote over them. */
    private fun stamp(cus: List<EncCu>) {
        for (cu in cus) stampCu(cu)
    }

    private fun stampCu(cu: EncCu) {
        val size = 1 shl cu.log2
        fill(depthMap, cu.x, cu.y, size, cu.depth)
        if (cu.nxn) {
            val half = size shr 1
            for (k in 0 until 4) fill(modeMap, cu.x + (k and 1) * half, cu.y + (k shr 1) * half, half, cu.lumaModes[k])
        } else {
            fill(modeMap, cu.x, cu.y, size, cu.lumaModes[0])
        }
    }

    private fun fill(map: ByteArray, x: Int, y: Int, size: Int, v: Int) {
        for (j in (y shr 2) until ((y + size) shr 2)) map.fill(v.toByte(), j * w4 + (x shr 2), j * w4 + ((x + size) shr 2))
    }

    /**
     * Decides the coding unit at ([x], [y]) that fills the node: 2Nx2N, and at the smallest size also NxN.
     * Sets [nodeCost] to its cost with its split_cu_flag, and leaves the estimate past it.
     */
    private fun evalLeaf(x: Int, y: Int, log2: Int, depth: Int, flagCoded: Boolean, hints: IntArray?): List<EncCu> {
        val start = cuStart[depth]
        est.states.copyInto(start)
        val target = targetQp(x, y, log2)
        setQp(target)
        var flagCost = 0
        if (flagCoded) flagCost = est.costOf(splitContext(x, y, depth), 0)
        var best = eval2Nx2N(x, y, log2, depth, hints)
        var bestCost = cuCost(best, start)
        // NxN is not tried where 2Nx2N predicts the luma so well that it codes no residual.
        if (log2 == minCbLog2 && log2 == 3 && !(plan.earlySkip && !lumaResidual(best.tu))) {
            save(partSave, x, y, log2, 7)
            val nxn = evalNxN(x, y, depth)
            val nxnCost = cuCost(nxn, start)
            if (nxnCost < bestCost) {
                best = nxn
                bestCost = nxnCost
            } else {
                restore(partSave)
                stampCu(best)
            }
        }
        // Commit: the estimate's states move past the split flag and the unit.
        start.copyInto(est.states)
        if (flagCoded) est.encode(splitContext(x, y, depth), 0)
        writeCodingUnit(est, best)
        nodeCost = bestCost + lambda * flagCost
        return listOf(best)
    }

    /** The cost of the decided unit [cu]: its squared error and the bins of its syntax from the states [start]. */
    private fun cuCost(cu: EncCu, start: ByteArray): Long {
        stampCu(cu)
        start.copyInto(est.states)
        est.cost = 0
        writeCodingUnit(est, cu)
        val rate = est.cost
        start.copyInto(est.states)
        val size = 1 shl cu.log2
        var d = sse(0, cu.x, cu.y, size, size)
        if (cat != 0) for (c in 1..2) d += sse(c, cu.x shr subW, cu.y shr subH, size shr subW, size shr subH)
        return (d shl 16) + lambda * rate
    }

    private fun eval2Nx2N(x: Int, y: Int, log2: Int, depth: Int, hints: IntArray?): EncCu {
        val cu = EncCu(x, y, log2, depth)
        cu.bypass = lossless
        markQp(cu)
        val size = 1 shl log2
        fill(depthMap, x, y, size, depth)
        val candidates = if (hints != null) withMpm(hints, x, y) else rankModes(x, y, log2, if (log2 <= 3) plan.rdModesSmall else plan.rdModesLarge)
        var bestCost = Long.MAX_VALUE
        var bestTu: EncTu? = null
        var bestMode = 0
        val store = modeSave[log2]
        val maxDepth = sps.maxTransformHierarchyDepthIntra
        for (m in candidates) {
            fill(modeMap, x, y, size, m)
            val tu = EncTu(x, y, log2, 0, 0)
            var cost = lumaTree(cu, tu, m, maxDepth)
            cost += lambda * modeCost(x, y, m)
            if (cost < bestCost) {
                bestCost = cost
                bestTu = tu
                bestMode = m
                if (candidates.size > 1) save(store, x, y, log2, 1)
            }
        }
        if (candidates.size > 1) restore(store)
        cu.lumaModes[0] = bestMode
        cu.tu = bestTu!!
        fill(modeMap, x, y, size, bestMode)
        if (cat != 0) decideChroma(cu)
        return cu
    }

    private fun evalNxN(x: Int, y: Int, depth: Int): EncCu {
        val cu = EncCu(x, y, 3, depth)
        cu.bypass = lossless
        cu.nxn = true
        markQp(cu)
        fill(depthMap, x, y, 8, depth)
        val root = EncTu(x, y, 3, 0, 0)
        val kids = Array(4) { k -> EncTu(x + (k and 1) * 4, y + (k shr 1) * 4, 2, 1, k) }
        root.children = kids
        cu.tu = root
        for (k in 0 until 4) {
            val px = kids[k].x
            val py = kids[k].y
            val candidates = rankModes(px, py, 2, plan.rdModesSmall)
            var bestCost = Long.MAX_VALUE
            var bestMode = 0
            for (m in candidates) {
                fill(modeMap, px, py, 4, m)
                val cost = lumaLeaf(cu, kids[k], m, splitCoded = false) + lambda * modeCost(px, py, m)
                if (cost < bestCost) {
                    bestCost = cost
                    bestMode = m
                }
            }
            if (candidates.last() != bestMode) {
                fill(modeMap, px, py, 4, bestMode)
                lumaLeaf(cu, kids[k], bestMode, splitCoded = false)
            }
            cu.lumaModes[k] = bestMode
            fill(modeMap, px, py, 4, bestMode)
        }
        if (cat != 0) decideChroma(cu)
        return cu
    }

    /**
     * The luma transform tree of [tu] for the 2Nx2N unit [cu] predicted with [mode]: the block whole, or split
     * where that costs less. Returns its cost; the reconstruction holds the choice.
     */
    private fun lumaTree(cu: EncCu, tu: EncTu, mode: Int, maxDepth: Int): Long {
        val log2 = tu.log2
        if (log2 > maxTbLog2) return lumaSplit(cu, tu, mode, maxDepth, coded = false)
        val canSplit = log2 > sps.minTbLog2 && tu.depth < maxDepth
        val leafCost = lumaLeaf(cu, tu, mode, canSplit)
        if (!canSplit || !plan.tryTransformSplit || (plan.earlySkip && tu.cbfY == 0)) return leafCost
        val store = tuSave[log2]
        save(store, tu.x, tu.y, log2, 1)
        val leafLevels = tu.levels[0]
        val leafCbf = tu.cbfY
        val leafSkip = tu.skip
        val splitCost = lumaSplit(cu, tu, mode, maxDepth, coded = true)
        if (splitCost < leafCost) return splitCost
        restore(store)
        tu.children = null
        tu.levels[0] = leafLevels
        tu.cbfY = leafCbf
        tu.skip = leafSkip
        return leafCost
    }

    private fun lumaSplit(cu: EncCu, tu: EncTu, mode: Int, maxDepth: Int, coded: Boolean): Long {
        val half = 1 shl (tu.log2 - 1)
        var cost = if (coded) lambda * est.costOf(Ctx.SPLIT_TRANSFORM + 5 - tu.log2, 1) else 0L
        val kids = Array(4) { k -> EncTu(tu.x + (k and 1) * half, tu.y + (k shr 1) * half, tu.log2 - 1, tu.depth + 1, k) }
        tu.children = kids
        tu.levels[0] = null
        tu.cbfY = 0
        for (k in kids) cost += lumaTree(cu, k, mode, maxDepth)
        return cost
    }

    /** One luma transform block [tu], undivided, predicted with [mode]. */
    private fun lumaLeaf(cu: EncCu, tu: EncTu, mode: Int, splitCoded: Boolean): Long {
        var cost = if (splitCoded) lambda * est.costOf(Ctx.SPLIT_TRANSFORM + 5 - tu.log2, 0) else 0L
        tu.children = null
        cost += codeBlock(cu, tu, 0, 0, tu.x, tu.y, tu.log2, mode, Ctx.CBF_LUMA + (if (tu.depth == 0) 1 else 0))
        return cost
    }

    /**
     * Predicts, transforms, quantizes and reconstructs one block of component [cIdx] at ([x], [y]) in its own
     * samples, predicted with [mode], and records it in slot [slot] of [tu]'s levels. Returns its squared
     * error and the bins of its coded block flag (context [cbfCtx]) and residual, at lambda.
     */
    private fun codeBlock(cu: EncCu, tu: EncTu, cIdx: Int, slot: Int, x: Int, y: Int, log2: Int, mode: Int, cbfCtx: Int): Long {
        val n = 1 shl log2
        val bit = 1 shl (if (slot == 0) 0 else (slot - 1) and 1)
        rec.intra.predict(cIdx, x, y, log2, mode, false)
        val plane = rec.planes[cIdx]
        val src = orig[cIdx]
        val stride = strides[cIdx]
        for (j in 0 until n) {
            val row = (y + j) * stride + x
            for (i in 0 until n) resid[j * n + i] = (src[row + i].toInt() and 0xFFFF) - (plane[row + i].toInt() and 0xFFFF)
        }
        val scanIdx = scanIndex(log2, cIdx, mode)
        val bitDepth = if (cIdx == 0) bitDepthY else bitDepthC
        if (cu.bypass) {
            var any = false
            for (i in 0 until n * n) {
                levelsA[i] = resid[i]
                if (resid[i] != 0) any = true
            }
            rec.addResidual(cIdx, x, y, n, resid, bitDepth)
            setBlock(tu, slot, bit, if (any) levelsA.copyOf(n * n) else null, false)
            return lambda * blockRate(cu, cIdx, log2, if (any) levelsA else null, scanIdx, false, cbfCtx)
        }
        val tryTs = plan.transformSkip && log2 == 2 && pps.transformSkipEnabled
        if (tryTs) save(blockSave, x, y, log2, 0, cIdx)
        val costA = transformBlock(cIdx, x, y, log2, scanIdx, bitDepth, cbfCtx, levelsA, false)
        if (!tryTs) {
            setBlock(tu, slot, bit, if (lastNonZero > 0) levelsA.copyOf(n * n) else null, false)
            return costA
        }
        val nzA = lastNonZero
        val keepA = levelsA.copyOf(n * n)
        val savedA = ShortArray(n * n)
        for (j in 0 until n) plane.copyInto(savedA, j * n, (y + j) * stride + x, (y + j) * stride + x + n)
        restore(blockSave)
        val costB = transformBlock(cIdx, x, y, log2, scanIdx, bitDepth, cbfCtx, levelsB, true)
        if (costB < costA && lastNonZero > 0) {
            setBlock(tu, slot, bit, levelsB.copyOf(n * n), true)
            return costB
        }
        for (j in 0 until n) savedA.copyInto(plane, (y + j) * stride + x, j * n, j * n + n)
        setBlock(tu, slot, bit, if (nzA > 0) keepA else null, false)
        return costA
    }

    private var lastNonZero = 0

    /** The transform (or with [skip], none), quantization and reconstruction of the residual in [resid]. */
    private fun transformBlock(cIdx: Int, x: Int, y: Int, log2: Int, scanIdx: Int, bitDepth: Int, cbfCtx: Int, levels: IntArray, skip: Boolean): Long {
        val n = 1 shl log2
        val total = n * n
        val dst = cIdx == 0 && log2 == 2
        if (skip) {
            // 8.6.4.2 scales a skipped residual by 2^tsShift and 8-299 by 2^-bdShift: the inverse is the
            // transform's own coefficient scale, 2^( 15 - BitDepth - Log2( nTbS ) ).
            val shift = 15 - bitDepth - log2
            for (i in 0 until total) coef[i] = resid[i] shl shift
        } else {
            forward.forward(resid, coef, log2, bitDepth, dst)
        }
        val qpc = qpPrime[cIdx]
        val hide = pps.signDataHiding
        val nz = quant.quantize(coef, levels, log2, cIdx, scanIdx, qpc, bitDepth, lambda, plan.rdoq, hide, cbfCtx)
        lastNonZero = nz
        if (nz > 0) {
            val ls = PictureDecoder.LEVEL_SCALE[qpc % 6]
            val shift = qpc / 6 - (bitDepth + log2 - 5)
            var maxX = 0
            var maxY = 0
            for (j in 0 until n) for (i in 0 until n) {
                val l = levels[j * n + i]
                if (l != 0) {
                    scaled[j * n + i] = PictureDecoder.scale(l, 16, ls, shift)
                    if (i > maxX) maxX = i
                    if (j > maxY) maxY = j
                } else {
                    scaled[j * n + i] = 0
                }
            }
            val bdShift = maxOf(20 - bitDepth, 0)
            if (skip) PictureDecoder.transformSkipResidual(scaled, rbuf, log2, bdShift, false)
            else inverse.inverse(scaled, rbuf, log2, dst, maxX, maxY, bdShift, 32767)
            rec.addResidual(cIdx, x, y, n, rbuf, bitDepth)
        }
        val d = sse(cIdx, x, y, n, n)
        val rate = blockRate(null, cIdx, log2, if (nz > 0) levels else null, scanIdx, skip, cbfCtx)
        return (d shl 16) + lambda * rate
    }

    private fun setBlock(tu: EncTu, slot: Int, bit: Int, levels: IntArray?, skip: Boolean) {
        tu.levels[slot] = levels
        val coded = if (levels != null) bit else 0
        when {
            slot == 0 -> tu.cbfY = coded
            slot <= 2 -> tu.cbfCb = (tu.cbfCb and bit.inv()) or coded
            else -> tu.cbfCr = (tu.cbfCr and bit.inv()) or coded
        }
        tu.skip = if (skip) tu.skip or (1 shl slot) else tu.skip and (1 shl slot).inv()
    }

    /** The bins of a block's coded block flag (context [cbfCtx], or none for -1) and its residual, from the current states. */
    private fun blockRate(cu: EncCu?, cIdx: Int, log2: Int, levels: IntArray?, scanIdx: Int, skip: Boolean, cbfCtx: Int): Int {
        var rate = if (cbfCtx >= 0) est.costOf(cbfCtx, if (levels != null) 1 else 0) else 0
        if (levels == null) return rate
        est.states.copyInto(snapshot)
        est.cost = 0
        writeResidual(est, cIdx, log2, levels, scanIdx, skip, cu?.bypass ?: lossless)
        rate += est.cost
        snapshot.copyInto(est.states)
        return rate
    }

    /** scanIdx of 7.4.9.11 for a block of [log2] of [cIdx] predicted with [mode]. */
    private fun scanIndex(log2: Int, cIdx: Int, mode: Int): Int =
        if (log2 == 2 || (log2 == 3 && (cIdx == 0 || cat == 3))) {
            if (mode in 6..14) HevcScan.VERTICAL else if (mode in 22..30) HevcScan.HORIZONTAL else HevcScan.DIAGONAL
        } else {
            HevcScan.DIAGONAL
        }

    private fun sse(cIdx: Int, x: Int, y: Int, w: Int, h: Int): Long {
        val plane = rec.planes[cIdx]
        val src = orig[cIdx]
        val stride = strides[cIdx]
        var total = 0L
        for (j in 0 until h) {
            val row = (y + j) * stride + x
            var s = 0
            for (i in 0 until w) {
                val d = (src[row + i].toInt() and 0xFFFF) - (plane[row + i].toInt() and 0xFFFF)
                s += d * d
            }
            total += s
        }
        return total
    }

    // ---------------------------------------------------------------- mode decisions

    /** The three most probable modes of 8.4.2 for the block at ([x], [y]), from the decided neighbours. */
    private fun mpm(x: Int, y: Int, out: IntArray) {
        val a = if (x > 0) modeMap[(y shr 2) * w4 + ((x - 1) shr 2)].toInt() else IntraPredictor.INTRA_DC
        // The CTB row above keeps no modes (8.4.2): its neighbour counts as DC.
        val b = if (y > 0 && ((y - 1) shr ctbLog2) == (y shr ctbLog2)) modeMap[((y - 1) shr 2) * w4 + (x shr 2)].toInt() else IntraPredictor.INTRA_DC
        if (a == b) {
            if (a < 2) {
                out[0] = 0; out[1] = 1; out[2] = 26
            } else {
                out[0] = a; out[1] = 2 + ((a + 29) % 32); out[2] = 2 + ((a - 2 + 1) % 32)
            }
        } else {
            out[0] = a
            out[1] = b
            out[2] = if (a != 0 && b != 0) 0 else if (a != 1 && b != 1) 1 else 26
        }
    }

    private val mpmBuf = IntArray(3)

    /** The bins of prev_intra_luma_pred_flag and mpm_idx or rem_intra_luma_pred_mode for [mode] at ([x], [y]). */
    private fun modeCost(x: Int, y: Int, mode: Int): Int {
        mpm(x, y, mpmBuf)
        for (i in 0 until 3) if (mpmBuf[i] == mode) return est.costOf(Ctx.PREV_INTRA_LUMA_PRED, 1) + (if (i == 0) 256 else 512)
        return est.costOf(Ctx.PREV_INTRA_LUMA_PRED, 0) + 5 * 256
    }

    private val satdCost = IntArray(35)
    private val predBuf = IntArray(32 * 32)
    private val satdCalc = Satd()
    private var satdFirst = true

    /**
     * The luma modes worth a full test for the block of 1 shl [log2] at ([x], [y]): the [count] of least SATD of
     * the prediction residual plus sqrt( lambda ) times the bits of the mode, and the first most probable mode.
     */
    private fun rankModes(x: Int, y: Int, log2: Int, count: Int): IntArray {
        val n = 1 shl log2
        satdCost.fill(Int.MAX_VALUE)
        satdFirst = true
        if (plan.modeStep == 1) {
            for (m in 0 until 35) satdCost[m] = satdOf(x, y, log2, m)
        } else {
            // A coarse pass over every modeStep-th angle, then the angles around the best two of it.
            val step = plan.modeStep
            satdCost[0] = satdOf(x, y, log2, 0)
            satdCost[1] = satdOf(x, y, log2, 1)
            var best = 2
            var second = -1
            for (m in 2..34 step step) {
                satdCost[m] = satdOf(x, y, log2, m)
                if (satdCost[m] < satdCost[best]) {
                    second = best
                    best = m
                } else if (m != best && (second < 0 || satdCost[m] < satdCost[second])) {
                    second = m
                }
            }
            for (centre in intArrayOf(best, second)) {
                if (centre < 2) continue
                for (m in maxOf(2, centre - step + 1)..minOf(34, centre + step - 1)) if (satdCost[m] == Int.MAX_VALUE) satdCost[m] = satdOf(x, y, log2, m)
            }
        }
        mpm(x, y, mpmBuf)
        val first = mpmBuf[0]
        val picked = IntArray(count + 1)
        var np = 0
        val used = BooleanArray(35)
        for (r in 0 until count) {
            var best = -1
            for (m in 0 until 35) if (!used[m] && satdCost[m] != Int.MAX_VALUE && (best < 0 || satdCost[m] < satdCost[best])) best = m
            if (best < 0) break
            used[best] = true
            picked[np++] = best
        }
        if (!used[first] && plan.addMpm) picked[np++] = first
        // The best goes last, so the reconstruction of a single candidate is the chosen one.
        val out = picked.copyOf(np)
        out.reverse()
        return out
    }

    /** The modes of [hints] and the first most probable mode, the best guess last. */
    private fun withMpm(hints: IntArray, x: Int, y: Int): IntArray {
        mpm(x, y, mpmBuf)
        val list = ArrayList<Int>()
        list += mpmBuf[0]
        for (h in hints.reversed()) if (h !in list) list += h
        return list.toIntArray()
    }

    private fun satdOf(x: Int, y: Int, log2: Int, mode: Int): Int {
        val n = 1 shl log2
        rec.intra.predict(0, x, y, log2, mode, false, !satdFirst)
        satdFirst = false
        val plane = rec.planes[0]
        val src = orig[0]
        val stride = strides[0]
        for (j in 0 until n) {
            val row = (y + j) * stride + x
            for (i in 0 until n) predBuf[j * n + i] = (src[row + i].toInt() and 0xFFFF) - (plane[row + i].toInt() and 0xFFFF)
        }
        val satd = satdCalc.of(predBuf, n)
        return (satd shl 8) + ((sqrtLambda * modeCost(x, y, mode)) shr 8)
    }

    /**
     * Chooses intra_chroma_pred_mode for [cu], whose luma is decided, by coding the chroma of its transform tree
     * with each candidate; in 4:4:4 NxN each of the four prediction blocks on its own.
     */
    private fun decideChroma(cu: EncCu) {
        if (cat == 3 && cu.nxn) {
            for (k in 0 until 4) decideChromaBlock(cu, cu.tu.children!![k], k)
        } else {
            decideChromaBlock(cu, cu.tu, 0)
        }
    }

    private fun decideChromaBlock(cu: EncCu, tu: EncTu, k: Int) {
        val xs = tu.x shr subW
        val ys = tu.y shr subH
        val ws = (1 shl tu.log2) shr subW
        val hs = (1 shl tu.log2) shr subH
        if (plan.chromaModes == 1) {
            cu.chromaSyntax[k] = 4
            chromaTree(cu, tu, k)
            return
        }
        saveChroma(chromaSave, xs, ys, ws, hs)
        val candidates = if (plan.chromaModes >= 5) intArrayOf(0, 1, 2, 3, 4) else rankChroma(cu, k, xs, ys, minOf(ws, hs, 32), plan.chromaModes)
        var bestCost = Long.MAX_VALUE
        var best = 4
        for (s in candidates) {
            cu.chromaSyntax[k] = s
            val bins = if (s == 4) est.costOf(Ctx.INTRA_CHROMA_PRED_MODE, 0) else est.costOf(Ctx.INTRA_CHROMA_PRED_MODE, 1) + 512
            val cost = chromaTree(cu, tu, k) + lambda * bins
            if (cost < bestCost) {
                bestCost = cost
                best = s
            }
            restore(chromaSave)
        }
        cu.chromaSyntax[k] = best
        chromaTree(cu, tu, k)
    }

    /**
     * The derived chroma mode and the [count] - 1 others of least SATD of both chroma components, predicted as one
     * block of [size] at the corner ([xs], [ys]) of the prediction block, which ranks them well enough.
     */
    private fun rankChroma(cu: EncCu, k: Int, xs: Int, ys: Int, size: Int, count: Int): IntArray {
        val log2 = size.countTrailingZeroBits()
        val cost = IntArray(4)
        for (s in 0 until 4) {
            cu.chromaSyntax[k] = s
            val mode = chromaMode(cu, k)
            var satd = 0
            for (c in 1..2) {
                rec.intra.predict(c, xs, ys, log2, mode, false)
                val plane = rec.planes[c]
                val src = orig[c]
                val stride = strides[c]
                for (j in 0 until size) {
                    val row = (ys + j) * stride + xs
                    for (i in 0 until size) predBuf[j * size + i] = (src[row + i].toInt() and 0xFFFF) - (plane[row + i].toInt() and 0xFFFF)
                }
                satd += satdCalc.of(predBuf, size)
            }
            cost[s] = satd
        }
        restore(chromaSave)
        val order = (0 until 4).sortedBy { cost[it] }
        return (order.take(count - 1) + 4).toIntArray()
    }

    /** The prediction mode of chroma block [k] of [cu] (8.4.3), for 4:2:2 through Table 8-3. */
    private fun chromaMode(cu: EncCu, k: Int): Int {
        val luma = cu.lumaModes[k]
        val s = cu.chromaSyntax[k]
        val idx = if (s == 4) luma else PictureDecoder.CHROMA_CANDIDATES[s].let { if (it == luma) 34 else it }
        return if (cat == 2) HevcTables.CHROMA_422_MODE[idx] else idx
    }

    /** Codes the chroma blocks of the transform tree [tu] with the mode of chroma prediction block [k]. */
    private fun chromaTree(cu: EncCu, tu: EncTu, k: Int): Long {
        val ch = tu.children
        if (ch != null) {
            var cost = 0L
            for (c in ch) cost += chromaTree(cu, c, k)
            return cost
        }
        val log2 = tu.log2
        val xC: Int
        val yC: Int
        val log2C: Int
        if (log2 > 2 || cat == 3) {
            log2C = if (cat == 3) log2 else log2 - 1
            xC = tu.x shr subW
            yC = tu.y shr subH
        } else if (tu.blkIdx == 3) {
            log2C = 2
            xC = (tu.x - 4) shr subW
            yC = (tu.y - 4) shr subH
        } else {
            return 0L
        }
        val mode = chromaMode(cu, k)
        val blocks = if (cat == 2) 2 else 1
        var cost = 0L
        for (c in 1..2) for (t in 0 until blocks) {
            cost += codeBlock(cu, tu, c, 1 + (c - 1) * 2 + t, xC, yC + (t shl log2C), log2C, mode, Ctx.CBF_CHROMA + tu.depth)
        }
        return cost
    }

    // ---------------------------------------------------------------- region stores

    /** Saves the reconstruction of the block of 1 shl [log2] luma samples at ([x], [y]): [mask] bit 0 luma, bit 1 and 2 chroma. */
    private fun save(s: RegionStore, x: Int, y: Int, log2: Int, mask: Int, single: Int = -1) {
        val n = 1 shl log2
        s.x = x
        s.y = y
        s.w = n
        s.h = n
        if (single >= 0) {
            // One block of component [single] in its own samples.
            s.comps = 1 shl single
            s.cw = n
            s.ch = n
            s.first = single
            copyOut(s.data[0], single, x, y, n, n)
            return
        }
        s.first = -1
        s.comps = if (cat == 0) mask and 1 else mask
        s.cw = n shr subW
        s.ch = n shr subH
        if (s.comps and 1 != 0) copyOut(s.data[0], 0, x, y, n, n)
        if (s.comps and 2 != 0) copyOut(s.data[1], 1, x shr subW, y shr subH, s.cw, s.ch)
        if (s.comps and 4 != 0) copyOut(s.data[2], 2, x shr subW, y shr subH, s.cw, s.ch)
    }

    private fun saveChroma(s: RegionStore, xs: Int, ys: Int, ws: Int, hs: Int) {
        s.first = -2
        s.x = xs
        s.y = ys
        s.cw = ws
        s.ch = hs
        copyOut(s.data[1], 1, xs, ys, ws, hs)
        copyOut(s.data[2], 2, xs, ys, ws, hs)
    }

    private fun restore(s: RegionStore) {
        when (s.first) {
            -2 -> {
                copyIn(s.data[1], 1, s.x, s.y, s.cw, s.ch)
                copyIn(s.data[2], 2, s.x, s.y, s.cw, s.ch)
            }
            -1 -> {
                if (s.comps and 1 != 0) copyIn(s.data[0], 0, s.x, s.y, s.w, s.h)
                if (s.comps and 2 != 0) copyIn(s.data[1], 1, s.x shr subW, s.y shr subH, s.cw, s.ch)
                if (s.comps and 4 != 0) copyIn(s.data[2], 2, s.x shr subW, s.y shr subH, s.cw, s.ch)
            }
            else -> copyIn(s.data[0], s.first, s.x, s.y, s.w, s.h)
        }
    }

    private fun copyOut(dst: ShortArray, c: Int, x: Int, y: Int, w: Int, h: Int) {
        val p = rec.planes[c]
        val stride = strides[c]
        for (j in 0 until h) p.copyInto(dst, j * w, (y + j) * stride + x, (y + j) * stride + x + w)
    }

    private fun copyIn(src: ShortArray, c: Int, x: Int, y: Int, w: Int, h: Int) {
        val p = rec.planes[c]
        val stride = strides[c]
        for (j in 0 until h) src.copyInto(p, (y + j) * stride + x, j * w, j * w + w)
    }

    // ---------------------------------------------------------------- syntax (7.3.8), for the estimate and the slice data

    /** Writes the CTB [rs]: sao( ) from [rec]'s parameters when the slice has SAO, then its coding quadtree. */
    fun writeCtb(c: CabacEncoder, rs: Int, sliceSao: Boolean) {
        val cus = decided[rs] ?: error("CTB $rs is not decided")
        val rx = rs % sps.picWidthInCtbs
        val ry = rs / sps.picWidthInCtbs
        if (sliceSao) writeSao(c, rs, rx, ry)
        cursor = 0
        writeQuadtree(c, cus, rx shl ctbLog2, ry shl ctbLog2, ctbLog2, 0)
        decided[rs] = null
    }

    private var cursor = 0

    private fun writeQuadtree(c: CabacEncoder, cus: List<EncCu>, x: Int, y: Int, log2: Int, depth: Int) {
        val size = 1 shl log2
        val next = cus[cursor]
        val split = if (x + size <= width && y + size <= height && log2 > minCbLog2) {
            val s = !(next.x == x && next.y == y && next.log2 == log2)
            c.encode(splitContext(x, y, depth), if (s) 1 else 0)
            s
        } else {
            log2 > minCbLog2
        }
        if (split) {
            val half = size shr 1
            for (k in 0 until 4) {
                val cx = x + (k and 1) * half
                val cy = y + (k shr 1) * half
                if (cx < width && cy < height) writeQuadtree(c, cus, cx, cy, log2 - 1, depth + 1)
            }
        } else {
            writeCodingUnit(c, next)
            cursor++
        }
    }

    /** cu_qp_delta_abs and cu_qp_delta_sign_flag waiting for the first transform unit with a coded block. */
    private var pendingDelta = false

    /** coding_unit( ), 7.3.8.5, of an intra unit in an I slice. */
    fun writeCodingUnit(c: CabacEncoder, cu: EncCu) {
        pendingDelta = cu.deltaHere
        if (pps.transquantBypassEnabled) c.encode(Ctx.TRANSQUANT_BYPASS, if (cu.bypass) 1 else 0)
        if (cu.log2 == minCbLog2) c.encode(Ctx.PART_MODE, if (cu.nxn) 0 else 1)
        val parts = if (cu.nxn) 4 else 1
        val half = (1 shl cu.log2) shr 1
        val idx = IntArray(4)
        for (k in 0 until parts) {
            val px = cu.x + (k and 1) * half
            val py = cu.y + (k shr 1) * half
            mpm(px, py, mpmBuf)
            val m = cu.lumaModes[k]
            idx[k] = -1
            for (i in 0 until 3) if (mpmBuf[i] == m) idx[k] = i
            if (idx[k] < 0) {
                // 8-29 to 8-31 run backwards: the remaining mode counts the candidates below it.
                var rem = m
                for (i in 0 until 3) if (mpmBuf[i] < m) rem--
                idx[k] = 3 + rem
            }
            c.encode(Ctx.PREV_INTRA_LUMA_PRED, if (idx[k] < 3) 1 else 0)
        }
        for (k in 0 until parts) {
            val i = idx[k]
            when {
                i == 0 -> c.bypass(0)
                i == 1 -> c.bypassBits(2, 2)
                i == 2 -> c.bypassBits(3, 2)
                else -> c.bypassBits(i - 3, 5)
            }
        }
        if (cat != 0) {
            val chromaParts = if (cat == 3) parts else 1
            for (k in 0 until chromaParts) {
                val s = cu.chromaSyntax[k]
                if (s == 4) {
                    c.encode(Ctx.INTRA_CHROMA_PRED_MODE, 0)
                } else {
                    c.encode(Ctx.INTRA_CHROMA_PRED_MODE, 1)
                    c.bypassBits(s, 2)
                }
            }
        }
        val maxTrafoDepth = sps.maxTransformHierarchyDepthIntra + (if (cu.nxn) 1 else 0)
        writeTransformTree(c, cu, cu.tu, maxTrafoDepth, 0, 0)
    }

    /** The chroma coded block flags of [t] for component [comp] (1 Cb, 2 Cr), as transform_tree( ) codes them at its node. */
    private fun chromaCbf(t: EncTu, comp: Int): Int {
        val ch = t.children ?: return if (comp == 1) t.cbfCb else t.cbfCr
        // 4:2:2 at a split 8x8 node: the two blocks the fourth 4x4 child carries.
        if (cat == 2 && t.log2 == 3) return chromaCbf(ch[3], comp)
        for (k in ch) if (chromaCbf(k, comp) != 0) return 1
        return 0
    }

    /** transform_tree( ), 7.3.8.8. */
    private fun writeTransformTree(c: CabacEncoder, cu: EncCu, t: EncTu, maxTrafoDepth: Int, parentCb: Int, parentCr: Int) {
        val log2 = t.log2
        val split = t.children != null
        if (log2 <= maxTbLog2 && log2 > sps.minTbLog2 && t.depth < maxTrafoDepth && !(cu.nxn && t.depth == 0)) {
            c.encode(Ctx.SPLIT_TRANSFORM + 5 - log2, if (split) 1 else 0)
        }
        var cb = 0
        var cr = 0
        if ((log2 > 2 && cat != 0) || cat == 3) {
            val second = cat == 2 && (!split || log2 == 3)
            if (t.depth == 0 || parentCb != 0) {
                cb = chromaCbf(t, 1)
                c.encode(Ctx.CBF_CHROMA + t.depth, cb and 1)
                if (second) c.encode(Ctx.CBF_CHROMA + t.depth, (cb shr 1) and 1)
            }
            if (t.depth == 0 || parentCr != 0) {
                cr = chromaCbf(t, 2)
                c.encode(Ctx.CBF_CHROMA + t.depth, cr and 1)
                if (second) c.encode(Ctx.CBF_CHROMA + t.depth, (cr shr 1) and 1)
            }
        } else if (cat != 0) {
            cb = parentCb
            cr = parentCr
        }
        val ch = t.children
        if (ch != null) {
            for (k in ch) writeTransformTree(c, cu, k, maxTrafoDepth, cb, cr)
            return
        }
        c.encode(Ctx.CBF_LUMA + (if (t.depth == 0) 1 else 0), t.cbfY)
        // transform_unit( ), 7.3.8.10: delta_qp( ) in the first one with a coded block; no chroma QP offsets.
        if (pendingDelta && (t.cbfY != 0 || cb != 0 || cr != 0)) {
            pendingDelta = false
            writeDeltaQp(c, cu.qpDelta)
        }
        val half = (1 shl cu.log2) shr 1
        val lumaMode = cu.lumaModes[if (cu.nxn) ((if (t.y - cu.y >= half) 2 else 0) + (if (t.x - cu.x >= half) 1 else 0)) else 0]
        if (t.cbfY != 0) writeResidual(c, 0, log2, t.levels[0]!!, scanIndex(log2, 0, lumaMode), t.skip and 1 != 0, cu.bypass)
        if (cat == 0) return
        val log2C = if (log2 > 2 || cat == 3) (if (cat == 3) log2 else log2 - 1) else if (t.blkIdx == 3) 2 else return
        val k = if (cat == 3 && cu.nxn) t.blkIdx else 0
        val mode = chromaMode(cu, k)
        val blocks = if (cat == 2) 2 else 1
        for (comp in 1..2) {
            val cbf = if (comp == 1) cb else cr
            for (b in 0 until blocks) {
                val slot = 1 + (comp - 1) * 2 + b
                if ((cbf shr b) and 1 != 0) writeResidual(c, comp, log2C, t.levels[slot]!!, scanIndex(log2C, comp, mode), (t.skip shr slot) and 1 != 0, cu.bypass)
            }
        }
    }

    /** residual_coding( ), 7.3.8.11, of the signed levels [lv] of an n by n block, n = 1 shl [log2]. */
    fun writeResidual(c: CabacEncoder, cIdx: Int, log2: Int, lv: IntArray, scanIdx: Int, skip: Boolean, bypass: Boolean) {
        val n = 1 shl log2
        val chroma = cIdx > 0
        if (pps.transformSkipEnabled && !bypass && log2 <= pps.log2MaxTransformSkipSize) {
            c.encode(Ctx.TRANSFORM_SKIP + (if (chroma) 1 else 0), if (skip) 1 else 0)
        }
        val subLog2 = log2 - 2
        val subScan = HevcScan.order(subLog2, scanIdx)
        val scan = HevcScan.order(2, scanIdx)
        val subSide = 1 shl subLog2
        var lastSub = -1
        var lastPos = -1
        search@ for (i in (1 shl (2 * subLog2)) - 1 downTo 0) {
            val sub = subScan[i]
            for (k in 15 downTo 0) {
                val pos = scan[k]
                if (lv[(((sub shr 8) shl 2) + (pos shr 8)) * n + ((sub and 0xFF) shl 2) + (pos and 0xFF)] != 0) {
                    lastSub = i
                    lastPos = k
                    break@search
                }
            }
        }
        check(lastSub >= 0) { "a coded block holds no level" }
        val lastSubPos = subScan[lastSub]
        val lastIn = scan[lastPos]
        var lastX = ((lastSubPos and 0xFF) shl 2) + (lastIn and 0xFF)
        var lastY = ((lastSubPos shr 8) shl 2) + (lastIn shr 8)
        if (scanIdx == HevcScan.VERTICAL) { val t = lastX; lastX = lastY; lastY = t }
        val cMax = (log2 shl 1) - 1
        val ctxOffset = if (chroma) 15 else 3 * (log2 - 2) + ((log2 - 1) shr 2)
        val ctxShift = if (chroma) log2 - 2 else (log2 + 1) shr 2
        val px = Quantizer.lastPrefix(lastX)
        val py = Quantizer.lastPrefix(lastY)
        for (b in 0 until px) c.encode(Ctx.LAST_X_PREFIX + ctxOffset + (b shr ctxShift), 1)
        if (px < cMax) c.encode(Ctx.LAST_X_PREFIX + ctxOffset + (px shr ctxShift), 0)
        for (b in 0 until py) c.encode(Ctx.LAST_Y_PREFIX + ctxOffset + (b shr ctxShift), 1)
        if (py < cMax) c.encode(Ctx.LAST_Y_PREFIX + ctxOffset + (py shr ctxShift), 0)
        if (px > 3) {
            val len = (px shr 1) - 1
            c.bypassBits(lastX - (1 shl len) * (2 + (px and 1)), len)
        }
        if (py > 3) {
            val len = (py shr 1) - 1
            c.bypassBits(lastY - (1 shl len) * (2 + (py and 1)), len)
        }
        val subFlags = writeSubFlags
        subFlags.fill(0, 0, subSide * subSide)
        val hide = pps.signDataHiding && !bypass
        val positions = writePositions
        val sigBase = Ctx.SIG_COEFF + (if (chroma) 27 else 0)
        var greater1Ctx = 1
        var firstSubblock = true
        for (i in lastSub downTo 0) {
            val sub = subScan[i]
            val xS = sub and 0xFF
            val yS = sub shr 8
            val base = (yS shl 2) * n + (xS shl 2)
            val right = if (xS < subSide - 1) subFlags[yS * subSide + xS + 1] else 0
            val below = if (yS < subSide - 1) subFlags[(yS + 1) * subSide + xS] else 0
            var inferDc = false
            val coded = if (i < lastSub && i > 0) {
                var any = 0
                for (k in 0..15) {
                    val pos = scan[k]
                    if (lv[base + (pos shr 8) * n + (pos and 0xFF)] != 0) any = 1
                }
                c.encode(Ctx.CODED_SUB_BLOCK + minOf(right + below, 1) + (if (chroma) 2 else 0), any)
                inferDc = true
                any
            } else {
                1
            }
            subFlags[yS * subSide + xS] = coded
            var count = 0
            var start = 15
            if (i == lastSub) {
                positions[count++] = lastPos
                start = lastPos - 1
            }
            if (coded == 1) {
                val prevCsbf = right or (below shl 1)
                for (k in start downTo 0) {
                    val pos = scan[k]
                    val sig = lv[base + (pos shr 8) * n + (pos and 0xFF)] != 0
                    if (k > 0 || !inferDc) {
                        val sigCtx = PictureDecoder.sigContext(log2, cIdx, scanIdx, xS, yS, pos and 0xFF, pos shr 8, prevCsbf)
                        c.encode(sigBase + sigCtx, if (sig) 1 else 0)
                        if (sig) {
                            positions[count++] = k
                            inferDc = false
                        }
                    } else {
                        positions[count++] = 0
                    }
                }
            }
            if (count == 0) continue
            var ctxSet = if (i == 0 || chroma) 0 else 2
            if (!firstSubblock && greater1Ctx == 0) ctxSet++
            firstSubblock = false
            greater1Ctx = 1
            var firstGreater1 = -1
            val g1Base = Ctx.GREATER1 + ctxSet * 4 + (if (chroma) 16 else 0)
            val abs = writeAbs
            for (k in 0 until count) {
                val pos = scan[positions[k]]
                val l = lv[base + (pos shr 8) * n + (pos and 0xFF)]
                abs[k] = if (l < 0) -l else l
            }
            for (k in 0 until minOf(count, 8)) {
                val f = if (abs[k] > 1) 1 else 0
                c.encode(g1Base + minOf(3, greater1Ctx), f)
                if (f == 1 && firstGreater1 < 0) firstGreater1 = k
                if (greater1Ctx > 0) greater1Ctx = if (f == 1) 0 else greater1Ctx + 1
            }
            if (firstGreater1 >= 0) c.encode(Ctx.GREATER2 + ctxSet + (if (chroma) 4 else 0), if (abs[firstGreater1] > 2) 1 else 0)
            val signHidden = hide && positions[0] - positions[count - 1] > 3
            val signCount = if (signHidden) count - 1 else count
            for (k in 0 until signCount) {
                val pos = scan[positions[k]]
                c.bypass(if (lv[base + (pos shr 8) * n + (pos and 0xFF)] < 0) 1 else 0)
            }
            var rice = 0
            for (k in 0 until count) {
                val a = abs[k]
                val baseLevel = if (k < 8) (if (a == 1) 1 else if (k == firstGreater1) (if (a > 2) 3 else 2) else 2) else 1
                val threshold = if (k < 8) (if (k == firstGreater1) 3 else 2) else 1
                if (baseLevel == threshold) {
                    writeRemaining(c, a - baseLevel, rice)
                    if (a > 3 * (1 shl rice)) rice = minOf(rice + 1, 4)
                }
            }
        }
    }

    /** delta_qp( ), 7.3.8.14: cu_qp_delta_abs as a prefix TR of cMax 5 and an EG0 suffix (9.3.3.10), then the sign. */
    private fun writeDeltaQp(c: CabacEncoder, v: Int) {
        val a = if (v < 0) -v else v
        val prefix = minOf(a, 5)
        for (i in 0 until prefix) c.encode(Ctx.CU_QP_DELTA_ABS + (if (i == 0) 0 else 1), 1)
        if (prefix < 5) c.encode(Ctx.CU_QP_DELTA_ABS + (if (prefix == 0) 0 else 1), 0)
        if (a >= 5) {
            var r = a - 5
            var k = 0
            while (r >= (1 shl k)) {
                c.bypass(1)
                r -= 1 shl k
                k++
            }
            c.bypass(0)
            c.bypassBits(r, k)
        }
        if (a != 0) c.bypass(if (v < 0) 1 else 0)
    }

    private val writeSubFlags = IntArray(64)
    private val writePositions = IntArray(16)
    private val writeAbs = IntArray(16)

    /** coeff_abs_level_remaining [rem] with Rice parameter [rice]: the prefix of 9.3.3.11, then EG( rice + 1 ). */
    private fun writeRemaining(c: CabacEncoder, rem: Int, rice: Int) {
        val prefix = rem shr rice
        if (prefix < 4) {
            for (i in 0 until prefix) c.bypass(1)
            c.bypass(0)
            c.bypassBits(rem and ((1 shl rice) - 1), rice)
            return
        }
        c.bypassBits(15, 4)
        var v = rem - (4 shl rice)
        var k = rice + 1
        while (v >= (1 shl k)) {
            c.bypass(1)
            v -= 1 shl k
            k++
        }
        c.bypass(0)
        c.bypassBits(v, k)
    }

    // ---------------------------------------------------------------- SAO syntax

    /** sao( rx, ry ), 7.3.8.3, of the parameters the SAO decision left in [rec]. */
    private fun writeSao(c: CabacEncoder, rs: Int, rx: Int, ry: Int) {
        val p = rec.sao ?: return
        val at = rs * 18
        val widthInCtbs = sps.picWidthInCtbs
        var mergeLeft = false
        if (rx > 0) {
            mergeLeft = same(p, at, at - 18)
            c.encode(Ctx.SAO_MERGE, if (mergeLeft) 1 else 0)
        }
        if (mergeLeft) return
        if (ry > 0) {
            val up = same(p, at, at - widthInCtbs * 18)
            c.encode(Ctx.SAO_MERGE, if (up) 1 else 0)
            if (up) return
        }
        for (cIdx in 0 until comps) {
            val o = at + cIdx * 6
            val type = p[o]
            if (cIdx < 2) {
                c.encode(Ctx.SAO_TYPE, if (type == 0) 0 else 1)
                if (type != 0) c.bypass(if (type == 2) 1 else 0)
            }
            if (type == 0) continue
            val bitDepth = if (cIdx == 0) bitDepthY else bitDepthC
            val cMax = (1 shl (minOf(bitDepth, 10) - 5)) - 1
            for (i in 0 until 4) {
                val a = p[o + 2 + i].let { if (it < 0) -it else it }
                for (b in 0 until a) c.bypass(1)
                if (a < cMax) c.bypass(0)
            }
            if (type == 1) {
                for (i in 0 until 4) if (p[o + 2 + i] != 0) c.bypass(if (p[o + 2 + i] < 0) 1 else 0)
                c.bypassBits(p[o + 1], 5)
            } else if (cIdx < 2) {
                c.bypassBits(p[o + 1], 2)
            }
        }
    }

    /** The SAO parameters of the CTBs at [a] and [b] are one, so the first merges with the second. */
    private fun same(p: IntArray, a: Int, b: Int): Boolean {
        for (i in 0 until comps * 6) if (p[a + i] != p[b + i]) return false
        return true
    }
}

/** The sum of absolute Hadamard transformed differences, in 8x8 tiles, or 4x4 for a 4x4 block. */
internal class Satd {
    private val m = IntArray(64)

    fun of(d: IntArray, n: Int): Int {
        if (n == 4) return (hadamard4(d) + 1) shr 1
        var sum = 0
        var y = 0
        while (y < n) {
            var x = 0
            while (x < n) {
                sum += (hadamard8(d, y * n + x, n) + 2) shr 2
                x += 8
            }
            y += 8
        }
        return sum
    }

    private fun hadamard4(d: IntArray): Int {
        val t = m
        for (j in 0 until 4) {
            val r = j * 4
            val a0 = d[r] + d[r + 1]
            val a1 = d[r] - d[r + 1]
            val a2 = d[r + 2] + d[r + 3]
            val a3 = d[r + 2] - d[r + 3]
            t[r] = a0 + a2
            t[r + 1] = a1 + a3
            t[r + 2] = a0 - a2
            t[r + 3] = a1 - a3
        }
        var s = 0
        for (i in 0 until 4) {
            val a0 = t[i] + t[4 + i]
            val a1 = t[i] - t[4 + i]
            val a2 = t[8 + i] + t[12 + i]
            val a3 = t[8 + i] - t[12 + i]
            s += abs(a0 + a2) + abs(a1 + a3) + abs(a0 - a2) + abs(a1 - a3)
        }
        return s
    }

    /** The rows, then the columns, of the 8-point Hadamard transform as three butterfly stages each. */
    private fun hadamard8(d: IntArray, at: Int, stride: Int): Int {
        val t = m
        for (j in 0 until 8) {
            val p = at + j * stride
            val a0 = d[p] + d[p + 1]
            val a1 = d[p] - d[p + 1]
            val a2 = d[p + 2] + d[p + 3]
            val a3 = d[p + 2] - d[p + 3]
            val a4 = d[p + 4] + d[p + 5]
            val a5 = d[p + 4] - d[p + 5]
            val a6 = d[p + 6] + d[p + 7]
            val a7 = d[p + 6] - d[p + 7]
            val b0 = a0 + a2
            val b1 = a1 + a3
            val b2 = a0 - a2
            val b3 = a1 - a3
            val b4 = a4 + a6
            val b5 = a5 + a7
            val b6 = a4 - a6
            val b7 = a5 - a7
            val r = j * 8
            t[r] = b0 + b4
            t[r + 1] = b1 + b5
            t[r + 2] = b2 + b6
            t[r + 3] = b3 + b7
            t[r + 4] = b0 - b4
            t[r + 5] = b1 - b5
            t[r + 6] = b2 - b6
            t[r + 7] = b3 - b7
        }
        var s = 0
        for (i in 0 until 8) {
            val a0 = t[i] + t[8 + i]
            val a1 = t[i] - t[8 + i]
            val a2 = t[16 + i] + t[24 + i]
            val a3 = t[16 + i] - t[24 + i]
            val a4 = t[32 + i] + t[40 + i]
            val a5 = t[32 + i] - t[40 + i]
            val a6 = t[48 + i] + t[56 + i]
            val a7 = t[48 + i] - t[56 + i]
            val b0 = a0 + a2
            val b1 = a1 + a3
            val b2 = a0 - a2
            val b3 = a1 - a3
            val b4 = a4 + a6
            val b5 = a5 + a7
            val b6 = a4 - a6
            val b7 = a5 - a7
            s += abs(b0 + b4) + abs(b1 + b5) + abs(b2 + b6) + abs(b3 + b7) + abs(b0 - b4) + abs(b1 - b5) + abs(b2 - b6) + abs(b3 - b7)
        }
        return s
    }

    private fun abs(v: Int) = if (v < 0) -v else v
}
