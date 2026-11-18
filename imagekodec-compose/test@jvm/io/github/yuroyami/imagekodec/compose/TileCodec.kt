package io.github.yuroyami.imagekodec.compose

import io.github.yuroyami.imagekodec.ExperimentalHeifCodecApi
import io.github.yuroyami.imagekodec.HeifBuilder
import io.github.yuroyami.imagekodec.HeifCodec
import io.github.yuroyami.imagekodec.HeifCodecInfo
import io.github.yuroyami.imagekodec.HeifCodecSession
import io.github.yuroyami.imagekodec.HeifPlanes
import java.util.concurrent.atomic.AtomicInteger

/**
 * A HEIF codec for tests, registered with `ImageKodec.registerHeifCodec` in place of the HEVC codec of
 * `imagekodec-heic`, which the bindings do not depend on (#170). Its items are monochrome tiles of
 * 8 bits whose size the `tstC` property gives and whose one sample is a tile number; every sample is
 * a hash of its position and that number. [decoded] counts the tiles it decodes, and [onTile] runs
 * after the cancellation check of each one, so a test can hold a decode in the middle.
 */
@OptIn(ExperimentalHeifCodecApi::class)
internal class TileCodec(override val itemType: String) : HeifCodec {
    override val sampleEntryTypes: Set<String> = emptySet()
    override val configurationType = "tstC"

    val decoded = AtomicInteger()

    @Volatile
    var onTile: (() -> Unit)? = null

    private fun size(configuration: ByteArray?): Pair<Int, Int> {
        val b = configuration!!.map { it.toInt() and 0xFF }
        return ((b[0] shl 8) or b[1]) to ((b[2] shl 8) or b[3])
    }

    override fun inspect(configuration: ByteArray?, data: ByteArray?): HeifCodecInfo {
        val (w, h) = size(configuration)
        return HeifCodecInfo(w, h, 8, 8, 0, fullRange = true)
    }

    override fun open(configuration: ByteArray?): HeifCodecSession = object : HeifCodecSession {
        override fun decode(sample: ByteArray, cancellationCheck: (() -> Unit)?): HeifPlanes {
            cancellationCheck?.invoke()
            onTile?.invoke()
            decoded.incrementAndGet()
            val (w, h) = size(configuration)
            val id = ((sample[0].toInt() and 0xFF) shl 8) or (sample[1].toInt() and 0xFF)
            val plane = ShortArray(w * h) { i ->
                var v = ((i % w) * 0x9E3779B1.toInt()) xor ((i / w) * 0x85EBCA77.toInt()) xor (id * 0xC2B2AE3D.toInt())
                v = (v xor (v ushr 15)) * 0x2C1B3C6D
                ((v xor (v ushr 12)) and 0xFF).toShort()
            }
            return HeifPlanes(w, h, 8, 8, 0, 0, arrayOf(plane), intArrayOf(w), fullRange = true)
        }
    }

    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun box(type: String, payload: ByteArray) = be32(8 + payload.size) + type.encodeToByteArray() + payload

    /**
     * A HEIC file of a [rows] by [columns] grid of [tileWidth] by [tileHeight] tiles, cut 5 columns and
     * 3 rows short of them. [padding] bytes of a property no reader needs make its `meta` box larger.
     */
    fun grid(rows: Int, columns: Int, tileWidth: Int, tileHeight: Int, padding: Int = 0): ByteArray {
        val width = columns * tileWidth - 5
        val height = rows * tileHeight - 3
        val count = rows * columns
        val ispe = box("ispe", ByteArray(4) + be32(width) + be32(height)) to false
        val pad = if (padding > 0) listOf(box("free", ByteArray(padding)) to false) else emptyList()
        val tile = listOf(
            box(configurationType, be16(tileWidth) + be16(tileHeight)) to true,
            box("ispe", ByteArray(4) + be32(tileWidth) + be32(tileHeight)) to false,
        )
        return HeifBuilder(major = "heic", brands = listOf("mif1", "heic")).apply {
            add(1, "grid", byteArrayOf(0, 0, (rows - 1).toByte(), (columns - 1).toByte()) + be16(width) + be16(height), listOf(ispe) + pad)
            for (i in 0 until count) add(2 + i, itemType, be16(i), tile, hidden = true)
            references += Triple("dimg", 1L, LongArray(count) { it + 2L })
        }.build()
    }
}
