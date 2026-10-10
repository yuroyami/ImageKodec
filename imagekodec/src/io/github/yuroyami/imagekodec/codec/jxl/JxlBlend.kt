// Altered Kotlin port of parts of libjxl 0.11.1 (lib/jxl/blending.cc, alpha.cc and
// render_pipeline/stage_blending.cc).
// Copyright (c) the JPEG XL Project Authors. All rights reserved.
// Use of the original source is governed by a BSD-style license; see NOTICE.

package io.github.yuroyami.imagekodec.codec.jxl

/** Puts a frame over the frames a file saved before it, the way the frame's blending modes say. */
internal object JxlBlend {

    // The modes of libjxl's PatchBlendMode, which frames and patches share.
    const val NONE = 0
    const val REPLACE = 1
    const val ADD = 2
    const val MUL = 3
    const val BLEND_ABOVE = 4
    const val BLEND_BELOW = 5
    const val WEIGHTED_ADD_ABOVE = 6
    const val WEIGHTED_ADD_BELOW = 7

    /** How one channel, or the three colour channels together, are blended. */
    class Mode(val mode: Int, val alphaChannel: Int, val clamp: Boolean)

    /** True when [fh] is drawn over an earlier frame, or covers only part of the image. */
    fun needsBlending(fh: JxlFrameHeader): Boolean {
        if (fh.type != JxlFrameHeader.REGULAR_FRAME && fh.type != JxlFrameHeader.SKIP_PROGRESSIVE) return false
        var replaceAll = fh.blending.mode == JxlBlendingInfo.REPLACE
        for (ec in fh.ecBlending) if (ec.mode != JxlBlendingInfo.REPLACE) replaceAll = false
        return fh.customSizeOrOrigin || !replaceAll
    }

    private fun frameMode(info: JxlBlendingInfo): Mode = Mode(
        when (info.mode) {
            JxlBlendingInfo.REPLACE -> REPLACE
            JxlBlendingInfo.ADD -> ADD
            JxlBlendingInfo.MUL -> MUL
            JxlBlendingInfo.BLEND -> BLEND_ABOVE
            else -> WEIGHTED_ADD_ABOVE
        },
        info.alphaChannel,
        info.clamp,
    )

    /**
     * Blends [frame] over its backgrounds and gives the whole image: three colour planes
     * and the extra planes. A frame that has no background is drawn over zeros.
     */
    fun blend(frame: JxlFrame, image: JxlImageHeader, store: JxlFrameStore): Pair<Array<JxlPlane>, Array<JxlPlane>> {
        val fh = frame.header
        val w = image.width
        val h = image.height
        fun background(source: Int): JxlReference? {
            val bg = store.references[source] ?: return null
            if (bg.beforeColorTransform) jxlFail("frame blended over a frame that is not in the image's colour space")
            if (bg.color[0].w < w || bg.color[0].h < h) jxlFail("frame blended over a frame smaller than the image")
            return bg
        }
        val colorBg = background(fh.blending.source)
        val color = Array(3) { copyOrZero(colorBg?.color?.get(it), w, h) }
        val extra = Array(frame.extra.size) { copyOrZero(background(fh.ecBlending[it].source)?.extra?.get(it), w, h) }

        val fw = frame.color[0].w
        val fhh = frame.color[0].h
        val x0 = maxOf(0L, fh.x0.toLong()).toInt()
        val y0 = maxOf(0L, fh.y0.toLong()).toInt()
        val x1 = minOf(w.toLong(), fh.x0.toLong() + fw).toInt()
        val y1 = minOf(h.toLong(), fh.y0.toLong() + fhh).toInt()
        if (x1 <= x0 || y1 <= y0) return Pair(color, extra)
        val n = x1 - x0
        val colorMode = frameMode(fh.blending)
        val ecModes = Array(frame.extra.size) { frameMode(fh.ecBlending[it]) }
        val channels = 3 + frame.extra.size
        val bg = Array(channels) { FloatArray(n) }
        val fg = Array(channels) { FloatArray(n) }
        val out = Array(channels) { FloatArray(n) }
        for (y in y0 until y1) {
            for (c in 0 until channels) {
                val to = if (c < 3) color[c] else extra[c - 3]
                val from = if (c < 3) frame.color[c] else frame.extra[c - 3]
                to.data.copyInto(bg[c], 0, y * w + x0, y * w + x1)
                val at = (y - fh.y0) * fw + (x0 - fh.x0)
                from.data.copyInto(fg[c], 0, at, at + n)
            }
            blendRow(bg, fg, out, n, colorMode, ecModes, image.extraChannels)
            for (c in 0 until channels) {
                val to = if (c < 3) color[c] else extra[c - 3]
                out[c].copyInto(to.data, y * w + x0, 0, n)
            }
        }
        return Pair(color, extra)
    }

    private fun copyOrZero(plane: JxlPlane?, w: Int, h: Int): JxlPlane {
        val out = JxlPlane(w, h)
        if (plane == null) return out
        if (plane.w == w) {
            plane.data.copyInto(out.data, 0, 0, w * h)
        } else {
            for (y in 0 until h) plane.data.copyInto(out.data, y * w, y * plane.w, y * plane.w + w)
        }
        return out
    }

    // Each step is rounded to single precision, as the floats of libjxl are.
    private fun r(x: Double): Double = x.toFloat().toDouble()

    private fun clamp01(x: Double): Double = maxOf(minOf(1.0, x), 0.0)

    /**
     * Blends [n] samples of every channel: [fg] over [bg], into [out]. Channels 0 to 2 are
     * the colour, blended as [color] says; the others are the extra channels.
     */
    fun blendRow(
        bg: Array<FloatArray>,
        fg: Array<FloatArray>,
        out: Array<FloatArray>,
        n: Int,
        color: Mode,
        ec: Array<Mode>,
        info: List<JxlExtraChannel>,
    ) {
        val hasAlpha = info.any { it.type == JxlExtraChannel.ALPHA }
        for (i in ec.indices) {
            val m = ec[i]
            val c = 3 + i
            val a = 3 + m.alphaChannel
            when (m.mode) {
                ADD -> for (x in 0 until n) out[c][x] = (bg[c][x].toDouble() + fg[c][x]).toFloat()
                BLEND_ABOVE -> alphaBlendChannel(bg[c], bg[a], fg[c], fg[a], out[c], n, c == a, info[m.alphaChannel].alphaAssociated, m.clamp)
                BLEND_BELOW -> alphaBlendChannel(fg[c], fg[a], bg[c], bg[a], out[c], n, c == a, info[m.alphaChannel].alphaAssociated, m.clamp)
                WEIGHTED_ADD_ABOVE -> weightedAdd(bg[c], fg[c], fg[a], out[c], n, c == a, m.clamp)
                WEIGHTED_ADD_BELOW -> weightedAdd(fg[c], bg[c], bg[a], out[c], n, c == a, m.clamp)
                MUL -> mul(bg[c], fg[c], out[c], n, m.clamp)
                REPLACE -> fg[c].copyInto(out[c], 0, 0, n)
                else -> bg[c].copyInto(out[c], 0, 0, n)
            }
        }
        val a = 3 + color.alphaChannel
        when (color.mode) {
            ADD -> add(bg, fg, out, n)
            WEIGHTED_ADD_ABOVE -> if (hasAlpha) for (c in 0 until 3) weightedAdd(bg[c], fg[c], fg[a], out[c], n, false, color.clamp) else add(bg, fg, out, n)
            WEIGHTED_ADD_BELOW -> if (hasAlpha) for (c in 0 until 3) weightedAdd(fg[c], bg[c], bg[a], out[c], n, false, color.clamp) else add(bg, fg, out, n)
            BLEND_ABOVE -> if (hasAlpha) alphaBlend(bg, fg, out, a, n, info[color.alphaChannel].alphaAssociated, color.clamp) else copy(fg, out, n)
            BLEND_BELOW -> if (hasAlpha) alphaBlend(fg, bg, out, a, n, info[color.alphaChannel].alphaAssociated, color.clamp) else copy(fg, out, n)
            MUL -> for (c in 0 until 3) mul(bg[c], fg[c], out[c], n, color.clamp)
            REPLACE -> copy(fg, out, n)
            else -> copy(bg, out, n)
        }
    }

    private fun add(bg: Array<FloatArray>, fg: Array<FloatArray>, out: Array<FloatArray>, n: Int) {
        for (c in 0 until 3) for (x in 0 until n) out[c][x] = (bg[c][x].toDouble() + fg[c][x]).toFloat()
    }

    private fun copy(src: Array<FloatArray>, out: Array<FloatArray>, n: Int) {
        for (c in 0 until 3) src[c].copyInto(out[c], 0, 0, n)
    }

    /** The colour of [top] over [bottom], and the alpha of the two together in channel [a]. */
    private fun alphaBlend(bottom: Array<FloatArray>, top: Array<FloatArray>, out: Array<FloatArray>, a: Int, n: Int, premultiplied: Boolean, clamp: Boolean) {
        for (x in 0 until n) {
            val fga = if (clamp) clamp01(top[a][x].toDouble()) else top[a][x].toDouble()
            val bga = bottom[a][x].toDouble()
            val keep = r(1.0 - fga)
            val newA = r(1.0 - r(keep * r(1.0 - bga)))
            if (premultiplied) {
                for (c in 0 until 3) out[c][x] = (top[c][x] + r(bottom[c][x] * keep)).toFloat()
            } else {
                val inv = if (newA > 0) r(1.0 / newA) else 0.0
                for (c in 0 until 3) {
                    out[c][x] = (r(r(top[c][x] * fga) + r(r(bottom[c][x] * bga) * keep)) * inv).toFloat()
                }
            }
            out[a][x] = newA.toFloat()
        }
    }

    /** One extra channel of [fg] over [bg]. libjxl clamps the alpha here when the frame does not ask for it, and this does the same. */
    private fun alphaBlendChannel(
        bg: FloatArray, bga: FloatArray, fg: FloatArray, fga: FloatArray, out: FloatArray,
        n: Int, isAlpha: Boolean, premultiplied: Boolean, clamp: Boolean,
    ) {
        for (x in 0 until n) {
            val fa = if (clamp) fga[x].toDouble() else clamp01(fga[x].toDouble())
            val keep = r(1.0 - fa)
            if (isAlpha) {
                out[x] = (1.0 - r(keep * r(1.0 - bga[x]))).toFloat()
            } else if (premultiplied) {
                out[x] = (fg[x] + r(bg[x] * keep)).toFloat()
            } else {
                val newA = r(1.0 - r(keep * r(1.0 - bga[x])))
                val inv = if (newA > 0) r(1.0 / newA) else 0.0
                out[x] = (r(r(fg[x] * fa) + r(r(bg[x].toDouble() * bga[x]) * keep)) * inv).toFloat()
            }
        }
    }

    private fun weightedAdd(bg: FloatArray, fg: FloatArray, fga: FloatArray, out: FloatArray, n: Int, isAlpha: Boolean, clamp: Boolean) {
        if (isAlpha) {
            bg.copyInto(out, 0, 0, n)
            return
        }
        for (x in 0 until n) {
            val a = if (clamp) clamp01(fga[x].toDouble()) else fga[x].toDouble()
            out[x] = (bg[x] + r(fg[x] * a)).toFloat()
        }
    }

    private fun mul(bg: FloatArray, fg: FloatArray, out: FloatArray, n: Int, clamp: Boolean) {
        for (x in 0 until n) {
            val f = if (clamp) clamp01(fg[x].toDouble()) else fg[x].toDouble()
            out[x] = (bg[x] * f).toFloat()
        }
    }
}
