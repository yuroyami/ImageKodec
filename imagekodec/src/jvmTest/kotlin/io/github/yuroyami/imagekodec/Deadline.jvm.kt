package io.github.yuroyami.imagekodec

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.fail

/** The worker every deadline runs on. A block that misses its deadline keeps it, so the next gets a new one. */
private var worker: ExecutorService = newWorker()

private fun newWorker(): ExecutorService =
    Executors.newSingleThreadExecutor { task -> Thread(task, "deadline").apply { isDaemon = true } }

internal actual fun withDeadline(label: String, millis: Long, block: () -> Unit) {
    val result = worker.submit(Callable { block() })
    try {
        result.get(millis, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        result.cancel(true)
        worker = newWorker()
        fail("$label ran past the $millis ms deadline")
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }
}
