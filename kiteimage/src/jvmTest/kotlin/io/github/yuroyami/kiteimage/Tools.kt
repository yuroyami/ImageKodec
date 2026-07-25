package io.github.yuroyami.kiteimage

import java.io.File

/**
 * Locates the reference codec binaries the oracle suites compare against.
 *
 * These tests are only worth having if they actually run, and a hardcoded
 * `/opt/homebrew/bin` finds them on one developer's Mac and silently skips
 * everywhere else, CI included. A test that skips looks exactly like a test that
 * passes, which is the worst possible failure mode for a suite whose whole job
 * is to catch divergence from the reference.
 *
 * So: search `PATH` first, then the handful of standard prefixes across macOS
 * (Homebrew on both architectures), Linux and MacPorts.
 */
internal object Tools {

    private val EXTRA_PREFIXES = listOf(
        "/opt/homebrew/bin",     // Homebrew, Apple silicon
        "/usr/local/bin",        // Homebrew on Intel, and the usual Linux local install
        "/usr/bin",
        "/bin",
        "/opt/local/bin",        // MacPorts
    )

    /** The executable named [name], or null when it isn't installed. */
    fun find(name: String): File? {
        val fromPath = System.getenv("PATH")
            ?.split(File.pathSeparator)
            ?.asSequence()
            ?.filter { it.isNotBlank() }
            ?.map { File(it, name) }
            ?.firstOrNull { it.canExecute() }
        if (fromPath != null) return fromPath

        return EXTRA_PREFIXES.asSequence()
            .map { File(it, name) }
            .firstOrNull { it.canExecute() }
    }

    /** True when every one of [names] is installed. */
    fun hasAll(vararg names: String): Boolean = names.all { find(it) != null }

    /**
     * [find], but fails loudly instead of returning null. Use it *after*
     * [hasAll] has already gated the test, so a missing tool at that point is a
     * bug in the gate rather than an uninstalled dependency.
     */
    fun require(name: String): File =
        find(name) ?: error("$name should have been found; the availability check and this call disagree")
}
