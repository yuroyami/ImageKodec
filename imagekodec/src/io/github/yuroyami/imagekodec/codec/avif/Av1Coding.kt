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
     * The restoration type of unit ([unitRow], [unitCol]) of [plane], with its Wiener taps into [wiener] at [wienerAt]
     * (two passes of three) or its self-guided projection into [xqd] at [xqdAt] and its set in [lrSgrSet].
     */
    abstract fun lrUnit(plane: Int, unitRow: Int, unitCol: Int, wiener: IntArray, wienerAt: Int, xqd: IntArray, xqdAt: Int): Int

    /** The self-guided set of the last [lrUnit] that chose one. */
    var lrSgrSet: Int = 0
}

/** The intra mode info of one block that the encoder chose (#176), as the block's syntax codes it. */
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
    var txSize = Av1.TX_4X4

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
        txSize = o.txSize
        return this
    }

    fun copy(): Av1BlockChoice = Av1BlockChoice().copyFrom(this)
}
