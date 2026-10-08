package io.github.yuroyami.imagekodec.internal.color

import io.github.yuroyami.imagekodec.ColorProfile
import io.github.yuroyami.imagekodec.RenderingIntent

/** Which of a file's colour declarations converts it to sRGB, and how. */
internal object ColorManagement {
    /**
     * The conversion of [profile] to sRGB with [intent], or null when nothing needs converting:
     * the file declares sRGB or nothing, or only what this does not read.
     */
    fun converter(profile: ColorProfile, intent: RenderingIntent): SrgbConverter? {
        val icc = profile.icc?.let { IccProfile.parse(it) } ?: return null
        return SrgbConverter.of(icc, intent)
    }
}
