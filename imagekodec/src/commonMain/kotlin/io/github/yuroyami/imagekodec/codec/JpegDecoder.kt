package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.imagekodec.ImageDecodeException
import io.github.yuroyami.imagekodec.JpegComponents
import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.UnsupportedImageException
import io.github.yuroyami.imagekodec.internal.Budget
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Baseline JPEG decoder: a faithful pure-Kotlin port of `stb_image.h`'s JPEG
 * path (the scalar kernels; stb's SIMD variants are behavior-identical by
 * design). Function-level comments name the stb original so the two can be
 * diffed side by side. Scope:
 *
 *  - baseline sequential (SOF0) and extended sequential (SOF1), 8-bit
 *  - Huffman decode with stb's 9-bit fast table + fast-AC combined table
 *  - restart intervals (DRI/RSTn), multi-scan non-interleaved baseline files
 *  - chroma subsampling with integer factors 1..4: 4:4:4, 4:2:0, 4:2:2, 4:1:1
 *    and friends: using stb's JFIF-centered triangle-filter upsampling for the
 *    2x cases and nearest-neighbor for the generic ones
 *  - grayscale (1 comp), YCbCr (3), component-id "RGB" (3, no transform),
 *    CMYK and YCCK chosen from the Adobe APP14 transform flag as libjpeg
 *    chooses them (4), where stb reads an unmarked file as YCbCr and drops K
 *  - stb's fixed-point AAN-style IDCT and reduced-precision YCbCr→RGB, so
 *    output is bit-identical to stb_image on the same file, except where stb's
 *    horizontal 2x upsampling swaps the weights next to the right edge: there
 *    this decoder weights the near sample, as libjpeg does
 *
 *  - progressive (SOF2): spectral selection + successive approximation, EOB
 *    runs, DC/AC refinement scans, deferred dequantize+IDCT at EOI
 *
 *  - arithmetic coding (SOF9, SOF10), with the conditioning of DAC: T.81's QM
 *    decoder and models as libjpeg's jdarith.c reads them, into the same
 *    coefficients, IDCT and upsampling as the Huffman paths, so an arithmetic
 *    file decodes to exactly the pixels of the Huffman file with its coefficients
 *
 * Hierarchical JPEG is refused: libjpeg, libjpeg-turbo and ffmpeg refuse it too,
 * so no file reaches a user and no oracle exists to hold a decoder to (#19).
 * EXIF orientation is metadata and deliberately not applied.
 */
internal object JpegDecoder {

    private const val FAST_BITS = 9
    private const val MARKER_NONE = 0xFF

    /**
     * Most scans one frame may hold. A sequential file has at most one per component and a real
     * progressive file ten to twenty. Each scan walks the whole image, so the count has to be bounded.
     */
    private const val MAX_SCANS = 512

    /** The scratch an IDCT takes: 64 for the full one, 8 rows of up to 8 and a column of 8 for [reducedIdctRect]. */
    private const val IDCT_SCRATCH = 72

    private const val MAX_DIMENSION = 1 shl 24
    private const val MAX_PIXELS = 1L shl 28

    // stbi__jpeg_dezigzag, with stb's overrun padding for corrupt files.
    private val DEZIGZAG = intArrayOf(
        0, 1, 8, 16, 9, 2, 3, 10,
        17, 24, 32, 25, 18, 11, 4, 5,
        12, 19, 26, 33, 40, 48, 41, 34,
        27, 20, 13, 6, 7, 14, 21, 28,
        35, 42, 49, 56, 57, 50, 43, 36,
        29, 22, 15, 23, 30, 37, 44, 51,
        58, 59, 52, 45, 38, 31, 39, 46,
        53, 60, 61, 54, 47, 55, 62, 63,
        63, 63, 63, 63, 63, 63, 63, 63,
        63, 63, 63, 63, 63, 63, 63,
    )

    // stbi__bmask / stbi__jbias
    private val BMASK = intArrayOf(
        0, 1, 3, 7, 15, 31, 63, 127, 255, 511, 1023, 2047, 4095, 8191, 16383, 32767, 65535,
    )
    private val JBIAS = intArrayOf(
        0, -1, -3, -7, -15, -31, -63, -127, -255, -511, -1023, -2047, -4095, -8191, -16383, -32767,
    )

    /**
     * The QM coder's probability estimation of T.81 Table D.2, one entry a state as libjpeg's
     * jaricom.c packs it: Qe in the top 16 bits, Next_Index_MPS in the next 8, then Switch_MPS in
     * bit 7 above Next_Index_LPS. Entry 113 is the fixed even estimate of the sign and refinement
     * bits, which never moves.
     */
    private val ARITAB = intArrayOf(
        0x5a1d0181, 0x2586020e, 0x11140310, 0x080b0412, 0x03d80514, 0x01da0617,
        0x00e50719, 0x006f081c, 0x0036091e, 0x001a0a21, 0x000d0b23, 0x00060c09,
        0x00030d0a, 0x00010d0c, 0x5a7f0f8f, 0x3f251024, 0x2cf21126, 0x207c1227,
        0x17b91328, 0x1182142a, 0x0cef152b, 0x09a1162d, 0x072f172e, 0x055c1830,
        0x04061931, 0x03031a33, 0x02401b34, 0x01b11c36, 0x01441d38, 0x00f51e39,
        0x00b71f3b, 0x008a203c, 0x0068213e, 0x004e223f, 0x003b2320, 0x002c0921,
        0x5ae125a5, 0x484c2640, 0x3a0d2741, 0x2ef12843, 0x261f2944, 0x1f332a45,
        0x19a82b46, 0x15182c48, 0x11772d49, 0x0e742e4a, 0x0bfb2f4b, 0x09f8304d,
        0x0861314e, 0x0706324f, 0x05cd3330, 0x04de3432, 0x040f3532, 0x03633633,
        0x02d43734, 0x025c3835, 0x01f83936, 0x01a43a37, 0x01603b38, 0x01253c39,
        0x00f63d3a, 0x00cb3e3b, 0x00ab3f3d, 0x008f203d, 0x5b1241c1, 0x4d044250,
        0x412c4351, 0x37d84452, 0x2fe84553, 0x293c4654, 0x23794756, 0x1edf4857,
        0x1aa94957, 0x174e4a48, 0x14244b48, 0x119c4c4a, 0x0f6b4d4a, 0x0d514e4b,
        0x0bb64f4d, 0x0a40304d, 0x583251d0, 0x4d1c5258, 0x438e5359, 0x3bdd545a,
        0x34ee555b, 0x2eae565c, 0x299a575d, 0x25164756, 0x557059d8, 0x4ca95a5f,
        0x44d95b60, 0x3e225c61, 0x38245d63, 0x32b45e63, 0x2e17565d, 0x56a860df,
        0x4f466165, 0x47e56266, 0x41cf6367, 0x3c3d6468, 0x375e5d63, 0x52316669,
        0x4c0f676a, 0x4639686b, 0x415e6367, 0x56276ae9, 0x50e76b6c, 0x4b85676d,
        0x55976d6e, 0x504f6b6f, 0x5a106fee, 0x55226d70, 0x59eb6ff0, 0x5a1d7171,
    )

    /** The fixed statistics bin of [ARITAB] entry 113. */
    private const val FIXED_BIN = 113

    private fun err(msg: String): Nothing = throw ImageDecodeException("JPEG: $msg")

    // stbi__huffman
    private class Huffman {
        val fast = ByteArray(1 shl FAST_BITS)       // symbol index or 0xFF flag
        val code = IntArray(256)
        val values = ByteArray(256)
        val size = ByteArray(257)
        val maxcode = LongArray(18)
        val delta = IntArray(17)

        // stbi__build_huffman
        fun build(count: IntArray) {
            var k = 0
            for (i in 0 until 16) {
                repeat(count[i]) {
                    if (k >= 257) err("bad size list")
                    size[k++] = (i + 1).toByte()
                }
            }
            size[k] = 0

            var code = 0
            k = 0
            for (j in 1..16) {
                delta[j] = k - code
                if (size[k].toInt() == j) {
                    while (size[k].toInt() == j) {
                        this.code[k++] = code++
                    }
                    if (code - 1 >= (1 shl j)) err("bad code lengths")
                }
                maxcode[j] = (code.toLong() shl (16 - j)) and 0xFFFFFFFFL
                code = code shl 1
            }
            maxcode[17] = 0xFFFFFFFFL

            fast.fill(0xFF.toByte())
            for (i in 0 until k) {
                val s = size[i].toInt()
                if (s <= FAST_BITS) {
                    val c = this.code[i] shl (FAST_BITS - s)
                    val m = 1 shl (FAST_BITS - s)
                    for (j in 0 until m) fast[c + j] = i.toByte()
                }
            }
        }
    }

    // stbi__build_fast_ac
    private fun buildFastAc(fastAc: ShortArray, h: Huffman) {
        for (i in 0 until (1 shl FAST_BITS)) {
            fastAc[i] = 0
            val fast = h.fast[i].toInt() and 0xFF
            if (fast < 255) {
                val rs = h.values[fast].toInt() and 0xFF
                val run = (rs shr 4) and 15
                val magbits = rs and 15
                val len = h.size[fast].toInt()
                if (magbits != 0 && len + magbits <= FAST_BITS) {
                    var k = ((i shl len) and ((1 shl FAST_BITS) - 1)) shr (FAST_BITS - magbits)
                    val m = 1 shl (magbits - 1)
                    if (k < m) k += ((-1 shl magbits) + 1)
                    if (k in -128..127) {
                        fastAc[i] = ((k * 256) + (run * 16) + (len + magbits)).toShort()
                    }
                }
            }
        }
    }

    private class Component {
        var id = 0
        var h = 0; var v = 0
        var tq = 0
        var quantization: IntArray? = null
        var hd = 0; var ha = 0
        var dcPred = 0
        // arithmetic only: the conditioning category of the last DC difference (T.81 F.1.4.4.1.2)
        var dcContext = 0
        // arithmetic sequential only: whether a scan has coded this component, so a repeat is passed over
        var coded = false
        var x = 0; var y = 0
        var w2 = 0; var h2 = 0
        // The plane in [data] when the decode is reduced: its stride and its rows of image.
        var ws = 0; var ys = 0
        // This component's own reduction across and down, as log2s: smaller than the image's for
        // subsampled chroma, which then decodes straight to the luma's resolution.
        var scaleH = 0; var scaleV = 0
        lateinit var data: ByteArray
        lateinit var linebuf: ByteArray
        // progressive only: raw coefficients, IDCT'd at EOI
        var coeff: ShortArray? = null
        var coeffW = 0
        // progressive only: per block, the zigzag index past which every coefficient is still zero
        // (ffmpeg's last_nnz), so an AC refinement walks only what a block holds
        var lastNonzero: ByteArray? = null
        // progressive only: per coefficient, the Al of the last scan that coded it, -1 before any
        // (libjpeg's coef_bits)
        val approximation = IntArray(64) { -1 }
    }

    private class State(val input: ByteArray) {
        var pos = 0

        val huffDc = Array(4) { Huffman() }
        val huffAc = Array(4) { Huffman() }
        val dequant = arrayOfNulls<IntArray>(4)
        val fastAc = Array(4) { ShortArray(1 shl FAST_BITS) }

        var imgX = 0; var imgY = 0; var imgN = 0
        var hMax = 1; var vMax = 1
        var mcuX = 0; var mcuY = 0
        val comp = Array(4) { Component() }

        var codeBuffer = 0
        var codeBits = 0
        var marker = MARKER_NONE
        var nomore = false
        var padBits = 0            // zero bits growBuffer added past the end of the data, which the file does not hold

        var jfif = false
        var app14ColorTransform = -1
        var rgb = 0

        var scanN = 0
        val order = IntArray(4)
        var restartInterval = 0
        var todo = 0

        // The decode reduces each side by 2^scale, with the IDCT of 8 >> scale points.
        var scale = 0

        var progressive = false
        var specStart = 0
        var specEnd = 0
        var succHigh = 0
        var succLow = 0
        var eobRun = 0
        // The scan breaks its band's progression, so its data is passed over (#67).
        var skipScan = false

        // Arithmetic coding (SOF9, SOF10): the conditioning of DAC, each table's statistics bins,
        // and the QM decoder's registers as libjpeg's jdarith.c keeps them.
        var arithmetic = false
        val dcL = IntArray(16)
        val dcU = IntArray(16) { 1 }
        val acK = IntArray(16) { 5 }
        val dcStats = Array(4) { ByteArray(64) }
        val acStats = Array(4) { ByteArray(256) }
        val fixedBin = byteArrayOf(FIXED_BIN.toByte())
        var arC = 0
        var arA = 0
        var arCt = 0
        // A code that cannot be valid stops the decode until the next restart, as libjpeg's ct = -1 does.
        var arBroken = false

        // header reads: truncation is a decode error
        fun u8(): Int {
            if (pos >= input.size) err("truncated file")
            return input[pos++].toInt() and 0xFF
        }

        fun u16be(): Int = (u8() shl 8) or u8()

        fun skip(n: Int) {
            if (n < 0 || pos + n > input.size) err("truncated file")
            pos += n
        }

        // entropy reads: stb's stbi__get8 returns 0 at EOF instead of failing
        fun u8e(): Int = if (pos < input.size) input[pos++].toInt() and 0xFF else 0

        val eof: Boolean get() = pos >= input.size
    }

    // -------------------------------------------------------------------------
    // entropy-coded bitstream

    // stbi__grow_buffer_unsafe
    private fun growBuffer(j: State) {
        do {
            val b = if (j.nomore) 0 else j.u8e()
            if (b == 0xFF) {
                var c = j.u8e()
                while (c == 0xFF) c = j.u8e()
                if (c != 0) {
                    j.marker = c
                    j.nomore = true
                    return
                }
            }
            if (j.nomore) j.padBits += 8
            j.codeBuffer = j.codeBuffer or (b shl (24 - j.codeBits))
            j.codeBits += 8
        } while (j.codeBits <= 24)
    }

    /**
     * True once the marker that ends the scan's data has been read and every bit left is padding.
     * The file holds no more data for this scan, so decoding on would only turn zero bits into noise.
     */
    private fun starved(j: State): Boolean = j.nomore && j.codeBits <= j.padBits

    // stbi__jpeg_huff_decode
    private fun huffDecode(j: State, h: Huffman): Int {
        if (j.codeBits < 16) growBuffer(j)

        val c = (j.codeBuffer ushr (32 - FAST_BITS)) and ((1 shl FAST_BITS) - 1)
        val k0 = h.fast[c].toInt() and 0xFF
        if (k0 < 255) {
            val s = h.size[k0].toInt()
            if (s > j.codeBits) return -1
            j.codeBuffer = j.codeBuffer shl s
            j.codeBits -= s
            return h.values[k0].toInt() and 0xFF
        }

        val temp = (j.codeBuffer ushr 16).toLong() and 0xFFFFL
        var k = FAST_BITS + 1
        while (true) {
            if (temp < h.maxcode[k]) break
            k++
            if (k > 17) break
        }
        if (k == 17) {
            j.codeBits -= 16
            return -1
        }
        if (k > j.codeBits) return -1

        val idx = ((j.codeBuffer ushr (32 - k)) and BMASK[k]) + h.delta[k]
        if (idx < 0 || idx >= 256) return -1
        j.codeBits -= k
        j.codeBuffer = j.codeBuffer shl k
        return h.values[idx].toInt() and 0xFF
    }

    private fun lrot(v: Int, n: Int): Int = (v shl n) or (v ushr (32 - n))

    // stbi__extend_receive
    private fun extendReceive(j: State, n: Int): Int {
        if (j.codeBits < n) growBuffer(j)
        if (j.codeBits < n) return 0
        val sgn = j.codeBuffer ushr 31
        var k = lrot(j.codeBuffer, n)
        j.codeBuffer = k and BMASK[n].inv()
        k = k and BMASK[n]
        j.codeBits -= n
        return k + (JBIAS[n] and (sgn - 1))
    }

    // stbi__jpeg_get_bits
    private fun getBits(j: State, n: Int): Int {
        if (j.codeBits < n) growBuffer(j)
        if (j.codeBits < n) return 0
        var k = lrot(j.codeBuffer, n)
        j.codeBuffer = k and BMASK[n].inv()
        k = k and BMASK[n]
        j.codeBits -= n
        return k
    }

    // stbi__jpeg_get_bit
    private fun getBit(j: State): Boolean {
        if (j.codeBits < 1) growBuffer(j)
        if (j.codeBits < 1) return false
        val k = j.codeBuffer
        j.codeBuffer = j.codeBuffer shl 1
        j.codeBits--
        return (k and Int.MIN_VALUE) != 0
    }

    // -------------------------------------------------------------------------
    // block decode + IDCT

    // stbi__jpeg_decode_block
    private fun decodeBlock(j: State, data: ShortArray, hdc: Huffman, hac: Huffman, fac: ShortArray, b: Int, dequant: IntArray) {
        if (j.codeBits < 16) growBuffer(j)
        val t = huffDecode(j, hdc)
        if (t < 0 || t > 15) err("bad huffman code")

        data.fill(0)

        val diff = if (t != 0) extendReceive(j, t) else 0
        val dc = j.comp[b].dcPred + diff
        if (dc < -32768 * 256 || dc > 32767 * 256) err("bad delta")
        j.comp[b].dcPred = dc
        val dcVal = dc * dequant[0]
        if (dcVal < Short.MIN_VALUE.toInt() || dcVal > Short.MAX_VALUE.toInt()) err("can't merge dc and ac")
        data[0] = dcVal.toShort()

        var k = 1
        do {
            if (j.codeBits < 16) growBuffer(j)
            val c = (j.codeBuffer ushr (32 - FAST_BITS)) and ((1 shl FAST_BITS) - 1)
            val r = fac[c].toInt()   // sign-extends
            if (r != 0) {   // fast-AC path
                k += (r shr 4) and 15
                val s = r and 15
                if (s > j.codeBits) err("bad huffman code")
                j.codeBuffer = j.codeBuffer shl s
                j.codeBits -= s
                val zig = DEZIGZAG[k++]
                data[zig] = ((r shr 8) * dequant[zig]).toShort()
            } else {
                val rs = huffDecode(j, hac)
                if (rs < 0) err("bad huffman code")
                val s = rs and 15
                val run = rs shr 4
                if (s == 0) {
                    if (rs != 0xF0) break   // end of block
                    k += 16
                } else {
                    k += run
                    val zig = DEZIGZAG[k++]
                    data[zig] = (extendReceive(j, s) * dequant[zig]).toShort()
                }
            }
        } while (k < 64)
    }

    // stbi__jpeg_decode_block_prog_dc
    private fun decodeBlockProgDc(j: State, data: ShortArray, dataOfs: Int, hdc: Huffman, b: Int) {
        if (j.specEnd != 0) err("can't merge dc and ac")
        if (j.codeBits < 16) growBuffer(j)

        if (j.succHigh == 0) {
            // first DC scan
            data.fill(0, dataOfs, dataOfs + 64)
            val t = huffDecode(j, hdc)
            if (t < 0 || t > 15) err("can't merge dc and ac")
            val diff = if (t != 0) extendReceive(j, t) else 0
            val dc = j.comp[b].dcPred + diff
            j.comp[b].dcPred = dc
            val v = dc * (1 shl j.succLow)
            if (v < Short.MIN_VALUE.toInt() || v > Short.MAX_VALUE.toInt()) err("can't merge dc and ac")
            data[dataOfs] = v.toShort()
        } else {
            // DC refinement: one bit
            if (getBit(j)) {
                data[dataOfs] = (data[dataOfs] + (1 shl j.succLow)).toShort()
            }
        }
    }

    // stbi__jpeg_decode_block_prog_ac
    private fun decodeBlockProgAc(j: State, data: ShortArray, dataOfs: Int, hac: Huffman, fac: ShortArray, last: ByteArray) {
        if (j.specStart == 0) err("can't merge dc and ac")
        val block = dataOfs shr 6

        if (j.succHigh == 0) {
            // first AC scan for this spectral band
            val shift = j.succLow
            if (j.eobRun != 0) {
                j.eobRun--
                return
            }
            var k = j.specStart
            var top = last[block].toInt()
            do {
                if (j.codeBits < 16) growBuffer(j)
                val c = (j.codeBuffer ushr (32 - FAST_BITS)) and ((1 shl FAST_BITS) - 1)
                val r = fac[c].toInt()
                if (r != 0) {   // fast-AC path
                    k += (r shr 4) and 15
                    val s = r and 15
                    if (s > j.codeBits) err("bad huffman code")
                    j.codeBuffer = j.codeBuffer shl s
                    j.codeBits -= s
                    if (k > top) top = minOf(k, 63)
                    val zig = DEZIGZAG[k++]
                    data[dataOfs + zig] = ((r shr 8) * (1 shl shift)).toShort()
                } else {
                    val rs = huffDecode(j, hac)
                    if (rs < 0) err("bad huffman code")
                    val s = rs and 15
                    val run = rs shr 4
                    if (s == 0) {
                        if (run < 15) {
                            j.eobRun = 1 shl run
                            if (run != 0) j.eobRun += getBits(j, run)
                            j.eobRun--
                            break
                        }
                        k += 16
                    } else {
                        k += run
                        if (k > top) top = minOf(k, 63)
                        val zig = DEZIGZAG[k++]
                        data[dataOfs + zig] = (extendReceive(j, s) * (1 shl shift)).toShort()
                    }
                }
            } while (k <= j.specEnd)
            last[block] = top.toByte()
        } else {
            // AC refinement
            val bit = (1 shl j.succLow).toShort()

            if (j.eobRun != 0) {
                j.eobRun--
                // Only a nonzero coefficient takes a correction bit, and none lies past last[block].
                for (k in j.specStart..minOf(j.specEnd, last[block].toInt())) {
                    val p = dataOfs + DEZIGZAG[k]
                    if (data[p].toInt() != 0 && getBit(j) && (data[p].toInt() and bit.toInt()) == 0) {
                        data[p] = if (data[p] > 0) {
                            (data[p] + bit).toShort()
                        } else {
                            (data[p] - bit).toShort()
                        }
                    }
                }
            } else {
                var k = j.specStart
                do {
                    val rs = huffDecode(j, hac)
                    if (rs < 0) err("bad huffman code")
                    var s = rs and 15
                    var r = rs shr 4
                    if (s == 0) {
                        if (r < 15) {
                            j.eobRun = (1 shl r) - 1
                            if (r != 0) j.eobRun += getBits(j, r)
                            r = 64   // force end of block
                        }
                        // r == 15, s == 0: run of 15 zeros then write s (0); nothing special
                    } else {
                        if (s != 1) err("bad huffman code")
                        s = if (getBit(j)) bit.toInt() else -bit.toInt()
                    }

                    // advance by r, refining existing nonzero coefficients on the way
                    while (k <= j.specEnd) {
                        val p = dataOfs + DEZIGZAG[k++]
                        if (data[p].toInt() != 0) {
                            if (getBit(j) && (data[p].toInt() and bit.toInt()) == 0) {
                                data[p] = if (data[p] > 0) {
                                    (data[p] + bit).toShort()
                                } else {
                                    (data[p] - bit).toShort()
                                }
                            }
                        } else {
                            if (r == 0) {
                                data[p] = s.toShort()
                                if (k - 1 > last[block]) last[block] = (k - 1).toByte()
                                break
                            }
                            r--
                        }
                    }
                } while (k <= j.specEnd)
            }
        }
    }

    private fun f2f(x: Double): Int = (x * 4096 + 0.5).toInt()

    private fun clamp(x: Int): Int = if (x < 0) 0 else if (x > 255) 255 else x

    /** The IDCT of block ([bx], [by]) of [comp] into its plane: the full one, or the reduced one of a scaled decode. */
    private fun idctInto(j: State, comp: Component, bx: Int, by: Int, data: ShortArray, tmp: IntArray) {
        val nh = 8 shr comp.scaleH
        val nv = 8 shr comp.scaleV
        val at = comp.ws * by * nv + bx * nh
        when {
            nh == 8 && nv == 8 -> idctBlock(comp.data, at, comp.ws, data, tmp)
            nh == nv -> reducedIdct(comp.data, at, comp.ws, data, nh, tmp)
            else -> reducedIdctRect(comp.data, at, comp.ws, data, nh, nv, tmp)
        }
    }

    /**
     * The reduced IDCT's table for [n] output points, in 1/8192ths: T(u, x) = C(u) times the mean
     * of cos((2p + 1) u pi / 16) over the 8 / n full-size samples p that output x stands for, with
     * C(0) = 1/sqrt(2) (ITU-T T.81, A.3.3). Row u holds the n values of frequency u. Derived here
     * rather than copied. [reducedIdct] explains where the formula comes from.
     */
    private fun reducedTable(n: Int): IntArray {
        val m = 8 / n
        return IntArray(8 * n) { i ->
            val u = i / n
            val x = i % n
            var sum = 0.0
            for (p in x * m until (x + 1) * m) sum += cos((2 * p + 1) * u * PI / 16)
            val c = if (u == 0) sqrt(0.5) else 1.0
            (c * sum / m * 8192).roundToInt()
        }
    }

    internal val REDUCED_8 = reducedTable(8)
    internal val REDUCED_4 = reducedTable(4)
    internal val REDUCED_2 = reducedTable(2)
    internal val REDUCED_1 = reducedTable(1)

    /**
     * The block that [data] holds, as [n] by [n] pixels, each the mean of the (8/n)-pixel square
     * of the full 8 by 8 IDCT it stands for. The full IDCT of T.81, A.3.3 is
     * f(p, q) = 1/4 sum over u, v of C(u) C(v) F(u, v) cos((2p+1)u pi/16) cos((2q+1)v pi/16),
     * so the mean over a square is the same sum with each cosine replaced by its mean over the
     * square's samples, which is the table T: g(x, y) = 1/4 sum over u, v of F(u, v) T(u, x) T(v, y).
     * Every frequency contributes, except those whose cosine averages to zero (4 at n = 4; 2, 4
     * and 6 at n = 2), as in libjpeg's jidctred.c, which derives its 4x4 and 2x2 IDCTs as the
     * averages of adjacent outputs of the full one. Keeping only the low n frequencies instead
     * left stripes where libjpeg gives a flat average (#62). For n = 1 the mean is the DC
     * coefficient over 8.
     */
    private fun reducedIdct(out: ByteArray, outOfs: Int, stride: Int, data: ShortArray, n: Int, rows: IntArray) {
        when (n) {
            1 -> out[outOfs] = clamp(128 + ((data[0].toInt() + 4) shr 3)).toByte()
            2 -> reducedIdct2(out, outOfs, stride, data, rows)
            else -> reducedIdct4(out, outOfs, stride, data, rows)
        }
    }

    // The table's entries as plain fields, for the two transforms below. An even frequency has
    // the same mean over output x and its mirror n - 1 - x, and an odd one the negated mean, so
    // each pass sums the even and the odd frequencies for the first half of the outputs and takes
    // the second half as their difference. A frequency whose row of the table is zero is left out:
    // 4 at n = 4, and 2, 4 and 6 at n = 2.
    private val R4 = IntArray(16) { REDUCED_4[(it / 2) * 4 + it % 2] }
    private val R2 = IntArray(8) { REDUCED_2[it * 2] }

    /**
     * [reducedIdct] at n = 4. The first pass descales by 2^11 and so keeps 2 bits above the
     * integer, as libjpeg's PASS1_BITS does; the second takes off 2^13 from the table, the 2^2
     * kept and the 1/4 of the IDCT, 2^17 in all, rounded half up.
     */
    private fun reducedIdct4(out: ByteArray, outOfs: Int, stride: Int, data: ShortArray, rows: IntArray) {
        val t = R4
        val a0 = t[0]; val a1 = t[1]; val b0 = t[2]; val b1 = t[3]; val c0 = t[4]; val c1 = t[5]
        val d0 = t[6]; val d1 = t[7]; val f0 = t[10]; val f1 = t[11]; val g0 = t[12]; val g1 = t[13]
        val h0 = t[14]; val h1 = t[15]
        for (v in 0 until 8) {
            val k = v * 8
            val r = v * 4
            val s0 = data[k].toInt(); val s1 = data[k + 1].toInt(); val s2 = data[k + 2].toInt(); val s3 = data[k + 3].toInt()
            val s5 = data[k + 5].toInt(); val s6 = data[k + 6].toInt(); val s7 = data[k + 7].toInt()
            if ((s1 or s2 or s3 or s5 or s6 or s7) == 0) {
                val dc = (s0 * a0 + (1 shl 10)) shr 11
                rows[r] = dc; rows[r + 1] = dc; rows[r + 2] = dc; rows[r + 3] = dc
                continue
            }
            val e0 = s0 * a0 + s2 * c0 + s6 * g0
            val e1 = s0 * a1 + s2 * c1 + s6 * g1
            val o0 = s1 * b0 + s3 * d0 + s5 * f0 + s7 * h0
            val o1 = s1 * b1 + s3 * d1 + s5 * f1 + s7 * h1
            rows[r] = (e0 + o0 + (1 shl 10)) shr 11
            rows[r + 1] = (e1 + o1 + (1 shl 10)) shr 11
            rows[r + 2] = (e1 - o1 + (1 shl 10)) shr 11
            rows[r + 3] = (e0 - o0 + (1 shl 10)) shr 11
        }
        for (x in 0 until 4) {
            val s0 = rows[x]; val s1 = rows[4 + x]; val s2 = rows[8 + x]; val s3 = rows[12 + x]
            val s5 = rows[20 + x]; val s6 = rows[24 + x]; val s7 = rows[28 + x]
            val e0 = s0 * a0 + s2 * c0 + s6 * g0 + (1 shl 16)
            val e1 = s0 * a1 + s2 * c1 + s6 * g1 + (1 shl 16)
            val o0 = s1 * b0 + s3 * d0 + s5 * f0 + s7 * h0
            val o1 = s1 * b1 + s3 * d1 + s5 * f1 + s7 * h1
            out[outOfs + x] = clamp(128 + ((e0 + o0) shr 17)).toByte()
            out[outOfs + stride + x] = clamp(128 + ((e1 + o1) shr 17)).toByte()
            out[outOfs + 2 * stride + x] = clamp(128 + ((e1 - o1) shr 17)).toByte()
            out[outOfs + 3 * stride + x] = clamp(128 + ((e0 - o0) shr 17)).toByte()
        }
    }

    /** [reducedIdct] at n = 2, scaled as [reducedIdct4]. */
    private fun reducedIdct2(out: ByteArray, outOfs: Int, stride: Int, data: ShortArray, rows: IntArray) {
        val t = R2
        val a = t[0]; val b = t[1]; val d = t[3]; val f = t[5]; val h = t[7]
        for (v in 0 until 8) {
            val k = v * 8
            val e = data[k] * a
            val o = data[k + 1] * b + data[k + 3] * d + data[k + 5] * f + data[k + 7] * h
            rows[v * 2] = (e + o + (1 shl 10)) shr 11
            rows[v * 2 + 1] = (e - o + (1 shl 10)) shr 11
        }
        for (x in 0 until 2) {
            val e = rows[x] * a + (1 shl 16)
            val o = rows[2 + x] * b + rows[6 + x] * d + rows[10 + x] * f + rows[14 + x] * h
            out[outOfs + x] = clamp(128 + ((e + o) shr 17)).toByte()
            out[outOfs + stride + x] = clamp(128 + ((e - o) shr 17)).toByte()
        }
    }

    /**
     * [reducedIdct] with [nh] points across and [nv] down, for a component whose two sampling
     * ratios differ, so it reduces by a different power of two each way. The first pass runs
     * the smaller transform: down each column when [nh] is larger, else across each row, so a
     * 4:2:2 block at a half takes eight 4-point columns and four 8-point rows rather than eight
     * 8-point rows. It descales as [reducedIdct4] descales. Only the lines of coefficients in
     * [LIVE_ROWS] go through it: any other frequency averages to zero over every output of the
     * second pass, so [reduce1d] never reads its line. A line with no coefficient but its first
     * is a constant. [scratch] holds the first pass's 8 lines and, in its last 8 entries, one
     * line of the second.
     */
    private fun reducedIdctRect(out: ByteArray, outOfs: Int, stride: Int, data: ShortArray, nh: Int, nv: Int, scratch: IntArray) {
        val columnsFirst = nh > nv
        // The first pass reduces to n1 points along lines that step by `along` in the block and
        // lie `across` apart; the second reduces each of its results to n2 points.
        val n1 = if (columnsFirst) nv else nh
        val n2 = if (columnsFirst) nh else nv
        val along = if (columnsFirst) 8 else 1
        val across = if (columnsFirst) 1 else 8
        for (line in LIVE_ROWS[n2.countTrailingZeroBits()]) {
            val k = line * across
            val r = line * n1
            val s0 = data[k].toInt(); val s1 = data[k + along].toInt(); val s2 = data[k + 2 * along].toInt()
            val s3 = data[k + 3 * along].toInt(); val s4 = data[k + 4 * along].toInt(); val s5 = data[k + 5 * along].toInt()
            val s6 = data[k + 6 * along].toInt(); val s7 = data[k + 7 * along].toInt()
            if ((s1 or s2 or s3 or s4 or s5 or s6 or s7) == 0) {
                // The DC term's mean is C(0) = T1 at every output.
                val dc = (s0 * T1 + (1 shl 10)) shr 11
                for (i in 0 until n1) scratch[r + i] = dc
                continue
            }
            reduce1d(n1, s0, s1, s2, s3, s4, s5, s6, s7, scratch, r)
            for (i in 0 until n1) scratch[r + i] = (scratch[r + i] + (1 shl 10)) shr 11
        }
        for (i in 0 until n1) {
            reduce1d(
                n2, scratch[i], scratch[n1 + i], scratch[2 * n1 + i], scratch[3 * n1 + i],
                scratch[4 * n1 + i], scratch[5 * n1 + i], scratch[6 * n1 + i], scratch[7 * n1 + i], scratch, 64,
            )
            // Line i of the second pass is row i of the output when the columns went first.
            for (j in 0 until n2) {
                val at = if (columnsFirst) outOfs + i * stride + j else outOfs + j * stride + i
                out[at] = clamp(128 + ((scratch[64 + j] + (1 shl 16)) shr 17)).toByte()
            }
        }
    }

    /** For each n of 1, 2, 4 and 8, by its log2: the frequencies with a nonzero mean over some output. */
    private val LIVE_ROWS = Array(4) { log ->
        val n = 1 shl log
        val table = when (n) {
            1 -> REDUCED_1
            2 -> REDUCED_2
            4 -> REDUCED_4
            else -> REDUCED_8
        }
        (0 until 8).filter { v -> (0 until n).any { y -> table[v * n + y] != 0 } }.toIntArray()
    }

    // The tables' entries as plain fields for [reduce1d]: T8 is the full 8-point basis, T1 only
    // the DC term. The even frequencies of each are kept for the first half of the outputs, then
    // the odd ones.
    private val T8 = IntArray(32) { i ->
        val u = if (i < 16) i / 4 * 2 else (i - 16) / 4 * 2 + 1
        REDUCED_8[u * 8 + i % 4]
    }
    private val T1 = REDUCED_1[0]

    /**
     * The [n]-point reduced transform of one row or column s0..s7 into out[at until at + n]:
     * the sum over u of s_u T(u, x), not yet descaled. Even frequencies have the same mean over
     * output x and its mirror n - 1 - x and odd ones the negated mean, so each half of the outputs
     * is the sum and the difference of the two. A frequency whose table row is zero is left out.
     */
    private fun reduce1d(n: Int, s0: Int, s1: Int, s2: Int, s3: Int, s4: Int, s5: Int, s6: Int, s7: Int, out: IntArray, at: Int) {
        when (n) {
            8 -> {
                val t = T8
                for (x in 0 until 4) {
                    val e = s0 * t[x] + s2 * t[4 + x] + s4 * t[8 + x] + s6 * t[12 + x]
                    val o = s1 * t[16 + x] + s3 * t[20 + x] + s5 * t[24 + x] + s7 * t[28 + x]
                    out[at + x] = e + o
                    out[at + 7 - x] = e - o
                }
            }
            4 -> {
                val t = R4
                val e0 = s0 * t[0] + s2 * t[4] + s6 * t[12]
                val e1 = s0 * t[1] + s2 * t[5] + s6 * t[13]
                val o0 = s1 * t[2] + s3 * t[6] + s5 * t[10] + s7 * t[14]
                val o1 = s1 * t[3] + s3 * t[7] + s5 * t[11] + s7 * t[15]
                out[at] = e0 + o0
                out[at + 1] = e1 + o1
                out[at + 2] = e1 - o1
                out[at + 3] = e0 - o0
            }
            2 -> {
                val t = R2
                val e = s0 * t[0]
                val o = s1 * t[1] + s3 * t[3] + s5 * t[5] + s7 * t[7]
                out[at] = e + o
                out[at + 1] = e - o
            }
            else -> out[at] = s0 * T1
        }
    }

    // stbi__idct_block (scalar). `tmp` is the 64-int intermediate.
    private fun idctBlock(out: ByteArray, outOfs: Int, outStride: Int, data: ShortArray, tmp: IntArray) {
        // columns
        for (i in 0 until 8) {
            if (data[i + 8].toInt() == 0 && data[i + 16].toInt() == 0 && data[i + 24].toInt() == 0 &&
                data[i + 32].toInt() == 0 && data[i + 40].toInt() == 0 && data[i + 48].toInt() == 0 &&
                data[i + 56].toInt() == 0
            ) {
                val dcterm = data[i].toInt() * 4
                tmp[i] = dcterm; tmp[i + 8] = dcterm; tmp[i + 16] = dcterm; tmp[i + 24] = dcterm
                tmp[i + 32] = dcterm; tmp[i + 40] = dcterm; tmp[i + 48] = dcterm; tmp[i + 56] = dcterm
            } else {
                idct1d(
                    data[i].toInt(), data[i + 8].toInt(), data[i + 16].toInt(), data[i + 24].toInt(),
                    data[i + 32].toInt(), data[i + 40].toInt(), data[i + 48].toInt(), data[i + 56].toInt(),
                ) { x0, x1, x2, x3, t0, t1, t2, t3 ->
                    val y0 = x0 + 512; val y1 = x1 + 512; val y2 = x2 + 512; val y3 = x3 + 512
                    tmp[i] = (y0 + t3) shr 10
                    tmp[i + 56] = (y0 - t3) shr 10
                    tmp[i + 8] = (y1 + t2) shr 10
                    tmp[i + 48] = (y1 - t2) shr 10
                    tmp[i + 16] = (y2 + t1) shr 10
                    tmp[i + 40] = (y2 - t1) shr 10
                    tmp[i + 24] = (y3 + t0) shr 10
                    tmp[i + 32] = (y3 - t0) shr 10
                }
            }
        }
        // rows
        var o = outOfs
        var v = 0
        for (i in 0 until 8) {
            idct1d(
                tmp[v], tmp[v + 1], tmp[v + 2], tmp[v + 3], tmp[v + 4], tmp[v + 5], tmp[v + 6], tmp[v + 7],
            ) { x0, x1, x2, x3, t0, t1, t2, t3 ->
                val y0 = x0 + 65536 + (128 shl 17); val y1 = x1 + 65536 + (128 shl 17)
                val y2 = x2 + 65536 + (128 shl 17); val y3 = x3 + 65536 + (128 shl 17)
                out[o] = clamp((y0 + t3) shr 17).toByte()
                out[o + 7] = clamp((y0 - t3) shr 17).toByte()
                out[o + 1] = clamp((y1 + t2) shr 17).toByte()
                out[o + 6] = clamp((y1 - t2) shr 17).toByte()
                out[o + 2] = clamp((y2 + t1) shr 17).toByte()
                out[o + 5] = clamp((y2 - t1) shr 17).toByte()
                out[o + 3] = clamp((y3 + t0) shr 17).toByte()
                out[o + 4] = clamp((y3 - t0) shr 17).toByte()
            }
            v += 8
            o += outStride
        }
    }

    // STBI__IDCT_1D
    private inline fun idct1d(
        s0: Int, s1: Int, s2: Int, s3: Int, s4: Int, s5: Int, s6: Int, s7: Int,
        emit: (x0: Int, x1: Int, x2: Int, x3: Int, t0: Int, t1: Int, t2: Int, t3: Int) -> Unit,
    ) {
        var p2 = s2
        var p3 = s6
        var p1 = (p2 + p3) * f2f(0.5411961)
        var t2 = p1 + p3 * f2f(-1.847759065)
        var t3 = p1 + p2 * f2f(0.765366865)
        p2 = s0
        p3 = s4
        var t0 = (p2 + p3) * 4096
        var t1 = (p2 - p3) * 4096
        val x0 = t0 + t3
        val x3 = t0 - t3
        val x1 = t1 + t2
        val x2 = t1 - t2
        t0 = s7
        t1 = s5
        t2 = s3
        t3 = s1
        p3 = t0 + t2
        var p4 = t1 + t3
        p1 = t0 + t3
        p2 = t1 + t2
        val p5 = (p3 + p4) * f2f(1.175875602)
        t0 *= f2f(0.298631336)
        t1 *= f2f(2.053119869)
        t2 *= f2f(3.072711026)
        t3 *= f2f(1.501321110)
        p1 = p5 + p1 * f2f(-0.899976223)
        p2 = p5 + p2 * f2f(-2.562915447)
        p3 *= f2f(-1.961570560)
        p4 *= f2f(-0.390180644)
        t3 += p1 + p4
        t2 += p2 + p3
        t1 += p2 + p4
        t0 += p1 + p3
        emit(x0, x1, x2, x3, t0, t1, t2, t3)
    }

    // -------------------------------------------------------------------------
    // markers + headers

    // stbi__get_marker
    private fun getMarker(j: State): Int {
        if (j.marker != MARKER_NONE) {
            val m = j.marker
            j.marker = MARKER_NONE
            return m
        }
        var x = j.u8e()
        if (x != 0xFF) return MARKER_NONE
        while (x == 0xFF) x = j.u8e()
        return x
    }

    // stbi__jpeg_reset
    private fun reset(j: State) {
        j.codeBits = 0
        j.codeBuffer = 0
        j.nomore = false
        j.padBits = 0
        for (c in j.comp) c.dcPred = 0
        j.marker = MARKER_NONE
        j.todo = if (j.restartInterval != 0) j.restartInterval else Int.MAX_VALUE
        j.eobRun = 0
    }

    private fun isRestart(m: Int) = m in 0xD0..0xD7

    // stbi__process_marker
    private fun processMarker(j: State, m: Int) {
        when {
            m == MARKER_NONE -> err("expected marker")

            m == 0xDD -> {   // DRI
                if (j.u16be() != 4) err("bad DRI len")
                j.restartInterval = j.u16be()
            }

            m == 0xDB -> {   // DQT
                var l = j.u16be() - 2
                while (l > 0) {
                    val q = j.u8()
                    val p = q shr 4
                    val t = q and 15
                    if (p != 0 && p != 1) err("bad DQT type")
                    if (t > 3) err("bad DQT table")
                    val sixteen = p != 0
                    val table = IntArray(64)
                    for (i in 0 until 64) {
                        table[DEZIGZAG[i]] = if (sixteen) j.u16be() else j.u8()
                    }
                    j.dequant[t] = table
                    l -= if (sixteen) 129 else 65
                }
                if (l != 0) err("bad DQT len")
            }

            m == 0xC4 -> {   // DHT
                var l = j.u16be() - 2
                while (l > 0) {
                    val q = j.u8()
                    val tc = q shr 4
                    val th = q and 15
                    if (tc > 1 || th > 3) err("bad DHT header")
                    val sizes = IntArray(16)
                    var n = 0
                    for (i in 0 until 16) {
                        sizes[i] = j.u8()
                        n += sizes[i]
                    }
                    if (n > 256) err("bad DHT header")
                    l -= 17
                    val h = if (tc == 0) j.huffDc[th] else j.huffAc[th]
                    h.build(sizes)
                    for (i in 0 until n) h.values[i] = j.u8().toByte()
                    if (tc != 0) buildFastAc(j.fastAc[th], j.huffAc[th])
                    l -= n
                }
                if (l != 0) err("bad DHT len")
            }

            m == 0xCC -> {   // DAC, libjpeg's get_dac
                var l = j.u16be() - 2
                while (l > 0) {
                    val index = j.u8()
                    val v = j.u8()
                    l -= 2
                    when {
                        index >= 32 -> err("bad DAC table $index")
                        index >= 16 -> j.acK[index - 16] = v
                        else -> {
                            if ((v and 15) > (v shr 4)) err("bad DAC conditioning $v")
                            j.dcL[index] = v and 15
                            j.dcU[index] = v shr 4
                        }
                    }
                }
                if (l != 0) err("bad DAC len")
            }

            m == 0xFE || m in 0xE0..0xEF -> {   // COM / APPn
                var l = j.u16be()
                if (l < 2) err(if (m == 0xFE) "bad COM len" else "bad APP len")
                l -= 2
                if (m == 0xE0 && l >= 5) {   // JFIF
                    val tag = intArrayOf('J'.code, 'F'.code, 'I'.code, 'F'.code, 0)
                    var ok = true
                    for (t in tag) if (j.u8() != t) ok = false
                    l -= 5
                    if (ok) j.jfif = true
                } else if (m == 0xEE && l >= 12) {   // Adobe APP14
                    val tag = intArrayOf('A'.code, 'd'.code, 'o'.code, 'b'.code, 'e'.code, 0)
                    var ok = true
                    for (t in tag) if (j.u8() != t) ok = false
                    l -= 6
                    if (ok) {
                        j.u8()      // version
                        j.u16be()   // flags0
                        j.u16be()   // flags1
                        j.app14ColorTransform = j.u8()
                        l -= 6
                    }
                }
                j.skip(l)
            }

            else -> err("unknown marker 0x${m.toString(16)}")
        }
    }

    /**
     * Why this decoder refuses a frame of type [marker] with these fields, or null when it decodes
     * it: one list that [processFrameHeader] throws from and that ImageProbe reports, so the two
     * cannot drift apart (#70). [factors] holds each component's sampling factors as the frame
     * header stores them, H in the high nibble and V in the low one. T.81 allows 1 to 255
     * components and any factor from 1 to 4, so each of these is a feature this decoder lacks, not
     * a fault in the file. A factor outside 1 to 4 is a fault, which [processFrameHeader] reports.
     */
    internal fun unsupportedFrame(marker: Int, precision: Int, height: Int, factors: IntArray): String? {
        when (marker) {
            0xC0, 0xC1, 0xC2, 0xC9, 0xCA -> {}
            0xC3, 0xCB -> return "lossless JPEG"
            0xC5, 0xC6, 0xC7, 0xCD, 0xCE, 0xCF -> return "hierarchical/differential JPEG"
            else -> return "JPEG SOF marker 0x${marker.toString(16)}"
        }
        if (precision != 8) return "$precision-bit JPEG (8-bit samples only)"
        if (height == 0) return "JPEG with its height deferred to a DNL marker"
        val n = factors.size
        if (n != 1 && n != 3 && n != 4) return "$n-component JPEG"
        if (factors.any { (it shr 4) !in 1..4 || (it and 15) !in 1..4 }) return null
        // libjpeg refuses them too (JERR_FRACT_SAMPLE_NOTIMPL in jdsample.c): an upsampling by a
        // fraction.
        val hMax = factors.maxOf { it shr 4 }
        val vMax = factors.maxOf { it and 15 }
        if (factors.any { hMax % (it shr 4) != 0 || vMax % (it and 15) != 0 }) {
            return "JPEG with $n components sampled ${factors.joinToString { "${it shr 4}x${it and 15}" }} (factors must divide the largest)"
        }
        return null
    }

    // stbi__process_frame_header (scan == STBI__SCAN_load), for any SOF [marker]
    private fun processFrameHeader(j: State, marker: Int) {
        val lf = j.u16be()
        if (lf < 11) err("bad SOF len")
        val precision = j.u8()
        j.imgY = j.u16be()
        j.imgX = j.u16be()
        val c = j.u8()
        if (lf != 8 + 3 * c) err("bad SOF len")
        val ids = IntArray(c)
        val factors = IntArray(c)
        val tables = IntArray(c)
        for (i in 0 until c) {
            ids[i] = j.u8()
            factors[i] = j.u8()
            tables[i] = j.u8()
        }
        unsupportedFrame(marker, precision, j.imgY, factors)?.let { throw UnsupportedImageException("$it is not supported") }

        if (j.imgX == 0) err("zero width")
        if (j.imgX > MAX_DIMENSION || j.imgY > MAX_DIMENSION || j.imgX.toLong() * j.imgY > MAX_PIXELS) {
            err("${j.imgX}x${j.imgY} exceeds safety limits")
        }
        if (!Budget.fits(j.imgX, j.imgY, j.input.size)) {
            err("${j.imgX}x${j.imgY} cannot come from ${j.input.size} bytes")
        }
        j.imgN = c

        j.rgb = 0
        val rgbIds = intArrayOf('R'.code, 'G'.code, 'B'.code)
        for (i in 0 until c) {
            val comp = j.comp[i]
            comp.id = ids[i]
            if (c == 3 && comp.id == rgbIds[i]) j.rgb++
            comp.h = factors[i] shr 4
            if (comp.h == 0 || comp.h > 4) err("bad H")
            comp.v = factors[i] and 15
            if (comp.v == 0 || comp.v > 4) err("bad V")
            comp.tq = tables[i]
            if (comp.tq > 3) err("bad TQ")
        }

        var hMax = 1
        var vMax = 1
        for (i in 0 until c) {
            if (j.comp[i].h > hMax) hMax = j.comp[i].h
            if (j.comp[i].v > vMax) vMax = j.comp[i].v
        }
        j.hMax = hMax
        j.vMax = vMax
        val mcuW = hMax * 8
        val mcuH = vMax * 8
        j.mcuX = (j.imgX + mcuW - 1) / mcuW
        j.mcuY = (j.imgY + mcuH - 1) / mcuH

        for (i in 0 until c) {
            val comp = j.comp[i]
            comp.x = (j.imgX * comp.h + hMax - 1) / hMax
            comp.y = (j.imgY * comp.v + vMax - 1) / vMax
            comp.w2 = j.mcuX * comp.h * 8
            comp.h2 = j.mcuY * comp.v * 8
            // A reduced decode keeps each block at 8 >> scale pixels a side (w2 and h2 are multiples
            // of 8). A subsampled component reduces less, across and down each by the power of two
            // its ratio that way holds, as libjpeg 9's jdmaster.c sizes DCT_h_scaled_size and
            // DCT_v_scaled_size apart: its upsampling then happens in the IDCT, as an exact average,
            // and only a ratio beyond the reduction or one of 3 is left to the upsampler.
            // libjpeg-turbo keeps one size for both ways, so a 4x2 or 4:2:2 file lost the chroma
            // detail of its finer direction and was upsampled back (#63).
            val hs = hMax / comp.h
            val vs = vMax / comp.v
            comp.scaleH = maxOf(0, j.scale - hs.countTrailingZeroBits())
            comp.scaleV = maxOf(0, j.scale - vs.countTrailingZeroBits())
            comp.ws = comp.w2 shr comp.scaleH
            comp.ys = (comp.y + (1 shl comp.scaleV) - 1) shr comp.scaleV
            // A block the file never reaches stays mid-gray, as in libjpeg, not black.
            comp.data = ByteArray(comp.ws * (comp.h2 shr comp.scaleV)).also { it.fill(0x80.toByte()) }
            if (j.progressive) {
                // w2/h2 are multiples of 8; one 64-short block per 8x8 tile
                comp.coeffW = comp.w2 / 8
                comp.coeff = ShortArray(comp.w2 * comp.h2)
                comp.lastNonzero = ByteArray(comp.coeffW * (comp.h2 / 8))
            }
        }
    }

    // stbi__process_scan_header
    private fun processScanHeader(j: State) {
        val ls = j.u16be()
        j.scanN = j.u8()
        if (j.scanN < 1 || j.scanN > 4 || j.scanN > j.imgN) err("bad SOS component count")
        if (ls != 6 + 2 * j.scanN) err("bad SOS len")
        for (i in 0 until j.scanN) {
            val id = j.u8()
            val q = j.u8()
            var which = 0
            while (which < j.imgN) {
                if (j.comp[which].id == id) break
                which++
            }
            if (which == j.imgN) err("SOS component id $id not in frame")
            j.comp[which].hd = q shr 4
            if (j.comp[which].hd > 3) err("bad DC huff")
            j.comp[which].ha = q and 15
            if (j.comp[which].ha > 3) err("bad AC huff")
            j.order[i] = which
        }
        j.specStart = j.u8()
        j.specEnd = j.u8()
        val aa = j.u8()
        j.succHigh = aa shr 4
        j.succLow = aa and 15
        j.skipScan = false
        if (j.progressive) {
            if (j.specStart > 63 || j.specEnd > 63 || j.specStart > j.specEnd ||
                j.succHigh > 13 || j.succLow > 13
            ) err("bad SOS")
            // T.81, Annex G: a refinement scan lowers the bit position by exactly one. libjpeg's
            // start_pass_phuff_decoder refuses anything else as a bad progression.
            if (j.succHigh != 0 && j.succLow != j.succHigh - 1) {
                err("bad progression: a refinement from bit ${j.succHigh} to bit ${j.succLow}")
            }
            // It must also refine from the bit position the band's last scan stopped at. libjpeg
            // only warns when it does not (JWRN_BOGUS_PROGRESSION) and applies the scan, but each
            // such scan walks the band again, so 500 of them over one band cost 500 passes (#67).
            // A refinement that breaks the progression is skipped instead, which leaves every
            // coefficient at most 13 refinements and no conforming file changed.
            for (i in 0 until j.scanN) {
                val record = j.comp[j.order[i]].approximation
                for (k in j.specStart..j.specEnd) {
                    if (j.succHigh != 0 && record[k] != j.succHigh) j.skipScan = true
                    // An arithmetic-coded scan costs a pass over the band even with no data, so a
                    // first scan of a band that already had one is passed over as well.
                    if (j.arithmetic && j.succHigh == 0 && record[k] != -1) j.skipScan = true
                }
            }
            if (!j.skipScan) {
                for (i in 0 until j.scanN) j.comp[j.order[i]].approximation.fill(j.succLow, j.specStart, j.specEnd + 1)
            }
        } else {
            if (j.specStart != 0) err("bad SOS")
            if (j.succHigh != 0 || j.succLow != 0) err("bad SOS")
            j.specEnd = 63
            // A sequential frame codes each component in one scan (T.81 B.2.3). An arithmetic-coded
            // scan costs a pass over its components even with no data, so a second one is passed over.
            if (j.arithmetic) {
                for (i in 0 until j.scanN) if (j.comp[j.order[i]].coded) j.skipScan = true
                if (!j.skipScan) for (i in 0 until j.scanN) j.comp[j.order[i]].coded = true
            }
        }
        // T.81 permits reusing a slot between components. Like libjpeg-turbo's
        // latch_quant_tables, keep the table from each component's first scan.
        for (i in 0 until j.scanN) {
            val comp = j.comp[j.order[i]]
            if (comp.quantization == null) {
                comp.quantization = j.dequant[comp.tq]?.copyOf()
                    ?: err("quantization table ${comp.tq} was not defined")
            }
        }
    }

    // stbi__parse_entropy_coded_data
    private fun parseEntropyCodedData(j: State) {
        if (j.arithmetic) {
            parseArithmeticScan(j)
            return
        }
        reset(j)
        if (j.progressive) {
            parseProgressiveScan(j)
            return
        }
        val data = ShortArray(64)
        val tmp = IntArray(IDCT_SCRATCH)
        if (j.scanN == 1) {
            // non-interleaved: one block at a time in scanline order
            val n = j.order[0]
            val comp = j.comp[n]
            val w = (comp.x + 7) shr 3
            val h = (comp.y + 7) shr 3
            for (jj in 0 until h) {
                for (i in 0 until w) {
                    // Without a restart interval nothing can follow the end of the data.
                    val live = !starved(j)
                    if (!live && j.restartInterval == 0) return
                    if (live) {
                        decodeBlock(j, data, j.huffDc[comp.hd], j.huffAc[comp.ha], j.fastAc[comp.ha], n, comp.quantization!!)
                        idctInto(j, comp, i, jj, data, tmp)
                    }
                    if (--j.todo <= 0) {
                        if (j.codeBits < 24) growBuffer(j)
                        if (!isRestart(j.marker)) return
                        reset(j)
                    }
                }
            }
        } else {
            // interleaved MCUs
            for (jj in 0 until j.mcuY) {
                for (i in 0 until j.mcuX) {
                    val live = !starved(j)
                    if (!live && j.restartInterval == 0) return
                    if (live) {
                        for (k in 0 until j.scanN) {
                            val n = j.order[k]
                            val comp = j.comp[n]
                            for (y in 0 until comp.v) {
                                for (x in 0 until comp.h) {
                                    decodeBlock(j, data, j.huffDc[comp.hd], j.huffAc[comp.ha], j.fastAc[comp.ha], n, comp.quantization!!)
                                    idctInto(j, comp, i * comp.h + x, jj * comp.v + y, data, tmp)
                                }
                            }
                        }
                    }
                    if (--j.todo <= 0) {
                        if (j.codeBits < 24) growBuffer(j)
                        if (!isRestart(j.marker)) return
                        reset(j)
                    }
                }
            }
        }
    }

    // stbi__parse_entropy_coded_data, progressive paths: coefficients only,
    // no IDCT here; that happens once at EOI in [finishProgressive].
    private fun parseProgressiveScan(j: State) {
        if (j.scanN == 1) {
            val n = j.order[0]
            val comp = j.comp[n]
            val coeff = comp.coeff!!
            val w = (comp.x + 7) shr 3
            val h = (comp.y + 7) shr 3
            for (jj in 0 until h) {
                for (i in 0 until w) {
                    // A block inside an EOB run reads no Huffman code, so it can be live with no data left.
                    val live = !starved(j) || j.eobRun > 0
                    if (!live && j.restartInterval == 0) return
                    if (live) {
                        val ofs = 64 * (i + jj * comp.coeffW)
                        if (j.specStart == 0) {
                            decodeBlockProgDc(j, coeff, ofs, j.huffDc[comp.hd], n)
                        } else {
                            decodeBlockProgAc(j, coeff, ofs, j.huffAc[comp.ha], j.fastAc[comp.ha], comp.lastNonzero!!)
                        }
                    }
                    if (--j.todo <= 0) {
                        if (j.codeBits < 24) growBuffer(j)
                        if (!isRestart(j.marker)) return
                        reset(j)
                    }
                }
            }
        } else {
            // interleaved progressive scans carry DC only
            for (jj in 0 until j.mcuY) {
                for (i in 0 until j.mcuX) {
                    val live = !starved(j)
                    if (!live && j.restartInterval == 0) return
                    if (live) {
                        for (k in 0 until j.scanN) {
                            val n = j.order[k]
                            val comp = j.comp[n]
                            val coeff = comp.coeff!!
                            for (y in 0 until comp.v) {
                                for (x in 0 until comp.h) {
                                    val x2 = i * comp.h + x        // block coords, not pixels
                                    val y2 = jj * comp.v + y
                                    decodeBlockProgDc(j, coeff, 64 * (x2 + y2 * comp.coeffW), j.huffDc[comp.hd], n)
                                }
                            }
                        }
                    }
                    if (--j.todo <= 0) {
                        if (j.codeBits < 24) growBuffer(j)
                        if (!isRestart(j.marker)) return
                        reset(j)
                    }
                }
            }
        }
    }

    // stbi__jpeg_finish: dequantize + IDCT every block of every component
    private fun finishProgressive(j: State) {
        val block = ShortArray(64)
        val tmp = IntArray(IDCT_SCRATCH)
        for (n in 0 until j.imgN) {
            val comp = j.comp[n]
            val coeff = comp.coeff ?: continue
            val dq = comp.quantization ?: continue
            val w = (comp.x + 7) shr 3
            val h = (comp.y + 7) shr 3
            for (jj in 0 until h) {
                for (i in 0 until w) {
                    val ofs = 64 * (i + jj * comp.coeffW)
                    for (k in 0 until 64) {   // stbi__jpeg_dequantize
                        block[k] = (coeff[ofs + k] * dq[k]).toShort()
                    }
                    idctInto(j, comp, i, jj, block, tmp)
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // arithmetic entropy decoding: T.81 Annex D's QM decoder with the models of F.2.4 and G.2,
    // laid out as libjpeg's jdarith.c lays them out

    // jdarith.c arith_decode: one binary decision, its estimate held in st[at]
    private fun arithDecode(j: State, st: ByteArray, at: Int): Int {
        // Renormalization and data input (T.81 D.2.6). A marker ends the data and the decoder reads
        // zeros from there on, which is how an arithmetic-coded segment may legally end.
        while (j.arA < 0x8000) {
            if (--j.arCt < 0) {
                var data = 0
                if (!j.nomore) {
                    if (j.eof) {
                        j.nomore = true
                    } else {
                        data = j.u8e()
                        if (data == 0xFF) {
                            var next = j.u8e()
                            while (next == 0xFF) next = j.u8e()
                            if (next == 0) {
                                data = 0xFF   // a stuffed zero
                            } else {
                                j.marker = next
                                j.nomore = true
                                data = 0
                            }
                        }
                    }
                }
                j.arC = (j.arC shl 8) or data
                j.arCt += 8
                // The first two bytes fill C, and A then starts at 0x10000.
                if (j.arCt < 0 && ++j.arCt == 0) j.arA = 0x8000
            }
            j.arA = j.arA shl 1
        }
        val sv = st[at].toInt() and 0xFF
        val entry = ARITAB[sv and 0x7F]
        val qe = entry ushr 16
        val nextMps = (entry ushr 8) and 0xFF
        val nextLps = entry and 0xFF
        var temp = j.arA - qe
        j.arA = temp
        temp = temp shl j.arCt
        // The decision, with the conditional exchanges of T.81 D.2.4 and D.2.5.
        if (j.arC >= temp) {
            j.arC -= temp
            if (j.arA < qe) {
                j.arA = qe
                st[at] = ((sv and 0x80) xor nextMps).toByte()
                return sv shr 7
            }
            j.arA = qe
            st[at] = ((sv and 0x80) xor nextLps).toByte()
            return (sv shr 7) xor 1
        }
        if (j.arA < 0x8000) {
            if (j.arA < qe) {
                st[at] = ((sv and 0x80) xor nextLps).toByte()
                return (sv shr 7) xor 1
            }
            st[at] = ((sv and 0x80) xor nextMps).toByte()
        }
        return sv shr 7
    }

    // jdarith.c start_pass and process_restart: fresh statistics for what the scan codes, fresh
    // predictions, and a decoder that reads its first two bytes again
    private fun arithReset(j: State) {
        for (i in 0 until j.scanN) {
            val comp = j.comp[j.order[i]]
            if (!j.progressive || (j.specStart == 0 && j.succHigh == 0)) {
                j.dcStats[comp.hd].fill(0)
                comp.dcPred = 0
                comp.dcContext = 0
            }
            if (!j.progressive || j.specStart != 0) j.acStats[comp.ha].fill(0)
        }
        j.arC = 0
        j.arA = 0
        j.arCt = -16
        j.arBroken = false
        j.nomore = false
        j.marker = MARKER_NONE
        j.todo = if (j.restartInterval != 0) j.restartInterval else Int.MAX_VALUE
    }

    /** The end of a restart interval: its RSTn marker and a fresh start, or false to end the scan. */
    private fun arithRestart(j: State): Boolean {
        if (--j.todo > 0) return true
        if (j.marker == MARKER_NONE) j.marker = skipJunkAtEnd(j)
        if (!isRestart(j.marker)) return false
        arithReset(j)
        return true
    }

    // T.81 F.2.4.1 (Figures F.19 and F.21 to F.24): the next DC difference of [comp], added to its
    // prediction, which wraps at 16 bits as libjpeg's last_dc_val does
    private fun arithDc(j: State, comp: Component) {
        val tbl = comp.hd
        val stats = j.dcStats[tbl]
        var st = comp.dcContext
        if (arithDecode(j, stats, st) == 0) {
            comp.dcContext = 0
            return
        }
        val sign = arithDecode(j, stats, st + 1)
        st += 2 + sign
        var m = arithDecode(j, stats, st)
        if (m != 0) {
            st = 20
            while (arithDecode(j, stats, st) != 0) {
                m = m shl 1
                if (m == 0x8000) {
                    j.arBroken = true
                    return
                }
                st++
            }
        }
        // The conditioning category of F.1.4.4.1.2, from the bounds of DAC.
        comp.dcContext = when {
            m < (1 shl j.dcL[tbl]) shr 1 -> 0
            m > (1 shl j.dcU[tbl]) shr 1 -> 12 + sign * 4
            else -> 4 + sign * 4
        }
        var v = m
        st += 14
        while (true) {
            m = m shr 1
            if (m == 0) break
            if (arithDecode(j, stats, st) != 0) v = v or m
        }
        v += 1
        if (sign != 0) v = -v
        comp.dcPred = (comp.dcPred + v) and 0xFFFF
    }

    // T.81 F.2.4.2 (Figure F.20): coefficients [start] to [end] of the block at [ofs], each times
    // 2^[shift], as jdarith.c's decode_mcu and decode_mcu_AC_first read them
    private fun arithAc(j: State, comp: Component, data: ShortArray, ofs: Int, start: Int, end: Int, shift: Int) {
        val tbl = comp.ha
        val stats = j.acStats[tbl]
        var k = start
        while (k <= end) {
            var st = 3 * (k - 1)
            if (arithDecode(j, stats, st) != 0) break   // end of block
            while (arithDecode(j, stats, st + 1) == 0) {
                st += 3
                if (++k > end) {
                    j.arBroken = true
                    return
                }
            }
            val sign = arithDecode(j, j.fixedBin, 0)
            st += 2
            var m = arithDecode(j, stats, st)
            if (m != 0 && arithDecode(j, stats, st) != 0) {
                m = m shl 1
                st = if (k <= j.acK[tbl]) 189 else 217
                while (arithDecode(j, stats, st) != 0) {
                    m = m shl 1
                    if (m == 0x8000) {
                        j.arBroken = true
                        return
                    }
                    st++
                }
            }
            var v = m
            st += 14
            while (true) {
                m = m shr 1
                if (m == 0) break
                if (arithDecode(j, stats, st) != 0) v = v or m
            }
            v += 1
            if (sign != 0) v = -v
            data[ofs + DEZIGZAG[k]] = (v shl shift).toShort()
            k++
        }
    }

    // jdarith.c decode_mcu_AC_refine (T.81 G.2): one more bit of every coefficient in the band
    private fun arithAcRefine(j: State, comp: Component, data: ShortArray, ofs: Int) {
        val stats = j.acStats[comp.ha]
        val p1 = 1 shl j.succLow
        val m1 = -1 shl j.succLow
        // The end of block of the scans before this one: past it, a coefficient can only be new.
        var kex = j.specEnd
        while (kex > 0 && data[ofs + DEZIGZAG[kex]].toInt() == 0) kex--
        var k = j.specStart
        while (k <= j.specEnd) {
            var st = 3 * (k - 1)
            if (k > kex && arithDecode(j, stats, st) != 0) break   // end of block
            while (true) {
                val p = ofs + DEZIGZAG[k]
                val coefficient = data[p].toInt()
                if (coefficient != 0) {
                    if (arithDecode(j, stats, st + 2) != 0) data[p] = (coefficient + if (coefficient < 0) m1 else p1).toShort()
                    break
                }
                if (arithDecode(j, stats, st + 1) != 0) {
                    data[p] = (if (arithDecode(j, j.fixedBin, 0) != 0) m1 else p1).toShort()
                    break
                }
                st += 3
                if (++k > j.specEnd) {
                    j.arBroken = true
                    return
                }
            }
            k++
        }
    }

    /**
     * One arithmetic-coded block of [comp] at block ([bx], [by]): a sequential one decoded,
     * dequantized and through the IDCT as [decodeBlock] and [idctInto] take a Huffman one, a
     * progressive one into the coefficients [finishProgressive] takes. After a code that cannot be
     * valid, the blocks up to the next restart keep what they hold, as in libjpeg.
     */
    private fun arithBlock(j: State, comp: Component, bx: Int, by: Int, data: ShortArray, tmp: IntArray) {
        if (!j.progressive) {
            data.fill(0)
            if (!j.arBroken) arithDc(j, comp)
            if (!j.arBroken) {
                data[0] = comp.dcPred.toShort()
                arithAc(j, comp, data, 0, 1, 63, 0)
            }
            val dequant = comp.quantization!!
            for (z in 0 until 64) if (data[z].toInt() != 0) data[z] = (data[z] * dequant[z]).toShort()
            idctInto(j, comp, bx, by, data, tmp)
            return
        }
        val coeff = comp.coeff!!
        val ofs = 64 * (bx + by * comp.coeffW)
        when {
            j.specStart == 0 && j.succHigh == 0 -> if (!j.arBroken) {
                arithDc(j, comp)
                if (!j.arBroken) coeff[ofs] = (comp.dcPred shl j.succLow).toShort()
            }
            // jdarith.c decode_mcu_DC_refine: the next bit of the two's complement DC value
            j.specStart == 0 -> if (arithDecode(j, j.fixedBin, 0) != 0) {
                coeff[ofs] = (coeff[ofs].toInt() or (1 shl j.succLow)).toShort()
            }
            j.succHigh == 0 -> if (!j.arBroken) arithAc(j, comp, coeff, ofs, j.specStart, j.specEnd, j.succLow)
            else -> if (!j.arBroken) arithAcRefine(j, comp, coeff, ofs)
        }
    }

    // The MCU loop of an arithmetic-coded scan, over the blocks and MCUs of a Huffman one.
    private fun parseArithmeticScan(j: State) {
        if (j.progressive && j.specStart != 0 && j.scanN != 1) err("an AC scan codes one component, not ${j.scanN}")
        arithReset(j)
        val data = ShortArray(64)
        val tmp = IntArray(IDCT_SCRATCH)
        if (j.scanN == 1) {
            val comp = j.comp[j.order[0]]
            val w = (comp.x + 7) shr 3
            val h = (comp.y + 7) shr 3
            for (by in 0 until h) {
                for (bx in 0 until w) {
                    arithBlock(j, comp, bx, by, data, tmp)
                    if (!arithRestart(j)) return
                }
            }
        } else {
            for (my in 0 until j.mcuY) {
                for (mx in 0 until j.mcuX) {
                    for (k in 0 until j.scanN) {
                        val comp = j.comp[j.order[k]]
                        for (y in 0 until comp.v) {
                            for (x in 0 until comp.h) arithBlock(j, comp, mx * comp.h + x, my * comp.v + y, data, tmp)
                        }
                    }
                    if (!arithRestart(j)) return
                }
            }
        }
    }

    /** Passes over the entropy-coded data of a skipped scan, its restart markers included, to the marker after it. */
    private fun skipScanData(j: State) {
        var m = skipJunkAtEnd(j)
        while (isRestart(m)) m = skipJunkAtEnd(j)
        j.marker = m
    }

    // stbi__skip_jpeg_junk_at_end
    private fun skipJunkAtEnd(j: State): Int {
        while (!j.eof) {
            var x = j.u8e()
            while (x == 0xFF) {
                if (j.eof) return MARKER_NONE
                x = j.u8e()
                if (x == 0x00) break            // stuffed zero, not a marker
                if (x == 0xFF) continue         // fill byte
                return x                        // real marker
            }
        }
        return MARKER_NONE
    }

    // -------------------------------------------------------------------------
    // resampling + color conversion

    private fun div4(x: Int) = (x shr 2) and 0xFF
    private fun div16(x: Int) = (x shr 4) and 0xFF

    // stbi__resample per component
    /**
     * [hs] and [vs] are the upsampling left after the component's own IDCT reduction. [filtered]
     * says whether the file's own ratios are ones stb interpolates, 2 or 1 each way; any other
     * ratio is replicated, so a reduced decode replicates what is left of it too, as the full
     * decode would, and stays its average.
     */
    private class Resampler(
        val comp: Component, val hs: Int, val vs: Int, imgX: Int, val stride: Int, val rows: Int, val filtered: Boolean,
    ) {
        val wLores = (imgX + hs - 1) / hs
        var ystep = vs shr 1
        var ypos = 0
        var line0 = 0                 // row offsets into comp.data
        var line1 = 0
        var outArr: ByteArray = comp.data
        var outOfs = 0
    }

    /** One row of upsampling for [r]; leaves the result in r.outArr/r.outOfs. */
    private fun resampleRow(r: Resampler) {
        val comp = r.comp
        val yBot = r.ystep >= (r.vs shr 1)
        val nearOfs = if (yBot) r.line1 else r.line0
        val farOfs = if (yBot) r.line0 else r.line1
        val inp = comp.data
        val out = comp.linebuf
        val w = r.wLores

        when {
            r.hs == 1 && r.vs == 1 -> {   // resample_row_1: no work, point at the row
                r.outArr = inp
                r.outOfs = nearOfs
            }
            r.filtered && r.hs == 1 && r.vs == 2 -> {   // stbi__resample_row_v_2
                for (i in 0 until w) {
                    out[i] = div4(3 * (inp[nearOfs + i].toInt() and 0xFF) + (inp[farOfs + i].toInt() and 0xFF) + 2).toByte()
                }
                r.outArr = out; r.outOfs = 0
            }
            r.filtered && r.hs == 2 && r.vs == 1 -> {   // stbi__resample_row_h_2, with its last pair corrected
                if (w == 1) {
                    out[0] = inp[nearOfs]; out[1] = inp[nearOfs]
                } else {
                    fun pix(i: Int) = inp[nearOfs + i].toInt() and 0xFF
                    out[0] = inp[nearOfs]
                    out[1] = div4(pix(0) * 3 + pix(1) + 2).toByte()
                    var i = 1
                    while (i < w - 1) {
                        val n = 3 * pix(i) + 2
                        out[i * 2] = div4(n + pix(i - 1)).toByte()
                        out[i * 2 + 1] = div4(n + pix(i + 1)).toByte()
                        i++
                    }
                    // Output 2(w-1) is the left half of input w-1, so w-1 is its near sample. stb weights
                    // w-2 by three here; libjpeg-turbo's h2v1_fancy_upsample weights w-1, as every
                    // other output of the row does (#69).
                    out[i * 2] = div4(pix(w - 1) * 3 + pix(w - 2) + 2).toByte()
                    out[i * 2 + 1] = inp[nearOfs + w - 1]
                }
                r.outArr = out; r.outOfs = 0
            }
            r.filtered && r.hs == 2 && r.vs == 2 -> {   // stbi__resample_row_hv_2
                fun near(i: Int) = inp[nearOfs + i].toInt() and 0xFF
                fun far(i: Int) = inp[farOfs + i].toInt() and 0xFF
                if (w == 1) {
                    val v = div4(3 * near(0) + far(0) + 2).toByte()
                    out[0] = v; out[1] = v
                } else {
                    var t1 = 3 * near(0) + far(0)
                    out[0] = div4(t1 + 2).toByte()
                    for (i in 1 until w) {
                        val t0 = t1
                        t1 = 3 * near(i) + far(i)
                        out[i * 2 - 1] = div16(3 * t0 + t1 + 8).toByte()
                        out[i * 2] = div16(3 * t1 + t0 + 8).toByte()
                    }
                    out[w * 2 - 1] = div4(t1 + 2).toByte()
                }
                r.outArr = out; r.outOfs = 0
            }
            else -> {   // stbi__resample_row_generic: nearest neighbor
                for (i in 0 until w) {
                    for (k in 0 until r.hs) {
                        out[i * r.hs + k] = inp[nearOfs + i]
                    }
                }
                r.outArr = out; r.outOfs = 0
            }
        }

        // advance vertical state (caller side of stb's loop)
        if (++r.ystep >= r.vs) {
            r.ystep = 0
            r.line0 = r.line1
            if (++r.ypos < r.rows) r.line1 += r.stride
        }
    }

    private fun float2fixed(x: Double): Int = ((x * 4096.0 + 0.5).toInt()) shl 8

    // Hoisted out of the per-pixel loop: the C originals are compile-time
    // constants, and not every Kotlin backend folds the calls away.
    private val FIX_1_40200 = float2fixed(1.40200)
    private val FIX_0_71414 = float2fixed(0.71414)
    private val FIX_0_34414 = float2fixed(0.34414)
    private val FIX_1_77200 = float2fixed(1.77200)

    // stbi__YCbCr_to_RGB_row (reduced-precision fixed point, bit-exact w/ stb)
    private fun ycbcrToRgbRow(out: IntArray, outOfs: Int, y: ByteArray, yOfs: Int, cb: ByteArray, cbOfs: Int, cr: ByteArray, crOfs: Int, count: Int) {
        for (i in 0 until count) {
            val yFixed = ((y[yOfs + i].toInt() and 0xFF) shl 20) + (1 shl 19)
            val cr0 = (cr[crOfs + i].toInt() and 0xFF) - 128
            val cb0 = (cb[cbOfs + i].toInt() and 0xFF) - 128
            var r = yFixed + cr0 * FIX_1_40200
            var g = yFixed + (cr0 * -FIX_0_71414) + ((cb0 * -FIX_0_34414) and 0xFFFF0000.toInt())
            var b = yFixed + cb0 * FIX_1_77200
            r = r shr 20; g = g shr 20; b = b shr 20
            r = clamp(r); g = clamp(g); b = clamp(b)
            out[outOfs + i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    // stbi__blinn_8x8
    private fun blinn(x: Int, y: Int): Int {
        val t = x * y + 128
        return (t + (t shr 8)) shr 8
    }

    // stbi__compute_y
    private fun computeY(r: Int, g: Int, b: Int): Int = (r * 77 + g * 150 + 29 * b) shr 8

    // -------------------------------------------------------------------------

    /** How [decode] turns a pixel's samples into RGB, chosen from the frame header and its markers. */
    internal enum class ColorModel { GRAY, YCBCR, RGB, CMYK, YCCK }

    /**
     * Decodes [input]. With [scale] above 0, each side comes out reduced by 2^scale, rounded
     * up, as libjpeg's scaled decode gives it: the IDCT itself shrinks, so the full-size image
     * never exists. [scale] is 0 to 3.
     */
    fun decode(input: ByteArray, scale: Int = 0): KiteBitmap {
        val j = decodeFrame(input, scale)
        val model = colorModel(j)
        val outX = outSize(j.imgX, scale)
        val argb = IntArray(outX * outSize(j.imgY, scale))
        forEachRow(j) { row, arrays, offsets -> convertRow(model, argb, row * outX, arrays, offsets, outX) }
        return KiteBitmap(outX, outSize(j.imgY, scale), argb)
    }

    /** What [decodeComponents] returns: the samples, and the color model [decode] would apply to them. */
    internal class Components(val components: JpegComponents, val model: ColorModel)

    /**
     * The samples of [input] after the IDCT and the upsampling that [decode] runs, before its
     * color conversion: every component of the frame, in the frame header's order, interleaved.
     */
    fun decodeComponents(input: ByteArray, scale: Int = 0): Components {
        val j = decodeFrame(input, scale)
        val n = j.imgN
        val outX = outSize(j.imgX, scale)
        // At most 2^28 pixels of 4 samples: the frame header's limit keeps this an Int.
        val samples = ByteArray(outX * outSize(j.imgY, scale) * n)
        forEachRow(j) { row, arrays, offsets ->
            var at = row * outX * n
            when (n) {
                1 -> arrays[0]!!.copyInto(samples, at, offsets[0], offsets[0] + outX)
                3 -> {
                    val a0 = arrays[0]!!; val a1 = arrays[1]!!; val a2 = arrays[2]!!
                    val o0 = offsets[0]; val o1 = offsets[1]; val o2 = offsets[2]
                    for (i in 0 until outX) {
                        samples[at] = a0[o0 + i]; samples[at + 1] = a1[o1 + i]; samples[at + 2] = a2[o2 + i]
                        at += 3
                    }
                }
                else -> {
                    val a0 = arrays[0]!!; val a1 = arrays[1]!!; val a2 = arrays[2]!!; val a3 = arrays[3]!!
                    val o0 = offsets[0]; val o1 = offsets[1]; val o2 = offsets[2]; val o3 = offsets[3]
                    for (i in 0 until outX) {
                        samples[at] = a0[o0 + i]; samples[at + 1] = a1[o1 + i]
                        samples[at + 2] = a2[o2 + i]; samples[at + 3] = a3[o3 + i]
                        at += 4
                    }
                }
            }
        }
        return Components(JpegComponents(outX, outSize(j.imgY, scale), n, samples, j.app14ColorTransform), colorModel(j))
    }

    /** One component as the inverse DCT leaves it: [width] by [height] samples in rows of [stride]. */
    internal class Plane(val samples: ByteArray, val stride: Int, val width: Int, val height: Int, val h: Int, val v: Int)

    /** Every component of a frame [width] by [height] pixels, in the frame header's order. */
    internal class Planes(val width: Int, val height: Int, val hMax: Int, val vMax: Int, val planes: List<Plane>)

    /**
     * The components of [input] at their own resolutions, before upsampling and color
     * conversion, as libjpeg's raw data output gives them: a chroma plane sampled 1 by 1
     * against luma sampled 2 by 2 comes back at half the size each way. The planes run on
     * to whole MCUs, so they hold at least the samples the image needs.
     */
    fun decodePlanes(input: ByteArray): Planes {
        val j = decodeFrame(input, 0)
        val planes = List(j.imgN) { k ->
            val c = j.comp[k]
            Plane(c.data, c.ws, c.x, c.y, c.h, c.v)
        }
        return Planes(j.imgX, j.imgY, j.hMax, j.vMax, planes)
    }

    /** [components] through the color conversion of [model], row by row as [decode] converts its rows. */
    internal fun toBitmap(components: JpegComponents, model: ColorModel): KiteBitmap {
        val w = components.width
        val n = components.componentCount
        val rows = Array(n) { ByteArray(w) }
        val arrays = arrayOfNulls<ByteArray>(4)
        for (k in 0 until n) arrays[k] = rows[k]
        val offsets = IntArray(4)
        val argb = IntArray(w * components.height)
        for (row in 0 until components.height) {
            for (i in 0 until w) for (k in 0 until n) rows[k][i] = components.samples[(row * w + i) * n + k]
            convertRow(model, argb, row * w, arrays, offsets, w)
        }
        return KiteBitmap(w, components.height, argb)
    }

    /** A side of [size] pixels reduced by 2^[scale], rounded up. */
    private fun outSize(size: Int, scale: Int): Int = (size + (1 shl scale) - 1) shr scale

    // stbi__decode_jpeg_header and stbi__decode_jpeg_image: every scan entropy-decoded and through
    // the IDCT, each component's plane at its own reduction.
    private fun decodeFrame(input: ByteArray, scale: Int): State {
        require(scale in 0..3) { "scale must be 0 to 3, was $scale" }
        val j = State(input)
        j.scale = scale

        // stbi__decode_jpeg_header: SOI, then markers until SOF
        if (j.u8() != 0xFF || j.u8() != 0xD8) err("no SOI")
        var m = getMarker(j)
        while (true) {
            when (m) {
                // SOF0 baseline, SOF1 extended sequential, SOF2 progressive, and the frame types
                // processFrameHeader refuses by name
                0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF -> break
                MARKER_NONE -> err("expected marker")
                else -> {
                    processMarker(j, m)
                    m = getMarker(j)
                }
            }
        }
        j.progressive = m == 0xC2 || m == 0xCA
        j.arithmetic = m == 0xC9 || m == 0xCA
        processFrameHeader(j, m)

        // stbi__decode_jpeg_image: scans until EOI
        m = getMarker(j)
        var sawScan = false
        var scans = 0
        while (m != 0xD9) {   // EOI
            when {
                m == 0xDA -> {   // SOS
                    if (++scans > MAX_SCANS) err("more than $MAX_SCANS scans")
                    processScanHeader(j)
                    if (j.skipScan) skipScanData(j) else parseEntropyCodedData(j)
                    sawScan = true
                    if (j.marker == MARKER_NONE) j.marker = skipJunkAtEnd(j)
                    m = getMarker(j)
                    if (isRestart(m)) m = getMarker(j)
                }
                m == 0xDC -> {   // DNL
                    val ld = j.u16be()
                    val nl = j.u16be()
                    if (ld != 4) err("bad DNL len")
                    if (nl != j.imgY) err("bad DNL height")
                    m = getMarker(j)
                }
                m == MARKER_NONE -> err("ran out of data before EOI")
                else -> {
                    processMarker(j, m)
                    m = getMarker(j)
                }
            }
        }
        if (!sawScan) err("no SOS scan before EOI")
        if (j.progressive) finishProgressive(j)

        return j
    }

    private fun colorModel(j: State): ColorModel = when (j.imgN) {
        1 -> ColorModel.GRAY
        3 -> if (j.rgb == 3 || (j.app14ColorTransform == 0 && !j.jfif)) ColorModel.RGB else ColorModel.YCBCR
        // libjpeg's default_decompress_parms: four components are CMYK under Adobe transform 0 or
        // with no Adobe marker, and YCCK under any other transform. The samples are Adobe's
        // inverted CMYK, as browsers and ImageMagick read every CMYK JPEG (#65).
        else -> if (j.app14ColorTransform == 0 || j.app14ColorTransform == -1) ColorModel.CMYK else ColorModel.YCCK
    }

    /**
     * Upsamples each component of the decoded frame [j] to the output size, one row at a time
     * (the resampling tail of stbi__load_jpeg_image), and hands [emit] each row: component k's
     * samples start at offsets[k] in arrays[k].
     */
    private inline fun forEachRow(j: State, emit: (row: Int, arrays: Array<ByteArray?>, offsets: IntArray) -> Unit) {
        val scale = j.scale
        val outX = outSize(j.imgX, scale)
        val outY = outSize(j.imgY, scale)
        val res = Array(j.imgN) { k ->
            val comp = j.comp[k]
            comp.linebuf = ByteArray(outX + 3)
            // The upsampling left after the component's own reduction: none for chroma that
            // decoded straight to the luma's resolution.
            val hs = j.hMax / comp.h
            val vs = j.vMax / comp.v
            Resampler(
                comp, hs shr (scale - comp.scaleH), vs shr (scale - comp.scaleV), outX,
                stride = comp.ws, rows = comp.ys, filtered = hs <= 2 && vs <= 2,
            )
        }
        val arrays = arrayOfNulls<ByteArray>(4)
        val offsets = IntArray(4)
        for (row in 0 until outY) {
            for (k in res.indices) {
                resampleRow(res[k])
                arrays[k] = res[k].outArr
                offsets[k] = res[k].outOfs
            }
            emit(row, arrays, offsets)
        }
    }

    /**
     * One row of [count] pixels in the color [model], from each component's samples at couOfs[k]
     * in couArr[k], into [argb] at [outOfs] (the color conversion of stbi__load_jpeg_image, with
     * opaque alpha).
     */
    private fun convertRow(model: ColorModel, argb: IntArray, outOfs: Int, couArr: Array<ByteArray?>, couOfs: IntArray, count: Int) {
        when (model) {
            ColorModel.RGB -> {
                val rA = couArr[0]!!; val gA = couArr[1]!!; val bA = couArr[2]!!
                for (i in 0 until count) {
                    argb[outOfs + i] = (0xFF shl 24) or
                        ((rA[couOfs[0] + i].toInt() and 0xFF) shl 16) or
                        ((gA[couOfs[1] + i].toInt() and 0xFF) shl 8) or
                        (bA[couOfs[2] + i].toInt() and 0xFF)
                }
            }
            ColorModel.YCBCR -> ycbcrToRgbRow(
                argb, outOfs,
                couArr[0]!!, couOfs[0], couArr[1]!!, couOfs[1], couArr[2]!!, couOfs[2], count,
            )
            ColorModel.CMYK -> {   // blinn multiply against K
                val cA = couArr[0]!!; val mA = couArr[1]!!; val yA = couArr[2]!!; val kA = couArr[3]!!
                for (i in 0 until count) {
                    val kk = kA[couOfs[3] + i].toInt() and 0xFF
                    val r = blinn(cA[couOfs[0] + i].toInt() and 0xFF, kk)
                    val g = blinn(mA[couOfs[1] + i].toInt() and 0xFF, kk)
                    val b = blinn(yA[couOfs[2] + i].toInt() and 0xFF, kk)
                    argb[outOfs + i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            ColorModel.YCCK -> {   // YCbCr, then invert + blinn against K
                val kA = couArr[3]!!
                ycbcrToRgbRow(argb, outOfs, couArr[0]!!, couOfs[0], couArr[1]!!, couOfs[1], couArr[2]!!, couOfs[2], count)
                for (i in 0 until count) {
                    val kk = kA[couOfs[3] + i].toInt() and 0xFF
                    val p = argb[outOfs + i]
                    val r = blinn(255 - ((p shr 16) and 0xFF), kk)
                    val g = blinn(255 - ((p shr 8) and 0xFF), kk)
                    val b = blinn(255 - (p and 0xFF), kk)
                    argb[outOfs + i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            ColorModel.GRAY -> {
                val yA = couArr[0]!!
                for (i in 0 until count) {
                    val g = yA[couOfs[0] + i].toInt() and 0xFF
                    argb[outOfs + i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
                }
            }
        }
    }
}
