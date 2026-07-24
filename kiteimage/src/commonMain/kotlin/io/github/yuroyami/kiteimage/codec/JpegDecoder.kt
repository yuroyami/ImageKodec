package io.github.yuroyami.kiteimage.codec

import io.github.yuroyami.kiteimage.ImageDecodeException
import io.github.yuroyami.kiteimage.KiteBitmap
import io.github.yuroyami.kiteimage.UnsupportedImageException
import io.github.yuroyami.kiteimage.internal.Budget

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
 *    CMYK and YCCK via the Adobe APP14 transform flag (4)
 *  - stb's fixed-point AAN-style IDCT and reduced-precision YCbCr→RGB, so
 *    output is bit-identical to stb_image on the same file
 *
 *  - progressive (SOF2): spectral selection + successive approximation, EOB
 *    runs, DC/AC refinement scans, deferred dequantize+IDCT at EOI
 *
 * Arithmetic coding, hierarchical and lossless JPEG are rejected like stb
 * rejects them (nobody writes them). EXIF orientation is metadata and
 * deliberately not applied.
 */
internal object JpegDecoder {

    private const val FAST_BITS = 9
    private const val MARKER_NONE = 0xFF

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
        var hd = 0; var ha = 0
        var dcPred = 0
        var x = 0; var y = 0
        var w2 = 0; var h2 = 0
        lateinit var data: ByteArray
        lateinit var linebuf: ByteArray
        // progressive only: raw coefficients, IDCT'd at EOI
        var coeff: ShortArray? = null
        var coeffW = 0
    }

    private class State(val input: ByteArray) {
        var pos = 0

        val huffDc = Array(4) { Huffman() }
        val huffAc = Array(4) { Huffman() }
        val dequant = Array(4) { IntArray(64) }
        val fastAc = Array(4) { ShortArray(1 shl FAST_BITS) }

        var imgX = 0; var imgY = 0; var imgN = 0
        var hMax = 1; var vMax = 1
        var mcuX = 0; var mcuY = 0
        val comp = Array(4) { Component() }

        var codeBuffer = 0
        var codeBits = 0
        var marker = MARKER_NONE
        var nomore = false

        var jfif = false
        var app14ColorTransform = -1
        var rgb = 0

        var scanN = 0
        val order = IntArray(4)
        var restartInterval = 0
        var todo = 0

        var progressive = false
        var specStart = 0
        var specEnd = 0
        var succHigh = 0
        var succLow = 0
        var eobRun = 0

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
            j.codeBuffer = j.codeBuffer or (b shl (24 - j.codeBits))
            j.codeBits += 8
        } while (j.codeBits <= 24)
    }

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
    private fun decodeBlockProgAc(j: State, data: ShortArray, dataOfs: Int, hac: Huffman, fac: ShortArray) {
        if (j.specStart == 0) err("can't merge dc and ac")

        if (j.succHigh == 0) {
            // first AC scan for this spectral band
            val shift = j.succLow
            if (j.eobRun != 0) {
                j.eobRun--
                return
            }
            var k = j.specStart
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
                        val zig = DEZIGZAG[k++]
                        data[dataOfs + zig] = (extendReceive(j, s) * (1 shl shift)).toShort()
                    }
                }
            } while (k <= j.specEnd)
        } else {
            // AC refinement
            val bit = (1 shl j.succLow).toShort()

            if (j.eobRun != 0) {
                j.eobRun--
                for (k in j.specStart..j.specEnd) {
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
                    for (i in 0 until 64) {
                        j.dequant[t][DEZIGZAG[i]] = if (sixteen) j.u16be() else j.u8()
                    }
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

    // stbi__process_frame_header (scan == STBI__SCAN_load)
    private fun processFrameHeader(j: State) {
        val lf = j.u16be()
        if (lf < 11) err("bad SOF len")
        if (j.u8() != 8) throw UnsupportedImageException("JPEG: only 8-bit precision is supported")
        j.imgY = j.u16be()
        if (j.imgY == 0) throw UnsupportedImageException("JPEG: delayed-height (DNL, height 0) files are not supported")
        j.imgX = j.u16be()
        if (j.imgX == 0) err("zero width")
        if (j.imgX > MAX_DIMENSION || j.imgY > MAX_DIMENSION || j.imgX.toLong() * j.imgY > MAX_PIXELS) {
            err("${j.imgX}x${j.imgY} exceeds safety limits")
        }
        if (!Budget.fits(j.imgX, j.imgY, j.input.size)) {
            err("${j.imgX}x${j.imgY} cannot come from ${j.input.size} bytes")
        }
        val c = j.u8()
        if (c != 3 && c != 1 && c != 4) err("bad component count $c")
        j.imgN = c
        if (lf != 8 + 3 * c) err("bad SOF len")

        j.rgb = 0
        val rgbIds = intArrayOf('R'.code, 'G'.code, 'B'.code)
        for (i in 0 until c) {
            val comp = j.comp[i]
            comp.id = j.u8()
            if (c == 3 && comp.id == rgbIds[i]) j.rgb++
            val q = j.u8()
            comp.h = q shr 4
            if (comp.h == 0 || comp.h > 4) err("bad H")
            comp.v = q and 15
            if (comp.v == 0 || comp.v > 4) err("bad V")
            comp.tq = j.u8()
            if (comp.tq > 3) err("bad TQ")
        }

        var hMax = 1
        var vMax = 1
        for (i in 0 until c) {
            if (j.comp[i].h > hMax) hMax = j.comp[i].h
            if (j.comp[i].v > vMax) vMax = j.comp[i].v
        }
        for (i in 0 until c) {
            if (hMax % j.comp[i].h != 0) err("bad H")
            if (vMax % j.comp[i].v != 0) err("bad V")
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
            comp.data = ByteArray(comp.w2 * comp.h2)
            if (j.progressive) {
                // w2/h2 are multiples of 8; one 64-short block per 8x8 tile
                comp.coeffW = comp.w2 / 8
                comp.coeff = ShortArray(comp.w2 * comp.h2)
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
        if (j.progressive) {
            if (j.specStart > 63 || j.specEnd > 63 || j.specStart > j.specEnd ||
                j.succHigh > 13 || j.succLow > 13
            ) err("bad SOS")
        } else {
            if (j.specStart != 0) err("bad SOS")
            if (j.succHigh != 0 || j.succLow != 0) err("bad SOS")
            j.specEnd = 63
        }
    }

    // stbi__parse_entropy_coded_data
    private fun parseEntropyCodedData(j: State) {
        reset(j)
        if (j.progressive) {
            parseProgressiveScan(j)
            return
        }
        val data = ShortArray(64)
        val tmp = IntArray(64)
        if (j.scanN == 1) {
            // non-interleaved: one block at a time in scanline order
            val n = j.order[0]
            val comp = j.comp[n]
            val w = (comp.x + 7) shr 3
            val h = (comp.y + 7) shr 3
            for (jj in 0 until h) {
                for (i in 0 until w) {
                    decodeBlock(j, data, j.huffDc[comp.hd], j.huffAc[comp.ha], j.fastAc[comp.ha], n, j.dequant[comp.tq])
                    idctBlock(comp.data, comp.w2 * jj * 8 + i * 8, comp.w2, data, tmp)
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
                    for (k in 0 until j.scanN) {
                        val n = j.order[k]
                        val comp = j.comp[n]
                        for (y in 0 until comp.v) {
                            for (x in 0 until comp.h) {
                                val x2 = (i * comp.h + x) * 8
                                val y2 = (jj * comp.v + y) * 8
                                decodeBlock(j, data, j.huffDc[comp.hd], j.huffAc[comp.ha], j.fastAc[comp.ha], n, j.dequant[comp.tq])
                                idctBlock(comp.data, comp.w2 * y2 + x2, comp.w2, data, tmp)
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
                    val ofs = 64 * (i + jj * comp.coeffW)
                    if (j.specStart == 0) {
                        decodeBlockProgDc(j, coeff, ofs, j.huffDc[comp.hd], n)
                    } else {
                        decodeBlockProgAc(j, coeff, ofs, j.huffAc[comp.ha], j.fastAc[comp.ha])
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
        val tmp = IntArray(64)
        for (n in 0 until j.imgN) {
            val comp = j.comp[n]
            val coeff = comp.coeff ?: continue
            val dq = j.dequant[comp.tq]
            val w = (comp.x + 7) shr 3
            val h = (comp.y + 7) shr 3
            for (jj in 0 until h) {
                for (i in 0 until w) {
                    val ofs = 64 * (i + jj * comp.coeffW)
                    for (k in 0 until 64) {   // stbi__jpeg_dequantize
                        block[k] = (coeff[ofs + k] * dq[k]).toShort()
                    }
                    idctBlock(comp.data, comp.w2 * jj * 8 + i * 8, comp.w2, block, tmp)
                }
            }
        }
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
    private class Resampler(val comp: Component, hMax: Int, vMax: Int, imgX: Int) {
        val hs = hMax / comp.h
        val vs = vMax / comp.v
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
            r.hs == 1 && r.vs == 2 -> {   // stbi__resample_row_v_2
                for (i in 0 until w) {
                    out[i] = div4(3 * (inp[nearOfs + i].toInt() and 0xFF) + (inp[farOfs + i].toInt() and 0xFF) + 2).toByte()
                }
                r.outArr = out; r.outOfs = 0
            }
            r.hs == 2 && r.vs == 1 -> {   // stbi__resample_row_h_2
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
                    out[i * 2] = div4(pix(w - 2) * 3 + pix(w - 1) + 2).toByte()
                    out[i * 2 + 1] = inp[nearOfs + w - 1]
                }
                r.outArr = out; r.outOfs = 0
            }
            r.hs == 2 && r.vs == 2 -> {   // stbi__resample_row_hv_2
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
            if (++r.ypos < comp.y) r.line1 += comp.w2
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

    fun decode(input: ByteArray): KiteBitmap {
        val j = State(input)

        // stbi__decode_jpeg_header: SOI, then markers until SOF
        if (j.u8() != 0xFF || j.u8() != 0xD8) err("no SOI")
        var m = getMarker(j)
        while (true) {
            when (m) {
                0xC0, 0xC1 -> break                          // SOF0 baseline / SOF1 extended sequential
                0xC2 -> {                                    // SOF2 progressive
                    j.progressive = true
                    break
                }
                0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF ->
                    throw UnsupportedImageException(
                        "JPEG with SOF marker 0x${m.toString(16)} (lossless/arithmetic/hierarchical) is not supported",
                    )
                MARKER_NONE -> err("expected marker")
                else -> {
                    processMarker(j, m)
                    m = getMarker(j)
                }
            }
        }
        processFrameHeader(j)

        // stbi__decode_jpeg_image: scans until EOI
        m = getMarker(j)
        var sawScan = false
        while (m != 0xD9) {   // EOI
            when {
                m == 0xDA -> {   // SOS
                    processScanHeader(j)
                    parseEntropyCodedData(j)
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

        // resample + color convert (tail of stbi__load_jpeg_image, n == 4 w/ opaque alpha)
        val decodeN = if (j.imgN < 3) 1 else j.imgN
        val isRgb = j.imgN == 3 && (j.rgb == 3 || (j.app14ColorTransform == 0 && !j.jfif))

        val res = Array(decodeN) { k ->
            j.comp[k].linebuf = ByteArray(j.imgX + 3)
            Resampler(j.comp[k], j.hMax, j.vMax, j.imgX)
        }

        val argb = IntArray(j.imgX * j.imgY)
        val couArr = arrayOfNulls<ByteArray>(4)
        val couOfs = IntArray(4)

        for (row in 0 until j.imgY) {
            val outOfs = row * j.imgX
            for (k in 0 until decodeN) {
                resampleRow(res[k])
                couArr[k] = res[k].outArr
                couOfs[k] = res[k].outOfs
            }
            when {
                j.imgN == 3 && isRgb -> {
                    val yA = couArr[0]!!; val cbA = couArr[1]!!; val crA = couArr[2]!!
                    for (i in 0 until j.imgX) {
                        argb[outOfs + i] = (0xFF shl 24) or
                            ((yA[couOfs[0] + i].toInt() and 0xFF) shl 16) or
                            ((cbA[couOfs[1] + i].toInt() and 0xFF) shl 8) or
                            (crA[couOfs[2] + i].toInt() and 0xFF)
                    }
                }
                j.imgN == 3 -> ycbcrToRgbRow(
                    argb, outOfs,
                    couArr[0]!!, couOfs[0], couArr[1]!!, couOfs[1], couArr[2]!!, couOfs[2], j.imgX,
                )
                j.imgN == 4 -> {
                    val kA = couArr[3]!!
                    when (j.app14ColorTransform) {
                        0 -> {   // CMYK: blinn multiply against K
                            val cA = couArr[0]!!; val mA = couArr[1]!!; val yA = couArr[2]!!
                            for (i in 0 until j.imgX) {
                                val kk = kA[couOfs[3] + i].toInt() and 0xFF
                                val r = blinn(cA[couOfs[0] + i].toInt() and 0xFF, kk)
                                val g = blinn(mA[couOfs[1] + i].toInt() and 0xFF, kk)
                                val b = blinn(yA[couOfs[2] + i].toInt() and 0xFF, kk)
                                argb[outOfs + i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                            }
                        }
                        2 -> {   // YCCK: YCbCr, then invert + blinn against K
                            ycbcrToRgbRow(argb, outOfs, couArr[0]!!, couOfs[0], couArr[1]!!, couOfs[1], couArr[2]!!, couOfs[2], j.imgX)
                            for (i in 0 until j.imgX) {
                                val kk = kA[couOfs[3] + i].toInt() and 0xFF
                                val p = argb[outOfs + i]
                                val r = blinn(255 - ((p shr 16) and 0xFF), kk)
                                val g = blinn(255 - ((p shr 8) and 0xFF), kk)
                                val b = blinn(255 - (p and 0xFF), kk)
                                argb[outOfs + i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                            }
                        }
                        else -> ycbcrToRgbRow(   // YCbCr + ignored 4th channel
                            argb, outOfs,
                            couArr[0]!!, couOfs[0], couArr[1]!!, couOfs[1], couArr[2]!!, couOfs[2], j.imgX,
                        )
                    }
                }
                else -> {   // grayscale
                    val yA = couArr[0]!!
                    for (i in 0 until j.imgX) {
                        val g = yA[couOfs[0] + i].toInt() and 0xFF
                        argb[outOfs + i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
                    }
                }
            }
        }

        return KiteBitmap(j.imgX, j.imgY, argb)
    }
}
