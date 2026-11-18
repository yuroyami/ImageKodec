package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.avif.Av1EncodeSettings
import io.github.yuroyami.imagekodec.codec.avif.Av1HeifEncoder
import io.github.yuroyami.imagekodec.codec.avif.HeifCodecs
import java.io.File
import java.lang.management.ManagementFactory
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Animated AVIF (#177) against ffmpeg's libaom (`-c:v libaom-av1 -f avif`) on the panning scenes of
 * [AnimatedScenes]: the size `encodeAvif` needs for the mean SSIMULACRA 2 score over the frames that libaom reaches
 * at a CRF, read straight between ImageKodec's sizes at qualities 10 apart, and the encode times. Both write 8-bit
 * 4:2:0 on one thread; libaom at `-cpu-used 6` with its defaults otherwise, which look 19 frames ahead and code
 * hidden alternate reference frames.
 *
 * Beside effort 2 as it is, the table gives the same encoder with every frame a key frame (intra only), and
 * without compound prediction, so each tool's share shows.
 *
 * Times are CPU time of the encoding thread for ImageKodec, after a warm-up, and wall time of the ffmpeg process.
 * The table goes to the test output and to `build/avif-sequence-benchmark.md`. It takes minutes, so it runs only
 * with `IMAGEKODEC_AVIS_BENCHMARK=1`. Its bound is loose, to catch a regression: the mean size ratio of effort 2
 * against libaom at CRF 40 stays under [RATIO_BOUND].
 */
@OptIn(ExperimentalHeifCodecApi::class)
class AvifSequenceBenchmarkTest {

    private fun run(vararg args: String): Pair<Int, String> {
        val proc = ProcessBuilder(listOf("timeout", "-s", "KILL", "900") + args.toList()).redirectErrorStream(true).start()
        val out = proc.inputStream.readBytes().decodeToString()
        return proc.waitFor() to out
    }

    private class Point(val quality: Int, val bytes: Int, val score: Double)

    private fun sizeAt(points: List<Point>, score: Double): Double? {
        val sorted = points.sortedBy { it.score }
        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            if (score >= a.score && score <= b.score && b.score > a.score) return a.bytes + (score - a.score) / (b.score - a.score) * (b.bytes - a.bytes)
        }
        return null
    }

    /** The mean SSIMULACRA 2 score of [file]'s frames against [source]'s. */
    private fun score(source: KiteAnimation, file: ByteArray): Double {
        val back = ImageKodec.decodeAnimation(file)
        check(back.frames.size == source.frames.size) { "${back.frames.size} frames decoded of ${source.frames.size}" }
        return source.frames.indices.map { ImageKodec.ssimulacra2(source.frames[it].bitmap, back.frames[it].bitmap) }.average()
    }

    /** An `av01` encoder whose sequences code with [settings] changed by [change]: to turn a tool off. */
    private fun variant(change: (Av1EncodeSettings) -> Av1EncodeSettings): HeifEncoder = object : HeifEncoder by Av1HeifEncoder {
        override fun openSequence(parameters: HeifEncodeParameters, keyFrameInterval: Int): HeifSequenceEncoder {
            val q = if (parameters.lossless) 0 else Av1HeifEncoder.qIndex(parameters.quality)
            val colour = parameters.colorPrimaries?.let { intArrayOf(it, parameters.transferCharacteristics!!, parameters.matrixCoefficients!!) }
            return Av1HeifEncoder.openSequence(change(Av1EncodeSettings(q, parameters.effort, colour)), keyFrameInterval)
        }
    }

    @Test
    fun againstLibaom() {
        assumeTrue("set IMAGEKODEC_AVIS_BENCHMARK=1 to run", System.getenv("IMAGEKODEC_AVIS_BENCHMARK") == "1")
        assumeTrue("ffmpeg is needed", Tools.hasAll("ffmpeg", "timeout"))
        val ffmpeg = Tools.require("ffmpeg").path
        val dir = File(System.getProperty("java.io.tmpdir"), "kite-avis-bench").apply { mkdirs() }
        val frames = (System.getenv("IMAGEKODEC_AVIS_BENCHMARK_FRAMES") ?: "24").toInt()
        val scenes = listOf("landscape" to PhotoScenes.landscape().bitmap, "city" to PhotoScenes.city().bitmap)
            .map { (name, bitmap) -> name to AnimatedScenes.pan(bitmap, 256, 192, frames) }
        val cpu = ManagementFactory.getThreadMXBean()
        repeat(2) { ImageKodec.encodeAvif(scenes[0].second, HeifEncodeOptions(quality = 50)) }
        val variants = listOf<Pair<String, (KiteAnimation, Int) -> ByteArray>>(
            "effort 2" to { a, q -> ImageKodec.encodeAvif(a, HeifEncodeOptions(quality = q)) },
            "effort 2, no compound" to { a, q -> withEncoder(variant { Av1EncodeSettings(it.qIndex, it.effort, it.colour, compound = false) }) { ImageKodec.encodeAvif(a, HeifEncodeOptions(quality = q)) } },
            "effort 2, intra only" to { a, q -> ImageKodec.encodeAvif(a, HeifEncodeOptions(quality = q, keyFrameInterval = 1)) },
            "effort 1" to { a, q -> ImageKodec.encodeAvif(a, HeifEncodeOptions(quality = q, effort = 1)) },
            "effort 3" to { a, q -> ImageKodec.encodeAvif(a, HeifEncodeOptions(quality = q, effort = 3)) },
        ).filter { it.first in (System.getenv("IMAGEKODEC_AVIS_BENCHMARK_VARIANTS") ?: DEFAULT_VARIANTS).split(';').map(String::trim) }
        val rows = ArrayList<String>()
        rows += "| Scene | libaom | Its size | Its score | ImageKodec | Size at that score | Ratio |"
        rows += "|---|---|---|---|---|---|---|"
        val ratios = HashMap<String, MutableList<Double>>()
        val ourTime = HashMap<String, Double>()
        var theirTime = 0.0
        for ((name, anim) in scenes) {
            val frameDir = File(dir, name).apply { mkdirs() }
            for ((i, f) in anim.frames.withIndex()) File(frameDir, "f%03d.png".format(i)).writeBytes(ImageKodec.encodePng(f.bitmap))
            val curves = variants.associate { (label, encode) ->
                label to (20..90 step 10).map { q ->
                    val start = cpu.currentThreadCpuTime
                    val file = encode(anim, q)
                    if (q == 50) ourTime[label] = (ourTime[label] ?: 0.0) + (cpu.currentThreadCpuTime - start) / 1e9
                    Point(q, file.size, score(anim, file))
                }
            }
            for (crf in listOf(30, 40, 50)) {
                val out = File(dir, "$name-$crf.avif").apply { delete() }
                val start = System.nanoTime()
                val (code, log) = run(
                    ffmpeg, "-hide_banner", "-loglevel", "error", "-framerate", "25", "-i", File(frameDir, "f%03d.png").path,
                    "-vf", "scale=out_color_matrix=bt601:out_range=full,format=yuv420p", "-color_range", "pc", "-colorspace", "smpte170m",
                    "-color_primaries", "bt709", "-color_trc", "iec61966-2-1",
                    "-c:v", "libaom-av1", "-cpu-used", "6", "-crf", "$crf", "-b:v", "0", "-threads", "1", "-row-mt", "0", "-f", "avif", out.path,
                )
                if (crf == 40) theirTime += (System.nanoTime() - start) / 1e9
                assertTrue(code == 0, "ffmpeg failed: $log")
                val bytes = out.readBytes()
                val theirs = score(anim, bytes)
                for ((label, points) in curves) {
                    val ours = sizeAt(points, theirs)
                    val ratio = ours?.let { it / bytes.size }
                    if (ratio != null) ratios.getOrPut("$label crf $crf") { ArrayList() } += ratio
                    rows += "| $name | -crf $crf | ${bytes.size} | ${"%.2f".format(theirs)} | $label | ${ours?.let { "%.0f".format(it) } ?: "outside"} | " +
                        "${ratio?.let { "%.3f".format(it) } ?: "-"} |"
                    println(rows.last())
                }
            }
            for ((label, points) in curves) println("$name $label: " + points.joinToString { "q${it.quality} ${it.bytes} ${"%.1f".format(it.score)}" })
        }
        rows += ""
        rows += "| ImageKodec against libaom | Mean size ratio at equal score |"
        rows += "|---|---|"
        for ((k, v) in ratios.toSortedMap()) rows += "| $k | ${"%.3f".format(v.average())} |"
        rows += ""
        rows += "| Encoder | Time for both scenes, $frames frames each (s) |"
        rows += "|---|---|"
        rows += "| ffmpeg libaom -cpu-used 6 -crf 40 | ${"%.2f".format(theirTime)} |"
        for ((label, t) in ourTime.toSortedMap()) rows += "| ImageKodec $label, quality 50 | ${"%.2f".format(t)} |"
        val table = rows.joinToString("\n")
        println(table)
        File("build").mkdirs()
        File("build/avif-sequence-benchmark.md").writeText(table + "\n")
        ratios["effort 2 crf 40"]?.let { assertTrue(it.average() < RATIO_BOUND, "effort 2 needs ${it.average()} times libaom's size at CRF 40") }
    }

    private fun <T> withEncoder(encoder: HeifEncoder, block: () -> T): T {
        ImageKodec.registerHeifEncoder(encoder)
        try {
            return block()
        } finally {
            HeifCodecs.reset()
        }
    }

    companion object {
        /** The regression bound on the mean size ratio of effort 2 against libaom at CRF 40. */
        const val RATIO_BOUND = 1.3

        /** The variants run unless `IMAGEKODEC_AVIS_BENCHMARK_VARIANTS` names others, separated by semicolons. */
        const val DEFAULT_VARIANTS = "effort 2;effort 2, no compound;effort 2, intra only"
    }
}
