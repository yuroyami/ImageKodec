package io.github.yuroyami.imagekodec

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
 *
 * Searching harder does not stop a suite from skipping when the tool really is
 * absent, and CI must not stay green when that happens. So when the environment
 * variable [REQUIRE_ENV] is `1`, which only the workflow sets, [hasAll] fails the
 * test instead of letting `assumeTrue` skip it. Locally the variable is unset, and
 * a contributor without OpenJPEG installed can still run the rest.
 */
internal object Tools {

    private val EXTRA_PREFIXES = listOf(
        "/opt/homebrew/bin",     // Homebrew, Apple silicon
        "/usr/local/bin",        // Homebrew on Intel, and the usual Linux local install
        "/usr/bin",
        "/bin",
        "/opt/local/bin",        // MacPorts
    )

    /** Set to `1` to turn a missing oracle tool from a skip into a failure. */
    const val REQUIRE_ENV = "IMAGEKODEC_REQUIRE_ORACLES"

    private val required: Boolean get() = System.getenv(REQUIRE_ENV) == "1"

    /**
     * The executable named [name], or null when it isn't installed.
     *
     * ImageMagick 7 installs `magick`, and ImageMagick 6, the version Debian and
     * Ubuntu still ship, installs the same command line as `convert`. Without the
     * fallback every suite that asks for `magick` skipped on Ubuntu, CI included.
     */
    fun find(name: String): File? {
        if (name == "magick") return findExact("magick") ?: findExact("convert")
        return findExact(name)
    }

    private fun findExact(name: String): File? {
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

    /**
     * True when every one of [names] is installed. When [REQUIRE_ENV] is `1`, a
     * missing one fails the calling test instead of returning false.
     */
    fun hasAll(vararg names: String): Boolean {
        val missing = names.filter { find(it) == null }
        if (missing.isNotEmpty() && required) {
            error("oracle tool(s) ${missing.joinToString()} not found, and $REQUIRE_ENV=1 forbids skipping")
        }
        return missing.isEmpty()
    }

    /**
     * [find], but fails loudly instead of returning null. Use it *after*
     * [hasAll] has already gated the test, so a missing tool at that point is a
     * bug in the gate rather than an uninstalled dependency.
     */
    fun require(name: String): File =
        find(name) ?: error("$name should have been found; the availability check and this call disagree")
}
