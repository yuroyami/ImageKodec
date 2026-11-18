package io.github.yuroyami.imagekodec.internal.color

/** Writes the small ICC profiles a decoder makes for a colour encoding that has no code points. */
internal object IccWriter {

    /**
     * An RGB display profile of ICC.1 version 4.3 of matrix and curves: [toD50], linear RGB to D50 XYZ
     * row by row, whose columns are the colorants, and one parametric curve for every channel,
     * [curve] holding a gamma alone (function type 0) or, with four more values a, b, c and d,
     * function type 3, the sRGB form.
     */
    fun matrixShaper(toD50: DoubleArray, curve: DoubleArray): ByteArray {
        val tags = ArrayList<Pair<String, ByteArray>>()
        fun xyz(x: Double, y: Double, z: Double) = text("XYZ ") + ByteArray(4) + fixed(x) + fixed(y) + fixed(z)
        val para = text("para") + ByteArray(4) + u16(if (curve.size == 1) 0 else 3) + ByteArray(2) +
            curve.fold(ByteArray(0)) { acc, v -> acc + fixed(v) }
        tags += "desc" to (text("mluc") + ByteArray(4) + u32(1) + u32(12) + text("enUS") + u32(22) + u32(28) +
            "ImageKodec".encodeToByteArray().fold(ByteArray(0)) { acc, b -> acc + byteArrayOf(0, b) } + ByteArray(2))
        tags += "wtpt" to xyz(ColorMath.D50[0], ColorMath.D50[1], ColorMath.D50[2])
        tags += "rXYZ" to xyz(toD50[0], toD50[3], toD50[6])
        tags += "gXYZ" to xyz(toD50[1], toD50[4], toD50[7])
        tags += "bXYZ" to xyz(toD50[2], toD50[5], toD50[8])
        tags += "rTRC" to para
        tags += "gTRC" to para
        tags += "bTRC" to para
        var at = 128 + 4 + 12 * tags.size
        val table = ArrayList<ByteArray>()
        val data = ArrayList<ByteArray>()
        for ((signature, body) in tags) {
            table += text(signature) + u32(at) + u32(body.size)
            val padded = body + ByteArray((4 - body.size % 4) % 4)
            data += padded
            at += padded.size
        }
        val header = u32(at) + ByteArray(4) + byteArrayOf(4, 0x30, 0, 0) + text("mntr") + text("RGB ") + text("XYZ ") +
            ByteArray(12) + text("acsp") + ByteArray(24) + u32(0) + fixed(ColorMath.D50[0]) + fixed(ColorMath.D50[1]) +
            fixed(ColorMath.D50[2]) + ByteArray(48)
        check(header.size == 128)
        return (listOf(header, u32(tags.size)) + table + data).fold(ByteArray(0)) { acc, b -> acc + b }
    }

    private fun text(s: String) = s.encodeToByteArray()
    private fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun u32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    /** An s15Fixed16Number, rounded. */
    private fun fixed(v: Double): ByteArray {
        val f = v * 65536.0
        return u32((if (f < 0) f - 0.5 else f + 0.5).toInt())
    }
}
