package io.github.yuroyami.imagekodec

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScalingAllocationTest {
    @Test
    fun aSmallReductionAllocatesOnlyOutputStorage() {
        val source = KiteBitmap(1000, 1000, IntArray(1_000_000) { -1 })
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assertTrue(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        repeat(8) { source.scaled(999, 999) }
        val thread = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(thread)
        val result = source.scaled(999, 999)
        val allocated = bean.getThreadAllocatedBytes(thread) - before
        val outputBytes = result.argb.size.toLong() * 4
        assertEquals(999, result.width)
        assertEquals(999, result.height)
        assertTrue(allocated < outputBytes + 4096, "$allocated bytes allocated for $outputBytes bytes of pixels")
        println("Scaling allocation: $allocated bytes for $outputBytes bytes of output pixels")
    }
}
