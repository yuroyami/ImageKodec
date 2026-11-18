package io.github.yuroyami.imagekodec.heic

import io.github.yuroyami.imagekodec.ImageKodec
import io.github.yuroyami.imagekodec.environmentVariable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.TimeSource

/**
 * The JVM's `HeicBenchmarkTest` on Kotlin/JS under Node (#169): the same two 12-megapixel pictures,
 * which x265 writes at test time through Node's `child_process`, decoded by ImageKodec, the fastest
 * of three after one to warm up, beside `dec265` on one thread. Node gives no peak heap, so this
 * reports time alone. In a browser, or without ffmpeg's libx265, it prints that it skipped, and
 * fails under `IMAGEKODEC_REQUIRE_ORACLES=1`.
 */
class HeicWebBenchmarkTest {

    private fun node(): dynamic = js("typeof process !== 'undefined' && process.getBuiltinModule ? process : null")

    private fun shell(command: String): Pair<Int, String> {
        val result = node().getBuiltinModule("child_process").spawnSync("sh", arrayOf("-c", command), js("({ encoding: 'utf8', maxBuffer: 1e8 })"))
        return ((result.status as? Int) ?: -1) to ((result.stdout as? String) ?: "")
    }

    private fun readFile(path: String): ByteArray {
        val buffer = node().getBuiltinModule("fs").readFileSync(path)
        // A Kotlin/JS ByteArray is an Int8Array, so the file's bytes need no copy.
        val view: dynamic = js("(function (b) { return new Int8Array(b.buffer, b.byteOffset, b.length); })")
        return view(buffer).unsafeCast<ByteArray>()
    }

    @Test
    fun twelveMegapixelsDecodeWithinBounds() {
        if (node() == null || shell("command -v ffmpeg >/dev/null 2>&1 && ffmpeg -hide_banner -encoders 2>/dev/null | grep -q libx265").first != 0) {
            if (environmentVariable("IMAGEKODEC_REQUIRE_ORACLES") == "1") fail("Node or ffmpeg with libx265 is not available")
            println("[heic-benchmark] skipped: Node or ffmpeg with libx265 is not available")
            return
        }
        ImageKodecHeic.install()
        val dir = shell("mktemp -d /tmp/heic-bench-XXXXXX").second.trim()
        if (!dir.startsWith("/tmp/heic-bench-")) fail("cannot make a directory: $dir")
        try {
            println("[heic-benchmark] | picture | bytes | dec265 ms | ImageKodec ms on JS |")
            for (case in HeicBenchmark.cases(dir)) {
                assertEquals(0, shell(case.command).first, "ffmpeg failed for ${case.name}")
                val file = case.file(readFile(case.stream))
                val dec265 = shell(HeicBenchmark.dec265Command(case.stream)).second.trim().toLongOrNull()
                var best = Long.MAX_VALUE
                repeat(WARM_UP + RUNS) { run ->
                    val start = TimeSource.Monotonic.markNow()
                    val decoded = ImageKodec.decode(file)
                    if (run >= WARM_UP) best = minOf(best, start.elapsedNow().inWholeMilliseconds)
                    assertEquals(case.width to case.height, decoded.width to decoded.height)
                }
                println("[heic-benchmark] | ${case.name} | ${file.size} | ${dec265 ?: "-"} | $best |")
                assertTrue(best < MAX_MILLIS, "${case.name}: $best ms")
            }
        } finally {
            shell("rm -rf $dir")
        }
    }

    private companion object {
        const val WARM_UP = 1
        const val RUNS = 3

        /** Kotlin/JS does 64-bit arithmetic in software and is slower than the JVM; the bound is wider by as much. */
        const val MAX_MILLIS = 120_000L
    }
}
