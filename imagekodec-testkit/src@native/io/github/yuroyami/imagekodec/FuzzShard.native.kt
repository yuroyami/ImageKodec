package io.github.yuroyami.imagekodec

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
public actual fun environmentVariable(name: String): String? = getenv(name)?.toKString()
