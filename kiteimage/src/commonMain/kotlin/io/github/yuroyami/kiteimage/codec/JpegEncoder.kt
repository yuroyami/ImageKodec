package io.github.yuroyami.kiteimage.codec

import io.github.yuroyami.kiteimage.KiteBitmap
import io.github.yuroyami.kiteimage.internal.flate.ByteArrayBuilder

/**
 * Baseline JPEG encoder — a faithful port of `stb_image_write.h`'s
 * `stbi_write_jpg_core` (fixed standard Huffman tables, AAN float fDCT, the
 * IJG-scaled quality→quant mapping, 4:2:0 subsampling at quality ≤ 90 and
 * 4:4:4 above, edge replication for partial MCUs).
 *
 * One deliberate divergence: arithmetic runs in Double, not Float. Kotlin/JS
 * doesn't round Float math to 32 bits between operations, so a Float port
 * would produce different files per target; Double is deterministic
 * everywhere. Output therefore may differ from C stbiw by a coefficient here
 * and there — validity and quality are pinned by tests instead (decode
 * round-trip PSNR, ImageIO and the real stb_image both read our files).
 */
internal object JpegEncoder {

    private val ZIGZAG = intArrayOf(
        0, 1, 5, 6, 14, 15, 27, 28, 2, 4, 7, 13, 16, 26, 29, 42, 3, 8, 12, 17, 25, 30, 41, 43, 9, 11, 18,
        24, 31, 40, 44, 53, 10, 19, 23, 32, 39, 45, 52, 54, 20, 22, 33, 38, 46, 51, 55, 60, 21, 34, 37, 47, 50, 56, 59, 61, 35, 36, 48, 49, 57, 58, 62, 63,
    )

    private val YQT = intArrayOf(
        16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55, 14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62, 18, 22,
        37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92, 49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99,
    )
    private val UVQT = intArrayOf(
        17, 18, 24, 47, 99, 99, 99, 99, 18, 21, 26, 66, 99, 99, 99, 99, 24, 26, 56, 99, 99, 99, 99, 99, 47, 66, 99, 99, 99, 99, 99, 99,
        99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99,
    )
    private val AASF = doubleArrayOf(
        1.0 * 2.828427125, 1.387039845 * 2.828427125, 1.306562965 * 2.828427125, 1.175875602 * 2.828427125,
        1.0 * 2.828427125, 0.785694958 * 2.828427125, 0.541196100 * 2.828427125, 0.275899379 * 2.828427125,
    )

    private val STD_DC_LUM_NRCODES = intArrayOf(0, 0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0)
    private val STD_DC_LUM_VALUES = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)
    private val STD_AC_LUM_NRCODES = intArrayOf(0, 0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7D)
    private val STD_AC_LUM_VALUES = intArrayOf(
        0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07, 0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xA1, 0x08,
        0x23, 0x42, 0xB1, 0xC1, 0x15, 0x52, 0xD1, 0xF0, 0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0A, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x25, 0x26, 0x27, 0x28,
        0x29, 0x2A, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59,
        0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6A, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7A, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
        0x8A, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9A, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7, 0xA8, 0xA9, 0xAA, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6,
        0xB7, 0xB8, 0xB9, 0xBA, 0xC2, 0xC3, 0xC4, 0xC5, 0xC6, 0xC7, 0xC8, 0xC9, 0xCA, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7, 0xD8, 0xD9, 0xDA, 0xE1, 0xE2,
        0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9, 0xEA, 0xF1, 0xF2, 0xF3, 0xF4, 0xF5, 0xF6, 0xF7, 0xF8, 0xF9, 0xFA,
    )
    private val STD_DC_CHR_NRCODES = intArrayOf(0, 0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0)
    private val STD_DC_CHR_VALUES = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)
    private val STD_AC_CHR_NRCODES = intArrayOf(0, 0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77)
    private val STD_AC_CHR_VALUES = intArrayOf(
        0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71, 0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91,
        0xA1, 0xB1, 0xC1, 0x09, 0x23, 0x33, 0x52, 0xF0, 0x15, 0x62, 0x72, 0xD1, 0x0A, 0x16, 0x24, 0x34, 0xE1, 0x25, 0xF1, 0x17, 0x18, 0x19, 0x1A, 0x26,
        0x27, 0x28, 0x29, 0x2A, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58,
        0x59, 0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6A, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7A, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
        0x88, 0x89, 0x8A, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9A, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7, 0xA8, 0xA9, 0xAA, 0xB2, 0xB3, 0xB4,
        0xB5, 0xB6, 0xB7, 0xB8, 0xB9, 0xBA, 0xC2, 0xC3, 0xC4, 0xC5, 0xC6, 0xC7, 0xC8, 0xC9, 0xCA, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7, 0xD8, 0xD9, 0xDA,
        0xE2, 0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9, 0xEA, 0xF2, 0xF3, 0xF4, 0xF5, 0xF6, 0xF7, 0xF8, 0xF9, 0xFA,
    )

    // Fixed code tables (code, size) — stbiw's YDC_HT/UVDC_HT/YAC_HT/UVAC_HT,
    // generated from the standard tables above at first use.
    private val YDC_HT = buildHuffTable(STD_DC_LUM_NRCODES, STD_DC_LUM_VALUES)
    private val UVDC_HT = buildHuffTable(STD_DC_CHR_NRCODES, STD_DC_CHR_VALUES)
    private val YAC_HT = buildHuffTable(STD_AC_LUM_NRCODES, STD_AC_LUM_VALUES)
    private val UVAC_HT = buildHuffTable(STD_AC_CHR_NRCODES, STD_AC_CHR_VALUES)

    /** codes[value] = (code shl 5) or size — packed like stbiw's [256][2] tables. */
    private fun buildHuffTable(nrcodes: IntArray, values: IntArray): IntArray {
        val out = IntArray(256)
        var code = 0
        var k = 0
        for (len in 1..16) {
            repeat(nrcodes[len]) {
                out[values[k]] = (code shl 5) or len
                code++
                k++
            }
            code = code shl 1
        }
        return out
    }

    private class BitWriter(val out: ByteArrayBuilder) {
        var bitBuf = 0
        var bitCnt = 0

        // stbiw__jpg_writeBits
        fun write(code: Int, size: Int) {
            bitCnt += size
            bitBuf = bitBuf or (code shl (24 - bitCnt))
            while (bitCnt >= 8) {
                val c = (bitBuf shr 16) and 0xFF
                out.append(c.toByte())
                if (c == 0xFF) out.append(0.toByte())
                bitBuf = bitBuf shl 8
                bitCnt -= 8
            }
        }

        fun writeEntry(packed: Int) = write(packed ushr 5, packed and 31)
    }

    fun encode(bitmap: KiteBitmap, quality: Int): ByteArray {
        val width = bitmap.width
        val height = bitmap.height

        var q = if (quality == 0) 90 else quality
        val subsample = q <= 90
        q = q.coerceIn(1, 100)
        q = if (q < 50) 5000 / q else 200 - q * 2

        val yTable = ByteArray(64)
        val uvTable = ByteArray(64)
        for (i in 0 until 64) {
            val yti = ((YQT[i] * q + 50) / 100).coerceIn(1, 255)
            yTable[ZIGZAG[i]] = yti.toByte()
            val uvti = ((UVQT[i] * q + 50) / 100).coerceIn(1, 255)
            uvTable[ZIGZAG[i]] = uvti.toByte()
        }

        val fdY = DoubleArray(64)
        val fdUV = DoubleArray(64)
        var k = 0
        for (row in 0 until 8) {
            for (col in 0 until 8) {
                fdY[k] = 1.0 / ((yTable[ZIGZAG[k]].toInt() and 0xFF) * AASF[row] * AASF[col])
                fdUV[k] = 1.0 / ((uvTable[ZIGZAG[k]].toInt() and 0xFF) * AASF[row] * AASF[col])
                k++
            }
        }

        val out = ByteArrayBuilder(width * height / 2 + 1024)

        // Headers (verbatim stbiw layout).
        out.append(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10, 'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0, 1, 1, 0, 0, 1, 0, 1, 0, 0, 0xFF.toByte(), 0xDB.toByte(), 0, 0x84.toByte(), 0))
        out.append(yTable)
        out.append(1.toByte())
        out.append(uvTable)
        out.append(
            byteArrayOf(
                0xFF.toByte(), 0xC0.toByte(), 0, 0x11, 8, (height shr 8).toByte(), height.toByte(), (width shr 8).toByte(), width.toByte(),
                3, 1, (if (subsample) 0x22 else 0x11).toByte(), 0, 2, 0x11, 1, 3, 0x11, 1, 0xFF.toByte(), 0xC4.toByte(), 0x01, 0xA2.toByte(), 0,
            ),
        )
        for (i in 1..16) out.append(STD_DC_LUM_NRCODES[i].toByte())
        for (v in STD_DC_LUM_VALUES) out.append(v.toByte())
        out.append(0x10.toByte())
        for (i in 1..16) out.append(STD_AC_LUM_NRCODES[i].toByte())
        for (v in STD_AC_LUM_VALUES) out.append(v.toByte())
        out.append(1.toByte())
        for (i in 1..16) out.append(STD_DC_CHR_NRCODES[i].toByte())
        for (v in STD_DC_CHR_VALUES) out.append(v.toByte())
        out.append(0x11.toByte())
        for (i in 1..16) out.append(STD_AC_CHR_NRCODES[i].toByte())
        for (v in STD_AC_CHR_VALUES) out.append(v.toByte())
        out.append(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 0xC, 3, 1, 0, 2, 0x11, 3, 0x11, 0, 0x3F, 0))

        val bw = BitWriter(out)
        var dcY = 0
        var dcU = 0
        var dcV = 0
        val argb = bitmap.argb

        fun ycc(x: Int, y: Int, dst: Int, yArr: DoubleArray, uArr: DoubleArray, vArr: DoubleArray) {
            val cx = if (x < width) x else width - 1
            val cy = if (y < height) y else height - 1
            val p = argb[cy * width + cx]
            val r = ((p ushr 16) and 0xFF).toDouble()
            val g = ((p ushr 8) and 0xFF).toDouble()
            val b = (p and 0xFF).toDouble()
            yArr[dst] = +0.29900 * r + 0.58700 * g + 0.11400 * b - 128
            uArr[dst] = -0.16874 * r - 0.33126 * g + 0.50000 * b
            vArr[dst] = +0.50000 * r - 0.41869 * g - 0.08131 * b
        }

        if (subsample) {
            val yBuf = DoubleArray(256)
            val uBuf = DoubleArray(256)
            val vBuf = DoubleArray(256)
            val subU = DoubleArray(64)
            val subV = DoubleArray(64)
            var y = 0
            while (y < height) {
                var x = 0
                while (x < width) {
                    var pos = 0
                    for (row in y until y + 16) {
                        for (col in x until x + 16) {
                            ycc(col, row, pos, yBuf, uBuf, vBuf)
                            pos++
                        }
                    }
                    dcY = processDU(bw, yBuf, 0, 16, fdY, dcY, YDC_HT, YAC_HT)
                    dcY = processDU(bw, yBuf, 8, 16, fdY, dcY, YDC_HT, YAC_HT)
                    dcY = processDU(bw, yBuf, 128, 16, fdY, dcY, YDC_HT, YAC_HT)
                    dcY = processDU(bw, yBuf, 136, 16, fdY, dcY, YDC_HT, YAC_HT)
                    var sp = 0
                    for (yy in 0 until 8) {
                        for (xx in 0 until 8) {
                            val j = yy * 32 + xx * 2
                            subU[sp] = (uBuf[j] + uBuf[j + 1] + uBuf[j + 16] + uBuf[j + 17]) * 0.25
                            subV[sp] = (vBuf[j] + vBuf[j + 1] + vBuf[j + 16] + vBuf[j + 17]) * 0.25
                            sp++
                        }
                    }
                    dcU = processDU(bw, subU, 0, 8, fdUV, dcU, UVDC_HT, UVAC_HT)
                    dcV = processDU(bw, subV, 0, 8, fdUV, dcV, UVDC_HT, UVAC_HT)
                    x += 16
                }
                y += 16
            }
        } else {
            val yBuf = DoubleArray(64)
            val uBuf = DoubleArray(64)
            val vBuf = DoubleArray(64)
            var y = 0
            while (y < height) {
                var x = 0
                while (x < width) {
                    var pos = 0
                    for (row in y until y + 8) {
                        for (col in x until x + 8) {
                            ycc(col, row, pos, yBuf, uBuf, vBuf)
                            pos++
                        }
                    }
                    dcY = processDU(bw, yBuf, 0, 8, fdY, dcY, YDC_HT, YAC_HT)
                    dcU = processDU(bw, uBuf, 0, 8, fdUV, dcU, UVDC_HT, UVAC_HT)
                    dcV = processDU(bw, vBuf, 0, 8, fdUV, dcV, UVDC_HT, UVAC_HT)
                    x += 8
                }
                y += 8
            }
        }

        // Bit-align for EOI (stbiw fillBits = 0x7F, 7 bits).
        bw.write(0x7F, 7)
        out.append(0xFF.toByte())
        out.append(0xD9.toByte())
        return out.toByteArray()
    }

    // stbiw__jpg_DCT — AAN forward DCT on one 8-element span.
    private fun dct1d(d: DoubleArray, o0: Int, o1: Int, o2: Int, o3: Int, o4: Int, o5: Int, o6: Int, o7: Int) {
        val d0 = d[o0]; val d1 = d[o1]; val d2 = d[o2]; val d3 = d[o3]
        val d4 = d[o4]; val d5 = d[o5]; val d6 = d[o6]; val d7 = d[o7]

        val tmp0 = d0 + d7; val tmp7 = d0 - d7
        val tmp1 = d1 + d6; val tmp6 = d1 - d6
        val tmp2 = d2 + d5; val tmp5 = d2 - d5
        val tmp3 = d3 + d4; val tmp4 = d3 - d4

        var tmp10 = tmp0 + tmp3
        val tmp13 = tmp0 - tmp3
        var tmp11 = tmp1 + tmp2
        var tmp12 = tmp1 - tmp2

        d[o0] = tmp10 + tmp11
        d[o4] = tmp10 - tmp11

        val z1 = (tmp12 + tmp13) * 0.707106781
        d[o2] = tmp13 + z1
        d[o6] = tmp13 - z1

        tmp10 = tmp4 + tmp5
        tmp11 = tmp5 + tmp6
        tmp12 = tmp6 + tmp7

        val z5 = (tmp10 - tmp12) * 0.382683433
        val z2 = tmp10 * 0.541196100 + z5
        val z4 = tmp12 * 1.306562965 + z5
        val z3 = tmp11 * 0.707106781

        val z11 = tmp7 + z3
        val z13 = tmp7 - z3

        d[o5] = z13 + z2
        d[o3] = z13 - z2
        d[o1] = z11 + z4
        d[o7] = z11 - z4
    }

    // stbiw__jpg_calcBits: (mask, size) for a coefficient value.
    private fun calcBits(value: Int): Int {
        var tmp = if (value < 0) -value else value
        val v = if (value < 0) value - 1 else value
        var size = 1
        while (true) {
            tmp = tmp shr 1
            if (tmp == 0) break
            size++
        }
        return ((v and ((1 shl size) - 1)) shl 5) or size
    }

    // stbiw__jpg_processDU
    private fun processDU(bw: BitWriter, cdu: DoubleArray, ofs: Int, stride: Int, fdtbl: DoubleArray, dcPrev: Int, htdc: IntArray, htac: IntArray): Int {
        val eob = htac[0x00]
        val m16 = htac[0xF0]
        val du = IntArray(64)

        var dataOff = ofs
        val n = ofs + stride * 8
        while (dataOff < n) {
            dct1d(cdu, dataOff, dataOff + 1, dataOff + 2, dataOff + 3, dataOff + 4, dataOff + 5, dataOff + 6, dataOff + 7)
            dataOff += stride
        }
        for (c in 0 until 8) {
            dct1d(
                cdu, ofs + c, ofs + c + stride, ofs + c + stride * 2, ofs + c + stride * 3,
                ofs + c + stride * 4, ofs + c + stride * 5, ofs + c + stride * 6, ofs + c + stride * 7,
            )
        }

        var j = 0
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val v = cdu[ofs + y * stride + x] * fdtbl[j]
                du[ZIGZAG[j]] = (if (v < 0) v - 0.5 else v + 0.5).toInt()
                j++
            }
        }

        val diff = du[0] - dcPrev
        if (diff == 0) {
            bw.writeEntry(htdc[0])
        } else {
            val bits = calcBits(diff)
            bw.writeEntry(htdc[bits and 31])
            bw.writeEntry(bits)
        }

        var end0pos = 63
        while (end0pos > 0 && du[end0pos] == 0) end0pos--
        if (end0pos == 0) {
            bw.writeEntry(eob)
            return du[0]
        }
        var i = 1
        while (i <= end0pos) {
            val startpos = i
            while (du[i] == 0 && i <= end0pos) i++
            var nrzeroes = i - startpos
            if (nrzeroes >= 16) {
                repeat(nrzeroes shr 4) { bw.writeEntry(m16) }
                nrzeroes = nrzeroes and 15
            }
            val bits = calcBits(du[i])
            bw.writeEntry(htac[(nrzeroes shl 4) + (bits and 31)])
            bw.writeEntry(bits)
            i++
        }
        if (end0pos != 63) bw.writeEntry(eob)
        return du[0]
    }
}
