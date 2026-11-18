package io.github.yuroyami.imagekodec

import java.io.File
import org.junit.Assume.assumeTrue
import kotlin.test.Test

/**
 * The time `ImageKodec.decode` takes on the AVIF files named in `IMAGEKODEC_AV1_DECODE_TIMING` (paths separated
 * by `:`), the best of five runs after a warm-up. #176 runs it before and after sharing the AV1 decoder's code
 * with the encoder, to show that the sharing did not slow the decoder.
 */
class Av1DecodeTimingTest {

    @Test
    fun decodeTimes() {
        val paths = System.getenv("IMAGEKODEC_AV1_DECODE_TIMING")
        assumeTrue("set IMAGEKODEC_AV1_DECODE_TIMING to AVIF paths to run", paths != null)
        for (path in paths!!.split(':')) {
            val bytes = File(path).readBytes()
            repeat(3) { ImageKodec.decode(bytes) }
            var best = Long.MAX_VALUE
            repeat(5) {
                val start = System.nanoTime()
                ImageKodec.decode(bytes)
                best = minOf(best, System.nanoTime() - start)
            }
            println("AV1 decode timing: ${File(path).name} best of 5 ${"%.1f".format(best / 1e6)} ms")
        }
    }
}
