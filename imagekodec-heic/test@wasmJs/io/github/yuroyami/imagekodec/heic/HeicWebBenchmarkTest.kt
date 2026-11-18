@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.imagekodec.heic

import io.github.yuroyami.imagekodec.ImageKodec
import io.github.yuroyami.imagekodec.environmentVariable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.TimeSource

private fun hasNode(): Boolean = js("typeof process !== 'undefined' && typeof process.getBuiltinModule === 'function'")

private fun spawn(command: String): JsAny =
    js("process.getBuiltinModule('child_process').spawnSync('sh', ['-c', command], { encoding: 'utf8', maxBuffer: 1e8 })")

private fun exitStatus(result: JsAny): Int = js("result.status === null ? -1 : result.status")

private fun standardOutput(result: JsAny): String = js("String(result.stdout)")

private fun readBuffer(path: String): JsAny = js("process.getBuiltinModule('fs').readFileSync(path)")

private fun bufferLength(buffer: JsAny): Int = js("buffer.length")

/** Four bytes of [buffer] from [at], high byte first, past its end as zeros: one call for four bytes. */
private fun bufferWord(buffer: JsAny, at: Int): Int =
    js("((buffer[at] << 24) | ((buffer[at + 1] || 0) << 16) | ((buffer[at + 2] || 0) << 8) | (buffer[at + 3] || 0))")

/**
 * The JVM's `HeicBenchmarkTest` on Kotlin/Wasm under Node (#169): the same two 12-megapixel
 * pictures, which x265 writes at test time through Node's `child_process`, decoded by ImageKodec,
 * the fastest of three after one to warm up, beside `dec265` on one thread. Node gives no peak heap,
 * so this reports time alone. In a browser, or without ffmpeg's libx265, it prints that it skipped,
 * and fails under `IMAGEKODEC_REQUIRE_ORACLES=1`.
 */
class HeicWebBenchmarkTest {

    private fun shell(command: String): Pair<Int, String> {
        val result = spawn(command)
        return exitStatus(result) to standardOutput(result)
    }

    private fun readFile(path: String): ByteArray {
        val buffer = readBuffer(path)
        val n = bufferLength(buffer)
        val out = ByteArray(n)
        var at = 0
        while (at < n) {
            val w = bufferWord(buffer, at)
            for (k in 0 until minOf(4, n - at)) out[at + k] = (w ushr (24 - 8 * k)).toByte()
            at += 4
        }
        return out
    }

    @Test
    fun twelveMegapixelsDecodeWithinBounds() {
        if (!hasNode() || shell("command -v ffmpeg >/dev/null 2>&1 && ffmpeg -hide_banner -encoders 2>/dev/null | grep -q libx265").first != 0) {
            if (environmentVariable("IMAGEKODEC_REQUIRE_ORACLES") == "1") fail("Node or ffmpeg with libx265 is not available")
            println("[heic-benchmark] skipped: Node or ffmpeg with libx265 is not available")
            return
        }
        ImageKodecHeic.install()
        val dir = shell("mktemp -d /tmp/heic-bench-XXXXXX").second.trim()
        if (!dir.startsWith("/tmp/heic-bench-")) fail("cannot make a directory: $dir")
        try {
            println("[heic-benchmark] | picture | bytes | dec265 ms | ImageKodec ms on wasmJs |")
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

        /** The bound of the JS run: a debug Wasm build is slower than the JVM. */
        const val MAX_MILLIS = 120_000L
    }
}
