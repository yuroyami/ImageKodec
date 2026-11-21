package io.github.yuroyami.imagekodec.heic

import io.github.yuroyami.imagekodec.ImageKodec
import io.github.yuroyami.imagekodec.TileRunner
import io.github.yuroyami.imagekodec.Tools
import org.junit.Assume.assumeTrue
import java.io.File
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How fast the HEIC decoder is and how much it holds (#169), on the 12-megapixel pictures of
 * [HeicBenchmark], which x265 writes at test time: a grid of 48 tiles at 8 bits and one picture
 * with wavefront rows at 10 bits. Each decode reports the fastest of [HeicBenchmark.RUNS] after
 * warm-up, its peak heap, and libde265's `dec265` on one thread over the same stream. The grid also
 * decodes with a tile runner on a pool of four threads (#152), which must give the same pixels. The report
 * goes to the test output and to `build/heic-benchmark.md`. Skips without ffmpeg's libx265.
 */
class HeicBenchmarkTest {

    init {
        ImageKodecHeic.install()
    }

    private fun shell(command: String): Pair<Int, String> {
        val proc = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val out = proc.inputStream.readBytes().decodeToString()
        if (!proc.waitFor(900, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return -1 to out
        }
        return proc.exitValue() to out
    }

    private class Measure(val millis: Long, val peakHeapMb: Long)

    /** The fastest of [HeicBenchmark.RUNS] runs of [block], after warm-up, and the peak heap of the first measured one. */
    private fun measure(block: () -> Unit): Measure {
        repeat(HeicBenchmark.WARM_UP) { block() }
        val pools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }
        System.gc()
        val base = pools.sumOf { it.usage.used }
        pools.forEach { it.resetPeakUsage() }
        var best = Long.MAX_VALUE
        var peak = 0L
        repeat(HeicBenchmark.RUNS) { run ->
            val start = System.nanoTime()
            block()
            best = minOf(best, (System.nanoTime() - start) / 1_000_000)
            if (run == 0) peak = pools.sumOf { it.peakUsage.used } - base
        }
        return Measure(best, peak shr 20)
    }

    @Test
    fun twelveMegapixelsDecodeWithinBounds() {
        assumeTrue("ffmpeg or timeout is not installed", Tools.hasAll("ffmpeg", "timeout"))
        if ("libx265" !in shell("ffmpeg -hide_banner -encoders").second) {
            if (System.getenv(Tools.REQUIRE_ENV) == "1") error("ffmpeg lacks libx265, and ${Tools.REQUIRE_ENV}=1 forbids skipping")
            assumeTrue("ffmpeg lacks libx265", false)
        }
        val hasDec265 = Tools.hasAll("dec265")
        val dir = kotlin.io.path.createTempDirectory("heic-bench").toFile()
        val pool = Executors.newFixedThreadPool(4)
        val fourThreads = TileRunner { jobs -> jobs.map { pool.submit(it::run) }.forEach { it.get() } }
        try {
            val report = StringBuilder(
                "# HEIC decode on the JVM\n\n| picture | bytes | dec265 ms | ImageKodec ms | peak heap MB | 4 threads ms | 4 threads peak heap MB |\n|---|---|---|---|---|---|---|\n",
            )
            for (case in HeicBenchmark.cases(dir.path)) {
                val (code, log) = shell(case.command)
                assertEquals(0, code, "ffmpeg failed for ${case.name}: $log")
                val file = case.file(File(case.stream).readBytes())
                val dec265 = if (hasDec265) shell(HeicBenchmark.dec265Command(case.stream)).second.trim().toLongOrNull() else null
                val serial = ImageKodec.decode(file)
                assertEquals(case.width to case.height, serial.width to serial.height)
                val m = measure { ImageKodec.decode(file) }
                val tiled = case.name.contains("grid")
                val threaded = if (!tiled) null else try {
                    ImageKodec.setTileRunner(fourThreads)
                    assertContentEquals(serial.argb, ImageKodec.decode(file).argb, "${case.name} on four threads")
                    measure { ImageKodec.decode(file) }
                } finally {
                    ImageKodec.setTileRunner(TileRunner.Serial)
                }
                val line = "| ${case.name} | ${file.size} | ${dec265 ?: "-"} | ${m.millis} | ${m.peakHeapMb} | ${threaded?.millis ?: "-"} | ${threaded?.peakHeapMb ?: "-"} |"
                println("[heic-benchmark] $line")
                report.append(line).append('\n')
                assertTrue(m.peakHeapMb < HeicBenchmark.MAX_PEAK_MB, "${case.name}: a peak heap of ${m.peakHeapMb} MB")
                assertTrue(m.millis < HeicBenchmark.MAX_MILLIS, "${case.name}: ${m.millis} ms")
            }
            File("build").mkdirs()
            File("build/heic-benchmark.md").writeText(report.toString())
        } finally {
            pool.shutdownNow()
            dir.deleteRecursively()
        }
    }
}
