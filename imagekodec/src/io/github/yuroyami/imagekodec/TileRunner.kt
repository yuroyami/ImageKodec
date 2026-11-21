package io.github.yuroyami.imagekodec

/**
 * Runs the independent jobs of a decode, the tiles of a HEIC or AVIF grid, on the threads it
 * chooses (#152), and the tiles of a grid that `encodeHeic` codes (#174). [ImageKodec.setTileRunner] sets the one runner that every decode uses; the
 * default, [Serial], runs the jobs in order on the calling thread.
 *
 * Each job decodes one tile with a codec session of its own and copies it into the canvas at
 * once. The tiles cover disjoint areas, so the jobs need no lock, and a decode holds the canvas
 * and one tile for each job running at a time. A job calls the decode's cancellation check before
 * its tile, on the thread that runs it.
 *
 * On the JVM, a fixed pool of threads:
 *
 * ```kotlin
 * val pool = Executors.newFixedThreadPool(4)
 * ImageKodec.setTileRunner { jobs -> jobs.map { pool.submit(it::run) }.forEach { it.get() } }
 * ```
 *
 * JS and Wasm have no threads, so [Serial] is the only sensible runner there.
 */
public fun interface TileRunner {
    /**
     * Runs every one of [jobs] once, in any order and on any threads, and returns only once every
     * one has ended. A job never throws: what goes wrong in it is kept, and the decode throws, after
     * this returns, the exception of the first tile, by tile order, that failed. A job that this
     * leaves unrun fails the decode with an [IllegalStateException]. What this throws itself comes
     * out of the decode as it is.
     *
     * No job calls a runner, so a pool of a fixed size cannot deadlock on jobs that wait for jobs.
     * Several decodes on several threads may call [run] at the same time.
     */
    public fun run(jobs: List<TileJob>)

    public companion object {
        /** Runs the jobs one after another, in order, on the calling thread. */
        public val Serial: TileRunner = TileRunner { jobs -> for (job in jobs) job.run() }
    }
}

/** One job of a [TileRunner]: the decode of one tile. It never throws. */
public fun interface TileJob {
    public fun run()
}
