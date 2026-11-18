package io.github.yuroyami.imagekodec.heic

import io.github.yuroyami.imagekodec.HeifCodedPicture
import io.github.yuroyami.imagekodec.HeifEncodeOptions
import io.github.yuroyami.imagekodec.HeifEncodeParameters
import io.github.yuroyami.imagekodec.HeifEncoder
import io.github.yuroyami.imagekodec.HeifPlanes
import io.github.yuroyami.imagekodec.ImageKodec
import io.github.yuroyami.imagekodec.Tools
import java.io.File
import org.junit.Assume.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What `quality` means for `encodeHeic` (#176): the mean SSIMULACRA 2 score over the photo scenes of [PhotoScenes]
 * at each quality, against libheif's `heif-enc -q` (x265) at the same number, so that equal quality gives about
 * equal scores in both, as `encodeAvif`'s quality already follows `avifenc -q`. It prints heif-enc's score and size
 * at every tenth quality, the HEVC encoder's at every second QP, the QP whose score matches each of heif-enc's (the
 * points [HevcEncoderSettings.qpOf] was fitted to), and then `encodeHeic` at each quality beside heif-enc. The table
 * goes to `build/heic-quality.md`.
 *
 * It takes minutes, so it runs only with `IMAGEKODEC_HEIC_ENCODE_BENCHMARK=1`. The bound is loose, to catch a mapping
 * that drifts: from quality 20 to 90 the mean scores stay within 3 of heif-enc's.
 */
class HeicQualityTest {

    init {
        ImageKodecHeic.install()
    }

    @AfterTest
    fun restore() = ImageKodecHeic.install()

    private fun run(vararg args: String): Pair<Int, String> {
        val proc = ProcessBuilder(listOf("timeout", "-s", "KILL", "600") + args.toList()).redirectErrorStream(true).start()
        val out = proc.inputStream.readBytes().decodeToString()
        return proc.waitFor() to out
    }

    /** The HEVC encoder at a fixed [qp], as the `hvc1` encoder. */
    private fun atQp(qp: Int) = ImageKodec.registerHeifEncoder(object : HeifEncoder {
        override val itemType = HevcHeifEncoder.itemType
        override val configurationType = HevcHeifEncoder.configurationType
        override val alphaUrn = HevcHeifEncoder.alphaUrn
        override fun encode(planes: HeifPlanes, parameters: HeifEncodeParameters, cancellationCheck: (() -> Unit)?): HeifCodedPicture =
            HevcHeifEncoder.encode(planes, parameters, HevcEncoderSettings(lossless = parameters.lossless, effort = parameters.effort, qp = qp), cancellationCheck)
    })

    private class Point(val x: Double, val bytes: Long, val score: Double)

    /** The x at which the points' score is [score], straight between the two that bracket it, or null. */
    private fun at(points: List<Point>, score: Double): Double? {
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            if ((score - a.score) * (score - b.score) <= 0 && a.score != b.score) return a.x + (score - a.score) / (b.score - a.score) * (b.x - a.x)
        }
        return null
    }

    @Test
    fun qualityFollowsHeifEnc() {
        assumeTrue("set IMAGEKODEC_HEIC_ENCODE_BENCHMARK=1 to run", System.getenv("IMAGEKODEC_HEIC_ENCODE_BENCHMARK") == "1")
        assumeTrue("heif-enc is needed", Tools.hasAll("heif-enc", "timeout"))
        val scenes = PhotoScenes.all()
        val pixels = scenes.sumOf { it.bitmap.width.toLong() * it.bitmap.height }
        val dir = File(System.getProperty("java.io.tmpdir"), "kite-heic-quality").apply { mkdirs() }
        val pngs = scenes.map { s -> File(dir, "${s.name}.png").apply { writeBytes(ImageKodec.encodePng(s.bitmap)) } }
        val rows = ArrayList<String>()
        fun mean(files: List<ByteArray>): Pair<Long, Double> =
            files.sumOf { it.size.toLong() } to scenes.indices.sumOf { ImageKodec.ssimulacra2(scenes[it].bitmap, ImageKodec.decode(files[it])) } / scenes.size
        // heif-enc at every tenth quality.
        val theirs = (0..100 step 10).map { q ->
            val files = pngs.map { png ->
                val out = File(dir, "${png.nameWithoutExtension}-$q.heic").apply { delete() }
                val (code, log) = run(Tools.require("heif-enc").path, "-q", "$q", "-o", out.path, png.path)
                assertTrue(code == 0, "heif-enc failed: $log")
                out.readBytes()
            }
            val (bytes, score) = mean(files)
            Point(q.toDouble(), bytes, score)
        }
        // The HEVC encoder at every second QP, from 51 down.
        val ours = (51 downTo 1 step 2).map { qp ->
            atQp(qp)
            val (bytes, score) = mean(scenes.map { ImageKodec.encodeHeic(it.bitmap, HeifEncodeOptions(quality = 50)) })
            Point(qp.toDouble(), bytes, score)
        }
        ImageKodecHeic.install()
        rows += "| heif-enc -q | Its bits a pixel | Its mean score | ImageKodec QP at that score |"
        rows += "|---|---|---|---|"
        for (p in theirs) {
            rows += "| ${p.x.toInt()} | ${"%.3f".format(p.bytes * 8.0 / pixels)} | ${"%.2f".format(p.score)} | ${at(ours, p.score)?.let { "%.1f".format(it) } ?: "outside"} |"
        }
        rows += ""
        rows += "| ImageKodec QP | Bits a pixel | Mean score |"
        rows += "|---|---|---|"
        for (p in ours) rows += "| ${p.x.toInt()} | ${"%.3f".format(p.bytes * 8.0 / pixels)} | ${"%.2f".format(p.score)} |"
        rows += ""
        rows += "| Quality | encodeHeic bits a pixel | encodeHeic mean score | heif-enc bits a pixel | heif-enc mean score |"
        rows += "|---|---|---|---|---|"
        val drift = ArrayList<Double>()
        for (p in theirs) {
            val q = p.x.toInt()
            val (bytes, score) = mean(scenes.map { ImageKodec.encodeHeic(it.bitmap, HeifEncodeOptions(quality = q)) })
            rows += "| $q | ${"%.3f".format(bytes * 8.0 / pixels)} | ${"%.2f".format(score)} | ${"%.3f".format(p.bytes * 8.0 / pixels)} | ${"%.2f".format(p.score)} |"
            if (q in 20..90) drift += kotlin.math.abs(score - p.score)
        }
        val table = rows.joinToString("\n")
        println(table)
        File("build").mkdirs()
        File("build/heic-quality.md").writeText(table + "\n")
        assertTrue(drift.max() < 3.0, "encodeHeic's mean score drifts ${drift.max()} from heif-enc's at the same quality")
    }
}
