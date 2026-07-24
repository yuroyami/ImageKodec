package io.github.yuroyami.kiteimage.codec

import io.github.yuroyami.kiteimage.ImageDecodeException
import io.github.yuroyami.kiteimage.KiteAnimation
import io.github.yuroyami.kiteimage.KiteBitmap
import io.github.yuroyami.kiteimage.KiteFrame
import io.github.yuroyami.kiteimage.internal.Budget
import io.github.yuroyami.kiteimage.internal.ByteReader

/**
 * GIF87a/89a decoder, ported against `stb_image.h`'s `stbi__gif_load_next` and
 * giflib's `DGifSlurp`, with the GIF89a spec + NETSCAPE2.0 extension note as
 * ground truth. Full container support:
 *
 *  - variable-width LZW with dictionary growth, mid-stream CLEAR codes, the
 *    KwKwK self-referencing case, and the "deferred clear" files that keep
 *    emitting 12-bit codes on a full dictionary
 *  - global and per-frame local color tables
 *  - 4-pass interlacing
 *  - full animation compositing: frame rects offset into the logical screen,
 *    transparency holes, and all three disposal methods (keep / restore-to-
 *    background / restore-to-previous)
 *  - frame delays (normalised like browsers: ≤1 cs → 100 ms) and the NETSCAPE
 *    loop count
 *
 * Compositing starts from a fully transparent canvas and "restore to background"
 * clears to transparent: what every modern renderer does; the spec's literal
 * background-color-index fill hasn't been honoured by anyone in decades.
 * Frames are presented as full composited canvases, so playback is just
 * "draw frame N, wait delay N".
 */
internal object GifDecoder {

    private const val MAX_DIMENSION = 1 shl 16       // GIF fields are u16 anyway
    private const val MAX_TOTAL_PIXELS = 1L shl 28   // canvas px × frames; bomb guard
    private const val MAX_CODES = 4096               // LZW dictionary limit (12-bit codes)

    /**
     * [cancellationCheck], when given, runs after each frame is composited and
     * may throw (e.g. `CoroutineContext.ensureActive`) to abandon a decode whose
     * result no longer matters: a fast-scrolled-away Coil request stops burning
     * CPU after at most one more frame instead of finishing the whole file.
     */
    fun decode(
        data: ByteArray,
        firstFrameOnly: Boolean,
        cancellationCheck: (() -> Unit)? = null,
    ): KiteAnimation {
        val r = ByteReader(data)

        // --- header + logical screen descriptor --------------------------------
        val magic = r.bytes(6).decodeToString()
        if (magic != "GIF87a" && magic != "GIF89a") {
            throw ImageDecodeException("not a GIF: bad magic \"$magic\"")
        }
        val width = r.u16le()
        val height = r.u16le()
        val packed = r.u8()
        r.skip(2)   // background color index + pixel aspect ratio: compositing ignores both
        if (width == 0 || height == 0) throw ImageDecodeException("GIF: zero dimension ${width}x$height")
        if (width.toLong() * height > MAX_TOTAL_PIXELS) {
            throw ImageDecodeException("GIF: ${width}x$height exceeds safety limits")
        }
        if (!Budget.fits(width, height, data.size)) {
            throw ImageDecodeException("GIF: ${width}x$height cannot come from ${data.size} bytes")
        }

        val globalTable: IntArray? = if (packed and 0x80 != 0) {
            readColorTable(r, sizeBits = (packed and 0x07) + 1)
        } else null

        // --- block loop ---------------------------------------------------------
        val canvas = IntArray(width * height)        // starts fully transparent
        val frames = ArrayList<KiteFrame>()
        var loopCount = 1                            // no NETSCAPE extension = play once

        // Pending graphic-control state; applies to the next image only.
        var disposal = 0
        var delayCs = 0
        var transparentIndex = -1

        while (true) {
            when (val block = r.u8()) {
                0x2C -> {   // image descriptor
                    val fx = r.u16le()
                    val fy = r.u16le()
                    val fw = r.u16le()
                    val fh = r.u16le()
                    val fPacked = r.u8()
                    if (fw == 0 || fh == 0) throw ImageDecodeException("GIF: empty frame rect")

                    val localTable: IntArray? = if (fPacked and 0x80 != 0) {
                        readColorTable(r, sizeBits = (fPacked and 0x07) + 1)
                    } else null
                    val table = localTable ?: globalTable
                        ?: throw ImageDecodeException("GIF: frame has no local color table and no global one exists")
                    val interlaced = fPacked and 0x40 != 0

                    val minCodeSize = r.u8()
                    val compressed = readSubBlocks(r)
                    var indices = lzwDecode(compressed, minCodeSize, fw * fh)
                    if (interlaced) indices = deinterlace(indices, fw, fh)

                    // Disposal 3 needs the pre-draw canvas back afterwards.
                    val before = if (disposal == 3) canvas.copyOf() else null

                    // Draw the frame rect onto the canvas; transparent indices leave
                    // the underlying canvas pixel alone. Out-of-screen parts of the
                    // rect (malformed but real) are clipped.
                    for (row in 0 until fh) {
                        val y = fy + row
                        if (y >= height) break
                        var src = row * fw
                        var dst = y * width + fx
                        for (col in 0 until fw) {
                            if (fx + col >= width) break
                            val idx = indices[src].toInt() and 0xFF
                            if (idx != transparentIndex) {
                                if (idx >= table.size) {
                                    throw ImageDecodeException("GIF: color index $idx outside ${table.size}-entry table")
                                }
                                canvas[dst] = table[idx]
                            }
                            src++; dst++
                        }
                    }

                    frames.add(
                        KiteFrame(
                            bitmap = KiteBitmap(width, height, canvas.copyOf()),
                            delayMillis = if (delayCs <= 1) 100 else delayCs * 10,
                            delayRawCentiseconds = delayCs,
                        ),
                    )
                    if (firstFrameOnly) return KiteAnimation(width, height, frames, loopCount)
                    cancellationCheck?.invoke()
                    if ((frames.size + 1).toLong() * width * height > MAX_TOTAL_PIXELS) {
                        throw ImageDecodeException("GIF: frame count exceeds the $MAX_TOTAL_PIXELS-pixel safety limit")
                    }

                    // Dispose AFTER presenting, preparing the canvas for the next frame.
                    when (disposal) {
                        2 -> {   // restore to background = clear the frame's rect to transparent
                            for (row in 0 until fh) {
                                val y = fy + row
                                if (y >= height) break
                                var dst = y * width + fx
                                for (col in 0 until fw) {
                                    if (fx + col >= width) break
                                    canvas[dst++] = 0
                                }
                            }
                        }
                        3 -> before!!.copyInto(canvas)
                        // 0/1 (and the undefined 4..7): leave the canvas as drawn
                    }

                    // Graphic control applies to exactly one image.
                    disposal = 0; delayCs = 0; transparentIndex = -1
                }

                0x21 -> {   // extension
                    when (r.u8()) {
                        0xF9 -> {   // graphic control
                            if (r.u8() != 4) throw ImageDecodeException("GIF: graphic control block size != 4")
                            val p = r.u8()
                            disposal = (p ushr 2) and 0x07
                            delayCs = r.u16le()
                            val t = r.u8()
                            transparentIndex = if (p and 1 != 0) t else -1
                            if (r.u8() != 0) throw ImageDecodeException("GIF: graphic control not terminated")
                        }
                        0xFF -> {   // application extension
                            val size = r.u8()
                            val app = r.bytes(size).decodeToString()
                            if (size == 11 && (app == "NETSCAPE2.0" || app == "ANIMEXTS1.0")) {
                                // One data sub-block: 0x01 + u16 loop count (0 = forever).
                                val sub = readSubBlocks(r)
                                if (sub.size >= 3 && sub[0].toInt() == 1) {
                                    loopCount = (sub[1].toInt() and 0xFF) or ((sub[2].toInt() and 0xFF) shl 8)
                                }
                            } else {
                                skipSubBlocks(r)
                            }
                        }
                        else -> skipSubBlocks(r)   // comment (0xFE), plain text (0x01), unknown
                    }
                }

                0x3B -> break   // trailer

                else -> throw ImageDecodeException("GIF: unexpected block 0x${block.toString(16)} at offset ${r.pos - 1}")
            }
        }

        if (frames.isEmpty()) throw ImageDecodeException("GIF: no image data before trailer")
        return KiteAnimation(width, height, frames, loopCount)
    }

    // -------------------------------------------------------------------------

    private fun readColorTable(r: ByteReader, sizeBits: Int): IntArray {
        val entries = 1 shl sizeBits
        val raw = r.bytes(entries * 3)
        return IntArray(entries) { i ->
            (0xFF shl 24) or
                ((raw[i * 3].toInt() and 0xFF) shl 16) or
                ((raw[i * 3 + 1].toInt() and 0xFF) shl 8) or
                (raw[i * 3 + 2].toInt() and 0xFF)
        }
    }

    private fun readSubBlocks(r: ByteReader): ByteArray {
        val parts = ArrayList<ByteArray>()
        var total = 0
        while (true) {
            val len = r.u8()
            if (len == 0) break
            val part = r.bytes(len)
            parts.add(part)
            total += len
        }
        val out = ByteArray(total)
        var at = 0
        for (p in parts) {
            p.copyInto(out, at)
            at += p.size
        }
        return out
    }

    private fun skipSubBlocks(r: ByteReader) {
        while (true) {
            val len = r.u8()
            if (len == 0) break
            r.skip(len)
        }
    }

    /**
     * GIF's variable-width LSB-first LZW. Decodes exactly [expected] indices;
     * surplus compressed data is ignored (real encoders pad), a shortfall is a
     * decode error. Handles mid-stream CLEAR, the KwKwK case (`code == avail`),
     * dictionary growth with code-width bumps at 2^width, and deferred-clear
     * streams that stay at 12 bits on a full table.
     */
    private fun lzwDecode(input: ByteArray, minCodeSize: Int, expected: Int): ByteArray {
        if (minCodeSize < 2 || minCodeSize > 11) {
            throw ImageDecodeException("GIF: LZW minimum code size $minCodeSize out of range")
        }
        val clear = 1 shl minCodeSize
        val end = clear + 1

        val prefix = IntArray(MAX_CODES)
        val suffix = ByteArray(MAX_CODES)
        val first = ByteArray(MAX_CODES)     // first byte of each code's chain, O(1) KwKwK
        for (i in 0 until clear) {
            suffix[i] = i.toByte()
            first[i] = i.toByte()
        }

        val out = ByteArray(expected)
        var outAt = 0
        val stack = ByteArray(MAX_CODES)

        // All hot-loop state lives in plain locals (no captured-var closures:
        // those box each `var` into a Ref object, a per-bit-read indirection
        // that Kotlin/Native in particular does not optimise away).
        var codeSize = minCodeSize + 1
        var codeMask = (1 shl codeSize) - 1
        var avail = clear + 2
        var oldCode = -1

        var bitBuf = 0
        var bitCnt = 0
        var inAt = 0

        while (outAt < expected) {
            while (bitCnt < codeSize) {
                if (inAt == input.size) {
                    throw ImageDecodeException("GIF: LZW data ended after $outAt of $expected pixels")
                }
                bitBuf = bitBuf or ((input[inAt++].toInt() and 0xFF) shl bitCnt)
                bitCnt += 8
            }
            val code = bitBuf and codeMask
            bitBuf = bitBuf ushr codeSize
            bitCnt -= codeSize

            when {
                code == clear -> {
                    codeSize = minCodeSize + 1
                    codeMask = (1 shl codeSize) - 1
                    avail = clear + 2
                    oldCode = -1
                }
                code == end -> {
                    if (outAt < expected) {
                        throw ImageDecodeException("GIF: LZW end code after $outAt of $expected pixels")
                    }
                }
                oldCode == -1 -> {
                    if (code >= clear) throw ImageDecodeException("GIF: first LZW code $code is not a literal")
                    out[outAt++] = code.toByte()
                    oldCode = code
                }
                else -> {
                    if (code > avail || (code == avail && avail == MAX_CODES)) {
                        throw ImageDecodeException("GIF: LZW code $code beyond dictionary ($avail)")
                    }
                    if (code == avail) {
                        // KwKwK: chain is oldCode's chain + oldCode's first byte;
                        // and that's exactly what the new dictionary entry becomes,
                        // so wire it up first and emit the new code's chain.
                        prefix[avail] = oldCode
                        suffix[avail] = first[oldCode]
                        first[avail] = first[oldCode]
                        avail++
                        outAt = emitChain(code, clear, prefix, suffix, stack, out, outAt, expected)
                    } else {
                        outAt = emitChain(code, clear, prefix, suffix, stack, out, outAt, expected)
                        if (avail < MAX_CODES) {
                            prefix[avail] = oldCode
                            suffix[avail] = first[code]
                            first[avail] = first[oldCode]
                            avail++
                        }
                    }
                    if (avail == (1 shl codeSize) && codeSize < 12) {
                        codeSize++
                        codeMask = (1 shl codeSize) - 1
                    }
                    oldCode = code
                }
            }
        }
        return out
    }

    /** Emit [code]'s chain into [out] at [outAt]; returns the new write position. */
    private fun emitChain(
        code: Int,
        clear: Int,
        prefix: IntArray,
        suffix: ByteArray,
        stack: ByteArray,
        out: ByteArray,
        outAt: Int,
        expected: Int,
    ): Int {
        var c = code
        var sp = 0
        while (c >= clear) {
            stack[sp++] = suffix[c]
            c = prefix[c]
            if (sp >= MAX_CODES) throw ImageDecodeException("GIF: corrupt LZW prefix chain")
        }
        stack[sp++] = suffix[c]
        var at = outAt
        while (sp > 0 && at < expected) out[at++] = stack[--sp]
        return at
    }

    /** Undo the 4-pass interlace: storage order → natural row order. */
    private fun deinterlace(src: ByteArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(src.size)
        var srcRow = 0
        for ((start, step) in listOf(0 to 8, 4 to 8, 2 to 4, 1 to 2)) {
            var y = start
            while (y < h) {
                src.copyInto(out, y * w, srcRow * w, (srcRow + 1) * w)
                srcRow++
                y += step
            }
        }
        return out
    }
}
