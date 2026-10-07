package io.github.yuroyami.imagekodec.codec.avif

/**
 * The post filters of decode_frame_wrapup( ) (specification section 7.4), in their order:
 * the loop filter on CurrFrame, CDEF into CdefFrame, the upscaling of both, and loop
 * restoration into LrFrame, which becomes the decoder's [Av1FrameDecoder.output].
 */
internal object Av1PostFilter {

    fun apply(f: Av1FrameDecoder) {
        val fh = f.fh
        if (fh.loopFilterLevel[0] != 0 || fh.loopFilterLevel[1] != 0) Av1LoopFilter.apply(f)
        // With CDEF off every cdef_idx stays -1, and CdefFrame is CurrFrame.
        val cdefOn = f.seq.enableCdef && !fh.codedLossless && !fh.allowIntrabc
        val cdef = if (cdefOn) Av1Cdef.apply(f) else f.frame
        var upscaledCdef = cdef
        var upscaledCurr = f.frame
        var strides = f.planeWidth.copyOf(f.numPlanes)
        if (fh.useSuperres) {
            upscaledCdef = Av1Superres.upscale(f, cdef)
            upscaledCurr = if (cdef === f.frame) upscaledCdef else Av1Superres.upscale(f, f.frame)
            strides = IntArray(f.numPlanes) { Av1.round2(fh.upscaledWidth, if (it > 0) f.subX else 0) }
        }
        val lr = if (fh.usesLr) Av1LoopRestoration.apply(f, upscaledCurr, upscaledCdef, strides) else upscaledCdef
        f.output = lr
        f.outputStride = strides
    }
}
