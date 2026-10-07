package io.github.yuroyami.imagekodec

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Old-style JPEG TIFFs (compression 6) in the layouts 1990s writers left behind, built
 * here from `cjpeg` output, against libtiff's reading of them through `tiff2rgba` (#8).
 * Nothing current writes compression 6, and libtiff only reads it.
 *
 * - Headers at JPEGInterchangeFormat, and the scan cut into strips at its restart
 *   markers with the markers removed, the scan header at the head of the first strip,
 *   as Wang's Imaging writer laid them out.
 * - No stream headers at all: bare tables behind JPEGQTables, JPEGDCTables and
 *   JPEGACTables, and strips of scan data only.
 * - The same in a column of tiles taller than the image.
 *
 * libtiff hands the samples on without upsampling, and converts them with the file's
 * ReferenceBlackWhite, which some of these files set to the studio range as LONG values.
 */
class TiffOldJpegOracleTest {

    private fun tools(): Boolean = Tools.hasAll("magick", "cjpeg", "tiff2rgba")

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

    private fun temp(name: String, ext: String) =
        File.createTempFile("kite-ojpeg-$name", ext).apply { deleteOnExit() }

    private val width = 101
    private val height = 75

    /** Gradients and hard-edged blocks, [w] by [h], as a PPM for cjpeg. */
    private fun sourcePpm(w: Int, h: Int): File {
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val block = (x / 13 + y / 11) % 3
            px[y * w + x] = argb(0xFF, x * 255 / (w - 1), if (block == 0) 30 else y * 255 / (h - 1),
                if (block == 2) 230 else (x + y) * 255 / (w + h - 2))
        }
        val png = temp("src", ".png").apply { writeBytes(ImageKodec.encodePng(KiteBitmap(w, h, px))) }
        val ppm = temp("src", ".ppm")
        assertEquals(0, run(Tools.require("magick").path, png.path, ppm.path).first)
        return ppm
    }

    /** A cjpeg stream [w] by [h] with luma sampled [h0] by [v0] and a restart every [restartRows] MCU rows. */
    private fun cjpeg(w: Int, h: Int, h0: Int, v0: Int, restartRows: Int): ByteArray {
        val out = temp("cjpeg", ".jpg")
        val args = mutableListOf(Tools.require("cjpeg").path, "-quality", "90", "-sample", "${h0}x$v0,1x1,1x1")
        if (restartRows > 0) args += listOf("-restart", "$restartRows")
        args += listOf("-outfile", out.path, sourcePpm(w, h).path)
        val (code, text) = run(*args.toTypedArray())
        assertEquals(0, code, "cjpeg failed: $text")
        return out.readBytes()
    }

    private class Segment(val marker: Int, val start: Int, val end: Int)

    /** The marker segments of [jpeg] before its scan, and where the scan header starts. */
    private fun segments(jpeg: ByteArray): Pair<List<Segment>, Int> {
        val list = ArrayList<Segment>()
        var at = 2
        while (true) {
            val marker = jpeg[at + 1].toInt() and 0xFF
            if (marker == 0xDA) return list to at
            val length = ((jpeg[at + 2].toInt() and 0xFF) shl 8) or (jpeg[at + 3].toInt() and 0xFF)
            list += Segment(marker, at, at + 2 + length)
            at += 2 + length
        }
    }

    /** The entropy-coded data after the scan header, cut at its restart markers, which go. */
    private fun intervals(jpeg: ByteArray, sos: Int): Pair<ByteArray, List<ByteArray>> {
        val headerEnd = sos + 2 + (((jpeg[sos + 2].toInt() and 0xFF) shl 8) or (jpeg[sos + 3].toInt() and 0xFF))
        val parts = ArrayList<ByteArray>()
        var start = headerEnd
        var i = headerEnd
        while (i < jpeg.size - 1) {
            val b = jpeg[i].toInt() and 0xFF
            val next = jpeg[i + 1].toInt() and 0xFF
            if (b == 0xFF && next in 0xD0..0xD7) {
                parts += jpeg.copyOfRange(start, i)
                i += 2
                start = i
                continue
            }
            i++
        }
        parts += jpeg.copyOfRange(start, jpeg.size)   // the last keeps the EOI, as Wang's files do
        return jpeg.copyOfRange(sos, headerEnd) to parts
    }

    private class Field(val tag: Int, val type: Int, val values: LongArray)

    /** A little-endian TIFF: [fields], then [blobs] that fields point at by index, as -(index + 1). */
    private fun tiff(fields: List<Field>, blobs: List<ByteArray>): ByteArray {
        val sorted = fields.sortedBy { it.tag }
        fun size(f: Field) = f.values.size * when (f.type) { 3 -> 2; 5 -> 8; else -> 4 }
        var at = 8 + 2 + sorted.size * 12 + 4
        val outside = HashMap<Int, Int>()
        for (f in sorted) if (size(f) > 4) { outside[f.tag] = at; at += size(f) }
        val blobAt = IntArray(blobs.size)
        for (i in blobs.indices) { blobAt[i] = at; at += blobs[i].size }
        val out = ByteArray(at)
        var p = 0
        fun u16(v: Int) { out[p++] = v.toByte(); out[p++] = (v ushr 8).toByte() }
        fun u32(v: Long) { u16(v.toInt()); u16((v ushr 16).toInt()) }
        fun resolve(v: Long) = if (v < 0) blobAt[(-v - 1).toInt()].toLong() else v
        fun put(f: Field) {
            for (v in f.values) when (f.type) {
                3 -> u16(v.toInt())
                5 -> { u32(v); u32(1) }
                else -> u32(resolve(v))
            }
        }
        out[0] = 'I'.code.toByte(); out[1] = 'I'.code.toByte(); p = 2; u16(42); u32(8)
        u16(sorted.size)
        for (f in sorted) {
            u16(f.tag); u16(f.type); u32(f.values.size.toLong())
            val slot = p
            if (size(f) > 4) u32(outside.getValue(f.tag).toLong()) else put(f)
            p = slot + 4
        }
        u32(0)
        for (f in sorted) outside[f.tag]?.let { p = it; put(f) }
        for (i in blobs.indices) blobs[i].copyInto(out, blobAt[i])
        return out
    }

    private fun short(tag: Int, vararg v: Long) = Field(tag, 3, v)
    private fun long(tag: Int, vararg v: Long) = Field(tag, 4, v)

    private fun baseFields(h0: Int, v0: Int, studio: Boolean) = listOf(
        long(256, width.toLong()), long(257, height.toLong()), short(258, 8, 8, 8), short(259, 6),
        short(262, 6), short(277, 3), short(284, 1), short(530, h0.toLong(), v0.toLong()),
        if (studio) long(532, 16, 235, 128, 240, 128, 240) else Field(532, 5, longArrayOf(0, 255, 128, 255, 128, 255)),
    )

    /** Headers at JPEGInterchangeFormat without their DRI, and the scan in strips of [mcuRows] MCU rows. */
    private fun interchangeFormat(h0: Int, v0: Int, mcuRows: Int): ByteArray {
        val jpeg = cjpeg(width, height, h0, v0, mcuRows)
        val (segments, sos) = segments(jpeg)
        val header = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            segments.filter { it.marker != 0xDD }.map { jpeg.copyOfRange(it.start, it.end) }.reduce { a, b -> a + b }
        val (scanHeader, parts) = intervals(jpeg, sos)
        val strips = parts.mapIndexed { i, part -> if (i == 0) scanHeader + part else part }
        val fields = baseFields(h0, v0, studio = false) + listOf(
            long(273, *LongArray(strips.size) { -(it + 2L) }),
            long(278, if (mcuRows == 0) height.toLong() else (mcuRows * v0 * 8).toLong()),
            long(279, *LongArray(strips.size) { strips[it].size.toLong() }),
            long(513, -1), long(514, header.size.toLong()),
        )
        return tiff(fields, listOf(header) + strips)
    }

    /** Bare tables behind the table tags, and strips or tiles of scan data only. */
    private fun bareTables(h0: Int, v0: Int, mcuRows: Int, tileWidth: Int = 0, studio: Boolean = false): ByteArray {
        val tiled = tileWidth > 0
        val codedWidth = if (tiled) tileWidth else width
        val stripRows = if (mcuRows == 0) height else mcuRows * v0 * 8
        val codedHeight = if (tiled) (height + stripRows - 1) / stripRows * stripRows else height
        val jpeg = cjpeg(codedWidth, codedHeight, h0, v0, mcuRows)
        val (segments, sos) = segments(jpeg)
        val quantization = ArrayList<ByteArray>()
        val dc = ArrayList<ByteArray>()
        val ac = ArrayList<ByteArray>()
        for (s in segments) {
            var at = s.start + 4
            when (s.marker) {
                0xDB -> while (at < s.end) { quantization += jpeg.copyOfRange(at + 1, at + 65); at += 65 }
                0xC4 -> while (at < s.end) {
                    val n = (0 until 16).sumOf { jpeg[at + 1 + it].toInt() and 0xFF }
                    val table = jpeg.copyOfRange(at + 1, at + 17 + n)
                    if ((jpeg[at].toInt() and 0xF0) == 0) dc += table else ac += table
                    at += 17 + n
                }
            }
        }
        val (_, parts) = intervals(jpeg, sos)
        val blobs = quantization + dc + ac + parts
        // cjpeg gives luma table 0 and both chroma components table 1, which the tags say by
        // repeating the second offset.
        fun offsets(first: Int) = longArrayOf(-(first + 1L), -(first + 2L), -(first + 2L))
        val q = 0
        val d = quantization.size
        val a = d + dc.size
        val firstPart = a + ac.size
        val geometry = if (tiled) listOf(
            long(322, tileWidth.toLong()), long(323, stripRows.toLong()),
            long(324, *LongArray(parts.size) { -(firstPart + it + 1L) }),
            long(325, *LongArray(parts.size) { parts[it].size.toLong() }),
        ) else listOf(
            long(273, *LongArray(parts.size) { -(firstPart + it + 1L) }), long(278, stripRows.toLong()),
            long(279, *LongArray(parts.size) { parts[it].size.toLong() }),
        )
        val fields = baseFields(h0, v0, studio) + geometry + listOf(
            short(512, 1), long(519, *offsets(q)), long(520, *offsets(d)), long(521, *offsets(a)),
        )
        return tiff(fields, blobs)
    }

    /** Our decode of [bytes] against libtiff's, within a mean and a worst channel difference. */
    private fun compare(name: String, bytes: ByteArray, meanLimit: Double = 0.6, worstLimit: Int = 6): String {
        val file = temp(name, ".tif").apply { writeBytes(bytes) }
        val reference = temp("$name-rgba", ".tif")
        val (code, text) = run(Tools.require("tiff2rgba").path, "-c", "none", file.path, reference.path)
        assertEquals(0, code, "tiff2rgba failed: $text")
        val theirs = ImageKodec.decode(reference.readBytes())
        assertTrue(ImageKodec.probe(bytes).isDecodable, "$name should probe as decodable")
        val ours = ImageKodec.decode(bytes)
        assertEquals(theirs.width, ours.width, "$name width")
        assertEquals(theirs.height, ours.height, "$name height")
        var total = 0L
        var worst = 0
        for (i in ours.argb.indices) for (shift in intArrayOf(24, 16, 8, 0)) {
            val d = abs(((ours.argb[i] ushr shift) and 0xFF) - ((theirs.argb[i] ushr shift) and 0xFF))
            total += d
            worst = maxOf(worst, d)
        }
        val mean = total.toDouble() / (ours.argb.size * 4)
        val report = "$name: mean $mean, worst $worst"
        assertTrue(mean <= meanLimit && worst <= worstLimit, report)
        return report
    }

    @Test
    fun headersAtInterchangeFormatWithTheScanInStripsMatchLibtiff() {
        assumeTrue("cjpeg, ImageMagick or libtiff tools not installed", tools())
        val reports = listOf(
            compare("jif-2x1", interchangeFormat(2, 1, mcuRows = 3)),
            compare("jif-2x2", interchangeFormat(2, 2, mcuRows = 1)),
            compare("jif-1x1-one-strip", interchangeFormat(1, 1, mcuRows = 0)),
        )
        println(reports.joinToString("\n"))
    }

    @Test
    fun bareTablesWithScanOnlyStripsAndTilesMatchLibtiff() {
        assumeTrue("cjpeg, ImageMagick or libtiff tools not installed", tools())
        val reports = listOf(
            compare("tables-one-strip", bareTables(2, 2, mcuRows = 0)),
            compare("tables-strips", bareTables(2, 1, mcuRows = 2)),
            compare("tables-studio", bareTables(2, 2, mcuRows = 0, studio = true)),
            compare("tables-tiles", bareTables(2, 2, mcuRows = 2, tileWidth = 112)),
        )
        println(reports.joinToString("\n"))
    }
}
