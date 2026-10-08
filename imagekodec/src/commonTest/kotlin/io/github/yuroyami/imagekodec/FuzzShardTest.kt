package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FuzzShardTest {

    private fun taken(value: String?, mutants: Int): List<Int> {
        val shard = FuzzShard.parse(value)
        return (0 until mutants).filter { shard.takesNext() }
    }

    @Test
    fun withoutTheVariableAProcessRunsEveryMutant() {
        assertEquals((0 until 10).toList(), taken(null, 10))
        assertEquals((0 until 10).toList(), taken("", 10))
    }

    @Test
    fun theSharesTogetherRunEachMutantOnce() {
        assertEquals(listOf(1, 5, 9), taken("1/4", 12))
        val all = (0 until 4).flatMap { taken("$it/4", 103) }
        assertEquals((0 until 103).toList(), all.sorted())
    }

    @Test
    fun aValueThatIsNotAShareFails() {
        for (value in listOf("4/4", "-1/4", "1", "1/0", "a/b", "1/4/2", "0 / 4")) {
            assertFailsWith<IllegalStateException>(value) { FuzzShard.parse(value) }
        }
    }
}
