package io.github.yuroyami.imagekodec

// A browser has no process, so a browser run takes every mutant.
private fun processEnvironment(name: String): String? =
    js("(typeof process !== 'undefined' && process.env && process.env[name]) || null")

internal actual fun environmentVariable(name: String): String? = processEnvironment(name)
