package io.github.yuroyami.imagekodec.internal

/**
 * What a caller's cancellation check threw, carried past every catch inside a decoder to the
 * entry point that unwraps it (#123). It is no [Exception], so a decoder's `catch (e: Exception)`,
 * which turns a fault into a decode error, lets it through.
 */
internal class DecodeCancelled(val reason: Throwable) : Throwable()

/** [check] as the decoders call it: whatever it throws travels as [DecodeCancelled]. */
internal fun cancellation(check: (() -> Unit)?): (() -> Unit)? =
    if (check == null) null else {
        {
            try {
                check()
            } catch (e: DecodeCancelled) {
                throw e
            } catch (t: Throwable) {
                throw DecodeCancelled(t)
            }
        }
    }

/**
 * Runs [block] with the decoders' form of [check], and gives back what the check threw as itself,
 * so a caller sees its own exception whatever the decoder does around it.
 */
internal inline fun <T> cancellable(noinline check: (() -> Unit)?, block: (cancel: (() -> Unit)?) -> T): T {
    if (check == null) return block(null)
    try {
        return block(cancellation(check))
    } catch (e: DecodeCancelled) {
        throw e.reason
    }
}
