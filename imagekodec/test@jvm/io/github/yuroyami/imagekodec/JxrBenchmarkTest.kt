package io.github.yuroyami.imagekodec

import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The lossy JPEG XR encoder (#181) against jxrlib's `JxrEncApp` on the photo scenes: the file
 * size at equal SSIMULACRA 2, and the encode times, printed as a table and written to
 * `build/jxr-benchmark.md`. For each `JxrEncApp -q` setting, the size ImageKodec needs for the
 * same score is read off its own curve of qualities. The bound catches a regression, not noise.
 * Skips without `JxrEncApp`.
 */
class JxrBenchmarkTest {

    private fun run(vararg args: String): Int {
        val proc = ProcessBuilder(args.toList()).redirectErrorStream(true).start()
        proc.inputStream.readBytes()
        if (!proc.waitFor(300, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return -1
        }
        return proc.exitValue()
    }

    private fun pnm(b: KiteBitmap): ByteArray {
        val head = "P6\n${b.width} ${b.height}\n255\n".encodeToByteArray()
        val body = ByteArray(b.width * b.height * 3)
        for (i in b.argb.indices) {
            val p = b.argb[i]
            body[3 * i] = (p shr 16).toByte()
            body[3 * i + 1] = (p shr 8).toByte()
            body[3 * i + 2] = p.toByte()
        }
        return head + body
    }

    private class Point(val quality: Int, val bytes: Int, val score: Double, val millis: Double)

    /** The size on [curve] (ImageKodec's points, by quality) where the score is [score], log-linear between its two neighbours. */
    private fun sizeAt(curve: List<Point>, score: Double): Double? {
        val sorted = curve.sortedBy { it.score }
        for (i in 1 until sorted.size) {
            val a = sorted[i - 1]
            val b = sorted[i]
            if (score >= a.score && score <= b.score) {
                val t = if (b.score == a.score) 0.0 else (score - a.score) / (b.score - a.score)
                return kotlin.math.exp(ln(a.bytes.toDouble()) * (1 - t) + ln(b.bytes.toDouble()) * t)
            }
        }
        return null
    }

    @Test
    fun sizeAtEqualScoreAgainstJxrEncApp() {
        assumeTrue(Tools.hasAll("JxrEncApp"))
        val enc = Tools.require("JxrEncApp").path
        val dir = kotlin.io.path.createTempDirectory("jxr-bench").toFile()
        val qualities = listOf(30, 50, 70, 80, 90)
        val report = StringBuilder("| scene | -q | JxrEncApp bytes | SSIMULACRA 2 | ImageKodec bytes at that score | ratio | JxrEncApp ms | ImageKodec ms |\n|---|---|---|---|---|---|---|---|\n")
        var theirsTotal = 0.0
        var oursTotal = 0.0
        // The mean score of each encoder at each quality, which shows what the quality scale means.
        val oursAt = DoubleArray(11)
        val theirsAt = DoubleArray(11)
        val scenes = PhotoScenes.all()
        try {
            for (scene in scenes) {
                val src = File(dir, "${scene.name}.pnm").apply { writeBytes(pnm(scene.bitmap)) }
                ImageKodec.encodeJxr(scene.bitmap, 50) // warm up
                val ours = (0..100 step 5).map { q ->
                    val start = System.nanoTime()
                    val bytes = ImageKodec.encodeJxr(scene.bitmap, q)
                    val ms = (System.nanoTime() - start) / 1e6
                    Point(q, bytes.size, ImageKodec.ssimulacra2(scene.bitmap, ImageKodec.decode(bytes)), ms)
                }
                for (p in ours) if (p.quality % 10 == 0) oursAt[p.quality / 10] += p.score / scenes.size
                for (q in (0..100 step 10)) {
                    val out = File(dir, "${scene.name}-$q.jxr")
                    val start = System.nanoTime()
                    val code = run(enc, "-i", src.path, "-o", out.path, "-c", "9", "-q", "%.2f".format(q / 100.0))
                    val ms = (System.nanoTime() - start) / 1e6
                    assertTrue(code == 0, "JxrEncApp failed on ${scene.name} at $q")
                    val bytes = out.readBytes()
                    val score = ImageKodec.ssimulacra2(scene.bitmap, ImageKodec.decode(bytes))
                    theirsAt[q / 10] += score / scenes.size
                    if (q !in qualities) continue
                    val at = sizeAt(ours, score)
                    val mine = ours.first { it.quality == q }
                    val line = "| ${scene.name} | ${q / 100.0} | ${bytes.size} | ${"%.2f".format(score)} | ${at?.let { "%.0f".format(it) } ?: "out of range"} | " +
                        "${at?.let { "%.3f".format(it / bytes.size) } ?: "-"} | ${"%.0f".format(ms)} | ${"%.0f".format(mine.millis)} |"
                    report.append(line).append('\n')
                    if (at != null) {
                        theirsTotal += bytes.size
                        oursTotal += at
                    }
                }
            }
            val ratio = oursTotal / theirsTotal
            report.append("\nTotal at equal score: ImageKodec ${"%.0f".format(oursTotal)} bytes, JxrEncApp ${"%.0f".format(theirsTotal)} bytes, ratio ${"%.3f".format(ratio)}\n")
            report.append("\n| quality | JxrEncApp mean SSIMULACRA 2 | ImageKodec mean SSIMULACRA 2 |\n|---|---|---|\n")
            for (i in 0 until 10) report.append("| ${i * 10} | ${"%.1f".format(theirsAt[i])} | ${"%.1f".format(oursAt[i])} |\n")
            println(report)
            File("build").mkdirs()
            File("build/jxr-benchmark.md").writeText("# JPEG XR encode against JxrEncApp\n\n$report")
            // A regression bound, loose enough for another machine's timing but not for a coding change.
            assertTrue(ratio < 0.97, "ImageKodec needs ${"%.3f".format(ratio)} times JxrEncApp's bytes at equal SSIMULACRA 2")
        } finally {
            dir.deleteRecursively()
        }
    }
}
