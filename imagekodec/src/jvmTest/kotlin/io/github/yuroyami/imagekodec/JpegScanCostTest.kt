package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Repeated empty scans used to cost one pass over the whole image each: 500 scans of a 4096 by 4096
 * image took 10 s on the machine that found this. The checks are relative, so a slow runner does not
 * flip them: many scans may not cost more than a few times the same image with one. Each decode must
 * also return the image, or a decode that fails fast would pass.
 */
class JpegScanCostTest {

    private fun millis(jpeg: ByteArray): Long {
        val start = System.nanoTime()
        val bitmap = ImageKodec.decode(jpeg)
        val elapsed = (System.nanoTime() - start) / 1_000_000
        assertEquals(4096, bitmap.width)
        assertEquals(4096, bitmap.height)
        return elapsed
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

    @Test
    fun refinementScansOverEmptyBlocksCostAboutAsMuchAsOne() {
        // #67: an AC refinement inside an end-of-band run walked all 63 coefficients of every block.
        // 512 refinements from bit 1 with no first scan break the progression and are passed over;
        // a first scan at bit 13 and its 13 refinements are a conforming file, whose empty blocks
        // now cost one step each. The padding in the reference keeps it within the input budget.
        val reference = eobRunJpeg(side = 4096, scans = listOf(0 to 0)) .let { jpeg ->
            jpeg.copyOfRange(0, 2) + jpegSegment(0xFE, ByteArray(5000)) + jpeg.copyOfRange(2, jpeg.size)
        }
        val bogus = eobRunJpeg(side = 4096, scans = List(512) { 1 to 0 })
        val conforming = eobRunJpeg(side = 4096, scans = listOf(0 to 13) + (13 downTo 1).map { it to it - 1 })
            .let { jpeg -> jpeg.copyOfRange(0, 2) + jpegSegment(0xFE, ByteArray(5000)) + jpeg.copyOfRange(2, jpeg.size) }
        millis(reference)
        val single = millis(reference)
        for ((name, jpeg) in listOf("512 bogus refinements" to bogus, "13 conforming refinements" to conforming)) {
            val elapsed = millis(jpeg)
            assertTrue(elapsed <= 4 * single + 500, "$name took $elapsed ms, one scan took $single ms")
        }
    }
}
