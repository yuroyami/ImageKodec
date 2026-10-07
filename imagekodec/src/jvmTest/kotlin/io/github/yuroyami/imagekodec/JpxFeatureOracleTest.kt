package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.JpxDecoder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The JPEG 2000 features past the baseline (#5), each written by opj_compress and decoded
 * against opj_decompress on the same file: every code-block style and mix of them, progression
 * order changes, and regions of interest. OpenJPEG writes no packed packet headers, so the PPM
 * and PPT files are its SOP and EPH codestreams with every packet header moved out of the
 * bitstream, and they must decode to what the codestream they came from decodes to. Skips
 * cleanly without the OpenJPEG tools.
 */
class JpxFeatureOracleTest {

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

    private fun temp(ext: String) = File.createTempFile("kite-jpx-feature", ext).apply { deleteOnExit() }

    /**
     * A 211 by 157 test card whose gradients, edges and pseudo-random texture fill many
     * bitplanes, so that the bypass reaches its raw passes, and whose height leaves a partial
     * stripe at the bottom of the edge code-blocks.
     */
    private val source: File by lazy {
        val w = 211
        val h = 157
        val body = ByteArray(w * h * 3)
        var seed = 12345
        var i = 0
        for (y in 0 until h) for (x in 0 until w) {
            seed = seed * 1103515245 + 12345
            val noise = (seed ushr 16) and 63
            body[i++] = (x * 255 / (w - 1) xor noise).toByte()
            body[i++] = ((y * 255 / (h - 1) + noise / 2) and 255).toByte()
            body[i++] = (if ((x / 9 + y / 7) % 2 == 0) 220 - noise else 30 + noise).toByte()
        }
        temp(".ppm").apply { writeBytes("P6\n$w $h\n255\n".encodeToByteArray() + body) }
    }

    private fun encode(vararg args: String, ext: String = ".j2k"): File {
        val out = temp(ext)
        val (code, text) = run(Tools.require("opj_compress").path, "-i", source.path, "-o", out.path, *args)
        assertEquals(0, code, "opj_compress ${args.toList()} failed: $text")
        return out
    }

    /** opj_decompress's reading of [file], 8-bit interleaved RGB. */
    private fun reference(file: File): ByteArray {
        val out = temp(".ppm")
        val (code, text) = run(Tools.require("opj_decompress").path, "-i", file.path, "-o", out.path)
        assertEquals(0, code, "opj_decompress failed: $text")
        val d = out.readBytes()
        // The magic, width, height and maximum, any of them after a comment line, then one byte of white space.
        var at = 0
        repeat(4) {
            while (d[at].toInt().toChar().isWhitespace() || d[at] == '#'.code.toByte()) {
                if (d[at] == '#'.code.toByte()) while (d[at] != '\n'.code.toByte()) at++ else at++
            }
            while (!d[at].toInt().toChar().isWhitespace()) at++
        }
        return d.copyOfRange(at + 1, d.size)
    }

    /** Asserts our decode of [data] against [expected] within [tolerance] per sample, and returns the worst difference. */
    private fun check(tag: String, data: ByteArray, expected: ByteArray, tolerance: Int): Int {
        val ours = assertNotNull(JpxDecoder.decode(data), "$tag: no decode")
        assertEquals(211, ours.width, tag)
        assertEquals(157, ours.height, tag)
        assertEquals(expected.size, ours.pixelBytes.size, tag)
        var worst = 0
        var sum = 0L
        for (i in expected.indices) {
            val d = kotlin.math.abs((ours.pixelBytes[i].toInt() and 0xFF) - (expected[i].toInt() and 0xFF))
            if (d > worst) worst = d
            sum += d
        }
        assertTrue(worst <= tolerance, "$tag: a sample $worst away from OpenJPEG's")
        // The 9/7 allowance is OpenJPEG's float rounding against our fixed point (#101): a level now and then.
        assertTrue(sum <= expected.size / 20, "$tag: mean difference ${sum.toDouble() / expected.size}")
        return worst
    }

    private fun compare(tag: String, file: File, lossy: Boolean) = check(tag, file.readBytes(), reference(file), if (lossy) 1 else 0)

    @Test
    fun everyCodeBlockStyleMatchesOpenJpeg() {
        assumeTrue("OpenJPEG tools not installed", tools())
        // Each switch alone, the ones that change segmentation together, and all six.
        val modes = listOf(1, 2, 4, 8, 16, 32, 1 + 4, 1 + 2, 1 + 8, 2 + 4 + 32, 1 + 16, 8 + 32, 63)
        for (m in modes) {
            // Several layers, so that segments continue from one packet into the next.
            compare("-M $m lossless", encode("-M", "$m", "-r", "20,8,1", "-b", "32,32"), lossy = false)
            compare("-M $m 9/7", encode("-M", "$m", "-I", "-r", "30,10", "-b", "16,64"), lossy = true)
        }
        // Small code-blocks on a tall image: many partial stripes for the vertically causal mode.
        compare("-M 9 small blocks", encode("-M", "9", "-b", "8,8", "-n", "3"), lossy = false)
    }

    @Test
    fun regionsOfInterestMatchOpenJpeg() {
        assumeTrue("OpenJPEG tools not installed", tools())
        for ((roi, extra) in listOf(
            "c=0,U=4" to emptyArray(), "c=1,U=11" to emptyArray(), "c=2,U=6" to arrayOf("-I"),
            "c=0,U=7" to arrayOf("-M", "1", "-r", "10,1"), "c=1,U=3" to arrayOf("-M", "63", "-I"),
        )) {
            compare("ROI $roi ${extra.toList()}", encode("-ROI", roi, *extra), lossy = "-I" in extra)
        }
    }

    // ---- packed packet headers ------------------------------------------------------

    private class Part(val tile: Int, val index: Int, val count: Int, val header: ByteArray, val packets: List<Pair<ByteArray, ByteArray>>)

    private fun u16(d: ByteArray, at: Int) = ((d[at].toInt() and 0xFF) shl 8) or (d[at + 1].toInt() and 0xFF)
    private fun u32(d: ByteArray, at: Int) = (u16(d, at) shl 16) or u16(d, at + 2)
    private fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun be32(v: Int) = be16(v ushr 16) + be16(v)
    private fun segment(marker: Int, payload: ByteArray) = be16(marker) + be16(payload.size + 2) + payload

    private fun indexOf(d: ByteArray, second: Int, from: Int): Int {
        for (i in from until d.size - 1) if (d[i] == 0xFF.toByte() && (d[i + 1].toInt() and 0xFF) == second) return i
        return -1
    }

    /**
     * The main header and tile-parts of a codestream written with SOP and EPH, each packet split
     * into its header, EPH included, and its SOP marker with the body after it. A packet header is
     * bit-stuffed and a body's segments are byte-stuffed, so neither holds 0xFF92 or 0xFF91.
     */
    private fun split(cs: ByteArray): Pair<ByteArray, List<Part>> {
        var at = 2
        while (u16(cs, at) != 0xFF90) at += 2 + u16(cs, at + 2)
        val main = cs.copyOfRange(0, at)
        val parts = ArrayList<Part>()
        while (u16(cs, at) == 0xFF90) {
            val end = at + u32(cs, at + 6)
            var sod = at + 12
            while (u16(cs, sod) != 0xFF93) sod += 2 + u16(cs, sod + 2)
            val packets = ArrayList<Pair<ByteArray, ByteArray>>()
            var p = sod + 2
            while (p < end) {
                assertEquals(0xFF91, u16(cs, p), "a packet without SOP")
                val eph = indexOf(cs, 0x92, p + 6)
                val next = indexOf(cs, 0x91, eph + 2).let { if (it < 0 || it > end) end else it }
                packets += cs.copyOfRange(p + 6, eph + 2) to (cs.copyOfRange(p, p + 6) + cs.copyOfRange(eph + 2, next))
                p = next
            }
            parts += Part(u16(cs, at + 4), cs[at + 10].toInt() and 0xFF, cs[at + 11].toInt() and 0xFF, cs.copyOfRange(at + 12, sod), packets)
            at = end
        }
        assertEquals(0xFFD9, u16(cs, at))
        return main to parts
    }

    private fun tilePart(part: Part, extraHeader: ByteArray): ByteArray {
        val body = part.packets.fold(ByteArray(0)) { all, packet -> all + packet.second }
        val header = part.header + extraHeader
        val sot = be16(part.tile) + be32(12 + header.size + 2 + body.size) + byteArrayOf(part.index.toByte(), part.count.toByte())
        return segment(0xFF90, sot) + header + be16(0xFF93) + body
    }

    /** [cs] with each tile-part's packet headers in PPT segments of at most [size] bytes, numbered on through the tile's parts. */
    internal fun withPpt(cs: ByteArray, size: Int): ByteArray {
        val (main, parts) = split(cs)
        val z = HashMap<Int, Int>()
        val out = ByteArrayOutputStream().apply { write(main) }
        for (part in parts) {
            val headers = part.packets.fold(ByteArray(0)) { all, packet -> all + packet.first }
            var ppt = ByteArray(0)
            var at = 0
            while (at < headers.size) {
                val n = minOf(size, headers.size - at)
                val index = z.getOrDefault(part.tile, 0)
                z[part.tile] = index + 1
                ppt += segment(0xFF61, byteArrayOf(index.toByte()) + headers.copyOfRange(at, at + n))
                at += n
            }
            out.write(tilePart(part, ppt))
        }
        out.write(be16(0xFFD9))
        return out.toByteArray()
    }

    /**
     * [cs] with every packet header in PPM segments of at most [size] bytes in the main header,
     * written in reverse order of their Zppm so that a decoder has to sort them, and with the
     * tile-parts, when [interleave], reordered to take turns between tiles.
     */
    internal fun withPpm(cs: ByteArray, size: Int, interleave: Boolean): ByteArray {
        val (main, original) = split(cs)
        val parts = if (interleave) original.sortedWith(compareBy({ it.index }, { it.tile })) else original
        val run = ByteArrayOutputStream()
        for (part in parts) {
            val headers = part.packets.fold(ByteArray(0)) { all, packet -> all + packet.first }
            run.write(be32(headers.size))
            run.write(headers)
        }
        val stream = run.toByteArray()
        val segments = ArrayList<ByteArray>()
        var at = 0
        while (at < stream.size) {
            val n = minOf(size, stream.size - at)
            segments += segment(0xFF60, byteArrayOf(segments.size.toByte()) + stream.copyOfRange(at, at + n))
            at += n
        }
        assertTrue(segments.size <= 256, "too many PPM segments")
        val out = ByteArrayOutputStream().apply { write(main) }
        for (s in segments.reversed()) out.write(s)
        for (part in parts) out.write(tilePart(part, ByteArray(0)))
        out.write(be16(0xFFD9))
        return out.toByteArray()
    }

    @Test
    fun packedPacketHeadersDecodeAsTheCodestreamTheyCameFrom() {
        assumeTrue("OpenJPEG tools not installed", tools())
        for ((tag, args) in listOf(
            "one tile" to arrayOf("-r", "20,5,1", "-n", "4"),
            "tiles and tile-parts" to arrayOf("-r", "20,5,1", "-t", "100,80", "-TP", "R", "-n", "3"),
            "styles" to arrayOf("-M", "63", "-r", "10,1", "-n", "3"),
            "POC" to emptyArray(),
        )) {
            val original = if (args.isEmpty()) {
                temp(".j2k").apply { writeBytes(withPoc(lrcp, listOf(Poc(0, 0, 1, 3, 3, 4), Poc(0, 0, 3, 6, 3, 1)), inTile = true)) }
            } else {
                encode("-SOP", "-EPH", *args)
            }
            val expected = reference(original)
            val cs = original.readBytes()
            check("$tag as it was", cs, expected, 0)
            val ppt = withPpt(cs, 300)
            check("$tag with PPT", ppt, expected, 0)
            // OpenJPEG reads the same PPT file the same way: the fixture is sound.
            val pptFile = temp(".j2k").apply { writeBytes(ppt) }
            assertTrue(expected.contentEquals(reference(pptFile)), "$tag: OpenJPEG reads the PPT file differently")
            check("$tag with PPM", withPpm(cs, 700, interleave = false), expected, 0)
            check("$tag with PPM, tile-parts interleaved", withPpm(cs, 700, interleave = true), expected, 0)
        }
    }

    // ---- progression order changes ------------------------------------------------

    internal class Poc(val rs: Int, val cs: Int, val le: Int, val re: Int, val ce: Int, val order: Int) {
        fun bytes() = byteArrayOf(rs.toByte(), cs.toByte()) + byteArrayOf((le ushr 8).toByte(), le.toByte()) +
            byteArrayOf(re.toByte(), ce.toByte(), order.toByte())
    }

    /** A packet's place: layer, resolution, component, precinct, and where the precinct sits on the reference grid. */
    private data class Place(val l: Int, val r: Int, val c: Int, val p: Int, val y: Int, val x: Int)

    /**
     * An LRCP codestream with SOP and EPH, of one tile per [tiles] across, at the origin and
     * with no subsampling, as opj_compress writes it: every precinct of every tile, three
     * components, six resolutions, three layers, the last one lossless, and small precincts
     * at the finer resolutions so that the spatial orders have more than one position to visit.
     */
    private val lrcp: ByteArray by lazy {
        encode("-SOP", "-EPH", "-p", "LRCP", "-r", "30,10,1", "-n", "6", "-c", "[64,64],[32,32],[32,32]").readBytes()
    }

    private val lrcpTiles: ByteArray by lazy {
        encode("-SOP", "-EPH", "-p", "LRCP", "-r", "20,5,1", "-n", "4", "-t", "106,157").readBytes()
    }

    /**
     * The places of every packet of the tile spanning [x0] to [x1] and [y0] to [y1], in the
     * codestream's LRCP order: T.800 B.6 and B.12 for a tile without subsampling.
     */
    private fun places(cs: ByteArray, x0: Int, y0: Int, x1: Int, y1: Int): List<Place> {
        var at = 2
        while (u16(cs, at) != 0xFF52) at += 2 + u16(cs, at + 2)
        val scod = cs[at + 4].toInt()
        val layers = u16(cs, at + 6)
        val levels = cs[at + 9].toInt()
        val out = ArrayList<Place>()
        for (l in 0 until layers) for (r in 0..levels) for (c in 0 until 3) {
            val shift = levels - r
            val pp = if (scod and 1 != 0) cs[at + 14 + r].toInt() and 0xFF else 0xFF
            val ppx = pp and 15
            val ppy = pp ushr 4
            fun ceilShift(v: Int, s: Int) = (v + (1 shl s) - 1) shr s
            val rx0 = ceilShift(x0, shift); val rx1 = ceilShift(x1, shift)
            val ry0 = ceilShift(y0, shift); val ry1 = ceilShift(y1, shift)
            val pw = ceilShift(rx1, ppx) - (rx0 shr ppx)
            val ph = ceilShift(ry1, ppy) - (ry0 shr ppy)
            for (j in 0 until ph) for (i in 0 until pw) {
                val x = maxOf(x0, (((rx0 shr ppx) + i) shl ppx) shl shift)
                val y = maxOf(y0, (((ry0 shr ppy) + j) shl ppy) shl shift)
                out += Place(l, r, c, j * pw + i, y, x)
            }
        }
        return out
    }

    /** The places of [all] in the order [entries] give, each packet where the first entry reaches it (A.6.6, B.12). */
    private fun order(all: List<Place>, entries: List<Poc>): List<Place> {
        val seen = HashSet<Place>()
        val out = ArrayList<Place>()
        for (e in entries) {
            // CEpoc 0 stands for 256 components (A.6.6).
            val ce = if (e.ce == 0) 256 else e.ce
            val chosen = all.filter { it.l < e.le && it.r in e.rs until e.re && it.c in e.cs until ce }
            val sorted = when (e.order) {
                0 -> chosen.sortedWith(compareBy({ it.l }, { it.r }, { it.c }, { it.p }))
                1 -> chosen.sortedWith(compareBy({ it.r }, { it.l }, { it.c }, { it.p }))
                2 -> chosen.sortedWith(compareBy({ it.r }, { it.y }, { it.x }, { it.c }, { it.l }))
                3 -> chosen.sortedWith(compareBy({ it.y }, { it.x }, { it.c }, { it.r }, { it.l }))
                else -> chosen.sortedWith(compareBy({ it.c }, { it.y }, { it.x }, { it.r }, { it.l }))
            }
            for (place in sorted) if (seen.add(place)) out += place
        }
        return out
    }

    /**
     * [cs], an LRCP codestream with SOP and EPH, with its packets reordered as [entries] order
     * them and a POC segment that says so, in the main header or, with [inTile], in the tile's
     * header, and with [mainToo] a main header POC as well that the tile's overrides. Each
     * packet header codes only its own precinct's state, so moving packets between precincts
     * keeps every one of them valid as long as each precinct's layers stay in order.
     */
    internal fun withPoc(cs: ByteArray, entries: List<Poc>, inTile: Boolean, mainToo: List<Poc>? = null, tile: Int = 0): ByteArray {
        val (main, parts) = split(cs)
        // SIZ: the image and tile sizes, with both offsets zero.
        val width = u32(cs, 8)
        val height = u32(cs, 12)
        val tileWidth = u32(cs, 24)
        val tileHeight = u32(cs, 28)
        val across = (width + tileWidth - 1) / tileWidth
        val out = ByteArrayOutputStream()
        out.write(main)
        val mainEntries = if (inTile) mainToo else entries
        if (mainEntries != null) out.write(segment(0xFF5F, mainEntries.fold(ByteArray(0)) { a, e -> a + e.bytes() }))
        var sequence = 0
        for (part in parts) {
            val x0 = part.tile % across * tileWidth
            val y0 = part.tile / across * tileHeight
            val all = places(cs, x0, y0, minOf(width, x0 + tileWidth), minOf(height, y0 + tileHeight))
            assertEquals(all.size, part.packets.size, "packets of tile ${part.tile}")
            val packets = all.zip(part.packets).toMap()
            val mine = inTile && part.tile == tile
            val list = when {
                mine -> order(all, entries)
                mainEntries != null -> order(all, mainEntries)
                else -> all
            }
            val header = part.header + if (mine) segment(0xFF5F, entries.fold(ByteArray(0)) { a, e -> a + e.bytes() }) else ByteArray(0)
            val body = ByteArrayOutputStream()
            for (place in list) {
                val (head, sopAndBody) = packets.getValue(place)
                body.write(byteArrayOf(0xFF.toByte(), 0x91.toByte(), 0, 4) + be16(sequence++ and 0xFFFF))
                body.write(head)
                body.write(sopAndBody, 6, sopAndBody.size - 6)
            }
            val bytes = body.toByteArray()
            // The header packets go first, so the body starts with the SOP of the first packet.
            val sot = be16(part.tile) + be32(12 + header.size + 2 + bytes.size) + byteArrayOf(0, 1)
            out.write(segment(0xFF90, sot) + header + be16(0xFF93) + bytes)
        }
        out.write(be16(0xFFD9))
        return out.toByteArray()
    }

    @Test
    fun progressionOrderChangesMatchOpenJpeg() {
        assumeTrue("OpenJPEG tools not installed", tools())
        val expected = reference(temp(".j2k").apply { writeBytes(lrcp) })
        val cases = listOf(
            // The first layer of the low resolutions, then everything, packets already read skipped.
            listOf(Poc(0, 0, 1, 3, 3, 4), Poc(0, 0, 3, 6, 3, 0)),
            // Each spatial order on part of the packets, the rest in another order.
            listOf(Poc(0, 0, 2, 6, 1, 2), Poc(0, 1, 3, 6, 3, 3), Poc(0, 0, 3, 6, 3, 1)),
            listOf(Poc(2, 0, 3, 6, 3, 0), Poc(0, 0, 3, 2, 3, 4), Poc(1, 2, 1, 4, 3, 3)),
            listOf(Poc(0, 0, 3, 6, 3, 3)),
            listOf(Poc(3, 1, 2, 6, 2, 4), Poc(0, 0, 3, 6, 3, 2)),
        )
        // CEpoc 0, every component up to 256, which OpenJPEG reads as none: only ours is held to the original.
        check("POC to component 256", withPoc(lrcp, listOf(Poc(0, 0, 3, 6, 0, 3)), inTile = false), expected, 0)
        for (entries in cases) for (inTile in listOf(false, true)) {
            val tag = "POC ${entries.map { listOf(it.rs, it.cs, it.le, it.re, it.ce, it.order) }} in the ${if (inTile) "tile" else "main"} header"
            val file = temp(".j2k").apply { writeBytes(withPoc(lrcp, entries, inTile)) }
            // OpenJPEG reads the reordered file as it read the original: the fixture is sound.
            assertTrue(expected.contentEquals(reference(file)), "$tag: OpenJPEG reads it differently")
            check(tag, file.readBytes(), expected, 0)
        }
    }

    @Test
    fun aTilesProgressionOrderChangesReplaceTheMainHeaders() {
        assumeTrue("OpenJPEG tools not installed", tools())
        val expected = reference(temp(".j2k").apply { writeBytes(lrcpTiles) })
        val cpr = listOf(Poc(0, 0, 3, 5, 3, 4))
        // A POC in the second tile's header only: the first keeps its COD order.
        val tileOnly = withPoc(lrcpTiles, cpr, inTile = true, tile = 1)
        assertTrue(expected.contentEquals(reference(temp(".j2k").apply { writeBytes(tileOnly) })), "OpenJPEG reads the per-tile POC differently")
        check("POC in one tile", tileOnly, expected, 0)
        // A main header POC that the second tile's own replaces (A.6.6: tile-part POC > main POC).
        // OpenJPEG appends a tile's changes to the main header's instead, so only our decode is held to the original.
        val both = withPoc(lrcpTiles, cpr, inTile = true, mainToo = listOf(Poc(0, 0, 3, 5, 3, 1)), tile = 1)
        check("POC in the main header and replaced in one tile", both, expected, 0)
    }
}
