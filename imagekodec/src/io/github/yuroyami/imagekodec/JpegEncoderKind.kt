package io.github.yuroyami.imagekodec

/** Which encoder [ImageKodec.encodeJpeg] uses. */
public enum class JpegEncoderKind {
    /**
     * stb_image_write's encoder, the default: the standard tables scaled by the quality, the
     * example Huffman tables, and 4:2:0 at quality 90 or less, 4:4:4 above. Simple and fast.
     */
    Fast,

    /**
     * A port of Google's Jpegli (#133), with the settings its library gives by default: its own
     * quantization matrices scaled by the distance the quality maps to, adaptive quantization,
     * and 4:2:0 at every quality. A baseline YCbCr file that every reader opens, smaller than
     * [Fast]'s at the same quality, and slower to write.
     */
    Jpegli,
}
