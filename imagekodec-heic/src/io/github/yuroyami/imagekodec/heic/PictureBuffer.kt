package io.github.yuroyami.imagekodec.heic

/**
 * The reference picture lists of one P or B slice (8.3.4), with ColPic and NoBackwardPredFlag of 8.3.5.
 * [longTerm] holds, for each entry, whether its picture was marked "used for long-term reference" while
 * the slice was decoded: LongTermRefPic( ) of 8.5.3.2.1.
 */
internal class RefLists(
    val pictures: Array<Array<DecodedPicture>>,
    val longTerm: Array<BooleanArray>,
    val currentPoc: Int,
    val colPic: DecodedPicture?,
    val noBackwardPred: Boolean,
) {
    /** PicOrderCntVal of RefPicListX[ [idx] ] for X = [list]. */
    fun poc(list: Int, idx: Int): Int = pictures[list][idx].poc
}

/**
 * The motion a picture keeps for the temporal candidates of the pictures after it (8.5.3.2.8): the
 * prediction block that covers each corner ( ( x >> 4 ) << 4, ( y >> 4 ) << 4 ) of a 16x16 grid, the
 * only positions 8.5.3.2.8 reads. Per entry, [flags] holds PredFlagL0 (1), PredFlagL1 (2) and whether
 * each list's reference was long-term (4 and 8); 0 is a block coded in intra mode. [mv] and [refPoc]
 * hold MvLX and the picture order count of the reference at entry * 2 + X.
 */
internal class CollocatedMotion(val width16: Int, entries: Int) {
    val mv: IntArray = IntArray(2 * entries)
    val refPoc: IntArray = IntArray(2 * entries)
    val flags: ByteArray = ByteArray(entries)
}

/**
 * The decoded picture buffer of C.5.2 with the picture order count of 8.3.1, the reference picture set
 * of 8.3.2, the pictures 8.3.3 generates and the reference picture lists of 8.3.4. The buffer lives as
 * long as the decoder, so the samples of a sequence can refer to one another.
 *
 * When [keepOutput] is set, the pictures that the "bumping" process outputs are kept, in output order,
 * for [takeOutput]; otherwise a picture only stops being needed for output, so a decoder that shows
 * pictures in decoding order holds nothing it does not need.
 */
internal class PictureBuffer(private val keepOutput: Boolean) {
    /** The pictures in the buffer, the current one excluded until it is decoded. */
    private val pictures = ArrayList<DecodedPicture>()
    private val output = ArrayList<DecodedPicture>()

    /** The first picture of the stream, or the first after an end of sequence NAL unit (8.1.3), is next. */
    private var startOfSequence = true
    /** NoRaslOutputFlag of the last IRAP picture, which its RASL pictures take after it. */
    private var irapNoRaslOutput = true
    /** slice_pic_order_cnt_lsb and PicOrderCntMsb of prevTid0Pic (8.3.1). */
    private var prevPocLsb = 0
    private var prevPocMsb = 0
    private var firstPicture = true

    // The reference picture set of the current picture (8.3.2).
    private val stCurrBefore = ArrayList<DecodedPicture>()
    private val stCurrAfter = ArrayList<DecodedPicture>()
    private val ltCurr = ArrayList<DecodedPicture>()

    /** An end of sequence NAL unit: the next picture starts afresh (8.1.3). */
    fun endOfSequence() {
        startOfSequence = true
    }

    /**
     * The work before the picture whose first slice segment header is [h] is decoded: whether it is a
     * RASL picture to drop, then its picture order count, its reference picture set with the pictures
     * the set lacks, and the output and removal of C.5.2.2. Returns the picture order count, or null
     * for a picture that is not decoded.
     */
    fun beginPicture(h: SliceHeader): Int? {
        val sps = h.sps
        val type = h.nalType
        val irap = h.isIrap
        // 8.1.3: NoRaslOutputFlag of an IRAP picture. No external means sets HandleCraAsBlaFlag.
        val noRaslOutput = irap && (type != NalType.CRA_NUT || startOfSequence)
        if (irap) irapNoRaslOutput = noRaslOutput
        // 8.3.3, NOTE: the RASL pictures of an IRAP picture with NoRaslOutputFlag 1 are not decoded.
        if ((type == NalType.RASL_N || type == NalType.RASL_R) && irapNoRaslOutput) return null
        startOfSequence = false
        val poc = pictureOrderCount(h, irap && noRaslOutput)
        referencePictureSet(h, poc, irap && noRaslOutput)
        outputAndRemoval(h, irap && noRaslOutput)
        // The current picture is about to take a buffer of its own, within the core's ceiling.
        reserve(sps, 1)
        firstPicture = false
        return poc
    }

    /** 8.3.1. Returns PicOrderCntVal and remembers the picture as prevTid0Pic when it qualifies. */
    private fun pictureOrderCount(h: SliceHeader, irapNoRasl: Boolean): Int {
        val maxLsb = 1 shl h.sps.log2MaxPocLsb
        val lsb = h.picOrderCntLsb
        val msb = when {
            irapNoRasl -> 0
            lsb < prevPocLsb && prevPocLsb - lsb >= maxLsb / 2 -> prevPocMsb + maxLsb
            lsb > prevPocLsb && lsb - prevPocLsb > maxLsb / 2 -> prevPocMsb - maxLsb
            else -> prevPocMsb
        }
        // 8.3.1 bounds PicOrderCntVal to 32 bits; a stream that runs past them is broken.
        val poc = msb.toLong() + lsb
        if (poc < Int.MIN_VALUE || poc > Int.MAX_VALUE) hevcError("PicOrderCntVal $poc passes 32 bits")
        // prevTid0Pic: TemporalId 0 and neither a RASL, a RADL nor a sub-layer non-reference picture.
        val type = h.nalType
        val slnr = type <= 14 && type % 2 == 0
        if (h.temporalId == 0 && type !in 6..9 && !slnr) {
            prevPocLsb = lsb
            prevPocMsb = msb
        }
        return poc.toInt()
    }

    /** 8.3.2 with the marking of its steps, and 8.3.3 for the pictures a BLA or CRA picture lacks. */
    private fun referencePictureSet(h: SliceHeader, poc: Int, irapNoRasl: Boolean) {
        stCurrBefore.clear()
        stCurrAfter.clear()
        ltCurr.clear()
        if (irapNoRasl) for (p in pictures) p.reference = DecodedPicture.UNUSED
        val rps = h.shortTermRps
        if (h.nalType == NalType.IDR_W_RADL || h.nalType == NalType.IDR_N_LP || rps == null) {
            for (p in pictures) p.reference = DecodedPicture.UNUSED
            return
        }
        val sps = h.sps
        val maxLsb = 1 shl sps.log2MaxPocLsb
        val keep = HashSet<DecodedPicture>()
        // Steps 1 and 2: the long-term entries, by their whole picture order count or by its LSBs.
        val nLt = h.pocLsbLt.size
        val ltFoll = ArrayList<Pair<Int, Boolean>>()
        val ltCurrPoc = ArrayList<Pair<Int, Boolean>>()
        for (i in 0 until nLt) {
            var pocLt = h.pocLsbLt[i].toLong()
            if (h.deltaPocMsbPresent[i]) pocLt += poc.toLong() - h.deltaPocMsbCycleLt[i] * maxLsb - (poc and (maxLsb - 1))
            if (pocLt < Int.MIN_VALUE || pocLt > Int.MAX_VALUE) hevcError("the long-term picture order count $pocLt passes 32 bits")
            (if (h.usedByCurrPicLt[i]) ltCurrPoc else ltFoll) += pocLt.toInt() to h.deltaPocMsbPresent[i]
        }
        fun longTerm(pocLt: Int, full: Boolean): DecodedPicture? = pictures.firstOrNull {
            it.reference != DecodedPicture.UNUSED && (if (full) it.poc == pocLt else (it.poc and (maxLsb - 1)) == pocLt)
        }
        val ltCurrPics = ltCurrPoc.map { (p, full) -> longTerm(p, full) }
        val ltFollPics = ltFoll.map { (p, full) -> longTerm(p, full) }
        for (p in ltCurrPics) if (p != null) { p.reference = DecodedPicture.LONG_TERM; keep += p }
        for (p in ltFollPics) if (p != null) { p.reference = DecodedPicture.LONG_TERM; keep += p }
        // Step 3: the short-term entries, by their picture order count.
        fun shortTerm(target: Int): DecodedPicture? = pictures.firstOrNull { it.reference == DecodedPicture.SHORT_TERM && it.poc == target }
        val missingStFoll = ArrayList<Int>()
        for (i in 0 until rps.numNegative) {
            val p = shortTerm(poc + rps.deltaPocS0[i])
            if (rps.usedS0[i]) stCurrBefore += p ?: missing(poc + rps.deltaPocS0[i])
            else if (p == null) missingStFoll += poc + rps.deltaPocS0[i]
            if (p != null) keep += p
        }
        for (i in 0 until rps.numPositive) {
            val p = shortTerm(poc + rps.deltaPocS1[i])
            if (rps.usedS1[i]) stCurrAfter += p ?: missing(poc + rps.deltaPocS1[i])
            else if (p == null) missingStFoll += poc + rps.deltaPocS1[i]
            if (p != null) keep += p
        }
        for ((k, p) in ltCurrPics.withIndex()) ltCurr += p ?: missing(ltCurrPoc[k].first)
        // Step 4.
        for (p in pictures) if (p !in keep) p.reference = DecodedPicture.UNUSED
        // 8.3.3: a BLA picture, or a CRA picture that starts decoding, stands in for the pictures it lacks.
        if (h.nalType in NalType.BLA_W_LP..NalType.CRA_NUT && irapNoRasl) {
            for (p in missingStFoll) generate(h.sps, p, DecodedPicture.SHORT_TERM)
            for ((k, p) in ltFollPics.withIndex()) if (p == null) generate(h.sps, ltFoll[k].first, DecodedPicture.LONG_TERM)
        }
    }

    /** An entry of RefPicSetStCurrBefore, RefPicSetStCurrAfter or RefPicSetLtCurr that is "no reference picture". */
    private fun missing(poc: Int): Nothing =
        hevcError("the reference picture set names a picture of PicOrderCntVal $poc that the decoder does not hold")

    /** 8.3.3.2: a picture of mid-grey samples coded in intra mode, never output. */
    private fun generate(sps: Sps, poc: Int, reference: Int) {
        reserve(sps, 1)
        val n = sps.width * sps.height
        val cw = sps.width / sps.subWidthC
        val ch = sps.height / sps.subHeightC
        val planes = if (sps.chromaArrayType == 0) arrayOf(ShortArray(n)) else arrayOf(ShortArray(n), ShortArray(cw * ch), ShortArray(cw * ch))
        planes[0].fill((1 shl (sps.bitDepthY - 1)).toShort())
        for (c in 1 until planes.size) planes[c].fill((1 shl (sps.bitDepthC - 1)).toShort())
        val strides = if (planes.size == 1) intArrayOf(sps.width) else intArrayOf(sps.width, cw, cw)
        val p = DecodedPicture(sps, planes, strides)
        p.poc = poc
        p.reference = reference
        p.outputFlag = false
        pictures += p
    }

    /**
     * Checks that [extra] more pictures of [sps] fit beside those in the buffer: the core's pixel
     * ceiling bounds them all together, before any is allocated.
     */
    private fun reserve(sps: Sps, extra: Int) {
        var pixels = sps.width.toLong() * sps.height * extra
        for (p in pictures) pixels += p.sps.width.toLong() * p.sps.height
        if (pixels > MAX_PIXELS) hevcError("the decoded picture buffer would hold $pixels samples, past the size ceiling")
    }

    /** C.5.2.2, before the current picture is decoded. */
    private fun outputAndRemoval(h: SliceHeader, irapNoRasl: Boolean) {
        val sps = h.sps
        val highest = sps.maxSubLayersMinus1
        if (irapNoRasl && !firstPicture) {
            // A CRA picture here follows an end of sequence NAL unit; it drops the pictures before it.
            val noOutputOfPriorPics = h.nalType == NalType.CRA_NUT || h.noOutputOfPriorPics
            if (!noOutputOfPriorPics) while (pictures.any { it.neededForOutput }) bump()
            pictures.clear()
            return
        }
        pictures.removeAll { !it.neededForOutput && it.reference == DecodedPicture.UNUSED }
        val maxBuffering = sps.maxDecPicBufferingMinus1[highest] + 1
        while (needsBumping(sps) || pictures.size >= maxBuffering) {
            if (pictures.none { it.neededForOutput }) {
                // Every picture is a reference: the stream holds more than its sps_max_dec_pic_buffering_minus1.
                if (pictures.size >= maxBuffering) hevcError("the decoded picture buffer holds ${pictures.size} reference pictures, past sps_max_dec_pic_buffering_minus1")
                break
            }
            bump()
        }
    }

    /** The first two conditions of C.5.2.2 and C.5.2.3: too many pictures waiting, or one waiting too long. */
    private fun needsBumping(sps: Sps): Boolean {
        val highest = sps.maxSubLayersMinus1
        var waiting = 0
        var latest = 0
        for (p in pictures) if (p.neededForOutput) {
            waiting++
            if (p.latency > latest) latest = p.latency
        }
        if (waiting > sps.maxNumReorderPics[highest]) return true
        val latencyPlus1 = sps.maxLatencyIncreasePlus1[highest]
        // SpsMaxLatencyPictures, 7-9.
        return latencyPlus1 != 0L && waiting > 0 && latest >= sps.maxNumReorderPics[highest] + latencyPlus1 - 1
    }

    /** The "bumping" process of C.5.2.4. */
    private fun bump() {
        var first: DecodedPicture? = null
        for (p in pictures) if (p.neededForOutput && (first == null || p.poc < first.poc)) first = p
        val p = first ?: return
        p.neededForOutput = false
        if (keepOutput) output += p
        if (p.reference == DecodedPicture.UNUSED) pictures.remove(p)
    }

    /**
     * The reference picture lists of the P or B slice [h] of the picture of PicOrderCntVal [poc] (8.3.4),
     * with ColPic and NoBackwardPredFlag (8.3.5). Every reference must have the current picture's size
     * and format, which a new SPS in the middle of a sequence could otherwise break.
     */
    fun refLists(h: SliceHeader, poc: Int): RefLists {
        val sps = h.sps
        val total = stCurrBefore.size + stCurrAfter.size + ltCurr.size
        if (total == 0) hevcError("a ${if (h.sliceType == SliceType.B) "B" else "P"} slice has no reference picture (NumPicTotalCurr is 0)")
        for (p in stCurrBefore + stCurrAfter + ltCurr) {
            val s = p.sps
            if (s.width != sps.width || s.height != sps.height || s.chromaFormatIdc != sps.chromaFormatIdc ||
                s.bitDepthY != sps.bitDepthY || s.bitDepthC != sps.bitDepthC
            ) hevcError("a reference picture of ${s.width} by ${s.height} differs from the current picture's format")
        }
        val lists = if (h.sliceType == SliceType.B) 2 else 1
        val pics = Array(lists) { x ->
            val active = (if (x == 0) h.numRefIdxL0ActiveMinus1 else h.numRefIdxL1ActiveMinus1) + 1
            // 8-8 and 8-10: the temporary list repeats the set, so entry k of it is entry k % NumPicTotalCurr of the set.
            val order = if (x == 0) stCurrBefore + stCurrAfter + ltCurr else stCurrAfter + stCurrBefore + ltCurr
            val entries = if (x == 0) h.listEntryL0 else h.listEntryL1
            Array(active) { i -> order[(entries?.get(i) ?: i) % order.size] }
        }
        val lt = Array(lists) { x -> BooleanArray(pics[x].size) { i -> pics[x][i] in ltCurr } }
        val col = if (!h.temporalMvpEnabled) null else if (h.sliceType == SliceType.B && !h.collocatedFromL0) pics[1][h.collocatedRefIdx] else pics[0][h.collocatedRefIdx]
        val noBackward = pics.all { list -> list.all { it.poc <= poc } }
        return RefLists(pics, lt, poc, col, noBackward)
    }

    /**
     * C.5.2.3 and step 4 of 8.1.3, once [p] is decoded: it joins the buffer as a short-term reference,
     * waits for output when PicOutputFlag is 1, and the pictures waiting too long are output.
     */
    fun endPicture(p: DecodedPicture) {
        if (p.outputFlag) for (q in pictures) if (q.neededForOutput && q.poc > p.poc) q.latency++
        p.neededForOutput = p.outputFlag
        p.latency = 0
        p.reference = DecodedPicture.SHORT_TERM
        pictures += p
        while (needsBumping(p.sps)) bump()
    }

    /** Outputs every picture still waiting, as at the end of a stream. */
    fun flush() {
        while (pictures.any { it.neededForOutput }) bump()
        pictures.removeAll { !it.neededForOutput && it.reference == DecodedPicture.UNUSED }
    }

    /** The pictures output since the last call, in output order; empty unless the buffer keeps output. */
    fun takeOutput(): List<DecodedPicture> {
        val out = ArrayList(output)
        output.clear()
        return out
    }
}
