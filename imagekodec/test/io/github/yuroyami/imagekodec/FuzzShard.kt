package io.github.yuroyami.imagekodec

/** The value of the environment variable [name], or null when it is not set or the platform has none. */
internal expect fun environmentVariable(name: String): String?

/**
 * One share of the mutants of [FuzzTest], so that several processes can run the suite at once.
 *
 * `IMAGEKODEC_FUZZ_SHARD=1/4` gives a process every mutant whose number leaves 1 when divided
 * by 4. The four processes 0/4 to 3/4 together run each mutant once. Without the variable a
 * process runs every mutant, which is what a local run does.
 */
internal class FuzzShard(private val index: Int, private val count: Int) {

    private var mutant = 0

    /** True when the next mutant belongs to this process. Call it once for each mutant, in order. */
    fun takesNext(): Boolean = mutant++ % count == index

    companion object {
        const val ENV = "IMAGEKODEC_FUZZ_SHARD"

        /** The share that [ENV] names. A value that is not `index/count` fails, so a typing mistake cannot skip mutants. */
        fun fromEnvironment(): FuzzShard = parse(environmentVariable(ENV))

        fun parse(value: String?): FuzzShard {
            if (value.isNullOrEmpty()) return FuzzShard(0, 1)
            val parts = value.split('/')
            val index = parts.getOrNull(0)?.toIntOrNull()
            val count = parts.getOrNull(1)?.toIntOrNull()
            if (parts.size != 2 || index == null || count == null || count < 1 || index !in 0 until count) {
                error("$ENV must be index/count with 0 <= index < count, such as 0/4, and is \"$value\"")
            }
            return FuzzShard(index, count)
        }
    }
}

/**
 * What changes the mutants of [FuzzTest] from run to run. Both default to the fixed set, so a plain
 * run, and a failure, stay reproducible (#117).
 *
 * - `IMAGEKODEC_FUZZ_SEED` is mixed into each test's own constant, so another value gives other
 *   mutants. Every failure names the seed that replays it.
 * - `IMAGEKODEC_FUZZ_ROUNDS` multiplies the mutant count of each mutation test.
 */
internal object FuzzSettings {
    const val SEED_ENV = "IMAGEKODEC_FUZZ_SEED"
    const val ROUNDS_ENV = "IMAGEKODEC_FUZZ_ROUNDS"

    val seed: Int = parseSeed(environmentVariable(SEED_ENV))
    val rounds: Int = parseRounds(environmentVariable(ROUNDS_ENV))

    /** A seed in decimal or 0x hexadecimal. A value that is neither fails, so a typing mistake cannot pass as the default. */
    fun parseSeed(value: String?): Int {
        if (value.isNullOrEmpty()) return 0
        val parsed = if (value.startsWith("0x") || value.startsWith("0X")) value.substring(2).toLongOrNull(16) else value.toLongOrNull()
        return parsed?.toInt() ?: error("$SEED_ENV must be a number, such as 20261107 or 0x1F, and is \"$value\"")
    }

    fun parseRounds(value: String?): Int {
        if (value.isNullOrEmpty()) return 1
        val parsed = value.toIntOrNull()
        if (parsed == null || parsed < 1) error("$ROUNDS_ENV must be a whole number of at least 1, and is \"$value\"")
        return parsed
    }

    /** The starting state for a test whose own constant is [constant]: the constant itself under the default seed. */
    fun mix(constant: Int): Int {
        if (seed == 0) return constant
        val mixed = constant xor (seed * -0x61c88647)   // the golden-ratio constant 0x9E3779B9
        return if (mixed == 0) constant else mixed       // xorshift never leaves 0
    }

    /** How to replay a failure, for its message. Empty under the default seed. */
    val replay: String get() = if (seed == 0) "" else " ($SEED_ENV=$seed)"
}
