/*
 * The JPEG XR inverse transform and overlap filter: a Kotlin port of jxrlib's
 * image/decode/strInvTransform.c and image/sys/strTransform.c, at 4creators/jxrlib commit 1ef17dd.
 *
 * Copyright (c) Microsoft Corp. All rights reserved.
 *
 * Use of this source code is governed by the BSD 2-Clause license that is reproduced in this
 * repository's NOTICE. Altered for Kotlin: each function takes a channel's row array and the
 * indices jxrlib takes as pointers into it, and the post-processing filters are left out.
 */
package io.github.yuroyami.imagekodec.codec.jxr

import kotlin.math.abs

/**
 * The photo core transform and photo overlap filter of T.832 clause 9.8 and 9.9, in jxrlib's
 * order: when macroblock (x, y) is decoded, the transform finishes what the macroblocks above and
 * to the left of it were waiting for. Codec subversion 0 (HD Photo) has its own filter edges and
 * DC compensation; JPEG XR's subversions 1 and 9 use the altered operators, with hard tile edges
 * for 9.
 */
internal object JxrTransform {

    // --- Primitives (strTransform.c, strInvTransform.c) ------------------------------------------

    fun dct2x2dn(p: IntArray, ia: Int, ib: Int, ic: Int, id: Int) {
        var a = p[ia]
        var b = p[ib]
        val cc = p[ic]
        var d = p[id]
        a += d
        b -= cc
        val t = (a - b) shr 1
        val c = t - d
        d = t - cc
        a -= d
        b += c
        p[ia] = a
        p[ib] = b
        p[ic] = c
        p[id] = d
    }

    private fun dct2x2up(p: IntArray, ia: Int, ib: Int, ic: Int, id: Int) {
        var a = p[ia]
        var b = p[ib]
        val cc = p[ic]
        var d = p[id]
        a += d
        b -= cc
        val t = (a - b + 1) shr 1
        val c = t - d
        d = t - cc
        a -= d
        b += c
        p[ia] = a
        p[ib] = b
        p[ic] = c
        p[id] = d
    }

    /** strDCT2x2dnDec: the 2x2 transform with the doubling of scaled arithmetic. */
    fun dct2x2dnDec(p: IntArray, ia: Int, ib: Int, ic: Int, id: Int) {
        dct2x2dn(p, ia, ib, ic, id)
        p[ia] *= 2
        p[ib] *= 2
        p[ic] *= 2
        p[id] *= 2
    }

    /** strIDCT4x4Stage1: the inverse transform of one 4x4 block. */
    fun idct4x4Stage1(p: IntArray, o: Int) {
        dct2x2up(p, o + 0, o + 1, o + 2, o + 3)
        invOdd(p, o + 5, o + 4, o + 7, o + 6)
        invOdd(p, o + 10, o + 8, o + 11, o + 9)
        invOddOdd(p, o + 15, o + 14, o + 13, o + 12)
        for (k in 0 until 4) dct2x2dn(p, o + k, o + k + 4, o + k + 8, o + k + 12)
    }

    /** strIDCT4x4Stage2: the inverse transform of the DCs of a macroblock's 16 blocks. */
    fun idct4x4Stage2(p: IntArray, o: Int) {
        invOdd(p, o + 32, o + 48, o + 96, o + 112)
        invOdd(p, o + 128, o + 192, o + 144, o + 208)
        invOddOdd(p, o + 160, o + 224, o + 176, o + 240)
        dct2x2up(p, o + 0, o + 64, o + 16, o + 80)
        dct2x2dn(p, o + 0, o + 192, o + 48, o + 240)
        dct2x2dn(p, o + 64, o + 128, o + 112, o + 176)
        dct2x2dn(p, o + 16, o + 208, o + 32, o + 224)
        dct2x2dn(p, o + 80, o + 144, o + 96, o + 160)
    }

    /** strNormalizeDec: chroma DCs double under scaled arithmetic. */
    fun normalizeDec(p: IntArray, o: Int, chroma: Boolean) {
        if (!chroma) return
        for (i in 0 until 256 step 16) p[o + i] += p[o + i]
    }

    private fun post2(p: IntArray, ia: Int, ib: Int) {
        p[ib] += (p[ia] + 4) shr 3
        p[ia] += (p[ib] + 2) shr 2
        p[ib] += (p[ia] + 4) shr 3
    }

    private fun post2Alt(p: IntArray, ia: Int, ib: Int) {
        var a = p[ia]
        var b = p[ib]
        b += (a + 2) shr 2
        a += (b + 1) shr 1
        a += b shr 5
        a += b shr 9
        a += b shr 13
        b += (a + 2) shr 2
        p[ia] = a
        p[ib] = b
    }

    private fun post2x2(p: IntArray, ia: Int, ib: Int, ic: Int, id: Int, alternate: Boolean) {
        var a = p[ia]
        var b = p[ib]
        var c = p[ic]
        var d = p[id]
        a += d
        b += c
        d -= (a + 1) shr 1
        c -= (b + 1) shr 1
        b += (a + 2) shr 2
        a += (b + 1) shr 1
        if (alternate) {
            a += b shr 5
            a += b shr 9
            a += b shr 13
        }
        b += (a + 2) shr 2
        d += (a + 1) shr 1
        c += (b + 1) shr 1
        a -= d
        b -= c
        p[ia] = a
        p[ib] = b
        p[ic] = c
        p[id] = d
    }

    /** strPost4: the 4-point overlap filter along an image edge. */
    fun post4(p: IntArray, ia: Int, ib: Int, ic: Int, id: Int) {
        var a = p[ia]
        var b = p[ib]
        var c = p[ic]
        var d = p[id]
        a += d
        b += c
        d -= (a + 1) shr 1
        c -= (b + 1) shr 1
        c -= (d + 1) shr 1
        d += (c + 1) shr 1
        d += (a + 1) shr 1
        c += (b + 1) shr 1
        a -= d - ((d * 3 + 16) shr 5)
        b -= c - ((c * 3 + 16) shr 5)
        d += (a * 3 + 8) shr 4
        c += (b * 3 + 8) shr 4
        a += (d * 3 + 16) shr 5
        b += (c * 3 + 16) shr 5
        p[ia] = a
        p[ib] = b
        p[ic] = c
        p[id] = d
    }

    fun post4Alt(p: IntArray, ia: Int, ib: Int, ic: Int, id: Int) {
        var a = p[ia]
        var b = p[ib]
        var c = p[ic]
        var d = p[id]
        a += d
        b += c
        d -= (a + 1) shr 1
        c -= (b + 1) shr 1
        // strHSTdec1_edge on (a, d) and on (b, c).
        run {
            var x = a
            var y = d
            x += y
            y = (x shr 1) - y
            x += (y * 3) shr 3
            y += (x * 3) shr 4
            y += x shr 7
            y -= x shr 10
            x += (y * 3 + 4) shr 3
            y -= x shr 1
            x += y
            a = x
            d = -y
        }
        run {
            var x = b
            var y = c
            x += y
            y = (x shr 1) - y
            x += (y * 3) shr 3
            y += (x * 3) shr 4
            y += x shr 7
            y -= x shr 10
            x += (y * 3 + 4) shr 3
            y -= x shr 1
            x += y
            b = x
            c = -y
        }
        c -= (d + 1) shr 1
        d += (c + 1) shr 1
        d += (a + 1) shr 1
        c += (b + 1) shr 1
        a -= d
        b -= c
        p[ia] = a
        p[ib] = b
        p[ic] = c
        p[id] = d
    }

    private fun iRotate1(p: IntArray, ia: Int, ib: Int) {
        p[ia] -= (p[ib] + 1) shr 1
        p[ib] += (p[ia] + 1) shr 1
    }

    private fun hstDec1(p: IntArray, ia: Int, id: Int, alternate: Boolean) {
        var a = p[ia]
        var d = p[id]
        a += d
        d = (a shr 1) - d
        a += (d * 3) shr 3
        d += (a * 3) shr 4
        if (alternate) {
            d += a shr 7
            d -= a shr 10
        }
        p[ia] = a
        p[id] = d
    }

    private fun hstDec(p: IntArray, ia: Int, ib: Int, ic: Int, id: Int) {
        var a = p[ia]
        var b = p[ib]
        var c = p[ic]
        var d = p[id]
        b -= c
        a += (d * 3 + 4) shr 3
        d -= b shr 1
        c = ((a - b) shr 1) - c
        p[ic] = d
        p[id] = c
        p[ia] = a - c
        p[ib] = b + d
    }

    private fun invOddOdd(p: IntArray, ia: Int, ib: Int, ic: Int, id: Int) {
        var a = p[ia]
        var b = p[ib]
        var c = p[ic]
        var d = p[id]
        d += a
        c -= b
        val t1 = d shr 1
        a -= t1
        val t2 = c shr 1
        b += t2
        a -= (b * 3 + 3) shr 3
        b += (a * 3 + 3) shr 2
        a -= (b * 3 + 4) shr 3
        b -= t2
        a += t1
        c += b
        d -= a
        p[ia] = a
        p[ib] = -b
        p[ic] = -c
        p[id] = d
    }

    private fun invOddOddPost(p: IntArray, ia: Int, ib: Int, ic: Int, id: Int) {
        var a = p[ia]
        var b = p[ib]
        var c = p[ic]
        var d = p[id]
        d += a
        c -= b
        val t1 = d shr 1
        a -= t1
        val t2 = c shr 1
        b += t2
        a -= (b * 3 + 6) shr 3
        b += (a * 3 + 2) shr 2
        a -= (b * 3 + 4) shr 3
        b -= t2
        a += t1
        c += b
        d -= a
        p[ia] = a
        p[ib] = b
        p[ic] = c
        p[id] = d
    }

    private fun invOdd(p: IntArray, ia: Int, ib: Int, ic: Int, id: Int) {
        var a = p[ia]
        var b = p[ib]
        var c = p[ic]
        var d = p[id]
        b += d
        a -= c
        d -= b shr 1
        c += (a + 1) shr 1
        a -= (b * 3 + 4) shr 3
        b += (a * 3 + 4) shr 3
        c -= (d * 3 + 4) shr 3
        d += (c * 3 + 4) shr 3
        c -= (b + 1) shr 1
        d = ((a + 1) shr 1) - d
        b += c
        a -= d
        p[ia] = a
        p[ib] = b
        p[ic] = c
        p[id] = d
    }

    private fun dcCompensate(p: IntArray, ia: Int, ib: Int, ic: Int, id: Int, dc: Int) {
        val h = dc shr 1
        p[ia] -= h
        p[id] -= h
        p[ib] += h
        p[ic] += h
    }

    private fun clipDCL(dcl: Int, alt: Int): Int = when {
        dcl > 0 -> if (alt > 0) minOf(dcl, alt) else 0
        dcl < 0 -> if (alt < 0) maxOf(dcl, alt) else 0
        else -> 0
    }

    /** strPost4x4Stage1Split and its _alternate: the 4x4 overlap filter across the corner of four blocks. */
    private fun post4x4Stage1Split(p: IntArray, q0: Int, q1: Int, offset: Int, hpQP: Int, hpAbsent: Boolean, alternate: Boolean) {
        val p2 = q0 + 72 - offset
        val p3 = q1 + 64 - offset
        val p0 = q0 + 12
        val p1 = q1 + 4
        for (k in 0 until 4) dct2x2dn(p, p0 + k, p2 + k, p1 + k, p3 + k)
        invOddOddPost(p, p3 + 0, p3 + 1, p3 + 2, p3 + 3)
        iRotate1(p, p1 + 2, p1 + 3)
        iRotate1(p, p1 + 0, p1 + 1)
        iRotate1(p, p2 + 1, p2 + 3)
        iRotate1(p, p2 + 0, p2 + 2)
        for (k in 0 until 4) hstDec1(p, p0 + k, p3 + k, alternate)
        for (k in 0 until 4) hstDec(p, p0 + k, p2 + k, p1 + k, p3 + k)
        if (alternate) return
        for (k in 0 until 4) {
            val tmp = (p[p0 + k] + p[p1 + k] + p[p2 + k] + p[p3 + k]) shr 1
            var dcl = (tmp * 595 + 65536) shr 17
            if ((abs(dcl) < hpQP && hpQP > 20) || hpAbsent) {
                val alt = (p[p0 + k] - p[p1 + k] - p[p2 + k] + p[p3 + k]) shr 1
                dcl = clipDCL(dcl, alt)
                dcCompensate(p, p0 + k, p2 + k, p1 + k, p3 + k, dcl)
            }
        }
    }

    private fun post4x4Stage1(p: IntArray, o: Int, offset: Int, hpQP: Int, hpAbsent: Boolean, alternate: Boolean) =
        post4x4Stage1Split(p, o, o + 16, offset, hpQP, hpAbsent, alternate)

    /** strPost4x4Stage2Split and its _alternate: the overlap filter of the second stage, across four macroblocks. */
    private fun post4x4Stage2Split(p: IntArray, p0: Int, p1: Int, alternate: Boolean) {
        dct2x2dn(p, p0 - 96, p0 + 96, p1 - 112, p1 + 80)
        dct2x2dn(p, p0 - 32, p0 + 32, p1 - 48, p1 + 16)
        dct2x2dn(p, p0 - 80, p0 + 112, p1 - 128, p1 + 64)
        dct2x2dn(p, p0 - 16, p0 + 48, p1 - 64, p1 + 0)
        invOddOddPost(p, p1 + 0, p1 + 64, p1 + 16, p1 + 80)
        iRotate1(p, p0 + 48, p0 + 32)
        iRotate1(p, p0 + 112, p0 + 96)
        iRotate1(p, p1 - 64, p1 - 128)
        iRotate1(p, p1 - 48, p1 - 112)
        hstDec1(p, p0 - 96, p1 + 80, alternate)
        hstDec1(p, p0 - 32, p1 + 16, alternate)
        hstDec1(p, p0 - 80, p1 + 64, alternate)
        hstDec1(p, p0 - 16, p1 + 0, alternate)
        hstDec(p, p0 - 96, p1 - 112, p0 + 96, p1 + 80)
        hstDec(p, p0 - 32, p1 - 48, p0 + 32, p1 + 16)
        hstDec(p, p0 - 80, p1 - 128, p0 + 112, p1 + 64)
        hstDec(p, p0 - 16, p1 - 64, p0 + 48, p1 + 0)
    }

    // --- invTransformMacroblock ---------------------------------------------------------------

    /** The transform of the macroblock at ([JxrPlane.column], [JxrPlane.row]), as the plane's codec subversion asks. */
    fun macroblock(s: JxrPlane, overlap: Int, tScale: Int) {
        if (s.image.subVersion == 0) original(s, overlap, tScale) else altered(s, overlap, tScale)
    }

    /** invTransformMacroblock, for codec subversion 0. */
    private fun original(s: JxrPlane, overlap: Int, tScale: Int) {
        val cf = s.cf
        val left = s.column == 0
        val right = s.column == s.mbWidth
        val top = s.row == 0
        val bottom = s.row == s.mbHeight
        val topOrBottom = top || bottom
        val leftOrRight = left || right
        val bottomOrRight = bottom || right
        val hpAbsent = s.subband == JxrBands.NO_HIGHPASS || s.subband == JxrBands.DC_ONLY
        val tile = s.tiles[s.tileColumn]
        if (tScale < 16) {
            for (i in 0 until s.planes) {
                val p = s.buf[i]
                val p0 = s.p0[i]
                val p1 = s.p1[i]
                val hpQP = if (hpAbsent) 255 else tile.hpQP[i][s.qIndexHP]
                if (!bottomOrRight) {
                    idct4x4Stage2(p, p1)
                    if (s.scaled) normalizeDec(p, p1, i != 0)
                }
                if (overlap == 2) {
                    if (leftOrRight && !topOrBottom) {
                        val j = if (left) 0 else -128
                        post4(p, p0 + j + 32, p0 + j + 48, p1 + j + 0, p1 + j + 16)
                        post4(p, p0 + j + 96, p0 + j + 112, p1 + j + 64, p1 + j + 80)
                    }
                    if (!leftOrRight) {
                        if (topOrBottom) {
                            val q = if (top) p1 else p0 + 32
                            post4(p, q - 128, q - 64, q + 0, q + 64)
                            post4(p, q - 112, q - 48, q + 16, q + 80)
                        } else {
                            post4x4Stage2Split(p, p0, p1, false)
                        }
                    }
                }
                if (tScale >= 4) continue
                if (!top) {
                    var j = if (left) 32 else -96
                    while (j < (if (right) 32 else 160)) {
                        idct4x4Stage1(p, p0 + j + 0)
                        idct4x4Stage1(p, p0 + j + 16)
                        j += 64
                    }
                }
                if (!bottom) {
                    var j = if (left) 0 else -128
                    while (j < (if (right) 0 else 128)) {
                        idct4x4Stage1(p, p1 + j + 0)
                        idct4x4Stage1(p, p1 + j + 16)
                        j += 64
                    }
                }
                if (overlap != 0) {
                    if (leftOrRight) {
                        val j = if (left) 0 + 10 else -64 + 14
                        if (!top) {
                            val q = p0 + 16 + j
                            post4(p, q + 0, q - 2, q + 6, q + 8)
                            post4(p, q + 1, q - 1, q + 7, q + 9)
                            post4(p, q + 16, q + 14, q + 22, q + 24)
                            post4(p, q + 17, q + 15, q + 23, q + 25)
                        }
                        if (!bottom) {
                            val q = p1 + j
                            post4(p, q + 0, q - 2, q + 6, q + 8)
                            post4(p, q + 1, q - 1, q + 7, q + 9)
                        }
                        if (!topOrBottom) {
                            post4(p, p0 + 48 + j + 0, p0 + 48 + j - 2, p1 - 10 + j, p1 - 8 + j)
                            post4(p, p0 + 48 + j + 1, p0 + 48 + j - 1, p1 - 9 + j, p1 - 7 + j)
                        }
                    }
                    var j = if (left) 0 else -192
                    val jEnd = if (right) -64 else 64
                    while (j < jEnd) {
                        if (top) {
                            val q = p1 + j
                            post4(p, q + 5, q + 4, q + 64, q + 65)
                            post4(p, q + 7, q + 6, q + 66, q + 67)
                            post4x4Stage1(p, p1 + j, 0, hpQP, hpAbsent, false)
                        } else if (bottom) {
                            post4x4Stage1(p, p0 + 16 + j, 0, hpQP, hpAbsent, false)
                            post4x4Stage1(p, p0 + 32 + j, 0, hpQP, hpAbsent, false)
                            val q = p0 + 48 + j
                            post4(p, q + 15, q + 14, q + 74, q + 75)
                            post4(p, q + 13, q + 12, q + 72, q + 73)
                        } else {
                            post4x4Stage1(p, p0 + 16 + j, 0, hpQP, hpAbsent, false)
                            post4x4Stage1(p, p0 + 32 + j, 0, hpQP, hpAbsent, false)
                            post4x4Stage1Split(p, p0 + 48 + j, p1 + j, 0, hpQP, hpAbsent, false)
                            post4x4Stage1(p, p1 + j, 0, hpQP, hpAbsent, false)
                        }
                        j += 64
                    }
                }
            }
        }
        if (cf == JxrColor.YUV_420 && tScale < 16) {
            for (i in 0 until 2) {
                val p = s.buf[1 + i]
                val p0 = s.p0[1 + i]
                val p1 = s.p1[1 + i]
                // jxrlib takes the chroma step of the channel numbered i here, so U takes Y's.
                val hpQP = if (hpAbsent) 255 else tile.hpQP[i][s.qIndexHP]
                if (!bottomOrRight) {
                    if (!s.scaled) dct2x2dn(p, p1, p1 + 32, p1 + 16, p1 + 48) else dct2x2dnDec(p, p1, p1 + 32, p1 + 16, p1 + 48)
                }
                if (overlap == 2) {
                    if (leftOrRight && !topOrBottom) {
                        val j = if (left) 0 else -32
                        post2(p, p0 + j + 16, p1 + j)
                    }
                    if (!leftOrRight) {
                        if (topOrBottom) {
                            val q = if (top) p1 else p0 + 16
                            post2(p, q - 32, q)
                        } else {
                            post2x2(p, p0 - 16, p0 + 16, p1 - 32, p1, false)
                        }
                    }
                }
                if (tScale >= 4) continue
                if (!top) {
                    var j = if (left) 16 else -16
                    while (j < (if (right) 16 else 48)) {
                        idct4x4Stage1(p, p0 + j)
                        j += 32
                    }
                }
                if (!bottom) {
                    var j = if (left) 0 else -32
                    while (j < (if (right) 0 else 32)) {
                        idct4x4Stage1(p, p1 + j)
                        j += 32
                    }
                }
                if (overlap != 0) {
                    if (!left && !top) {
                        var j = -48
                        while (j < (if (right) -16 else 16)) {
                            if (bottom) {
                                val q = p0 + j
                                post4(p, q + 15, q + 14, q + 42, q + 43)
                                post4(p, q + 13, q + 12, q + 40, q + 41)
                            } else {
                                post4x4Stage1Split(p, p0 + j, p1 - 16 + j, 32, hpQP, hpAbsent, false)
                            }
                            j += 32
                        }
                        if (right) {
                            if (!bottom) {
                                post4(p, p0 - 2, p0 - 4, p1 - 28, p1 - 26)
                                post4(p, p0 - 1, p0 - 3, p1 - 27, p1 - 25)
                            }
                            post4(p, p0 - 18, p0 - 20, p0 - 12, p0 - 10)
                            post4(p, p0 - 17, p0 - 19, p0 - 11, p0 - 9)
                        } else {
                            post4x4Stage1(p, p0 - 32, 32, hpQP, hpAbsent, false)
                        }
                        post4x4Stage1(p, p0 - 64, 32, hpQP, hpAbsent, false)
                    } else if (top) {
                        var j = if (left) 0 else -64
                        while (j < (if (right) -32 else 0)) {
                            val q = p1 + j + 4
                            post4(p, q + 1, q + 0, q + 28, q + 29)
                            post4(p, q + 3, q + 2, q + 30, q + 31)
                            j += 32
                        }
                    } else if (left) {
                        if (!bottom) {
                            post4(p, p0 + 26, p0 + 24, p1 + 0, p1 + 2)
                            post4(p, p0 + 27, p0 + 25, p1 + 1, p1 + 3)
                        }
                        post4(p, p0 + 10, p0 + 8, p0 + 16, p0 + 18)
                        post4(p, p0 + 11, p0 + 9, p0 + 17, p0 + 19)
                    }
                }
            }
        }
        if (cf == JxrColor.YUV_422 && tScale < 16) {
            for (i in 0 until 2) {
                val p = s.buf[1 + i]
                val p0 = s.p0[1 + i]
                val p1 = s.p1[1 + i]
                val hpQP = if (hpAbsent) 255 else tile.hpQP[i][s.qIndexHP]
                if (!bottomOrRight && tScale < 16) {
                    p[p1] -= (p[p1 + 32] + 1) shr 1
                    p[p1 + 32] += p[p1]
                    if (!s.scaled) {
                        dct2x2dn(p, p1 + 0, p1 + 64, p1 + 16, p1 + 80)
                        dct2x2dn(p, p1 + 32, p1 + 96, p1 + 48, p1 + 112)
                    } else {
                        dct2x2dnDec(p, p1 + 0, p1 + 64, p1 + 16, p1 + 80)
                        dct2x2dnDec(p, p1 + 32, p1 + 96, p1 + 48, p1 + 112)
                    }
                }
                if (overlap == 2) {
                    if (!bottom) {
                        if (leftOrRight) {
                            if (!top) {
                                val j = if (left) 0 else -64
                                post2(p, p0 + 48 + j, p1 + j)
                            }
                            val j = if (left) 16 else -48
                            post2(p, p1 + j, p1 + j + 16)
                        } else {
                            if (top) post2(p, p1 - 64, p1) else post2x2(p, p0 - 16, p0 + 48, p1 - 64, p1, false)
                            post2x2(p, p1 - 48, p1 + 16, p1 - 32, p1 + 32, false)
                        }
                    } else if (!leftOrRight) {
                        post2(p, p0 - 16, p0 + 48)
                    }
                }
                if (tScale >= 4) continue
                if (!top) {
                    var j = if (left) 48 else -16
                    while (j < (if (right) 48 else 112)) {
                        idct4x4Stage1(p, p0 + j)
                        j += 64
                    }
                }
                if (!bottom) {
                    var j = if (left) 0 else -64
                    while (j < (if (right) 0 else 64)) {
                        idct4x4Stage1(p, p1 + j + 0)
                        idct4x4Stage1(p, p1 + j + 16)
                        idct4x4Stage1(p, p1 + j + 32)
                        j += 64
                    }
                }
                if (overlap != 0) {
                    val jEnd = if (right) -64 else 0
                    if (!top) {
                        if (leftOrRight) {
                            val q = p0 + if (left) 32 + 10 else -32 + 14
                            post4(p, q + 0, q - 2, q + 6, q + 8)
                            post4(p, q + 1, q - 1, q + 7, q + 9)
                        }
                        var j = if (left) 0 else -128
                        while (j < jEnd) {
                            post4x4Stage1(p, p0 + j + 32, 0, hpQP, hpAbsent, false)
                            j += 64
                        }
                    }
                    if (!bottom) {
                        if (leftOrRight) {
                            var q = p1 + if (left) 0 + 10 else -64 + 14
                            post4(p, q + 0, q - 2, q + 6, q + 8)
                            post4(p, q + 1, q - 1, q + 7, q + 9)
                            q += 16
                            post4(p, q + 0, q - 2, q + 6, q + 8)
                            post4(p, q + 1, q - 1, q + 7, q + 9)
                        }
                        var j = if (left) 0 else -128
                        while (j < jEnd) {
                            post4x4Stage1(p, p1 + j + 0, 0, hpQP, hpAbsent, false)
                            post4x4Stage1(p, p1 + j + 16, 0, hpQP, hpAbsent, false)
                            j += 64
                        }
                    }
                    if (topOrBottom) {
                        val q = if (top) p1 + 5 else p0 + 48 + 13
                        var j = if (left) 0 else -128
                        while (j < jEnd) {
                            post4(p, q + j + 0, q + j - 1, q + j + 59, q + j + 60)
                            post4(p, q + j + 2, q + j + 1, q + j + 61, q + j + 62)
                            j += 64
                        }
                    } else {
                        if (leftOrRight) {
                            val j = if (left) 0 + 0 else -64 + 4
                            post4(p, p0 + j + 48 + 10 + 0, p0 + j + 48 + 10 - 2, p1 + j + 0, p1 + j + 2)
                            post4(p, p0 + j + 48 + 10 + 1, p0 + j + 48 + 10 - 1, p1 + j + 1, p1 + j + 3)
                        }
                        var j = if (left) 0 else -128
                        while (j < jEnd) {
                            post4x4Stage1Split(p, p0 + j + 48, p1 + j + 0, 0, hpQP, hpAbsent, false)
                            j += 64
                        }
                    }
                }
            }
        }
    }

    /** invTransformMacroblock_alteredOperators_hard, for codec subversions 1 and 9. */
    private fun altered(s: JxrPlane, overlap: Int, tScale: Int) {
        val cf = s.cf
        val left = s.column == 0
        val right = s.column == s.mbWidth
        val top = s.row == 0
        val bottom = s.row == s.mbHeight
        val topOrBottom = top || bottom
        val leftOrRight = left || right
        val bottomOrRight = bottom || right
        val leftAdjacent = s.column == 1
        val rightAdjacent = s.column == s.mbWidth - 1
        if (s.image.hardTiles) {
            // jxrlib walks the tile rows (uiTileY) against the column here and the tile columns against the row.
            val tileX = s.tileX
            val tileY = s.tileY
            val numV = s.numV
            val numH = s.numH
            if (s.column == 0) {
                s.vertTileBoundary = false
                s.tY = 0
            }
            s.oneMBLeftVertTB = false
            s.oneMBRightVertTB = false
            if (s.tY > 0 && s.tY <= numH && s.column - 1 == tileY[s.tY]) s.oneMBRightVertTB = true
            if (s.tY < numH && s.column == tileY[s.tY + 1]) {
                s.vertTileBoundary = true
                s.tY++
            } else {
                s.vertTileBoundary = false
            }
            if (s.tY < numH && s.column + 1 == tileY[s.tY + 1]) s.oneMBLeftVertTB = true
            if (s.row == 0) {
                s.horiTileBoundary = false
                s.tX = 0
            } else if (s.mbY != s.row && s.tX < numV && s.row == tileX[s.tX + 1]) {
                s.horiTileBoundary = true
                s.tX++
            } else if (s.mbY != s.row) {
                s.horiTileBoundary = false
            }
        } else {
            s.vertTileBoundary = false
            s.horiTileBoundary = false
            s.oneMBLeftVertTB = false
            s.oneMBRightVertTB = false
        }
        s.mbX = s.column
        s.mbY = s.row
        val vtb = s.vertTileBoundary
        val htb = s.horiTileBoundary
        val leftEdge = left || vtb
        val rightEdge = right || vtb
        val topEdge = top || htb
        val bottomEdge = bottom || htb
        val oneRight = leftAdjacent || s.oneMBRightVertTB
        val oneLeft = rightAdjacent || s.oneMBLeftVertTB
        if (tScale < 16) {
            for (i in 0 until s.planes) {
                val p = s.buf[i]
                val p0 = s.p0[i]
                val p1 = s.p1[i]
                if (!bottomOrRight) {
                    idct4x4Stage2(p, p1)
                    if (s.scaled) normalizeDec(p, p1, i != 0)
                }
                if (overlap == 2) {
                    if (topEdge && leftEdge) post4Alt(p, p1 + 0, p1 + 64, p1 + 0 + 16, p1 + 64 + 16)
                    if (topEdge && rightEdge) post4Alt(p, p1 - 128, p1 - 64, p1 - 128 + 16, p1 - 64 + 16)
                    if (bottomEdge && leftEdge) post4Alt(p, p0 + 32, p0 + 96, p0 + 32 + 16, p0 + 96 + 16)
                    if (bottomEdge && rightEdge) post4Alt(p, p0 - 96, p0 - 32, p0 - 96 + 16, p0 - 32 + 16)
                    if ((leftOrRight || vtb) && (!topOrBottom && !htb)) {
                        if (leftEdge) {
                            post4Alt(p, p0 + 32, p0 + 48, p1 + 0, p1 + 16)
                            post4Alt(p, p0 + 96, p0 + 112, p1 + 64, p1 + 80)
                        }
                        if (rightEdge) {
                            val j = -128
                            post4Alt(p, p0 + j + 32, p0 + j + 48, p1 + j + 0, p1 + j + 16)
                            post4Alt(p, p0 + j + 96, p0 + j + 112, p1 + j + 64, p1 + j + 80)
                        }
                    }
                    if (!leftOrRight) {
                        if ((topOrBottom || htb) && !vtb) {
                            if (topEdge) {
                                val q = p1
                                post4Alt(p, q - 128, q - 64, q + 0, q + 64)
                                post4Alt(p, q - 112, q - 48, q + 16, q + 80)
                            }
                            if (bottomEdge) {
                                val q = p0 + 32
                                post4Alt(p, q - 128, q - 64, q + 0, q + 64)
                                post4Alt(p, q - 112, q - 48, q + 16, q + 80)
                            }
                        }
                        if (!topOrBottom && !htb && !vtb) post4x4Stage2Split(p, p0, p1, true)
                    }
                }
                if (tScale >= 4) continue
                if (!top) {
                    var j = if (left) 32 else -96
                    while (j < (if (right) 32 else 160)) {
                        idct4x4Stage1(p, p0 + j + 0)
                        idct4x4Stage1(p, p0 + j + 16)
                        j += 64
                    }
                }
                if (!bottom) {
                    var j = if (left) 0 else -128
                    while (j < (if (right) 0 else 128)) {
                        idct4x4Stage1(p, p1 + j + 0)
                        idct4x4Stage1(p, p1 + j + 16)
                        j += 64
                    }
                }
                if (overlap != 0) {
                    if (leftOrRight || vtb) {
                        if (topEdge && leftEdge) post4Alt(p, p1 + 0, p1 + 1, p1 + 2, p1 + 3)
                        if (topEdge && rightEdge) post4Alt(p, p1 - 59, p1 - 60, p1 - 57, p1 - 58)
                        if (bottomEdge && leftEdge) post4Alt(p, p0 + 48 + 10, p0 + 48 + 11, p0 + 48 + 8, p0 + 48 + 9)
                        if (bottomEdge && rightEdge) post4Alt(p, p0 - 1, p0 - 2, p0 - 3, p0 - 4)
                        if (leftEdge) edgeColumn(p, p0, p1, 0 + 10, top, bottom, topOrBottom || htb)
                        if (rightEdge) edgeColumn(p, p0, p1, -64 + 14, top, bottom, topOrBottom || htb)
                    }
                    val jStart = if (left) 0 else -192
                    val jEnd = if (right) -64 else 64
                    if (topEdge) {
                        var j = jStart
                        while (j < jEnd) {
                            if (!vtb || j != -64) {
                                val q = p1 + j
                                post4Alt(p, q + 5, q + 4, q + 64, q + 65)
                                post4Alt(p, q + 7, q + 6, q + 66, q + 67)
                                post4x4Stage1(p, p1 + j, 0, 0, false, true)
                            }
                            j += 64
                        }
                    }
                    if (bottomEdge) {
                        var j = jStart
                        while (j < jEnd) {
                            if (!vtb || j != -64) {
                                post4x4Stage1(p, p0 + 16 + j, 0, 0, false, true)
                                post4x4Stage1(p, p0 + 32 + j, 0, 0, false, true)
                                val q = p0 + 48 + j
                                post4Alt(p, q + 15, q + 14, q + 74, q + 75)
                                post4Alt(p, q + 13, q + 12, q + 72, q + 73)
                            }
                            j += 64
                        }
                    }
                    if (!top && !bottom && !htb) {
                        var j = jStart
                        while (j < jEnd) {
                            if (!vtb || j != -64) {
                                post4x4Stage1(p, p0 + 16 + j, 0, 0, false, true)
                                post4x4Stage1(p, p0 + 32 + j, 0, 0, false, true)
                                post4x4Stage1Split(p, p0 + 48 + j, p1 + j, 0, 0, false, true)
                                post4x4Stage1(p, p1 + j, 0, 0, false, true)
                            }
                            j += 64
                        }
                    }
                }
            }
        }
        if (cf == JxrColor.YUV_420 && tScale < 16) {
            for (i in 0 until 2) {
                val p = s.buf[1 + i]
                val p0 = s.p0[1 + i]
                val p1 = s.p1[1 + i]
                val before = s.predBefore[i]
                val after = s.predAfter[i]
                if (!bottomOrRight) {
                    if (!s.scaled) dct2x2dn(p, p1, p1 + 32, p1 + 16, p1 + 48) else dct2x2dnDec(p, p1, p1 + 32, p1 + 16, p1 + 48)
                }
                if (overlap == 2) {
                    if (oneRight && topEdge) p[p1 - 64 + 0] -= p[p1 - 64 + 32]
                    if (oneLeft && topEdge) before[0] = p[p1 + 0]
                    if (rightEdge && topEdge) p[p1 - 64 + 32] -= before[0]
                    if (oneRight && bottomEdge) p[p0 - 64 + 16] -= p[p0 - 64 + 48]
                    if (oneLeft && bottomEdge) before[1] = p[p0 + 16]
                    if (rightEdge && bottomEdge) p[p0 - 64 + 48] -= before[1]
                    if ((leftOrRight || vtb) && !topOrBottom && !htb) {
                        if (leftEdge) post2Alt(p, p0 + 0 + 16, p1 + 0)
                        if (rightEdge) post2Alt(p, p0 + -32 + 16, p1 + -32)
                    }
                    if (!leftOrRight) {
                        if ((topOrBottom || htb) && !vtb) {
                            if (topEdge) post2Alt(p, p1 - 32, p1)
                            if (bottomEdge) post2Alt(p, p0 + 16 - 32, p0 + 16)
                        } else if (!topOrBottom && !htb && !vtb) {
                            post2x2(p, p0 - 16, p0 + 16, p1 - 32, p1, true)
                        }
                    }
                    if (oneRight && topEdge) p[p1 - 64 + 0] += p[p1 - 64 + 32]
                    if (oneLeft && topEdge) after[0] = p[p1 + 0]
                    if (rightEdge && topEdge) p[p1 - 64 + 32] += after[0]
                    if (oneRight && bottomEdge) p[p0 - 64 + 16] += p[p0 - 64 + 48]
                    if (oneLeft && bottomEdge) after[1] = p[p0 + 16]
                    if (rightEdge && bottomEdge) p[p0 - 64 + 48] += after[1]
                }
                if (tScale >= 4) continue
                if (!top) {
                    var j = if (left) 48 else if (oneRight) -48 else -16
                    while (j < (if (rightEdge) 16 else 48)) {
                        idct4x4Stage1(p, p0 + j)
                        j += 32
                    }
                }
                if (!bottom) {
                    var j = if (left) 32 else if (oneRight) -64 else -32
                    while (j < (if (rightEdge) 0 else 32)) {
                        idct4x4Stage1(p, p1 + j)
                        j += 32
                    }
                }
                if (overlap != 0) {
                    if (topEdge && oneRight) post4Alt(p, p1 - 64 + 0, p1 - 64 + 1, p1 - 64 + 2, p1 - 64 + 3)
                    if (topEdge && rightEdge) post4Alt(p, p1 - 27, p1 - 28, p1 - 25, p1 - 26)
                    if (bottomEdge && oneRight) post4Alt(p, p0 - 64 + 16 + 10, p0 - 64 + 16 + 11, p0 - 64 + 16 + 8, p0 - 64 + 16 + 9)
                    if (bottomEdge && rightEdge) post4Alt(p, p0 - 1, p0 - 2, p0 - 3, p0 - 4)
                    if (!left && !top) {
                        if (oneRight) {
                            if (!bottom && !htb) {
                                post4Alt(p, p0 - 64 + 26, p0 - 64 + 24, p1 - 64 + 0, p1 - 64 + 2)
                                post4Alt(p, p0 - 64 + 27, p0 - 64 + 25, p1 - 64 + 1, p1 - 64 + 3)
                            }
                            post4Alt(p, p0 - 64 + 10, p0 - 64 + 8, p0 - 64 + 16, p0 - 64 + 18)
                            post4Alt(p, p0 - 64 + 11, p0 - 64 + 9, p0 - 64 + 17, p0 - 64 + 19)
                        }
                        if (bottomEdge) {
                            var q = p0 - 48
                            post4Alt(p, q + 15, q + 14, q + 42, q + 43)
                            post4Alt(p, q + 13, q + 12, q + 40, q + 41)
                            if (!right && !vtb) {
                                q = p0 - 16
                                post4Alt(p, q + 15, q + 14, q + 42, q + 43)
                                post4Alt(p, q + 13, q + 12, q + 40, q + 41)
                            }
                        } else {
                            post4x4Stage1Split(p, p0 - 48, p1 - 16 - 48, 32, 0, false, true)
                            if (!right && !vtb) post4x4Stage1Split(p, p0 - 16, p1 - 16 - 16, 32, 0, false, true)
                        }
                        if (rightEdge) {
                            if (!bottom && !htb) {
                                post4Alt(p, p0 - 2, p0 - 4, p1 - 28, p1 - 26)
                                post4Alt(p, p0 - 1, p0 - 3, p1 - 27, p1 - 25)
                            }
                            post4Alt(p, p0 - 18, p0 - 20, p0 - 12, p0 - 10)
                            post4Alt(p, p0 - 17, p0 - 19, p0 - 11, p0 - 9)
                        } else {
                            post4x4Stage1(p, p0 - 32, 32, 0, false, true)
                        }
                        post4x4Stage1(p, p0 - 64, 32, 0, false, true)
                    }
                    if (topEdge) {
                        if (!left) {
                            val q = p1 - 64 + 4
                            post4Alt(p, q + 1, q + 0, q + 28, q + 29)
                            post4Alt(p, q + 3, q + 2, q + 30, q + 31)
                        }
                        if (!left && !right && !vtb) {
                            val q = p1 - 32 + 4
                            post4Alt(p, q + 1, q + 0, q + 28, q + 29)
                            post4Alt(p, q + 3, q + 2, q + 30, q + 31)
                        }
                    }
                }
            }
        }
        if (cf == JxrColor.YUV_422 && tScale < 16) {
            for (i in 0 until 2) {
                val p = s.buf[1 + i]
                val p0 = s.p0[1 + i]
                val p1 = s.p1[1 + i]
                val before = s.predBefore[i]
                val after = s.predAfter[i]
                if (!bottomOrRight && tScale < 16) {
                    p[p1] -= (p[p1 + 32] + 1) shr 1
                    p[p1 + 32] += p[p1]
                    if (!s.scaled) {
                        dct2x2dn(p, p1 + 0, p1 + 64, p1 + 16, p1 + 80)
                        dct2x2dn(p, p1 + 32, p1 + 96, p1 + 48, p1 + 112)
                    } else {
                        dct2x2dnDec(p, p1 + 0, p1 + 64, p1 + 16, p1 + 80)
                        dct2x2dnDec(p, p1 + 32, p1 + 96, p1 + 48, p1 + 112)
                    }
                }
                if (overlap == 2) {
                    if (oneRight && topEdge) p[p1 - 128 + 0] -= p[p1 - 128 + 64]
                    if (oneLeft && topEdge) before[0] = p[p1 + 0]
                    if (rightEdge && topEdge) p[p1 - 128 + 64] -= before[0]
                    if (oneRight && bottomEdge) p[p0 - 128 + 48] -= p[p0 - 128 + 112]
                    if (oneLeft && bottomEdge) before[1] = p[p0 + 48]
                    if (rightEdge && bottomEdge) p[p0 - 128 + 112] -= before[1]
                    if (!bottom) {
                        if (leftOrRight || vtb) {
                            if (!top && !htb) {
                                if (leftEdge) post2Alt(p, p0 + 48 + 0, p1 + 0)
                                if (rightEdge) post2Alt(p, p0 + 48 + -64, p1 + -64)
                            }
                            if (leftEdge) post2Alt(p, p1 + 16, p1 + 16 + 16)
                            if (rightEdge) post2Alt(p, p1 + -48, p1 + -48 + 16)
                        }
                        if (!leftOrRight && !vtb) {
                            if (topEdge) post2Alt(p, p1 - 64, p1) else post2x2(p, p0 - 16, p0 + 48, p1 - 64, p1, true)
                            post2x2(p, p1 - 48, p1 + 16, p1 - 32, p1 + 32, true)
                        }
                    }
                    if (bottomEdge && (!leftOrRight && !vtb)) post2Alt(p, p0 - 16, p0 + 48)
                    if (oneRight && topEdge) p[p1 - 128 + 0] += p[p1 - 128 + 64]
                    if (oneLeft && topEdge) after[0] = p[p1 + 0]
                    if (rightEdge && topEdge) p[p1 - 128 + 64] += after[0]
                    if (oneRight && bottomEdge) p[p0 - 128 + 48] += p[p0 - 128 + 112]
                    if (oneLeft && bottomEdge) after[1] = p[p0 + 48]
                    if (rightEdge && bottomEdge) p[p0 - 128 + 112] += after[1]
                }
                if (tScale >= 4) continue
                if (!top) {
                    var j = if (left) 112 else if (oneRight) -80 else -16
                    while (j < (if (rightEdge) 48 else 112)) {
                        idct4x4Stage1(p, p0 + j)
                        j += 64
                    }
                }
                if (!bottom) {
                    var j = if (left) 64 else if (oneRight) -128 else -64
                    while (j < (if (rightEdge) 0 else 64)) {
                        idct4x4Stage1(p, p1 + j + 0)
                        idct4x4Stage1(p, p1 + j + 16)
                        idct4x4Stage1(p, p1 + j + 32)
                        j += 64
                    }
                }
                if (overlap != 0) {
                    if (topEdge && oneRight) post4Alt(p, p1 - 128 + 0, p1 - 128 + 1, p1 - 128 + 2, p1 - 128 + 3)
                    if (topEdge && rightEdge) post4Alt(p, p1 - 59, p1 - 60, p1 - 57, p1 - 58)
                    if (bottomEdge && oneRight) post4Alt(p, p0 - 128 + 48 + 10, p0 - 128 + 48 + 11, p0 - 128 + 48 + 8, p0 - 128 + 48 + 9)
                    if (bottomEdge && rightEdge) post4Alt(p, p0 - 1, p0 - 2, p0 - 3, p0 - 4)
                    val jStart = if (left) 0 else -128
                    val jEnd = if (rightEdge) -64 else 0
                    if (!top) {
                        if (oneRight) {
                            val q = p0 + 32 + 10 - 128
                            post4Alt(p, q + 0, q - 2, q + 6, q + 8)
                            post4Alt(p, q + 1, q - 1, q + 7, q + 9)
                        }
                        if (rightEdge) {
                            val q = p0 - 32 + 14
                            post4Alt(p, q + 0, q - 2, q + 6, q + 8)
                            post4Alt(p, q + 1, q - 1, q + 7, q + 9)
                        }
                        var j = jStart
                        while (j < jEnd) {
                            post4x4Stage1(p, p0 + j + 32, 0, 0, false, true)
                            j += 64
                        }
                    }
                    if (!bottom) {
                        if (oneRight) {
                            var q = p1 + 0 + 10 - 128
                            post4Alt(p, q + 0, q - 2, q + 6, q + 8)
                            post4Alt(p, q + 1, q - 1, q + 7, q + 9)
                            q += 16
                            post4Alt(p, q + 0, q - 2, q + 6, q + 8)
                            post4Alt(p, q + 1, q - 1, q + 7, q + 9)
                        }
                        if (rightEdge) {
                            var q = p1 - 64 + 14
                            post4Alt(p, q + 0, q - 2, q + 6, q + 8)
                            post4Alt(p, q + 1, q - 1, q + 7, q + 9)
                            q += 16
                            post4Alt(p, q + 0, q - 2, q + 6, q + 8)
                            post4Alt(p, q + 1, q - 1, q + 7, q + 9)
                        }
                        var j = jStart
                        while (j < jEnd) {
                            post4x4Stage1(p, p1 + j + 0, 0, 0, false, true)
                            post4x4Stage1(p, p1 + j + 16, 0, 0, false, true)
                            j += 64
                        }
                    }
                    if (topOrBottom || htb) {
                        if (topEdge) {
                            val q = p1 + 5
                            var j = jStart
                            while (j < jEnd) {
                                post4Alt(p, q + j + 0, q + j - 1, q + j + 59, q + j + 60)
                                post4Alt(p, q + j + 2, q + j + 1, q + j + 61, q + j + 62)
                                j += 64
                            }
                        }
                        if (bottomEdge) {
                            val q = p0 + 48 + 13
                            var j = jStart
                            while (j < jEnd) {
                                post4Alt(p, q + j + 0, q + j - 1, q + j + 59, q + j + 60)
                                post4Alt(p, q + j + 2, q + j + 1, q + j + 61, q + j + 62)
                                j += 64
                            }
                        }
                    } else {
                        if (oneRight) {
                            val j = 0 + 0 - 128
                            post4Alt(p, p0 + j + 48 + 10 + 0, p0 + j + 48 + 10 - 2, p1 + j + 0, p1 + j + 2)
                            post4Alt(p, p0 + j + 48 + 10 + 1, p0 + j + 48 + 10 - 1, p1 + j + 1, p1 + j + 3)
                        }
                        if (rightEdge) {
                            val j = -64 + 4
                            post4Alt(p, p0 + j + 48 + 10 + 0, p0 + j + 48 + 10 - 2, p1 + j + 0, p1 + j + 2)
                            post4Alt(p, p0 + j + 48 + 10 + 1, p0 + j + 48 + 10 - 1, p1 + j + 1, p1 + j + 3)
                        }
                        var j = jStart
                        while (j < jEnd) {
                            post4x4Stage1Split(p, p0 + j + 48, p1 + j + 0, 0, 0, false, true)
                            j += 64
                        }
                    }
                }
            }
        }
    }

    /** The vertical 4-point filters down a left (j = 10) or right (j = -50) image or tile edge, in the luma loop of the altered transform. */
    private fun edgeColumn(p: IntArray, p0: Int, p1: Int, j: Int, top: Boolean, bottom: Boolean, horizontalEdge: Boolean) {
        if (!top) {
            val q = p0 + 16 + j
            post4Alt(p, q + 0, q - 2, q + 6, q + 8)
            post4Alt(p, q + 1, q - 1, q + 7, q + 9)
            post4Alt(p, q + 16, q + 14, q + 22, q + 24)
            post4Alt(p, q + 17, q + 15, q + 23, q + 25)
        }
        if (!bottom) {
            val q = p1 + j
            post4Alt(p, q + 0, q - 2, q + 6, q + 8)
            post4Alt(p, q + 1, q - 1, q + 7, q + 9)
        }
        if (!horizontalEdge) {
            post4Alt(p, p0 + 48 + j + 0, p0 + 48 + j - 2, p1 - 10 + j, p1 - 8 + j)
            post4Alt(p, p0 + 48 + j + 1, p0 + 48 + j - 1, p1 - 9 + j, p1 - 7 + j)
        }
    }
}
