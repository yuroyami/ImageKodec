package io.github.yuroyami.kiteimage

import io.github.yuroyami.kiteimage.codec.Jbig2Decoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The walk over segment headers has to move forward. A data length of 0xFFFFFFF5 is not the
 * unknown-length marker, but narrowed to an Int it is -11, which put the cursor back on the header it
 * had just read. The decode then parsed the same segment until it ran out of memory: 14 s on 11 bytes.
 * The decode runs on its own thread so a walk that does not end fails the test and does not stall it.
 */
class Jbig2SegmentWalkTest {

    @Test
    fun aSegmentLengthThatWrapsDoesNotSendTheWalkBackwards() {
        val stream = byteArrayOf(0, 0, 0, 0, 0x33, 0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xF5.toByte())
        val done = CountDownLatch(1)
        val outcome = AtomicReference<Result<ByteArray?>>()
        val thread = Thread {
            outcome.set(runCatching { Jbig2Decoder.decode(stream, null, 8, 8) })
            done.countDown()
        }
        thread.isDaemon = true
        thread.start()
        assertTrue(done.await(5, TimeUnit.SECONDS), "the segment walk did not end within 5 s on an 11-byte stream")
        assertTrue(outcome.get().isSuccess, "the decode should return null, not throw: ${outcome.get()}")
    }
}
