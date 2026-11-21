package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * The time `encodeAvif` takes on a 256 by 256 photo-like scene at each effort, on whatever target runs it (#176): the
 * faster of two runs after one to warm up, printed as `[avif-timing]` lines. It takes minutes on JS, so it runs only
 * with `IMAGEKODEC_AV1_ENCODE_TIMING=1`.
 */
class AvifEncodeTimingTest {

    @Test
    fun encodeTimesAtEachEffort() {
        if (environmentVariable("IMAGEKODEC_AV1_ENCODE_TIMING") != "1") {
            println("[avif-timing] skipped: set IMAGEKODEC_AV1_ENCODE_TIMING=1 to run")
            return
        }
        val scene = PhotoScenes.landscape(256, 256).bitmap
        for (effort in 1..3) {
            val options = HeifEncodeOptions(quality = 50, effort = effort)
            var best = Long.MAX_VALUE
            var size = 0
            repeat(3) { run ->
                val start = TimeSource.Monotonic.markNow()
                val file = ImageKodec.encodeAvif(scene, options)
                if (run > 0) best = minOf(best, start.elapsedNow().inWholeMilliseconds)
                size = file.size
            }
            println("[avif-timing] 256x256 quality 50 effort $effort: $size bytes, $best ms")
            assertTrue(size > 0)
        }
    }
}
