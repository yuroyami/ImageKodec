package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Repeated empty scans used to cost one pass over the whole image each: 500 scans of a 4096 by 4096
 * image took 10 s on the machine that found this. The check is relative, so a slow runner does not
 * flip it: 500 empty scans may not cost more than a few times the same image with one.
 */
class JpegScanCostTest {

    private fun millis(jpeg: ByteArray): Long {
        val start = System.nanoTime()
        runCatching { ImageKodec.decode(jpeg) }
        return (System.nanoTime() - start) / 1_000_000
    }

    @Test
    fun manyEmptyScansCostAboutAsMuchAsOne() {
        // Both files are about 5 KB, which is what the input-size budget asks of a 4096 by 4096 image.
        val one = emptyScanJpeg(side = 4096, scans = 1, padding = 5000)
        val many = emptyScanJpeg(side = 4096, scans = 500)
        millis(one)                                            // warm up the JIT before timing
        val single = millis(one)
        val repeated = millis(many)
        assertTrue(
            repeated <= 4 * single + 500,
            "500 empty scans took $repeated ms, one took $single ms: each scan is walking the whole image",
        )
    }
}
