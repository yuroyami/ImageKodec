package io.github.yuroyami.imagekodec.heic

import io.github.yuroyami.imagekodec.AnnexB
import io.github.yuroyami.imagekodec.HeifChroma
import io.github.yuroyami.imagekodec.HeifEncodeOptions
import io.github.yuroyami.imagekodec.HeifPlanes
import io.github.yuroyami.imagekodec.ImageKodec
import io.github.yuroyami.imagekodec.ImageMetadata
import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.KiteBitmap16
import io.github.yuroyami.imagekodec.Orientation
import io.github.yuroyami.imagekodec.Tools
import java.io.File
import org.junit.Assume.assumeTrue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Files from `ImageKodec.encodeHeic` read by other HEIF and HEVC readers (#174). For each file:
 * - libheif's `heif-info` lists the structure written: size, chroma, depth, alpha, thumbnail, colour profile,
 *   metadata and rotation;
 * - libheif's `heif-convert` gives the RGB ImageKodec's decoder gives, within one level on 4:4:4 and 4:0:0, as
 *   HeicOracleTest holds files from x265. heif-convert upsamples 4:2:0 chroma by repeating each sample, where
 *   ImageKodec interpolates (9, 3, 3 and 1 sixteenths), so on 4:2:0 the two differ wherever the chroma changes;
 *   the bound there is the measured worst of these files, and the mean is printed;
 * - every coded item equals libde265's (`dec265`) decode of it, sample for sample, and ffmpeg's where libde265
 *   1.0 aborts, on a cropped 4:0:0 stream above 8 bits;
 * - `exiftool` reads the EXIF orientation, the XMP and the ICC profile back.
 *
 * Every external call runs under `timeout -s KILL`.
 */
class HeicEncodeOracleTest {

    init {
        ImageKodecHeic.install()
    }

    private val dir = File(System.getProperty("java.io.tmpdir"), "kite-heic-encode").apply { mkdirs() }

    private fun run(vararg args: String): Pair<Int, String> {
        val proc = ProcessBuilder(listOf("timeout", "-s", "KILL", "120") + args.toList()).redirectErrorStream(true).start()
        val out = proc.inputStream.readBytes()
        return proc.waitFor() to out.decodeToString()
    }

    private val photo = HeicEncodeTest()

    /** A 16-bit picture of gradients and noise, gray when [gray]. */
    private fun deep(w: Int, h: Int, gray: Boolean, alpha: Boolean): KiteBitmap16 {
        val b = photo.photo(w, h, alpha)
        val channels = (if (gray) 1 else 3) + (if (alpha) 1 else 0)
        val out = ShortArray(w * h * channels)
        for (i in 0 until w * h) {
            val p = b.argb[i]
            val r = ((p ushr 16) and 0xFF) * 257 + (i * 37 and 0xFF)
            val g = ((p ushr 8) and 0xFF) * 257 + (i * 11 and 0xFF)
            val bl = (p and 0xFF) * 257
            val at = i * channels
            if (gray) {
                out[at] = minOf(g, 65535).toShort()
            } else {
                out[at] = minOf(r, 65535).toShort()
                out[at + 1] = minOf(g, 65535).toShort()
                out[at + 2] = bl.toShort()
            }
            if (alpha) out[at + channels - 1] = ((p ushr 24) * 257).toShort()
        }
        return KiteBitmap16(w, h, channels, out)
    }

    private class Case(
        val name: String,
        val file: ByteArray,
        /** Lines heif-info must print. */
        val info: List<String>,
        val chroma: String,
        val orientation: Orientation = Orientation.Normal,
        val alpha: Boolean = false,
    )

    private val xmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
        "<rdf:Description xmlns:dc=\"http://purl.org/dc/elements/1.1/\" dc:format=\"image/heic\"/></rdf:RDF></x:xmpmeta>"

    /** A little-endian EXIF block of one entry: orientation 1. */
    private val exif = byteArrayOf(0x49, 0x49, 42, 0, 8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0)

    private fun cases(): List<Case> {
        val out = ArrayList<Case>()
        fun add(name: String, file: ByteArray, chroma: String, vararg info: String, orientation: Orientation = Orientation.Normal, alpha: Boolean = false) {
            out += Case(name, file, info.toList(), chroma, orientation, alpha)
        }
        val q80 = HeifEncodeOptions(quality = 80)
        add("4:2:0 8 bits, odd size", ImageKodec.encodeHeic(photo.photo(65, 37), q80), "4:2:0",
            "image: 65x37 (id=1), primary", "colorspace: YCbCr, 4:2:0", "bit depth: 8", "crop: left=0 top=0 right=1 bottom=1", "main brand: heic")
        add("4:4:4 8 bits with alpha", ImageKodec.encodeHeic(photo.photo(64, 48, alpha = true), HeifEncodeOptions(quality = 80, chroma = HeifChroma.Yuv444)), "4:4:4",
            "image: 64x48 (id=1), primary", "colorspace: YCbCr, 4:4:4", "alpha channel: yes", alpha = true)
        add("4:2:2 8 bits", ImageKodec.encodeHeic(photo.photo(66, 40), HeifEncodeOptions(quality = 60, chroma = HeifChroma.Yuv422)), "4:2:2",
            "colorspace: YCbCr, 4:2:2")
        add("grid 4:2:0 with alpha and a thumbnail", ImageKodec.encodeHeic(photo.photo(300, 200, alpha = true), HeifEncodeOptions(quality = 70, tileSize = 128, thumbnailSize = 64)), "4:2:0",
            "image: 300x200 (id=1), primary", "thumbnail: 64x42", "alpha channel: yes", alpha = true)
        add("grid 4:4:4", ImageKodec.encodeHeic(photo.photo(200, 130), HeifEncodeOptions(quality = 70, chroma = HeifChroma.Yuv444, tileSize = 64, effort = 1)), "4:4:4",
            "image: 200x130 (id=1), primary", "colorspace: YCbCr, 4:4:4")
        add("10 bits 4:2:0", ImageKodec.encodeHeic16(deep(70, 42, false, false), HeifEncodeOptions(quality = 70)), "4:2:0",
            "bit depth: 10", "main brand: heix")
        add("10 bits 4:4:4", ImageKodec.encodeHeic16(deep(48, 40, false, false), HeifEncodeOptions(quality = 70, chroma = HeifChroma.Yuv444)), "4:4:4",
            "bit depth: 10")
        add("10 bits gray, cropped", ImageKodec.encodeHeic16(deep(45, 27, true, false), HeifEncodeOptions(quality = 70)), "4:0:0",
            "colorspace: monochrome", "bit depth: 10")
        add("8 bits gray with alpha", ImageKodec.encodeHeic(gray(photo.photo(40, 32, alpha = true)), HeifEncodeOptions(quality = 70)), "4:0:0",
            "colorspace: monochrome", "alpha channel: yes", alpha = true)
        add("lossless 8 bits", ImageKodec.encodeHeic(photo.photo(57, 33), HeifEncodeOptions(lossless = true)), "4:4:4",
            "colorspace: RGB", "bit depth: 8")
        add("lossless 12 bits with alpha", ImageKodec.encodeHeic16(deep(33, 20, false, true), HeifEncodeOptions(lossless = true)), "4:4:4",
            "colorspace: RGB", "bit depth: 12", "alpha channel: yes", alpha = true)
        for (o in listOf(Orientation.Rotate90, Orientation.Rotate180, Orientation.FlipHorizontal, Orientation.Transpose)) {
            add("orientation $o", ImageKodec.encodeHeic(photo.photo(48, 32), HeifEncodeOptions(quality = 80, chroma = HeifChroma.Yuv444, orientation = o)), "4:4:4",
                orientation = o)
        }
        val icc = File("/usr/share/color/icc/sRGB.icc").takeIf { it.isFile }?.readBytes()
        if (icc == null && System.getenv(Tools.REQUIRE_ENV) == "1") error("/usr/share/color/icc/sRGB.icc (icc-profiles-free) is missing")
        add("metadata", ImageKodec.encodeHeic(photo.photo(64, 48), HeifEncodeOptions(quality = 80, chroma = HeifChroma.Yuv444, orientation = Orientation.Rotate270), ImageMetadata(exif, xmp, icc)), "4:4:4",
            *listOfNotNull("Exif: 36 bytes", "XMP: ${xmp.length} bytes", icc?.let { "color profile: prof" }, "angle (ccw): 90").toTypedArray(), orientation = Orientation.Rotate270)
        return out
    }

    private fun gray(b: KiteBitmap) = KiteBitmap(b.width, b.height, IntArray(b.argb.size) { i ->
        val p = b.argb[i]
        (p and 0xFF000000.toInt()) or (((p ushr 8) and 0xFF) * 0x010101)
    })

    /** Where [planes] differ from the raw planes [theirs] (16 bits little-endian above 8), or null. */
    private fun differs(planes: HeifPlanes, theirs: ByteArray): String? {
        val wide = planes.bitDepthLuma > 8
        var at = 0
        for (c in planes.planes.indices) {
            val w = if (c == 0) planes.width else planes.width shr planes.subX
            val h = if (c == 0) planes.height else planes.height shr planes.subY
            if (at + w * h * (if (wide) 2 else 1) > theirs.size) return "${theirs.size} bytes, fewer than the picture"
            for (y in 0 until h) for (x in 0 until w) {
                val v = if (wide) (theirs[at].toInt() and 0xFF) or ((theirs[at + 1].toInt() and 0xFF) shl 8) else theirs[at].toInt() and 0xFF
                at += if (wide) 2 else 1
                val mine = planes.planes[c][y * planes.strides[c] + x].toInt() and 0xFFFF
                if (mine != v) return "plane $c differs at ($x, $y): $mine, not $v"
            }
        }
        return if (theirs.size != at) "${theirs.size} bytes, not $at" else null
    }

    private fun pixFmt(p: HeifPlanes): String {
        val base = when {
            p.planes.size == 1 -> "gray"
            p.subY == 1 -> "yuv420p"
            p.subX == 1 -> "yuv422p"
            else -> "yuv444p"
        }
        return if (p.bitDepthLuma == 8) base else "$base${p.bitDepthLuma}le"
    }

    /** Each `hvc1` item of [file] against libde265, or ffmpeg where libde265 1.0 aborts. Returns how many. */
    private fun checkItems(name: String, file: ByteArray): Int {
        val items = HeifItems(file)
        var n = 0
        for (item in items.items.values.filter { it.type == "hvc1" }) {
            val hvcC = item.property("hvcC") ?: fail("$name: item ${item.id} has no hvcC")
            val mine = HevcCodec.open(hvcC).decode(item.data, null) ?: fail("$name: item ${item.id} shows nothing")
            val stream = File(dir, "item.hevc").apply { writeBytes(AnnexB.stream(hvcC, listOf(item.data))) }
            val raw = File(dir, "item.yuv").apply { delete() }
            // libde265 1.0 aborts on a 4:0:0 picture above 8 bits with a conformance window (AGENTS.md).
            val cropped = mine.width % 8 != 0 || mine.height % 8 != 0
            if (mine.planes.size == 1 && mine.bitDepthLuma > 8 && cropped) {
                val (code, log) = run(Tools.require("ffmpeg").path, "-hide_banner", "-loglevel", "error", "-i", stream.path, "-f", "rawvideo", "-pix_fmt", pixFmt(mine), "-y", raw.path)
                assertEquals(0, code, "$name: ffmpeg failed on item ${item.id}: $log")
                differs(mine, raw.readBytes())?.let { fail("$name: item ${item.id} against ffmpeg: $it") }
            } else {
                val (code, log) = run(Tools.require("dec265").path, "-q", "-o", raw.path, stream.path)
                assertEquals(0, code, "$name: dec265 failed on item ${item.id}: $log")
                differs(mine, raw.readBytes())?.let { fail("$name: item ${item.id} against libde265: $it") }
            }
            n++
        }
        return n
    }

    private fun libheif(file: ByteArray): KiteBitmap {
        val input = File(dir, "in.heic").apply { writeBytes(file) }
        val png = File(dir, "out.png").apply { delete() }
        val (code, log) = run(Tools.require("heif-convert").path, input.path, png.path)
        assertEquals(0, code, "heif-convert failed: $log")
        return ImageKodec.decode(png.readBytes())
    }

    /** The largest and the mean difference of any channel between [a] and [b], alpha unless [colourOnly] is set aside. */
    private fun difference(a: KiteBitmap, b: KiteBitmap, colourOnly: Boolean = false): Pair<Int, Double> {
        assertEquals(a.width to a.height, b.width to b.height, "size")
        var worst = 0
        var sum = 0L
        val channels = if (colourOnly) 3 else 4
        for (i in a.argb.indices) for (c in 0 until channels) {
            val d = abs(((a.argb[i] ushr (8 * c)) and 0xFF) - ((b.argb[i] ushr (8 * c)) and 0xFF))
            worst = maxOf(worst, d)
            sum += d
        }
        return worst to sum.toDouble() / (a.argb.size * channels)
    }

    /** [file] with each `irot` set to no turn: libheif 1.17.6 turns the colour of an image with alpha but not its alpha (AGENTS.md). */
    private fun unturned(file: ByteArray): ByteArray {
        val out = file.copyOf()
        val irot = "irot".encodeToByteArray()
        for (i in 0 until out.size - 4) if ((0 until 4).all { out[i + it] == irot[it] }) out[i + 4] = 0
        return out
    }

    @Test
    fun libheifLibde265AndFfmpegReadTheFiles() {
        assumeTrue("heif-info, heif-convert, dec265 or ffmpeg not installed", Tools.hasAll("heif-info", "heif-convert", "dec265", "ffmpeg", "timeout"))
        var worst420 = 0
        for (case in cases()) {
            val input = File(dir, "case.heic").apply { writeBytes(case.file) }
            val (code, info) = run(Tools.require("heif-info").path, input.path)
            assertEquals(0, code, "${case.name}: heif-info failed: $info")
            for (line in case.info) assertTrue(info.lines().any { it.trim() == line.trim() || it.trim().startsWith(line.trim()) }, "${case.name}: heif-info lacks '$line':\n$info")
            val items = checkItems(case.name, case.file)
            val ours = ImageKodec.decode(case.file, applyOrientation = true)
            val turnedAlpha = case.alpha && case.orientation != Orientation.Normal
            val (worst, mean) = difference(ours, libheif(case.file), colourOnly = turnedAlpha)
            val line = "${case.name}: $items items equal libde265's or ffmpeg's decode; RGB against heif-convert: worst $worst, mean ${"%.3f".format(mean)}"
            println(line)
            if (case.chroma == "4:4:4" || case.chroma == "4:0:0") {
                assertTrue(worst <= 1, "${case.name}: RGB differs from heif-convert by $worst")
            } else {
                worst420 = maxOf(worst420, worst)
                assertTrue(worst <= SUBSAMPLED_BOUND, "${case.name}: RGB differs from heif-convert by $worst")
                assertTrue(mean < 2.0, "${case.name}: RGB differs from heif-convert by $mean on average")
            }
        }
        println("worst difference from heif-convert on subsampled chroma: $worst420 (bound $SUBSAMPLED_BOUND)")
    }

    @Test
    fun exiftoolReadsTheMetadata() {
        assumeTrue("exiftool not installed", Tools.hasAll("exiftool", "timeout"))
        val icc = File("/usr/share/color/icc/sRGB.icc").takeIf { it.isFile }?.readBytes()
        val file = ImageKodec.encodeHeic(photo.photo(64, 48), HeifEncodeOptions(quality = 80, orientation = Orientation.Rotate90), ImageMetadata(exif, xmp, icc))
        val input = File(dir, "meta.heic").apply { writeBytes(file) }
        val (code, out) = run(Tools.require("exiftool").path, "-s", "-Orientation", "-XMP-dc:Format", "-ProfileDescription", input.path)
        assertEquals(0, code, out)
        println(out.trim())
        // The EXIF block said 1; the file turns the picture a quarter clockwise, and the EXIF tag follows it.
        assertTrue(out.lines().any { it.startsWith("Orientation") && it.endsWith("Rotate 90 CW") }, out)
        assertTrue(out.lines().any { it.startsWith("Format") && it.endsWith("image/heic") }, out)
        if (icc != null) assertTrue(out.lines().any { it.startsWith("ProfileDescription") }, out)
        val back = ImageKodec.readMetadata(file)
        assertEquals(xmp, back.xmp)
        icc?.let { assertContentEquals(it, back.icc) }
    }

    @Test
    fun alphaOfATurnedFileMatchesLibheifUnturned() {
        assumeTrue("heif-convert not installed", Tools.hasAll("heif-convert", "timeout"))
        val file = ImageKodec.encodeHeic(photo.photo(48, 32, alpha = true), HeifEncodeOptions(quality = 80, chroma = HeifChroma.Yuv444, orientation = Orientation.Rotate90))
        val (worst, _) = difference(ImageKodec.decode(file), libheif(unturned(file)))
        println("alpha and colour of a turned file with alpha, unturned, against heif-convert: worst $worst")
        assertTrue(worst <= 1, "differs by $worst")
    }

    private companion object {
        /**
         * The bound on 4:2:0 and 4:2:2 against heif-convert's repeated chroma. Measured: worst 17 (10-bit 4:2:0),
         * 15 and 16 at 8 bits, with means of 0.3 to 1.5 levels; 4:4:4 and 4:0:0 of the same pictures stay within 1.
         */
        const val SUBSAMPLED_BOUND = 20
    }
}
