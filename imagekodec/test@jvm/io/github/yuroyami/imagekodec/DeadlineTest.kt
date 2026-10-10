package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The JVM's [withDeadline], which FuzzTest runs every mutant under (#102). */
class DeadlineTest {

    @Test
    fun aBlockPastItsDeadlineFailsTheTestInsteadOfStallingIt() {
        val start = System.nanoTime()
        val e = assertFailsWith<AssertionError> { withDeadline("a loop", 200) { while (true) Thread.sleep(10) } }
        assertTrue("a loop ran past the 200 ms deadline" == e.message, e.message)
        assertTrue(System.nanoTime() - start < 5_000_000_000L, "the deadline did not stop the wait")
        // The worker that missed its deadline is abandoned, and the next block runs on a new one.
        var ran = false
        withDeadline("the next block", 1_000) { ran = true }
        assertTrue(ran)
    }

    @Test
    fun theBlocksOwnFailureComesThrough() {
        val e = assertFailsWith<ImageDecodeException> { withDeadline("a refusal", 1_000) { throw ImageDecodeException("refused") } }
        assertEquals("refused", e.message)
        assertFailsWith<AssertionError> { withDeadline("an assertion", 1_000) { kotlin.test.fail("leaked") } }
    }
}
