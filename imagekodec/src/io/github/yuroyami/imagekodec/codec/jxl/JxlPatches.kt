// Altered Kotlin port of parts of libjxl 0.11.1 (lib/jxl/dec_patch_dictionary.cc,
// dec_patch_dictionary.h, patch_dictionary_internal.h and render_pipeline/stage_patches.cc).
// Copyright (c) the JPEG XL Project Authors. All rights reserved.
// Use of the original source is governed by a BSD-style license; see NOTICE.

package io.github.yuroyami.imagekodec.codec.jxl

/**
 * Patches: rectangles of an earlier saved frame that are blended onto this frame. An
 * encoder uses them for a shape that repeats, such as the letters of a text.
 */
internal class JxlPatches private constructor(
    private val image: JxlImageHeader,
    /** The saved frames, as they were when the patches were read. */
    private val frames: Array<JxlReference?>,
    /** For each source rectangle: its saved frame, then x0, y0, width and height in that frame. */
    private val sources: IntArray,
    /** For each patch: x and y in this frame, then the index of its source rectangle. */
    private val positions: IntArray,
    private val count: Int,
    /** For each patch: how the colour is blended, then how each extra channel is. */
    private val blendings: IntArray,
    /** True when a patch blends an extra channel with a mode other than "none", or uses alpha. */
    val usesExtraChannels: Boolean,
    /**
     * The summed size of all patches in samples of one channel. libjxl limits the number of
     * patches and not this sum, so a caller that wants to bound the work has to check it.
     */
    val totalArea: Long,
) {
    /**
     * Blends every patch onto the frame: [color] are the 3 colour planes, [extra] the extra
     * planes. Works in place. The extra planes are read and written only when
     * [usesExtraChannels] is true, and then they must have the size of the colour planes.
     */
    fun apply(color: Array<JxlPlane>, extra: Array<JxlPlane>) {
        if (count == 0) return
        if (color.size != 3) jxlFail("patches need three colour planes")
        val w = color[0].w
        val h = color[0].h
        if (color[1].w != w || color[1].h != h || color[2].w != w || color[2].h != h) {
            jxlFail("patches need colour planes of one size")
        }
        val numEc = if (usesExtraChannels) image.extraChannels.size else 0
        if (extra.size < numEc) jxlFail("patches blend an extra channel the frame does not have")
        for (i in 0 until numEc) {
            if (extra[i].w != w || extra[i].h != h) jxlFail("patches blend an extra channel that is not the size of the colour")
        }
        val channels = 3 + numEc
        val planes = Array(channels) { if (it < 3) color[it] else extra[it - 3] }
        var widest = 0
        for (s in 0 until sources.size / SOURCE_FIELDS) widest = maxOf(widest, sources[s * SOURCE_FIELDS + 3])
        widest = minOf(widest, w)
        val bg = Array(channels) { FloatArray(widest) }
        val fg = Array(channels) { FloatArray(widest) }
        val out = Array(channels) { FloatArray(widest) }

        val stride = image.extraChannels.size + 1
        // Most patches share their modes, so a mode object is made only when the mode changes.
        val packed = IntArray(1 + numEc) { -1 }
        var colorMode = JxlBlend.Mode(JxlBlend.NONE, 0, false)
        val ecModes = Array(numEc) { colorMode }

        // libjxl draws the patches of a row in the order of the file. Every sample depends
        // only on the patches over it, so drawing patch by patch gives the same result.
        for (p in 0 until count) {
            val px = positions[p * 3]
            val py = positions[p * 3 + 1]
            val s = positions[p * 3 + 2] * SOURCE_FIELDS
            val source = frames[sources[s]] ?: jxlFail("patch from a frame that was not saved")
            val sx = sources[s + 1]
            val sy = sources[s + 2]
            // The frame's planes can be smaller than the padded size the positions were checked against.
            val n = minOf(sources[s + 3], w - px)
            val rows = minOf(sources[s + 4], h - py)
            if (n <= 0 || rows <= 0) continue

            for (j in 0..numEc) {
                val b = blendings[p * stride + j]
                if (b == packed[j]) continue
                packed[j] = b
                val mode = JxlBlend.Mode(b and 7, b ushr 4, b and 8 != 0)
                if (j == 0) colorMode = mode else ecModes[j - 1] = mode
            }
            if (numEc == 0 && colorMode.mode == JxlBlend.NONE) continue

            for (iy in 0 until rows) {
                val to = (py + iy) * w + px
                for (c in 0 until channels) {
                    val from = if (c < 3) source.color[c] else source.extra[c - 3]
                    val at = (sy + iy) * from.w + sx
                    from.data.copyInto(fg[c], 0, at, at + n)
                    planes[c].data.copyInto(bg[c], 0, to, to + n)
                }
                // The frame is the background and the saved frame the foreground, as in AddOneRow.
                JxlBlend.blendRow(bg, fg, out, n, colorMode, ecModes, image.extraChannels)
                for (c in 0 until channels) out[c].copyInto(planes[c].data, to, 0, n)
            }
        }
    }

    companion object {
        // The contexts of the patch dictionary's entropy code, as the JPEG XL standard numbers them.
        private const val CTX_NUM_SOURCES = 0
        private const val CTX_REFERENCE_FRAME = 1
        private const val CTX_SIZE = 2
        private const val CTX_SOURCE_POSITION = 3
        private const val CTX_POSITION = 4
        private const val CTX_BLEND_MODE = 5
        private const val CTX_OFFSET = 6
        private const val CTX_COUNT = 7
        private const val CTX_ALPHA_CHANNEL = 8
        private const val CTX_CLAMP = 9
        private const val NUM_CONTEXTS = 10

        private const val NUM_BLEND_MODES = 8
        private const val SOURCE_FIELDS = 5

        private const val MAX_ARRAY = Int.MAX_VALUE - 8L

        private fun usesAlpha(mode: Int): Boolean = mode >= JxlBlend.BLEND_ABOVE

        /** Doubles [a], up to [limit] entries. The caller has checked that the limit holds what it adds. */
        private fun grow(a: IntArray, limit: Long): IntArray {
            if (a.size >= minOf(limit, MAX_ARRAY)) jxlFail("too many patches")
            return a.copyOf(minOf(a.size * 2L, limit, MAX_ARRAY).toInt())
        }

        /**
         * Reads the patch dictionary. [xsize] and [ysize] are the frame's padded size,
         * [image] gives the extra channels, [store] the saved frames.
         */
        fun read(br: JxlBitReader, xsize: Int, ysize: Int, image: JxlImageHeader, store: JxlFrameStore): JxlPatches {
            val numEc = image.extraChannels.size
            val stride = numEc + 1
            val code = JxlCode.read(br, NUM_CONTEXTS)
            val reader = JxlSymbolReader(code, br)
            fun num(ctx: Int): Long = reader.read(ctx).toLong() and 0xFFFFFFFFL

            val numSources = num(CTX_NUM_SOURCES)
            // libjxl holds the patches to about 66 bytes for each pixel of the frame.
            val numPixels = xsize.toLong() * ysize
            val maxSources = 1024 + numPixels / 4
            val maxPatches = maxSources * 4
            val maxBlendings = maxPatches * 4
            if (numSources > maxSources || numSources > MAX_ARRAY / SOURCE_FIELDS) jxlFail("too many patches")

            val frames = store.references.copyOf()
            var sources = IntArray(minOf(numSources, 64L).toInt() * SOURCE_FIELDS)
            var positions = IntArray(64 * 3)
            var blendings = IntArray(64 * stride)
            var count = 0
            var total = 0L
            var nextSize = 1L
            var usesExtra = false
            var usedFrames = 0
            var area = 0L
            var lastX = 0L
            var lastY = 0L

            for (id in 0 until numSources.toInt()) {
                val ref = num(CTX_REFERENCE_FRAME)
                val frame = (if (ref < frames.size) frames[ref.toInt()] else null)
                    ?: jxlFail("patch from a frame that was not saved")
                if (!frame.beforeColorTransform) jxlFail("patch from a frame saved after its colour transform")
                val fw = frame.color[0].w
                val fh = frame.color[0].h
                for (c in 1 until 3) {
                    if (frame.color[c].w != fw || frame.color[c].h != fh) jxlFail("saved frame with planes of different sizes")
                }
                usedFrames = usedFrames or (1 shl ref.toInt())
                val x0 = num(CTX_SOURCE_POSITION)
                val y0 = num(CTX_SOURCE_POSITION)
                val w = num(CTX_SIZE) + 1
                val h = num(CTX_SIZE) + 1
                if (x0 + w > fw || y0 + h > fh) jxlFail("patch outside its saved frame")

                var idCount = num(CTX_COUNT)
                if (idCount > maxPatches) jxlFail("too many patches")
                idCount++
                total += idCount
                if (total > maxPatches) jxlFail("too many patches")
                if (nextSize < total) nextSize = minOf(nextSize * 2, maxPatches)
                if (nextSize * stride > maxBlendings) jxlFail("too many patches")
                // libjxl checks only the size it reserves, which lags behind the count. This
                // checks the count itself, so the blendings cannot pass the limit libjxl names.
                if (total * stride > maxBlendings) jxlFail("too many patches")

                if (id * SOURCE_FIELDS == sources.size) sources = grow(sources, numSources * SOURCE_FIELDS)
                val s = id * SOURCE_FIELDS
                sources[s] = ref.toInt()
                sources[s + 1] = x0.toInt()
                sources[s + 2] = y0.toInt()
                sources[s + 3] = w.toInt()
                sources[s + 4] = h.toInt()
                area += w * h * idCount

                val chooseAlpha = numEc > 1
                for (i in 0 until idCount) {
                    val x: Long
                    val y: Long
                    if (i == 0L) {
                        x = num(CTX_POSITION)
                        y = num(CTX_POSITION)
                    } else {
                        val dx = unpackSigned(reader.read(CTX_OFFSET)).toLong()
                        if (dx < 0 && -dx > lastX) jxlFail("patch at a negative x")
                        x = lastX + dx
                        val dy = unpackSigned(reader.read(CTX_OFFSET)).toLong()
                        if (dy < 0 && -dy > lastY) jxlFail("patch at a negative y")
                        y = lastY + dy
                    }
                    if (x + w > xsize) jxlFail("patch past the right edge of the frame")
                    if (y + h > ysize) jxlFail("patch past the bottom edge of the frame")

                    if ((count + 1L) * 3 > positions.size) positions = grow(positions, maxPatches * 3)
                    if ((count + 1L) * stride > blendings.size) blendings = grow(blendings, maxBlendings)
                    for (j in 0 until stride) {
                        val mode = num(CTX_BLEND_MODE)
                        if (mode >= NUM_BLEND_MODES) jxlFail("patch blend mode $mode")
                        val m = mode.toInt()
                        if (usesAlpha(m)) usesExtra = true
                        if (m != JxlBlend.NONE && j > 0) usesExtra = true
                        var alpha = 0
                        if (usesAlpha(m) && chooseAlpha) {
                            val a = num(CTX_ALPHA_CHANNEL)
                            if (a >= numEc) jxlFail("patch blended with alpha channel $a of $numEc")
                            alpha = a.toInt()
                        }
                        val clamp = (usesAlpha(m) || m == JxlBlend.MUL) && num(CTX_CLAMP) != 0L
                        blendings[count * stride + j] = m or (if (clamp) 8 else 0) or (alpha shl 4)
                    }
                    positions[count * 3] = x.toInt()
                    positions[count * 3 + 1] = y.toInt()
                    positions[count * 3 + 2] = id
                    count++
                    lastX = x
                    lastY = y
                }
            }
            reader.checkFinalState()

            if (usesExtra) {
                for (ref in frames.indices) {
                    if (usedFrames and (1 shl ref) == 0) continue
                    val frame = frames[ref] ?: continue
                    if (frame.extra.size < numEc) jxlFail("patch from a saved frame without the extra channels")
                    for (i in 0 until numEc) {
                        if (frame.extra[i].w != frame.color[0].w || frame.extra[i].h != frame.color[0].h) {
                            jxlFail("saved frame with planes of different sizes")
                        }
                    }
                }
            }
            return JxlPatches(
                image, frames, sources, positions.copyOf(count * 3), count,
                blendings.copyOf(count * stride), usesExtra, area,
            )
        }
    }
}
