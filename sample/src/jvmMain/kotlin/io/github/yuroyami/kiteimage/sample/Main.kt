package io.github.yuroyami.kiteimage.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.github.yuroyami.kiteimage.KiteAnimation
import io.github.yuroyami.kiteimage.KiteBitmap
import io.github.yuroyami.kiteimage.KiteFrame
import io.github.yuroyami.kiteimage.KiteImage
import io.github.yuroyami.kiteimage.compose.KiteImage

private class Tile(val title: String, val bytes: ByteArray)

/** Runtime-built content so the encoders get dogfooded on every launch. */
private fun tiles(): List<Tile> {
    fun card(w: Int, h: Int, alpha: Boolean): KiteBitmap {
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val a = if (alpha) ((x + y) * 255 / (w + h - 2)) else 0xFF
            px[y * w + x] = (a shl 24) or ((x * 255 / (w - 1)) shl 16) or
                ((y * 255 / (h - 1)) shl 8) or (255 - x * 255 / (w - 1))
        }
        return KiteBitmap(w, h, px)
    }

    /** A four-frame spin, so the GIF encoder gets exercised on an animation. */
    fun spinner(): KiteAnimation {
        val size = 96
        val frames = List(4) { i ->
            val px = IntArray(size * size)
            for (y in 0 until size) for (x in 0 until size) {
                val quadrant = (x / (size / 2)) + 2 * (y / (size / 2))
                val lit = quadrant == i
                px[y * size + x] = if (lit) 0xFFF07A32.toInt() else 0xFF23374F.toInt()
            }
            KiteFrame(KiteBitmap(size, size, px), delayMillis = 180, delayRawCentiseconds = 18)
        }
        return KiteAnimation(size, size, frames, loopCount = 0)
    }

    return listOf(
        Tile("GIF: animated, decoded + played\nby KiteImage", SAMPLE_GIF),
        Tile("GIF: OUR encoder, animated\n(quantised + dithered)", KiteImage.encodeGif(spinner())),
        Tile("APNG: acTL/fcTL/fdAT,\nfour frames", SAMPLE_APNG),
        Tile("WebP lossless (VP8L)", SAMPLE_WEBP),
        Tile("WebP: animated (ANIM/ANMF)", SAMPLE_WEBP_ANIMATED),
        Tile("JPEG 4:2:0 (ffmpeg-encoded)", SAMPLE_JPEG),
        Tile("JPEG: OUR encoder, q85", KiteImage.encodeJpeg(card(96, 96, alpha = false), quality = 85)),
        Tile("PNG: OUR encoder, alpha", KiteImage.encodePng(card(96, 96, alpha = true))),
        Tile("BMP: OUR encoder, 32-bit\nwith alpha", KiteImage.encodeBmp(card(96, 96, alpha = true))),
        Tile("JPEG 2000 (absorbed JPX codec)", SAMPLE_JP2),
        Tile("TIFF deflate (ffmpeg-encoded)", SAMPLE_TIFF),
    )
}

/**
 * The one-line summary under each tile comes from `KiteImage.probe`: the header
 * read, not the decode. It doubles as a live check that probing and decoding
 * agree about what every one of these files is.
 */
private fun caption(bytes: ByteArray): String {
    val info = KiteImage.probeOrNull(bytes) ?: return "unrecognised"
    return buildString {
        append(info.format)
        append("  ${info.displayWidth}x${info.displayHeight}")
        if (info.bitDepth != 8) append("  ${info.bitDepth}-bit")
        if (info.hasAlpha) append("  alpha")
        if (info.isAnimated) {
            append("  ${info.frameCount} frames")
            append(if (info.loopCount == 0) ", looping" else ", x${info.loopCount}")
        }
    }
}

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "KiteImage sample: every pixel pure Kotlin") {
        Surface(color = MaterialTheme.colorScheme.background) {
            val all = tiles()
            LazyVerticalGrid(
                columns = GridCells.Adaptive(180.dp),
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(all) { tile -> TileView(tile) }
            }
        }
    }
}

@Composable
private fun TileView(tile: Tile) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(160.dp).background(Color(0xFFE8E8E8)),
            contentAlignment = Alignment.Center,
        ) {
            KiteImage(
                data = tile.bytes,
                contentDescription = tile.title,
                modifier = Modifier.size(150.dp),
            )
        }
        Text(
            tile.title,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            caption(tile.bytes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
