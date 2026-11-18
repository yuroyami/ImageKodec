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

    /** A ball that moves over a photo, with a clear border that fills in, and a repeated frame. */
    private fun animation(): KiteAnimation {
        val base = PhotoScenes.landscape(96, 72).bitmap
        fun frame(t: Int): KiteBitmap = KiteBitmap(96, 72, IntArray(96 * 72) { i ->
            val x = i % 96
            val y = i / 96
            val dx = x - (20 + t * 15)
            val dy = y - (30 + t * 5)
            when {
                y < 6 && x > 90 - t * 20 -> 0
                y < 6 -> argb(0x60 + t * 30, 200, 200, 40)
                dx * dx + dy * dy < 120 -> argb(255, 250, 40, 40)
                else -> base.argb[i]
            }
        })
        val frames = listOf(frame(0), frame(1), frame(1), frame(2), frame(3))
        return KiteAnimation(96, 72, frames.mapIndexed { i, b -> KiteFrame(b, 40 + 70 * i, (40 + 70 * i) / 10) }, loopCount = 7)
    }

    private fun readPam(file: File): KiteBitmap {
        val bytes = file.readBytes()
        val header = bytes.decodeToString(0, minOf(bytes.size, 200))
        val w = Regex("WIDTH (\\d+)").find(header)!!.groupValues[1].toInt()
        val h = Regex("HEIGHT (\\d+)").find(header)!!.groupValues[1].toInt()
        val start = header.indexOf("ENDHDR\n") + 7
        return KiteBitmap(w, h, IntArray(w * h) { i ->
            val o = start + i * 4
            argb(bytes[o + 3].toInt() and 255, bytes[o].toInt() and 255, bytes[o + 1].toInt() and 255, bytes[o + 2].toInt() and 255)
        })
    }

    @Test
    fun animDumpAndWebpmuxReadEveryAnimation() {
        assumeTrue("anim_dump or webpmux is not installed", Tools.hasAll("anim_dump", "webpmux"))
        val anim = animation()
        val settings = listOf(WebpEncodeOptions(lossless = true), WebpEncodeOptions(quality = 70), WebpEncodeOptions(quality = 95, effort = 3, background = 0xFF336699.toInt()))
        for (options in settings) {
            val webp = File(dir, "anim.webp").apply { writeBytes(ImageKodec.encodeWebp(anim, options)) }
            val out = File(dir, "dump").apply { deleteRecursively(); mkdirs() }
            val (code, text) = run(Tools.require("anim_dump").path, "-folder", out.path, "-prefix", "f", "-pam", webp.path)
            assertEquals(0, code, "anim_dump failed: $text")
            val ours = ImageKodec.decodeAnimation(webp.readBytes())
            val dumped = out.listFiles()!!.filter { it.name.endsWith(".pam") }.sortedBy { it.name }
            assertEquals(anim.frames.size, dumped.size, "$options: frames anim_dump writes")
            assertEquals(anim.frames.size, ours.frames.size, "$options: frames ImageKodec reads")
            for ((i, file) in dumped.withIndex()) {
                // anim_dump sets every fully transparent pixel to 0 before it writes a frame
                // (CleanupTransparentPixels in libwebp's examples/anim_util.c), so ours are cleaned alike.
                val mine = ours.frames[i].bitmap.argb.map { if (it ushr 24 == 0) 0 else it }
                val theirs = readPam(file).argb.toList()
                val differing = mine.indices.count { mine[it] != theirs[it] }
                assertEquals(0, differing, "$options, frame $i: $differing pixels differ from anim_dump's")
                if (options.lossless) {
                    val source = anim.frames[i].bitmap.argb.map { if (it ushr 24 == 0) 0 else it }
                    assertEquals(source, mine, "frame $i is not the source")
                }
            }
            val (infoCode, info) = run(Tools.require("webpmux").path, "-info", webp.path)
            assertEquals(0, infoCode, info)
            println(info)
            assertTrue(info.contains("Number of frames: ${anim.frames.size}"), info)
            assertTrue(info.contains("Loop Count : 7"), info)
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
