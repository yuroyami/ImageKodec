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
import io.github.yuroyami.kiteimage.KiteBitmap
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

    fun bmp(w: Int, h: Int): ByteArray {
        // 24-bit BMP built by hand — BMP has no encoder yet, the format is trivial.
        val rowPad = (4 - (w * 3) % 4) % 4
        val size = 54 + (w * 3 + rowPad) * h
        val out = ByteArray(size)
        var o = 0
        fun u8(v: Int) { out[o++] = v.toByte() }
        fun u16(v: Int) { u8(v); u8(v shr 8) }
        fun u32(v: Int) { u16(v); u16(v shr 16) }
        u8('B'.code); u8('M'.code); u32(size); u32(0); u32(54)
        u32(40); u32(w); u32(h); u16(1); u16(24); u32(0); u32(0); u32(0); u32(0); u32(0); u32(0)
        for (y in h - 1 downTo 0) {
            for (x in 0 until w) {
                u8((x * 255 / (w - 1))); u8(128); u8((y * 255 / (h - 1)))
            }
            repeat(rowPad) { u8(0) }
        }
        return out
    }

    return listOf(
        Tile("GIF — animated,\ndecoded + played by KiteImage", SAMPLE_GIF),
        Tile("JPEG 4:2:0 (ffmpeg-encoded)", SAMPLE_JPEG),
        Tile("JPEG — OUR encoder q85", KiteImage.encodeJpeg(card(96, 96, alpha = false), quality = 85)),
        Tile("PNG — OUR encoder, alpha", KiteImage.encodePng(card(96, 96, alpha = true))),
        Tile("JPEG 2000 (absorbed JPX codec)", SAMPLE_JP2),
        Tile("TIFF deflate (ffmpeg-encoded)", SAMPLE_TIFF),
        Tile("BMP 24-bit", bmp(96, 96)),
    )
}

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "KiteImage sample — every pixel pure Kotlin") {
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
    }
}
