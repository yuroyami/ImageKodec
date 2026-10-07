package io.github.yuroyami.imagekodec

import kotlin.test.fail
import kotlin.time.TimeSource

/**
 * Runs [block] and fails the test when it takes longer than [millis], so an input that loops or
 * runs for minutes fails the suite instead of stalling it (#102). The JVM runs the block on a
 * worker and stops waiting at the deadline, which also catches a loop that never ends. A platform
 * with no thread to wait on measures the block after it returns.
 */
internal expect fun withDeadline(label: String, millis: Long, block: () -> Unit)

/** [withDeadline] for a platform that can only measure: fails after [block] returns, if it was late. */
internal fun measuredDeadline(label: String, millis: Long, block: () -> Unit) {
    val start = TimeSource.Monotonic.markNow()
    block()
    val took = start.elapsedNow().inWholeMilliseconds
    if (took > millis) fail("$label took $took ms, past the $millis ms deadline")
}
