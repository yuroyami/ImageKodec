package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.jxr.JxrDecoder
import org.junit.Assume.assumeTrue
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The JPEG XR encoder (#181) against jxrlib's `JxrDecApp`: every file the encoder writes, in every
 * pixel format and coding option it has, decodes in `JxrDecApp` to exactly the samples
 * ImageKodec's decoder gives, and a lossless file to the input. `exiftool` reads the metadata of
 * the container back. Skips without `JxrDecApp` (Debian's libjxr-tools), and the metadata test
 * without `exiftool`.
 */
class JxrEncodeOracleTest {

    private fun run(vararg args: String): Pair<Int, String> {
        val proc = ProcessBuilder(args.toList()).redirectErrorStream(true).start()
        val out = ByteArrayOutputStream()
        proc.inputStream.copyTo(out)
        if (!proc.waitFor(300, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return -1 to ""
        }
        return proc.exitValue() to out.toString()
    }

    private fun temp(ext: String) = File.createTempFile("kite-jxr-enc", ext).apply { deleteOnExit() }

    /** The samples of a TIFF that JxrDecApp wrote: one strip, uncompressed, little endian. */
    private class Tiff(val spp: Int, val samples: IntArray)

    private fun readTiff(bytes: ByteArray): Tiff {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val ifd = b.getInt(4)
        val n = b.getShort(ifd).toInt()
        val tags = HashMap<Int, IntArray>()
        for (i in 0 until n) {
            val at = ifd + 2 + 12 * i
            val tag = b.getShort(at).toInt() and 0xFFFF
            if (tag !in setOf(256, 257, 258, 273, 277)) continue
            val type = b.getShort(at + 2).toInt()
            val count = b.getInt(at + 4)
            val size = if (type == 3) 2 else 4
            val base = if (count * size > 4) b.getInt(at + 8) else at + 8
            tags[tag] = IntArray(count) { k -> if (type == 3) b.getShort(base + 2 * k).toInt() and 0xFFFF else b.getInt(base + 4 * k) }
        }
        val w = tags.getValue(256)[0]
        val h = tags.getValue(257)[0]
        val spp = tags.getValue(277)[0]
        val bits = tags.getValue(258)[0]
        val start = tags.getValue(273)[0]
        return Tiff(spp, IntArray(w * h * spp) { i -> if (bits == 8) bytes[start + i].toInt() and 0xFF else b.getShort(start + 2 * i).toInt() and 0xFFFF })
    }

    private fun reference(jxr: ByteArray): Tiff {
        val input = temp(".jxr").apply { writeBytes(jxr) }
        val out = temp(".tif")
        val (code, text) = run(Tools.require("JxrDecApp").path, "-i", input.path, "-o", out.path)
        assertTrue(code == 0, "JxrDecApp failed: $text")
        return readTiff(out.readBytes())
    }

    /** ImageKodec's samples of [jxr] in the order JxrDecApp writes them: gray, or red, green, blue and alpha. */
    private fun ours(jxr: ByteArray): Pair<Int, IntArray> {
        val d = JxrDecoder.decodeRaw(jxr)
        val pf = d.format
        val r = d.raster
        val color = if (pf.colorFormat == 0) 1 else 3
        val channels = color + if (d.alpha) 1 else 0
        val out = IntArray(r.width * r.height * channels)
        for (i in 0 until r.width * r.height) {
            for (c in 0 until channels) out[i * channels + c] = r.samples[i * r.slots + if (c < color && pf.bgr) 2 - c else c]
        }
        return channels to out
    }

    /** The input as JxrDecApp would write it: gray, or red, green, blue and alpha. */
    private fun samples(b: KiteBitmap, channels: Int): IntArray = IntArray(b.width * b.height * channels) { i ->
        val p = b.argb[i / channels]
        when (if (channels == 1) 0 else i % channels) {
            0 -> (p shr 16) and 0xFF
            1 -> (p shr 8) and 0xFF
            2 -> p and 0xFF
            else -> p ushr 24
        }
    }

    private var files = 0
    private var compared = 0L

    /** [jxr] read by JxrDecApp and by ImageKodec must agree sample for sample, and equal [exact] when given. */
    private fun check(label: String, jxr: ByteArray, exact: IntArray? = null) {
        val want = reference(jxr)
        val (channels, got) = ours(jxr)
        assertEquals(want.spp, channels, "$label: channels")
        assertEquals(want.samples.size, got.size, "$label: samples")
        for (i in got.indices) {
            if (got[i] != want.samples[i]) fail("$label: sample $i is ${got[i]}, JxrDecApp ${want.samples[i]}")
        }
        if (exact != null) {
            for (i in got.indices) if (got[i] != exact[i]) fail("$label: sample $i is ${got[i]}, the input ${exact[i]}")
        }
        files++
        compared += got.size
    }

    private fun scene(w: Int, h: Int, seed: Int, alpha: Boolean, gray: Boolean): KiteBitmap {
        val rng = java.util.Random(seed.toLong())
        return KiteBitmap(w, h, IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val noise = if (x in w / 5 until w / 2 && y in h / 4 until h / 2) rng.nextInt(256) else 0
            val edge = if (x > w * 3 / 4) (if ((x / 3 + y / 4) % 2 == 0) 230 else 25) else -1
            val r = if (edge >= 0) edge else (x * 5 + y * 7 + noise) and 0xFF
            val g = if (gray) r else if (edge >= 0) 255 - edge else (x * 3 + y * 2 + noise / 3) and 0xFF
            val b = if (gray) r else if (edge >= 0) edge / 2 else (x * 9 + y * 3 + 60) and 0xFF
            val a = if (alpha) (x * 3 + y * 11) and 0xFF else 255
            (a shl 24) or (r shl 16) or (g shl 8) or b
        })
    }

    private fun scene16(w: Int, h: Int, channels: Int, seed: Int): KiteBitmap16 {
        val rng = java.util.Random(seed.toLong())
        return KiteBitmap16(w, h, channels, ShortArray(w * h * channels) { i ->
            val p = i / channels
            val c = i % channels
            val x = p % w
            val y = p / w
            val v = if (x in w / 5 until w / 2) rng.nextInt(65536) else (x * 701 + y * 433 + c * 9000) and 0xFFFF
            v.toShort()
        })
    }

    private class Case(val name: String, val options: JxrEncodeOptions)

    private val lossless = listOf(
        Case("lossless", JxrEncodeOptions(lossless = true)),
        Case("lossless overlap 1", JxrEncodeOptions(lossless = true, overlap = 1)),
        Case("lossless overlap 2", JxrEncodeOptions(lossless = true, overlap = 2)),
        Case("lossless tiles", JxrEncodeOptions(lossless = true, tileSize = 32)),
        Case("lossless frequency", JxrEncodeOptions(lossless = true, frequencyOrder = true, tileSize = 48)),
    )

    private val lossy = listOf(
        Case("q90", JxrEncodeOptions(quality = 90)),
        Case("q60", JxrEncodeOptions(quality = 60)),
        Case("q30", JxrEncodeOptions(quality = 30)),
        Case("q75 overlap 0", JxrEncodeOptions(quality = 75, overlap = 0)),
        Case("q75 overlap 2", JxrEncodeOptions(quality = 75, overlap = 2)),
        Case("q75 4:2:0", JxrEncodeOptions(quality = 75, chroma = JxrChroma.Half)),
        Case("q75 4:2:0 overlap 0", JxrEncodeOptions(quality = 75, chroma = JxrChroma.Half, overlap = 0)),
        Case("q40 4:4:4", JxrEncodeOptions(quality = 40, chroma = JxrChroma.Full)),
        Case("q100", JxrEncodeOptions(quality = 100)),
        Case("q0", JxrEncodeOptions(quality = 0)),
        Case("q80 chroma 40", JxrEncodeOptions(quality = 80, chromaQuality = 40)),
        Case("q70 tiles", JxrEncodeOptions(quality = 70, tileSize = 32)),
        Case("q50 tiles 4:2:0 overlap 2", JxrEncodeOptions(quality = 50, tileSize = 32, chroma = JxrChroma.Half, overlap = 2)),
        Case("tile qualities", JxrEncodeOptions(quality = 70, tileSize = 48, tileQualities = intArrayOf(90, 20, 60, 75))),
        Case("q70 frequency", JxrEncodeOptions(quality = 70, frequencyOrder = true)),
        Case("q40 frequency tiles", JxrEncodeOptions(quality = 40, frequencyOrder = true, tileSize = 32)),
        Case("alpha q50", JxrEncodeOptions(quality = 70, alphaLossless = false, alphaQuality = 50)),
    )

    @Test
    fun everyFormatAndOptionMatchesJxrDecApp() {
        assumeTrue(Tools.hasAll("JxrDecApp"))
        val sizes = listOf(83 to 61, 16 to 16, 1 to 1, 5 to 37)
        for ((w, h) in sizes) {
            for ((gray, alpha) in listOf(false to false, true to false, false to true, true to true)) {
                val src = scene(w, h, w * 7 + h, alpha, gray)
                val channels = if (alpha) 4 else if (gray) 1 else 3
                for (planar in listOf(true, false)) {
                    if (!alpha && !planar) continue
                    for (case in lossless) {
                        val o = case.options
                        val jxr = ImageKodec.encodeJxr(src, JxrEncodeOptions(lossless = true, overlap = o.overlap, tileSize = o.tileSize, frequencyOrder = o.frequencyOrder, planarAlpha = planar))
                        check("$w x $h ${channels}ch planar $planar ${case.name}", jxr, samples(src, channels))
                    }
                    for (case in lossy) {
                        val o = case.options
                        val tiles = ((w + 47) / 48) * ((h + 47) / 48)
                        if ((o.tileQualities?.size ?: tiles) != tiles) continue
                        val jxr = ImageKodec.encodeJxr(
                            src,
                            JxrEncodeOptions(
                                quality = o.quality, chroma = o.chroma, overlap = o.overlap, chromaQuality = o.chromaQuality,
                                planarAlpha = planar, alphaLossless = o.alphaLossless, alphaQuality = o.alphaQuality,
                                tileSize = o.tileSize, tileQualities = o.tileQualities, frequencyOrder = o.frequencyOrder,
                            ),
                        )
                        check("$w x $h ${channels}ch planar $planar ${case.name}", jxr)
                    }
                }
            }
        }
        println("JxrEncodeOracleTest: $files files, $compared samples equal to JxrDecApp's")
    }

    /** A big-endian EXIF block: IFD0 with text tags, orientation 6 and the EXIF and GPS directories, and an Interoperability directory. */
    private fun exif(): ByteArray {
        val b = ByteBuffer.allocate(512).order(ByteOrder.BIG_ENDIAN)
        class E(val tag: Int, val type: Int, val count: Int, val value: Int)
        val data = ByteBuffer.allocate(256).order(ByteOrder.BIG_ENDIAN)
        val dataAt = 300
        fun ascii(s: String): Pair<Int, Int> {
            val at = dataAt + data.position()
            data.put((s + "\u0000").encodeToByteArray())
            if (data.position() % 2 == 1) data.put(0)
            return at to s.length + 1
        }
        fun rationals(vararg v: Int): Int {
            val at = dataAt + data.position()
            for (x in v) data.putInt(x)
            return at
        }
        fun ifd(at: Int, entries: List<E>) {
            b.position(at)
            b.putShort(entries.size.toShort())
            for (e in entries) {
                b.putShort(e.tag.toShort()).putShort(e.type.toShort()).putInt(e.count)
                if (e.type == 3 && e.count == 1) b.putShort(e.value.toShort()).putShort(0) else b.putInt(e.value)
            }
            b.putInt(0)
        }
        val (make, makeN) = ascii("KiteCam")
        val (date, dateN) = ascii("2026:11:09 08:30:00")
        val exposure = rationals(1, 250)
        val latitude = rationals(51, 1, 30, 1, 0, 1)
        ifd(8, listOf(
            E(0x010F, 2, makeN, make), E(0x0110, 2, 3, 0x58310000), E(0x0112, 3, 1, 6), E(0x0132, 2, dateN, date),
            E(0x8769, 4, 1, 100), E(0x8825, 4, 1, 200),
        ))
        ifd(100, listOf(E(0x829A, 5, 1, exposure), E(0x8827, 3, 1, 200), E(0x9003, 2, dateN, date), E(0xA005, 4, 1, 250)))
        ifd(200, listOf(E(0x0001, 2, 2, 0x4E000000), E(0x0002, 5, 3, latitude)))
        ifd(250, listOf(E(0x0001, 2, 4, 0x52393800)))
        b.position(0)
        b.put(byteArrayOf(0x4D, 0x4D, 0, 0x2A, 0, 0, 0, 8))
        val out = b.array().copyOf(dataAt + data.position())
        data.array().copyInto(out, dataAt, 0, data.position())
        return out
    }

    /** A display profile with one tag, its copyright: enough for exiftool to read its class and colour space. */
    private fun icc(): ByteArray = ByteArray(156).also {
        ByteBuffer.wrap(it).putInt(0, 156).putInt(4, 0x6C636D73).putInt(8, 0x02100000).putInt(12, 0x6D6E7472)
            .putInt(16, 0x52474220).putInt(20, 0x58595A20).putInt(36, 0x61637370)
            .putInt(128, 1).putInt(132, 0x63707274).putInt(136, 144).putInt(140, 12)
            .putInt(144, 0x74657874).putInt(152, 0x4B697465)
    }

    @Test
    fun exiftoolReadsTheMetadata() {
        assumeTrue(Tools.hasAll("exiftool", "JxrDecApp"))
        val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">""" +
            """<rdf:Description xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:creator><rdf:Seq><rdf:li>Kite Tester</rdf:li></rdf:Seq></dc:creator>""" +
            """</rdf:Description></rdf:RDF></x:xmpmeta>"""
        val src = scene(40, 24, 9, alpha = true, gray = false)
        val profile = icc()
        val jxr = ImageKodec.encodeJxr(src, JxrEncodeOptions(quality = 80), ImageMetadata(exif(), xmp, profile))
        val file = temp(".jxr").apply { writeBytes(jxr) }
        val (code, text) = run(Tools.require("exiftool").path, "-a", "-s", "-n", file.path)
        assertTrue(code == 0, "exiftool failed: $text")
        val tags = text.lines().filter { ":" in it }.associate { it.substringBefore(":").trim() to it.substringAfter(":").trim() }
        val want = mapOf(
            "Make" to "KiteCam", "Model" to "X1", "ModifyDate" to "2026:11:09 08:30:00", "DateTimeOriginal" to "2026:11:09 08:30:00",
            "ExposureTime" to "0.004", "ISO" to "200", "InteropIndex" to "R98", "GPSLatitudeRef" to "N", "GPSLatitude" to "51.5",
            "Creator" to "Kite Tester", "ProfileClass" to "mntr", "ColorSpaceData" to "RGB", "ProfileCopyright" to "Kite", "PixelFormat" to "15",
            "ImageWidth" to "40", "ImageHeight" to "24",
        )
        for ((tag, value) in want) assertEquals(value, tags[tag], "exiftool's $tag in:\n$text")

        // The orientation is the Transformation tag, which the decoder turns back into EXIF's 6.
        assertEquals(Orientation.Rotate90, ImageKodec.probe(jxr).orientation)
        assertTrue(ImageKodec.probe(jxr).colorProfile?.icc?.contentEquals(profile) == true, "the ICC profile")
        check("metadata", jxr)
        println("exiftool: ${want.size} tags read back")
    }

    @Test
    fun sixteenBitMatchesJxrDecApp() {
        assumeTrue(Tools.hasAll("JxrDecApp"))
        for (channels in 1..4) {
            for ((w, h) in listOf(45 to 30, 1 to 1, 16 to 33)) {
                val src = scene16(w, h, channels, channels * 100 + w)
                val outChannels = if (channels == 2) 4 else channels
                val exact = IntArray(w * h * outChannels) { i ->
                    val p = i / outChannels
                    val c = i % outChannels
                    val from = when {
                        channels == 2 -> if (c == 3) 1 else 0
                        else -> c
                    }
                    src.samples[p * channels + from].toInt() and 0xFFFF
                }
                for (planar in listOf(true, false)) {
                    if (!src.hasAlpha && !planar) continue
                    for (overlap in 0..2) {
                        val jxr = ImageKodec.encodeJxr16(src, JxrEncodeOptions(lossless = true, overlap = overlap, planarAlpha = planar))
                        check("16-bit $w x $h ${channels}ch lossless overlap $overlap planar $planar", jxr, exact)
                    }
                    for (q in listOf(95, 60, 20)) {
                        val jxr = ImageKodec.encodeJxr16(src, JxrEncodeOptions(quality = q, planarAlpha = planar))
                        check("16-bit $w x $h ${channels}ch q$q planar $planar", jxr)
                    }
                }
            }
        }
        println("JxrEncodeOracleTest 16-bit: $files files, $compared samples equal to JxrDecApp's")
    }
}
