package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.internal.flate.Deflate
import io.github.yuroyami.imagekodec.internal.flate.Inflate
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * The deflate encoder splits its output into blocks and stores the ones Huffman coding would only
 * enlarge, so incompressible data grows by no more than a stored block's 5 bytes per 65535 (#20).
 * `InflateOracleTest` decodes the same streams with the JDK's zlib.
 */
class DeflateBlockTest {

    private fun roundTrip(data: ByteArray): ByteArray {
        val packed = Deflate.encode(data)
        assertContentEquals(data, Inflate.inflate(packed))
        return packed
    }

    @Test
    fun incompressibleDataGrowsByOnlyTheStoredOverhead() {
        for (size in listOf(1, 1000, 65_535, 65_536, 1 shl 20)) {
            val data = Random(size).nextBytes(size)
            val packed = roundTrip(data)
            val limit = size + 5 * ((size + 65_534) / 65_535) + 1
            assertTrue(packed.size <= limit, "$size random bytes took ${packed.size}, the stored limit is $limit")
        }
    }

    @Test
    fun blocksOfDifferentKindsJoinInOneStream() {
        // Random, then runs, then text, then random again: stored, fixed or dynamic blocks in turn, with
        // matches that reach back across block boundaries.
        val random = Random(7)
        val text = "the quick brown fox jumps over the lazy dog ".repeat(3000).encodeToByteArray()
        val data = random.nextBytes(70_000) + ByteArray(50_000) { (it / 1000).toByte() } + text + random.nextBytes(20_000) + text
        val packed = roundTrip(data)
        assertTrue(packed.size < data.size, "${data.size} mixed bytes took ${packed.size}")
    }

    @Test
    fun theEdgeCasesRoundTrip() {
        roundTrip(ByteArray(0))
        roundTrip(ByteArray(1))
        roundTrip(ByteArray(200_000))
        roundTrip(ByteArray(200_000) { (it % 251).toByte() })
    }
}
