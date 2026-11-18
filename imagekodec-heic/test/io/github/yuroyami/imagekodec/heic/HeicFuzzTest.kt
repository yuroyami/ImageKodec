package io.github.yuroyami.imagekodec.heic

import io.github.yuroyami.imagekodec.ColorTarget
import io.github.yuroyami.imagekodec.FuzzSettings
import io.github.yuroyami.imagekodec.FuzzShard
import io.github.yuroyami.imagekodec.ImageDecodeException
import io.github.yuroyami.imagekodec.ImageKodec
import io.github.yuroyami.imagekodec.withDeadline
import kotlin.test.Test
import kotlin.test.fail

/**
 * Damaged copies of [HeicVector] through every path of the facade (#145): each mutant decodes or throws
 * ImageDecodeException, within a deadline. The core's `FuzzTest` has no HEVC codec installed, so this is
 * where the HEVC decoder meets hostile container and slice data.
 */
class HeicFuzzTest {

    private val shard = FuzzShard.fromEnvironment()

    private class Rng(private var state: Int) {
        fun next(): Int {
            var x = state
            x = x xor (x shl 13)
            x = x xor (x ushr 17)
            x = x xor (x shl 5)
            state = x
            return x
        }

        fun nextInt(bound: Int): Int = ((next().toLong() and 0xFFFFFFFFL) % bound).toInt()
    }

    private fun mustFailCleanly(label: String, body: () -> Unit) {
        try {
            body()
        } catch (_: ImageDecodeException) {
            // The contract: malformed input is a decode error.
        } catch (t: Throwable) {
            fail("$label${FuzzSettings.replay} leaked ${t::class.simpleName}: ${t.message}")
        }
    }

    private fun exercise(label: String, bytes: ByteArray, animation: Boolean = false) {
        if (!shard.takesNext()) return
        withDeadline(label + FuzzSettings.replay, DEADLINE_MILLIS) {
            if (animation) mustFailCleanly("$label decodeAnimation") { ImageKodec.decodeAnimation(bytes) }
            mustFailCleanly("$label probe") { ImageKodec.probe(bytes) }
            mustFailCleanly("$label decode") { ImageKodec.decode(bytes, applyOrientation = true) }
            mustFailCleanly("$label decode16") { ImageKodec.decode16(bytes) }
            mustFailCleanly("$label decode to sRGB") { ImageKodec.decode(bytes, colorTarget = ColorTarget.Srgb()) }
            try {
                ImageKodec.probeOrNull(bytes)
            } catch (t: Throwable) {
                fail("$label probeOrNull leaked ${t::class.simpleName}: ${t.message}")
            }
        }
    }

    private val seed get() = HeicVector.file

    @Test
    fun bitFlips() {
        ImageKodecHeic.install()
        val rng = Rng(FuzzSettings.mix(0x48454943))
        repeat(BIT_FLIPS * FuzzSettings.rounds) {
            val bytes = seed.copyOf()
            val at = rng.nextInt(bytes.size)
            val bit = rng.nextInt(8)
            bytes[at] = (bytes[at].toInt() xor (1 shl bit)).toByte()
            exercise("heic bit@$at.$bit", bytes)
        }
    }

    @Test
    fun byteChanges() {
        ImageKodecHeic.install()
        val rng = Rng(FuzzSettings.mix(0x68657663))
        repeat(BYTE_CHANGES * FuzzSettings.rounds) {
            val bytes = seed.copyOf()
            val count = 1 + rng.nextInt(4)
            val label = StringBuilder("heic bytes")
            repeat(count) {
                val at = rng.nextInt(bytes.size)
                val v = when (rng.nextInt(4)) {
                    0 -> 0
                    1 -> 0xFF
                    else -> rng.nextInt(256)
                }
                bytes[at] = v.toByte()
                label.append(" @$at=$v")
            }
            exercise(label.toString(), bytes)
        }
    }

    @Test
    fun truncations() {
        ImageKodecHeic.install()
        val size = seed.size
        val step = maxOf(1, size / (TRUNCATIONS * FuzzSettings.rounds))
        var cut = 0
        while (cut < size) {
            exercise("heic cut@$cut", seed.copyOf(cut))
            cut += step
        }
        exercise("heic cut@${size - 1}", seed.copyOf(size - 1))
    }

    /**
     * [HeicSequenceVector] (#157): its sample table with `ctts`, and P and B pictures that refer to the
     * samples before them, through decodeAnimation as well.
     */
    @Test
    fun sequenceMutations() {
        ImageKodecHeic.install()
        val sequence = HeicSequenceVector.file()
        val rng = Rng(FuzzSettings.mix(0x6d736631))
        repeat(SEQUENCE_MUTANTS * FuzzSettings.rounds) {
            val bytes = sequence.copyOf()
            val at = rng.nextInt(bytes.size)
            val label = if (rng.nextInt(2) == 0) {
                val bit = rng.nextInt(8)
                bytes[at] = (bytes[at].toInt() xor (1 shl bit)).toByte()
                "heic sequence bit@$at.$bit"
            } else {
                val v = when (rng.nextInt(3)) {
                    0 -> 0
                    1 -> 0xFF
                    else -> rng.nextInt(256)
                }
                bytes[at] = v.toByte()
                "heic sequence byte@$at=$v"
            }
            exercise(label, bytes, animation = true)
        }
        val step = maxOf(1, sequence.size / (TRUNCATIONS * FuzzSettings.rounds))
        var cut = 0
        while (cut < sequence.size) {
            exercise("heic sequence cut@$cut", sequence.copyOf(cut), animation = true)
            cut += step
        }
    }

    private companion object {
        const val SEQUENCE_MUTANTS = 600
        const val BIT_FLIPS = 1000
        const val BYTE_CHANGES = 600
        const val TRUNCATIONS = 200

        /** Far past what a mutant of a file of a few CTBs needs, so only a loop or a runaway allocation reaches it. */
        const val DEADLINE_MILLIS = 60_000L
    }
}
