package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.internal.flate.Deflate
import io.github.yuroyami.imagekodec.internal.flate.Inflate
import java.lang.management.ManagementFactory
import java.util.zip.Inflater
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/** The deflate encoder's memory follows its window and token buffer, not the input (#163). */
class DeflateMemoryTest {

    private fun allocatedDuring(block: () -> Unit): Long {
        // The allocation counter of this thread: every byte the block allocates, freed or not.
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        block()
        return bean.getThreadAllocatedBytes(id) - before
    }

    @Test
    fun thirtyTwoMebibytesOfRandomBytesAllocateAFewTimesTheirSize() {
        val data = ByteArray(32 shl 20).also { Random(11).nextBytes(it) }
        lateinit var encoded: ByteArray
        val allocated = allocatedDuring { encoded = Deflate.encode(data) }
        // Every allocation of the call, the output's growth copies among them, not only what it holds
        // at once. Measured on JDK 21: 374,505,952 bytes, 11.2 times the input, where the encoder
        // before #163 allocated 720,599,584, 21.5 times, a hash chain of 4 bytes an input byte among them.
        assertTrue(allocated < 14L * data.size, "allocated $allocated bytes for ${data.size}")
        val z = Inflater(true).apply { setInput(encoded) }
        val back = ByteArray(data.size)
        var n = 0
        while (n < back.size && !z.finished()) n += z.inflate(back, n, back.size - n)
        assertContentEquals(data, back)
        assertContentEquals(data, Inflate.inflate(encoded))
    }
}
