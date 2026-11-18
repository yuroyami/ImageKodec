package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.imagekodec.ImageMetadata
import io.github.yuroyami.imagekodec.JpegEncodeOptions
import io.github.yuroyami.imagekodec.JpegEncoderKind
import io.github.yuroyami.imagekodec.JpegGrayscale
import io.github.yuroyami.imagekodec.JpegSubsampling
import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.KiteBitmap16
import io.github.yuroyami.imagekodec.codec.jpegli.JpegliEncoder
import io.github.yuroyami.imagekodec.internal.cancellable
import io.github.yuroyami.imagekodec.internal.flate.MAX_ARRAY

/**
 * Baseline JPEG encoder: a faithful port of `stb_image_write.h`'s
 * `stbi_write_jpg_core` (fixed standard Huffman tables, AAN float fDCT, the
 * IJG-scaled quality→quant mapping, 4:2:0 subsampling at quality ≤ 90 and
 * 4:4:4 above, edge replication for partial MCUs).
 *
 * One deliberate divergence: arithmetic runs in Double, not Float. Kotlin/JS
 * doesn't round Float math to 32 bits between operations, so a Float port
 * would produce different files per target; Double is deterministic
 * everywhere. Output therefore may differ from C stbiw by a coefficient here
 * and there, so validity and quality are pinned by tests instead: a decode
 * round-trip PSNR check, and ImageIO reading the files back.
 *
 * Since #183 this makes the quantized coefficients, at 8 or 12 bits and with the subsampling
 * asked for, and [JpegWriter] writes them in any process of T.81; the calls without options
 * write stbiw's file, byte for byte. The lossless samples go to [JpegWriter] too.
 */
internal object JpegEncoder {

    private const val MAX_SIDE = 65535                // the frame header's X and Y are 16 bits

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

    internal val STD_DC_LUM_NRCODES = intArrayOf(0, 0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0)
    internal val STD_DC_LUM_VALUES = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)
    internal val STD_AC_LUM_NRCODES = intArrayOf(0, 0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7D)
    internal val STD_AC_LUM_VALUES = intArrayOf(
        0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07, 0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xA1, 0x08,
        0x23, 0x42, 0xB1, 0xC1, 0x15, 0x52, 0xD1, 0xF0, 0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0A, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x25, 0x26, 0x27, 0x28,
        0x29, 0x2A, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59,
        0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6A, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7A, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
        0x8A, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9A, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7, 0xA8, 0xA9, 0xAA, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6,
        0xB7, 0xB8, 0xB9, 0xBA, 0xC2, 0xC3, 0xC4, 0xC5, 0xC6, 0xC7, 0xC8, 0xC9, 0xCA, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7, 0xD8, 0xD9, 0xDA, 0xE1, 0xE2,
        0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9, 0xEA, 0xF1, 0xF2, 0xF3, 0xF4, 0xF5, 0xF6, 0xF7, 0xF8, 0xF9, 0xFA,
    )
    internal val STD_DC_CHR_NRCODES = intArrayOf(0, 0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0)
    internal val STD_DC_CHR_VALUES = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)
    internal val STD_AC_CHR_NRCODES = intArrayOf(0, 0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77)
    internal val STD_AC_CHR_VALUES = intArrayOf(
        0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71, 0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91,
        0xA1, 0xB1, 0xC1, 0x09, 0x23, 0x33, 0x52, 0xF0, 0x15, 0x62, 0x72, 0xD1, 0x0A, 0x16, 0x24, 0x34, 0xE1, 0x25, 0xF1, 0x17, 0x18, 0x19, 0x1A, 0x26,
        0x27, 0x28, 0x29, 0x2A, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58,
        0x59, 0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6A, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7A, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
        0x88, 0x89, 0x8A, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9A, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7, 0xA8, 0xA9, 0xAA, 0xB2, 0xB3, 0xB4,
        0xB5, 0xB6, 0xB7, 0xB8, 0xB9, 0xBA, 0xC2, 0xC3, 0xC4, 0xC5, 0xC6, 0xC7, 0xC8, 0xC9, 0xCA, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7, 0xD8, 0xD9, 0xDA,
        0xE2, 0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9, 0xEA, 0xF2, 0xF3, 0xF4, 0xF5, 0xF6, 0xF7, 0xF8, 0xF9, 0xFA,
    )

    /** A picture's samples a row at a time: red, green and blue of [bits] bits each, equal for a gray source. */
    internal class Source(val width: Int, val height: Int, val bits: Int, val gray: Boolean, val row: (y: Int, r: IntArray, g: IntArray, b: IntArray) -> Unit) {
        /** True when every pixel is gray. */
        fun allGray(): Boolean {
            if (gray) return true
            val r = IntArray(width)
            val g = IntArray(width)
            val b = IntArray(width)
            for (y in 0 until height) {
                row(y, r, g, b)
                for (x in 0 until width) if (r[x] != g[x] || g[x] != b[x]) return false
            }
            return true
        }

        companion object {
            fun of(bitmap: KiteBitmap): Source = Source(bitmap.width, bitmap.height, 8, false) { y, r, g, b ->
                val argb = bitmap.argb
                val base = y * bitmap.width
                for (x in 0 until bitmap.width) {
                    val p = argb[base + x]
                    r[x] = (p ushr 16) and 0xFF
                    g[x] = (p ushr 8) and 0xFF
                    b[x] = p and 0xFF
                }
            }

            fun of(bitmap: KiteBitmap16): Source = Source(bitmap.width, bitmap.height, 16, bitmap.channels <= 2) { y, r, g, b ->
                val s = bitmap.samples
                val n = bitmap.channels
                var at = y * bitmap.width * n
                for (x in 0 until bitmap.width) {
                    if (n <= 2) {
                        val v = s[at].toInt() and 0xFFFF
                        r[x] = v
                        g[x] = v
                        b[x] = v
                    } else {
                        r[x] = s[at].toInt() and 0xFFFF
                        g[x] = s[at + 1].toInt() and 0xFFFF
                        b[x] = s[at + 2].toInt() and 0xFFFF
                    }
                    at += n
                }
            }
        }
    }

    fun encode(
        bitmap: KiteBitmap,
        quality: Int,
        metadata: ImageMetadata? = null,
        kind: JpegEncoderKind = JpegEncoderKind.Fast,
    ): ByteArray = when {
        metadata != null && !metadata.isEmpty -> UltraHdr.encode(bitmap, quality, metadata, kind)
        else -> encodeWith(bitmap, quality, null, kind)
    }

    /** [bitmap] in three components by [kind], with the raw APPn [segments] after the header that kind writes. */
    fun encodeWith(bitmap: KiteBitmap, quality: Int, segments: ByteArray?, kind: JpegEncoderKind): ByteArray = when (kind) {
        JpegEncoderKind.Fast -> encode(bitmap, quality, segments, gray = false)
        JpegEncoderKind.Jpegli -> JpegliEncoder.encode(bitmap, quality, segments)
    }

    /** Where the segments go in a file [kind] writes: after the JFIF header, or right after SOI where it writes none. */
    fun headerEnd(kind: JpegEncoderKind): Int = when (kind) {
        JpegEncoderKind.Fast -> 20
        JpegEncoderKind.Jpegli -> 2
    }

    /**
     * [bitmap] as a baseline JPEG with the raw APPn [segments] after the JFIF header. A [gray]
     * encode writes one component, the luma, as a single-channel gain map is stored (#130).
     */
    fun encode(bitmap: KiteBitmap, quality: Int, segments: ByteArray?, gray: Boolean): ByteArray {
        require(quality in 1..100) { "JPEG quality must be 1..100, was $quality" }
        checkSides(bitmap.width, bitmap.height)
        val frame = dctFrame(Source.of(bitmap), quality, if (quality <= 90) SUB_420 else SUB_444, gray, 8, null)
        return JpegWriter.writeDct(frame, JpegHeaderPlan(true, -1, segments), JpegCodingPlan(false, false, false, 0), null)
    }

    internal fun checkSides(width: Int, height: Int) {
        require(width <= MAX_SIDE && height <= MAX_SIDE) {
            "JPEG stores each side in 16 bits, so ${width}x$height is larger than $MAX_SIDE"
        }
    }

    // ---- with options (#183) ----

    /** [bitmap] as [options] ask, with [metadata]. */
    fun encode(bitmap: KiteBitmap, options: JpegEncodeOptions, metadata: ImageMetadata, check: (() -> Unit)?): ByteArray {
        require(options.precision == 8) {
            "encodeJpeg writes 8-bit samples, so precision must be 8, was ${options.precision}; encodeJpeg16 writes the others"
        }
        return encode(Source.of(bitmap), options, metadata, check)
    }

    /** [bitmap] as [options] ask, with [metadata]: each sample the top [JpegEncodeOptions.precision] bits of its 16. */
    fun encode16(bitmap: KiteBitmap16, options: JpegEncodeOptions, metadata: ImageMetadata, check: (() -> Unit)?): ByteArray =
        encode(Source.of(bitmap), options, metadata, check)

    private fun encode(source: Source, options: JpegEncodeOptions, metadata: ImageMetadata, check: (() -> Unit)?): ByteArray {
        checkSides(source.width, source.height)
        if (options.lossless && options.restartInterval != 0) {
            require(options.restartInterval % source.width == 0) {
                "a lossless restart interval must hold whole rows of ${source.width} MCUs, was ${options.restartInterval}"
            }
        }
        return cancellable(check) { cancel ->
            cancel?.invoke()
            val gray = when (options.grayscale) {
                JpegGrayscale.Always -> true
                JpegGrayscale.Never -> false
                JpegGrayscale.Auto -> source.allGray()
            }
            val header = when {
                options.lossless && !gray -> JpegHeaderPlan(false, 0, null)
                options.encoder == JpegEncoderKind.Jpegli -> JpegHeaderPlan(false, -1, null)
                else -> JpegHeaderPlan(true, -1, null)
            }
            val write = { segments: ByteArray? -> write(source, options, gray, JpegHeaderPlan(header.jfif, header.adobeTransform, segments), cancel) }
            if (metadata.isEmpty) write(null) else UltraHdr.encode(source.width, source.height, metadata, header.segmentsAt, write)
        }
    }

    private fun write(source: Source, options: JpegEncodeOptions, gray: Boolean, header: JpegHeaderPlan, cancel: (() -> Unit)?): ByteArray {
        val coding = JpegCodingPlan(
            arithmetic = options.arithmetic,
            progressive = options.progressive,
            optimize = options.optimizeHuffman || options.precision > 8 || options.lossless,
            restartInterval = options.restartInterval,
            dcLower = options.arithmeticDcLower,
            dcUpper = options.arithmeticDcUpper,
            acThreshold = options.arithmeticAcThreshold,
        )
        if (options.lossless) return lossless(source, options, gray, header, coding, cancel)
        val quality = options.quality.coerceAtLeast(1)
        val frame = when (options.encoder) {
            JpegEncoderKind.Jpegli -> JpegliEncoder.frame(source, quality, gray, cancel)
            JpegEncoderKind.Fast -> {
                val sub = when (options.subsampling) {
                    JpegSubsampling.Auto -> if (quality <= 90) SUB_420 else SUB_444
                    JpegSubsampling.S444 -> SUB_444
                    JpegSubsampling.S422 -> SUB_422
                    JpegSubsampling.S420 -> SUB_420
                }
                dctFrame(source, quality, sub, gray, options.precision, cancel)
            }
        }
        return JpegWriter.writeDct(frame, header, coding, cancel)
    }

    /**
     * A lossless file: the top [JpegEncodeOptions.precision] bits of each sample, less the point
     * transform's, as one gray component or as R, G and B. A colour pixel made gray takes the
     * luma of JFIF's weights in 16-bit fixed point, rounded.
     */
    private fun lossless(source: Source, options: JpegEncodeOptions, gray: Boolean, header: JpegHeaderPlan, coding: JpegCodingPlan, cancel: (() -> Unit)?): ByteArray {
        val w = source.width
        val h = source.height
        val shift = source.bits - options.precision + options.pointTransform
        val n = if (gray) 1 else 3
        require(w.toLong() * h <= MAX_ARRAY) { "${w}x$h is too large to encode" }
        val planes = List(n) { IntArray(w * h) }
        val r = IntArray(w)
        val g = IntArray(w)
        val b = IntArray(w)
        for (y in 0 until h) {
            cancel?.invoke()
            source.row(y, r, g, b)
            val base = y * w
            for (x in 0 until w) {
                if (gray) {
                    val luma = if (r[x] == g[x] && g[x] == b[x]) r[x] else ((19595L * r[x] + 38470L * g[x] + 7471L * b[x] + 32768) shr 16).toInt()
                    planes[0][base + x] = luma ushr shift
                } else {
                    planes[0][base + x] = r[x] ushr shift
                    planes[1][base + x] = g[x] ushr shift
                    planes[2][base + x] = b[x] ushr shift
                }
            }
        }
        val ids = if (gray) intArrayOf(1) else intArrayOf('R'.code, 'G'.code, 'B'.code)
        return JpegWriter.writeLossless(planes, ids, w, h, options.precision, options.predictor, options.pointTransform, header, coding, cancel)
    }

    internal const val SUB_444 = 0
    internal const val SUB_422 = 1
    internal const val SUB_420 = 2

    /**
     * The quantized coefficients of [source] as stbiw makes them: the standard tables scaled by
     * [quality] the IJG way, the luma and chroma of JFIF, chroma averaged over 2 by 2 or 2 by 1
     * pixels by [sub], and edges replicated to whole MCUs. A [gray] frame is the luma alone, and a
     * [precision] of 12 centres the samples on 2048 instead of 128.
     */
    fun dctFrame(source: Source, quality: Int, sub: Int, gray: Boolean, precision: Int, cancel: (() -> Unit)?): JpegFramePlan {
        val width = source.width
        val height = source.height
        val q = if (quality < 50) 5000 / quality else 200 - quality * 2
        val yTable = IntArray(64)
        val uvTable = IntArray(64)
        for (i in 0 until 64) {
            yTable[ZIGZAG[i]] = ((YQT[i] * q + 50) / 100).coerceIn(1, 255)
            uvTable[ZIGZAG[i]] = ((UVQT[i] * q + 50) / 100).coerceIn(1, 255)
        }
        val fdY = DoubleArray(64)
        val fdUV = DoubleArray(64)
        var k = 0
        for (row in 0 until 8) {
            for (col in 0 until 8) {
                fdY[k] = 1.0 / (yTable[ZIGZAG[k]] * AASF[row] * AASF[col])
                fdUV[k] = 1.0 / (uvTable[ZIGZAG[k]] * AASF[row] * AASF[col])
                k++
            }
        }
        val hY = if (gray || sub == SUB_444) 1 else 2
        val vY = if (gray || sub != SUB_420) 1 else 2
        val mcusX = (width + 8 * hY - 1) / (8 * hY)
        val mcusY = (height + 8 * vY - 1) / (8 * vY)
        val luma = blocks(1, hY, vY, 0, mcusX, mcusY)
        val comps = if (gray) listOf(luma) else listOf(luma, blocks(2, 1, 1, 1, mcusX, mcusY), blocks(3, 1, 1, 1, mcusX, mcusY))
        val frame = JpegFramePlan(precision, width, height, comps, if (gray) listOf(yTable) else listOf(yTable, uvTable))

        val center = if (precision == 8) 128.0 else 2048.0
        val shift = source.bits - precision
        val padW = mcusX * 8 * hY
        val rows = 8 * vY
        val yPlane = DoubleArray(padW * rows)
        val uPlane = if (gray) DoubleArray(0) else DoubleArray(padW * rows)
        val vPlane = if (gray) DoubleArray(0) else DoubleArray(padW * rows)
        val cw = padW / hY
        val uSub = if (gray) DoubleArray(0) else DoubleArray(cw * 8)
        val vSub = if (gray) DoubleArray(0) else DoubleArray(cw * 8)
        val r = IntArray(width)
        val g = IntArray(width)
        val b = IntArray(width)
        val block = DoubleArray(64)
        for (my in 0 until mcusY) {
            cancel?.invoke()
            for (ry in 0 until rows) {
                source.row(minOf(my * rows + ry, height - 1), r, g, b)
                val base = ry * padW
                for (x in 0 until padW) {
                    if (x >= width) {
                        yPlane[base + x] = yPlane[base + width - 1]
                        if (!gray) {
                            uPlane[base + x] = uPlane[base + width - 1]
                            vPlane[base + x] = vPlane[base + width - 1]
                        }
                        continue
                    }
                    val rd = (r[x] ushr shift).toDouble()
                    val gd = (g[x] ushr shift).toDouble()
                    val bd = (b[x] ushr shift).toDouble()
                    yPlane[base + x] = +0.29900 * rd + 0.58700 * gd + 0.11400 * bd - center
                    if (!gray) {
                        uPlane[base + x] = -0.16874 * rd - 0.33126 * gd + 0.50000 * bd
                        vPlane[base + x] = +0.50000 * rd - 0.41869 * gd - 0.08131 * bd
                    }
                }
            }
            for (by in 0 until vY) for (bx in 0 until mcusX * hY) {
                for (yy in 0 until 8) for (xx in 0 until 8) block[yy * 8 + xx] = yPlane[(by * 8 + yy) * padW + bx * 8 + xx]
                quantize(block, fdY, luma, ((my * vY + by) * luma.blocksWide + bx) * 64)
            }
            if (gray) continue
            for (yy in 0 until 8) for (xx in 0 until cw) {
                val o = yy * cw + xx
                when (sub) {
                    SUB_444 -> {
                        uSub[o] = uPlane[yy * padW + xx]
                        vSub[o] = vPlane[yy * padW + xx]
                    }
                    SUB_422 -> {
                        val j = yy * padW + xx * 2
                        uSub[o] = (uPlane[j] + uPlane[j + 1]) * 0.5
                        vSub[o] = (vPlane[j] + vPlane[j + 1]) * 0.5
                    }
                    else -> {
                        val j = yy * 2 * padW + xx * 2
                        uSub[o] = (uPlane[j] + uPlane[j + 1] + uPlane[j + padW] + uPlane[j + padW + 1]) * 0.25
                        vSub[o] = (vPlane[j] + vPlane[j + 1] + vPlane[j + padW] + vPlane[j + padW + 1]) * 0.25
                    }
                }
            }
            for (c in 1..2) {
                val plane = if (c == 1) uSub else vSub
                val comp = comps[c]
                for (bx in 0 until mcusX) {
                    for (yy in 0 until 8) for (xx in 0 until 8) block[yy * 8 + xx] = plane[yy * cw + bx * 8 + xx]
                    quantize(block, fdUV, comp, (my * comp.blocksWide + bx) * 64)
                }
            }
        }
        return frame
    }

    /** A component of [mcusX] by [mcusY] MCUs, its size checked before the allocation. */
    internal fun blocks(id: Int, h: Int, v: Int, tq: Int, mcusX: Int, mcusY: Int): JpegComponentPlan {
        val count = mcusX.toLong() * h * mcusY * v * 64
        require(count <= MAX_ARRAY) { "the picture is too large to encode: $count coefficients in one component" }
        return JpegComponentPlan(id, h, v, tq, mcusX * h, mcusY * v)
    }

    // stbiw__jpg_processDU, its first half: the forward DCT of [block], quantized by [fdtbl] into [comp] at [at], zigzag order.
    private fun quantize(block: DoubleArray, fdtbl: DoubleArray, comp: JpegComponentPlan, at: Int) {
        for (row in 0 until 8) {
            val o = row * 8
            dct1d(block, o, o + 1, o + 2, o + 3, o + 4, o + 5, o + 6, o + 7)
        }
        for (c in 0 until 8) dct1d(block, c, c + 8, c + 16, c + 24, c + 32, c + 40, c + 48, c + 56)
        val out = comp.coefficients
        for (j in 0 until 64) {
            val v = block[j] * fdtbl[j]
            out[at + ZIGZAG[j]] = (if (v < 0) v - 0.5 else v + 0.5).toInt().toShort()
        }
    }

    // stbiw__jpg_DCT: AAN forward DCT on one 8-element span.
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
}
