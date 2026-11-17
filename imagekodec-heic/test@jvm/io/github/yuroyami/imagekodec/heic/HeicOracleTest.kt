package io.github.yuroyami.imagekodec.heic

import io.github.yuroyami.imagekodec.AnnexB
import io.github.yuroyami.imagekodec.ColorTarget
import io.github.yuroyami.imagekodec.HeifBuilder
import io.github.yuroyami.imagekodec.HeifPlanes
import io.github.yuroyami.imagekodec.ImageFormat
import io.github.yuroyami.imagekodec.ImageKodec
import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.Orientation
import io.github.yuroyami.imagekodec.Tools
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * HEIC through `ImageKodec` (#145). Files built here around x265 items, and the real photos of
 * `reference/heic/samples`: the planes of each coded item equal libde265's and ffmpeg's decode of the
 * item as an Annex B stream, sample for sample, and the RGB of the whole file, with its grid, crop,
 * alpha and orientation, matches libheif's `heif-convert` on 4:0:0 and 4:4:4. heif-convert upsamples
 * chroma by nearest neighbour where ImageKodec interpolates, so 4:2:0 and 4:2:2 RGB is not compared.
 */
class HeicOracleTest {

    init {
        ImageKodecHeic.install()
    }

    // ---- tools -----------------------------------------------------------------------

    private fun run(vararg args: String): Pair<Int, ByteArray> {
        val proc = ProcessBuilder(args.toList()).redirectErrorStream(true).start()
        val out = proc.inputStream.readBytes()
        if (!proc.waitFor(300, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return -1 to out
        }
        return proc.exitValue() to out
    }

    private fun temp(ext: String) = File.createTempFile("kite-heic", ext).apply { deleteOnExit() }

    private fun requireTools() {
        assumeTrue("ffmpeg, dec265 or heif-convert not installed", Tools.hasAll("ffmpeg", "dec265", "heif-convert", "timeout"))
        val encoders = run(Tools.require("ffmpeg").path, "-hide_banner", "-encoders").second.decodeToString()
        if ("libx265" !in encoders) {
            if (System.getenv(Tools.REQUIRE_ENV) == "1") error("ffmpeg lacks libx265, and ${Tools.REQUIRE_ENV}=1 forbids skipping")
            assumeTrue("ffmpeg lacks libx265", false)
        }
    }

    /** An x265 item: the parameter sets in its hvcC, the other NAL units in its sample. */
    private class Coded(val hvcC: ByteArray, val sample: ByteArray, val units: List<ByteArray>)

    /** [source] (a lavfi graph) coded by x265 with the MD5 hash SEI. x265 3.5 can hang for good, so it runs under a hard kill. */
    private fun encode(source: String, pixFmt: String, params: String = ""): Coded {
        val out = temp(".hevc")
        val (code, log) = run(
            Tools.require("timeout").path, "-s", "KILL", "120",
            Tools.require("ffmpeg").path, "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", source,
            "-frames:v", "1", "-pix_fmt", pixFmt, "-c:v", "libx265",
            "-x265-params", "keyint=1:hash=1:log-level=none${if (params.isEmpty()) "" else ":$params"}", "-f", "hevc", "-y", out.path,
        )
        assertEquals(0, code, "ffmpeg failed for $source $pixFmt $params: ${log.decodeToString()}")
        // The prefix SEI holds x265's version and options, a kilobyte or more that nothing reads.
        val units = AnnexB.split(out.readBytes()).filter { ((it[0].toInt() shr 1) and 0x3F) != 39 }
        val sets = units.filter { ((it[0].toInt() shr 1) and 0x3F) in 32..34 }
        val chroma = when {
            pixFmt.startsWith("gray") -> 0
            pixFmt.startsWith("yuv420") -> 1
            pixFmt.startsWith("yuv422") -> 2
            else -> 3
        }
        val depth = Regex("p?(\\d+)le$").find(pixFmt)?.groupValues?.get(1)?.toInt() ?: 8
        return Coded(AnnexB.hvcC(sets, 4, chroma, depth, depth), AnnexB.sample(units - sets.toSet()), units)
    }

    private fun testsrc(w: Int, h: Int) = "testsrc2=size=${w}x$h:rate=25"

    // ---- boxes -----------------------------------------------------------------------

    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun box(type: String, payload: ByteArray) = be32(8 + payload.size) + type.encodeToByteArray() + payload
    private fun fullBox(type: String, payload: ByteArray) = box(type, ByteArray(4) + payload)

    private fun hvcC(c: Coded) = box("hvcC", c.hvcC) to true
    private fun ispe(w: Int, h: Int) = fullBox("ispe", be32(w) + be32(h)) to false
    private fun nclx(p: Int, t: Int, m: Int, full: Boolean) =
        box("colr", "nclx".encodeToByteArray() + be16(p) + be16(t) + be16(m) + byteArrayOf(if (full) 0x80.toByte() else 0)) to false
    private fun prof(icc: ByteArray) = box("colr", "prof".encodeToByteArray() + icc) to false
    private fun irot(angle: Int) = box("irot", byteArrayOf(angle.toByte())) to true
    private fun imir(axis: Int) = box("imir", byteArrayOf(axis.toByte())) to true
    private fun clap(w: Int, h: Int, horizOffN: Int, vertOffN: Int, d: Int) =
        box("clap", be32(w) + be32(1) + be32(h) + be32(1) + be32(horizOffN) + be32(d) + be32(vertOffN) + be32(d)) to true
    private val alphaAux = fullBox("auxC", "urn:mpeg:hevc:2015:auxid:1".encodeToByteArray() + byteArrayOf(0)) to true

    private fun grid(rows: Int, columns: Int, w: Int, h: Int) =
        byteArrayOf(0, 0, (rows - 1).toByte(), (columns - 1).toByte()) + be16(w) + be16(h)

    private fun heif(build: HeifBuilder.() -> Unit) = HeifBuilder("heic", listOf("mif1", "heic", "miaf")).apply(build).build()

    // ---- comparisons -----------------------------------------------------------------

    /** Our planes of a coded item, through the codec as the core opens it. */
    private fun ours(hvcC: ByteArray, sample: ByteArray): HeifPlanes =
        HevcCodec.open(hvcC).decode(sample, null) ?: fail("the item shows no picture")

    /** Where [planes] differ from the raw planes [theirs] that [who] wrote, or null when every sample matches. */
    private fun differs(who: String, planes: HeifPlanes, theirs: ByteArray): String? {
        val wide = planes.bitDepthLuma > 8
        var at = 0
        for (c in planes.planes.indices) {
            val w = if (c == 0) planes.width else planes.width shr planes.subX
            val h = if (c == 0) planes.height else planes.height shr planes.subY
            if (at + w * h * (if (wide) 2 else 1) > theirs.size) return "$who gave ${theirs.size} bytes, fewer than the picture"
            for (y in 0 until h) for (x in 0 until w) {
                val v = if (wide) (theirs[at].toInt() and 0xFF) or ((theirs[at + 1].toInt() and 0xFF) shl 8) else theirs[at].toInt() and 0xFF
                at += if (wide) 2 else 1
                val mine = planes.planes[c][y * planes.strides[c] + x].toInt()
                if (mine != v) return "plane $c differs from $who at ($x, $y): $mine, not $v"
            }
        }
        if (theirs.size != at) return "$who gave ${theirs.size} bytes, not $at"
        return null
    }

    /** The planes of the item ([hvcC], [sample]) against libde265's and, unless [libde265Only], ffmpeg's decode of it. */
    private fun checkItem(name: String, hvcC: ByteArray, sample: ByteArray, libde265Only: Boolean = false) {
        val planes = ours(hvcC, sample)
        val stream = temp(".hevc").apply { writeBytes(AnnexB.stream(hvcC, listOf(sample))) }
        val yuv = temp(".yuv")
        val (code, log) = run(Tools.require("dec265").path, "-q", "-o", yuv.path, stream.path)
        assertEquals(0, code, "$name: libde265 failed: ${log.decodeToString()}")
        differs("libde265", planes, yuv.readBytes())?.let { fail("$name: $it") }
        if (libde265Only) return
        val raw = temp(".yuv")
        val (fcode, flog) = run(Tools.require("ffmpeg").path, "-hide_banner", "-loglevel", "error", "-i", stream.path, "-f", "rawvideo", "-y", raw.path)
        assertEquals(0, fcode, "$name: ffmpeg failed: ${flog.decodeToString()}")
        differs("ffmpeg", planes, raw.readBytes())?.let { fail("$name: $it") }
    }

    private fun channel(argb: Int, c: Int) = (argb ushr (8 * c)) and 255

    /** The largest difference of any channel, alpha last unless [colourOnly], between [ours] and [theirs], after a check of their sizes. */
    private fun worst(name: String, ours: KiteBitmap, theirs: KiteBitmap, colourOnly: Boolean = false): Int {
        assertEquals(theirs.width to theirs.height, ours.width to ours.height, "$name size")
        var worst = 0
        var where = ""
        for (i in theirs.argb.indices) for (c in 0 until if (colourOnly) 3 else 4) {
            val d = abs(channel(theirs.argb[i], c) - channel(ours.argb[i], c))
            if (d > worst) {
                worst = d
                where = " at (${i % ours.width}, ${i / ours.width}): %08x, not %08x".format(ours.argb[i], theirs.argb[i])
            }
        }
        if (worst > 1) println("$name: worst difference from heif-convert $worst$where")
        return worst
    }

    /**
     * [heic] with each `irot` set to no turn. libheif 1.17.6 turns the colour of an image with alpha but not
     * its alpha, so the alpha is held to heif-convert's on the file that is not turned (measured: 190 levels).
     */
    private fun unturned(heic: ByteArray): ByteArray {
        val out = heic.copyOf()
        val irot = "irot".encodeToByteArray()
        for (i in 0 until out.size - 4) if ((0 until 4).all { out[i + it] == irot[it] }) out[i + 4] = 0
        return out
    }

    /** libheif's RGB of [heic], as heif-convert writes it to a PNG. */
    private fun libheif(heic: ByteArray): KiteBitmap {
        val input = temp(".heic").apply { writeBytes(heic) }
        val out = temp(".png")
        val (code, log) = run(Tools.require("heif-convert").path, input.path, out.path)
        assertEquals(0, code, "heif-convert failed: ${log.decodeToString()}")
        return ImageKodec.decode(out.readBytes())
    }

    // ---- synthetic files -------------------------------------------------------------

    private class Synthetic(
        val name: String,
        val file: ByteArray,
        val coded: List<Coded>,
        val width: Int,
        val height: Int,
        val depth: Int,
        val alpha: Boolean = false,
        val orientation: Orientation = Orientation.Normal,
        /** The RGB is compared with heif-convert: 4:0:0 and 4:4:4 only. */
        val rgb: Boolean = false,
    )

    private val bt709Full = nclx(1, 13, 1, true)

    /** One item of [w] by [h] with [properties] after its hvcC and ispe. */
    private fun single(name: String, w: Int, h: Int, pixFmt: String, depth: Int, rgb: Boolean, params: String = "", vararg properties: Pair<ByteArray, Boolean>): Synthetic {
        val c = encode(testsrc(w, h), pixFmt, params)
        val file = heif { add(1, "hvc1", c.sample, listOf(hvcC(c), ispe(w, h)) + properties) }
        return Synthetic(name, file, listOf(c), w, h, depth, rgb = rgb)
    }

    /**
     * A 2x2 grid of [tw] by [th] tiles cut from one picture, its output [w] by [h], with [extra] on the grid item.
     * The tiles carry the grid's nclx too: libheif 1.17.6 converts by the tiles' colour, and without it reads
     * up to 54 levels away from the grid's nclx, which this reader follows.
     */
    private fun gridFile(name: String, tw: Int, th: Int, w: Int, h: Int, pixFmt: String, extra: List<Pair<ByteArray, Boolean>> = emptyList(), shown: Pair<Int, Int> = w to h): Synthetic {
        val tiles = List(4) { k -> encode("${testsrc(2 * tw, 2 * th)},crop=$tw:$th:${(k % 2) * tw}:${(k / 2) * th}", pixFmt) }
        val file = heif {
            add(1, "grid", grid(2, 2, w, h), listOf(ispe(w, h), bt709Full) + extra)
            for ((k, t) in tiles.withIndex()) add(2 + k, "hvc1", t.sample, listOf(hvcC(t), ispe(tw, th), bt709Full), hidden = true)
            references += Triple("dimg", 1L, longArrayOf(2, 3, 4, 5))
        }
        return Synthetic(name, file, tiles, shown.first, shown.second, 8, rgb = true)
    }

    private fun synthetics(): List<Synthetic> {
        val out = ArrayList<Synthetic>()
        out += single("8 bits 4:2:0", 64, 48, "yuv420p", 8, false)
        out += single("10 bits 4:2:0", 66, 38, "yuv420p10le", 10, false)
        out += single("4:2:2 10 bits", 80, 48, "yuv422p10le", 10, false)
        out += single("4:2:2 8 bits", 64, 64, "yuv422p", 8, false, "", bt709Full)
        out += single("monochrome 8 bits", 64, 48, "gray", 8, true, "", nclx(2, 2, 2, true))
        out += single("monochrome 10 bits", 72, 40, "gray10le", 10, true, "", nclx(2, 2, 2, true))
        out += single("4:4:4 8 bits", 64, 48, "yuv444p", 8, true, "", bt709Full)
        out += single("4:4:4 10 bits, BT.601 limited range", 72, 56, "yuv444p10le", 10, true, "", nclx(1, 13, 6, false))
        out += gridFile("2x2 grid", 64, 48, 128, 96, "yuv444p")
        out += gridFile("2x2 grid cropped by its output size", 64, 48, 120, 90, "yuv444p")
        // Twelve samples off the left, (120 - 92) / 2 - 2, and eight off the top, (90 - 74) / 2.
        out += gridFile("2x2 grid with a clean aperture", 64, 48, 120, 90, "yuv444p", listOf(clap(92, 74, -2, 0, 1)), 92 to 74)
        // A colour item and its alpha item, which x265 codes in full range.
        val color = encode(testsrc(64, 48), "yuv444p")
        val alpha = encode("${testsrc(64, 48)},hue=s=0,rotate=0.5", "gray", "range=full")
        for ((name, extra) in listOf("alpha" to emptyList(), "alpha, irot 1" to listOf(irot(1)))) {
            val file = heif {
                add(1, "hvc1", color.sample, listOf(hvcC(color), ispe(64, 48), bt709Full) + extra)
                add(2, "hvc1", alpha.sample, listOf(hvcC(alpha), ispe(64, 48), alphaAux), hidden = true)
                references += Triple("auxl", 2L, longArrayOf(1))
            }
            out += Synthetic(name, file, listOf(color, alpha), 64, 48, 8, alpha = true, orientation = if (extra.isEmpty()) Orientation.Normal else Orientation.Rotate270, rgb = true)
        }
        run {
            val tiles = List(4) { k -> encode("${testsrc(64, 64)},crop=32:32:${(k % 2) * 32}:${(k / 2) * 32}", "yuv444p") }
            val a = encode("${testsrc(60, 56)},hue=s=0", "gray", "range=full")
            val file = heif {
                add(1, "grid", grid(2, 2, 60, 56), listOf(ispe(60, 56), bt709Full))
                for ((k, t) in tiles.withIndex()) add(2 + k, "hvc1", t.sample, listOf(hvcC(t), ispe(32, 32), bt709Full), hidden = true)
                add(6, "hvc1", a.sample, listOf(hvcC(a), ispe(60, 56), alphaAux), hidden = true)
                references += Triple("dimg", 1L, longArrayOf(2, 3, 4, 5))
                references += Triple("auxl", 6L, longArrayOf(1))
            }
            out += Synthetic("grid with alpha", file, tiles + a, 60, 56, 8, alpha = true, rgb = true)
        }
        // An overlay of two items on a filled canvas, the second partly off it.
        run {
            val a = encode(testsrc(48, 32), "yuv444p")
            val b = encode("${testsrc(32, 32)},hue=h=90", "yuv444p")
            val iovl = byteArrayOf(0, 0) + be16(0x2000) + be16(0x8000) + be16(0xC000) + be16(0xFFFF) + be16(80) + be16(56) +
                be16(4) + be16(6) + be16(60) + be16(30)
            val file = heif {
                add(1, "iovl", iovl, listOf(ispe(80, 56)))
                add(2, "hvc1", a.sample, listOf(hvcC(a), ispe(48, 32), bt709Full), hidden = true)
                add(3, "hvc1", b.sample, listOf(hvcC(b), ispe(32, 32), bt709Full), hidden = true)
                references += Triple("dimg", 1L, longArrayOf(2, 3))
            }
            out += Synthetic("overlay", file, listOf(a, b), 80, 56, 8, rgb = true)
        }
        val turned = encode(testsrc(64, 40), "yuv444p")
        fun oriented(name: String, orientation: Orientation, size: Pair<Int, Int>, vararg properties: Pair<ByteArray, Boolean>) {
            val file = heif { add(1, "hvc1", turned.sample, listOf(hvcC(turned), ispe(64, 40), bt709Full) + properties) }
            out += Synthetic(name, file, listOf(turned), size.first, size.second, 8, orientation = orientation, rgb = true)
        }
        // irot turns anticlockwise; the EXIF orientation names the clockwise turn that shows the picture.
        oriented("irot 1", Orientation.Rotate270, 64 to 40, irot(1))
        oriented("irot 2", Orientation.Rotate180, 64 to 40, irot(2))
        oriented("irot 3", Orientation.Rotate90, 64 to 40, irot(3))
        oriented("imir 0", Orientation.FlipVertical, 64 to 40, imir(0))
        oriented("imir 1", Orientation.FlipHorizontal, 64 to 40, imir(1))
        oriented("clap, irot 1 and imir 0", Orientation.Transpose, 40 to 30, clap(40, 30, 0, 0, 1), irot(1), imir(0))
        return out
    }

    /**
     * Each synthetic file: every coded item equals libde265 and ffmpeg; probe agrees with what decodes;
     * decode16 narrows to decode; and on 4:0:0 and 4:4:4 the RGB matches heif-convert's within one level
     * (measured worst: 1, the rounding of libheif's fixed-point conversion).
     */
    @Test
    fun syntheticFilesMatchLibde265FfmpegAndLibheif() {
        requireTools()
        val failures = ArrayList<String>()
        for (s in synthetics()) {
            try {
                for ((k, c) in s.coded.withIndex()) checkItem("${s.name}, item ${k + 1}", c.hvcC, c.sample)
                val info = ImageKodec.probe(s.file)
                assertEquals(ImageFormat.HEIC, info.format, s.name)
                assertTrue(info.isDecodable, "${s.name}: ${info.unsupportedReason}")
                assertEquals(s.width to s.height, info.width to info.height, "${s.name} probe size")
                assertEquals(s.depth, info.bitDepth, "${s.name} probe depth")
                assertEquals(s.alpha, info.hasAlpha, "${s.name} probe alpha")
                assertEquals(s.orientation, info.orientation, "${s.name} orientation")
                val bitmap = ImageKodec.decode(s.file)
                assertEquals(s.width to s.height, bitmap.width to bitmap.height, "${s.name} size")
                val wide = ImageKodec.decode16(s.file)
                assertContentEquals(bitmap.argb, wide.toBitmap().argb, "${s.name}: decode16 does not narrow to decode")
                assertEquals(if (s.alpha) 4 else 3, wide.channels, "${s.name} channels")
                val line = StringBuilder("${s.name}: ${s.coded.size} items match libde265 and ffmpeg")
                if (s.rgb) {
                    val shown = ImageKodec.decode(s.file, applyOrientation = true)
                    assertEquals(shown.width to shown.height, info.displayWidth to info.displayHeight, "${s.name} display size")
                    val turnedAlpha = s.alpha && s.orientation != Orientation.Normal
                    var w = worst(s.name, shown, libheif(s.file), colourOnly = turnedAlpha)
                    if (turnedAlpha) w = maxOf(w, worst("${s.name}, not turned", ImageKodec.decode(s.file), libheif(unturned(s.file))))
                    line.append(", RGB within $w of heif-convert")
                    assertTrue(w <= 1, "${s.name} differs from heif-convert by $w")
                }
                println(line)
            } catch (e: AssertionError) {
                failures += "${s.name}: ${e.message}"
            } catch (e: Exception) {
                failures += "${s.name}: ${e::class.simpleName} ${e.message}\n${e.stackTrace.take(8).joinToString("\n")}"
            }
        }
        for (f in failures) println("FAILED $f")
        assertTrue(failures.isEmpty(), "${failures.size} cases failed:\n${failures.joinToString("\n")}")
    }

    /**
     * The colour of an item without nclx comes from the VUI of its stream, also beside an ICC profile, as an
     * Apple photo has it: the RGB equals that of the same item with an nclx of the VUI's values, probe reports
     * them, and `ColorTarget.Srgb` converts through them.
     */
    @Test
    fun colourFromTheVui() {
        requireTools()
        val c = encode(testsrc(64, 48), "yuv444p", "colorprim=bt470bg:transfer=smpte170m:colormatrix=bt470bg:range=full")
        fun file(vararg properties: Pair<ByteArray, Boolean>) = heif { add(1, "hvc1", c.sample, listOf(hvcC(c), ispe(64, 48)) + properties) }
        val vui = file()
        val withNclx = file(nclx(5, 6, 5, true))
        val withIcc = file(prof(sRgbLikeProfile()))
        assertContentEquals(intArrayOf(5, 6, 5, 1), ImageKodec.probe(vui).colorProfile?.cicp, "probe of the VUI")
        val iccInfo = ImageKodec.probe(withIcc).colorProfile
        assertNotNull(iccInfo?.icc, "probe keeps the ICC profile")
        assertContentEquals(intArrayOf(5, 6, 5, 1), iccInfo?.cicp, "probe of the VUI beside an ICC profile")
        val reference = ImageKodec.decode(withNclx).argb
        assertContentEquals(reference, ImageKodec.decode(vui).argb, "the VUI's matrix and range")
        assertContentEquals(reference, ImageKodec.decode(withIcc).argb, "the VUI's matrix and range beside an ICC profile")
        val srgb = ImageKodec.decode(vui, colorTarget = ColorTarget.Srgb()).argb
        assertContentEquals(ImageKodec.decode(withNclx, colorTarget = ColorTarget.Srgb()).argb, srgb, "to sRGB through the VUI")
        assertFalse(srgb.contentEquals(reference), "BT.601's curve and primaries are not sRGB's")
        // The same against libheif, which reads the matrix and range of an nclx.
        val w = worst("nclx of the VUI", ImageKodec.decode(withNclx), libheif(withNclx))
        println("colour from the VUI: equal to the nclx item, which is within $w of heif-convert")
        assertTrue(w <= 1, "differs from heif-convert by $w")
    }

    /** An `altr` group shows the first item this reader can: here the HEVC item after one of an unknown type. */
    @Test
    fun anAltrGroupShowsTheHevcAlternative() {
        requireTools()
        val c = encode(testsrc(64, 48), "yuv444p")
        val alone = heif { add(1, "hvc1", c.sample, listOf(hvcC(c), ispe(64, 48), bt709Full)) }
        val grouped = heif {
            add(1, "zzzz", ByteArray(16), listOf(ispe(64, 48)))
            add(2, "hvc1", c.sample, listOf(hvcC(c), ispe(64, 48), bt709Full))
            groups += "altr" to longArrayOf(1, 2)
        }
        assertTrue(ImageKodec.probe(grouped).isDecodable)
        assertContentEquals(ImageKodec.decode(alone).argb, ImageKodec.decode(grouped).argb)
    }

    /** A minimal valid RGB display profile: probe keeps its bytes, and a Source decode ignores it. */
    private fun sRgbLikeProfile(): ByteArray {
        val p = ByteArray(132)
        be32(132).copyInto(p, 0)
        "mntr".encodeToByteArray().copyInto(p, 12)
        "RGB ".encodeToByteArray().copyInto(p, 16)
        "XYZ ".encodeToByteArray().copyInto(p, 20)
        "acsp".encodeToByteArray().copyInto(p, 36)
        return p
    }

    // ---- the committed vector ---------------------------------------------------------

    /**
     * How [HeicVector.file] was made: a 2x2 grid of 32 by 32 x265 tiles at 4:4:4 with 16 by 16 CTBs and both
     * loop filters, shown at 60 by 56, turned a quarter anticlockwise, with a full-range alpha item.
     */
    private fun vectorFile(): ByteArray {
        val opts = "ctu=16:min-cu-size=8:qp=36:ipratio=1"
        val tiles = List(4) { k -> encode("${testsrc(64, 64)},crop=32:32:${(k % 2) * 32}:${(k / 2) * 32}", "yuv444p", opts) }
        val alpha = encode("nullsrc=size=60x56,geq=lum='X*4+Y':cb=128:cr=128", "gray", "$opts:range=full")
        return heif {
            add(1, "grid", grid(2, 2, 60, 56), listOf(ispe(60, 56), bt709Full, irot(1)))
            for ((k, t) in tiles.withIndex()) add(2 + k, "hvc1", t.sample, listOf(hvcC(t), ispe(32, 32), bt709Full), hidden = true)
            add(6, "hvc1", alpha.sample, listOf(hvcC(alpha), ispe(60, 56), alphaAux), hidden = true)
            references += Triple("dimg", 1L, longArrayOf(2, 3, 4, 5))
            references += Triple("auxl", 6L, longArrayOf(1))
        }
    }

    /**
     * The vector that every target decodes: each coded item equals libde265 and ffmpeg, and its RGB, turned,
     * matches heif-convert's within one level. Set `IMAGEKODEC_HEIC_VECTOR` to a path to write a new one there.
     */
    @Test
    fun theCommittedVectorMatchesLibde265AndLibheif() {
        requireTools()
        System.getenv("IMAGEKODEC_HEIC_VECTOR")?.let { File(it).writeText(vectorFile().joinToString("") { b -> "%02x".format(b) }) }
        val file = HeicVector.file
        val items = HeifItems(file)
        for (item in items.items.values.filter { it.type == "hvc1" }) checkItem("vector item ${item.id}", item.property("hvcC")!!, item.data)
        val shown = ImageKodec.decode(file, applyOrientation = true)
        assertEquals(56 to 60, shown.width to shown.height)
        val w = maxOf(worst("the vector", shown, libheif(file), colourOnly = true), worst("the vector, not turned", ImageKodec.decode(file), libheif(unturned(file))))
        println("the committed vector: ${items.items.size - 1} coded items match libde265 and ffmpeg, RGB within $w of heif-convert")
        assertTrue(w <= 1, "the vector differs from heif-convert by $w")
    }

    // ---- real files ------------------------------------------------------------------

    private fun samples(): File? {
        val dir = File(System.getenv("IMAGEKODEC_HEIC_DATA") ?: "../reference/heic", "samples")
        if (dir.isDirectory) return dir
        if (System.getenv(Tools.REQUIRE_ENV) == "1") error("no HEIC samples at $dir, and ${Tools.REQUIRE_ENV}=1 forbids skipping; run tools/heic_data.py")
        return null
    }

    /** The primary image's size as heif-info prints it. */
    private fun heifInfoSize(file: File): Pair<Int, Int> {
        val (code, log) = run(Tools.require("heif-info").path, file.path)
        assertEquals(0, code, "heif-info failed on ${file.name}")
        val m = Regex("image: (\\d+)x(\\d+) \\(id=\\d+\\), primary").find(log.decodeToString()) ?: fail("heif-info names no primary image")
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    /**
     * Each sample photo: it decodes, at the size probe and heif-info give; every coded item in it, the tiles
     * of its grids, thumbnails and auxiliary images, equals libde265's decode of that item; and decode16 keeps
     * the 10 bits of `cat.hif`.
     */
    @Test
    fun samplePhotos() {
        val dir = samples()
        assumeTrue("no HEIC samples; run tools/heic_data.py", dir != null)
        assumeTrue("dec265 or heif-info not installed", Tools.hasAll("dec265", "heif-info"))
        val files = dir!!.listFiles { f -> f.name.endsWith(".heic") || f.name.endsWith(".hif") }!!.sortedBy { it.name }
        assertTrue(files.size >= 4, "the four samples of tools/heic_data.py")
        for (file in files) {
            val data = file.readBytes()
            val info = ImageKodec.probe(data)
            assertTrue(info.isDecodable, "${file.name}: ${info.unsupportedReason}")
            val bitmap = ImageKodec.decode(data)
            assertEquals(info.width to info.height, bitmap.width to bitmap.height, "${file.name}: decode and probe")
            assertEquals(heifInfoSize(file), bitmap.width to bitmap.height, "${file.name}: heif-info")
            val wide = ImageKodec.decode16(data)
            assertContentEquals(bitmap.argb, wide.toBitmap().argb, "${file.name}: decode16 does not narrow to decode")
            if (info.bitDepth > 8) {
                // A 10-bit sample widens as v * 65535 / 1023, so its low six bits are not all zero and not a copy of the top.
                assertTrue(wide.samples.any { s -> ((s.toInt() and 0xFFFF) shr 8) != ((s.toInt() and 0xFF)) }, "${file.name}: decode16 keeps more than 8 bits")
            }
            val items = HeifItems(data)
            var checked = 0
            for (item in items.items.values.filter { it.type == "hvc1" }) {
                val hvcC = item.property("hvcC") ?: fail("${file.name}: item ${item.id} has no hvcC")
                checkItem("${file.name} item ${item.id}", hvcC, item.data, libde265Only = true)
                checked++
            }
            println("${file.name}: ${bitmap.width}x${bitmap.height}, ${info.bitDepth} bits, colour ${info.colorProfile?.cicp?.toList()}, ICC ${info.colorProfile?.icc?.size} bytes; $checked coded items equal libde265")
        }
    }

    /** The decode time of the photos after warm-up, for the record; it fails only past a generous bound. */
    @Test
    fun decodeTimeOfThePhotos() {
        val dir = samples()
        assumeTrue("no HEIC samples; run tools/heic_data.py", dir != null)
        for (name in listOf("pug.heic", "cat.hif")) {
            val data = File(dir, name).readBytes()
            repeat(2) { ImageKodec.decode(data) }
            val runs = 3
            val start = System.nanoTime()
            repeat(runs) { ImageKodec.decode(data) }
            val ms = (System.nanoTime() - start) / 1_000_000.0 / runs
            println("$name: %.0f ms a decode on the JVM".format(ms))
            assertTrue(ms < 60_000, "$name took $ms ms")
        }
    }
}
