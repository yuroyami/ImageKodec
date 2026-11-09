package io.github.yuroyami.imagekodec

import org.junit.Assume.assumeTrue
import java.io.File
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How fast the JPEG XL decoder is and how much it holds (#111), on files of several megapixels
 * that libjxl's cjxl writes at test time: a lossy VarDCT file and a lossless Modular one. Each
 * decode reports its time next to djxl's, its peak heap and the bytes it allocated, in the test
 * output and in `build/jxl-benchmark.md`. The bounds are wide: they catch a decoder that grows
 * by an order of magnitude, not a slow run on a loaded machine. Skips without cjxl and djxl.
 */
class JxlBenchmarkTest {

    private val width = JxlBenchmarkPicture.WIDTH
    private val height = JxlBenchmarkPicture.HEIGHT

    private fun run(vararg args: String): Int {
        val proc = ProcessBuilder(args.toList()).redirectErrorStream(true).start()
        proc.inputStream.readBytes()
        if (!proc.waitFor(600, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return -1
        }
        return proc.exitValue()
    }

    private class Measure(val millis: Long, val peakHeapMb: Long, val allocatedMb: Long)

    private fun measure(block: () -> Unit): Measure {
        val pools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }
        System.gc()
        val base = pools.sumOf { it.usage.used }
        pools.forEach { it.resetPeakUsage() }
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        val allocatedBefore = bean.getThreadAllocatedBytes(id)
        val start = System.nanoTime()
        block()
        val millis = (System.nanoTime() - start) / 1_000_000
        val allocated = bean.getThreadAllocatedBytes(id) - allocatedBefore
        val peak = pools.sumOf { it.peakUsage.used } - base
        return Measure(millis, peak shr 20, allocated shr 20)
    }

    @Test
    fun several_megapixels_decode_within_bounds() {
        assumeTrue("cjxl or djxl is not installed", Tools.hasAll("cjxl", "djxl"))
        val cjxl = Tools.require("cjxl").path
        val djxl = Tools.require("djxl").path
        val dir = kotlin.io.path.createTempDirectory("jxl-bench").toFile()
        try {
            val png = File(dir, "source.png").apply { writeBytes(ImageKodec.encodePng(JxlBenchmarkPicture.picture())) }
            val report = StringBuilder("# JPEG XL decode, ${width}x$height\n\n| file | bytes | djxl ms | ImageKodec ms | peak heap MB | allocated MB |\n|---|---|---|---|---|---|\n")
            for ((name, options) in JxlBenchmarkPicture.cases) {
                val jxl = File(dir, "${name.substringBefore(',')}.jxl")
                assertEquals(0, run(cjxl, png.path, jxl.path, *options.toTypedArray()), "cjxl failed for $name")
                val bytes = jxl.readBytes()
                val djxlStart = System.nanoTime()
                assertEquals(0, run(djxl, jxl.path, File(dir, "out.png").path), "djxl failed for $name")
                val djxlMs = (System.nanoTime() - djxlStart) / 1_000_000
                ImageKodec.decode(bytes) // warm up
                var decoded: KiteBitmap? = null
                val m = measure { decoded = ImageKodec.decode(bytes) }
                assertEquals(width, decoded!!.width)
                assertEquals(height, decoded!!.height)
                val line = "| $name | ${bytes.size} | $djxlMs | ${m.millis} | ${m.peakHeapMb} | ${m.allocatedMb} |"
                println("[jxl-benchmark] $line")
                report.append(line).append('\n')
                // An ARGB bitmap of 6 megapixels is 23 MB. The decoder keeps a float plane for
                // each channel and its working space, so a few hundred MB is its normal reach.
                assertTrue(m.peakHeapMb < 1_500, "$name: a peak heap of ${m.peakHeapMb} MB")
                assertTrue(m.millis < 120_000, "$name: ${m.millis} ms")
            }
            File("build").mkdirs()
            File("build/jxl-benchmark.md").writeText(report.toString())
        } finally {
            dir.deleteRecursively()
        }
    }
}
