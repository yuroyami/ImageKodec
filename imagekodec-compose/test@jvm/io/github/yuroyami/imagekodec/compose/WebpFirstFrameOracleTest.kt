package io.github.yuroyami.imagekodec.compose

import androidx.compose.ui.use
import io.github.yuroyami.imagekodec.ImageKodec
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

class WebpFirstFrameOracleTest {
    @Test
    fun firstPartialFrameMatchesIndependentSkiaComposition() {
        val bytes = Base64.decode("UklGRoQAAABXRUJQVlA4WAoAAAACAAAABAAAAAAAQU5JTQYAAAAA/wD/AABBTk1GKAAAAAEAAAAAAAAAAAAAAGQAAAJWUDhMDwAAAC8AAAAABxD9j/4HIqL/AQBBTk1GKAAAAAIAAAAAAAAAAAAAAGQAAAJWUDhMDwAAAC8AAAAABxDR//4HIqL/AQA=")
        val still = ImageKodec.decode(bytes)
        Data.makeFromBytes(bytes).use { data ->
            Codec.makeFromData(data).use { codec ->
                assertEquals(2, codec.frameCount)
                codec.readPixels().use { reference ->
                    assertEquals(reference.width, still.width)
                    assertEquals(reference.height, still.height)
                    for (y in 0 until still.height) for (x in 0 until still.width) {
                        assertEquals(reference.getColor(x, y), still[x, y], "Skia first frame at $x/$y")
                    }
                }
            }
        }
    }
}
