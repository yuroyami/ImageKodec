package io.github.yuroyami.kiteimage.codec

import io.github.yuroyami.kiteimage.KiteBitmap
import io.github.yuroyami.kiteimage.internal.flate.Crc32
import io.github.yuroyami.kiteimage.internal.flate.Zlib
import kotlin.math.abs

/**
 * PNG encoder (lodepng as the structural reference, libpng's filter heuristic).
 *
 * Output shape: 8-bit truecolor — color type 6 (RGBA) when any pixel carries
 * alpha, color type 2 (RGB) otherwise. Palette/gray optimization is deliberately
 * out of scope for v1: every decoder reads truecolor, and correctness beats
 * bytes here. Per row, all five filters are tried and the one with the minimal
 * sum of absolute differences wins (the classic libpng "MSAD" heuristic) before
 * the stream goes through the vendored zlib deflate.
 */
internal object PngEncoder {

    fun encode(bitmap: KiteBitmap): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        val alpha = bitmap.hasTransparency()
        val bpp = if (alpha) 4 else 3
        val colorType = if (alpha) 6 else 2

        // Raw scanlines, interleaved samples.
        val rowBytes = w * bpp
        val raw = ByteArray(h * rowBytes)
        var o = 0
        for (p in bitmap.argb) {
            raw[o++] = ((p ushr 16) and 0xFF).toByte()
            raw[o++] = ((p ushr 8) and 0xFF).toByte()
            raw[o++] = (p and 0xFF).toByte()
            if (alpha) raw[o++] = ((p ushr 24) and 0xFF).toByte()
        }

        // Filter each row with the minimum-sum-of-absolute-differences choice.
        val filtered = ByteArray(h * (1 + rowBytes))
        val candidate = ByteArray(rowBytes)
        val best = ByteArray(rowBytes)
        for (y in 0 until h) {
            val rowOfs = y * rowBytes
            val prevOfs = rowOfs - rowBytes
            var bestFilter = 0
            var bestScore = Long.MAX_VALUE
            for (filter in 0..4) {
                var score = 0L
                for (i in 0 until rowBytes) {
                    val x = raw[rowOfs + i].toInt() and 0xFF
                    val a = if (i >= bpp) raw[rowOfs + i - bpp].toInt() and 0xFF else 0
                    val b = if (y > 0) raw[prevOfs + i].toInt() and 0xFF else 0
                    val c = if (y > 0 && i >= bpp) raw[prevOfs + i - bpp].toInt() and 0xFF else 0
                    val v = when (filter) {
                        0 -> x
                        1 -> (x - a) and 0xFF
                        2 -> (x - b) and 0xFF
                        3 -> (x - ((a + b) ushr 1)) and 0xFF
                        else -> {
                            val p = a + b - c
                            val pa = abs(p - a); val pb = abs(p - b); val pc = abs(p - c)
                            val pred = if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                            (x - pred) and 0xFF
                        }
                    }
                    candidate[i] = v.toByte()
                    // MSAD treats bytes as signed deltas around 0/256.
                    score += if (v < 128) v.toLong() else (256 - v).toLong()
                }
                if (score < bestScore) {
                    bestScore = score
                    bestFilter = filter
                    candidate.copyInto(best)
                }
            }
            filtered[y * (1 + rowBytes)] = bestFilter.toByte()
            best.copyInto(filtered, y * (1 + rowBytes) + 1)
        }

        // Assemble chunks.
        val out = io.github.yuroyami.kiteimage.internal.flate.ByteArrayBuilder(filtered.size / 2 + 128)
        out.append(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteArray(13)
        writeBe32(ihdr, 0, w)
        writeBe32(ihdr, 4, h)
        ihdr[8] = 8
        ihdr[9] = colorType.toByte()
        appendChunk(out, "IHDR", ihdr)
        appendChunk(out, "IDAT", Zlib.compress(filtered))
        appendChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun writeBe32(d: ByteArray, at: Int, v: Int) {
        d[at] = (v ushr 24).toByte()
        d[at + 1] = (v ushr 16).toByte()
        d[at + 2] = (v ushr 8).toByte()
        d[at + 3] = v.toByte()
    }

    private fun appendChunk(out: io.github.yuroyami.kiteimage.internal.flate.ByteArrayBuilder, type: String, data: ByteArray) {
        val len = ByteArray(4)
        writeBe32(len, 0, data.size)
        out.append(len)
        val typeBytes = ByteArray(4) { type[it].code.toByte() }
        out.append(typeBytes)
        out.append(data)
        val crc = Crc32()
        crc.update(typeBytes)
        crc.update(data)
        val crcBytes = ByteArray(4)
        writeBe32(crcBytes, 0, crc.value().toInt())
        out.append(crcBytes)
    }
}
