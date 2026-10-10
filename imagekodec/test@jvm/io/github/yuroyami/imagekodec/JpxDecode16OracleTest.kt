package io.github.yuroyami.imagekodec

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JPEG 2000 at full precision through [ImageKodec.decode16] (#107), against opj_decompress,
 * whose PGM, PPM and PAM output keeps each component's own precision: 10, 12 and 16 bits,
 * gray, RGB through the RCT, RGBA with a `cdef` opacity channel, sYCC through its colour
 * conversion, and 9/7 files through the ICT. Each of our samples is OpenJPEG's replicated up
 * to 16 bits, exactly on lossless files. Skips cleanly without the OpenJPEG tools.
 */
class JpxDecode16OracleTest {

    private fun tools(): Boolean = Tools.hasAll("opj_compress", "opj_decompress")

    private fun run(vararg args: String): Pair<Int, String> {
        val proc = ProcessBuilder(args.toList()).redirectErrorStream(true).start()
        val out = ByteArrayOutputStream()
        proc.inputStream.copyTo(out)
        if (!proc.waitFor(120, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return -1 to ""
        }
        return proc.exitValue() to out.toString()
    }

    private fun temp(ext: String) = File.createTempFile("kite-jpx16", ext).apply { deleteOnExit() }

    private val width = 97
    private val height = 61

    /** A PGM, PPM or PAM of [depth] channels at [bits] bits, every sample using its low bits. */
    private fun source(depth: Int, bits: Int): File {
        val max = (1 shl bits) - 1
        val body = ByteArrayOutputStream()
        for (y in 0 until height) for (x in 0 until width) for (c in 0 until depth) {
            val v = when (c) {
                0 -> x * max / (width - 1)
                1 -> (y * 977 + x * 13) % (max + 1)
                2 -> ((x xor y) * 2654435761L % (max + 1)).toInt()
                else -> (x + y) * max / (width + height - 2)
            }
            body.write(v ushr 8); body.write(v and 255)
        }
        val header = when (depth) {
            1 -> "P5\n$width $height\n$max\n"
            3 -> "P6\n$width $height\n$max\n"
            else -> "P7\nWIDTH $width\nHEIGHT $height\nDEPTH 4\nMAXVAL $max\nTUPLTYPE RGB_ALPHA\nENDHDR\n"
        }
        return temp(if (depth == 1) ".pgm" else if (depth == 3) ".ppm" else ".pam").apply {
            writeBytes(header.encodeToByteArray() + body.toByteArray())
        }
    }

    private fun encode(src: File, vararg args: String): ByteArray {
        val out = temp(".jp2")
        val (code, text) = run(Tools.require("opj_compress").path, "-i", src.path, "-o", out.path, *args)
        assertEquals(0, code, "opj_compress failed: $text")
        return out.readBytes()
    }

    private class Raw(val channels: Int, val max: Int, val samples: IntArray)

    /** opj_decompress's reading of [data]: the samples at the components' own precision. */
    private fun reference(data: ByteArray, channels: Int): Raw {
        val input = temp(".jp2").apply { writeBytes(data) }
        val out = temp(if (channels == 1) ".pgm" else ".ppm")
        val (code, text) = run(Tools.require("opj_decompress").path, "-i", input.path, "-o", out.path)
        assertEquals(0, code, "opj_decompress failed: $text")
        val d = out.readBytes()
        val text8 = d.decodeToString(0, minOf(d.size, 200))
        val max: Int
        val start: Int
        if (text8.startsWith("P7")) {
            max = Regex("MAXVAL (\\d+)").find(text8)!!.groupValues[1].toInt()
            start = text8.indexOf("ENDHDR\n") + 7
        } else {
            // The magic, width, height and maximum, any of them after a comment line, then one byte of white space.
            val tokens = ArrayList<String>()
            var at = 0
            while (tokens.size < 4) {
                while (d[at].toInt().toChar().isWhitespace()) at++
                if (d[at] == '#'.code.toByte()) { while (d[at] != '\n'.code.toByte()) at++; continue }
                val from = at
                while (!d[at].toInt().toChar().isWhitespace()) at++
                tokens += d.decodeToString(from, at)
            }
            max = tokens[3].toInt()
            start = at + 1
        }
        val wide = max > 255
        val count = width * height * channels
        val samples = IntArray(count) {
            if (wide) ((d[start + 2 * it].toInt() and 255) shl 8) or (d[start + 2 * it + 1].toInt() and 255)
            else d[start + it].toInt() and 255
        }
        return Raw(channels, max, samples)
    }

    /**
     * A JP2 file with a `cdef` box in its header that makes the last of [channels] components
     * opacity of [type]: 1 straight, 2 premultiplied.
     */
    private fun withOpacity(jp2: ByteArray, channels: Int, type: Int = 1): ByteArray {
        fun u32(at: Int) = ((jp2[at].toInt() and 255) shl 24) or ((jp2[at + 1].toInt() and 255) shl 16) or
            ((jp2[at + 2].toInt() and 255) shl 8) or (jp2[at + 3].toInt() and 255)
        fun be(v: Int, n: Int) = ByteArray(n) { (v ushr (8 * (n - 1 - it))).toByte() }
        var at = 0
        while (jp2.decodeToString(at + 4, at + 8) != "jp2h") at += u32(at)
        val box = jp2.copyOfRange(at, at + u32(at))
        val entries = (0 until channels).fold(be(channels, 2)) { all, c ->
            all + be(c, 2) + be(if (c == channels - 1) type else 0, 2) + be(if (c == channels - 1) 0 else c + 1, 2)
        }
        val cdef = be(8 + entries.size, 4) + "cdef".encodeToByteArray() + entries
        val header = be(box.size + cdef.size, 4) + box.copyOfRange(4, box.size) + cdef
        return jp2.copyOfRange(0, at) + header + jp2.copyOfRange(at + box.size, jp2.size)
    }

    /** The JP2 file with its `colr` box's enumerated colour space set to [space]. */
    private fun withColourSpace(jp2: ByteArray, space: Int): ByteArray {
        val at = jp2.decodeToString().indexOf("colr")
        assertEquals(1, jp2[at + 4].toInt(), "colr is not enumerated")
        return jp2.copyOf().also { for (i in 0..3) it[at + 7 + i] = (space ushr (24 - 8 * i)).toByte() }
    }

    private fun compare(tag: String, data: ByteArray, channels: Int, bits: Int, tolerance: Int = 0) {
        val expected = reference(data, channels)
        assertEquals((1 shl bits) - 1, expected.max, "$tag: OpenJPEG's precision")
        val ours = ImageKodec.decode16(data)
        assertEquals(width, ours.width, tag)
        assertEquals(height, ours.height, tag)
        assertEquals(channels, ours.channels, tag)
        var worst = 0
        var sum = 0L
        var lowBits = 0
        for (i in expected.samples.indices) {
            val v = expected.samples[i]
            val got = ours.samples[i].toInt() and 0xFFFF
            if (tolerance == 0) {
                val wide = if (bits == 16) v else (v shl (16 - bits)) or (v shr (2 * bits - 16))
                assertEquals(wide, got, "$tag sample $i")
            } else {
                // Our top bits against OpenJPEG's sample: OpenJPEG's float rounding against our fixed point (#101).
                val d = kotlin.math.abs((got ushr (16 - bits)) - v)
                worst = maxOf(worst, d)
                sum += d
            }
            if (v and 0xFF != 0 || bits > 8 && (v shr (bits - 8)) != v ushr (bits - 8)) lowBits++
        }
        assertTrue(worst <= tolerance, "$tag: a sample $worst away")
        assertTrue(sum <= expected.samples.size / 10, "$tag: mean ${sum.toDouble() / expected.samples.size}")
        // The point of it: most samples have bits that 8 bits would lose.
        assertTrue(lowBits > expected.samples.size / 2, "$tag: too few samples with low bits")
        assertTrue(ImageKodec.decode(data).argb.contentEquals(ours.toBitmap().argb), "$tag: toBitmap differs from decode")
    }

    @Test
    fun grayAndRgbKeepEveryBit() {
        assumeTrue("OpenJPEG tools not installed", tools())
        for (bits in listOf(10, 12, 16)) {
            compare("$bits-bit gray", encode(source(1, bits)), 1, bits)
            compare("$bits-bit RGB through the RCT", encode(source(3, bits)), 3, bits)
            compare("$bits-bit RGB in layers", encode(source(3, bits), "-r", "40,10,1", "-n", "4"), 3, bits)
        }
    }

    @Test
    fun opacityKeepsEveryBit() {
        assumeTrue("OpenJPEG tools not installed", tools())
        for (bits in listOf(10, 16)) {
            compare("$bits-bit RGBA", withOpacity(encode(source(4, bits)), 4), 4, bits)
        }
    }

    @Test
    fun premultipliedOpacityDividesAtFullPrecision() {
        assumeTrue("OpenJPEG tools not installed", tools())
        // OpenJPEG hands premultiplied samples back as they are, so the expectation is built from its
        // samples: each color divided by its opacity at 16 bits, kept in the byte the 8-bit decode gives.
        val data = withOpacity(encode(source(4, 10)), 4, type = 2)
        val raw = reference(data, 4).samples
        val narrow = ImageKodec.decode(data)
        val wide = ImageKodec.decode16(data)
        assertEquals(4, wide.channels)
        assertTrue(narrow.argb.contentEquals(wide.toBitmap().argb), "toBitmap differs from decode")
        var finer = 0
        for (i in 0 until width * height) {
            val a16 = (raw[i * 4 + 3] shl 6) or (raw[i * 4 + 3] shr 4)
            assertEquals(a16, wide.samples[i * 4 + 3].toInt() and 0xFFFF, "opacity $i")
            for (c in 0 until 3) {
                val c16 = ((raw[i * 4 + c] shl 6) or (raw[i * 4 + c] shr 4)).toLong()
                val ideal = if (a16 == 0) 0L else minOf(65535L, (c16 * 65535 + a16 / 2) / a16)
                val v8 = (narrow.argb[i] ushr (16 - 8 * c)) and 0xFF
                val expected = ideal.coerceIn((v8 shl 8).toLong(), ((v8 shl 8) or 0xFF).toLong()).toInt()
                val got = wide.samples[i * 4 + c].toInt() and 0xFFFF
                assertEquals(expected, got, "color $c of $i")
                if (got != v8 * 257) finer++
            }
        }
        assertTrue(finer > width * height, "the division kept no more than 8 bits")
    }

    @Test
    fun syccConvertsAtFullPrecision() {
        assumeTrue("OpenJPEG tools not installed", tools())
        compare("12-bit sYCC", withColourSpace(encode(source(3, 12), "-mct", "0"), 18), 3, 12)
    }

    @Test
    fun irreversibleFilesStayWithinARoundingOfOpenJpeg() {
        assumeTrue("OpenJPEG tools not installed", tools())
        compare("12-bit gray 9/7", encode(source(1, 12), "-I", "-r", "4"), 1, 12, tolerance = 1)
        compare("12-bit RGB through the ICT", encode(source(3, 12), "-I", "-r", "6"), 3, 12, tolerance = 1)
    }
}
