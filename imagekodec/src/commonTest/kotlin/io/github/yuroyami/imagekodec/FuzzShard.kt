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
