package io.github.yuroyami.imagekodec

internal actual fun withDeadline(label: String, millis: Long, block: () -> Unit) = measuredDeadline(label, millis, block)
