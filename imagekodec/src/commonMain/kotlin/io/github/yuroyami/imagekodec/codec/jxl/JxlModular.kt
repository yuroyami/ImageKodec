// Altered Kotlin port of parts of libjxl 0.11.1 (lib/jxl/modular/encoding/dec_ma.cc,
// encoding.cc, context_predict.h and modular_image.h).
// Copyright (c) the JPEG XL Project Authors. All rights reserved.
// Use of the original source is governed by a BSD-style license; see NOTICE.

package io.github.yuroyami.imagekodec.codec.jxl

import io.github.yuroyami.imagekodec.internal.Budget

/**
 * One plane of whole numbers of a Modular image. [hshift] and [vshift] say how many times
 * the plane is halved against the image, and are -1 for a plane that holds a palette.
 */
internal class JxlChannel(var w: Int, var h: Int, var hshift: Int = 0, var vshift: Int = 0) {
    private var store: IntArray? = null

    /** The samples, row by row. They are made on first use, so a channel that is only described costs nothing. */
    val data: IntArray
        get() = store ?: run {
            if (w.toLong() * h > Budget.MAX_PIXELS) jxlFail("Modular channel of $w by $h passes the pixel ceiling")
            IntArray(w * h).also { store = it }
        }

    /** Gives the channel a new size and drops its samples. */
    fun resize(nw: Int, nh: Int) {
        w = nw
        h = nh
        store = null
    }

    fun replace(with: JxlChannel) {
        w = with.w
        h = with.h
        hshift = with.hshift
        vshift = with.vshift
        store = with.store
    }
}

/** The parameters of the self-correcting ("weighted") predictor of one Modular stream. */
internal class JxlWpHeader(
    val p1C: Int = 16,
    val p2C: Int = 10,
    val p3Ca: Int = 7,
    val p3Cb: Int = 7,
    val p3Cc: Int = 7,
    val p3Cd: Int = 0,
    val p3Ce: Int = 0,
    val w0: Int = 0xD,
    val w1: Int = 0xC,
    val w2: Int = 0xC,
    val w3: Int = 0xC,
) {
    companion object {
        fun read(br: JxlBitReader): JxlWpHeader {
            if (br.bool()) return JxlWpHeader()
            return JxlWpHeader(
                br.bits(5), br.bits(5), br.bits(5), br.bits(5), br.bits(5), br.bits(5), br.bits(5),
                br.bits(4), br.bits(4), br.bits(4), br.bits(4),
            )
        }
    }
}

/** What every Modular stream starts with: whether it uses the frame's tree, its predictor parameters and its transforms. */
internal class JxlGroupHeader(val useGlobalTree: Boolean, val wp: JxlWpHeader, val transforms: List<JxlTransform>) {
    companion object {
        fun read(br: JxlBitReader): JxlGroupHeader {
            val global = br.bool()
            val wp = JxlWpHeader.read(br)
            val count = br.u32(fv(0), fv(1), fo(4, 2), fo(8, 18))
            val transforms = ArrayList<JxlTransform>(count)
            repeat(count) { transforms.add(JxlTransform.read(br)) }
            return JxlGroupHeader(global, wp, transforms)
        }
    }
}

/**
 * A Modular image: its channels, the transforms that still have to be undone, and how many
 * of the first channels are "meta" channels, which hold palettes and not pixels.
 */
internal class JxlModularImage(val bitDepth: Int) {
    val channels = ArrayList<JxlChannel>()
    var transforms: List<JxlTransform> = emptyList()
    var metaChannels = 0

    /** Undoes every transform, last one first. */
    fun undoTransforms(wp: JxlWpHeader) {
        for (i in transforms.indices.reversed()) transforms[i].inverse(this, wp)
        transforms = emptyList()
    }
}

/**
 * The decision tree of a Modular stream ("meta-adaptive" tree). An inner node compares one
 * property of the pixel with a value; a leaf names the predictor, the offset and the
 * multiplier of the pixel and the context its residual is read with.
 */
internal class JxlTree private constructor(
    /** The property an inner node tests, or -1 for a leaf. */
    val property: IntArray,
    val splitval: IntArray,
    /** The child for a property above the value, or the leaf's number. */
    val lchild: IntArray,
    val rchild: IntArray,
    val predictor: IntArray,
    val offset: IntArray,
    val multiplier: IntArray,
    val size: Int,
) {
    companion object {
        private const val SPLIT_VAL = 0
        private const val PROPERTY = 1
        private const val PREDICTOR = 2
        private const val OFFSET = 3
        private const val MULTIPLIER_LOG = 4
        private const val MULTIPLIER_BITS = 5
        private const val TREE_CONTEXTS = 6

        const val MAX_SIZE = 1 shl 22
        private const val HEIGHT_LIMIT = 2048

        fun read(br: JxlBitReader, sizeLimit: Int): JxlTree {
            val code = JxlCode.read(br, TREE_CONTEXTS)
            // A property that can only be "split" never reaches a leaf.
            if (code.onlySymbol(code.contextMap[PROPERTY]) > 0) jxlFail("Modular tree that never ends")
            val reader = JxlSymbolReader(code, br)
            val limit = minOf(sizeLimit, MAX_SIZE)
            var cap = 64
            var property = IntArray(cap)
            var splitval = IntArray(cap)
            var lchild = IntArray(cap)
            var rchild = IntArray(cap)
            var predictor = IntArray(cap)
            var offset = IntArray(cap)
            var multiplier = IntArray(cap)
            var size = 0
            var leaves = 0
            var toDecode = 1
            while (toDecode > 0) {
                if (size > limit) jxlFail("Modular tree of more than $limit nodes")
                if (size == cap) {
                    cap *= 2
                    property = property.copyOf(cap)
                    splitval = splitval.copyOf(cap)
                    lchild = lchild.copyOf(cap)
                    rchild = rchild.copyOf(cap)
                    predictor = predictor.copyOf(cap)
                    offset = offset.copyOf(cap)
                    multiplier = multiplier.copyOf(cap)
                }
                toDecode--
                val prop1 = reader.read(PROPERTY)
                if (prop1 < 0 || prop1 > 256) jxlFail("Modular tree property $prop1 is out of range")
                if (prop1 == 0) {
                    val p = reader.read(PREDICTOR)
                    if (p < 0 || p >= NUM_PREDICTORS) jxlFail("Modular predictor $p is out of range")
                    val off = unpackSigned(reader.read(OFFSET))
                    val mulLog = reader.read(MULTIPLIER_LOG)
                    if (mulLog < 0 || mulLog >= 31) jxlFail("Modular multiplier is out of range")
                    val mulBits = reader.read(MULTIPLIER_BITS)
                    if (mulBits < 0 || mulBits >= (1 shl (31 - mulLog)) - 1) jxlFail("Modular multiplier is out of range")
                    property[size] = -1
                    lchild[size] = leaves++
                    predictor[size] = p
                    offset[size] = off
                    multiplier[size] = (mulBits + 1) shl mulLog
                    size++
                    continue
                }
                property[size] = prop1 - 1
                splitval[size] = unpackSigned(reader.read(SPLIT_VAL))
                lchild[size] = size + toDecode + 1
                rchild[size] = size + toDecode + 2
                multiplier[size] = 1
                size++
                toDecode += 2
            }
            reader.checkFinalState()
            val tree = JxlTree(property, splitval, lchild, rchild, predictor, offset, multiplier, size)
            tree.validate()
            return tree
        }
    }

    /** The number of leaves, which is the number of contexts the stream's code has. */
    val leaves: Int get() = (size + 1) / 2

    /**
     * Fails when a node can never be reached, because a node above it already limits the
     * property to the other side, or when the tree is taller than libjxl allows.
     */
    private fun validate() {
        var numProperties = 0
        for (i in 0 until size) if (property[i] >= numProperties) numProperties = property[i] + 1
        if (numProperties == 0) return
        val lo = IntArray(numProperties) { Int.MIN_VALUE }
        val hi = IntArray(numProperties) { Int.MAX_VALUE }
        // A walk from the root that keeps the range of each property on the way down, and puts it back on the way up.
        val node = IntArray(HEIGHT_LIMIT + 2)
        val step = IntArray(HEIGHT_LIMIT + 2)
        val savedLo = IntArray(HEIGHT_LIMIT + 2)
        val savedHi = IntArray(HEIGHT_LIMIT + 2)
        var depth = 0
        node[0] = 0
        step[0] = 0
        while (depth >= 0) {
            val n = node[depth]
            val p = property[n]
            if (p < 0) {
                depth--
                continue
            }
            val v = splitval[n]
            when (step[depth]) {
                0 -> {
                    if (lo[p] > v || hi[p] <= v) jxlFail("Modular tree with a node that cannot be reached")
                    if (depth + 1 > HEIGHT_LIMIT) jxlFail("Modular tree is too tall")
                    savedLo[depth] = lo[p]
                    savedHi[depth] = hi[p]
                    lo[p] = v + 1
                    step[depth] = 1
                    depth++
                    node[depth] = lchild[n]
                    step[depth] = 0
                }
                1 -> {
                    lo[p] = savedLo[depth]
                    hi[p] = v
                    step[depth] = 2
                    depth++
                    node[depth] = rchild[n]
                    step[depth] = 0
                }
                else -> {
                    hi[p] = savedHi[depth]
                    depth--
                }
            }
        }
    }
}

/** The tree and the code that every Modular stream of a frame may share. */
internal class JxlModularCode(val tree: JxlTree, val code: JxlCode)

internal const val NUM_PREDICTORS = 14

private const val PRED_ZERO = 0
private const val PRED_LEFT = 1
private const val PRED_TOP = 2
private const val PRED_AVERAGE0 = 3
private const val PRED_SELECT = 4
private const val PRED_GRADIENT = 5
internal const val PRED_WEIGHTED = 6
private const val PRED_TOP_RIGHT = 7
private const val PRED_TOP_LEFT = 8
private const val PRED_LEFT_LEFT = 9
private const val PRED_AVERAGE1 = 10
private const val PRED_AVERAGE2 = 11
private const val PRED_AVERAGE3 = 12
private const val PRED_AVERAGE4 = 13

// The channel number and the stream number never change inside a channel.
private const val STATIC_PROPERTIES = 2

// The properties that need no earlier channel: two static ones, 13 of the neighbours and one of the weighted predictor.
private const val NONREF_PROPERTIES = 16
private const val WP_PROPERTY = 15
private const val PROPERTIES_PER_CHANNEL = 4

/** The gradient left + top - topleft, held between left and top. The sum wraps as 32-bit numbers do. */
internal fun jxlClampedGradient(n: Int, w: Int, l: Int): Int {
    val m = minOf(n, w)
    val big = maxOf(n, w)
    val grad = n + w - l
    val clamped = if (l < m) big else grad
    return if (l > big) m else clamped
}

/**
 * The state of the weighted predictor over one channel: four sub-predictors, and how wrong
 * each of them was on the row above and on this row.
 */
internal class JxlWpState(private val h: JxlWpHeader, private val xsize: Int) {
    private val prediction = LongArray(4)
    private var pred = 0L
    private val predErrors = Array(4) { IntArray((xsize + 2) * 2) }
    private val error = IntArray((xsize + 2) * 2)
    private val weights = IntArray(4)

    /** The largest of the four neighbouring errors, which is the predictor's property. */
    var property = 0
        private set

    private fun errorWeight(sum: Int, maxWeight: Int): Int {
        val x = sum.toLong() and 0xFFFFFFFFL
        var shift = (63 - (x + 1).countLeadingZeroBits()) - 5
        if (shift < 0) shift = 0
        return 4 + ((maxWeight * DIV_LOOKUP[(x ushr shift).toInt()]) ushr shift)
    }

    /** The prediction for the sample at ([x], [y]) from its neighbours above ([n]), left ([w]) and so on. */
    fun predict(x: Int, y: Int, n: Int, w: Int, ne: Int, nw: Int, nn: Int): Long {
        val curRow = if (y and 1 != 0) 0 else xsize + 2
        val prevRow = if (y and 1 != 0) xsize + 2 else 0
        val posN = prevRow + x
        val posNE = if (x < xsize - 1) posN + 1 else posN
        val posNW = if (x > 0) posN - 1 else posN
        weights[0] = errorWeight(predErrors[0][posN] + predErrors[0][posNE] + predErrors[0][posNW], h.w0)
        weights[1] = errorWeight(predErrors[1][posN] + predErrors[1][posNE] + predErrors[1][posNW], h.w1)
        weights[2] = errorWeight(predErrors[2][posN] + predErrors[2][posNE] + predErrors[2][posNW], h.w2)
        weights[3] = errorWeight(predErrors[3][posN] + predErrors[3][posNE] + predErrors[3][posNW], h.w3)

        // The neighbours carry three more bits of precision.
        val bn = n.toLong() shl 3
        val bw = w.toLong() shl 3
        val bne = ne.toLong() shl 3
        val bnw = nw.toLong() shl 3
        val bnn = nn.toLong() shl 3

        val teW = if (x == 0) 0L else error[curRow + x - 1].toLong()
        val teN = error[posN].toLong()
        val teNW = error[posNW].toLong()
        val sumWN = teN + teW
        val teNE = error[posNE].toLong()

        var p = teW
        if (kotlin.math.abs(teN) > kotlin.math.abs(p)) p = teN
        if (kotlin.math.abs(teNW) > kotlin.math.abs(p)) p = teNW
        if (kotlin.math.abs(teNE) > kotlin.math.abs(p)) p = teNE
        property = p.toInt()

        prediction[0] = bw + bne - bn
        prediction[1] = bn - (((sumWN + teNE) * h.p1C) shr 5)
        prediction[2] = bw - (((sumWN + teNW) * h.p2C) shr 5)
        prediction[3] = bn - ((teNW * h.p3Ca + teN * h.p3Cb + teNE * h.p3Cc + (bnn - bn) * h.p3Cd + (bnw - bw) * h.p3Ce) shr 5)

        // The average of the four, each weighted by how well it did so far.
        var weightSum = weights[0] + weights[1] + weights[2] + weights[3]
        val logWeight = 31 - weightSum.countLeadingZeroBits()
        weightSum = 0
        for (i in 0 until 4) {
            weights[i] = weights[i] ushr (logWeight - 4)
            weightSum += weights[i]
        }
        var sum = ((weightSum shr 1) - 1).toLong()
        for (i in 0 until 4) sum += prediction[i] * weights[i]
        pred = (sum * DIV_LOOKUP[weightSum - 1]) shr 24

        if (((teN xor teW) or (teN xor teNW)) > 0) return (pred + 3) shr 3
        val mx = maxOf(bw, maxOf(bne, bn))
        val mn = minOf(bw, minOf(bne, bn))
        pred = maxOf(mn, minOf(mx, pred))
        return (pred + 3) shr 3
    }

    /** Records how far each sub-predictor was from the true [value] at ([x], [y]). */
    fun update(value: Int, x: Int, y: Int) {
        val curRow = if (y and 1 != 0) 0 else xsize + 2
        val prevRow = if (y and 1 != 0) xsize + 2 else 0
        val v = value.toLong() shl 3
        error[curRow + x] = (pred - v).toInt()
        for (i in 0 until 4) {
            val err = ((kotlin.math.abs(prediction[i] - v) + 3) shr 3).toInt()
            predErrors[i][curRow + x] = err
            predErrors[i][prevRow + x + 1] += err
        }
    }

    private companion object {
        // 2^24 / (i + 1), which turns the division by a sum of weights into a multiplication.
        val DIV_LOOKUP = IntArray(64) { (1 shl 24) / (it + 1) }
    }
}

internal object JxlModular {

    /** No limit on the size of a channel, for a stream that is not the frame's global one. */
    const val NO_LIMIT = 0x1FFFFFFF

    /**
     * Reads one Modular stream into [image]: its header, the description of its transforms,
     * and the channels that are no larger than [maxChanSize] on either side. [streamId] is
     * the number of the stream in its frame, which trees may test.
     */
    fun decode(
        br: JxlBitReader,
        image: JxlModularImage,
        streamId: Int,
        global: JxlModularCode?,
        maxChanSize: Int = NO_LIMIT,
        groupDim: Int = NO_LIMIT,
    ): JxlGroupHeader? {
        if (image.channels.isEmpty()) return null
        val header = JxlGroupHeader.read(br)
        image.transforms = header.transforms
        for (t in header.transforms) t.metaApply(image)
        validateChannels(image, groupDim)

        val channels = image.channels
        var distanceMultiplier = 0
        var count = 0
        var samples = 0L
        var last = channels.size
        for (i in channels.indices) {
            val ch = channels[i]
            if (i >= image.metaChannels && (ch.w > maxChanSize || ch.h > maxChanSize)) {
                last = i
                break
            }
            samples += ch.w.toLong() * ch.h
            if (ch.w == 0 || ch.h == 0) continue
            if (ch.w > distanceMultiplier) distanceMultiplier = ch.w
            count++
        }
        if (count == 0) return header

        val tree: JxlTree
        val code: JxlCode
        if (!header.useGlobalTree) {
            tree = JxlTree.read(br, minOf(1L shl 20, 1024 + samples).toInt())
            code = JxlCode.read(br, tree.leaves)
        } else {
            if (global == null) jxlFail("Modular stream asks for a global tree that the frame does not have")
            tree = global.tree
            code = global.code
        }
        val reader = JxlSymbolReader(code, br, distanceMultiplier)
        for (i in 0 until last) {
            val ch = channels[i]
            if (ch.w == 0 || ch.h == 0) continue
            decodeChannel(reader, code, tree, header.wp, image, i, streamId)
        }
        reader.checkFinalState()
        return header
    }

    /** A channel that a group has to hold must not be halved so often that the group holds none of it. */
    private fun validateChannels(image: JxlModularImage, groupDim: Int) {
        val channels = image.channels
        var c = image.metaChannels
        while (c < channels.size && channels[c].w <= groupDim && channels[c].h <= groupDim) c++
        for (i in c until channels.size) {
            val ch = channels[i]
            if (ch.w == 0 || ch.h == 0) continue
            val isLf = minOf(ch.hshift, ch.vshift) >= 3
            val dim = groupDim.toLong() * (if (isLf) 8 else 1)
            val shift = maxOf(0, maxOf(ch.hshift, ch.vshift))
            if (shift >= 63 || dim shr shift == 0L) jxlFail("Modular transforms leave a group with no samples")
        }
    }

    /**
     * Reads the samples of channel [chan]. For each sample the tree picks a predictor and a
     * context from the properties of the sample's neighbours, and the stream gives the
     * difference from the prediction.
     */
    private fun decodeChannel(
        reader: JxlSymbolReader,
        code: JxlCode,
        tree: JxlTree,
        wpHeader: JxlWpHeader,
        image: JxlModularImage,
        chan: Int,
        streamId: Int,
    ) {
        val ch = image.channels[chan]
        val w = ch.w
        val h = ch.h
        val data = ch.data
        val contextMap = code.contextMap

        // Keep only the part of the tree this channel can reach: the tests of the channel and stream numbers are settled here.
        val tp = tree.property
        val ts = tree.splitval
        val tl = tree.lchild
        val tr = tree.rchild
        var cap = 16
        var nProp = IntArray(cap)
        var nSplit = IntArray(cap)
        var nLeft = IntArray(cap)
        var nRight = IntArray(cap)
        var nPred = IntArray(cap)
        var nOffset = IntArray(cap)
        var nMul = IntArray(cap)
        var nodes = 0
        var maxProp = -1
        var usesWp = false
        // Each entry of the stack: a node of the whole tree, and the slot of its parent to fill, as parent * 2 + side.
        var stack = IntArray(64)
        var sp = 0
        stack[sp++] = 0
        stack[sp++] = -1
        while (sp > 0) {
            val slot = stack[--sp]
            var cur = stack[--sp]
            while (true) {
                val p = tp[cur]
                if (p < 0 || p >= STATIC_PROPERTIES) break
                val value = if (p == 0) chan else streamId
                cur = if (value > ts[cur]) tl[cur] else tr[cur]
            }
            if (nodes == cap) {
                cap *= 2
                nProp = nProp.copyOf(cap)
                nSplit = nSplit.copyOf(cap)
                nLeft = nLeft.copyOf(cap)
                nRight = nRight.copyOf(cap)
                nPred = nPred.copyOf(cap)
                nOffset = nOffset.copyOf(cap)
                nMul = nMul.copyOf(cap)
            }
            val id = nodes++
            if (slot >= 0) {
                if (slot and 1 == 0) nLeft[slot shr 1] = id else nRight[slot shr 1] = id
            }
            val p = tp[cur]
            nProp[id] = p
            if (p < 0) {
                nLeft[id] = contextMap[tl[cur]]
                nPred[id] = tree.predictor[cur]
                nOffset[id] = tree.offset[cur]
                nMul[id] = tree.multiplier[cur]
                if (nPred[id] == PRED_WEIGHTED) usesWp = true
            } else {
                nSplit[id] = ts[cur]
                if (p > maxProp) maxProp = p
                if (p == WP_PROPERTY) usesWp = true
                if (sp + 4 > stack.size) stack = stack.copyOf(stack.size * 2)
                stack[sp++] = tr[cur]
                stack[sp++] = id * 2 + 1
                stack[sp++] = tl[cur]
                stack[sp++] = id * 2
            }
        }

        val wp = if (usesWp) JxlWpState(wpHeader, w) else null

        if (nodes == 1) {
            // One leaf: nothing depends on the neighbours' properties.
            val predictor = nPred[0]
            val offset = nOffset[0].toLong()
            val mul = nMul[0].toLong()
            val ctx = nLeft[0]
            if (predictor == PRED_ZERO) {
                for (i in 0 until w * h) data[i] = (unpackSigned(reader.readClustered(ctx)) * mul + offset).toInt()
                return
            }
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    val guess = predict(predictor, data, row, x, y, w, wp)
                    val v = (unpackSigned(reader.readClustered(ctx)) * mul + offset + guess).toInt()
                    data[row + x] = v
                    wp?.update(v, x, y)
                }
            }
            return
        }

        // Properties past the first 16 describe earlier channels of the same size, four for each.
        val numProps = if (maxProp >= NONREF_PROPERTIES) {
            NONREF_PROPERTIES + ((maxProp - NONREF_PROPERTIES) / PROPERTIES_PER_CHANNEL + 1) * PROPERTIES_PER_CHANNEL
        } else {
            NONREF_PROPERTIES
        }
        val props = IntArray(numProps)
        props[0] = chan
        props[1] = streamId
        val wanted = (numProps - NONREF_PROPERTIES) / PROPERTIES_PER_CHANNEL
        val refs = ArrayList<IntArray>(wanted)
        var j = chan - 1
        while (j >= 0 && refs.size < wanted) {
            val other = image.channels[j]
            if (other.w == w && other.h == h && other.hshift == ch.hshift && other.vshift == ch.vshift) refs.add(other.data)
            j--
        }

        for (y in 0 until h) {
            val row = y * w
            props[2] = y
            props[9] = 0
            for (x in 0 until w) {
                val at = row + x
                val left = if (x > 0) data[at - 1] else if (y > 0) data[at - w] else 0
                val top = if (y > 0) data[at - w] else left
                val topleft = if (x > 0 && y > 0) data[at - w - 1] else left
                val topright = if (x + 1 < w && y > 0) data[at - w + 1] else top
                val leftleft = if (x > 1) data[at - 2] else left
                val toptop = if (y > 1) data[at - 2 * w] else top
                val toprightright = if (x + 2 < w && y > 0) data[at - w + 2] else topright

                props[3] = x
                props[4] = if (top > 0) top else -top
                props[5] = if (left > 0) left else -left
                props[6] = top
                props[7] = left
                props[8] = left - props[9]
                props[9] = left + top - topleft
                props[10] = left - topleft
                props[11] = topleft - top
                props[12] = top - topright
                props[13] = top - toptop
                props[14] = left - leftleft

                var wpPred = 0L
                if (wp != null) {
                    wpPred = wp.predict(x, y, top, left, topright, topleft, toptop)
                    props[WP_PROPERTY] = wp.property
                }
                var o = NONREF_PROPERTIES
                for (r in refs) {
                    val v = r[at].toLong()
                    val vleft = if (x > 0) r[at - 1] else 0
                    val vtop = if (y > 0) r[at - w] else vleft
                    val vtopleft = if (x > 0 && y > 0) r[at - w - 1] else vleft
                    val d = v - jxlClampedGradient(vleft, vtop, vtopleft)
                    props[o] = kotlin.math.abs(v).toInt()
                    props[o + 1] = v.toInt()
                    props[o + 2] = kotlin.math.abs(d).toInt()
                    props[o + 3] = d.toInt()
                    o += PROPERTIES_PER_CHANNEL
                }

                var n = 0
                while (true) {
                    val p = nProp[n]
                    if (p < 0) break
                    n = if (props[p] > nSplit[n]) nLeft[n] else nRight[n]
                }
                val guess = when (nPred[n]) {
                    PRED_ZERO -> 0L
                    PRED_LEFT -> left.toLong()
                    PRED_TOP -> top.toLong()
                    PRED_AVERAGE0 -> (left.toLong() + top) / 2
                    PRED_SELECT -> select(left.toLong(), top.toLong(), topleft.toLong())
                    PRED_GRADIENT -> jxlClampedGradient(left, top, topleft).toLong()
                    PRED_WEIGHTED -> wpPred
                    PRED_TOP_RIGHT -> topright.toLong()
                    PRED_TOP_LEFT -> topleft.toLong()
                    PRED_LEFT_LEFT -> leftleft.toLong()
                    PRED_AVERAGE1 -> (left.toLong() + topleft) / 2
                    PRED_AVERAGE2 -> (topleft.toLong() + top) / 2
                    PRED_AVERAGE3 -> (top.toLong() + topright) / 2
                    else -> (6L * top - 2L * toptop + 7L * left + leftleft + toprightright + 3L * topright + 8) / 16
                }
                val v = (unpackSigned(reader.readClustered(nLeft[n])).toLong() * nMul[n] + nOffset[n] + guess).toInt()
                data[at] = v
                wp?.update(v, x, y)
            }
        }
    }

    private fun select(a: Long, b: Long, c: Long): Long {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        return if (pa < pb) a else b
    }

    /**
     * What [predictor] gives for the sample at ([x], [y]) of a plane [data] that is [w] wide
     * and starts row y at [row]. A neighbour outside the plane takes the value of a nearer one.
     */
    fun predict(predictor: Int, data: IntArray, row: Int, x: Int, y: Int, w: Int, wp: JxlWpState?): Long {
        val at = row + x
        val left = if (x > 0) data[at - 1] else if (y > 0) data[at - w] else 0
        val top = if (y > 0) data[at - w] else left
        val topleft = if (x > 0 && y > 0) data[at - w - 1] else left
        val topright = if (x + 1 < w && y > 0) data[at - w + 1] else top
        val toptop = if (y > 1) data[at - 2 * w] else top
        val wpPred = wp?.predict(x, y, top, left, topright, topleft, toptop) ?: 0L
        return when (predictor) {
            PRED_ZERO -> 0L
            PRED_LEFT -> left.toLong()
            PRED_TOP -> top.toLong()
            PRED_AVERAGE0 -> (left.toLong() + top) / 2
            PRED_SELECT -> select(left.toLong(), top.toLong(), topleft.toLong())
            PRED_GRADIENT -> jxlClampedGradient(left, top, topleft).toLong()
            PRED_WEIGHTED -> wpPred
            PRED_TOP_RIGHT -> topright.toLong()
            PRED_TOP_LEFT -> topleft.toLong()
            PRED_LEFT_LEFT -> (if (x > 1) data[at - 2] else left).toLong()
            PRED_AVERAGE1 -> (left.toLong() + topleft) / 2
            PRED_AVERAGE2 -> (topleft.toLong() + top) / 2
            PRED_AVERAGE3 -> (top.toLong() + topright) / 2
            else -> {
                val leftleft = if (x > 1) data[at - 2] else left
                val toprightright = if (x + 2 < w && y > 0) data[at - w + 2] else topright
                (6L * top - 2L * toptop + 7L * left + leftleft + toprightright + 3L * topright + 8) / 16
            }
        }
    }
}
