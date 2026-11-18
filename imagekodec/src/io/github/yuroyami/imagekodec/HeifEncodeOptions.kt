package io.github.yuroyami.imagekodec

/**
 * How the chroma of a HEIF picture is stored: at half the luma's width and height ([Yuv420]), at half
 * its width ([Yuv422]), or at full size ([Yuv444]). A gray picture stores no chroma at all, whatever
 * this says.
 */
public enum class HeifChroma {
    /** Chroma at half the width and half the height, as cameras and phones write it. */
    Yuv420,

    /** Chroma at half the width. */
    Yuv422,

    /** Chroma at full size: sharper colour edges, larger files. */
    Yuv444,
}

/**
 * The settings of a HEIF encode (#174), for [ImageKodec.encodeHeic], [ImageKodec.encodeHeic16],
 * [ImageKodec.encodeAvif] and [ImageKodec.encodeAvif16] (#176).
 * Every parameter has a default, so name only the ones to change:
 *
 * ```kotlin
 * val heic = ImageKodec.encodeHeic(bitmap, HeifEncodeOptions(quality = 80, chroma = HeifChroma.Yuv444))
 * ```
 *
 * @property quality 0 to 100, higher is better and larger. The HEVC encoder codes at a QP of 51 at 0,
 *   26 at 50 and 1 at 100, straight between; 50 sits next to x265's default visual quality. The AV1 encoder
 *   codes at a base_q_idx of 240 at 0, 117 at 50, 43 at 80 and 1 from 97 on, straight between, so that 50 and
 *   80 give about the SSIMULACRA 2 score of libavif's `avifenc -q 50` and `-q 80`.
 * @property lossless codes the picture so that it decodes to exactly the input: RGB through the identity
 *   matrix of ITU-T H.273 in 4:4:4, or gray in 4:0:0, with [quality] and [chroma] ignored. A 16-bit
 *   input keeps exactly the samples a picture of [bitDepth] bits can give back through
 *   [ImageKodec.decode16]; other values move to the nearest of those.
 * @property chroma the chroma subsampling of a lossy colour picture. The default, 4:2:0, averages each
 *   2 by 2 block of chroma, centred where the decoder's upsampling expects it.
 * @property bitDepth the bits of each coded sample: 8, 10 or 12, or null for 8 from a [KiteBitmap] and
 *   for 10 lossy or 12 lossless from a [KiteBitmap16].
 * @property effort 1 (fast) to 3 (thorough). 1 tests few intra modes and no transform skip, 2 adds 64 by
 *   64 coding units, one level of transform split and rate-distortion optimised quantization, and 3 tests
 *   every mode and two levels of transform split with no early stops.
 *   In AV1, 1 tries one mode a block, square partitions down to 8 by 8 and two transform types; 2 adds
 *   angle deltas, filter intra, a transform split, more transform types, a level search and loop
 *   restoration; 3 adds 4 by 4 blocks, rectangular partitions, a second split and every transform type.
 * @property tileSize the side of the square tiles of a grid, a multiple of 8 from 64 to 8192: a picture
 *   wider or taller than this is written as a `grid` of tiles of this size, which the tile runner of
 *   [ImageKodec.setTileRunner] encodes, and the edge tiles are padded by repeating the last row and
 *   column. 512 is what phones write. Null writes one coded picture of any size the codec's levels hold.
 * @property thumbnailSize the longest side of a thumbnail item to write beside the image, scaled down
 *   from it, or null for none. A picture no larger than this gets no thumbnail.
 * @property orientation how to show the stored pixels, as `irot` and `imir` properties: a reader that
 *   applies them, as [ImageKodec.decode] with `applyOrientation` does, shows `bitmap.oriented(orientation)`.
 *   An EXIF block written with the image takes the same orientation, so the two never disagree.
 * @throws IllegalArgumentException when a value is out of its range
 */
public class HeifEncodeOptions @Throws(IllegalArgumentException::class) public constructor(
    public val quality: Int = 50,
    public val lossless: Boolean = false,
    public val chroma: HeifChroma = HeifChroma.Yuv420,
    public val bitDepth: Int? = null,
    public val effort: Int = 2,
    public val tileSize: Int? = 512,
    public val thumbnailSize: Int? = null,
    public val orientation: Orientation = Orientation.Normal,
) {
    init {
        require(quality in 0..100) { "HEIF quality $quality is not in 0..100" }
        require(bitDepth == null || bitDepth == 8 || bitDepth == 10 || bitDepth == 12) { "HEIF bit depth $bitDepth is not 8, 10 or 12" }
        require(effort in 1..3) { "HEIF effort $effort is not in 1..3" }
        require(tileSize == null || (tileSize in 64..8192 && tileSize % 8 == 0)) { "HEIF tile size $tileSize is not a multiple of 8 from 64 to 8192" }
        require(thumbnailSize == null || thumbnailSize in 1..8192) { "HEIF thumbnail size $thumbnailSize is not in 1..8192" }
    }

    override fun toString(): String =
        "HeifEncodeOptions(quality=$quality, lossless=$lossless, chroma=$chroma, bitDepth=$bitDepth, effort=$effort, " +
            "tileSize=$tileSize, thumbnailSize=$thumbnailSize, orientation=$orientation)"
}
