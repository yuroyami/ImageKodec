package io.github.yuroyami.imagekodec.codec.avif

/**
 * What an [Av1FrameDecoder] asks when it codes a frame instead of decoding one (#176): each decision the syntax
 * needs, at the point the syntax needs it, and the [sink] its symbols go to. The encoder implements it, so the
 * frame decoder's own syntax walk writes the stream, with its contexts and its CDF adaptation, and its own
 * prediction and reconstruction make the encoder's picture.
 */
internal abstract class Av1Coding {
    /** Where the symbols go: a writer, or a counter of their cost. */
    lateinit var sink: Av1SymbolSink

    /** The partition of the block of [bSize] at ([r], [c]). */
    abstract fun partition(r: Int, c: Int, bSize: Int): Int

    /** The choices of the block of [subSize] at ([r], [c]), asked once as the block starts. */
    abstract fun block(d: Av1FrameDecoder, r: Int, c: Int, subSize: Int): Av1BlockChoice

    /** `cdef_idx` of the 64 by 64 block at ([r], [c]). */
    abstract fun cdefIdx(r: Int, c: Int): Int

    /**
     * The levels of the transform block of [txSz] at ([startX], [startY]) of [plane], whose prediction is already in
     * the frame: signed, in [levels] laid out as [Av1FrameDecoder.quant], with the luma transform type in [txType].
     * Returns the end of block, 0 for none; the level at the end of block's scan position is not 0.
     */
    abstract fun coefficients(d: Av1FrameDecoder, plane: Int, startX: Int, startY: Int, txSz: Int): Int

    /** Signed levels of the last [coefficients]. */
    val levels: IntArray = IntArray(1024)

    /** The luma transform type of the last [coefficients]. */
    var txType: Int = Av1.DCT_DCT

    /**
     * The residual of the last [coefficients] as [Av1Transform.residual] would make it from its levels, when the
     * encoder still has it from coding the same levels before; null when the decoder has to make it (#176).
     */
    open val knownResidual: IntArray? get() = null

    /** The residual the decoder made for the last [coefficients], `w` by `h` of [txSz], before the type's flips. */
    open fun madeResidual(residual: LongArray, txSz: Int) {}

    /**
     * The restoration type of unit ([unitRow], [unitCol]) of [plane], with its Wiener taps into [wiener] at [wienerAt]
     * (two passes of three) or its self-guided projection into [xqd] at [xqdAt] and its set in [lrSgrSet].
     */
    abstract fun lrUnit(plane: Int, unitRow: Int, unitCol: Int, wiener: IntArray, wienerAt: Int, xqd: IntArray, xqdAt: Int): Int

    /** The self-guided set of the last [lrUnit] that chose one. */
    var lrSgrSet: Int = 0
}

/**
 * The mode info of one block that the encoder chose, as the block's syntax codes it: intra (#176), or inter with
 * its references, mode and vectors (#177).
 */
internal class Av1BlockChoice {
    var skip = 0
    var yMode = Av1.DC_PRED
    var angleDeltaY = 0
    var uvMode = Av1.DC_PRED
    var angleDeltaUV = 0
    var cflAlphaU = 0
    var cflAlphaV = 0
    var useFilterIntra = false
    var filterIntraMode = 0
    var paletteSizeY = 0
    var paletteSizeUV = 0
    /** The luma palette, ascending, and its colour map (64 a row), which a choice never changes once made. */
    val paletteColorsY = IntArray(8)
    var colorMapY: IntArray? = null
    /** The chroma palette, U ascending with each V beside its U, and its colour map (64 a row), likewise fixed. */
    val paletteColorsU = IntArray(8)
    val paletteColorsV = IntArray(8)
    var colorMapUV: IntArray? = null
    var txSize = Av1.TX_4X4
    /** Whether the block copies an earlier part of the frame (intra block copy), and its vector in eighths. */
    var useIntrabc = false
    var mvRow = 0
    var mvCol = 0
    /** Whether the block predicts from other frames (#177); [yMode] is then its inter mode, NEARESTMV to NEW_NEWMV. */
    var isInter = false
    /** RefFrame[ 0 ] and RefFrame[ 1 ] of an inter block: LAST_FRAME to ALTREF_FRAME, and NONE when it has one reference. */
    var refFrame0 = Av1.INTRA_FRAME
    var refFrame1 = Av1.NONE
    /** The index into the reference vector stack that NEWMV or NEARMV codes. */
    var refMvIdx = 0
    /** The vector of the second reference of a compound block, in eighths; the first is [mvRow] and [mvCol]. */
    var mv1Row = 0
    var mv1Col = 0
    /** The interpolation filter, when the frame lets each block choose. */
    var interpFilter = Av1.EIGHTTAP

    fun copyFrom(o: Av1BlockChoice): Av1BlockChoice {
        skip = o.skip
        yMode = o.yMode
        angleDeltaY = o.angleDeltaY
        uvMode = o.uvMode
        angleDeltaUV = o.angleDeltaUV
        cflAlphaU = o.cflAlphaU
        cflAlphaV = o.cflAlphaV
        useFilterIntra = o.useFilterIntra
        filterIntraMode = o.filterIntraMode
        paletteSizeY = o.paletteSizeY
        paletteSizeUV = o.paletteSizeUV
        o.paletteColorsY.copyInto(paletteColorsY)
        colorMapY = o.colorMapY
        o.paletteColorsU.copyInto(paletteColorsU)
        o.paletteColorsV.copyInto(paletteColorsV)
        colorMapUV = o.colorMapUV
        txSize = o.txSize
        useIntrabc = o.useIntrabc
        mvRow = o.mvRow
        mvCol = o.mvCol
        isInter = o.isInter
        refFrame0 = o.refFrame0
        refFrame1 = o.refFrame1
        refMvIdx = o.refMvIdx
        mv1Row = o.mv1Row
        mv1Col = o.mv1Col
        interpFilter = o.interpFilter
        return this
    }

    fun copy(): Av1BlockChoice = Av1BlockChoice().copyFrom(this)
}
