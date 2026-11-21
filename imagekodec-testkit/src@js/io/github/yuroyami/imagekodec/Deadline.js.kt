package io.github.yuroyami.imagekodec

public actual fun withDeadline(label: String, millis: Long, block: () -> Unit) = measuredDeadline(label, millis, block)
