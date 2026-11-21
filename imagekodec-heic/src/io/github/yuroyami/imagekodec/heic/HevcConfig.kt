package io.github.yuroyami.imagekodec.heic

/**
 * The HEVCDecoderConfigurationRecord of an `hvcC` box (ISO/IEC 14496-15, 8.3.2.1.2): the size of
 * the NAL unit lengths in a sample, the fixed fields that describe the stream, and the NAL units
 * of its arrays, usually the VPS, SPS and PPS.
 */
internal class HevcConfig(
    val lengthSize: Int,
    val chromaFormat: Int,
    val bitDepthLuma: Int,
    val bitDepthChroma: Int,
    val nalUnits: List<ByteArray>,
) {
    companion object {
        /** The 23 bytes before the arrays. */
        private const val HEADER = 23

        /** Reads the payload [b] of an `hvcC` box, without its box header. */
        fun parse(b: ByteArray): HevcConfig {
            if (b.size < HEADER) hevcError("hvcC of ${b.size} bytes is cut off")
            // lengthSizeMinusOne: 14496-15 allows 0, 1 and 3, so lengths of 1, 2 or 4 bytes.
            val lengthSizeMinusOne = b[21].toInt() and 3
            if (lengthSizeMinusOne == 2) hevcError("hvcC lengthSizeMinusOne 2 is not allowed")
            val units = ArrayList<ByteArray>()
            var p = HEADER
            repeat(b[22].toInt() and 0xFF) {
                if (b.size - p < 3) hevcError("hvcC is cut off in its arrays")
                val count = u16(b, p + 1)
                p += 3
                repeat(count) {
                    if (b.size - p < 2) hevcError("hvcC is cut off in its arrays")
                    val length = u16(b, p)
                    p += 2
                    if (length > b.size - p) hevcError("a NAL unit of $length bytes passes the end of hvcC")
                    if (length < 2) hevcError("a NAL unit of $length bytes in hvcC has no header")
                    units += b.copyOfRange(p, p + length)
                    p += length
                }
            }
            return HevcConfig(
                lengthSize = lengthSizeMinusOne + 1,
                chromaFormat = b[16].toInt() and 3,
                bitDepthLuma = (b[17].toInt() and 7) + 8,
                bitDepthChroma = (b[18].toInt() and 7) + 8,
                nalUnits = units,
            )
        }

        private fun u16(b: ByteArray, p: Int) = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
    }
}
