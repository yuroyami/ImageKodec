@file:OptIn(ExperimentalForeignApi::class, NativeRuntimeApi::class)

package io.github.yuroyami.imagekodec.heic

import io.github.yuroyami.imagekodec.ImageKodec
import io.github.yuroyami.imagekodec.environmentVariable
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
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
 * The JVM's `HeicBenchmarkTest` on Kotlin/Native (#169): the same two pictures, which x265 writes
 * at test time, decoded by `dec265` on one thread and by ImageKodec, one thread too, since no
 * runner is set. The peak is the growth of the process's resident memory during the first measured
 * decode, which Linux keeps as VmHWM and resets on a write of 5 to `/proc/self/clear_refs`, as
 * `JxlNativeBenchmarkTest` reads it. Without ffmpeg's libx265 it prints that it skipped, and fails
 * under `IMAGEKODEC_REQUIRE_ORACLES=1`. Run it as a release build (`-v release`); a debug build is
 * several times slower and says nothing of what an app gets.
 */
class HeicNativeBenchmarkTest {

    private fun shell(command: String): Int = system(command).let { (it shr 8) and 0xFF }

    private fun readFile(path: String): ByteArray {
        val file = fopen(path, "rb") ?: fail("cannot read $path")
        try {
            fseek(file, 0, SEEK_END)
            val size = ftell(file).toInt()
            rewind(file)
            val bytes = ByteArray(size)
            if (size > 0) bytes.usePinned { assertEquals(size.toULong(), fread(it.addressOf(0), 1u, size.convert(), file)) }
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

    /** The fastest of [HeicBenchmark.RUNS] runs after warm-up, and the peak of the first decode. */
    private fun measure(block: () -> Unit): Measure {
        GC.collect()
        resetPeak()
        val base = statusKb("VmRSS")
        block()
        val peak = (statusKb("VmHWM") - base) shr 10
        repeat(HeicBenchmark.WARM_UP - 1) { block() }
        var best = Long.MAX_VALUE
        repeat(HeicBenchmark.RUNS) {
            val start = TimeSource.Monotonic.markNow()
            block()
            best = minOf(best, start.elapsedNow().inWholeMilliseconds)
        }
        return Measure(best, peak)
    }

    @Test
    fun twelveMegapixelsDecodeWithinBounds() {
        ImageKodecHeic.install()
        if (shell("command -v ffmpeg >/dev/null 2>&1 && ffmpeg -hide_banner -encoders 2>/dev/null | grep -q libx265") != 0) {
            if (environmentVariable("IMAGEKODEC_REQUIRE_ORACLES") == "1") fail("ffmpeg with libx265 is not installed")
            println("[heic-benchmark] skipped: ffmpeg with libx265 is not installed")
            return
        }
        val dir = memScoped { mkdtemp("/tmp/heic-bench-XXXXXX".cstr.getPointer(this))?.toKString() } ?: fail("cannot make a directory")
        try {
            val report = StringBuilder("# HEIC decode on linuxX64\n\n| picture | bytes | dec265 ms | ImageKodec ms | peak resident MB |\n|---|---|---|---|---|\n")
            for (case in HeicBenchmark.cases(dir)) {
                assertEquals(0, shell("${case.command} >/dev/null 2>&1"), "ffmpeg failed for ${case.name}")
                val file = case.file(readFile(case.stream))
                val dec265 = if (shell("${HeicBenchmark.dec265Command(case.stream)} > $dir/dec265.txt 2>/dev/null") == 0) {
                    readFile("$dir/dec265.txt").decodeToString().trim().toLongOrNull()
                } else {
                    null
                }
                val m = measure {
                    val decoded = ImageKodec.decode(file)
                    assertEquals(case.width to case.height, decoded.width to decoded.height)
                }
                val line = "| ${case.name} | ${file.size} | ${dec265 ?: "-"} | ${m.millis} | ${m.peakMb} |"
                println("[heic-benchmark] $line")
                report.append(line).append('\n')
                assertTrue(m.peakMb < HeicBenchmark.MAX_PEAK_MB, "${case.name}: a peak of ${m.peakMb} MB")
                assertTrue(m.millis < HeicBenchmark.MAX_MILLIS, "${case.name}: ${m.millis} ms")
            }
            shell("mkdir -p build")
            fopen("build/heic-benchmark-linuxX64.md", "w")?.let {
                fputs(report.toString(), it)
                fclose(it)
            }
        } finally {
            shell("rm -rf $dir")
        }
    }
}
