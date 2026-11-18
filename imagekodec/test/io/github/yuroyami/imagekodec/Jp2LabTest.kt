package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The CIELab colour space of a JP2 header (#187) on every target: its white and black land on
 * sRGB's, a neutral axis stays gray, and a fixed file converts to the same samples everywhere,
 * which a committed hash holds. `Jp2LabOracleTest` holds the conversion to lcms2.
 */
class Jp2LabTest {

    /** [file] with its colr box's enumerated colour space set to CIELab (14), and [parameters] after it when given. */
    private fun asLab(file: ByteArray, parameters: LongArray?): ByteArray {
        fun find(type: String): Int {
            for (i in 0..file.size - 4) if (file.copyOfRange(i, i + 4).decodeToString() == type) return i - 4
            error("no $type box")
        }
        fun u32(v: Long) = ByteArray(4) { (v ushr (24 - 8 * it)).toByte() }
        fun read(at: Int) = (0 until 4).fold(0L) { acc, k -> (acc shl 8) or (file[at + k].toLong() and 0xFF) }
        val extra = if (parameters == null) 0 else 28
        val jp2h = find("jp2h")
        val colr = find("colr")
        return file.copyOfRange(0, jp2h) + u32(read(jp2h) + extra) + file.copyOfRange(jp2h + 4, colr) +
            u32(15L + extra) + file.copyOfRange(colr + 4, colr + 11) + u32(14) +
            (parameters?.fold(ByteArray(0)) { acc, v -> acc + u32(v) } ?: ByteArray(0)) + file.copyOfRange(colr + 15, file.size)
    }

    private fun fnv(bytes: ByteArray): Int {
        var h = 0x811C9DC5.toInt()
        for (b in bytes) h = (h xor (b.toInt() and 0xFF)) * 16777619
        return h
    }

    /** Samples L, a, b of 8 bits: white, black, the neutral axis, then colours across a* and b*. */
    private val samples: List<IntArray> = buildList {
        add(intArrayOf(255, 128, 96))
        add(intArrayOf(0, 128, 96))
        for (l in 0..255 step 51) add(intArrayOf(l, 128, 96))
        for (a in 0..255 step 32) for (b in 0..255 step 32) add(intArrayOf(150, a, b))
    }

    private fun file(parameters: LongArray?): ByteArray {
        val w = samples.size
        val bitmap = KiteBitmap(w, 1, IntArray(w) { i -> samples[i].let { (0xFF shl 24) or (it[0] shl 16) or (it[1] shl 8) or it[2] } })
        return asLab(ImageKodec.encodeJp2(bitmap, Jp2EncodeOptions(lossless = true, levels = 1)), parameters)
    }

    @Test
    fun whiteBlackAndGrayLandOnSrgbs() {
        val file = file(null)
        assertTrue(ImageKodec.probe(file).isDecodable)
        val argb = ImageKodec.decode(file).argb
        assertEquals(0xFFFFFFFF.toInt(), argb[0], "L* 100 is white")
        assertEquals(0xFF000000.toInt(), argb[1], "L* 0 is black")
        for (i in 2 until 8) {
            val p = argb[i]
            val r = (p ushr 16) and 0xFF
            assertEquals(r, (p ushr 8) and 0xFF, "neutral sample $i")
            assertEquals(r, p and 0xFF, "neutral sample $i")
        }
    }

    @Test
    fun aFixedLabFileConvertsAlikeOnEveryTarget() {
        val hashes = listOf(null, longArrayOf(100, 16, 255, 128, 220, 128, 0x00443635)).map { parameters ->
            val file = file(parameters)
            val narrow = ImageKodec.decode(file).argb
            val wide = ImageKodec.decode16(file).samples
            val bytes = ByteArray(narrow.size * 4 + wide.size * 2)
            for (i in narrow.indices) for (k in 0 until 4) bytes[i * 4 + k] = (narrow[i] ushr (24 - 8 * k)).toByte()
            for (i in wide.indices) { bytes[narrow.size * 4 + 2 * i] = (wide[i].toInt() shr 8).toByte(); bytes[narrow.size * 4 + 2 * i + 1] = wide[i].toByte() }
            fnv(bytes).toUInt().toString(16)
        }
        assertEquals(EXPECTED, hashes, hashes.joinToString())
    }

    private companion object {
        /** The JVM's decode, which every other target must give sample for sample. */
        val EXPECTED = listOf("e576bad9", "51791989")
    }
}
