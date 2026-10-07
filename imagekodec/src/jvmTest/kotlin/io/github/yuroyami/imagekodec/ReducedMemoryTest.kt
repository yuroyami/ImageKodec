package io.github.yuroyami.imagekodec

import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A reduced decode must allocate for the smaller image, not for the full one: that is its
 * whole point. The JVM counts the bytes each thread allocates, so this measures instead of
 * trusting the code.
 */
class ReducedMemoryTest {

    private fun allocated(block: () -> Unit): Long {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        block()
        return bean.getThreadAllocatedBytes(id) - before
    }

    private fun timed(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    @Test
    fun aReducedJpegDecodeAllocatesForTheSmallerImage() {
        // A 3,000 by 2,000 photo-like baseline JPEG with 4:2:0 chroma.
        val w = 3000
        val h = 2000
        val jpeg = ImageKodec.encodeJpeg(KiteBitmap(w, h, IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            argb(0xFF, (x * 7 + y) and 0xFF, (y * 3) and 0xFF, ((x xor y) * 5) and 0xFF)
        }), quality = 85)
        repeat(2) { ImageKodec.decode(jpeg); ImageKodec.decodeReduced(jpeg, 8) } // warm up
        val full = allocated { ImageKodec.decode(jpeg) }
        val report = StringBuilder("full: $full bytes, ${timed { ImageKodec.decode(jpeg) }} ms\n")
        for (r in listOf(2, 4, 8)) {
            val bytes = allocated { ImageKodec.decodeReduced(jpeg, r) }
            report.appendLine("1/$r: $bytes bytes, ${timed { ImageKodec.decodeReduced(jpeg, r) }} ms")
            // The pixels shrink by r squared. Allow twice that share for the fixed costs.
            assertTrue(bytes < 2 * full / (r * r), report.toString())
        }
        println(report)
    }
}
