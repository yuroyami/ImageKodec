package io.github.yuroyami.imagekodec

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CgBI against ImageIO (#189). No installed tool reads or writes Apple's CgBI PNG, and
 * `pngcrush -revert-iphone-optimizations` is not packaged here, so the test does what that
 * command undoes, with the JDK alone: it reads a plain PNG of the photo-like scenes with
 * ImageIO, inflates its rows with `java.util.zip`, premultiplies them, stores them blue first,
 * deflates them raw, and puts a `CgBI` chunk in front. The decode must give ImageIO's pixels
 * within the steps that premultiplication leaves.
 */
class PngCgbiOracleTest {

    private fun chunk(type: String, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val t = type.toByteArray(Charsets.US_ASCII)
        out.write(java.nio.ByteBuffer.allocate(4).putInt(body.size).array())
        out.write(t)
        out.write(body)
        val crc = CRC32().apply { update(t); update(body) }
        out.write(java.nio.ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
        return out.toByteArray()
    }

    /** [png], an 8-bit RGBA file without interlace, rewritten as Xcode writes it. */
    private fun toCgbi(png: ByteArray, width: Int, height: Int): ByteArray {
        val idat = ByteArrayOutputStream()
        var at = 8
        var ihdr = ByteArray(0)
        while (at < png.size) {
            val n = java.nio.ByteBuffer.wrap(png, at, 4).int
            val type = String(png, at + 4, 4, Charsets.US_ASCII)
            if (type == "IHDR") ihdr = png.copyOfRange(at + 8, at + 8 + n)
            if (type == "IDAT") idat.write(png, at + 8, n)
            at += 12 + n
        }
        assertEquals(8, ihdr[8].toInt()); assertEquals(6, ihdr[9].toInt()); assertEquals(0, ihdr[12].toInt())
        val stride = 1 + 4 * width
        val rows = ByteArray(stride * height)
        Inflater().apply { setInput(idat.toByteArray()); assertEquals(rows.size, inflate(rows)); end() }
        // Undo the filters with the PNG specification's own definitions, then store unfiltered.
        val raw = ByteArray(rows.size)
        for (y in 0 until height) {
            raw[y * stride] = 0
            for (i in 1 until stride) {
                val x = rows[y * stride + i].toInt() and 0xFF
                val a = if (i > 4) raw[y * stride + i - 4].toInt() and 0xFF else 0
                val b = if (y > 0) raw[(y - 1) * stride + i].toInt() and 0xFF else 0
                val c = if (y > 0 && i > 4) raw[(y - 1) * stride + i - 4].toInt() and 0xFF else 0
                val v = when (rows[y * stride].toInt()) {
                    0 -> x
                    1 -> x + a
                    2 -> x + b
                    3 -> x + (a + b) / 2
                    else -> {
                        val p = a + b - c
                        val pa = abs(p - a); val pb = abs(p - b); val pc = abs(p - c)
                        x + if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                    }
                }
                raw[y * stride + i] = v.toByte()
            }
        }
        for (y in 0 until height) for (x in 0 until width) {
            val p = y * stride + 1 + 4 * x
            val alpha = raw[p + 3].toInt() and 0xFF
            fun pre(i: Int) = (((raw[p + i].toInt() and 0xFF) * alpha + 127) / 255).toByte()
            val r = pre(0); val g = pre(1); val b = pre(2)
            raw[p] = b; raw[p + 1] = g; raw[p + 2] = r
        }
        val deflater = Deflater(9, true).apply { setInput(raw); finish() }
        val compressed = ByteArrayOutputStream()
        val buf = ByteArray(65536)
        while (!deflater.finished()) compressed.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return png.copyOf(8) + chunk("CgBI", byteArrayOf(0x50, 0, 0x20, 6)) + chunk("IHDR", ihdr) +
            chunk("IDAT", compressed.toByteArray()) + chunk("IEND", ByteArray(0))
    }

    @Test
    fun cgbiOfEveryPhotoSceneMatchesImageIoOnTheSourcePng() {
        var worstOpaque = 0
        var compared = 0
        for (picture in PhotoScenes.all()) {
            val scene = picture.bitmap
            // An alpha ramp across the picture, so every alpha from 0 to 255 is premultiplied,
            // and an opaque band at the right.
            val px = IntArray(scene.argb.size) { i ->
                val a = minOf(255, (i % scene.width) * 300 / (scene.width - 1))
                (a shl 24) or (scene.argb[i] and 0xFFFFFF)
            }
            val png = ImageKodec.encodePng(KiteBitmap(scene.width, scene.height, px))
            val reference = ImageIO.read(ByteArrayInputStream(png))
            val ours = ImageKodec.decode(toCgbi(png, scene.width, scene.height))
            for (y in 0 until scene.height) for (x in 0 until scene.width) {
                val want = reference.getRGB(x, y)
                val got = ours[x, y]
                val a = want ushr 24
                assertEquals(a, got ushr 24)
                for (shift in intArrayOf(16, 8, 0)) {
                    val w = if (a == 0) 0 else (want shr shift) and 0xFF
                    val d = abs(w - ((got shr shift) and 0xFF))
                    val bound = if (a == 0) 0 else (255 + a - 1) / a
                    assertTrue(d <= bound, "(${x}, $y) alpha $a channel $shift: $d past $bound")
                    if (a == 255) worstOpaque = maxOf(worstOpaque, d)
                }
                compared++
            }
        }
        println("CgBI against ImageIO: $compared pixels, worst opaque difference $worstOpaque")
        assertEquals(0, worstOpaque)
    }
}
