package io.github.yuroyami.imagekodec

/**
 * Animated test scenes for the sequence encoders (#177): a camera that pans across one of the [PhotoScenes], so every
 * frame shows the scene moved by a few pixels, with a disc that crosses it on a path of its own, as a sprite over a
 * moving background does. The scene's noise moves with it, as a still photograph's would under a pan.
 */
internal object AnimatedScenes {

    /**
     * [frames] frames of [width] by [height] cut from [scene], panning [dx] and [dy] pixels a frame, with a disc of
     * radius [radius] moving across, each frame [delay] milliseconds.
     */
    fun pan(
        scene: KiteBitmap, width: Int, height: Int, frames: Int, dx: Int = 3, dy: Int = 1, radius: Int = 18, delay: Int = 40,
    ): KiteAnimation {
        require(width + dx * frames <= scene.width && height + dy * frames <= scene.height) { "the pan leaves the scene" }
        val out = (0 until frames).map { t ->
            val cx = width / 5 + t * (width * 3 / 5) / maxOf(1, frames - 1)
            val cy = height / 2 + (t % 7) - 3
            val argb = IntArray(width * height) { i ->
                val x = i % width
                val y = i / width
                val ddx = x - cx
                val ddy = y - cy
                if (ddx * ddx + ddy * ddy < radius * radius) {
                    // A shaded disc: lit from the top left.
                    val shade = 200 - (ddx + ddy) * 2
                    (0xFF shl 24) or (shade.coerceIn(0, 255) shl 16) or ((shade * 3 / 5).coerceIn(0, 255) shl 8) or 40
                } else {
                    scene.argb[(y + t * dy) * scene.width + x + t * dx]
                }
            }
            KiteFrame(KiteBitmap(width, height, argb), delay, delay / 10)
        }
        return KiteAnimation(width, height, out, 0)
    }
}
