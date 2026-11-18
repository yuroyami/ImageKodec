package io.github.yuroyami.imagekodec

import org.junit.Assume.assumeTrue
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The JPEG XL encoder's sizes (#179) against libjxl's cjxl on the scenes of [PhotoScenes],
 * printed as tables with the encode times. Lossless: bytes against `cjxl -d 0 -e 7`. The
 * bounds catch a regression; the tables are the measure. Skips without cjxl.
 */
class JxlEncodeBenchmarkTest {

    private fun run(vararg args: String): Pair<Int, String> {
        val kill = Tools.find("timeout")
        val command = if (kill != null) listOf(kill.path, "-s", "KILL", "300") + args else args.toList()
        val proc = ProcessBuilder(command).redirectErrorStream(true).start()
        val out = ByteArrayOutputStream()
        proc.inputStream.copyTo(out)
        if (!proc.waitFor(320, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return -1 to ""
        }
        return proc.exitValue() to out.toString()
    }

    private fun temp(ext: String) = File.createTempFile("kite-jxlbench", ext).apply { deleteOnExit() }

    /** cjxl's file of [bitmap] with [options], and the milliseconds it took. */
    private fun cjxl(bitmap: KiteBitmap, vararg options: String): Pair<ByteArray, Long> {
        val input = temp(".png").apply { writeBytes(ImageKodec.encodePng(bitmap)) }
        val out = temp(".jxl")
        val start = System.nanoTime()
        val (code, text) = run(Tools.require("cjxl").path, input.path, out.path, *options)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(code == 0, "cjxl failed: $text")
        return out.readBytes() to ms
    }

    private inline fun timed(block: () -> ByteArray): Pair<ByteArray, Long> {
        val start = System.nanoTime()
        val r = block()
        return r to (System.nanoTime() - start) / 1_000_000
    }

    /** Our file at the largest distance whose SSIMULACRA 2 score reaches [target], by bisection on the distance. */
    private fun matchScore(bitmap: KiteBitmap, target: Double, effort: Int): Triple<ByteArray, Double, Double> {
        var lo = 0.1
        var hi = 15.0
        var best: Triple<ByteArray, Double, Double>? = null
        repeat(9) {
            val d = (lo + hi) / 2
            val jxl = ImageKodec.encodeJxl(bitmap, JxlEncodeOptions(distance = d, effort = effort))
            val score = ImageKodec.ssimulacra2(bitmap, ImageKodec.decode(jxl))
            if (score >= target) {
                best = Triple(jxl, score, d)
                lo = d
            } else {
                hi = d
            }
        }
        return best ?: ImageKodec.encodeJxl(bitmap, JxlEncodeOptions(distance = 0.1, effort = effort)).let {
            Triple(it, ImageKodec.ssimulacra2(bitmap, ImageKodec.decode(it)), 0.1)
        }
    }

    @Test
    fun lossySizesAtEqualScoreAgainstCjxl() {
        assumeTrue("cjxl is not installed", Tools.hasAll("cjxl"))
        ImageKodec.encodeJxl(PhotoScenes.bokeh().bitmap, JxlEncodeOptions(distance = 1.0))
        var worst = 0.0
        for (d in listOf("1", "2.5")) {
            println("cjxl -e 7 -d $d: bytes, score, ms | ours at the same score, effort 2: bytes, score, distance, ms, ratio | ours at -d $d: score")
            var ours = 0L
            var theirs = 0L
            for (scene in PhotoScenes.all()) {
                val (ref, refMs) = cjxl(scene.bitmap, "-d", d, "-e", "7")
                val refScore = ImageKodec.ssimulacra2(scene.bitmap, ImageKodec.decode(ref))
                val (jxl, score, dist) = matchScore(scene.bitmap, refScore, 2)
                val (_, ms) = timed { ImageKodec.encodeJxl(scene.bitmap, JxlEncodeOptions(distance = dist, effort = 2)) }
                val same = ImageKodec.encodeJxl(scene.bitmap, JxlEncodeOptions(distance = d.toDouble()))
                val sameScore = ImageKodec.ssimulacra2(scene.bitmap, ImageKodec.decode(same))
                println(
                    "%-10s %7d %6.2f %5d ms | %7d %6.2f %5.2f %5d ms %5.3f | %6.2f".format(
                        scene.name, ref.size, refScore, refMs, jxl.size, score, dist, ms, jxl.size.toDouble() / ref.size, sameScore,
                    ),
                )
                ours += jxl.size
                theirs += ref.size
            }
            val ratio = ours.toDouble() / theirs
            println("total at -d $d: ours / cjxl = %.3f".format(ratio))
            worst = maxOf(worst, ratio)
        }
        assertTrue(worst < 1.2, "lossy files are %.3f times cjxl's at the same score".format(worst))
    }

    @Test
    fun losslessSizesAgainstCjxl() {
        assumeTrue("cjxl is not installed", Tools.hasAll("cjxl"))
        // The first encode warms the JIT, so the times below are of compiled code.
        ImageKodec.encodeJxl(PhotoScenes.bokeh().bitmap, JxlEncodeOptions(lossless = true))
        println("lossless      cjxl -e 7        effort 1         effort 2         effort 3")
        var ours = 0L
        var theirs = 0L
        for (scene in PhotoScenes.all()) {
            val (ref, refMs) = cjxl(scene.bitmap, "-d", "0", "-e", "7")
            val row = StringBuilder("%-10s %8d %5d ms".format(scene.name, ref.size, refMs))
            for (effort in 1..3) {
                val (jxl, ms) = timed { ImageKodec.encodeJxl(scene.bitmap, JxlEncodeOptions(lossless = true, effort = effort)) }
                row.append(" %8d %5d ms".format(jxl.size, ms))
                if (effort == 2) ours += jxl.size
            }
            theirs += ref.size
            println(row)
        }
        val ratio = ours.toDouble() / theirs
        println("effort 2 total / cjxl -e 7 total = %.3f".format(ratio))
        assertTrue(ratio < 1.25, "lossless effort 2 is %.3f times cjxl's size".format(ratio))
    }
}
