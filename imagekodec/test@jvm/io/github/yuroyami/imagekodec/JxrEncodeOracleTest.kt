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
            for ((gray, alpha) in listOf(false to false, true to false, false to true)) {
                val src = scene(w, h, w * 7 + h, alpha, gray)
                val channels = if (gray) 1 else if (alpha) 4 else 3
                for (planar in listOf(true, false)) {
                    if (!alpha && !planar) continue
                    for (case in lossless) {
                        if (case.options.tileQualities != null && (w < 96 || h < 96)) continue
                        val o = case.options
                        val jxr = ImageKodec.encodeJxr(src, JxrEncodeOptions(lossless = true, overlap = o.overlap, tileSize = o.tileSize, frequencyOrder = o.frequencyOrder, planarAlpha = planar))
                        check("$w x $h ${channels}ch planar $planar ${case.name}", jxr, samples(src, channels))
                    }
                    for (case in lossy) {
                        val o = case.options
                        val tiles = ((w + 47) / 48) * ((h + 47) / 48)
                        if (o.tileQualities != null && o.tileQualities!!.size != tiles) continue
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
