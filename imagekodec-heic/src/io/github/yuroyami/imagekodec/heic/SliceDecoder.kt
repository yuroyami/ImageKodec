package io.github.yuroyami.imagekodec.heic

/**
 * Decodes the slice segments of one picture: it parses each CTU (7.3.8) with CABAC (9.3) and
 * reconstructs it at once (8.4, 8.5, 8.6). The picture keeps, in units of 4x4 luma samples, only what a
 * later stage needs: the prediction mode, QpY, the PCM and bypass flags, the transform and prediction
 * edges, and for a picture with P or B slices the motion of [MotionField].
 * [sps] and [pps] must have passed [Pps.checkAgainst]. Under separate_colour_plane_flag each of the three
 * colour planes has a decoder of its own, a monochrome picture of [colourPlane] (8.1.3).
 */
internal class PictureDecoder(val sps: Sps, val pps: Pps, val colourPlane: Int = 0) {
    val width: Int = sps.width
    val height: Int = sps.height
    val chromaArrayType: Int = sps.chromaArrayType
    val subWShift: Int = if (chromaArrayType == 1 || chromaArrayType == 2) 1 else 0
    val subHShift: Int = if (chromaArrayType == 1) 1 else 0
    private val ctbLog2 = sps.ctbLog2
    private val ctbSize = 1 shl ctbLog2
    private val widthInCtbs = sps.picWidthInCtbs
    private val sizeInCtbs = sps.picSizeInCtbs
    private val minTbLog2 = sps.minTbLog2
    private val minCbLog2 = sps.minCbLog2

    /**
     * The decoded picture before the conformance window: luma, then Cb and Cr unless 4:0:0. Samples
     * are unsigned, so one of 16 bits sits in its Short as a negative value, and every read masks it
     * with 0xFFFF.
     */
    val planes: Array<ShortArray>
    val strides: IntArray

    init {
        val cw = width shr subWShift
        val ch = height shr subHShift
        planes = if (chromaArrayType == 0) arrayOf(ShortArray(width * height)) else arrayOf(ShortArray(width * height), ShortArray(cw * ch), ShortArray(cw * ch))
        strides = if (chromaArrayType == 0) intArrayOf(width) else intArrayOf(width, cw, cw)
    }

    // The picture's state in 4x4 units: width and height are multiples of MinCbSizeY, so of 8.
    private val w4 = width shr 2
    private val ctDepth = ByteArray(w4 * (height shr 2))
    /** IntraPredModeY, and INTRA_DC for a PCM block, which 8.4.2 reads so. */
    private val predModeY = ByteArray(w4 * (height shr 2))
    /** QpY of the coding unit, which 8.6.1 predicts from and the deblocking filter reads. */
    val qpY: ByteArray = ByteArray(w4 * (height shr 2))
    /** The FLAG_ bits of each unit. */
    val flags: ByteArray = ByteArray(w4 * (height shr 2))
    /** palette_mode_flag of the coding unit of each unit, whose samples the deblocking filter keeps (8.7.2.5.7). */
    val paletteUnits: BooleanArray? = if (sps.paletteMode) BooleanArray(w4 * (height shr 2)) else null

    /** The tiles and the tile scan of 6.5.1. */
    val layout: CtbLayout = CtbLayout(sps, pps)

    /** TileId of each CTB in raster scan. */
    val tileIdRs: IntArray = IntArray(sizeInCtbs) { layout.tileId[layout.ctbAddrRsToTs[it]] }

    /** SliceAddrRs of the slice that holds each CTB, or -1 before it is decoded. */
    val ctbSlice: IntArray = IntArray(sizeInCtbs) { -1 }

    /** The slice segment header of each CTB, whose flags and offsets the loop filters read. */
    val ctbHeader: Array<SliceHeader?> = arrayOfNulls(sizeInCtbs)

    /** The SAO parameters of each CTB (7.4.9.3): per component its type, class or band and four offsets. */
    val sao: IntArray? = if (sps.saoEnabled) IntArray(sizeInCtbs * 18) else null

    /** MinTbAddrZs within a CTB (6-10), in minimum transform blocks: the z-scan order 6.4.1 compares. */
    private val zShift = ctbLog2 - minTbLog2
    private val zScan = IntArray(1 shl (2 * zShift)) { i ->
        val x = i and ((1 shl zShift) - 1)
        val y = i shr zShift
        var z = 0
        for (b in 0 until zShift) z = z or (((x shr b) and 1) shl (2 * b)) or (((y shr b) and 1) shl (2 * b + 1))
        z
    }

    private val cabac = Cabac()
    /** Palette mode, with the predictor palette that runs through the slice. */
    private val palette: PaletteDecoder? = if (sps.paletteMode) PaletteDecoder(this, cabac) else null
    /** TwoVersionsOfCurrDecPicFlag, 7-40. */
    private val twoVersions = pps.twoVersionsOfCurrDecPic(sps)
    /**
     * A picture that refers to itself predicts from its samples before the loop filters (8.1.3), anywhere above
     * or left of the block, so its loop filters wait for the whole picture.
     */
    private val deferLoopFilters = pps.currPicRefEnabled
    private val act = pps.residualAdaptiveColourTransform
    /** tu_residual_act_flag of the transform unit being decoded. */
    private var tuAct = false
    /** The residuals of the three components of a transform unit under the adaptive colour transform (8.6.8). */
    private val actResidual = if (act) Array(3) { IntArray(32 * 32) } else emptyArray()

    // The range extension tools (7.4.3.2.2, 7.4.3.3.2), read once per picture so the common path tests a field.
    private val extendedPrecision = sps.extendedPrecision
    /** CoeffMaxY and CoeffMaxC of 7-29 and 7-30; CoeffMin is -CoeffMax - 1. */
    private val coeffMaxY = (1 shl (if (extendedPrecision) maxOf(15, sps.bitDepthY + 6) else 15)) - 1
    private val coeffMaxC = (1 shl (if (extendedPrecision) maxOf(15, sps.bitDepthC + 6) else 15)) - 1
    /** The smallest log2 size whose transform sums need 64 bits, per component kind: luma, chroma. */
    private val wideLog2Y = (2..5).firstOrNull { !InverseTransform.fitsInt(it, coeffMaxY) } ?: 6
    private val wideLog2C = (2..5).firstOrNull { !InverseTransform.fitsInt(it, coeffMaxC) } ?: 6
    /** log2TransformRange of 8-300 and 8-304. */
    private val log2RangeY = if (extendedPrecision) maxOf(15, sps.bitDepthY + 6) else 15
    private val log2RangeC = if (extendedPrecision) maxOf(15, sps.bitDepthC + 6) else 15
    private val rotation = sps.transformSkipRotation
    private val skipContext = sps.transformSkipContext
    private val implicitRdpcm = sps.implicitRdpcm
    private val persistentRice = sps.persistentRiceAdaptation
    private val bypassAlignment = sps.cabacBypassAlignment
    private val crossComponent = pps.crossComponentPrediction
    /** Log2MinCuChromaQpOffsetSize, 7-39. */
    private val log2MinCuChromaQpOffsetSize = ctbLog2 - pps.diffCuChromaQpOffsetDepth
    private val wppStates = ByteArray(Ctx.COUNT)
    private val dsStates = ByteArray(Ctx.COUNT)
    private val tiles = pps.tilesEnabled
    private val wpp = pps.entropyCodingSync
    private val heightInCtbs = sps.picHeightInCtbs
    /** Intra prediction into [planes], which an encoder's reconstruction runs too. */
    internal val intra: IntraPredictor = IntraPredictor(this)
    /** The motion of the picture, made by its first P or B slice segment. */
    var motion: MotionField? = null
        private set
    private var motionPredictor: MotionPredictor? = null
    private var interPredictor: InterPredictor? = null
    /** initType of 9.3.2.2 for the slice segment being decoded. */
    private var initType = 0
    private val transform = InverseTransform()
    private val coeff = IntArray(32 * 32)
    private val residual = IntArray(32 * 32)
    /** The luma residual of the current transform unit, which cross-component prediction adds to chroma (8.6.6). */
    private val lumaResidual = IntArray(if (pps.crossComponentPrediction) 32 * 32 else 0)
    private val subFlags = IntArray(64)
    private val positions = IntArray(16)

    /** ScalingFactor (7-44 to 7-51) at sizeId * 6 + matrixId, raster order, or null without scaling lists. */
    private val scalingFactor: Array<IntArray>? = sps.scalingLists?.let { spsLists ->
        val lists = pps.scalingLists ?: spsLists
        Array(24) { k ->
            val sizeId = k / 6
            val n = 4 shl sizeId
            val list = lists.lists[k]
            val scan = HevcScan.order(if (sizeId == 0) 2 else 3, HevcScan.DIAGONAL)
            val rep = if (sizeId == 0) 1 else n / 8
            val f = IntArray(n * n)
            for (i in list.indices) {
                val x = (scan[i] and 0xFF) * rep
                val y = (scan[i] shr 8) * rep
                for (j in 0 until rep) for (kk in 0 until rep) f[(y + j) * n + x + kk] = list[i]
            }
            if (sizeId >= 2) f[0] = lists.dc[k]
            f
        }
    }

    // The slice segment being decoded.
    private lateinit var slice: SliceHeader
    private var sliceAddrRs = 0
    private var sliceQpY = 0
    private var ctbAddr = 0
    private var ctbTs = 0
    /** CtbAddrInTs after the last CTB of the previous slice segment, where a dependent one starts. */
    private var nextCtbTs = 0
    private var stopBit = 0
    private lateinit var rbsp: ByteArray
    private var substreamEnd = 0

    // The coding unit and quantization group being decoded.
    private var cuBypass = false
    /** CuPredMode is MODE_INTER (or MODE_SKIP). */
    private var cuInter = false
    private var partMode = PartMode.PART_2Nx2N
    private var intraSplit = false
    private var maxTrafoDepth = 0
    private var xCu = 0
    private var yCu = 0
    private var log2Cu = 0
    private val chromaMode = IntArray(4)
    /** intra_chroma_pred_mode of each prediction block, whose value 4 lets cross_comp_pred( ) be coded. */
    private val chromaSyntax = IntArray(4)
    /** ResScaleVal of 7-80 for Cb and Cr of the current transform unit. */
    private var resScaleCb = 0
    private var resScaleCr = 0
    private var isCuChromaQpOffsetCoded = false
    private var cuQpOffsetCb = 0
    private var cuQpOffsetCr = 0
    private var isCuQpDeltaCoded = false
    private var cuQpDeltaVal = 0
    private var qpYPred = 0
    private var lastQpY = 0
    private var cuQpY = 0
    private val log2MinCuQpDeltaSize = ctbLog2 - pps.diffCuQpDeltaDepth

    /** CTBs decoded so far, so the caller can tell a picture whose slices miss some. */
    var decodedCtbs: Int = 0
        private set

    /** Counts of the inter coding tools the picture uses, at the indices of [HevcStats], for tests; null to count nothing. */
    var stats: IntArray? = null

    /**
     * Decodes the slice segment [h], whose RBSP is [rbsp] with the emulation prevention bytes removed
     * from the escaped positions [escapes], in tile scan (7.3.8.1). [cancel] runs once per CTB row of a tile.
     */
    fun decodeSliceSegment(h: SliceHeader, rbsp: ByteArray, escapes: IntArray, cancel: (() -> Unit)?) {
        slice = h
        // 9.3.2.2, Table 9-4.
        initType = when (h.sliceType) {
            SliceType.I -> 0
            SliceType.P -> if (h.cabacInit) 2 else 1
            else -> if (h.cabacInit) 1 else 2
        }
        if (h.sliceType != SliceType.I) {
            val refs = h.refs ?: hevcError("a P or B slice has no reference picture lists")
            val m = motion ?: MotionField(width, height).also { motion = it }
            val mp = motionPredictor ?: MotionPredictor(this).also { motionPredictor = it }
            if (interPredictor == null) interPredictor = InterPredictor(this)
            mp.startSlice(h, refs, m)
        }
        this.rbsp = rbsp
        sliceAddrRs = h.sliceAddrRs
        sliceQpY = h.sliceQpY
        stopBit = BitReader(rbsp).stopBit
        // 7.4.7.1: each slice segment starts with CuQpOffsetCb and CuQpOffsetCr at 0.
        cuQpOffsetCb = 0
        cuQpOffsetCr = 0
        ctbAddr = h.sliceSegmentAddress
        if (ctbSlice[ctbAddr] != -1) hevcError("slice_segment_address $ctbAddr names a CTB already decoded")
        ctbTs = layout.ctbAddrRsToTs[ctbAddr]
        // 9.3.2.1: a dependent slice segment takes the contexts that the segment just before it stored.
        if (h.dependentSliceSegment && ctbTs != nextCtbTs) hevcError("the dependent slice segment at slice_segment_address $ctbAddr does not follow the one before it")
        val starts = substreamStarts(h, escapes)
        var substream = 0
        substreamEnd = if (starts.size > 1) starts[1] else rbsp.size
        cabac.start(rbsp, starts[0], substreamEnd)
        startContexts(h.dependentSliceSegment)
        while (true) {
            val x = ctbAddr % widthInCtbs
            if (cancel != null && (x == 0 || tileIdRs[ctbAddr] != tileIdRs[ctbAddr - 1])) cancel()
            ctbSlice[ctbAddr] = sliceAddrRs
            ctbHeader[ctbAddr] = h
            codingTreeUnit()
            decodedCtbs++
            // 9.3.1: under WPP the contexts after the second CTB of a row of a tile seed the row below, and so
            // does the predictor palette (9.3.2.4).
            if (wpp && (x == 1 || (ctbAddr > 1 && tileIdRs[ctbAddr] != tileIdRs[ctbAddr - 2]))) {
                cabac.states.copyInto(wppStates)
                palette?.store(PaletteDecoder.WPP)
            }
            val end = cabac.terminate()
            ctbTs++
            ctbDecoded(ctbAddr)
            if (end == 1) {
                // 9.3.4.3.5: the last bit read is rbsp_stop_one_bit, so the data must end there.
                if (cabac.bitPosition - 1 != stopBit) hevcError("end_of_slice_segment_flag is 1 before the end of the slice segment data")
                if (substream != starts.size - 1) {
                    hevcError("num_entry_point_offsets ${starts.size - 1} but the slice segment has ${substream + 1} subsets")
                }
                // 9.3.2.4: kept for a dependent slice segment that may follow.
                if (pps.dependentSliceSegmentsEnabled) {
                    cabac.states.copyInto(dsStates)
                    palette?.store(PaletteDecoder.DEPENDENT)
                }
                nextCtbTs = ctbTs
                return
            }
            if (ctbTs >= sizeInCtbs) hevcError("the slice segment passes the last CTB of the picture")
            ctbAddr = layout.ctbAddrTsToRs[ctbTs]
            if (ctbSlice[ctbAddr] != -1) hevcError("the slice segment runs into a CTB already decoded")
            // 7.3.8.1: a subset ends with each tile, and under WPP with each CTB row of a tile.
            val newTile = tiles && layout.tileId[ctbTs] != layout.tileId[ctbTs - 1]
            if (newTile || (wpp && rowStartInTile())) {
                if (cabac.terminate() != 1) hevcError("end_of_subset_one_bit is 0")
                substream++
                if (substream >= starts.size) hevcError("num_entry_point_offsets ${starts.size - 1} is fewer than the subsets of the slice segment")
                val next = cabac.alignedByte
                // 9.3.4.3.5: the last bit read is alignment_bit_equal_to_one, then zero bits to the byte.
                val one = cabac.bitPosition - 1
                if (next > substreamEnd) hevcError("the subset before CTB $ctbAddr is cut off")
                if ((rbsp[one shr 3].toInt() shr (7 - (one and 7))) and 1 != 1) hevcError("alignment_bit_equal_to_one is 0")
                checkZeroBits(cabac.bitPosition, next * 8, "alignment_bit_equal_to_zero")
                if (next != starts[substream]) hevcError("entry_point_offset_minus1[ ${substream - 1} ] does not match the slice segment data")
                substreamEnd = if (substream + 1 < starts.size) starts[substream + 1] else rbsp.size
                cabac.start(rbsp, next, substreamEnd)
                startContexts(false)
            }
        }
    }

    /** The current CTB is the first of its CTB row within its tile. */
    private fun rowStartInTile(): Boolean = ctbAddr % widthInCtbs == 0 || tileIdRs[ctbAddr] != tileIdRs[ctbAddr - 1]

    /** The RBSP byte at which each subset of the slice segment data starts (7.4.7.1, 7-55). */
    private fun substreamStarts(h: SliceHeader, escapes: IntArray): IntArray {
        val start = h.headerBits shr 3
        val offsets = h.entryPointOffsets
        val out = IntArray(offsets.size + 1)
        out[0] = start
        if (offsets.isEmpty()) return out
        // The offsets count emulation prevention bytes, which the RBSP has dropped.
        var escStart = start
        for (e in escapes) if (e <= escStart) escStart++ else break
        var esc = escStart.toLong()
        for (k in offsets.indices) {
            esc += offsets[k]
            var removed = 0
            for (e in escapes) if (e < esc) removed++ else break
            val r = esc - removed
            if (r >= rbsp.size) hevcError("entry_point_offset_minus1[ $k ] passes the end of the slice segment data")
            out[k + 1] = r.toInt()
        }
        return out
    }

    /** Checks that the bits from [from] up to [to] of the RBSP are 0, as the syntax element [name] requires. */
    private fun checkZeroBits(from: Int, to: Int, name: String) {
        for (b in from until to) {
            val byte = b shr 3
            if (byte >= rbsp.size) hevcError("$name is cut off")
            if ((rbsp[byte].toInt() shr (7 - (b and 7))) and 1 != 0) hevcError("$name is 1")
        }
    }

    /**
     * Initialises the contexts at the start of a slice segment or of a subset (9.3.2.1): afresh at a
     * tile; under WPP, at a CTB row of a tile, from the row above when its CTB above right is available;
     * in a [dependent] slice segment, from the segment before it.
     */
    private fun startContexts(dependent: Boolean) {
        val firstInTile = ctbTs == 0 || layout.tileId[ctbTs] != layout.tileId[ctbTs - 1]
        val rowStart = wpp && rowStartInTile()
        // 9.3.2.1: the predictor palette starts, or is taken over, wherever the contexts are.
        if (firstInTile) {
            Ctx.init(cabac.states, initType, sliceQpY)
            palette?.initialise()
        } else if (rowStart) {
            val above = ctbAddr - widthInCtbs + 1
            // (xNbT, yNbT) of 9-3, available by 6.4.1: inside the picture, decoded, in the slice and the tile.
            val x = ctbAddr % widthInCtbs
            if (ctbAddr >= widthInCtbs && x + 1 < widthInCtbs && ctbSlice[above] == sliceAddrRs && tileIdRs[above] == tileIdRs[ctbAddr]) {
                wppStates.copyInto(cabac.states)
                palette?.restore(PaletteDecoder.WPP)
            } else {
                Ctx.init(cabac.states, initType, sliceQpY)
                palette?.initialise()
            }
        } else if (dependent) {
            dsStates.copyInto(cabac.states)
            palette?.restore(PaletteDecoder.DEPENDENT)
        } else {
            Ctx.init(cabac.states, initType, sliceQpY)
            palette?.initialise()
        }
        // 8.6.1: qPY_PREV is SliceQpY in the first quantization group of a slice, a tile, or a CTB row of a tile under WPP.
        if (firstInTile || rowStart || !dependent) lastQpY = sliceQpY
    }

    /**
     * Counts the decoded CTB at [rs], and runs the loop filters over the CTB rows that no later CTB
     * reads before deblocking: a row is deblocked once the row below it is decoded, as intra prediction
     * reads the last line of the row above (8.4.4.2.1), and given SAO once the row below is deblocked.
     */
    private fun ctbDecoded(rs: Int) {
        if (++rowCtbs[rs / widthInCtbs] != widthInCtbs) return
        while (completeRows < heightInCtbs && rowCtbs[completeRows] == widthInCtbs) completeRows++
        if (!deferLoopFilters) runLoopFilters(completeRows - 1)
    }

    private val rowCtbs = IntArray(heightInCtbs)
    private var completeRows = 0
    private var deblockedRows = 0
    private var saoRows = 0
    private val deblocker = Deblocker(this)
    private val saoFilter: SaoFilter? = if (sps.saoEnabled) SaoFilter(this) else null

    /**
     * Deblocks the CTB rows before [rows], then applies SAO to every row whose neighbours below are deblocked.
     * An encoder passes [beforeSao], which sees each row deblocked just before SAO changes it, to choose the
     * row's SAO parameters there.
     */
    internal fun runLoopFilters(rows: Int, beforeSao: ((Int) -> Unit)? = null) {
        while (deblockedRows < rows) deblocker.filterRow(deblockedRows++)
        val last = if (deblockedRows == heightInCtbs) heightInCtbs else deblockedRows - 1
        while (saoRows < last) {
            beforeSao?.invoke(saoRows)
            saoFilter?.filterRow(saoRows)
            saoRows++
        }
    }

    // The entry points of an encoder (HevcEncoder), which reconstructs its picture in a PictureDecoder so that
    // prediction, the residual and the loop filters are the decoder's own. It codes one slice of I slice segments.

    /** Starts the reconstruction of the slice [h]. */
    internal fun beginEncoding(h: SliceHeader) {
        slice = h
        sliceAddrRs = h.sliceAddrRs
        sliceQpY = h.sliceQpY
    }

    /**
     * Starts the reconstruction of the P or B slice [h], whose reference picture lists are built: the picture's
     * motion field, merging and AMVP ([encoderMotion]) and inter prediction ([predictEncoded]), as
     * [decodeSliceSegment] makes them.
     */
    internal fun beginInterEncoding(h: SliceHeader) {
        beginEncoding(h)
        val refs = h.refs ?: error("a P or B slice has no reference picture lists")
        val m = motion ?: MotionField(width, height).also { motion = it }
        val mp = motionPredictor ?: MotionPredictor(this).also { motionPredictor = it }
        if (interPredictor == null) interPredictor = InterPredictor(this)
        mp.startSlice(h, refs, m)
    }

    /** The merging candidates and motion vector predictors of the slice being encoded. */
    internal val encoderMotion: MotionPredictor get() = motionPredictor ?: error("the slice is not a P or B slice")

    /** The inter prediction of the slice being encoded, whose interpolation the motion search runs. */
    internal val encoderInter: InterPredictor get() = interPredictor ?: error("the slice is not a P or B slice")

    /** Predicts the block at ([xPb], [yPb]) of [w] by [h] into the picture, as [predictionUnit] does (8.5.3.3). */
    internal fun predictEncoded(xPb: Int, yPb: Int, w: Int, h: Int, r0: Int, m0: Int, r1: Int, m1: Int) {
        interPredictor!!.predict(slice, slice.refs!!, xPb, yPb, w, h, r0, m0, r1, m1)
    }

    /**
     * Marks the coding unit of [size] at ([x0], [y0]) as intra or inter, with cu_skip_flag [skip], while the
     * encoder weighs it: merging and AMVP read the kind of a neighbour (6.4.2), and cu_skip_flag's context the
     * neighbour's flag. An intra unit leaves no motion for later pictures, as a decoder keeps none of it.
     */
    internal fun markEncodedKind(x0: Int, y0: Int, size: Int, intra: Boolean, skip: Boolean) {
        val m = motion ?: return
        for (y in (y0 shr 2) until ((y0 + size) shr 2)) {
            val row = y * w4
            for (x in (x0 shr 2) until ((x0 + size) shr 2)) {
                val f = flags[row + x].toInt() and 0xFF
                flags[row + x] = (if (intra) f or FLAG_INTRA else f and FLAG_INTRA.inv()).toByte()
            }
        }
        fillUnits(m.skip, x0, y0, size, size, if (skip) 1 else 0)
        if (intra) {
            val c = m.collocated
            var y = (y0 + 15) and 15.inv()
            while (y < y0 + size) {
                var x = (x0 + 15) and 15.inv()
                while (x < x0 + size) {
                    c.flags[(y shr 4) * c.width16 + (x shr 4)] = 0
                    x += 16
                }
                y += 16
            }
        }
    }

    /**
     * Records the inter coding unit of 1 shl [log2] samples at ([x0], [y0]) as the decoder records one it parses:
     * its depth, bypass flag, QpY [qp], its edges and the edge between its prediction blocks of [partMode], and
     * cu_skip_flag [skip]. Its motion is stored through [encoderMotion].
     */
    internal fun markEncodedInterUnit(x0: Int, y0: Int, log2: Int, depth: Int, bypass: Boolean, qp: Int, partMode: Int, skip: Boolean) {
        val size = 1 shl log2
        val unitFlags = if (bypass) FLAG_BYPASS else 0
        for (y in (y0 shr 2) until ((y0 + size) shr 2)) {
            val row = y * w4
            for (x in (x0 shr 2) until ((x0 + size) shr 2)) {
                ctDepth[row + x] = depth.toByte()
                flags[row + x] = unitFlags.toByte()
            }
        }
        fillUnits(motion!!.skip, x0, y0, size, size, if (skip) 1 else 0)
        val half = size shr 1
        val quarter = size shr 2
        when (partMode) {
            PartMode.PART_2NxN -> markPredictionEdge(x0, y0 + half, size, false)
            PartMode.PART_Nx2N -> markPredictionEdge(x0 + half, y0, size, true)
            PartMode.PART_2NxnU -> markPredictionEdge(x0, y0 + quarter, size, false)
            PartMode.PART_2NxnD -> markPredictionEdge(x0, y0 + size - quarter, size, false)
            PartMode.PART_nLx2N -> markPredictionEdge(x0 + quarter, y0, size, true)
            PartMode.PART_nRx2N -> markPredictionEdge(x0 + size - quarter, y0, size, true)
        }
        markEdges(x0, y0, size)
        fillUnits(qpY, x0, y0, size, size, qp)
    }

    /** Marks the CTB [rs] as being coded in the slice, as the decoder does before it parses the CTB. */
    internal fun beginEncodedCtb(rs: Int) {
        ctbAddr = rs
        ctbSlice[rs] = sliceAddrRs
        ctbHeader[rs] = slice
        decodedCtbs++
    }

    /**
     * Records the coding unit of 1 shl [log2] samples at ([x0], [y0]) as the decoder records an intra unit
     * it parses: its depth [depth] in the coding quadtree, its bypass flag, its QpY [qp] and its edges.
     */
    internal fun markEncodedUnit(x0: Int, y0: Int, log2: Int, depth: Int, bypass: Boolean, qp: Int) {
        val size = 1 shl log2
        val unitFlags = FLAG_INTRA or (if (bypass) FLAG_BYPASS else 0)
        for (y in (y0 shr 2) until ((y0 + size) shr 2)) {
            val row = y * w4
            for (x in (x0 shr 2) until ((x0 + size) shr 2)) {
                ctDepth[row + x] = depth.toByte()
                flags[row + x] = unitFlags.toByte()
            }
        }
        // In a P or B slice, cu_skip_flag 0, and no motion for later pictures.
        if (motion != null) markEncodedKind(x0, y0, size, intra = true, skip = false)
        markEdges(x0, y0, size)
        fillUnits(qpY, x0, y0, size, size, qp)
    }

    /** Records a luma transform block as [transformUnit] does: its edges, and FLAG_CODED when [coded]. */
    internal fun markEncodedTransform(x0: Int, y0: Int, log2: Int, coded: Boolean) {
        markEdges(x0, y0, 1 shl log2)
        if (!coded) return
        val n = 1 shl (log2 - 2)
        for (y in 0 until n) for (x in 0 until n) {
            val u = ((y0 shr 2) + y) * w4 + (x0 shr 2) + x
            flags[u] = (flags[u].toInt() or FLAG_CODED).toByte()
        }
    }

    /** The motion that later pictures take temporal candidates from, or null for a picture of I slices only. */
    fun collocatedMotion(): CollocatedMotion? = motion?.collocated

    /** Runs the loop filters over the rows still owed, once every CTB of the picture is decoded (8.7.1). */
    fun finishLoopFilters() {
        runLoopFilters(heightInCtbs)
    }

    /**
     * availableN of 6.4.1 for the block at ([xCurr], [yCurr]) and its neighbour at ([xN], [yN]). Within
     * a CTB, z-scan order decides.
     */
    fun available(xCurr: Int, yCurr: Int, xN: Int, yN: Int): Boolean {
        if (xN < 0 || yN < 0 || xN >= width || yN >= height) return false
        val ctbN = (yN shr ctbLog2) * widthInCtbs + (xN shr ctbLog2)
        val ctbC = (yCurr shr ctbLog2) * widthInCtbs + (xCurr shr ctbLog2)
        // Another CTB is decoded before the current one if it is in the slice at all, and must share its tile.
        if (ctbN != ctbC) return ctbSlice[ctbN] == sliceAddrRs && tileIdRs[ctbN] == tileIdRs[ctbC]
        val m = ctbSize - 1
        val zN = zScan[(((yN and m) shr minTbLog2) shl zShift) + ((xN and m) shr minTbLog2)]
        val zC = zScan[(((yCurr and m) shr minTbLog2) shl zShift) + ((xCurr and m) shr minTbLog2)]
        return zN <= zC
    }

    /** A neighbouring sample is available for intra prediction (8.4.4.2.1), constrained_intra_pred_flag included. */
    fun intraAvailable(xCurr: Int, yCurr: Int, xN: Int, yN: Int): Boolean {
        if (!available(xCurr, yCurr, xN, yN)) return false
        return !pps.constrainedIntraPred || flags[(yN shr 2) * w4 + (xN shr 2)].toInt() and FLAG_INTRA != 0
    }

    private fun codingTreeUnit() {
        val x0 = (ctbAddr % widthInCtbs) shl ctbLog2
        val y0 = (ctbAddr / widthInCtbs) shl ctbLog2
        if (slice.saoLuma || slice.saoChroma) parseSao(ctbAddr % widthInCtbs, ctbAddr / widthInCtbs)
        codingQuadtree(x0, y0, ctbLog2, 0)
    }

    /** sao( rx, ry ), 7.3.8.3, kept per CTB for the loop filter stage. */
    private fun parseSao(rx: Int, ry: Int) {
        val params = sao ?: hevcError("slice_sao_luma_flag is 1 where sample_adaptive_offset_enabled_flag is 0")
        var mergeLeft = 0
        var mergeUp = 0
        if (rx > 0 && ctbAddr > sliceAddrRs && tileIdRs[ctbAddr - 1] == tileIdRs[ctbAddr]) mergeLeft = cabac.decode(Ctx.SAO_MERGE)
        val up = ctbAddr - widthInCtbs
        if (ry > 0 && mergeLeft == 0 && up >= sliceAddrRs && tileIdRs[up] == tileIdRs[ctbAddr]) mergeUp = cabac.decode(Ctx.SAO_MERGE)
        val at = ctbAddr * 18
        if (mergeLeft == 1 || mergeUp == 1) {
            val from = (if (mergeLeft == 1) ctbAddr - 1 else ctbAddr - widthInCtbs) * 18
            params.copyInto(params, at, from, from + 18)
            return
        }
        params.fill(0, at, at + 18)
        for (cIdx in 0 until (if (chromaArrayType != 0) 3 else 1)) {
            if (!((slice.saoLuma && cIdx == 0) || (slice.saoChroma && cIdx > 0))) continue
            val o = at + cIdx * 6
            // 7.4.9.3: Cr takes the type and edge class of Cb.
            val type = if (cIdx == 2) params[o - 6] else if (cabac.decode(Ctx.SAO_TYPE) == 0) 0 else if (cabac.bypass() == 0) 1 else 2
            params[o] = type
            if (type == 0) continue
            val bitDepth = if (cIdx == 0) sps.bitDepthY else sps.bitDepthC
            val cMax = (1 shl (minOf(bitDepth, 10) - 5)) - 1
            for (i in 0 until 4) {
                var v = 0
                while (v < cMax && cabac.bypass() == 1) v++
                params[o + 2 + i] = v
            }
            if (type == 1) {
                for (i in 0 until 4) if (params[o + 2 + i] != 0 && cabac.bypass() == 1) params[o + 2 + i] = -params[o + 2 + i]
                params[o + 1] = cabac.bypassBits(5)
            } else {
                // 7.4.9.3: edge offsets are positive for the first two categories and negative for the others.
                params[o + 4] = -params[o + 4]
                params[o + 5] = -params[o + 5]
                params[o + 1] = if (cIdx == 2) params[o - 6 + 1] else cabac.bypassBits(2)
            }
        }
    }

    private fun codingQuadtree(x0: Int, y0: Int, log2: Int, depth: Int) {
        val size = 1 shl log2
        val split = if (x0 + size <= width && y0 + size <= height && log2 > minCbLog2) {
            // 9.3.4.2.2 with Table 9-49.
            var inc = 0
            if (available(x0, y0, x0 - 1, y0) && ctDepth[(y0 shr 2) * w4 + ((x0 - 1) shr 2)] > depth) inc++
            if (available(x0, y0, x0, y0 - 1) && ctDepth[((y0 - 1) shr 2) * w4 + (x0 shr 2)] > depth) inc++
            cabac.decode(Ctx.SPLIT_CU + inc) == 1
        } else {
            log2 > minCbLog2
        }
        if (log2 >= log2MinCuQpDeltaSize) startQuantizationGroup(x0, y0)
        if (slice.cuChromaQpOffsetEnabled && log2 >= log2MinCuChromaQpOffsetSize) isCuChromaQpOffsetCoded = false
        if (split) {
            val half = size shr 1
            codingQuadtree(x0, y0, log2 - 1, depth + 1)
            if (x0 + half < width) codingQuadtree(x0 + half, y0, log2 - 1, depth + 1)
            if (y0 + half < height) codingQuadtree(x0, y0 + half, log2 - 1, depth + 1)
            if (x0 + half < width && y0 + half < height) codingQuadtree(x0 + half, y0 + half, log2 - 1, depth + 1)
        } else {
            codingUnit(x0, y0, log2, depth)
        }
    }

    /** The start of a quantization group: IsCuQpDeltaCoded and CuQpDeltaVal reset, and qPY_PRED of 8.6.1. */
    private fun startQuantizationGroup(xQg: Int, yQg: Int) {
        isCuQpDeltaCoded = false
        cuQpDeltaVal = 0
        val prev = lastQpY
        // qPY_A and qPY_B come from the left and upper groups only inside the current CTB, which is
        // decoded there before the group.
        val m = ctbSize - 1
        val a = if (xQg and m != 0) qpY[(yQg shr 2) * w4 + ((xQg - 1) shr 2)].toInt() else prev
        val b = if (yQg and m != 0) qpY[((yQg - 1) shr 2) * w4 + (xQg shr 2)].toInt() else prev
        qpYPred = (a + b + 1) shr 1
    }

    /** QpY of 8-283 for the current CuQpDeltaVal. */
    private fun currentQpY(): Int {
        val off = sps.qpBdOffsetY
        return ((qpYPred + cuQpDeltaVal + 52 + 2 * off) % (52 + off)) - off
    }

    private fun codingUnit(x0: Int, y0: Int, log2: Int, depth: Int) {
        xCu = x0
        yCu = y0
        log2Cu = log2
        cuBypass = pps.transquantBypassEnabled && cabac.decode(Ctx.TRANSQUANT_BYPASS) == 1
        val size = 1 shl log2
        var skip = false
        val m = motion
        if (m != null && slice.sliceType != SliceType.I) {
            // 9.3.4.2.2 with Table 9-49.
            var inc = 0
            if (available(x0, y0, x0 - 1, y0) && m.skip[(y0 shr 2) * w4 + ((x0 - 1) shr 2)].toInt() != 0) inc++
            if (available(x0, y0, x0, y0 - 1) && m.skip[((y0 - 1) shr 2) * w4 + (x0 shr 2)].toInt() != 0) inc++
            skip = cabac.decode(Ctx.CU_SKIP + inc) == 1
            fillUnits(m.skip, x0, y0, size, size, if (skip) 1 else 0)
        }
        // pred_mode_flag 1 is MODE_INTRA; an I slice codes every unit in intra mode.
        val isIntra = !skip && (slice.sliceType == SliceType.I || cabac.decode(Ctx.PRED_MODE) == 1)
        stats?.let { s ->
            if (slice.sliceType != SliceType.I) s[if (skip) HevcStats.SKIP else if (isIntra) HevcStats.INTRA_IN_INTER else HevcStats.INTER]++
        }
        cuInter = !isIntra
        intraSplit = false
        partMode = PartMode.PART_2Nx2N
        if (isIntra && palette != null && log2 <= sps.maxTbLog2 && cabac.decode(Ctx.PALETTE_MODE) == 1) {
            paletteUnit(x0, y0, log2, depth)
            return
        }
        if (isIntra) {
            // part_mode of an intra unit is coded only at the smallest size: 1 is PART_2Nx2N, 0 PART_NxN.
            intraSplit = log2 == minCbLog2 && cabac.decode(Ctx.PART_MODE) == 0
            if (intraSplit) partMode = PartMode.PART_NxN
        } else if (!skip) {
            partMode = interPartMode(log2)
            stats?.let { it[HevcStats.PART_2Nx2N + partMode]++ }
        }
        cuQpY = currentQpY()
        val pcm = isIntra && !intraSplit && sps.pcmEnabled && log2 >= sps.log2MinPcmCb && log2 <= sps.log2MaxPcmCb && cabac.terminate() == 1
        val unitFlags = (if (isIntra) FLAG_INTRA else 0) or (if (pcm) FLAG_PCM else 0) or (if (cuBypass) FLAG_BYPASS else 0)
        for (y in (y0 shr 2) until ((y0 + size) shr 2)) {
            val row = y * w4
            for (x in (x0 shr 2) until ((x0 + size) shr 2)) {
                ctDepth[row + x] = depth.toByte()
                flags[row + x] = unitFlags.toByte()
            }
        }
        if (pcm) {
            fillUnits(predModeY, x0, y0, size, size, IntraPredictor.INTRA_DC)
            pcmSample(x0, y0, log2)
        } else if (isIntra) {
            intraModes(x0, y0, log2)
            maxTrafoDepth = sps.maxTransformHierarchyDepthIntra + (if (intraSplit) 1 else 0)
            transformTree(x0, y0, x0, y0, log2, 0, 0, 0, 0)
        } else {
            val merged = predictionUnits(x0, y0, log2, depth, skip)
            // rqt_root_cbf, inferred 1 for a merged 2Nx2N unit that is not skipped (7.4.9.5).
            val rootCbf = !skip && ((partMode == PartMode.PART_2Nx2N && merged) || cabac.decode(Ctx.RQT_ROOT_CBF) == 1)
            if (rootCbf) {
                maxTrafoDepth = sps.maxTransformHierarchyDepthInter
                transformTree(x0, y0, x0, y0, log2, 0, 0, 0, 0)
            }
        }
        markEdges(x0, y0, size)
        fillUnits(qpY, x0, y0, size, size, cuQpY)
        lastQpY = cuQpY
    }

    /**
     * A coding unit in palette mode (7.3.8.13, 8.4.1): coded in intra mode, its luma intra prediction mode
     * taken as INTRA_DC by its neighbours as a PCM unit's is, as no mode is derived for it.
     */
    private fun paletteUnit(x0: Int, y0: Int, log2: Int, depth: Int) {
        val size = 1 shl log2
        cuQpY = currentQpY()
        // tu_residual_act_flag is inferred 0, so escape samples take the chroma QPs of 8-285 and 8-286.
        tuAct = false
        val unitFlags = FLAG_INTRA or (if (cuBypass) FLAG_BYPASS else 0)
        for (y in (y0 shr 2) until ((y0 + size) shr 2)) {
            val row = y * w4
            for (x in (x0 shr 2) until ((x0 + size) shr 2)) {
                ctDepth[row + x] = depth.toByte()
                flags[row + x] = unitFlags.toByte()
                paletteUnits!![row + x] = true
            }
        }
        fillUnits(predModeY, x0, y0, size, size, IntraPredictor.INTRA_DC)
        palette!!.codingUnit(x0, y0, log2, cuBypass)
        markEdges(x0, y0, size)
        fillUnits(qpY, x0, y0, size, size, cuQpY)
        lastQpY = cuQpY
    }

    /** delta_qp( ) and chroma_qp_offset( ) of a palette unit with escape samples (7.3.8.13). */
    internal fun paletteQp(bypass: Boolean) {
        deltaQp()
        if (!bypass) chromaQpOffset()
    }

    /** Qp′Y of the current coding unit (8-284). */
    internal val lumaQpPrime: Int get() = cuQpY + sps.qpBdOffsetY

    /** part_mode of an inter unit, with the binarization of Table 9-45 and the contexts of Table 9-48. */
    private fun interPartMode(log2: Int): Int {
        val c = cabac
        if (c.decode(Ctx.PART_MODE) == 1) return PartMode.PART_2Nx2N
        if (log2 == minCbLog2) {
            if (c.decode(Ctx.PART_MODE + 1) == 1) return PartMode.PART_2NxN
            if (log2 == 3) return PartMode.PART_Nx2N
            return if (c.decode(Ctx.PART_MODE + 2) == 1) PartMode.PART_Nx2N else PartMode.PART_NxN
        }
        val horizontal = c.decode(Ctx.PART_MODE + 1) == 1
        if (!sps.ampEnabled) return if (horizontal) PartMode.PART_2NxN else PartMode.PART_Nx2N
        // The third bin tells a symmetric split from an asymmetric one, whose side a bypass bin gives.
        if (c.decode(Ctx.PART_MODE + 3) == 1) return if (horizontal) PartMode.PART_2NxN else PartMode.PART_Nx2N
        val second = c.bypass() == 1
        return if (horizontal) (if (second) PartMode.PART_2NxnD else PartMode.PART_2NxnU) else (if (second) PartMode.PART_nRx2N else PartMode.PART_nLx2N)
    }

    /**
     * The prediction units of the inter coding unit at ([x0], [y0]) (7.3.8.5): each one's syntax, its
     * motion (8.5.3.2) and its samples (8.5.3.3), in order, as a later unit may predict its motion from an
     * earlier one. The edges between the units are marked for the deblocking filter (8.7.2.3). Returns
     * merge_flag of the first unit.
     */
    private fun predictionUnits(x0: Int, y0: Int, log2: Int, depth: Int, skip: Boolean): Boolean {
        val n = 1 shl log2
        val half = n shr 1
        val quarter = n shr 2
        var merged = false
        fun pu(dx: Int, dy: Int, w: Int, h: Int, idx: Int) {
            val m = predictionUnit(x0, y0, n, x0 + dx, y0 + dy, w, h, idx, depth, skip)
            if (idx == 0) merged = m
        }
        when (partMode) {
            PartMode.PART_2Nx2N -> pu(0, 0, n, n, 0)
            PartMode.PART_2NxN -> { pu(0, 0, n, half, 0); pu(0, half, n, half, 1); markPredictionEdge(x0, y0 + half, n, false) }
            PartMode.PART_Nx2N -> { pu(0, 0, half, n, 0); pu(half, 0, half, n, 1); markPredictionEdge(x0 + half, y0, n, true) }
            PartMode.PART_2NxnU -> { pu(0, 0, n, quarter, 0); pu(0, quarter, n, n - quarter, 1); markPredictionEdge(x0, y0 + quarter, n, false) }
            PartMode.PART_2NxnD -> { pu(0, 0, n, n - quarter, 0); pu(0, n - quarter, n, quarter, 1); markPredictionEdge(x0, y0 + n - quarter, n, false) }
            PartMode.PART_nLx2N -> { pu(0, 0, quarter, n, 0); pu(quarter, 0, n - quarter, n, 1); markPredictionEdge(x0 + quarter, y0, n, true) }
            PartMode.PART_nRx2N -> { pu(0, 0, n - quarter, n, 0); pu(n - quarter, 0, quarter, n, 1); markPredictionEdge(x0 + n - quarter, y0, n, true) }
            else -> {
                pu(0, 0, half, half, 0); pu(half, 0, half, half, 1); pu(0, half, half, half, 2); pu(half, half, half, half, 3)
                markPredictionEdge(x0 + half, y0, n, true)
                markPredictionEdge(x0, y0 + half, n, false)
            }
        }
        return merged
    }

    /** Marks a prediction block edge of [length] samples from ([x], [y]), vertical or horizontal, that is no transform edge. */
    private fun markPredictionEdge(x: Int, y: Int, length: Int, vertical: Boolean) {
        val at = (y shr 2) * w4 + (x shr 2)
        for (i in 0 until (length shr 2)) {
            val u = if (vertical) at + i * w4 else at + i
            flags[u] = (flags[u].toInt() or (if (vertical) FLAG_EDGE_LEFT else FLAG_EDGE_TOP)).toByte()
        }
    }

    /**
     * prediction_unit( ) of 7.3.8.6 for the block at ([xPb], [yPb]) of [w] by [h], partition [partIdx] of
     * the coding block at ([xCb], [yCb]) of [nCbS] and depth [depth]; then its motion and its samples.
     * Returns merge_flag.
     */
    private fun predictionUnit(xCb: Int, yCb: Int, nCbS: Int, xPb: Int, yPb: Int, w: Int, h: Int, partIdx: Int, depth: Int, skip: Boolean): Boolean {
        val c = cabac
        val mp = motionPredictor!!
        val refs = slice.refs!!
        val merge = skip || c.decode(Ctx.MERGE_FLAG) == 1
        var r0 = -1
        var r1 = -1
        var m0 = 0
        var m1 = 0
        if (merge) {
            // merge_idx: TR with cMax MaxNumMergeCand - 1, its first bin in a context.
            val max = slice.maxNumMergeCand - 1
            var idx = 0
            while (idx < max && (if (idx == 0) c.decode(Ctx.MERGE_IDX) else c.bypass()) == 1) idx++
            mp.merge(xCb, yCb, nCbS, xPb, yPb, w, h, partIdx, partMode, idx)
            r0 = mp.ref0; r1 = mp.ref1; m0 = mp.mv0; m1 = mp.mv1
        } else {
            // inter_pred_idc, 9.3.3.9: PRED_L0 0, PRED_L1 1, PRED_BI 2.
            var idc = 0
            if (slice.sliceType == SliceType.B) {
                idc = if (w + h != 12 && c.decode(Ctx.INTER_PRED_IDC + depth) == 1) 2 else c.decode(Ctx.INTER_PRED_IDC + 4)
            }
            var mvd0 = 0
            var mvd1 = 0
            var flag0 = 0
            var flag1 = 0
            if (idc != 1) {
                r0 = refIdx(slice.numRefIdxL0ActiveMinus1)
                mvd0 = mvdCoding()
                flag0 = c.decode(Ctx.MVP_FLAG)
            }
            if (idc != 0) {
                r1 = refIdx(slice.numRefIdxL1ActiveMinus1)
                mvd1 = if (slice.mvdL1Zero && idc == 2) 0 else mvdCoding()
                flag1 = c.decode(Ctx.MVP_FLAG)
            }
            if (r0 >= 0) m0 = addMvd(mp.predictor(0, r0, xCb, yCb, nCbS, xPb, yPb, w, h, partIdx, flag0), mvd0, refs.isCurrent[0][r0])
            if (r1 >= 0) m1 = addMvd(mp.predictor(1, r1, xCb, yCb, nCbS, xPb, yPb, w, h, partIdx, flag1), mvd1, refs.isCurrent[1][r1])
        }
        stats?.let { s ->
            s[if (merge) HevcStats.MERGE else HevcStats.AMVP]++
            if (r0 >= 0 && r1 >= 0) s[HevcStats.BI]++
        }
        // 8.5.3.2.1: under TwoVersionsOfCurrDecPicFlag, a bi-predicted block of 8x8 whose two vectors are both
        // fractional (8-102) and not the same motion (8-103) predicts from list 0 alone. The size is the
        // prediction block's own: nPbSw and nPbSh of 8-86 and 8-87 would also take in the 16x4 and 16x12 blocks
        // of a 16x16 AMP unit, and the conformance streams Bipred_8by8_A, MVRESIDC_A and HT_A then fail.
        if (twoVersions && r0 >= 0 && r1 >= 0 && w == 8 && h == 8 && !wholeSample(m0) && !wholeSample(m1)) {
            if (m0 != m1 || refs.poc(0, r0) != refs.poc(1, r1)) {
                r1 = -1
                m1 = 0
            }
        }
        mp.store(xPb, yPb, w, h, r0, m0, r1, m1)
        interPredictor!!.predict(slice, refs, xPb, yPb, w, h, r0, m0, r1, m1)
        return merge
    }

    /**
     * 8-94 to 8-101: mvpLX + mvdLX wrapped to 16 bits, or in whole samples under use_integer_mv_flag or for a
     * reference that is the current picture ([current]).
     */
    private fun addMvd(mvp: Int, mvd: Int, current: Boolean): Int {
        val px = MotionField.x(mvp)
        val py = MotionField.y(mvp)
        val dx = MotionField.x(mvd)
        val dy = MotionField.y(mvd)
        if (slice.useIntegerMv || current) return MotionField.pack(((px shr 2) + dx) shl 2, ((py shr 2) + dy) shl 2)
        return MotionField.pack(px + dx, py + dy)
    }

    /** noIntegerMvFlag of 8-102 is 0 for this vector: both its components are whole samples. */
    private fun wholeSample(mv: Int): Boolean = MotionField.x(mv) and 3 == 0 && MotionField.y(mv) and 3 == 0

    /** ref_idx_l0 or ref_idx_l1: TR with cMax [max], two bins in contexts and the rest bypass. */
    private fun refIdx(max: Int): Int {
        var i = 0
        while (i < max && (if (i < 2) cabac.decode(Ctx.REF_IDX + i) else cabac.bypass()) == 1) i++
        return i
    }

    /** mvd_coding( ), 7.3.8.9: the difference packed as a motion vector, each component in -2^15..2^15 - 1. */
    private fun mvdCoding(): Int {
        val c = cabac
        val g0x = c.decode(Ctx.ABS_MVD_GREATER0)
        val g0y = c.decode(Ctx.ABS_MVD_GREATER0)
        val g1x = if (g0x == 1) c.decode(Ctx.ABS_MVD_GREATER1) else 0
        val g1y = if (g0y == 1) c.decode(Ctx.ABS_MVD_GREATER1) else 0
        val x = mvdComponent(g0x, g1x)
        val y = mvdComponent(g0y, g1y)
        return MotionField.pack(x, y)
    }

    /** One component of mvd_coding( ): abs_mvd_minus2 as EG1 (9.3.3.3), then mvd_sign_flag, all bypass. */
    private fun mvdComponent(greater0: Int, greater1: Int): Int {
        if (greater0 == 0) return 0
        var abs = 1
        if (greater1 == 1) {
            var k = 1
            var v = 0
            while (cabac.bypass() == 1) {
                v += 1 shl k
                // A difference of 16 bits needs no longer prefix.
                if (++k > 16) hevcError("abs_mvd_minus2 is out of range")
            }
            abs = v + cabac.bypassBits(k) + 2
        }
        val negative = cabac.bypass() == 1
        // 7.4.9.9: MvdLX is in -2^15..2^15 - 1.
        if (abs > 32768 || (abs == 32768 && !negative)) hevcError("a motion vector difference of ${if (negative) -abs else abs} is out of range")
        return if (negative) -abs else abs
    }

    private fun fillUnits(a: ByteArray, x0: Int, y0: Int, w: Int, h: Int, v: Int) {
        for (y in (y0 shr 2) until ((y0 + h) shr 2)) a.fill(v.toByte(), y * w4 + (x0 shr 2), y * w4 + ((x0 + w) shr 2))
    }

    /** Marks the left and top edges of a coding or transform block as transform block edges, for the deblocking filter. */
    private fun markEdges(x0: Int, y0: Int, size: Int) {
        val n = size shr 2
        val top = (y0 shr 2) * w4 + (x0 shr 2)
        for (i in 0 until n) {
            flags[top + i] = (flags[top + i].toInt() or (FLAG_EDGE_TOP or FLAG_TRANSFORM_TOP)).toByte()
            flags[top + i * w4] = (flags[top + i * w4].toInt() or (FLAG_EDGE_LEFT or FLAG_TRANSFORM_LEFT)).toByte()
        }
    }

    /** The luma and chroma intra prediction modes of a coding unit: 7.3.8.5, 8.4.2 and 8.4.3. */
    private fun intraModes(x0: Int, y0: Int, log2: Int) {
        val parts = if (intraSplit) 4 else 1
        val half = (1 shl log2) shr 1
        val pbSize = if (intraSplit) half else 1 shl log2
        var prevFlags = 0
        for (k in 0 until parts) prevFlags = prevFlags or (cabac.decode(Ctx.PREV_INTRA_LUMA_PRED) shl k)
        for (k in 0 until parts) {
            val xPb = x0 + (k and 1) * half
            val yPb = y0 + (k shr 1) * half
            // mpm_idx is TR with cMax 2, rem_intra_luma_pred_mode FL of 5 bits, all bypass.
            val mode = if (prevFlags shr k and 1 == 1) {
                val idx = if (cabac.bypass() == 0) 0 else if (cabac.bypass() == 0) 1 else 2
                lumaMode(xPb, yPb, idx, -1)
            } else {
                lumaMode(xPb, yPb, -1, cabac.bypassBits(5))
            }
            fillUnits(predModeY, xPb, yPb, pbSize, pbSize, mode)
        }
        if (chromaArrayType == 0) return
        val chromaParts = if (chromaArrayType == 3) parts else 1
        for (k in 0 until chromaParts) {
            val xPb = x0 + (k and 1) * half
            val yPb = y0 + (k shr 1) * half
            // Table 9-46: 0 is mode 4; 1 and two bypass bins give modes 0 to 3.
            val icpm = if (cabac.decode(Ctx.INTRA_CHROMA_PRED_MODE) == 0) 4 else cabac.bypassBits(2)
            chromaSyntax[k] = icpm
            val luma = predModeY[(yPb shr 2) * w4 + (xPb shr 2)].toInt()
            // Table 8-2: modes 0 to 3 name planar, vertical, horizontal and DC, and 34 stands in for the luma mode.
            val modeIdx = if (icpm == 4) luma else CHROMA_CANDIDATES[icpm].let { if (it == luma) 34 else it }
            chromaMode[k] = if (chromaArrayType == 2) HevcTables.CHROMA_422_MODE[modeIdx] else modeIdx
        }
    }

    /** IntraPredModeY of 8.4.2 for the prediction block at ([xPb], [yPb]), from [mpmIdx] or [rem]. */
    private fun lumaMode(xPb: Int, yPb: Int, mpmIdx: Int, rem: Int): Int {
        val a = candidate(xPb, yPb, xPb - 1, yPb, false)
        val b = candidate(xPb, yPb, xPb, yPb - 1, true)
        var c0: Int
        var c1: Int
        var c2: Int
        if (a == b) {
            if (a < 2) {
                c0 = IntraPredictor.INTRA_PLANAR; c1 = IntraPredictor.INTRA_DC; c2 = 26
            } else {
                c0 = a; c1 = 2 + ((a + 29) % 32); c2 = 2 + ((a - 2 + 1) % 32)
            }
        } else {
            c0 = a
            c1 = b
            c2 = if (a != 0 && b != 0) IntraPredictor.INTRA_PLANAR else if (a != 1 && b != 1) IntraPredictor.INTRA_DC else 26
        }
        if (mpmIdx >= 0) return when (mpmIdx) { 0 -> c0; 1 -> c1; else -> c2 }
        // 8-29 to 8-31, then each candidate at or below the mode moves it up by one.
        if (c0 > c1) { val t = c0; c0 = c1; c1 = t }
        if (c0 > c2) { val t = c0; c0 = c2; c2 = t }
        if (c1 > c2) { val t = c1; c1 = c2; c2 = t }
        var mode = rem
        if (mode >= c0) mode++
        if (mode >= c1) mode++
        if (mode >= c2) mode++
        return mode
    }

    /** candIntraPredModeX of 8.4.2, step 2. */
    private fun candidate(xPb: Int, yPb: Int, xN: Int, yN: Int, above: Boolean): Int {
        if (!available(xPb, yPb, xN, yN)) return IntraPredictor.INTRA_DC
        val u = (yN shr 2) * w4 + (xN shr 2)
        val f = flags[u].toInt()
        if (f and FLAG_INTRA == 0 || f and FLAG_PCM != 0) return IntraPredictor.INTRA_DC
        // The CTB row above is not kept for prediction modes.
        if (above && yN < ((yPb shr ctbLog2) shl ctbLog2)) return IntraPredictor.INTRA_DC
        return predModeY[u].toInt()
    }

    /** pcm_sample( ), 7.3.8.7, and its reconstruction by 8-12, 8-15 and 8-16; then the engine restarts (9.3.2.6). */
    private fun pcmSample(x0: Int, y0: Int, log2: Int) {
        val start = cabac.alignedByte
        checkZeroBits(cabac.bitPosition, start * 8, "pcm_alignment_zero_bit")
        var bit = start * 8
        val limit = substreamEnd * 8
        fun read(n: Int): Int {
            if (bit + n > limit) hevcError("pcm_sample is cut off")
            var v = 0
            for (i in 0 until n) {
                v = (v shl 1) or ((rbsp[bit shr 3].toInt() shr (7 - (bit and 7))) and 1)
                bit++
            }
            return v
        }
        val size = 1 shl log2
        val luma = planes[0]
        val shiftY = sps.bitDepthY - sps.pcmBitDepthY
        for (j in 0 until size) for (i in 0 until size) luma[(y0 + j) * strides[0] + x0 + i] = (read(sps.pcmBitDepthY) shl shiftY).toShort()
        if (chromaArrayType != 0) {
            val shiftC = sps.bitDepthC - sps.pcmBitDepthC
            val w = size shr subWShift
            val h = size shr subHShift
            for (c in 1..2) {
                val p = planes[c]
                val s = strides[c]
                for (j in 0 until h) for (i in 0 until w) p[((y0 shr subHShift) + j) * s + (x0 shr subWShift) + i] = (read(sps.pcmBitDepthC) shl shiftC).toShort()
            }
        }
        // Every PCM block holds a whole number of bytes: 64 samples and more of each kind.
        cabac.start(rbsp, bit shr 3, substreamEnd)
    }

    /** transform_tree( ), 7.3.8.8. [parentCb] and [parentCr] hold the parent's chroma cbf, two blocks in 4:2:2. */
    private fun transformTree(x0: Int, y0: Int, xBase: Int, yBase: Int, log2: Int, depth: Int, blkIdx: Int, parentCb: Int, parentCr: Int) {
        val split = if (log2 <= sps.maxTbLog2 && log2 > minTbLog2 && depth < maxTrafoDepth && !(intraSplit && depth == 0)) {
            cabac.decode(Ctx.SPLIT_TRANSFORM + 5 - log2) == 1
        } else {
            // 7.4.9.8: a block larger than the largest transform, the four blocks of an NxN unit, and with
            // max_transform_hierarchy_depth_inter 0 an inter unit of several prediction blocks (interSplitFlag) split.
            log2 > sps.maxTbLog2 || (intraSplit && depth == 0) ||
                (cuInter && sps.maxTransformHierarchyDepthInter == 0 && partMode != PartMode.PART_2Nx2N && depth == 0)
        }
        var cb = 0
        var cr = 0
        if ((log2 > 2 && chromaArrayType != 0) || chromaArrayType == 3) {
            val second = chromaArrayType == 2 && (!split || log2 == 3)
            if (depth == 0 || parentCb != 0) {
                cb = cabac.decode(Ctx.CBF_CHROMA + depth)
                if (second) cb = cb or (cabac.decode(Ctx.CBF_CHROMA + depth) shl 1)
            }
            if (depth == 0 || parentCr != 0) {
                cr = cabac.decode(Ctx.CBF_CHROMA + depth)
                if (second) cr = cr or (cabac.decode(Ctx.CBF_CHROMA + depth) shl 1)
            }
        } else if (chromaArrayType != 0) {
            // A 4x4 luma block of 4:2:0 or 4:2:2: its chroma is the parent's, coded with the fourth block.
            cb = parentCb
            cr = parentCr
        }
        if (split) {
            val half = 1 shl (log2 - 1)
            transformTree(x0, y0, x0, y0, log2 - 1, depth + 1, 0, cb, cr)
            transformTree(x0 + half, y0, x0, y0, log2 - 1, depth + 1, 1, cb, cr)
            transformTree(x0, y0 + half, x0, y0, log2 - 1, depth + 1, 2, cb, cr)
            transformTree(x0 + half, y0 + half, x0, y0, log2 - 1, depth + 1, 3, cb, cr)
        } else {
            // cbf_luma is inferred 1 for an inter unit's undivided block with no chroma residual.
            val cbfLuma = if (!cuInter || depth != 0 || cb != 0 || cr != 0) cabac.decode(Ctx.CBF_LUMA + (if (depth == 0) 1 else 0)) else 1
            transformUnit(x0, y0, xBase, yBase, log2, blkIdx, cbfLuma, cb, cr)
        }
    }

    /** transform_unit( ), 7.3.8.10, each block reconstructed as soon as it is parsed. */
    private fun transformUnit(x0: Int, y0: Int, xBase: Int, yBase: Int, log2: Int, blkIdx: Int, cbfLuma: Int, cb: Int, cr: Int) {
        tuAct = false
        if (cbfLuma != 0 || cb != 0 || cr != 0) {
            // tu_residual_act_flag: an inter unit, or an intra one whose chroma takes the luma mode (mode 4) in every block.
            if (act && (cuInter || (if (intraSplit) (0..3).all { chromaSyntax[it] == 4 } else chromaSyntax[0] == 4))) {
                tuAct = cabac.decode(Ctx.TU_RESIDUAL_ACT) == 1
            }
            deltaQp()
            if ((cb != 0 || cr != 0) && !cuBypass) chromaQpOffset()
        }
        markEdges(x0, y0, 1 shl log2)
        if (cbfLuma != 0) {
            val n = 1 shl (log2 - 2)
            for (y in 0 until n) for (x in 0 until n) {
                val u = ((y0 shr 2) + y) * w4 + (x0 shr 2) + x
                flags[u] = (flags[u].toInt() or FLAG_CODED).toByte()
            }
        }
        // An inter block has no intra prediction mode; -1 picks the diagonal scan and no implicit DPCM.
        val lumaMode = if (cuInter) -1 else predModeY[(y0 shr 2) * w4 + (x0 shr 2)].toInt()
        if (tuAct) {
            actTransformUnit(x0, y0, log2, lumaMode, cbfLuma, cb, cr)
            return
        }
        block(0, x0, y0, log2, lumaMode, cbfLuma != 0)
        if (chromaArrayType == 0) return
        val log2C: Int
        val xC: Int
        val yC: Int
        if (log2 > 2 || chromaArrayType == 3) {
            log2C = if (chromaArrayType == 3) log2 else log2 - 1
            xC = x0
            yC = y0
        } else if (blkIdx == 3) {
            log2C = 2
            xC = xBase
            yC = yBase
        } else {
            return
        }
        // The chroma mode of the prediction block that holds the transform block (four in 4:4:4 NxN).
        val half = (1 shl log2Cu) shr 1
        val k = if (chromaArrayType == 3 && intraSplit) (if (yC - yCu >= half) 2 else 0) + (if (xC - xCu >= half) 1 else 0) else 0
        val mode = if (cuInter) -1 else chromaMode[k]
        val blocks = if (chromaArrayType == 2) 2 else 1
        // 7.3.8.10: cross_comp_pred( ) only in 4:4:4 (Pps.checkAgainst), whose chroma blocks are the luma block's.
        val ccp = crossComponent && cbfLuma != 0 && (cuInter || chromaSyntax[k] == 4)
        for (c in 1..2) {
            val cbf = if (c == 1) cb else cr
            if (ccp) crossComponentPrediction(c) else if (c == 1) resScaleCb = 0 else resScaleCr = 0
            for (t in 0 until blocks) {
                block(c, xC shr subWShift, (yC shr subHShift) + (t shl log2C), log2C, mode, (cbf shr t) and 1 != 0)
            }
        }
    }

    /**
     * A transform unit with tu_residual_act_flag (4:4:4 only, so every block is the luma block's size): the three
     * residuals of 8.6.2, the chroma ones with cross-component prediction, go through the adaptive colour
     * transform of 8.6.8 before each is added to its prediction (8.4.1 and 8.5.4 with controlParaAct).
     */
    private fun actTransformUnit(x0: Int, y0: Int, log2: Int, lumaMode: Int, cbfLuma: Int, cb: Int, cr: Int) {
        val n = 1 shl log2
        // The prediction block that holds the transform block, as in [transformUnit].
        val half = (1 shl log2Cu) shr 1
        val k = if (intraSplit) (if (y0 - yCu >= half) 2 else 0) + (if (x0 - xCu >= half) 1 else 0) else 0
        val chromaModeK = if (cuInter) -1 else chromaMode[k]
        val ccp = crossComponent && cbfLuma != 0 && (cuInter || chromaSyntax[k] == 4)
        resScaleCb = 0
        resScaleCr = 0
        for (c in 0..2) {
            if (c > 0 && ccp) crossComponentPrediction(c)
            val coded = if (c == 0) cbfLuma != 0 else ((if (c == 1) cb else cr) and 1) != 0
            val r = blockResidual(c, log2, if (c == 0) lumaMode else chromaModeK, coded)
            if (r == null) actResidual[c].fill(0, 0, n * n) else r.copyInto(actResidual[c], 0, 0, n * n)
        }
        adaptiveColourTransform(n)
        for (c in 0..2) {
            val mode = if (c == 0) lumaMode else chromaModeK
            if (!cuInter) intra.predict(c, x0, y0, log2, mode, cuBypass && implicitRdpcm, false)
            addResidual(c, x0, y0, n, actResidual[c], sps.planeBitDepth(c))
        }
    }

    /** 8.6.8.2 over the n by n residuals of [actResidual]: YCgCo back to the coded colour components. */
    private fun adaptiveColourTransform(n: Int) {
        val rY = actResidual[0]
        val rCb = actResidual[1]
        val rCr = actResidual[2]
        val minY = -coeffMaxY - 1
        val minC = -coeffMaxC - 1
        // 8-329 to 8-332.
        val deltaY = maxOf(sps.bitDepthY, sps.bitDepthC) - sps.bitDepthY
        val deltaC = maxOf(sps.bitDepthY, sps.bitDepthC) - sps.bitDepthC
        val offsetY = if (deltaY > 0) 1 shl (deltaY - 1) else 0
        val offsetC = if (deltaC > 0) 1 shl (deltaC - 1) else 0
        val lossy = !cuBypass
        for (i in 0 until n * n) {
            // 8-326 to 8-328, then 8-333 to 8-335 for a unit that is not bypassed.
            var y = rY[i].coerceIn(minY, coeffMaxY)
            var cg = rCb[i].coerceIn(minC, coeffMaxC)
            var co = rCr[i].coerceIn(minC, coeffMaxC)
            if (lossy) {
                y = y shl deltaY
                cg = cg shl (deltaC + 1)
                co = co shl (deltaC + 1)
            }
            // 8-336 to 8-339: the lifting steps of YCgCo-R.
            val tmp = y - (cg shr 1)
            y = tmp + cg
            cg = tmp - (co shr 1)
            co += cg
            if (lossy) {
                // 8-340 to 8-342.
                y = (y + offsetY) shr deltaY
                cg = (cg + offsetC) shr deltaC
                co = (co + offsetC) shr deltaC
            }
            rY[i] = y
            rCb[i] = cg
            rCr[i] = co
        }
    }

    /** delta_qp( ), 7.3.8.14, with the binarisation of 9.3.3.10. */
    private fun deltaQp() {
        if (!pps.cuQpDeltaEnabled || isCuQpDeltaCoded) return
        isCuQpDeltaCoded = true
        var v = 0
        while (v < 5 && cabac.decode(Ctx.CU_QP_DELTA_ABS + (if (v == 0) 0 else 1)) == 1) v++
        if (v == 5) {
            var k = 0
            while (cabac.bypass() == 1) {
                v += 1 shl k
                if (++k > 16) hevcError("cu_qp_delta_abs is out of range")
            }
            v += cabac.bypassBits(k)
        }
        if (v != 0 && cabac.bypass() == 1) v = -v
        // 7.4.9.14.
        val half = sps.qpBdOffsetY / 2
        if (v < -(26 + half) || v > 25 + half) hevcError("CuQpDeltaVal $v is out of range")
        cuQpDeltaVal = v
        cuQpY = currentQpY()
    }

    /**
     * One transform block of component [cIdx] at ([x0], [y0]) in its own samples: intra prediction
     * (8.4.4.2), then, when [coded], residual_coding( ) and the residual (8.6.2), changed by residual DPCM
     * (8.6.5) and cross-component prediction (8.6.6), added with clipping (8.6.7).
     */
    private fun block(cIdx: Int, x0: Int, y0: Int, log2: Int, mode: Int, coded: Boolean) {
        // An inter unit's prediction is already in the picture (8.5.1).
        if (!cuInter) intra.predict(cIdx, x0, y0, log2, mode, cuBypass && implicitRdpcm, false)
        val r = blockResidual(cIdx, log2, mode, coded) ?: return
        addResidual(cIdx, x0, y0, 1 shl log2, r, if (cIdx == 0) sps.bitDepthY else sps.bitDepthC)
    }

    /**
     * The residual of one transform block of [cIdx] (8.6.2), with residual DPCM (8.6.5) and cross-component
     * prediction (8.6.6), in a buffer that the next block reuses; null for a block without one.
     */
    private fun blockResidual(cIdx: Int, log2: Int, mode: Int, coded: Boolean): IntArray? {
        // 8.4.4.1, step 8: a chroma block takes a share of the luma residual even with no residual of its own.
        val resScale = if (cIdx == 0) 0 else if (cIdx == 1) resScaleCb else resScaleCr
        if (!coded && resScale == 0) return null
        val n = 1 shl log2
        val bitDepth = if (cIdx == 0) sps.bitDepthY else sps.bitDepthC
        val r: IntArray
        if (coded) {
            val qp = if (cIdx != 0) {
                chromaQp(cIdx)
            } else if (tuAct) {
                // 8-291.
                (cuQpY + sps.qpBdOffsetY + pps.actQpOffsetY + slice.actYQpOffset).coerceIn(0, 51 + sps.qpBdOffsetY)
            } else {
                cuQpY + sps.qpBdOffsetY
            }
            val packed = residualCoding(cIdx, log2, mode, qp, bitDepth)
            r = residualOf(packed, cIdx, log2, mode, bitDepth)
        } else {
            r = residual
            r.fill(0, 0, n * n)
        }
        if (crossComponent) {
            if (cIdx == 0) {
                r.copyInto(lumaResidual, 0, 0, n * n)
            } else if (resScale != 0) {
                // 8-324, with ( rY << BitDepthC ) >> BitDepthY as one shift so it stays in 32 bits.
                val shift = sps.bitDepthC - sps.bitDepthY
                val rY = lumaResidual
                for (i in 0 until n * n) {
                    val y = if (shift >= 0) rY[i] shl shift else rY[i] shr -shift
                    r[i] += (resScale * y) shr 3
                }
            }
        }
        return r
    }

    /** 8.6.7: adds the residual [r] of the n by n block at ([x0], [y0]) of [cIdx] to its prediction, clipped. */
    internal fun addResidual(cIdx: Int, x0: Int, y0: Int, n: Int, r: IntArray, bitDepth: Int) {
        val plane = planes[cIdx]
        val stride = strides[cIdx]
        val max = (1 shl bitDepth) - 1
        for (y in 0 until n) {
            val row = (y0 + y) * stride + x0
            for (x in 0 until n) {
                val v = (plane[row + x].toInt() and 0xFFFF) + r[y * n + x]
                plane[row + x] = (if (v < 0) 0 else if (v > max) max else v).toShort()
            }
        }
    }

    /**
     * The residual samples of 8.6.2 from the coefficients [coeff] that [residualCoding] returned as
     * [packed], with the residual DPCM of 8.6.5 that 8.4.4.1 applies to intra blocks.
     */
    private fun residualOf(packed: Int, cIdx: Int, log2: Int, mode: Int, bitDepth: Int): IntArray {
        val n = 1 shl log2
        val maxX = packed and 0xFF
        val maxY = (packed shr 8) and 0xFF
        val skip = packed and TRANSFORM_SKIPPED != 0
        // rotateCoeffs of 8.6.2: a 4x4 intra block without a transform, read back to front.
        val rotate = rotation && !cuInter && log2 == 2 && (skip || cuBypass)
        val r: IntArray
        if (cuBypass) {
            if (rotate) {
                r = residual
                for (i in 0 until 16) r[i] = coeff[15 - i]
            } else {
                r = coeff
            }
        } else {
            r = residual
            // 8-295.
            val bdShift = maxOf(20 - bitDepth, if (extendedPrecision) 11 else 0)
            if (skip) {
                transformSkipResidual(coeff, r, log2, bdShift, rotate)
            } else if (log2 >= (if (cIdx == 0) wideLog2Y else wideLog2C)) {
                transform.inverseWide(coeff, r, log2, maxX, maxY, bdShift, if (cIdx == 0) coeffMaxY else coeffMaxC)
            } else {
                transform.inverse(coeff, r, log2, !cuInter && cIdx == 0 && log2 == 2, maxX, maxY, bdShift, if (cIdx == 0) coeffMaxY else coeffMaxC)
            }
        }
        // 8.4.4.1, step 7: residualDpcm, along the direction of prediction (mDir = predModeIntra / 26); or
        // 8.5.4.2, step 3, and 8.5.4.3, step 4: along explicit_rdpcm_dir_flag.
        val explicit = packed and (EXPLICIT_HORIZONTAL or EXPLICIT_VERTICAL)
        if (explicit != 0 || (implicitRdpcm && (skip || cuBypass) && (mode == 10 || mode == 26))) {
            if (explicit == EXPLICIT_HORIZONTAL || mode == 10) {
                for (y in 0 until n) for (x in 1 until n) r[y * n + x] += r[y * n + x - 1]
            } else {
                for (y in 1 until n) for (x in 0 until n) r[y * n + x] += r[(y - 1) * n + x]
            }
        }
        return r
    }

    /** chroma_qp_offset( ), 7.3.8.15, and CuQpOffsetCb and CuQpOffsetCr by 7-87 and 7-88. */
    private fun chromaQpOffset() {
        if (!slice.cuChromaQpOffsetEnabled || isCuChromaQpOffsetCoded) return
        isCuChromaQpOffsetCoded = true
        if (cabac.decode(Ctx.CU_CHROMA_QP_OFFSET_FLAG) == 0) {
            cuQpOffsetCb = 0
            cuQpOffsetCr = 0
            return
        }
        // cu_chroma_qp_offset_idx: TR with cMax chroma_qp_offset_list_len_minus1, every bin in one context.
        val last = pps.cbQpOffsetList.size - 1
        var idx = 0
        while (idx < last && cabac.decode(Ctx.CU_CHROMA_QP_OFFSET_IDX) == 1) idx++
        cuQpOffsetCb = pps.cbQpOffsetList[idx]
        cuQpOffsetCr = pps.crQpOffsetList[idx]
    }

    /** cross_comp_pred( x0, y0, c ), 7.3.8.12, for [cIdx] = c + 1: ResScaleVal by 7-79 and 7-80. */
    private fun crossComponentPrediction(cIdx: Int) {
        val c = cIdx - 1
        // log2_res_scale_abs_plus1: TR with cMax 4, bin k in context 4 * c + k.
        var log2Abs = 0
        while (log2Abs < 4 && cabac.decode(Ctx.LOG2_RES_SCALE_ABS + 4 * c + log2Abs) == 1) log2Abs++
        var v = 0
        if (log2Abs != 0) {
            v = 1 shl (log2Abs - 1)
            if (cabac.decode(Ctx.RES_SCALE_SIGN + c) == 1) v = -v
        }
        if (cIdx == 1) resScaleCb = v else resScaleCr = v
    }

    /** Qp′Cb or Qp′Cr of 8.6.1 for the current coding unit, with the offsets of 8-287 and 8-288 under tu_residual_act_flag. */
    internal fun chromaQp(cIdx: Int): Int {
        val offset = when {
            tuAct && cIdx == 1 -> pps.actQpOffsetCb + slice.actCbQpOffset + cuQpOffsetCb
            tuAct -> pps.actQpOffsetCr + slice.actCrQpOffset + cuQpOffsetCr
            cIdx == 1 -> pps.cbQpOffset + slice.cbQpOffset + cuQpOffsetCb
            else -> pps.crQpOffset + slice.crQpOffset + cuQpOffsetCr
        }
        val qpi = (cuQpY + offset).coerceIn(-sps.qpBdOffsetC, 57)
        val qpc = if (chromaArrayType == 1) {
            // Table 8-10.
            if (qpi < 30) qpi else if (qpi > 43) qpi - 6 else HevcTables.QPC_30_TO_43[qpi - 30]
        } else {
            minOf(qpi, 51)
        }
        return qpc + sps.qpBdOffsetC
    }

    /**
     * residual_coding( ), 7.3.8.11, into [coeff] in raster order, scaled by 8.6.3 unless the unit
     * bypasses it. Returns the last column and row that hold a coefficient, and TRANSFORM_SKIPPED.
     */
    private fun residualCoding(cIdx: Int, log2: Int, predMode: Int, qp: Int, bitDepth: Int): Int {
        val n = 1 shl log2
        coeff.fill(0, 0, n * n)
        val c = cabac
        val chroma = cIdx > 0
        val skip = pps.transformSkipEnabled && !cuBypass && log2 <= pps.log2MaxTransformSkipSize &&
            c.decode(Ctx.TRANSFORM_SKIP + (if (chroma) 1 else 0)) == 1
        // explicit_rdpcm_flag and explicit_rdpcm_dir_flag, of an inter block without a transform.
        var explicit = 0
        if (cuInter && sps.explicitRdpcm && (skip || cuBypass) && c.decode(Ctx.EXPLICIT_RDPCM + (if (chroma) 1 else 0)) == 1) {
            explicit = if (c.decode(Ctx.EXPLICIT_RDPCM_DIR + (if (chroma) 1 else 0)) == 1) EXPLICIT_VERTICAL else EXPLICIT_HORIZONTAL
        }
        // last_sig_coeff_x_prefix and _y_prefix: TR with cMax ( log2 << 1 ) - 1 and the contexts of 9.3.4.2.3.
        val cMax = (log2 shl 1) - 1
        val ctxOffset = if (chroma) 15 else 3 * (log2 - 2) + ((log2 - 1) shr 2)
        val ctxShift = if (chroma) log2 - 2 else (log2 + 1) shr 2
        var px = 0
        while (px < cMax && c.decode(Ctx.LAST_X_PREFIX + ctxOffset + (px shr ctxShift)) == 1) px++
        var py = 0
        while (py < cMax && c.decode(Ctx.LAST_Y_PREFIX + ctxOffset + (py shr ctxShift)) == 1) py++
        // 7-74 to 7-77.
        var lastX = if (px > 3) ((1 shl ((px shr 1) - 1)) * (2 + (px and 1))) + c.bypassBits((px shr 1) - 1) else px
        var lastY = if (py > 3) ((1 shl ((py shr 1) - 1)) * (2 + (py and 1))) + c.bypassBits((py shr 1) - 1) else py
        // scanIdx of 7.4.9.11.
        val scanIdx = if (log2 == 2 || (log2 == 3 && (cIdx == 0 || chromaArrayType == 3))) {
            if (predMode in 6..14) HevcScan.VERTICAL else if (predMode in 22..30) HevcScan.HORIZONTAL else HevcScan.DIAGONAL
        } else {
            HevcScan.DIAGONAL
        }
        if (scanIdx == HevcScan.VERTICAL) { val t = lastX; lastX = lastY; lastY = t }
        val subLog2 = log2 - 2
        val subScan = HevcScan.order(subLog2, scanIdx)
        val scan = HevcScan.order(2, scanIdx)
        val subSide = 1 shl subLog2
        val lastSub = indexOf(subScan, (lastX shr 2) or ((lastY shr 2) shl 8))
        val lastPos = indexOf(scan, (lastX and 3) or ((lastY and 3) shl 8))
        subFlags.fill(0, 0, subSide * subSide)
        // The scaling of 8.6.3, applied to each coefficient as it is read.
        val factor = scalingFactor?.let { f ->
            // 7.4.5 Table 7-4: matrixId is cIdx in an intra block and 3 + cIdx in an inter one; 8.6.3 keeps
            // m = 16 for a larger skipped block.
            if (skip && n > 4) null else f[subLog2 * 6 + (if (cuInter) 3 else 0) + cIdx]
        }
        val levelScale = LEVEL_SCALE[qp % 6]
        val coeffMax = if (chroma) coeffMaxC else coeffMaxY
        // 8-301: bdShift is BitDepth + Log2( nTbS ) + 10 - log2TransformRange, and log2TransformRange is
        // 15 unless extended precision raises it (8-300).
        val log2Range = if (chroma) log2RangeC else log2RangeY
        val shift = qp / 6 - (bitDepth + log2 + 10 - log2Range)
        // 7.3.8.11: neither a bypass block nor one that residual DPCM changes hides a sign.
        val hide = pps.signDataHiding && !cuBypass && explicit == 0 && !(implicitRdpcm && skip && (predMode == 10 || predMode == 26))
        // sbType of 9-20 and 9-21, whose StatCoeff seeds the Rice parameter of each sub-block.
        val statIdx = Ctx.STAT_COEFF + (if (chroma) 0 else 2) + (if (skip || cuBypass) 1 else 0)
        val skipSig = skipContext && (skip || cuBypass)
        var maxX = 0
        var maxY = 0
        var greater1Ctx = 1
        var firstSubblock = true
        val sigBase = Ctx.SIG_COEFF + (if (chroma) 27 else 0)
        val positions = this.positions
        for (i in lastSub downTo 0) {
            val sub = subScan[i]
            val xS = sub and 0xFF
            val yS = sub shr 8
            val right = if (xS < subSide - 1) subFlags[yS * subSide + xS + 1] else 0
            val below = if (yS < subSide - 1) subFlags[(yS + 1) * subSide + xS] else 0
            var inferDc = false
            val coded = if (i < lastSub && i > 0) {
                val inc = minOf(right + below, 1) + (if (chroma) 2 else 0)
                inferDc = true
                c.decode(Ctx.CODED_SUB_BLOCK + inc)
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
                    val xP = pos and 0xFF
                    val yP = pos shr 8
                    if (k > 0 || !inferDc) {
                        // 9-40: one context for every coefficient of a block without a transform.
                        val sigCtx = if (skipSig) (if (chroma) 16 else 42) else sigContext(log2, cIdx, scanIdx, xS, yS, xP, yP, prevCsbf)
                        if (c.decode(sigBase + sigCtx) == 1) {
                            positions[count++] = k
                            inferDc = false
                        }
                    } else {
                        // 7.4.9.11: the DC of a coded sub-block whose other coefficients are all 0.
                        positions[count++] = 0
                    }
                }
            }
            if (count == 0) continue
            // coeff_abs_level_greater1_flag for the first eight, with the contexts of 9.3.4.2.6.
            var ctxSet = if (i == 0 || chroma) 0 else 2
            if (!firstSubblock && greater1Ctx == 0) ctxSet++
            firstSubblock = false
            greater1Ctx = 1
            var greater1 = 0
            var firstGreater1 = -1
            val g1Base = Ctx.GREATER1 + ctxSet * 4 + (if (chroma) 16 else 0)
            for (k in 0 until minOf(count, 8)) {
                val f = c.decode(g1Base + minOf(3, greater1Ctx))
                if (f == 1) {
                    greater1 = greater1 or (1 shl k)
                    if (firstGreater1 < 0) firstGreater1 = k
                }
                if (greater1Ctx > 0) greater1Ctx = if (f == 1) 0 else greater1Ctx + 1
            }
            var greater2 = 0
            if (firstGreater1 >= 0) greater2 = c.decode(Ctx.GREATER2 + ctxSet + (if (chroma) 4 else 0))
            val signHidden = hide && positions[0] - positions[count - 1] > 3
            val signCount = if (signHidden) count - 1 else count
            // 9.3.4.3.6 before the bypass bins, when escapeDataPresent of 7.3.8.11 is 1: a second greater1
            // flag of 1, a greater2 flag of 1, or more coefficients than greater1 flags.
            if (bypassAlignment && (count > 8 || greater2 == 1 || greater1 and (greater1 - 1) != 0)) c.align()
            var signs = c.bypassBits(signCount) shl (32 - signCount)
            var rice = if (persistentRice) c.states[statIdx] / 4 else 0
            var firstRemaining = persistentRice
            var sum = 0
            for (k in 0 until count) {
                var abs = 1 + ((greater1 shr k) and 1) + (if (k == firstGreater1) greater2 else 0)
                val threshold = if (k < 8) (if (k == firstGreater1) 3 else 2) else 1
                if (abs == threshold) {
                    val rem = remaining(rice, log2Range)
                    abs += rem
                    if (firstRemaining) {
                        // 9-23, on the first coeff_abs_level_remaining of the sub-block.
                        firstRemaining = false
                        val stat = c.states[statIdx].toInt()
                        if (rem >= (3 shl (stat / 4))) c.states[statIdx] = (stat + 1).toByte()
                        else if (2 * rem < (1 shl (stat / 4)) && stat > 0) c.states[statIdx] = (stat - 1).toByte()
                    }
                    // 9-24, or 9-25 without its bound of 4 under persistent_rice_adaptation_enabled_flag.
                    if (abs > 3 * (1 shl rice)) rice = if (persistentRice) rice + 1 else minOf(rice + 1, 4)
                }
                // A level past CoeffMin or CoeffMax (7.4.9.11); this also keeps the Rice parameter below 23.
                if (abs > coeffMax + 1) hevcError("a coefficient level of $abs is out of range")
                var level = abs
                if (k < signCount) {
                    if (signs < 0) level = -level
                    signs = signs shl 1
                }
                sum += abs
                // Sign data hiding: the parity of the sum gives the sign of the last coefficient.
                if (signHidden && k == count - 1 && sum and 1 == 1) level = -level
                if (level > coeffMax) hevcError("a coefficient level of $level is out of range")
                val pos = scan[positions[k]]
                val xC = (xS shl 2) + (pos and 0xFF)
                val yC = (yS shl 2) + (pos shr 8)
                if (xC > maxX) maxX = xC
                if (yC > maxY) maxY = yC
                val m = if (factor == null) 16 else factor[yC * n + xC]
                coeff[yC * n + xC] = if (cuBypass) level else if (extendedPrecision) scaleWide(level, m, levelScale, shift, coeffMax) else scale(level, m, levelScale, shift)
            }
        }
        return maxX or (maxY shl 8) or (if (skip) TRANSFORM_SKIPPED else 0) or explicit
    }

    /**
     * coeff_abs_level_remaining with Rice parameter [rice]: a TR prefix of up to four ones (9.3.3.11),
     * then a suffix of EG( rice + 1 ) (9.3.3.3), or under extended precision the limited EGk of 9.3.3.4
     * for a component of [log2Range], log2TransformRange of 9-14.
     */
    private fun remaining(rice: Int, log2Range: Int): Int {
        var prefix = 0
        while (prefix < 4 && cabac.bypass() == 1) prefix++
        if (prefix < 4) return (prefix shl rice) + cabac.bypassBits(rice)
        if (extendedPrecision) {
            // 9-15 and 9-16: at most 28 - log2TransformRange ones, then an escape of up to log2TransformRange bits.
            val riceParam = rice + 1
            val maxPrefix = 28 - log2Range
            var ext = 0
            while (ext < maxPrefix && cabac.bypass() == 1) ext++
            val escape = if (ext == maxPrefix) log2Range else ext + riceParam
            return (((1 shl ext) - 1) shl riceParam) + cabac.bypassBits(escape) + (4 shl rice)
        }
        var k = rice + 1
        var v = 0
        while (cabac.bypass() == 1) {
            v += 1 shl k
            // A level of 16 bits needs no more; a longer prefix is no valid code.
            if (++k > 20) hevcError("coeff_abs_level_remaining is out of range")
        }
        return v + cabac.bypassBits(k) + (4 shl rice)
    }

    internal companion object {
        const val FLAG_INTRA = 1
        const val FLAG_PCM = 2
        const val FLAG_BYPASS = 4
        const val FLAG_EDGE_LEFT = 8
        const val FLAG_EDGE_TOP = 16
        /** The left or top edge of the unit is a transform block edge, not only a prediction block edge (8.7.2.4). */
        const val FLAG_TRANSFORM_LEFT = 32
        const val FLAG_TRANSFORM_TOP = 64
        /** The unit's luma transform block holds a non-zero coefficient level (8.7.2.4). */
        const val FLAG_CODED = 128
        const val TRANSFORM_SKIPPED = 1 shl 16
        const val EXPLICIT_HORIZONTAL = 1 shl 17
        const val EXPLICIT_VERTICAL = 1 shl 18

        /** Table 8-2: the mode that intra_chroma_pred_mode 0 to 3 names. */
        val CHROMA_CANDIDATES = intArrayOf(0, 26, 10, 1)

        /** levelScale of 8.6.3. */
        val LEVEL_SCALE = intArrayOf(40, 45, 51, 57, 64, 72)

        /**
         * The residual of a transform-skipped block (8.6.4.2 with tsShift 5 + Log2( nTbS ), then 8-299) from the
         * scaled coefficients [coeff] into [r], read back to front when [rotate]. The two shifts are one: d << tsShift
         * is a multiple of 1 << bdShift or the rounding of 8-299 lands on d shifted by their difference, which keeps an
         * extended precision coefficient of 22 bits from passing 32.
         */
        fun transformSkipResidual(coeff: IntArray, r: IntArray, log2: Int, bdShift: Int, rotate: Boolean) {
            val ts = 5 + log2
            val last = (1 shl (2 * log2)) - 1
            if (ts >= bdShift) {
                val up = ts - bdShift
                for (i in 0..last) r[i] = coeff[if (rotate) last - i else i] shl up
            } else {
                val down = bdShift - ts
                val round = 1 shl (down - 1)
                for (i in 0..last) r[i] = (coeff[if (rotate) last - i else i] + round) shr down
            }
        }

        /** sigCtx of 9.3.4.2.5, as an offset from the first sig_coeff_flag context of the component. */
        fun sigContext(log2: Int, cIdx: Int, scanIdx: Int, xS: Int, yS: Int, xP: Int, yP: Int, prevCsbf: Int): Int {
            if (log2 == 2) return HevcTables.CTX_IDX_MAP[(yP shl 2) + xP]
            if (xS == 0 && yS == 0 && xP == 0 && yP == 0) return 0
            var sig = when (prevCsbf) {
                0 -> if (xP + yP == 0) 2 else if (xP + yP < 3) 1 else 0
                1 -> if (yP == 0) 2 else if (yP == 1) 1 else 0
                2 -> if (xP == 0) 2 else if (xP == 1) 1 else 0
                else -> 2
            }
            if (cIdx == 0) {
                if (xS + yS > 0) sig += 3
                sig += if (log2 == 3) (if (scanIdx == 0) 9 else 15) else 21
            } else {
                sig += if (log2 == 3) 9 else 12
            }
            return sig
        }

        fun indexOf(scan: IntArray, packed: Int): Int {
            for (i in scan.indices) if (scan[i] == packed) return i
            return 0
        }

        /**
         * 8-309 in 32 bits without extended precision: the product level * m * levelScale fits, and a left
         * shift past 16 bits only saturates, so the shift by qP / 6 and the one by bdShift become one
         * shift either way.
         */
        fun scale(level: Int, m: Int, levelScale: Int, shift: Int): Int {
            val x = level * m * levelScale
            if (shift >= 0) {
                if (x > (32767 shr shift)) return 32767
                if (x < (-32768 shr shift)) return -32768
                return x shl shift
            }
            val b = -shift
            return InverseTransform.clip16((x + (1 shl (b - 1))) shr b)
        }

        /**
         * 8-309 for any CoeffMax, still in 32 bits. A level of 22 bits times m * levelScale (below 2^15)
         * can pass 2^31, but then the result saturates: bdShift of 8-301 is at most 31 - log2TransformRange,
         * so 2^31 shifted right by it is already past CoeffMax. The rounding of a right shift by b is taken
         * as ( ( x >> ( b - 1 ) ) + 1 ) >> 1, which equals ( x + 2^( b - 1 ) ) >> b and cannot overflow.
         */
        fun scaleWide(level: Int, m: Int, levelScale: Int, shift: Int, coeffMax: Int): Int {
            val f = m * levelScale
            val mag = if (level < 0) -level else level
            // Below 2^16 the product fits; above, a division tells whether it would.
            if (mag >= 65536 && mag > Int.MAX_VALUE / f) return if (level < 0) -coeffMax - 1 else coeffMax
            val x = level * f
            val min = -coeffMax - 1
            val v: Int
            if (shift >= 0) {
                if (x > (coeffMax shr shift)) return coeffMax
                if (x < (min shr shift)) return min
                v = x shl shift
            } else {
                v = ((x shr (-shift - 1)) + 1) shr 1
            }
            return if (v < min) min else if (v > coeffMax) coeffMax else v
        }
    }
}
