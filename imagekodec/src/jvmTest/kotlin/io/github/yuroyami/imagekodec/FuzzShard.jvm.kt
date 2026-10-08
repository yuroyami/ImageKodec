package io.github.yuroyami.imagekodec

internal actual fun environmentVariable(name: String): String? = System.getenv(name)
