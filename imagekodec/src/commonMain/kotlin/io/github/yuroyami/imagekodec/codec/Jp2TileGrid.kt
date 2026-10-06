package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.imagekodec.ImageDecodeException

/** T.800 B.3, equations B-3 through B-5; shared by probe and decode. */
internal class Jp2TileGrid(
    xsiz: Long, ysiz: Long, xosiz: Long, yosiz: Long,
    xtsiz: Long, ytsiz: Long, xtosiz: Long, ytosiz: Long,
) {
    val width: Int
    val height: Int
    val count: Int

    init {
        fun bad(message: String): Nothing = throw ImageDecodeException("JPEG 2000 tile grid: $message")
        if (xsiz <= xosiz || ysiz <= yosiz) bad("image extent must exceed XOsiz and YOsiz")
        if (xtsiz == 0L || ytsiz == 0L) bad("XTsiz and YTsiz must be positive")
        if (xtosiz > xosiz || ytosiz > yosiz) bad("XTOsiz or YTOsiz exceeds the image origin")
        if (xtosiz + xtsiz <= xosiz || ytosiz + ytsiz <= yosiz) bad("first tile does not overlap the image")
        val w = (xsiz - xtosiz + xtsiz - 1) / xtsiz
        val h = (ysiz - ytosiz + ytsiz - 1) / ytsiz
        // A.4.2 reserves tile index 65535; no valid grid needs a larger loop.
        if (w > 65535 || h > 65535 || w * h > 65535) bad("more than 65535 tiles")
        width = w.toInt()
        height = h.toInt()
        count = (w * h).toInt()
    }
}
