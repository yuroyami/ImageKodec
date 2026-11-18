package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.jxl.JxlBitReader
import io.github.yuroyami.imagekodec.codec.jxl.JxlColorEncoding
import io.github.yuroyami.imagekodec.codec.jxl.JxlContainer
import io.github.yuroyami.imagekodec.codec.jxl.JxlDecoder
import io.github.yuroyami.imagekodec.codec.jxl.JxlImageHeader

/**
 * Rewrites the colour encoding in the image header of a JPEG XL file (18181-1 A.6 and E.2), so a
 * test can have a header that no cjxl at hand writes: primaries of their own with any curve. cjxl
 * 0.7 takes no custom primaries on its command line, and cjxl 0.11 drops a PNG's cHRM (#189).
 * The frames are kept byte for byte, since they start on a byte boundary after the headers.
 */
internal object JxlHeaderRewrite {

    /** An RGB encoding with D65 white, primaries [xy] (red, green and blue x and y), and [gamma] times 10^7 or the curve [transfer]. */
    fun custom(xy: DoubleArray, gamma: Int = 0, transfer: Int = JxlColorEncoding.TF_SRGB): JxlColorEncoding = JxlColorEncoding(
        false, JxlColorEncoding.RGB, JxlColorEncoding.WHITE_D65, IntArray(2), JxlColorEncoding.PRIMARIES_CUSTOM,
        IntArray(6) { Math.round(xy[it] * 1_000_000).toInt() }, gamma != 0, gamma, transfer, 1,
    )

    /**
     * [jxl] as a bare codestream whose colour encoding is [color]. The old encoding is found by
     * reading one at every bit of the header until one equals the header's and the rewritten
     * file reads back with every other field unchanged. Fails when none does.
     */
    fun withColor(jxl: ByteArray, color: JxlColorEncoding): ByteArray {
        val original = JxlDecoder.open(jxl)
        val cs = JxlContainer.codestream(jxl)
        check(!original.header.color.wantIcc) { "a header with an ICC profile is not rewritten" }
        val start = cs.start + 2
        val br = JxlBitReader(cs.data, start, cs.end)
        JxlImageHeader.read(br)
        val headerEnd = br.bitsConsumed.toInt()
        fun bit(i: Int) = (cs.data[start + i / 8].toInt() ushr (i % 8)) and 1
        val replacement = bits(color)
        for (p in 0 until headerEnd) {
            val shifted = ByteArray((headerEnd - p + 7) / 8 + 8)
            for (i in p until headerEnd) shifted[(i - p) / 8] = (shifted[(i - p) / 8].toInt() or (bit(i) shl ((i - p) % 8))).toByte()
            val r = JxlBitReader(shifted, 0, shifted.size)
            val found = try {
                JxlColorEncoding.read(r)
            } catch (_: ImageDecodeException) {
                continue
            }
            val n = r.bitsConsumed.toInt()
            if (p + n > headerEnd || !same(found, original.header.color)) continue
            val out = ArrayList<Int>()
            for (i in 0 until p) out += bit(i)
            out += replacement
            for (i in p + n until headerEnd) out += bit(i)
            while (out.size % 8 != 0) out += 0
            val head = ByteArray(2 + out.size / 8)
            head[0] = 0xFF.toByte()
            head[1] = 0x0A
            for (i in out.indices) head[2 + i / 8] = (head[2 + i / 8].toInt() or (out[i] shl (i % 8))).toByte()
            val candidate = head + cs.data.copyOfRange(original.firstFrame, cs.end)
            val h = try {
                JxlDecoder.open(candidate).header
            } catch (_: ImageDecodeException) {
                continue
            }
            val o = original.header
            if (h.width == o.width && h.height == o.height && h.xybEncoded == o.xybEncoded && h.bitDepth.bits == o.bitDepth.bits &&
                h.modular16BitBuffers == o.modular16BitBuffers && h.extraChannels.size == o.extraChannels.size &&
                h.intensityTarget == o.intensityTarget && h.orientation == o.orientation && same(h.color, color)
            ) {
                return candidate
            }
        }
        error("no colour encoding found to rewrite in the header")
    }

    private fun same(a: JxlColorEncoding, b: JxlColorEncoding) = a.wantIcc == b.wantIcc && a.colorSpace == b.colorSpace &&
        a.whitePoint == b.whitePoint && a.white.contentEquals(b.white) && a.primaries == b.primaries &&
        a.primariesXy.contentEquals(b.primariesXy) && a.haveGamma == b.haveGamma && a.gamma == b.gamma &&
        a.transferFunction == b.transferFunction && a.renderingIntent == b.renderingIntent

    /** The bits of [c] as 18181-1 E.2 lays out ColourEncoding, first bit first. */
    private fun bits(c: JxlColorEncoding): List<Int> {
        val out = ArrayList<Int>()
        fun write(n: Int, v: Int) {
            for (k in 0 until n) out += (v ushr k) and 1
        }
        fun bool(b: Boolean) = write(1, if (b) 1 else 0)
        /** U32 with distributions (value or bits and offset); the first that holds [v]. */
        fun u32(v: Int, vararg d: IntArray) {
            for ((selector, dist) in d.withIndex()) {
                val fits = if (dist.size == 1) v == dist[0] else v - dist[1] >= 0 && (v - dist[1]).toLong() < (1L shl dist[0])
                if (!fits) continue
                write(2, selector)
                if (dist.size == 2) write(dist[0], v - dist[1])
                return
            }
            error("$v fits no distribution")
        }
        fun enumValue(v: Int) = u32(v, intArrayOf(0), intArrayOf(1), intArrayOf(4, 2), intArrayOf(6, 18))
        fun customXy(v: Int) {
            val packed = (v shl 1) xor (v shr 31)
            u32(packed, intArrayOf(19, 0), intArrayOf(19, 524288), intArrayOf(20, 1048576), intArrayOf(21, 2097152))
        }
        bool(false) // not all default
        bool(c.wantIcc)
        enumValue(c.colorSpace)
        enumValue(c.whitePoint)
        if (c.whitePoint == JxlColorEncoding.WHITE_CUSTOM) for (v in c.white) customXy(v)
        enumValue(c.primaries)
        if (c.primaries == JxlColorEncoding.PRIMARIES_CUSTOM) for (v in c.primariesXy) customXy(v)
        bool(c.haveGamma)
        if (c.haveGamma) write(24, c.gamma) else enumValue(c.transferFunction)
        enumValue(c.renderingIntent)
        return out
    }
}
