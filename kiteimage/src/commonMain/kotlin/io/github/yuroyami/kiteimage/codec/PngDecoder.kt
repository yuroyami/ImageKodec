package io.github.yuroyami.kiteimage.codec

import io.github.yuroyami.kiteimage.ImageDecodeException
import io.github.yuroyami.kiteimage.KiteBitmap
import io.github.yuroyami.kiteimage.UnsupportedImageException
import io.github.yuroyami.kiteimage.internal.ByteReader
import io.github.yuroyami.kiteimage.internal.flate.Crc32
import io.github.yuroyami.kiteimage.internal.flate.InflateException
import io.github.yuroyami.kiteimage.internal.flate.Zlib

/**
 * PNG decoder (W3C PNG spec / RFC 2083), ported against `stb_image.h`'s
 * `stbi__parse_png_file` with the spec as ground truth. Scope:
 *
 *  - all five color types — gray (0), RGB (2), palette (3), gray+alpha (4), RGBA (6)
 *  - all legal bit depths; 16-bit samples reduce to their high byte (stb's 8-bit
 *    behaviour), sub-byte gray scales by sample replication (1/2/4-bit → ×255/×85/×17)
 *  - all five row filters (None/Sub/Up/Average/Paeth)
 *  - `tRNS` transparency for types 0/2 (color-key) and 3 (per-entry alpha)
 *  - CRC verification on the chunks we consume (IHDR/PLTE/tRNS/IDAT); ancillary
 *    chunks we skip are not CRC-checked — real-world writers get those wrong,
 *    and stb doesn't check any CRC at all
 *
 * Interlaced (Adam7) files are recognised and rejected with a clear message —
 * next on the roadmap, tracked in PORTING_STATUS.md. Apple's proprietary CgBI
 * variant is detected up front for the same reason.
 */
internal object PngDecoder {

    private const val MAX_DIMENSION = 1 shl 24       // 16M px per side
    private const val MAX_PIXELS = 1L shl 28         // 268M px ≈ 1 GiB of ARGB; bomb guard

    fun decode(data: ByteArray): KiteBitmap {
        val r = ByteReader(data)

        // 8-byte signature: \x89 P N G \r \n \x1a \n (sniffed upstream, re-checked here
        // because decoders must stand alone).
        val sig = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        for (expected in sig) {
            if (r.u8() != expected) throw ImageDecodeException("not a PNG: bad signature")
        }

        // --- IHDR ---------------------------------------------------------------
        var chunk = readChunkHeader(r)
        if (chunk.type == "CgBI") {
            throw UnsupportedImageException(
                "Apple CgBI PNG (Xcode-crushed, raw deflate + swapped channels) is not supported",
            )
        }
        if (chunk.type != "IHDR" || chunk.length != 13) {
            throw ImageDecodeException("PNG: first chunk must be IHDR(13), got ${chunk.type}(${chunk.length})")
        }
        val ihdr = r.bytes(13)
        verifyCrc(r, "IHDR", ihdr)

        val h = ByteReader(ihdr)
        val widthL = h.u32be()
        val heightL = h.u32be()
        val bitDepth = h.u8()
        val colorType = h.u8()
        if (h.u8() != 0) throw ImageDecodeException("PNG: unknown compression method")
        if (h.u8() != 0) throw ImageDecodeException("PNG: unknown filter method")
        val interlace = h.u8()

        if (widthL <= 0 || heightL <= 0) throw ImageDecodeException("PNG: bad dimensions ${widthL}x$heightL")
        if (widthL > MAX_DIMENSION || heightL > MAX_DIMENSION || widthL * heightL > MAX_PIXELS) {
            throw ImageDecodeException("PNG: ${widthL}x$heightL exceeds safety limits")
        }
        val width = widthL.toInt()
        val height = heightL.toInt()
        if (interlace != 0 && interlace != 1) {
            throw ImageDecodeException("PNG: unknown interlace method $interlace")
        }

        val channels = when (colorType) {
            0 -> 1; 2 -> 3; 3 -> 1; 4 -> 2; 6 -> 4
            else -> throw ImageDecodeException("PNG: unknown color type $colorType")
        }
        val legalDepths = when (colorType) {
            0 -> intArrayOf(1, 2, 4, 8, 16)
            3 -> intArrayOf(1, 2, 4, 8)
            else -> intArrayOf(8, 16)
        }
        if (bitDepth !in legalDepths) {
            throw ImageDecodeException("PNG: bit depth $bitDepth is illegal for color type $colorType")
        }

        // --- remaining chunks ---------------------------------------------------
        var palette: IntArray? = null            // 0xFFRRGGBB entries
        var paletteAlpha: IntArray? = null       // parallel alpha, 255 default
        var trnsGray = -1                        // color-key at native depth, type 0
        var trnsR = -1; var trnsG = -1; var trnsB = -1   // color-key, type 2
        val idat = ArrayList<ByteArray>()
        var sawEnd = false

        while (!sawEnd) {
            chunk = readChunkHeader(r)
            when (chunk.type) {
                "PLTE" -> {
                    if (chunk.length % 3 != 0 || chunk.length > 256 * 3) {
                        throw ImageDecodeException("PNG: PLTE length ${chunk.length} invalid")
                    }
                    val raw = r.bytes(chunk.length)
                    verifyCrc(r, "PLTE", raw)
                    palette = IntArray(raw.size / 3) { i ->
                        val rr = raw[i * 3].toInt() and 0xFF
                        val g = raw[i * 3 + 1].toInt() and 0xFF
                        val b = raw[i * 3 + 2].toInt() and 0xFF
                        (0xFF shl 24) or (rr shl 16) or (g shl 8) or b
                    }
                }
                "tRNS" -> {
                    val raw = r.bytes(chunk.length)
                    verifyCrc(r, "tRNS", raw)
                    val t = ByteReader(raw)
                    when (colorType) {
                        3 -> {
                            val pal = palette ?: throw ImageDecodeException("PNG: tRNS before PLTE")
                            if (raw.size > pal.size) throw ImageDecodeException("PNG: tRNS longer than palette")
                            paletteAlpha = IntArray(pal.size) { 255 }
                            for (i in raw.indices) paletteAlpha[i] = raw[i].toInt() and 0xFF
                        }
                        0 -> trnsGray = t.u16be()
                        2 -> { trnsR = t.u16be(); trnsG = t.u16be(); trnsB = t.u16be() }
                        else -> throw ImageDecodeException("PNG: tRNS not allowed for color type $colorType")
                    }
                }
                "IDAT" -> {
                    val raw = r.bytes(chunk.length)
                    verifyCrc(r, "IDAT", raw)
                    idat.add(raw)
                }
                "IEND" -> {
                    r.skip(4)   // CRC of empty data; constant, nothing to protect
                    sawEnd = true
                }
                else -> {
                    // Ancillary chunk we don't consume (tEXt, gAMA, pHYs, …): skip data
                    // + CRC without verifying — see class doc.
                    r.skip(chunk.length)
                    r.skip(4)
                }
            }
        }

        if (colorType == 3 && palette == null) throw ImageDecodeException("PNG: palette image without PLTE")
        if (idat.isEmpty()) throw ImageDecodeException("PNG: no IDAT data")

        // --- inflate ------------------------------------------------------------
        val compressed = concat(idat)
        // Exact size is knowable from IHDR: each row is one filter byte + ceil(bits/8).
        // Interlaced files are the sum over the seven Adam7 passes, each an
        // independent sub-image with its own filter bytes.
        fun rowBytesFor(w: Int) = ((w.toLong() * channels * bitDepth + 7) / 8).toInt()
        val expected: Long = if (interlace == 0) {
            height.toLong() * (1 + rowBytesFor(width))
        } else {
            var total = 0L
            for (p in 0 until 7) {
                val wp = passWidth(width, p)
                val hp = passHeight(height, p)
                if (wp > 0 && hp > 0) total += hp.toLong() * (1 + rowBytesFor(wp))
            }
            total
        }
        val inflated = try {
            Zlib.decompress(compressed, maximumSize = expected)
        } catch (e: InflateException) {
            throw ImageDecodeException("PNG: IDAT inflate failed: ${e.message}", e)
        }
        if (inflated.size.toLong() != expected) {
            throw ImageDecodeException("PNG: inflated to ${inflated.size} bytes, expected $expected")
        }

        // The filter's "previous pixel" step: whole bytes for depths >= 8, one byte
        // for packed sub-byte rows (spec: filters operate on bytes, not samples).
        val fUnit = maxOf(1, (channels * bitDepth) / 8)
        val argb = IntArray(width * height)

        if (interlace == 0) {
            val rowBytes = rowBytesFor(width)
            unfilter(inflated, 0, height, rowBytes, fUnit)
            expand(
                inflated, 0, width, height, rowBytes, bitDepth, colorType,
                palette, paletteAlpha, trnsGray, trnsR, trnsG, trnsB, argb,
            ) { x, y -> y * width + x }
        } else {
            // Adam7: each pass unfilters + expands independently, scattering its
            // pixels to (xStart + x*xStep, yStart + y*yStep) in the final image.
            var ofs = 0
            for (p in 0 until 7) {
                val wp = passWidth(width, p)
                val hp = passHeight(height, p)
                if (wp == 0 || hp == 0) continue
                val rb = rowBytesFor(wp)
                unfilter(inflated, ofs, hp, rb, fUnit)
                val x0 = PASS_X_START[p]; val xs = PASS_X_STEP[p]
                val y0 = PASS_Y_START[p]; val ys = PASS_Y_STEP[p]
                expand(
                    inflated, ofs, wp, hp, rb, bitDepth, colorType,
                    palette, paletteAlpha, trnsGray, trnsR, trnsG, trnsB, argb,
                ) { x, y -> (y0 + y * ys) * width + (x0 + x * xs) }
                ofs += hp * (1 + rb)
            }
        }
        return KiteBitmap(width, height, argb)
    }

    // Adam7 pass geometry (PNG spec §8.2).
    private val PASS_X_START = intArrayOf(0, 4, 0, 2, 0, 1, 0)
    private val PASS_Y_START = intArrayOf(0, 0, 4, 0, 2, 0, 1)
    private val PASS_X_STEP = intArrayOf(8, 8, 4, 4, 2, 2, 1)
    private val PASS_Y_STEP = intArrayOf(8, 8, 8, 4, 4, 2, 2)

    private fun passWidth(w: Int, p: Int): Int {
        val start = PASS_X_START[p]
        val step = PASS_X_STEP[p]
        return if (w <= start) 0 else (w - start + step - 1) / step
    }

    private fun passHeight(h: Int, p: Int): Int {
        val start = PASS_Y_START[p]
        val step = PASS_Y_STEP[p]
        return if (h <= start) 0 else (h - start + step - 1) / step
    }

    // -------------------------------------------------------------------------

    private class ChunkHeader(val length: Int, val type: String)

    private fun readChunkHeader(r: ByteReader): ChunkHeader {
        val length = r.u32be()
        if (length < 0 || length > Int.MAX_VALUE.toLong()) {
            throw ImageDecodeException("PNG: chunk length $length out of range")
        }
        val type = buildString {
            repeat(4) {
                val c = r.u8()
                if (c !in 0x41..0x7A) throw ImageDecodeException("PNG: corrupt chunk type byte $c")
                append(c.toChar())
            }
        }
        return ChunkHeader(length.toInt(), type)
    }

    /** Consume the 4-byte chunk CRC and check it against type + data. */
    private fun verifyCrc(r: ByteReader, type: String, chunkData: ByteArray) {
        val stored = r.u32be()
        val crc = Crc32()
        crc.update(ByteArray(4) { type[it].code.toByte() })
        crc.update(chunkData)
        if (crc.value() != stored) {
            throw ImageDecodeException(
                "PNG: CRC mismatch in $type chunk (corrupt file)",
            )
        }
    }

    private fun concat(parts: List<ByteArray>): ByteArray {
        var total = 0
        for (p in parts) total += p.size
        val out = ByteArray(total)
        var at = 0
        for (p in parts) {
            p.copyInto(out, at)
            at += p.size
        }
        return out
    }

    /**
     * Reverse the per-row filters in place for a region starting at [base]
     * (the whole image, or one Adam7 pass). Layout: `height` rows of
     * `1 + rowBytes`, first byte = filter type. After this runs, the filter bytes
     * are stale and the sample bytes are raw.
     */
    private fun unfilter(d: ByteArray, base: Int, height: Int, rowBytes: Int, fUnit: Int) {
        for (y in 0 until height) {
            val rowStart = base + y * (1 + rowBytes) + 1
            val prevStart = rowStart - (1 + rowBytes)
            val filter = d[rowStart - 1].toInt() and 0xFF

            when (filter) {
                0 -> Unit
                1 -> { // Sub: + raw[x - fUnit]
                    for (i in fUnit until rowBytes) {
                        d[rowStart + i] = (d[rowStart + i] + d[rowStart + i - fUnit]).toByte()
                    }
                }
                2 -> { // Up: + prior[x]
                    if (y > 0) for (i in 0 until rowBytes) {
                        d[rowStart + i] = (d[rowStart + i] + d[prevStart + i]).toByte()
                    }
                }
                3 -> { // Average: + floor((left + up) / 2)
                    for (i in 0 until rowBytes) {
                        val left = if (i >= fUnit) d[rowStart + i - fUnit].toInt() and 0xFF else 0
                        val up = if (y > 0) d[prevStart + i].toInt() and 0xFF else 0
                        d[rowStart + i] = (d[rowStart + i] + ((left + up) ushr 1)).toByte()
                    }
                }
                4 -> { // Paeth
                    for (i in 0 until rowBytes) {
                        val a = if (i >= fUnit) d[rowStart + i - fUnit].toInt() and 0xFF else 0
                        val b = if (y > 0) d[prevStart + i].toInt() and 0xFF else 0
                        val c = if (y > 0 && i >= fUnit) d[prevStart + i - fUnit].toInt() and 0xFF else 0
                        val p = a + b - c
                        val pa = if (p >= a) p - a else a - p
                        val pb = if (p >= b) p - b else b - p
                        val pc = if (p >= c) p - c else c - p
                        val pred = if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                        d[rowStart + i] = (d[rowStart + i] + pred).toByte()
                    }
                }
                else -> throw ImageDecodeException("PNG: unknown filter type $filter in row $y")
            }
        }
    }

    /**
     * Expand a filtered-then-unfiltered region ([width]×[height] samples starting
     * at [base]) into [argb], with [dst] mapping region coordinates to final
     * pixel indices — identity for normal files, the pass scatter for Adam7.
     */
    private fun expand(
        d: ByteArray, base: Int, width: Int, height: Int, rowBytes: Int,
        bitDepth: Int, colorType: Int,
        palette: IntArray?, paletteAlpha: IntArray?,
        trnsGray: Int, trnsR: Int, trnsG: Int, trnsB: Int,
        argb: IntArray,
        dst: (x: Int, y: Int) -> Int,
    ) {
        val stride = 1 + rowBytes

        // Reads sample number `s` of a row as an Int at native depth. For 16-bit the
        // full value is returned (color-key compares need it); byte reduction to the
        // high byte happens at the callers.
        fun sample(rowStart: Int, s: Int): Int = when (bitDepth) {
            8 -> d[rowStart + s].toInt() and 0xFF
            16 -> ((d[rowStart + s * 2].toInt() and 0xFF) shl 8) or (d[rowStart + s * 2 + 1].toInt() and 0xFF)
            else -> {
                val bitPos = s * bitDepth
                val byte = d[rowStart + (bitPos ushr 3)].toInt() and 0xFF
                val shift = 8 - bitDepth - (bitPos and 7)
                (byte ushr shift) and ((1 shl bitDepth) - 1)
            }
        }

        // Scale a native-depth sample to 8-bit by replication.
        fun scale(v: Int): Int = when (bitDepth) {
            1 -> v * 255
            2 -> v * 85
            4 -> v * 17
            8 -> v
            else -> v ushr 8   // 16-bit → high byte
        }

        for (y in 0 until height) {
            val rowStart = base + y * stride + 1
            when (colorType) {
                0 -> for (x in 0 until width) {
                    val raw = sample(rowStart, x)
                    val g = scale(raw)
                    val a = if (raw == trnsGray) 0 else 0xFF
                    argb[dst(x, y)] = (a shl 24) or (g shl 16) or (g shl 8) or g
                }
                2 -> for (x in 0 until width) {
                    val rr = sample(rowStart, x * 3)
                    val gg = sample(rowStart, x * 3 + 1)
                    val bb = sample(rowStart, x * 3 + 2)
                    val a = if (rr == trnsR && gg == trnsG && bb == trnsB) 0 else 0xFF
                    argb[dst(x, y)] = (a shl 24) or (scale(rr) shl 16) or (scale(gg) shl 8) or scale(bb)
                }
                3 -> {
                    val pal = palette!!
                    for (x in 0 until width) {
                        val idx = sample(rowStart, x)
                        if (idx >= pal.size) {
                            throw ImageDecodeException("PNG: palette index $idx out of ${pal.size} entries")
                        }
                        val alpha = paletteAlpha?.get(idx) ?: 255
                        argb[dst(x, y)] = (pal[idx] and 0x00FFFFFF) or (alpha shl 24)
                    }
                }
                4 -> for (x in 0 until width) {
                    val g = scale(sample(rowStart, x * 2))
                    val a = scale(sample(rowStart, x * 2 + 1))
                    argb[dst(x, y)] = (a shl 24) or (g shl 16) or (g shl 8) or g
                }
                6 -> for (x in 0 until width) {
                    val rr = scale(sample(rowStart, x * 4))
                    val gg = scale(sample(rowStart, x * 4 + 1))
                    val bb = scale(sample(rowStart, x * 4 + 2))
                    val aa = scale(sample(rowStart, x * 4 + 3))
                    argb[dst(x, y)] = (aa shl 24) or (rr shl 16) or (gg shl 8) or bb
                }
            }
        }
    }
}
