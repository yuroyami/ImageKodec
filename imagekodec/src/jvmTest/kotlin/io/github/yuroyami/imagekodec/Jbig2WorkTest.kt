package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.Jbig2Decoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A damaged symbol dictionary has to end. Its export flags are runs over the symbols, and before
 * the runs were bounded a negative or zero run kept the walk in place while the coder, out of data,
 * kept making up more: a damaged PDF page held the decoder past a 10-second watchdog. These are
 * symbol dictionaries of 12 random coded bytes that never finished before the fix. Each decode runs
 * on its own thread, so one that does not end fails the test instead of stalling it.
 */
class Jbig2WorkTest {

    @Test
    fun symbolDictionariesThatOnceNeverEndedNowEnd() {
        for ((newSymbols, seed) in listOf(1 to 382, 3 to 29, 3 to 365)) {
            val stream = randomSymbolDictionary(template = 0, newSymbols = newSymbols, seed = seed)
            val done = CountDownLatch(1)
            val thread = Thread {
                Jbig2Decoder.decode(stream, null, 8, 8)
                done.countDown()
            }
            thread.isDaemon = true
            thread.start()
            assertTrue(done.await(5, TimeUnit.SECONDS), "seed $seed with $newSymbols new symbols did not end within 5 s")
        }
    }
}
