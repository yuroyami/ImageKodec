@file:OptIn(ExperimentalForeignApi::class, NativeRuntimeApi::class)

package io.github.yuroyami.imagekodec

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.ByteVar
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.mkdtemp
import platform.posix.rewind
import platform.posix.system
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.TimeSource

/**
 * The JVM's `JxlBenchmarkTest` on Kotlin/Native (#111): the same two files, which cjxl writes
 * at test time, decoded by djxl and by ImageKodec. The JVM reads its peak heap from the memory
 * pools; here the peak is the growth of the process's resident memory during one decode, which
 * Linux keeps as VmHWM and resets on a write of 5 to `/proc/self/clear_refs`. The Kotlin/Native
 * allocator maps each large array on its own and unmaps it once the collector frees it, so a
 * collection before the decode leaves little for the decode to reuse unseen. Without cjxl and
 * djxl it prints that it skipped, and fails under `IMAGEKODEC_REQUIRE_ORACLES=1`.
 */
class JxlNativeBenchmarkTest {

    private fun shell(command: String): Int = system(command).let { (it shr 8) and 0xFF }

    private fun writeFile(path: String, bytes: ByteArray) {
        val file = fopen(path, "wb") ?: fail("cannot write $path")
        try {
            bytes.usePinned { assertEquals(bytes.size.toULong(), fwrite(it.addressOf(0), 1u, bytes.size.convert(), file)) }
        } finally {
            fclose(file)
        }
    }

    private fun readFile(path: String): ByteArray {
        val file = fopen(path, "rb") ?: fail("cannot read $path")
        try {
            fseek(file, 0, SEEK_END)
            val size = ftell(file).toInt()
            rewind(file)
            val bytes = ByteArray(size)
            bytes.usePinned { assertEquals(size.toULong(), fread(it.addressOf(0), 1u, size.convert(), file)) }
            return bytes
        } finally {
            fclose(file)
        }
    }

    /** A field of `/proc/self/status` in kB, such as VmRSS or VmHWM. */
    private fun statusKb(field: String): Long {
        val file = fopen("/proc/self/status", "r") ?: fail("cannot read /proc/self/status")
        try {
            memScoped {
                val line = allocArray<ByteVar>(256)
                while (fgets(line, 256, file) != null) {
                    val text = line.toKString()
                    if (text.startsWith("$field:")) return text.substringAfter(':').trim().substringBefore(' ').toLong()
                }
            }
        } finally {
            fclose(file)
        }
        fail("/proc/self/status has no $field")
    }

    private fun resetPeak() {
        val file = fopen("/proc/self/clear_refs", "w") ?: fail("cannot reset the peak resident memory")
        fputs("5", file)
        fclose(file)
    }

    private class Measure(val millis: Long, val peakMb: Long)

    private fun measure(block: () -> Unit): Measure {
        GC.collect()
        resetPeak()
        val base = statusKb("VmRSS")
        val start = TimeSource.Monotonic.markNow()
        block()
        val millis = start.elapsedNow().inWholeMilliseconds
        return Measure(millis, (statusKb("VmHWM") - base) shr 10)
    }

    @Test
    fun severalMegapixelsDecodeWithinBounds() {
        if (shell("command -v cjxl >/dev/null 2>&1 && command -v djxl >/dev/null 2>&1") != 0) {
            if (environmentVariable("IMAGEKODEC_REQUIRE_ORACLES") == "1") fail("cjxl or djxl is not installed")
            println("[jxl-benchmark] skipped: cjxl or djxl is not installed")
            return
        }
        val dir = memScoped { mkdtemp("/tmp/jxl-bench-XXXXXX".cstr.getPointer(this))?.toKString() } ?: fail("cannot make a directory")
        try {
            val width = JxlBenchmarkPicture.WIDTH
            val height = JxlBenchmarkPicture.HEIGHT
            writeFile("$dir/source.png", ImageKodec.encodePng(JxlBenchmarkPicture.picture()))
            val report = StringBuilder("# JPEG XL decode on linuxX64, ${width}x$height\n\n| file | bytes | djxl ms | ImageKodec ms | peak resident MB |\n|---|---|---|---|---|\n")
            for ((name, options) in JxlBenchmarkPicture.cases) {
                val jxl = "$dir/${name.substringBefore(',')}.jxl"
                assertEquals(0, shell("cjxl $dir/source.png $jxl ${options.joinToString(" ")} >/dev/null 2>&1"), "cjxl failed for $name")
                val bytes = readFile(jxl)
                val djxlStart = TimeSource.Monotonic.markNow()
                assertEquals(0, shell("djxl $jxl $dir/out.png >/dev/null 2>&1"), "djxl failed for $name")
                val djxlMs = djxlStart.elapsedNow().inWholeMilliseconds
                // The first decode gives the peak, before a later one can reuse what it left mapped; the second gives the time.
                val decode = {
                    val decoded = ImageKodec.decode(bytes)
                    assertEquals(width to height, decoded.width to decoded.height)
                }
                val first = measure(decode)
                val second = measure(decode)
                val line = "| $name | ${bytes.size} | $djxlMs | ${second.millis} | ${first.peakMb} |"
                println("[jxl-benchmark] $line")
                report.append(line).append('\n')
                // The bounds of the JVM test: an order of magnitude past the normal reach.
                assertTrue(first.peakMb < 1_500, "$name: a peak of ${first.peakMb} MB")
                assertTrue(second.millis < 120_000, "$name: ${second.millis} ms")
            }
            shell("mkdir -p build")
            fopen("build/jxl-benchmark-linuxX64.md", "w")?.let {
                fputs(report.toString(), it)
                fclose(it)
            }
        } finally {
            shell("rm -rf $dir")
        }
    }
}
