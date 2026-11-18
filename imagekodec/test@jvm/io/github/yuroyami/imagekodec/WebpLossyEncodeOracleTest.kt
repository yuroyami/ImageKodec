package io.github.yuroyami.imagekodec

import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The lossy WebP writer (#178) read by libwebp 1.3.2, and measured against its encoder:
 *
 * - `dwebp` decodes every file, lossy colour, `ALPH` alpha of every filter and level count, to the
 *   pixels ImageKodec's decoder gives;
 * - on the scenes of [PhotoScenes], the size at an equal SSIMULACRA 2 score against `cwebp -m 4`,
 *   whose sizes are interpolated between the qualities either side of our score. The table prints
 *   both, with encode times; the bound holds what the encoder reaches, to catch a regression.
 *
 * Skips without cwebp and dwebp, unless IMAGEKODEC_REQUIRE_ORACLES is 1.
 */
class WebpLossyEncodeOracleTest {

    private val dir: File = Files.createTempDirectory("webp-lossy").toFile().apply { deleteOnExit() }

    private fun run(vararg command: String): Pair<Int, String> {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val out = process.inputStream.readBytes().decodeToString()
        if (!process.waitFor(300, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return -1 to "timed out"
        }
        return process.exitValue() to out
    }

    /** dwebp's reading of [webp], as ARGB. */
    private fun dwebp(webp: ByteArray, name: String): KiteBitmap {
        val input = File(dir, "$name.webp").apply { writeBytes(webp) }
        val pam = File(dir, "$name.pam")
        val (code, text) = run(Tools.require("dwebp").path, "-quiet", input.path, "-pam", "-o", pam.path)
        assertEquals(0, code, "dwebp could not decode $name: $text")
        val bytes = pam.readBytes()
        val header = bytes.decodeToString(0, minOf(bytes.size, 200))
        val w = Regex("WIDTH (\\d+)").find(header)!!.groupValues[1].toInt()
        val h = Regex("HEIGHT (\\d+)").find(header)!!.groupValues[1].toInt()
        val start = header.indexOf("ENDHDR\n") + 7
        return KiteBitmap(w, h, IntArray(w * h) { i ->
            val o = start + i * 4
            argb(bytes[o + 3].toInt() and 255, bytes[o].toInt() and 255, bytes[o + 1].toInt() and 255, bytes[o + 2].toInt() and 255)
        })
    }

    private fun assumeTools() = assumeTrue("cwebp or dwebp is not installed", Tools.hasAll("cwebp", "dwebp"))

    private fun checkSame(name: String, webp: ByteArray) {
        val theirs = dwebp(webp, name)
        val ours = ImageKodec.decode(webp)
        assertEquals(ours.width to ours.height, theirs.width to theirs.height, "$name: size")
        val differing = ours.argb.indices.count { ours.argb[it] != theirs.argb[it] }
        assertEquals(0, differing, "$name: $differing pixels differ from dwebp's")
    }

    private fun translucent(b: KiteBitmap, levels: Boolean): KiteBitmap =
        KiteBitmap(b.width, b.height, IntArray(b.argb.size) { i ->
            val x = i % b.width
            val y = i / b.width
            val a = when {
                x < b.width / 4 -> 0
                x > b.width * 3 / 4 -> 255
                levels -> (x * 7 + y * 3) and 0xFF
                else -> if ((x / 9 + y / 9) % 2 == 0) 255 else 0
            }
            (a shl 24) or (b.argb[i] and 0xFFFFFF)
        })

    @Test
    fun dwebpReadsEveryFileAsImageKodecDoes() {
        assumeTools()
        val scenes = PhotoScenes.all()
        for (scene in scenes) for (q in listOf(0, 40, 75, 95, 100)) {
            checkSame("${scene.name}-q$q", ImageKodec.encodeWebp(scene.bitmap, q))
        }
        val bokeh = PhotoScenes.bokeh().bitmap
        for (effort in 1..3) for (sharp in listOf(false, true)) {
            checkSame("bokeh-e$effort-s$sharp", ImageKodec.encodeWebp(bokeh, WebpEncodeOptions(quality = 60, effort = effort, sharpYuv = sharp)))
        }
        for (filter in WebpAlphaFilter.entries) for (aq in listOf(0, 30, 100)) for (levels in listOf(false, true)) {
            val b = translucent(PhotoScenes.portrait(120, 90).bitmap, levels)
            checkSame("alpha-$filter-$aq-$levels", ImageKodec.encodeWebp(b, WebpEncodeOptions(quality = 70, alphaQuality = aq, alphaFilter = filter)))
        }
        for ((w, h) in listOf(1 to 1, 1 to 40, 40 to 1, 17 to 9, 300 to 9, 9 to 300, 33 to 33)) {
            checkSame("edge-${w}x$h", ImageKodec.encodeWebp(PhotoScenes.bokeh(w, h).bitmap, 75))
        }
    }

    private class Point(val quality: Int, val size: Int, val score: Double)

    private fun cwebpCurve(scene: PhotoScenes.Scene): List<Point> {
        val png = File(dir, "${scene.name}.png").apply { writeBytes(ImageKodec.encodePng(scene.bitmap)) }
        return (0..100 step 5).map { q ->
            val out = File(dir, "${scene.name}-cwebp-$q.webp")
            val (code, text) = run(Tools.require("cwebp").path, "-quiet", "-m", "4", "-q", q.toString(), png.path, "-o", out.path)
            assertEquals(0, code, "cwebp failed: $text")
            val bytes = out.readBytes()
            Point(q, bytes.size, ImageKodec.ssimulacra2(scene.bitmap, ImageKodec.decode(bytes)))
        }
    }

    /** cwebp's size at [score], between the two points of its curve around it; null off the curve. */
    private fun sizeAt(curve: List<Point>, score: Double): Double? {
        val sorted = curve.sortedBy { it.score }
        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            if (score >= a.score && score <= b.score) {
                if (b.score == a.score) return a.size.toDouble()
                return a.size + (b.size - a.size) * (score - a.score) / (b.score - a.score)
            }
        }
        return null
    }

    @Test
    fun sizeAtEqualScoreAgainstCwebp() {
        assumeTools()
        val rows = ArrayList<String>()
        val ratios = ArrayList<Double>()
        var oursMillis = 0L
        var theirsMillis = 0L
        for (scene in PhotoScenes.all()) {
            val curve = cwebpCurve(scene)
            val png = File(dir, "${scene.name}.png")
            for (q in QUALITIES) {
                ImageKodec.encodeWebp(scene.bitmap, q) // warm up
                val t0 = System.nanoTime()
                val ours = ImageKodec.encodeWebp(scene.bitmap, q)
                val t1 = System.nanoTime()
                val score = ImageKodec.ssimulacra2(scene.bitmap, ImageKodec.decode(ours))
                val theirs = sizeAt(curve, score)
                val c0 = System.nanoTime()
                run(Tools.require("cwebp").path, "-quiet", "-m", "4", "-q", q.toString(), png.path, "-o", File(dir, "t.webp").path)
                val c1 = System.nanoTime()
                oursMillis += (t1 - t0) / 1_000_000
                theirsMillis += (c1 - c0) / 1_000_000
                val ratio = theirs?.let { ours.size / it }
                if (ratio != null) ratios += ratio
                rows += "%-10s %4d %8d %8.3f %10s %8s %6d %6d".format(
                    scene.name, q, ours.size, score, theirs?.let { "%.0f".format(it) } ?: "-",
                    ratio?.let { "%+.1f%%".format(100 * (it - 1)) } ?: "-", (t1 - t0) / 1_000_000, (c1 - c0) / 1_000_000,
                )
            }
        }
        val mean = ratios.sum() / ratios.size
        println("scene        q     ours    score   cwebp -m4   size     ms  cwebp ms")
        rows.forEach(::println)
        println("mean size against cwebp -m 4 at equal SSIMULACRA 2: %+.1f%%; encode %d ms, cwebp %d ms (its process included)".format(100 * (mean - 1), oursMillis, theirsMillis))
        assertTrue(mean < MEAN_BOUND, "the mean size at equal score is %.3f of cwebp's, above the bound %.3f".format(mean, MEAN_BOUND))
    }

    private companion object {
        val QUALITIES = listOf(50, 75, 90)
        const val MEAN_BOUND = 2.0
    }
}
