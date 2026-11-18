package io.github.yuroyami.imagekodec

/** The chroma subsampling of a JPEG that [ImageKodec.encodeJpeg] writes in YCbCr. */
public enum class JpegSubsampling {
    /**
     * The encoder's own rule: [JpegEncoderKind.Fast] writes 4:2:0 at quality 90 or less and 4:4:4
     * above, [JpegEncoderKind.Jpegli] 4:2:0 at every quality, and a lossless file 4:4:4.
     */
    Auto,

    /** Every component at full size. */
    S444,

    /** Cb and Cr at half the width and the full height. */
    S422,

    /** Cb and Cr at half the width and half the height. */
    S420,
}

/** Whether [ImageKodec.encodeJpeg] writes one component, the gray level, or three. */
public enum class JpegGrayscale {
    /** One component when every pixel is gray (red, green and blue equal), three otherwise. */
    Auto,

    /** One component, the luma of each pixel by the weights of JFIF (0.299, 0.587, 0.114). */
    Always,

    /** Three components, even for a gray picture. */
    Never,
}

/**
 * How [ImageKodec.encodeJpeg] and [ImageKodec.encodeJpeg16] write a JPEG (#183): the process of
 * ITU-T T.81, the entropy coding, the components and their sampling. Every parameter has a
 * default, and the defaults write what [ImageKodec.encodeJpeg] writes from a quality alone: an
 * 8-bit baseline file with the example Huffman tables of T.81 Annex K. The one change is
 * [grayscale], which by default writes a gray picture as one component.
 *
 * The processes: a sequential DCT file (baseline SOF0 at 8 bits, extended SOF1 at 12), a
 * [progressive] one (SOF2), the arithmetic-coded forms of both (SOF9, SOF10), and a [lossless]
 * one (SOF3, or SOF11 with [arithmetic]). Hierarchical files are not written.
 *
 * @property quality 0 to 100, higher is better, as each [encoder] maps it to its tables. 0 writes
 *   what 1 writes, as libjpeg reads 0. A lossless file ignores it.
 * @property subsampling the chroma sampling of a three-component DCT file. A lossless file takes
 *   [JpegSubsampling.Auto] or [JpegSubsampling.S444] only, and [JpegEncoderKind.Jpegli]
 *   [JpegSubsampling.Auto] or [JpegSubsampling.S420] only, its matrices being tuned for 4:2:0.
 * @property progressive a progressive file by the scan script libjpeg uses by default: a first
 *   DC scan of every component at half precision, the AC coefficients of each component in bands
 *   by spectral selection and two steps of successive approximation, then the refinements. Its
 *   Huffman tables are always built for the file, since the example ones have no end-of-band
 *   runs.
 * @property optimizeHuffman Huffman tables built from the file's own symbol counts (T.81 K.2,
 *   limited to 16 bits by K.3), in a second pass, in place of the example tables: smaller and
 *   slower. A 12-bit or lossless Huffman file always gets them, since the example tables do not
 *   hold its larger categories. Not with [arithmetic], which has no Huffman tables.
 * @property arithmetic arithmetic coding by T.81 Annex D's QM coder with the models of F.1.4,
 *   G.1.3 and H.1.2.3, in place of Huffman coding: commonly a tenth smaller than Huffman coding
 *   with tables built for the file, and read by fewer programs (no browser reads it).
 * @property arithmeticDcLower the lower conditioning bound L of the DAC segment, 0 to 15, for the
 *   DC coefficients or the lossless differences (T.81 F.1.4.4.1.2). The default is T.81's.
 * @property arithmeticDcUpper the upper bound U, [arithmeticDcLower] to 15.
 * @property arithmeticAcThreshold the AC conditioning Kx, 1 to 63 (T.81 F.1.4.4.2). The three
 *   are written in a DAC segment, and set only with [arithmetic].
 * @property grayscale one component or three; see [JpegGrayscale].
 * @property restartInterval the number of MCUs between restart markers, 0 for none, up to 65535.
 *   A lossless interval must hold whole rows of MCUs, as T.81 H.1.1 asks and libjpeg-turbo
 *   requires, which the encode checks against the width.
 * @property encoder which encoder makes the coefficients. [JpegEncoderKind.Jpegli] honours every
 *   option of an 8-bit DCT file: its progressive, arithmetic and optimized files hold the
 *   coefficients of its baseline file, and its gray file is the luma of its colour one.
 * @property precision the bits a sample: 8, or 12 for a DCT file, which only
 *   [ImageKodec.encodeJpeg16] writes; 2 to 16 for a lossless file. Each sample is the top
 *   [precision] bits of the 16-bit sample, which [ImageKodec.decode16] widens back.
 * @property lossless a lossless file of T.81 Annex H: the difference of each sample from the
 *   prediction of [predictor], so [ImageKodec.decode16] returns every bit. Three components are
 *   stored as RGB, with no colour transform, and none is subsampled.
 * @property predictor the lossless predictor of T.81 Table H.1, 1 to 7: 1 the sample to the left,
 *   2 above, 3 above left, 4 left + above - above left, 5 and 6 halfway forms of 4, and 7 the
 *   mean of left and above. Set only with [lossless].
 * @property pointTransform the low bits a lossless file drops, 0 to [precision] - 1: they come
 *   back as zeros. Set only with [lossless].
 * @throws IllegalArgumentException if a value is outside its range, or two options exclude each
 *   other, each named in the message
 */
public class JpegEncodeOptions @Throws(IllegalArgumentException::class) public constructor(
    public val quality: Int = 90,
    public val subsampling: JpegSubsampling = JpegSubsampling.Auto,
    public val progressive: Boolean = false,
    public val optimizeHuffman: Boolean = false,
    public val arithmetic: Boolean = false,
    public val arithmeticDcLower: Int = 0,
    public val arithmeticDcUpper: Int = 1,
    public val arithmeticAcThreshold: Int = 5,
    public val grayscale: JpegGrayscale = JpegGrayscale.Auto,
    public val restartInterval: Int = 0,
    public val encoder: JpegEncoderKind = JpegEncoderKind.Fast,
    public val precision: Int = 8,
    public val lossless: Boolean = false,
    public val predictor: Int = 1,
    public val pointTransform: Int = 0,
) {
    init {
        require(quality in 0..100) { "quality must be 0 to 100, was $quality" }
        require(restartInterval in 0..65535) { "restartInterval must be 0 to 65535, was $restartInterval" }
        require(arithmeticDcLower in 0..15) { "arithmeticDcLower must be 0 to 15, was $arithmeticDcLower" }
        require(arithmeticDcUpper in arithmeticDcLower..15) {
            "arithmeticDcUpper must be arithmeticDcLower ($arithmeticDcLower) to 15, was $arithmeticDcUpper"
        }
        require(arithmeticAcThreshold in 1..63) { "arithmeticAcThreshold must be 1 to 63, was $arithmeticAcThreshold" }
        require(arithmetic || (arithmeticDcLower == 0 && arithmeticDcUpper == 1 && arithmeticAcThreshold == 5)) {
            "arithmeticDcLower, arithmeticDcUpper and arithmeticAcThreshold apply to arithmetic coding, and arithmetic is false"
        }
        require(!(arithmetic && optimizeHuffman)) { "optimizeHuffman applies to Huffman coding, and arithmetic is true" }
        if (lossless) {
            require(precision in 2..16) { "a lossless precision must be 2 to 16, was $precision" }
            require(!progressive) { "a lossless file is sequential, so progressive must be false" }
            require(subsampling == JpegSubsampling.Auto || subsampling == JpegSubsampling.S444) {
                "a lossless file is not subsampled, so subsampling must be Auto or S444, was $subsampling"
            }
            require(predictor in 1..7) { "predictor must be 1 to 7, was $predictor" }
            require(pointTransform in 0 until precision) { "pointTransform must be 0 to ${precision - 1}, was $pointTransform" }
        } else {
            require(precision == 8 || precision == 12) { "a DCT precision must be 8 or 12, was $precision" }
            require(predictor == 1) { "predictor applies to a lossless file, and lossless is false" }
            require(pointTransform == 0) { "pointTransform applies to a lossless file, and lossless is false" }
        }
        if (encoder == JpegEncoderKind.Jpegli) {
            require(!lossless && precision == 8) { "the Jpegli encoder writes 8-bit DCT files only, not lossless or precision $precision" }
            require(subsampling == JpegSubsampling.Auto || subsampling == JpegSubsampling.S420) {
                "the Jpegli encoder writes 4:2:0 only, so subsampling must be Auto or S420, was $subsampling"
            }
        }
    }

    override fun toString(): String = "JpegEncodeOptions(quality=$quality, subsampling=$subsampling, progressive=$progressive, " +
        "optimizeHuffman=$optimizeHuffman, arithmetic=$arithmetic, arithmeticDcLower=$arithmeticDcLower, " +
        "arithmeticDcUpper=$arithmeticDcUpper, arithmeticAcThreshold=$arithmeticAcThreshold, grayscale=$grayscale, " +
        "restartInterval=$restartInterval, encoder=$encoder, precision=$precision, lossless=$lossless, " +
        "predictor=$predictor, pointTransform=$pointTransform)"
}
