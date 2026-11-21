package io.github.yuroyami.imagekodec

/** How [ImageKodec.encodeTiff] compresses each strip or tile (TIFF 6.0 Compression, tag 259). */
public enum class TiffCompression {
    /** Compression 1: the samples as they are. Every reader takes it. */
    None,

    /** Compression 32773: Apple's byte run-length coding, each row packed alone (TIFF 6.0 section 9). */
    PackBits,

    /** Compression 5: LZW with TIFF's early change of code width (TIFF 6.0 section 13). */
    Lzw,

    /** Compression 8: zlib's Deflate, as the TIFF Technical Notes define Adobe Deflate. The default. */
    Deflate,

    /**
     * Compression 4: CCITT Group 4 (ITU-T T.6) for a bi-level page, as scanners and fax software
     * write documents. Each strip or tile is coded alone and ends with EOFB.
     */
    CcittG4,

    /**
     * Compression 3: CCITT Group 3 one-dimensional (ITU-T T.4 Modified Huffman) for a bi-level
     * page, each row after an EOL, with T4Options 0.
     */
    CcittG3,

    /**
     * Compression 7: baseline JPEG as TIFF Technical Note 2 defines it, for 8-bit gray or colour
     * without alpha. The quantization and Huffman tables sit once in JPEGTables, and each strip or
     * tile is an abbreviated JPEG stream. Colour is stored as YCbCr (photometric 6). Lossy.
     */
    Jpeg,
}

/** The kind of samples [ImageKodec.encodeTiff] writes (TIFF 6.0 PhotometricInterpretation, tag 262). */
public enum class TiffPhotometric {
    /**
     * Gray (photometric 1) when every pixel is gray, RGB (photometric 2) otherwise, as the calls
     * without options write; bi-level with a CCITT compression.
     */
    Auto,

    /**
     * One bit a pixel, WhiteIsZero (photometric 0) as fax files are. A pixel is black when its luma
     * (BT.601 weights), laid over white by its alpha, is below half, as [ImageKodec.encodeCcitt]
     * decides, so this is lossy for anything but a black and white page.
     */
    BiLevel,

    /**
     * One sample a pixel, BlackIsZero (photometric 1). A pixel that is not gray is stored as its
     * luma, `(77 R + 150 G + 29 B + 128) / 256` (BT.601 weights in eighths of a level), so a gray
     * page is kept exactly.
     */
    Gray,

    /**
     * A palette (photometric 3) of the page's colours in the order they first appear, at 1, 2, 4
     * or 8 bits a pixel, the fewest that hold them. Lossless; needs an opaque page of at most 256
     * colours.
     */
    Palette,

    /** Red, green and blue (photometric 2), even for a gray page. */
    Rgb,
}

/**
 * The settings of a TIFF encode (#184), for [ImageKodec.encodeTiff], [ImageKodec.encodeTiff16] and
 * [ImageKodec.encodeTiffCmyk]. The defaults give the bytes the calls without options give.
 *
 * Compression, photometric and depth must fit together: the CCITT compressions take a bi-level
 * page only (and [TiffPhotometric.Auto] then means bi-level), [TiffCompression.Jpeg] takes 8-bit
 * gray or colour without alpha, and a palette or bi-level page takes 8-bit pixels. A combination
 * that does not fit throws [IllegalArgumentException] from the encode, naming it.
 *
 * @property compression how each strip or tile is compressed.
 * @property photometric the kind of samples written.
 * @property predictor difference each sample from the one to its left first (TIFF Predictor 2),
 *   which makes LZW and Deflate smaller on photographs. Used with [TiffCompression.Lzw] and
 *   [TiffCompression.Deflate] on samples of 8 or 16 bits, and never on palette indices; ignored
 *   otherwise.
 * @property quality 0 to 100, higher is better: the JPEG quality, as [ImageKodec.encodeJpeg] reads
 *   it (0 is read as 1). From 91 up the chroma is kept whole (YCbCr 4:4:4), below it is halved
 *   both ways (4:2:0). Used by [TiffCompression.Jpeg] only.
 * @property rowsPerStrip the rows in each strip, or 0 for strips of about 8 KiB of samples, as the
 *   calls without options write. A JPEG strip that is not the last must hold a whole number of
 *   rows of blocks: a multiple of 16 rows at 4:2:0 and of 8 at 4:4:4.
 * @property tileWidth the width of each tile, a positive multiple of 16, or 0 to write strips.
 * @property tileLength the height of each tile, a positive multiple of 16, or 0 to write strips.
 *   The tiles at the right and bottom edges are written whole, padded with zero samples (or, under
 *   JPEG, with the edge pixels).
 * @throws IllegalArgumentException if a value is out of its range, or tiles are asked for together
 *   with [rowsPerStrip]
 */
public class TiffEncodeOptions @Throws(IllegalArgumentException::class) public constructor(
    public val compression: TiffCompression = TiffCompression.Deflate,
    public val photometric: TiffPhotometric = TiffPhotometric.Auto,
    public val predictor: Boolean = true,
    public val quality: Int = 90,
    public val rowsPerStrip: Int = 0,
    public val tileWidth: Int = 0,
    public val tileLength: Int = 0,
) {
    init {
        require(quality in 0..100) { "TIFF: quality must be 0 to 100, was $quality" }
        require(rowsPerStrip >= 0) { "TIFF: rowsPerStrip must not be negative, was $rowsPerStrip" }
        require(tileWidth >= 0 && tileWidth % 16 == 0) { "TIFF: tileWidth must be a multiple of 16, was $tileWidth" }
        require(tileLength >= 0 && tileLength % 16 == 0) { "TIFF: tileLength must be a multiple of 16, was $tileLength" }
        require((tileWidth == 0) == (tileLength == 0)) {
            "TIFF: tileWidth and tileLength must both be 0 (strips) or both be set, were $tileWidth and $tileLength"
        }
        require(tileWidth == 0 || rowsPerStrip == 0) { "TIFF: rowsPerStrip is for strips, but tiles of ${tileWidth}x$tileLength were asked for" }
    }

    /** Whether the page is written in tiles rather than strips. */
    public val tiled: Boolean get() = tileWidth > 0

    override fun toString(): String =
        "TiffEncodeOptions(compression=$compression, photometric=$photometric, predictor=$predictor, quality=$quality, " +
            "rowsPerStrip=$rowsPerStrip, tileWidth=$tileWidth, tileLength=$tileLength)"
}
