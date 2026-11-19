package io.github.yuroyami.imagekodec

import java.io.File
import java.lang.management.ManagementFactory
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The AV1 encoder (#176) against libavif's avifenc (libaom) on the photo-like scenes of [PhotoScenes]: the size
 * `encodeAvif` needs for the SSIMULACRA 2 score avifenc reaches at `-q 50` and `-q 80`, at speeds 6 and 9, and the
 * encode times. Each avifenc file sets a target score; ImageKodec's size at that score comes from its sizes and
 * scores at qualities 5 apart, read straight between the two that bracket it. Both write 8-bit 4:2:0 on one thread.
 *
 * The times are of the six scenes at quality 50, each the least of three runs: ImageKodec's in the CPU time of its
 * thread, after the JIT has compiled the encoder, and avifenc's as the wall time of its process, start included,
 * which other work on the machine lengthens less than it would a JVM's wall time.
 *
 * The table goes to the test output and to `build/av1-encode-benchmark.md`. It takes minutes, so it runs only with
 * `IMAGEKODEC_AV1_BENCHMARK=1`; `IMAGEKODEC_AV1_BENCHMARK_EFFORTS` (say `1,2,3`) sets the efforts, 2 by default.
 * The bounds it holds are loose, there to catch a regression: at effort 2, the mean size ratio against speed 6 stays
 * under [RATIO_BOUND], and the time against speed 6 under [TIME_BOUND].
 */
class Av1EncoderBenchmarkTest {

    private fun run(vararg args: String): Pair<Int, String> {
        val proc = ProcessBuilder(listOf("timeout", "-s", "KILL", "600") + args.toList()).redirectErrorStream(true).start()
        val out = proc.inputStream.readBytes().decodeToString()
        return proc.waitFor() to out
    }

    private class Point(val quality: Int, val bytes: Int, val score: Double)

    /** ImageKodec's size at [score] by its [points], straight between the two bracketing it; null outside them. */
    private fun sizeAt(points: List<Point>, score: Double): Double? {
        val sorted = points.sortedBy { it.score }
        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            if (score >= a.score && score <= b.score && b.score > a.score) {
                val t = (score - a.score) / (b.score - a.score)
                return a.bytes + t * (b.bytes - a.bytes)
            }
        }
        return null
    }

    /** The quality whose score is [score], straight between the qualities that bracket it. */
    private fun qualityAt(points: List<Point>, score: Double): Double? {
        val sorted = points.sortedBy { it.quality }
        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            if ((score - a.score) * (score - b.score) <= 0 && b.score != a.score) {
                return a.quality + (score - a.score) / (b.score - a.score) * (b.quality - a.quality)
            }
        }
        return null
    }

    @Test
    fun againstAvifenc() {
        assumeTrue("set IMAGEKODEC_AV1_BENCHMARK=1 to run", System.getenv("IMAGEKODEC_AV1_BENCHMARK") == "1")
        assumeTrue("avifenc is needed", Tools.hasAll("avifenc", "timeout"))
        val efforts = (System.getenv("IMAGEKODEC_AV1_BENCHMARK_EFFORTS") ?: "2").split(',').map { it.trim().toInt() }
        val dir = File(System.getProperty("java.io.tmpdir"), "kite-av1-bench").apply { mkdirs() }
        val scenes = PhotoScenes.all()
        val cpu = ManagementFactory.getThreadMXBean()
        // Warm-up, so the JIT has compiled the encoder before the clock runs.
        for (effort in efforts) repeat(2) { ImageKodec.encodeAvif(scenes[0].bitmap, HeifEncodeOptions(quality = 50, effort = effort)) }
        val rows = ArrayList<String>()
        rows += "| Scene | avifenc | Its size | Its score | Effort | ImageKodec size at that score | Ratio | ImageKodec quality at that score |"
        rows += "|---|---|---|---|---|---|---|---|"
        val ratios = HashMap<String, MutableList<Double>>()
        val qualities = HashMap<String, MutableList<Double>>()
        val ourTime = HashMap<Int, Double>()
        val theirTime = HashMap<Int, Double>()
        val pngs = scenes.map { s -> File(dir, "${s.name}.png").apply { writeBytes(ImageKodec.encodePng(s.bitmap)) } }
        for ((n, scene) in scenes.withIndex()) {
            val png = pngs[n]
            val curves = efforts.associateWith { effort ->
                (10..95 step 5).map { q ->
                    val file = ImageKodec.encodeAvif(scene.bitmap, HeifEncodeOptions(quality = q, effort = effort))
                    Point(q, file.size, ImageKodec.ssimulacra2(scene.bitmap, ImageKodec.decode(file)))
                }
            }
            for (effort in efforts) {
                var best = Double.MAX_VALUE
                repeat(3) {
                    val start = cpu.currentThreadCpuTime
                    ImageKodec.encodeAvif(scene.bitmap, HeifEncodeOptions(quality = 50, effort = effort))
                    best = minOf(best, (cpu.currentThreadCpuTime - start) / 1e9)
                }
                ourTime[effort] = (ourTime[effort] ?: 0.0) + best
            }
            for (speed in listOf(6, 9)) for (q in listOf(50, 80)) {
                val out = File(dir, "${scene.name}-$speed-$q.avif")
                var best = Double.MAX_VALUE
                repeat(if (q == 50) 3 else 1) {
                    out.delete()
                    val start = System.nanoTime()
                    val (code, log) = run(Tools.require("avifenc").path, "-s", "$speed", "-q", "$q", "--yuv", "420", "-d", "8", "-j", "1", png.path, out.path)
                    best = minOf(best, (System.nanoTime() - start) / 1e9)
                    assertTrue(code == 0, "avifenc failed: $log")
                }
                if (q == 50) theirTime[speed] = (theirTime[speed] ?: 0.0) + best
                val bytes = out.readBytes()
                val score = ImageKodec.ssimulacra2(scene.bitmap, ImageKodec.decode(bytes))
                for (effort in efforts) {
                    val points = curves[effort]!!
                    val ours = sizeAt(points, score)
                    val quality = qualityAt(points, score)
                    val ratio = ours?.let { it / bytes.size }
                    if (ratio != null) ratios.getOrPut("$effort s$speed q$q") { ArrayList() } += ratio
                    if (quality != null) qualities.getOrPut("$effort q$q") { ArrayList() } += quality
                    rows += "| ${scene.name} | -s $speed -q $q | ${bytes.size} | ${"%.2f".format(score)} | $effort | " +
                        "${ours?.let { "%.0f".format(it) } ?: "outside"} | ${ratio?.let { "%.3f".format(it) } ?: "-"} | " +
                        "${quality?.let { "%.1f".format(it) } ?: "-"} |"
                    println(rows.last())
                }
            }
        }
        rows += ""
        rows += "| Effort and target | Mean size ratio (ImageKodec / avifenc) at equal score |"
        rows += "|---|---|"
        for ((k, v) in ratios.toSortedMap()) rows += "| $k | ${"%.3f".format(v.average())} |"
        rows += ""
        rows += "| Effort and avifenc quality | Mean ImageKodec quality at its score |"
        rows += "|---|---|"
        for ((k, v) in qualities.toSortedMap()) rows += "| $k | ${"%.1f".format(v.average())} |"
        rows += ""
        rows += "| Encoder | Time over the six scenes at quality 50 (s) | Times avifenc -s 6 |"
        rows += "|---|---|---|"
        val s6 = theirTime[6]!!
        for ((speed, t) in theirTime.toSortedMap()) rows += "| avifenc -s $speed | ${"%.3f".format(t)} | ${"%.2f".format(t / s6)} |"
        for ((effort, t) in ourTime.toSortedMap()) rows += "| ImageKodec effort $effort | ${"%.3f".format(t)} | ${"%.2f".format(t / s6)} |"
        val table = rows.joinToString("\n")
        println(table)
        File("build").mkdirs()
        File("build/av1-encode-benchmark.md").writeText(table + "\n")
        ratios["2 s6 q50"]?.let { assertTrue(it.average() < RATIO_BOUND, "effort 2 needs ${it.average()} times avifenc -s 6 -q 50's size") }
        ourTime[2]?.let { assertTrue(it / s6 < TIME_BOUND, "effort 2 takes ${it / s6} times avifenc -s 6's time") }
    }

    companion object {
        /** The regression bound on the mean size ratio at effort 2 against avifenc -s 6 -q 50, which measured 1.17. */
        const val RATIO_BOUND = 1.21

        /**
         * The regression bound on the time of effort 2 against avifenc -s 6, loose for a machine doing other work: it
         * measured 4.7 (#176), 5.4 and 6.1 before the second round of speed work, and 10.8 before the first.
         */
        const val TIME_BOUND = 7.0
    }
}
