package io.github.yuroyami.imagekodec.codec.avif

/**
 * The post filters of decode_frame_wrapup( ) (specification section 7.4), in their order: the
 * loop filter on CurrFrame, CDEF into CdefFrame, the upscaling of both, and loop restoration
 * into LrFrame, which becomes the decoder's [Av1FrameDecoder.output].
 *
 * Each works on the frame in place, so a frame is held once however many filters it takes: loop
 * restoration needs only the deblocked rows around each stripe from before CDEF, which are kept
 * aside first, and upscaled with the frame when superres is on.
 */
internal object Av1PostFilter {

    fun apply(f: Av1FrameDecoder) {
        val fh = f.fh
        if (fh.loopFilterLevel[0] != 0 || fh.loopFilterLevel[1] != 0) Av1LoopFilter.apply(f)
        val saved = if (fh.usesLr) Av1LoopRestoration.savedRows(f) else null
        // With CDEF off every cdef_idx stays -1, and CdefFrame is CurrFrame.
        if (f.seq.enableCdef && !fh.codedLossless && !fh.allowIntrabc) Av1Cdef.apply(f)
        var planes = f.frame
        var strides = f.planeWidth.copyOf(f.numPlanes)
        if (fh.useSuperres) {
            planes = Av1Superres.upscale(f, f.frame)
            strides = IntArray(f.numPlanes) { Av1Superres.upscaledWidth(f, it) }
            if (saved != null) {
                for (plane in saved.indices) {
                    for (entry in saved[plane].entries) {
                        val wide = ShortArray(strides[plane])
                        Av1Superres.row(f, plane, entry.value, 0, wide, 0)
                        entry.setValue(wide)
                    }
                }
            }
        }
        if (saved != null) Av1LoopRestoration.apply(f, planes, strides, saved)
        f.output = planes
        f.outputStride = strides
    }
}
