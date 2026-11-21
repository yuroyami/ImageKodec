package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.avif.Av1LevelMemo
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The AV1 encoder's memo of chosen levels (#176) gives a kept result only to a call whose every input matches: the
 * coefficient contexts beside the block price its levels, so a result kept under other contexts is not this call's,
 * though its residual and types are the same. The search itself seldom meets such a pair, so it is built here.
 */
class Av1LevelMemoTest {

    private val residual = IntArray(64) { (it * 37 % 23) - 11 }
    private val types = intArrayOf(0, 9, 3, -1)
    private val ctx = IntArray(8) { it % 3 }
    private val levels = IntArray(64) { if (it < 5) 5 - it else 0 }

    private fun kept(memo: Av1LevelMemo) = memo.remember(7, 2 shl 8, residual, 64, ctx, 8, types, levels, 64, 5, 9)

    @Test
    fun aCallWithTheSameInputsTakesTheKeptResult() {
        val memo = Av1LevelMemo()
        val entry = kept(memo)
        val hit = memo.recall(7, 2 shl 8, residual.copyOf(), 64, ctx.copyOf(), 8, types.copyOf())
        assertSame(entry, hit)
        assertContentEquals(levels, hit!!.levels.copyOf(64))
    }

    @Test
    fun otherCoefficientContextsTakeNothing() {
        val memo = Av1LevelMemo()
        kept(memo)
        for (i in ctx.indices) {
            val other = ctx.copyOf().also { it[i] = it[i] + 1 }
            assertNull(memo.recall(7, 2 shl 8, residual, 64, other, 8, types), "context $i")
        }
    }

    @Test
    fun anyOtherInputTakesNothing() {
        val memo = Av1LevelMemo()
        kept(memo)
        assertNull(memo.recall(8, 2 shl 8, residual, 64, ctx, 8, types), "place")
        assertNull(memo.recall(7, 3 shl 8, residual, 64, ctx, 8, types), "intra direction")
        assertNull(memo.recall(7, 2 shl 8, residual.copyOf().also { it[63]++ }, 64, ctx, 8, types), "residual")
        assertNull(memo.recall(7, 2 shl 8, residual, 64, ctx, 8, intArrayOf(0, 9, -1)), "types")
        memo.forget()
        assertNull(memo.recall(7, 2 shl 8, residual, 64, ctx, 8, types), "after forget")
    }
}
