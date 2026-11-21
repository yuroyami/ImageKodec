package io.github.yuroyami.imagekodec.internal

import io.github.yuroyami.imagekodec.TileJob
import io.github.yuroyami.imagekodec.TileRunner
import kotlin.concurrent.atomics.AtomicArray
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicIntArray
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** The [TileRunner] that every decode uses (#152), and the way a decoder hands it its tiles. */
@OptIn(ExperimentalAtomicApi::class)
internal object Tiles {

    private val current = AtomicReference(TileRunner.Serial)

    fun set(runner: TileRunner) = current.store(runner)

    /**
     * Runs [job] for each index below [count] through the runner, and throws, once the runner has
     * returned, what the lowest index that failed threw, so the error does not depend on the order
     * the jobs ran in. A job whose index is past one that already failed does nothing, since its
     * error could not be the one thrown. With [parallel] false, or with [TileRunner.Serial], the
     * jobs run in order on this thread, and the first error stops them.
     */
    fun run(count: Int, parallel: Boolean = true, job: (Int) -> Unit) {
        val runner = current.load()
        if (!parallel || runner === TileRunner.Serial || count < 2) {
            for (i in 0 until count) job(i)
            return
        }
        val errors = AtomicArray(arrayOfNulls<Throwable>(count))
        val ended = AtomicIntArray(count)
        val failedAt = AtomicInt(count)
        val jobs = List(count) { i ->
            TileJob {
                if (i < failedAt.load()) {
                    try {
                        job(i)
                    } catch (t: Throwable) {
                        errors.storeAt(i, t)
                        while (true) {
                            val f = failedAt.load()
                            if (f <= i || failedAt.compareAndSet(f, i)) break
                        }
                    }
                }
                ended.storeAt(i, 1)
            }
        }
        runner.run(jobs)
        for (i in 0 until count) {
            errors.loadAt(i)?.let { throw it }
            check(ended.loadAt(i) == 1) { "the tile runner returned before job $i of $count ended" }
        }
    }
}
