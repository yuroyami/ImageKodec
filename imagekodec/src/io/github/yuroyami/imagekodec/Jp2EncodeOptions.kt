package io.github.yuroyami.imagekodec

/**
 * The order in which a JPEG 2000 codestream lays out its packets (T.800 B.12.1): by quality
 * layer, resolution, component and precinct, in the order of the letters. [LRCP] gives a file
 * that sharpens as it loads, [RLCP] and [RPCL] one that grows in size, [PCRL] one that fills in
 * by area, and [CPRL] one component after another.
 */
public enum class Jp2Progression {
    /** Layer, resolution, component, precinct: the default, quality first. */
    LRCP,

    /** Resolution, layer, component, precinct. */
    RLCP,

    /** Resolution, precinct, component, layer. */
    RPCL,

    /** Precinct, component, resolution, layer. */
    PCRL,

    /** Component, precinct, resolution, layer. */
    CPRL,
}

/**
 * How [ImageKodec.encodeJp2] writes a JPEG 2000 file (#180). Every parameter has a default, so name
 * only those to change. The defaults write a lossy JP2 file at [quality] 75: the 9/7 wavelet over
 * five resolution levels, one tile, code-blocks of 64 by 64, one quality layer in [Jp2Progression.LRCP]
 * order.
 *
 * @property quality 0 to 100, higher is better: the lossy target when [ratios] is empty. The encoder
 *   aims at a peak signal-to-noise ratio of `20 + 0.28 * quality` dB over all components, as
 *   `opj_compress -q` aims at the ratio it is given: 34 dB at 50, 41 dB at 75, 48 dB at 100. It
 *   truncates code-block passes after a rate and distortion sort (T.800 Annex J) until the
 *   estimated error reaches the target.
 * @property lossless true for the reversible 5/3 wavelet and colour transform with nothing
 *   truncated: the decode returns every sample exactly. [quality] is then not used.
 * @property ratios the compression ratio of each quality layer, raw size over coded size, the first
 *   layer the most compressed, as `opj_compress -r 40,20,10` takes them; each at least 1 and none
 *   larger than the one before. The raw size is width times height times components times bits a
 *   sample over 8. Empty for the [quality] target. With [lossless], they give the layers before a
 *   last one that holds everything.
 * @property layers the quality layers when [ratios] is empty, 1 to 100: the last meets [quality] or
 *   holds the lossless image, and each one before it is about half the size of the next.
 * @property codestreamOnly true for a bare codestream (J2K), as a PDF embeds one, without the JP2
 *   boxes; the metadata, the colour space and the channel definitions are then not written.
 * @property tileWidth the width of a tile, or 0 for one tile as wide as the image
 * @property tileHeight the height of a tile, or 0 for one tile as tall as the image
 * @property levels the wavelet decomposition levels, 0 to 32. A tile too small for them gets as many
 *   as its shorter side allows, so `ImageKodec.decodeReduced` can drop each one.
 * @property codeBlockWidth the width of a code-block, a power of two from 4 to 1024
 * @property codeBlockHeight the height of a code-block, a power of two from 4 to 1024; the two
 *   together at most 4096 samples
 * @property precinctSize the side of a precinct on each resolution, a power of two from 2 to 32768,
 *   or 0 for one precinct a resolution
 * @property progression the packet order
 * @property effort 1 to 3, higher is slower. 1 sizes the layers from the code-block bytes alone and
 *   quantizes more coarsely. 2 sizes each layer exactly, packet headers included. 3 does what 2
 *   does and, for a lossless tile of three or more components, also codes the tile without the
 *   colour transform and keeps the smaller.
 * @property bypass the selective arithmetic coding bypass (code-block style 0x01): the significance
 *   and refinement passes below the fourth bit plane are written raw, which is faster to decode
 * @property resetContexts reset the coding contexts after each pass (code-block style 0x02)
 * @property terminateEachPass end the arithmetic coder after each pass (code-block style 0x04)
 * @property verticalCausal vertically causal contexts (code-block style 0x08)
 * @property segmentationSymbols a segmentation symbol after each cleanup pass (code-block style 0x20)
 * @property sopMarkers an SOP marker before each packet, for resynchronisation
 * @property ephMarkers an EPH marker after each packet header
 * @property bitsPerSample the bit depth of each component, 1 to 16, or 0 for the bitmap's own: 8 for
 *   a [KiteBitmap], 16 for a [KiteBitmap16]. A sample is scaled to it and rounded, so a
 *   [KiteBitmap16] that [ImageKodec.decode16] read from a 12-bit file encodes losslessly at 12 bits.
 *   At most 8 for a [KiteBitmap].
 * @throws IllegalArgumentException if a parameter is out of the range given here, or [ratios] and a
 *   [layers] other than 1 disagree on the number of layers
 */
public class Jp2EncodeOptions @Throws(IllegalArgumentException::class) public constructor(
    public val quality: Int = 75,
    public val lossless: Boolean = false,
    public val ratios: List<Double> = emptyList(),
    public val layers: Int = 1,
    public val codestreamOnly: Boolean = false,
    public val tileWidth: Int = 0,
    public val tileHeight: Int = 0,
    public val levels: Int = 5,
    public val codeBlockWidth: Int = 64,
    public val codeBlockHeight: Int = 64,
    public val precinctSize: Int = 0,
    public val progression: Jp2Progression = Jp2Progression.LRCP,
    public val effort: Int = 2,
    public val bypass: Boolean = false,
    public val resetContexts: Boolean = false,
    public val terminateEachPass: Boolean = false,
    public val verticalCausal: Boolean = false,
    public val segmentationSymbols: Boolean = false,
    public val sopMarkers: Boolean = false,
    public val ephMarkers: Boolean = false,
    public val bitsPerSample: Int = 0,
) {
    init {
        require(quality in 0..100) { "quality must be 0 to 100, was $quality" }
        require(layers in 1..MAX_LAYERS) { "layers must be 1 to $MAX_LAYERS, was $layers" }
        require(ratios.size <= MAX_LAYERS) { "ratios must give at most $MAX_LAYERS layers, gave ${ratios.size}" }
        for ((i, r) in ratios.withIndex()) {
            require(r.isFinite() && r >= 1.0) { "ratios[$i] must be at least 1, was $r" }
            require(i == 0 || r <= ratios[i - 1]) { "ratios must not grow from one layer to the next: ${ratios[i - 1]} then $r" }
        }
        require(ratios.isEmpty() || layers == 1 || layers == ratios.size) {
            "layers ($layers) and ratios (${ratios.size} layers) disagree"
        }
        require(!lossless || ratios.size < MAX_LAYERS) { "lossless adds a layer to ratios, which leaves more than $MAX_LAYERS" }
        require(tileWidth >= 0 && tileHeight >= 0) { "tile sides must not be negative: ${tileWidth}x$tileHeight" }
        require(levels in 0..32) { "levels must be 0 to 32, was $levels" }
        require(codeBlockWidth in 4..1024 && codeBlockWidth and (codeBlockWidth - 1) == 0) {
            "codeBlockWidth must be a power of two from 4 to 1024, was $codeBlockWidth"
        }
        require(codeBlockHeight in 4..1024 && codeBlockHeight and (codeBlockHeight - 1) == 0) {
            "codeBlockHeight must be a power of two from 4 to 1024, was $codeBlockHeight"
        }
        require(codeBlockWidth * codeBlockHeight <= 4096) {
            "a code-block holds at most 4096 samples, ${codeBlockWidth}x$codeBlockHeight holds ${codeBlockWidth * codeBlockHeight}"
        }
        require(precinctSize == 0 || (precinctSize in 2..32768 && precinctSize and (precinctSize - 1) == 0)) {
            "precinctSize must be 0 or a power of two from 2 to 32768, was $precinctSize"
        }
        require(effort in 1..3) { "effort must be 1 to 3, was $effort" }
        require(bitsPerSample in 0..16) { "bitsPerSample must be 0 to 16, was $bitsPerSample" }
    }

    /** The quality layers the file holds. */
    internal val layerCount: Int
        get() = when {
            ratios.isEmpty() -> layers
            lossless -> ratios.size + 1
            else -> ratios.size
        }

    override fun toString(): String =
        "Jp2EncodeOptions(quality=$quality, lossless=$lossless, ratios=$ratios, layers=$layers, codestreamOnly=$codestreamOnly, " +
            "tile=${tileWidth}x$tileHeight, levels=$levels, codeBlock=${codeBlockWidth}x$codeBlockHeight, precinctSize=$precinctSize, " +
            "progression=$progression, effort=$effort, bypass=$bypass, resetContexts=$resetContexts, terminateEachPass=$terminateEachPass, " +
            "verticalCausal=$verticalCausal, segmentationSymbols=$segmentationSymbols, sopMarkers=$sopMarkers, ephMarkers=$ephMarkers, " +
            "bitsPerSample=$bitsPerSample)"
}

/** OpenJPEG's J2K_MAX_LAYERS: the most layers its encoder writes. */
private const val MAX_LAYERS = 100
