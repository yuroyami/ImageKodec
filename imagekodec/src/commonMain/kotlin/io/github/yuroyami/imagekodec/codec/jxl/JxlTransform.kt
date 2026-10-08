// Altered Kotlin port of parts of libjxl 0.11.1 (lib/jxl/modular/transform/transform.cc,
// rct.cc, palette.cc, palette.h, squeeze.cc and squeeze.h).
// Copyright (c) the JPEG XL Project Authors. All rights reserved.
// Use of the original source is governed by a BSD-style license; see NOTICE.

package io.github.yuroyami.imagekodec.codec.jxl

/** One step of a squeeze: which channels it halves, in which direction, and where their residuals go. */
internal class JxlSqueezeStep(val horizontal: Boolean, val inPlace: Boolean, val beginC: Int, val numC: Int)

/**
 * A reversible change an encoder made to a Modular image before it coded the channels:
 * a colour transform of three channels (RCT), a palette, or a squeeze, which splits a
 * channel into a half-size average and a residual.
 */
internal class JxlTransform private constructor(
    val id: Int,
    val beginC: Int,
    val rctType: Int,
    val numC: Int,
    val nbColors: Int,
    val nbDeltas: Int,
    val predictor: Int,
    /** The steps of a squeeze. The default steps replace an empty list when the transform is first applied. */
    var squeezes: List<JxlSqueezeStep>,
) {
    companion object {
        const val RCT = 0
        const val PALETTE = 1
        const val SQUEEZE = 2

        /** The first size that is small enough for the default squeeze to stop. */
        private const val MAX_FIRST_PREVIEW_SIZE = 8

        fun read(br: JxlBitReader): JxlTransform {
            val id = br.u32(fv(RCT), fv(PALETTE), fv(SQUEEZE), fv(3))
            if (id == 3) jxlFail("Modular transform of an unknown kind")
            var beginC = 0
            var rctType = 6
            var numC = 3
            var nbColors = 256
            var nbDeltas = 0
            var predictor = 0
            var squeezes: List<JxlSqueezeStep> = emptyList()
            if (id == RCT || id == PALETTE) beginC = br.u32(fb(3), fo(6, 8), fo(10, 72), fo(13, 1096))
            if (id == RCT) {
                rctType = br.u32(fv(6), fb(2), fo(4, 2), fo(6, 10))
                if (rctType >= 42) jxlFail("Modular colour transform $rctType does not exist")
            }
            if (id == PALETTE) {
                numC = br.u32(fv(1), fv(3), fv(4), fo(13, 1))
                nbColors = br.u32(fo(8, 0), fo(10, 256), fo(12, 1280), fo(16, 5376))
                nbDeltas = br.u32(fv(0), fo(8, 1), fo(10, 257), fo(16, 1281))
                predictor = br.bits(4)
                if (predictor >= NUM_PREDICTORS) jxlFail("Modular predictor $predictor is out of range")
            }
            if (id == SQUEEZE) {
                val count = br.u32(fv(0), fo(4, 1), fo(6, 9), fo(8, 41))
                val list = ArrayList<JxlSqueezeStep>(count)
                repeat(count) {
                    val horizontal = br.bool()
                    val inPlace = br.bool()
                    val begin = br.u32(fb(3), fo(6, 8), fo(10, 72), fo(13, 1096))
                    val num = br.u32(fv(1), fv(2), fv(3), fo(4, 4))
                    list.add(JxlSqueezeStep(horizontal, inPlace, begin, num))
                }
                squeezes = list
            }
            return JxlTransform(id, beginC, rctType, numC, nbColors, nbDeltas, predictor, squeezes)
        }

        /** Fails unless channels [c1] to [c2] exist, are all meta or all not, and have one size and one shift. */
        private fun checkEqualChannels(image: JxlModularImage, c1: Int, c2: Int) {
            val channels = image.channels
            if (c1 > channels.size || c2 >= channels.size || c2 < c1) {
                jxlFail("Modular transform of channels $c1 to $c2, of ${channels.size}")
            }
            if (c1 < image.metaChannels && c2 >= image.metaChannels) jxlFail("Modular transform mixes meta channels and others")
            val a = channels[c1]
            for (c in c1 + 1..c2) {
                val b = channels[c]
                if (a.w != b.w || a.h != b.h || a.hshift != b.hshift || a.vshift != b.vshift) {
                    jxlFail("Modular transform of channels that differ in size")
                }
            }
        }

        // The implicit colours an index past a palette's end stands for: a 4x4x4 cube, then a 5x5x5 cube.
        private const val SMALL_CUBE = 4
        private const val SMALL_CUBE_BITS = 2
        private const val LARGE_CUBE = 5
        private const val LARGE_CUBE_OFFSET = SMALL_CUBE * SMALL_CUBE * SMALL_CUBE

        // The colour differences a negative palette index stands for. Each one is used with both signs.
        private val DELTA_PALETTE = intArrayOf(
            0, 0, 0, 4, 4, 4, 11, 0, 0, 0, 0, -13, 0, -12, 0, -10, -10, -10,
            -18, -18, -18, -27, -27, -27, -18, -18, 0, 0, 0, -32, -32, 0, 0, -37, -37, -37,
            0, -32, -32, 24, 24, 45, 50, 50, 50, -45, -24, -24, -24, -45, -45, 0, -24, -24,
            -34, -34, 0, -24, 0, -24, -45, -45, -24, 64, 64, 64, -32, 0, -32, 0, -32, 0,
            -32, 0, 32, -24, -45, -24, 45, 24, 45, 24, -24, -45, -45, -24, 24, 80, 80, 80,
            64, 0, 0, 0, 0, -64, 0, -64, -64, -24, -24, 45, 96, 96, 96, 64, 64, 0,
            45, -24, -24, 34, -34, 0, 112, 112, 112, 24, -45, -45, 45, 45, -24, 0, -32, 32,
            24, -24, 45, 0, 96, 96, 45, -24, 24, 24, -45, -24, -24, -45, 24, 0, -64, 0,
            96, 0, 0, 128, 128, 128, 64, 0, 64, 144, 144, 144, 96, 96, 0, -36, -36, 36,
            45, -24, -45, 45, -45, -24, 0, 0, -96, 0, 128, 128, 0, 96, 0, 45, 24, -45,
            -128, 0, 0, 24, -45, 24, -45, 24, -45, 64, 0, -64, 64, -64, -64, 96, 0, 96,
            45, -45, 24, 24, 45, -45, 64, 64, -64, 128, 128, 0, 0, 0, -128, -24, 45, -45,
        )

        /** Component [c] of palette entry [index]. [palette] holds one row of [paletteSize] values for each component. */
        private fun paletteValue(palette: IntArray, index: Int, c: Int, paletteSize: Int, bitDepth: Int): Int {
            if (index < 0) {
                if (c >= 3) return 0
                val i = (-(index + 1)) % 143
                var result = DELTA_PALETTE[((i + 1) shr 1) * 3 + c] * (if (i and 1 == 0) -1 else 1)
                if (bitDepth > 8) result *= 1 shl (bitDepth - 8)
                return result
            }
            if (index < paletteSize) return palette[c * paletteSize + index]
            if (c >= 3) return 0
            // (value * ((1 << depth) - 1)) / 4 spreads four or five steps over the range of the bit depth.
            val max = (1L shl bitDepth) - 1
            if (index < paletteSize + LARGE_CUBE_OFFSET) {
                val i = (index - paletteSize) shr (c * SMALL_CUBE_BITS)
                return (((i % SMALL_CUBE) * max) shr 2).toInt() + (1 shl maxOf(0, bitDepth - 3))
            }
            var i = index - paletteSize - LARGE_CUBE_OFFSET
            if (c == 1) i /= LARGE_CUBE
            if (c == 2) i /= LARGE_CUBE * LARGE_CUBE
            return (((i % LARGE_CUBE) * max) shr 2).toInt()
        }

        /** The amount a squeezed pair leans by, guessed from the average before ([b]), this one ([a]) and the next ([n]). */
        private fun smoothTendency(b: Long, a: Long, n: Long): Long {
            var diff = 0L
            if (b >= a && a >= n) {
                diff = (4 * b - 3 * n - a + 6) / 12
                if (diff - (diff and 1) > 2 * (b - a)) diff = 2 * (b - a) + 1
                if (diff + (diff and 1) > 2 * (a - n)) diff = 2 * (a - n)
            } else if (b <= a && a <= n) {
                diff = (4 * b - 3 * n - a - 6) / 12
                if (diff + (diff and 1) < 2 * (b - a)) diff = 2 * (b - a) - 1
                if (diff - (diff and 1) < 2 * (a - n)) diff = 2 * (a - n)
            }
            return diff
        }
    }

    /** Changes the list of channels of [image] to what the encoder coded, without touching a sample. */
    fun metaApply(image: JxlModularImage) {
        when (id) {
            RCT -> checkEqualChannels(image, beginC, beginC + 2)
            PALETTE -> metaPalette(image)
            else -> metaSqueeze(image)
        }
    }

    /** Undoes the transform on the decoded channels of [image]. */
    fun inverse(image: JxlModularImage, wp: JxlWpHeader) {
        when (id) {
            RCT -> inverseRct(image)
            PALETTE -> inversePalette(image, wp)
            else -> inverseSqueeze(image)
        }
    }

    private fun inverseRct(image: JxlModularImage) {
        checkEqualChannels(image, beginC, beginC + 2)
        if (rctType == 0) return
        val permutation = rctType / 7
        val custom = rctType % 7
        val m = beginC
        val channels = image.channels
        val in0 = channels[m]
        val in1 = channels[m + 1]
        val in2 = channels[m + 2]
        val o0 = m + permutation % 3
        val o1 = m + (permutation + 1 + permutation / 3) % 3
        val o2 = m + (permutation + 2 - permutation / 3) % 3
        if (custom != 0) {
            val a = in0.data
            val b = in1.data
            val c = in2.data
            val n = in0.w * in0.h
            if (custom == 6) {
                // YCgCo.
                for (i in 0 until n) {
                    val co = b[i]
                    val cg = c[i]
                    val tmp = a[i] - (cg shr 1)
                    val g = cg + tmp
                    val blue = tmp - (co shr 1)
                    a[i] = blue + co
                    b[i] = g
                    c[i] = blue
                }
            } else {
                val second = custom shr 1
                val third = custom and 1
                for (i in 0 until n) {
                    val first = a[i]
                    var s = b[i]
                    var t = c[i]
                    if (third != 0) t += first
                    if (second == 1) s += first else if (second == 2) s += (first + t) shr 1
                    b[i] = s
                    c[i] = t
                }
            }
        }
        // The three results go to the places the permutation names.
        val r0 = JxlChannel(0, 0).also { it.replace(in0) }
        val r1 = JxlChannel(0, 0).also { it.replace(in1) }
        val r2 = JxlChannel(0, 0).also { it.replace(in2) }
        channels[o0] = r0
        channels[o1] = r1
        channels[o2] = r2
    }

    private fun metaPalette(image: JxlModularImage) {
        val endC = beginC + numC - 1
        checkEqualChannels(image, beginC, endC)
        val nb = numC
        if (beginC >= image.metaChannels) {
            image.metaChannels++
        } else {
            if (endC >= image.metaChannels) jxlFail("Modular palette mixes meta channels and others")
            image.metaChannels += 2 - nb
        }
        // The channels of the palette's colours become one channel of indices, and the palette goes first.
        for (i in endC downTo beginC + 1) image.channels.removeAt(i)
        image.channels.add(0, JxlChannel(nbColors + nbDeltas, nb, -1, -1))
    }

    private fun inversePalette(image: JxlModularImage, wpHeader: JxlWpHeader) {
        val channels = image.channels
        if (image.metaChannels < 1) jxlFail("Modular palette without its palette channel")
        val paletteChannel = channels[0]
        val nb = paletteChannel.h
        val paletteSize = paletteChannel.w
        val c0 = beginC + 1
        if (c0 >= channels.size) jxlFail("Modular palette of a channel that does not exist")
        if (nb < 1) jxlFail("Modular palette of no component")
        val index = channels[c0]
        val w = index.w
        val h = index.h
        for (i in 1 until nb) channels.add(c0 + 1, JxlChannel(w, h, index.hshift, index.vshift))
        val palette = paletteChannel.data
        val bitDepth = minOf(image.bitDepth, 24)
        val size = w.toLong() * h

        if (size == 0L) {
            // Nothing to fill.
        } else if (nbDeltas == 0 && predictor == 0) {
            if (nb == 1) {
                val p = index.data
                for (i in p.indices) {
                    // An empty palette leaves -1 here, as it does in libjxl.
                    val at = minOf(maxOf(0, p[i]), paletteSize - 1)
                    p[i] = paletteValue(palette, at, 0, paletteSize, bitDepth)
                }
            } else {
                val idx = index.data
                val out = Array(nb) { channels[c0 + it].data }
                for (i in idx.indices) {
                    val at = idx[i]
                    for (c in 0 until nb) out[c][i] = paletteValue(palette, at, c, paletteSize, bitDepth)
                }
            }
        } else {
            // An index below nbDeltas adds its colour to a prediction from the pixels already decoded.
            val idx = index.data.copyOf()
            for (c in 0 until nb) {
                val p = channels[c0 + c].data
                if (c == 0) p.fill(0)
                val wp = if (predictor == PRED_WEIGHTED) JxlWpState(wpHeader, w) else null
                for (y in 0 until h) {
                    val row = y * w
                    for (x in 0 until w) {
                        val at = idx[row + x]
                        val entry = paletteValue(palette, at, c, paletteSize, bitDepth)
                        var v = entry.toLong()
                        if (at < nbDeltas) v += JxlModular.predict(predictor, p, row, x, y, w, wp)
                        p[row + x] = v.toInt()
                        // The weighted predictor records an error for every pixel, predicted or not, as libjxl does.
                        wp?.update(v.toInt(), x, y)
                    }
                }
            }
        }
        if (c0 >= image.metaChannels) {
            image.metaChannels--
        } else {
            if (image.metaChannels < 2 - nb) jxlFail("Modular palette leaves fewer than no meta channels")
            image.metaChannels -= 2 - nb
            if (beginC + nb - 1 >= image.metaChannels) jxlFail("Modular palette mixes meta channels and others")
        }
        channels.removeAt(0)
    }

    private fun defaultSqueeze(image: JxlModularImage): List<JxlSqueezeStep> {
        val first = image.metaChannels
        val count = image.channels.size - first
        if (count < 1) jxlFail("Modular squeeze of an image with no channel")
        var w = image.channels[first].w
        var h = image.channels[first].h
        val steps = ArrayList<JxlSqueezeStep>()
        // Chroma goes first, so the first passes hold a picture with its colour at half size.
        if (count > 2 && image.channels[first + 1].w == w && image.channels[first + 1].h == h) {
            steps.add(JxlSqueezeStep(true, false, first + 1, 2))
            steps.add(JxlSqueezeStep(false, false, first + 1, 2))
        }
        if (w <= h && h > MAX_FIRST_PREVIEW_SIZE) {
            steps.add(JxlSqueezeStep(false, true, first, count))
            h = (h + 1) / 2
        }
        while (w > MAX_FIRST_PREVIEW_SIZE || h > MAX_FIRST_PREVIEW_SIZE) {
            if (w > MAX_FIRST_PREVIEW_SIZE) {
                steps.add(JxlSqueezeStep(true, true, first, count))
                w = (w + 1) / 2
            }
            if (h > MAX_FIRST_PREVIEW_SIZE) {
                steps.add(JxlSqueezeStep(false, true, first, count))
                h = (h + 1) / 2
            }
        }
        return steps
    }

    private fun checkSqueezeStep(step: JxlSqueezeStep, numChannels: Int) {
        val c1 = step.beginC
        val c2 = step.beginC + step.numC - 1
        if (c1 >= numChannels || c2 >= numChannels || c2 < c1) jxlFail("Modular squeeze of channels $c1 to $c2, of $numChannels")
    }

    private fun metaSqueeze(image: JxlModularImage) {
        if (squeezes.isEmpty()) squeezes = defaultSqueeze(image)
        val channels = image.channels
        for (step in squeezes) {
            checkSqueezeStep(step, channels.size)
            val beginc = step.beginC
            val endc = step.beginC + step.numC - 1
            if (beginc < image.metaChannels) {
                if (endc >= image.metaChannels) jxlFail("Modular squeeze mixes meta channels and others")
                if (!step.inPlace) jxlFail("Modular squeeze of meta channels with residuals elsewhere")
                image.metaChannels += step.numC
            }
            val offset = if (step.inPlace) endc + 1 else channels.size
            for (c in beginc..endc) {
                val ch = channels[c]
                if (ch.hshift > 30 || ch.vshift > 30) jxlFail("Modular channel squeezed too many times")
                var w = ch.w
                var h = ch.h
                if (w == 0 || h == 0) jxlFail("Modular squeeze of an empty channel")
                if (step.horizontal) {
                    ch.resize((w + 1) / 2, h)
                    if (ch.hshift >= 0) ch.hshift++
                    w -= (w + 1) / 2
                } else {
                    ch.resize(w, (h + 1) / 2)
                    if (ch.vshift >= 0) ch.vshift++
                    h -= (h + 1) / 2
                }
                channels.add(offset + (c - beginc), JxlChannel(w, h, ch.hshift, ch.vshift))
            }
        }
    }

    private fun inverseSqueeze(image: JxlModularImage) {
        val channels = image.channels
        for (i in squeezes.indices.reversed()) {
            val step = squeezes[i]
            checkSqueezeStep(step, channels.size)
            val beginc = step.beginC
            val endc = step.beginC + step.numC - 1
            val offset = if (step.inPlace) endc + 1 else channels.size + beginc - endc - 1
            if (offset < 0) jxlFail("Modular squeeze without its residual channels")
            if (beginc < image.metaChannels) {
                if (image.metaChannels <= step.numC) jxlFail("Modular squeeze leaves no meta channel")
                image.metaChannels -= step.numC
            }
            for (c in beginc..endc) {
                val rc = offset + c - beginc
                if (rc >= channels.size) jxlFail("Modular squeeze without its residual channels")
                if (channels[c].w < channels[rc].w || channels[c].h < channels[rc].h) {
                    jxlFail("Modular squeeze with a residual larger than its average")
                }
                if (step.horizontal) inverseHSqueeze(channels[c], channels[rc]) else inverseVSqueeze(channels[c], channels[rc])
            }
            for (c in endc downTo beginc) channels.removeAt(offset + c - beginc)
        }
    }

    private fun inverseHSqueeze(avg: JxlChannel, res: JxlChannel) {
        if (avg.w != (avg.w + res.w + 1) / 2 || avg.h != res.h) jxlFail("Modular squeeze of channels that do not fit")
        if (res.w == 0) {
            avg.hshift--
            return
        }
        val out = JxlChannel(avg.w + res.w, avg.h, avg.hshift - 1, avg.vshift)
        if (res.h != 0) {
            val a = avg.data
            val r = res.data
            val o = out.data
            val aw = avg.w
            val rw = res.w
            val ow = out.w
            for (y in 0 until avg.h) {
                val ar = y * aw
                val rr = y * rw
                val or = y * ow
                for (x in 0 until rw) {
                    val average = a[ar + x].toLong()
                    val next = if (x + 1 < aw) a[ar + x + 1].toLong() else average
                    val left = if (x > 0) o[or + 2 * x - 1].toLong() else average
                    val diff = r[rr + x] + smoothTendency(left, average, next)
                    val first = average + diff / 2
                    o[or + 2 * x] = first.toInt()
                    o[or + 2 * x + 1] = (first - diff).toInt()
                }
                if (ow and 1 != 0) o[or + ow - 1] = a[ar + aw - 1]
            }
        }
        avg.replace(out)
    }

    private fun inverseVSqueeze(avg: JxlChannel, res: JxlChannel) {
        if (avg.h != (avg.h + res.h + 1) / 2 || avg.w != res.w) jxlFail("Modular squeeze of channels that do not fit")
        if (res.h == 0) {
            avg.vshift--
            return
        }
        val out = JxlChannel(avg.w, avg.h + res.h, avg.hshift, avg.vshift - 1)
        if (res.w != 0) {
            val a = avg.data
            val r = res.data
            val o = out.data
            val w = avg.w
            val ah = avg.h
            for (y in 0 until res.h) {
                val ar = y * w
                val nr = (if (y + 1 < ah) y + 1 else y) * w
                val or = 2 * y * w
                for (x in 0 until w) {
                    val average = a[ar + x].toLong()
                    val next = a[nr + x].toLong()
                    val top = if (y > 0) o[or - w + x].toLong() else average
                    val diff = r[ar + x] + smoothTendency(top, average, next)
                    val first = average + diff / 2
                    o[or + x] = first.toInt()
                    o[or + w + x] = (first - diff).toInt()
                }
            }
            if (out.h and 1 != 0) a.copyInto(o, (out.h - 1) * w, (ah - 1) * w, ah * w)
        }
        avg.replace(out)
    }
}
