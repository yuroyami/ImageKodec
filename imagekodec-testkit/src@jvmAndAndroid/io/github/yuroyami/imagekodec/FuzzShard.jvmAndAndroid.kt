package io.github.yuroyami.imagekodec

public actual fun environmentVariable(name: String): String? = System.getenv(name)
