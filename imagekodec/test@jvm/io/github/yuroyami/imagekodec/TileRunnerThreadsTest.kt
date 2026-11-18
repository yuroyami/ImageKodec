package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.avif.HeifCodecs
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * The tile runner on four threads of an executor (#152): a hundred decodes of each grid give the
 * pixels of the serial decode, and the jobs really run on more than one thread.
 */
@OptIn(ExperimentalHeifCodecApi::class)
class TileRunnerThreadsTest {

    @AfterTest
    fun restore() {
        ImageKodec.setTileRunner(TileRunner.Serial)
        HeifCodecs.reset()
    }

    @Test
    fun fourThreadsGiveTheSamePixelsInAHundredRuns() {
        ImageKodec.registerHeifCodec(HashTileCodec)
        val files = listOf(
            "the libavif grid" to AvifFixtures.grid,
            "an 8 by 8 grid of 4:2:0 at 10 bits" to HashTileCodec.grid(format = 1, depth = 10, rows = 8, columns = 8, tileWidth = 64, tileHeight = 48),
            "4:2:2 with an alpha grid" to HashTileCodec.grid(format = 2, alpha = HashTileCodec.AlphaKind.GRID, premultiplied = true),
        )
        val pool = Executors.newFixedThreadPool(4)
        try {
            val threads = ConcurrentHashMap.newKeySet<Long>()
            for ((label, file) in files) {
                ImageKodec.setTileRunner(TileRunner.Serial)
                val info = ImageKodec.probe(file)
                val full = ImageKodec.decode(file).argb
                val reduced = ImageKodec.decodeDownscaledTo(file, info.width / 3, info.height / 3).argb
                ImageKodec.setTileRunner { jobs ->
                    jobs.map { job ->
                        pool.submit {
                            threads += Thread.currentThread().id
                            job.run()
                        }
                    }.forEach { it.get() }
                }
                repeat(100) { run ->
                    assertContentEquals(full, ImageKodec.decode(file).argb, "$label, run $run")
                    assertContentEquals(reduced, ImageKodec.decodeDownscaledTo(file, info.width / 3, info.height / 3).argb, "$label reduced, run $run")
                }
            }
            assertTrue(threads.size > 1, "the jobs ran on ${threads.size} thread")
        } finally {
            pool.shutdownNow()
        }
    }
}
