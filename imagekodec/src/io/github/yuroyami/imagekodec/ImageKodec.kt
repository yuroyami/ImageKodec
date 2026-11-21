package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.internal.cancellable
import io.github.yuroyami.imagekodec.internal.Tiles
import io.github.yuroyami.imagekodec.codec.BmpDecoder
import io.github.yuroyami.imagekodec.codec.BmpEncoder
import io.github.yuroyami.imagekodec.codec.CCITT_MAX_ENCODE_COLUMNS
import io.github.yuroyami.imagekodec.codec.CcittFax
import io.github.yuroyami.imagekodec.codec.Jbig2Decoder
import io.github.yuroyami.imagekodec.codec.Jbig2Encoder
import io.github.yuroyami.imagekodec.codec.bilevelRows
import io.github.yuroyami.imagekodec.codec.encodeCcittRows
import io.github.yuroyami.imagekodec.codec.GifDecoder
import io.github.yuroyami.imagekodec.codec.GifEncoder
import io.github.yuroyami.imagekodec.codec.ImageProbe
import io.github.yuroyami.imagekodec.codec.JpegDecoder
import io.github.yuroyami.imagekodec.codec.JpegEncoder
import io.github.yuroyami.imagekodec.codec.JpegRepack
import io.github.yuroyami.imagekodec.internal.metric.Ssimulacra2
import io.github.yuroyami.imagekodec.codec.Jp2Encoder
import io.github.yuroyami.imagekodec.codec.JpxDecoder
import io.github.yuroyami.imagekodec.codec.MetadataIo
import io.github.yuroyami.imagekodec.codec.PngDecoder
import io.github.yuroyami.imagekodec.codec.PngEncoder
import io.github.yuroyami.imagekodec.codec.PngWriter
import io.github.yuroyami.imagekodec.codec.TiffDecoder
import io.github.yuroyami.imagekodec.codec.TiffEncoder
import io.github.yuroyami.imagekodec.codec.WebpDecoder
import io.github.yuroyami.imagekodec.codec.WebpEncoder
import io.github.yuroyami.imagekodec.codec.avif.AvifDecoder
import io.github.yuroyami.imagekodec.codec.avif.HeifCodecs
import io.github.yuroyami.imagekodec.codec.avif.HeifEncode
import io.github.yuroyami.imagekodec.codec.jxl.JxlDecoder
import io.github.yuroyami.imagekodec.codec.jxl.JxlEncoder
import io.github.yuroyami.imagekodec.codec.jxr.JxrDecoder
import io.github.yuroyami.imagekodec.codec.jxr.JxrEncoder
import io.github.yuroyami.imagekodec.internal.color.ColorManagement
import io.github.yuroyami.imagekodec.internal.color.Ink
import io.github.yuroyami.imagekodec.internal.color.SrgbConverter
import io.github.yuroyami.imagekodec.internal.color.reducedBy

/**
 * The ImageKodec facade. Everything is pure computation on byte arrays: no I/O and
 * no platform types, so it behaves identically on every KMP target. It starts no
 * threads of its own; a caller can hand the tiles of a grid image to its own threads
 * through [setTileRunner].
 *
 * ```kotlin
 * val bitmap = ImageKodec.decode(bytes)          // sniffs the format, dispatches
 * val anim = ImageKodec.decodeAnimation(bytes)   // frames + delays + loop count
 * val format = ImageKodec.detect(bytes)          // just the sniff
 * val info = ImageKodec.probe(bytes)             // size + depth + frames, no decode
 * val thumb = ImageKodec.decodeScaled(bytes, 256, 256)  // fitted, reduced while decoding
 * ```
 */
public object ImageKodec {

    /**
     * Adds [codec] as the decoder of its HEIF item type, so that HEIF files whose items have that
     * type decode. A codec for a type that already has one replaces it. Safe from several threads.
     */
    @ExperimentalHeifCodecApi
    public fun registerHeifCodec(codec: HeifCodec): Unit = HeifCodecs.register(codec)

    /**
     * Adds [encoder] as the encoder of its HEIF item type, so that [encodeHeic] can write HEIF files
     * whose items have that type (#174). An encoder for a type that already has one replaces it. Safe
     * from several threads.
     *
     * @throws IllegalArgumentException if its item or configuration type is not four characters
     */
    @ExperimentalHeifCodecApi
    @Throws(IllegalArgumentException::class)
    public fun registerHeifEncoder(encoder: HeifEncoder): Unit = HeifCodecs.registerEncoder(encoder)

    /**
     * Sets the [TileRunner] that every decode from now on hands its independent jobs to: the tiles
     * of a HEIC or AVIF grid, in [decode], [decode16], [decodeScaled] and the other decodes of a grid
     * image (#152), and the tiles that [encodeHeic] and [encodeHeic16] code (#174). The default, [TileRunner.Serial], runs them in order on the calling thread,
     * which is all JS and Wasm can do. Set it once, at startup, before any decode: a decode reads the
     * runner each time it hands out a set of jobs, so a change while it runs can apply to its later
     * rows of tiles.
     *
     * A decode's result does not depend on the runner, and neither does its error: of several tiles
     * that fail, the first in tile order gives the exception. With a runner that uses several
     * threads, a decode's cancellation check runs on them, possibly at the same time. A grid whose
     * tiles are derived images, or that names one tile more than once, decodes in order whatever the
     * runner.
     */
    public fun setTileRunner(runner: TileRunner): Unit = Tiles.set(runner)

    /** Identify [data]'s format from its magic bytes, or null if unrecognised. */
    public fun detect(data: ByteArray): ImageFormat? = ImageFormat.sniff(data)

    /**
     * Read [data]'s header and report what it says: dimensions, bit depth,
     * declared alpha, animation frame count, EXIF orientation, page count, and
     * whether this build can decode it. No pixels are decoded and no image-sized
     * buffer is allocated, so this stays cheap on a 50 MP file. A TIFF reports its
     * first page; [probePage] reads another.
     *
     * Use it to size a layout before the decode, to reject hostile uploads by
     * dimension, or to decide whether to claim a file at all.
     *
     * @throws ImageDecodeException if the format is unrecognised or the header
     *   is malformed past the point of reading
     */
    @Throws(ImageDecodeException::class)
    public fun probe(data: ByteArray): ImageInfo = ImageProbe.probe(data)

    /**
     * [probe] for page [page] of [data], counting from 0: that page's dimensions,
     * depth, alpha, orientation and decodability, and the file's
     * [ImageInfo.pageCount]. A TIFF has a page for each image file directory in its
     * main chain, a HEIC or AVIF file one for each top-level image, the primary first,
     * and every other format has one.
     *
     * @throws ImageDecodeException if the format is unrecognised or the header
     *   is malformed past the point of reading
     * @throws IllegalArgumentException if [page] is negative or the file has no such page
     */
    @Throws(ImageDecodeException::class, IllegalArgumentException::class)
    public fun probePage(data: ByteArray, page: Int): ImageInfo {
        require(page >= 0) { "page must not be negative, was $page" }
        val format = detect(data)
        if (format == ImageFormat.TIFF) return ImageProbe.tiff(data, page)
        if (format == ImageFormat.AVIF || format == ImageFormat.HEIC) return AvifDecoder.probePage(data, page)
        val info = ImageProbe.probe(data)
        require(page == 0) { "page $page asked of a one-page ${info.format} image" }
        return info
    }

    /** [probe], but null instead of throwing on unrecognised or malformed input. */
    public fun probeOrNull(data: ByteArray): ImageInfo? =
        try {
            ImageProbe.probe(data)
        } catch (_: ImageDecodeException) {
            null
        }

    /**
     * Decode [data] into a [KiteBitmap], sniffing the format first.
     * GIF and WebP inputs yield their first composited frame. APNG yields its
     * default image, which may be separate from the animation; use
     * [decodeAnimation] for the full sequence. A TIFF yields its first page;
     * [ImageInfo.pageCount] says how many there are and [decodePage] decodes them.
     *
     * With [applyOrientation] the EXIF orientation tag is honoured, so a phone
     * photo comes back the way it was shot instead of on its side. It defaults
     * to false because it changes the returned dimensions, and a caller that
     * already handles orientation itself should not pay for it twice: check
     * [ImageInfo.orientation] from [probe] if you want to decide per file.
     *
     * With [colorTarget] [ColorTarget.Srgb] the colours are converted to sRGB through the
     * file's embedded profile, as a browser draws them; by default they come back as stored.
     *
     * [cancellationCheck], when given, runs at a coarse step of the decode and may throw to stop a
     * decode whose result no longer matters; what it throws comes out of this call as it is. It
     * runs once a row of MCUs of a JPEG, every 64 rows and every DEFLATE block of a PNG, before
     * each strip or tile of a TIFF, each tile and code block of a JPEG 2000 image, each row of
     * superblocks of an AVIF, each group of a JPEG XL frame and each row of macroblocks of a JPEG XR
     * image; the other formats check it once before they start.
     *
     * @throws ImageDecodeException on malformed/truncated input or unknown format
     * @throws UnsupportedImageException on formats recognised but not yet decodable
     *   (see [ImageFormat]: sniffing is deliberately wider than decoding)
     */
    @Throws(ImageDecodeException::class)
    public fun decode(
        data: ByteArray,
        applyOrientation: Boolean = false,
        colorTarget: ColorTarget = ColorTarget.Source,
        cancellationCheck: (() -> Unit)? = null,
    ): KiteBitmap = cancellable(cancellationCheck) { cancel -> decodeWith(data, applyOrientation, colorTarget, cancel) }

    private fun decodeWith(data: ByteArray, applyOrientation: Boolean, colorTarget: ColorTarget, cancel: (() -> Unit)?): KiteBitmap {
        val bitmap = inColor(data, 0, colorTarget, cancel = cancel) { decodeRaw(data, cancel) }
        if (!applyOrientation) return bitmap
        val orientation = probeOrNull(data)?.orientation ?: Orientation.Normal
        return bitmap.oriented(orientation)
    }

    /**
     * A thumbnail that [data] carries, or null when it carries none (#150): for a HEIC or AVIF file,
     * the item with a `thmb` reference to its primary image, and for a JPEG, the thumbnail in its
     * EXIF. Of several, it gives the smallest that reaches [maxWidth] or [maxHeight], else the
     * largest, and skips one whose codec this build lacks. The thumbnail is not scaled; use
     * [scaled] for an exact box. [ImageInfo.thumbnailWidth] gives the largest one's size.
     *
     * [applyOrientation] turns it as [decode] turns the image: a HEIF thumbnail by its own `irot`
     * and `imir`, a JPEG's by the file's EXIF orientation. [colorTarget] converts it through its
     * own colour description, and [cancellationCheck] works as for [decode].
     *
     * @throws ImageDecodeException on a malformed thumbnail
     * @throws UnsupportedImageException when every thumbnail needs a codec this build lacks
     * @throws IllegalArgumentException if [maxWidth] or [maxHeight] is not positive
     */
    @Throws(ImageDecodeException::class, IllegalArgumentException::class)
    public fun decodeThumbnail(
        data: ByteArray,
        maxWidth: Int = Int.MAX_VALUE,
        maxHeight: Int = Int.MAX_VALUE,
        applyOrientation: Boolean = false,
        colorTarget: ColorTarget = ColorTarget.Source,
        cancellationCheck: (() -> Unit)? = null,
    ): KiteBitmap? {
        require(maxWidth > 0 && maxHeight > 0) { "box must be positive: ${maxWidth}x$maxHeight" }
        return decodeThumbnailIn(data, maxWidth, maxHeight, applyOrientation, colorTarget, cancellationCheck)
    }

    private fun decodeThumbnailIn(
        data: ByteArray,
        maxWidth: Int,
        maxHeight: Int,
        applyOrientation: Boolean,
        colorTarget: ColorTarget,
        cancellationCheck: (() -> Unit)?,
    ): KiteBitmap? = cancellable(cancellationCheck) { cancel ->
        when (detect(data)) {
            ImageFormat.JPEG -> ImageProbe.jpegThumbnail(data)?.let { thumb ->
                val bitmap = decodeWith(thumb, false, colorTarget, cancel)
                if (applyOrientation) bitmap.oriented(probeOrNull(data)?.orientation ?: Orientation.Normal) else bitmap
            }
            ImageFormat.AVIF, ImageFormat.HEIC -> AvifDecoder.thumbnail(data, maxWidth, maxHeight, cancel)?.let { t ->
                var bitmap = t.bitmap
                if (colorTarget is ColorTarget.Srgb && (t.icc != null || t.cicp != null)) {
                    ColorManagement.converter(ColorProfile(icc = t.icc, cicp = t.cicp), colorTarget.intent, 3)?.let { bitmap = it.convert(bitmap) }
                }
                if (applyOrientation) bitmap.oriented(t.orientation) else bitmap
            }
            else -> null
        }
    }

    /**
     * The auxiliary images of the primary image of a HEIC or AVIF file (#154): the items that an
     * `auxl` reference ties to it, in the order of those references, alpha among them. Each gives
     * its URN, the size and bit depth of its picture and the subtype bytes of its `auxC`;
     * [AuxiliaryImage] names the URNs this library knows. A file with none, and a file of any other
     * format, gives an empty list. No picture is decoded.
     *
     * @throws ImageDecodeException on a malformed HEIF file
     */
    @Throws(ImageDecodeException::class)
    public fun auxiliaryImages(data: ByteArray): List<AuxiliaryImage> = when (detect(data)) {
        ImageFormat.AVIF, ImageFormat.HEIC -> AvifDecoder.auxiliaryImages(data)
        else -> emptyList()
    }

    /**
     * Decode auxiliary image [index] of [auxiliaryImages] as one channel of 16-bit samples: the
     * item's luma or gray plane as its codec gives it, at [AuxiliaryImage.width] by
     * [AuxiliaryImage.height], before the item's clean aperture, rotation and mirror. A coded sample
     * `v` of n bits becomes `v * 65535 / (2^n - 1)`, rounded to the nearest, so an 8-bit `v` is
     * `v * 257` and [AuxiliaryImage.bitDepth] gives `v` back. Nothing else changes it: a limited
     * range is not stretched and chroma planes are left out, as alpha and depth are carried in luma
     * alone. What a depth sample measures is in
     * [AuxiliaryImage.subtype] or the file's XMP. [cancellationCheck] works as for [decode].
     *
     * @throws ImageDecodeException on a malformed file or item
     * @throws UnsupportedImageException when the item needs a codec this build lacks
     * @throws IllegalArgumentException if [index] is not an index of [auxiliaryImages]
     */
    @Throws(ImageDecodeException::class, IllegalArgumentException::class)
    public fun decodeAuxiliaryImage(data: ByteArray, index: Int, cancellationCheck: (() -> Unit)? = null): KiteBitmap16 =
        cancellable(cancellationCheck) { cancel ->
            when (detect(data)) {
                ImageFormat.AVIF, ImageFormat.HEIC -> AvifDecoder.decodeAuxiliary(data, index, cancel)
                else -> throw IllegalArgumentException("auxiliary image $index asked of a file with none")
            }
        }

    /**
     * The HDR gain map of a HEIC or AVIF file, decoded, with its parameters, or null when it has
     * none (#153): the gain map image of a tone-mapped (`tmap`) item that is the primary item or
     * whose base image the primary item is, with its ISO 21496-1 metadata, else the Apple gain map
     * auxiliary image with the MakerNote tags that go with it. [HeifGainMap] says what its samples
     * mean. The map is returned, not applied. A file of any other format gives null; the gain map of
     * an Ultra HDR JPEG comes from [readMetadata]. [cancellationCheck] works as for [decode].
     *
     * @throws ImageDecodeException on a malformed file, gain map item or `tmap` payload
     * @throws UnsupportedImageException when the gain map image needs a codec this build lacks, or
     *   the metadata is of a version this reader does not know
     */
    @Throws(ImageDecodeException::class)
    public fun decodeGainMap(data: ByteArray, cancellationCheck: (() -> Unit)? = null): HeifGainMap? =
        cancellable(cancellationCheck) { cancel ->
            when (detect(data)) {
                ImageFormat.AVIF, ImageFormat.HEIC -> AvifDecoder.gainMap(data, cancel)
                else -> null
            }
        }

    /**
     * Decode page [page] of [data], counting from 0. A TIFF has a page for each
     * image file directory in its main chain, as libtiff, ImageIO and libvips count
     * them, and [decode] gives the first; [ImageInfo.pageCount] says how many there
     * are. A HEIC or AVIF file has a page for each top-level image, the primary first,
     * so the two eyes of a spatial photo are pages 0 and 1, as
     * [ImageInfo.stereoLeftPage] and [ImageInfo.stereoRightPage] say. Every other
     * format has one page. Pages are separate images with their own
     * size, depth and orientation, not animation frames, so [decodeAnimation]
     * gives a TIFF's first page alone.
     *
     * With [applyOrientation] the page's own orientation tag is honoured, as
     * [decode] does for the first, and [colorTarget] converts as for [decode].
     *
     * @throws ImageDecodeException on malformed/truncated input or unknown format
     * @throws UnsupportedImageException on formats or features recognised but not yet decodable
     * @throws IllegalArgumentException if [page] is negative or the file has no such page
     */
    @Throws(ImageDecodeException::class, IllegalArgumentException::class)
    public fun decodePage(
        data: ByteArray,
        page: Int,
        applyOrientation: Boolean = false,
        colorTarget: ColorTarget = ColorTarget.Source,
        cancellationCheck: (() -> Unit)? = null,
    ): KiteBitmap = cancellable(cancellationCheck) { cancel ->
        require(page >= 0) { "page must not be negative, was $page" }
        val format = detect(data)
        if ((format == ImageFormat.AVIF || format == ImageFormat.HEIC) && page > 0) {
            val bitmap = inColor(data, page, colorTarget, cancel = cancel) { AvifDecoder.decodePage(data, page, wide = false, cancel).first }
            return@cancellable if (applyOrientation) bitmap.oriented(AvifDecoder.probePage(data, page).orientation) else bitmap
        }
        if (format != ImageFormat.TIFF) {
            // Data that is no image fails as decode fails, whatever the page.
            if (format == null || page == 0) return@cancellable decodeWith(data, applyOrientation, colorTarget, cancel)
            throw IllegalArgumentException("page $page asked of a one-page $format image")
        }
        tiffPage(data, page, applyOrientation, colorTarget, cancel)
    }

    private fun tiffPage(data: ByteArray, page: Int, applyOrientation: Boolean, colorTarget: ColorTarget, cancel: (() -> Unit)?): KiteBitmap {
        val bitmap = inColor(data, page, colorTarget, cancel = cancel) { TiffDecoder.decode(data, page, cancel) }
        if (!applyOrientation) return bitmap
        val orientation = try {
            ImageProbe.tiff(data, page).orientation
        } catch (_: ImageDecodeException) {
            Orientation.Normal
        }
        return bitmap.oriented(orientation)
    }

    /**
     * Decode page [page] of [data] at 16 bits a sample, for the files whose low byte
     * matters: depth maps, scientific and medical images, heavily graded photographs.
     * [decode] keeps 8 bits a channel; this keeps every bit a 16-bit PNG or TIFF stores,
     * including a TIFF palette's 16-bit ColorMap, every bit of a JPEG 2000 component
     * up to 16 bits deep, through its palette, channel definitions and colour space, and
     * every bit of a 12-bit JPEG or a lossless one of 9 to 16 bits, converted at that
     * precision. A JPEG XL image keeps up to 16 bits a sample; a deeper or a float
     * sample is rounded to 16 bits. A JPEG XR image keeps its 16-bit samples, and its linear
     * fixed-point, half and float samples convert to sRGB at 16 bits. The channels are those the file stores once a
     * palette is looked up (gray, gray and alpha, RGB or RGBA), so a gray depth map
     * stays one sample a pixel. Narrower samples
     * replicate up (an 8-bit `v` becomes `v * 257`, a 12-bit one `v shl 4 or (v shr 8)`, a
     * 1-bit one 0 or 65535), and every other format decodes as [decode] does and widens
     * the same way, as RGB, or RGBA when the file declares transparency.
     * [KiteBitmap16.toBitmap] then gives exactly what [decode] returns.
     *
     * [page] counts from 0, as for [decodePage]. With [applyOrientation] the page's
     * orientation tag is honoured, as [decode] does. [colorTarget] converts as for [decode],
     * at full precision, and [cancellationCheck] runs as for [decode].
     *
     * @throws ImageDecodeException on malformed/truncated input or unknown format
     * @throws UnsupportedImageException on formats or features recognised but not yet decodable
     * @throws IllegalArgumentException if [page] is negative or the file has no such page
     */
    @Throws(ImageDecodeException::class, IllegalArgumentException::class)
    public fun decode16(
        data: ByteArray,
        page: Int = 0,
        applyOrientation: Boolean = false,
        colorTarget: ColorTarget = ColorTarget.Source,
        cancellationCheck: (() -> Unit)? = null,
    ): KiteBitmap16 = cancellable(cancellationCheck) { cancel -> decode16With(data, page, applyOrientation, colorTarget, cancel) }

    private fun decode16With(data: ByteArray, page: Int, applyOrientation: Boolean, colorTarget: ColorTarget, cancel: (() -> Unit)?): KiteBitmap16 {
        require(page >= 0) { "page must not be negative, was $page" }
        val format = detect(data)
        val heif = format == ImageFormat.AVIF || format == ImageFormat.HEIC
        if (format != ImageFormat.TIFF && !heif && format != null && page != 0) {
            throw IllegalArgumentException("page $page asked of a one-page $format image")
        }
        when (format) {
            ImageFormat.TIFF, ImageFormat.PNG, ImageFormat.JP2, ImageFormat.AVIF, ImageFormat.HEIC, ImageFormat.JPEG, ImageFormat.JXL, ImageFormat.JXR -> {}
            // A BMP that holds a PNG keeps that PNG's precision.
            ImageFormat.BMP -> return BmpDecoder.embedded(data)?.let { decode16With(it, 0, applyOrientation, colorTarget, cancel) }
                ?: widened(data, applyOrientation, colorTarget, cancel)
            else -> return widened(data, applyOrientation, colorTarget, cancel)
        }
        val wide = inColor16(data, page, colorTarget, cancel) {
            when (format) {
                ImageFormat.TIFF -> TiffDecoder.decode16(data, page, cancel)
                ImageFormat.PNG -> PngDecoder.decode16(data, cancel)
                ImageFormat.JP2 -> JpxDecoder.decode16ForFacade(data, cancel)
                ImageFormat.AVIF, ImageFormat.HEIC ->
                    if (page == 0) AvifDecoder.decode16(data, cancel) else AvifDecoder.decodePage(data, page, wide = true, cancel).second!!
                ImageFormat.JXL -> JxlDecoder.decode16(data, cancel)
                ImageFormat.JXR -> JxrDecoder.decode16(data, cancel)
                else -> JpegDecoder.decode16(data, cancel)
            }
        }
        if (!applyOrientation) return wide
        val orientation = try {
            when {
                format == ImageFormat.TIFF -> ImageProbe.tiff(data, page).orientation
                heif -> AvifDecoder.probePage(data, page).orientation
                else -> ImageProbe.probe(data).orientation
            }
        } catch (_: ImageDecodeException) {
            Orientation.Normal
        }
        return wide.oriented(orientation)
    }

    /** [decode], widened to 16 bits a sample, with alpha when the file declares it. */
    private fun widened(data: ByteArray, applyOrientation: Boolean, colorTarget: ColorTarget, cancel: (() -> Unit)?): KiteBitmap16 {
        val bitmap = decodeWith(data, applyOrientation, colorTarget, cancel)
        val alpha = probeOrNull(data)?.hasAlpha ?: bitmap.hasTransparency()
        return KiteBitmap16.widened(bitmap, alpha)
    }

    /**
     * Decode [data] with each side divided by [reduction], rounded up: 1, 2, 4 or 8. Use it
     * for an image that will draw smaller than its pixels, such as a scan on a phone screen
     * or a thumbnail. Like [decode] by default, it does not apply EXIF orientation.
     *
     * A JPEG reduces inside its inverse DCT, as libjpeg's scaled decode does, so the
     * full-size pixels never exist: a baseline 35-megapixel scan decoded at an eighth needs
     * memory for about half a megapixel. A progressive JPEG still keeps the coefficients of
     * the full size, 2 bytes a sample, until its last scan. A BMP that holds a JPEG as its pixel
     * array reduces as that JPEG does. A JPEG 2000 image drops its finest
     * wavelet levels, as OpenJPEG's reduce option does. A JPEG XR image decodes jxrlib's
     * thumbnail, as `JxrDecApp -T` does: every second sample of the full transform at 2, and
     * every fourth or eighth, the DCs of the second transform stage alone, at 4 and 8, for which
     * a file in frequency order skips its highpass. Where jxrlib would round a side otherwise,
     * the image decodes in full and averages as below. Other formats decode in full, then
     * average each block of [reduction] by [reduction] pixels, weighted by alpha as [scaled]
     * does. [colorTarget] converts as for [decode], after the reduction, and [cancellationCheck]
     * runs as for [decode].
     *
     * @throws IllegalArgumentException if [reduction] is not 1, 2, 4 or 8
     * @throws ImageDecodeException on malformed/truncated input or unknown format
     * @throws UnsupportedImageException on formats recognised but not yet decodable
     */
    @Throws(ImageDecodeException::class, IllegalArgumentException::class)
    public fun decodeReduced(
        data: ByteArray,
        reduction: Int,
        colorTarget: ColorTarget = ColorTarget.Source,
        cancellationCheck: (() -> Unit)? = null,
    ): KiteBitmap {
        require(reduction == 1 || reduction == 2 || reduction == 4 || reduction == 8) {
            "reduction must be 1, 2, 4 or 8, was $reduction"
        }
        return cancellable(cancellationCheck) { cancel -> inColor(data, 0, colorTarget, reduction, cancel) { reducedRaw(data, reduction, cancel) } }
    }

    private fun reducedRaw(data: ByteArray, reduction: Int, cancel: (() -> Unit)?): KiteBitmap {
        if (reduction == 1) return decodeRaw(data, cancel)
        return when (detect(data)) {
            ImageFormat.JPEG -> JpegDecoder.decode(data, scale = reduction.countTrailingZeroBits(), cancel = cancel)
            ImageFormat.JP2 -> jp2ToBitmap(data, reduction, cancel)
            ImageFormat.JXR -> JxrDecoder.decodeReduced(data, reduction, cancel) ?: decodeRaw(data, cancel).reducedBy(reduction)
            // A BMP that holds a JPEG reduces inside that JPEG's inverse DCT.
            ImageFormat.BMP -> BmpDecoder.embedded(data)?.let { reducedRaw(it, reduction, cancel) } ?: decodeRaw(data, cancel).reducedBy(reduction)
            else -> decodeRaw(data, cancel).reducedBy(reduction)
        }
    }

    /**
     * Decode [data] at the size `decode(data, applyOrientation).scaled(maxWidth, maxHeight)` gives:
     * fitted inside [maxWidth] by [maxHeight] with the image's own aspect ratio, and never larger
     * than the image. Use it for a thumbnail or a preview, where the full-size pixels would only
     * be thrown away.
     *
     * A JPEG, JPEG 2000 or JPEG XR decodes at the largest reduction [decodeReduced] offers whose output
     * still covers that size, as libjpeg's scaled decode or Android's sample size is chosen, and
     * the box filter of [scaled] finishes the rest. The full-size pixels never exist, and the
     * result differs from the full decode only by the reduced inverse transform. A HEIC or AVIF
     * image made of a `grid` of tiles reduces while it decodes: one row of tiles at a time is
     * decoded, converted to RGB and fed to the box filter, so the full-size pixels never exist
     * either, and the result equals the full decode then [scaled], pixel for pixel. Its tiles still
     * decode at full size, so this saves memory, not time. Other formats decode in full and filter
     * once. [colorTarget] converts as for [decode], and [cancellationCheck] runs as for [decode],
     * and at least once a tile of a grid.
     *
     * @throws IllegalArgumentException if [maxWidth] or [maxHeight] is not positive
     * @throws ImageDecodeException on malformed/truncated input or unknown format
     * @throws UnsupportedImageException on formats recognised but not yet decodable
     */
    @Throws(ImageDecodeException::class, IllegalArgumentException::class)
    public fun decodeScaled(
        data: ByteArray,
        maxWidth: Int,
        maxHeight: Int,
        applyOrientation: Boolean = false,
        colorTarget: ColorTarget = ColorTarget.Source,
        cancellationCheck: (() -> Unit)? = null,
    ): KiteBitmap {
        require(maxWidth > 0 && maxHeight > 0) { "target must be positive: ${maxWidth}x$maxHeight" }
        return cancellable(cancellationCheck) { cancel ->
            if (!reducesWhileDecoding(data)) return@cancellable decodeWith(data, applyOrientation, colorTarget, cancel).scaled(maxWidth, maxHeight)
            val info = probe(data)
            val width = if (applyOrientation) info.displayWidth else info.width
            val height = if (applyOrientation) info.displayHeight else info.height
            val (dw, dh) = fittedSize(width, height, maxWidth, maxHeight) ?: return@cancellable decodeWith(data, applyOrientation, colorTarget, cancel)
            if (isHeif(data)) heifReducedTo(data, info, dw, dh, applyOrientation, colorTarget, cancel)
            else reducedTo(data, info, dw, dh, applyOrientation, colorTarget, cancel)
        }
    }

    /**
     * Decode [data] at exactly [width] by [height], the size
     * `decode(data, applyOrientation).downscaledTo(width, height)` gives. The caller chooses the
     * aspect ratio: use it for the size of a centre crop, computed from [probe], or for a size a
     * layout has already worked out. It reduces a JPEG, JPEG 2000 or JPEG XR, and a HEIC or AVIF
     * grid, while decoding as [decodeScaled] does. [colorTarget] converts as for [decode], and [cancellationCheck] runs
     * as for [decode].
     *
     * @throws IllegalArgumentException if [width] or [height] is not positive or exceeds the side of
     *   the image it applies to, after the orientation when [applyOrientation] is set
     * @throws ImageDecodeException on malformed/truncated input or unknown format
     * @throws UnsupportedImageException on formats recognised but not yet decodable
     */
    @Throws(ImageDecodeException::class, IllegalArgumentException::class)
    public fun decodeDownscaledTo(
        data: ByteArray,
        width: Int,
        height: Int,
        applyOrientation: Boolean = false,
        colorTarget: ColorTarget = ColorTarget.Source,
        cancellationCheck: (() -> Unit)? = null,
    ): KiteBitmap {
        require(width > 0 && height > 0) { "target must be positive: ${width}x$height" }
        return cancellable(cancellationCheck) { cancel ->
            if (!reducesWhileDecoding(data)) return@cancellable decodeWith(data, applyOrientation, colorTarget, cancel).downscaledTo(width, height)
            if (isHeif(data)) heifReducedTo(data, probe(data), width, height, applyOrientation, colorTarget, cancel)
            else reducedTo(data, probe(data), width, height, applyOrientation, colorTarget, cancel)
        }
    }

    private fun ceilDiv(side: Int, reduction: Int): Int = ((side.toLong() + reduction - 1) / reduction).toInt()

    /**
     * Whether [decodeScaled] reduces [data] inside its decoder, so that a smaller result costs less:
     * [decodeReduced] does for a JPEG, JPEG 2000 or JPEG XR, and a HEIC or AVIF grid scales band by
     * band (#151).
     */
    private fun reducesWhileDecoding(data: ByteArray): Boolean = when (detect(data)) {
        ImageFormat.JPEG, ImageFormat.JP2, ImageFormat.JXR -> true
        ImageFormat.BMP -> BmpDecoder.embedded(data)?.let { detect(it) == ImageFormat.JPEG } ?: false
        ImageFormat.AVIF, ImageFormat.HEIC -> AvifDecoder.reducesWhileDecoding(data)
        else -> false
    }

    private fun isHeif(data: ByteArray): Boolean = detect(data).let { it == ImageFormat.AVIF || it == ImageFormat.HEIC }

    /**
     * The HEIC or AVIF grid [data], whose header [info] describes, at exactly [width] by [height],
     * scaled band by band as it decodes (#151). Each band converts as [inColor] converts the whole
     * picture, before the scale, and the reduction comes before the orientation, which moves the
     * same pixels either way. Where the band path declines, the image decodes in full.
     */
    private fun heifReducedTo(
        data: ByteArray,
        info: ImageInfo,
        width: Int,
        height: Int,
        applyOrientation: Boolean,
        colorTarget: ColorTarget,
        cancel: (() -> Unit)?,
    ): KiteBitmap {
        val orientation = if (applyOrientation) info.orientation else Orientation.Normal
        val storedWidth = if (orientation.swapsAxes) height else width
        val storedHeight = if (orientation.swapsAxes) width else height
        // A CMYK profile converts nothing in a HEIF file, as inColor finds no ink in it.
        val conversion = conversion(data, 0, colorTarget)?.takeIf { it.converter.channels != 4 }
        val converter = conversion?.converter
        val reduced = AvifDecoder.downscaledGrid(
            data, storedWidth, storedHeight,
            wide = conversion != null && conversion.bitDepth > 8,
            // A gray profile applies only to gray pixels, which only a monochrome picture promises band by band.
            grayOnly = converter?.channels == 1,
            cancel = cancel,
        ) { band, band16 ->
            if (converter == null) {
                band
            } else {
                band16?.let { applied(converter, it) }?.toBitmap() ?: applied(converter, band) ?: band
            }
        } ?: return decodeWith(data, applyOrientation, colorTarget, cancel).downscaledTo(width, height)
        return reduced.oriented(orientation)
    }

    /**
     * [data], whose header [info] describes, decoded at the largest reduction that still covers
     * [width] by [height] and box-filtered to exactly that size. A reduced side is the full side
     * divided by the reduction and rounded up, as [decodeReduced] promises.
     */
    private fun reducedTo(
        data: ByteArray,
        info: ImageInfo,
        width: Int,
        height: Int,
        applyOrientation: Boolean,
        colorTarget: ColorTarget,
        cancel: (() -> Unit)?,
    ): KiteBitmap {
        val orientation = if (applyOrientation) info.orientation else Orientation.Normal
        // The reduction divides the stored sides, so compare them with the target before the orientation.
        val storedWidth = if (orientation.swapsAxes) height else width
        val storedHeight = if (orientation.swapsAxes) width else height
        var reduction = 8
        while (reduction > 1 && (ceilDiv(info.width, reduction) < storedWidth || ceilDiv(info.height, reduction) < storedHeight)) {
            reduction /= 2
        }
        var bitmap = inColor(data, 0, colorTarget, reduction, cancel) { reducedRaw(data, reduction, cancel) }
        // A header that disagrees with its data must not make the result smaller than asked for.
        if (bitmap.width < storedWidth || bitmap.height < storedHeight) bitmap = inColor(data, 0, colorTarget, cancel = cancel) { decodeRaw(data, cancel) }
        return bitmap.oriented(orientation).downscaledTo(width, height)
    }

    /**
     * Decode the JPEG in [data] to its stored samples: every component after the inverse DCT and
     * the upsampling, before any color conversion. Use it when the caller, not the decoder, knows
     * what the samples mean, such as the CMYK or YCCK ink of a PDF image. [JpegComponents] says
     * how the samples are laid out.
     *
     * [reduction] works as in [decodeReduced]: 1, 2, 4 or 8, each side divided by it and rounded
     * up, inside the inverse DCT. The decode and the upsampling are those of [decodeReduced], so
     * its color conversion of these samples gives its pixels exactly, and a file it refuses is
     * refused here with the same exception. It costs about one [decodeReduced]. [cancellationCheck]
     * runs as for [decode], once a row of MCUs.
     *
     * @throws IllegalArgumentException if [reduction] is not 1, 2, 4 or 8
     * @throws ImageDecodeException on malformed or truncated input, or input that is not a JPEG
     * @throws UnsupportedImageException on a JPEG feature this decoder does not implement
     */
    @Throws(ImageDecodeException::class, IllegalArgumentException::class)
    public fun decodeJpegComponents(data: ByteArray, reduction: Int = 1, cancellationCheck: (() -> Unit)? = null): JpegComponents {
        require(reduction == 1 || reduction == 2 || reduction == 4 || reduction == 8) {
            "reduction must be 1, 2, 4 or 8, was $reduction"
        }
        return cancellable(cancellationCheck) { cancel ->
            JpegDecoder.decodeComponents(data, scale = reduction.countTrailingZeroBits(), cancel = cancel).components
        }
    }

    /**
     * Decode the JPEG 2000 image in [data] to its stored components: every component after the
     * inverse wavelet, the multiple component transform and the DC level shift, sampled onto the
     * image grid, before the JP2 palette, channel definitions and colour space. Use it when the
     * caller, not the file, says what the components mean, as a PDF's `ColorSpace` does for a
     * `JPXDecode` image (ISO 32000-1, 8.9.5.2). [JpxComponents] says how the samples are laid out,
     * and reports the file's own colour specification.
     *
     * [reduction] works as in [decodeReduced]: 1, 2, 4 or 8, each side divided by it and rounded up,
     * by dropping the finest wavelet levels. The reconstruction is the one [decode] runs, so its
     * colour stage applied to 8-bit components gives its pixels exactly. A codestream [decode]
     * refuses is refused here with the same exception; a colour box [decode] cannot apply is
     * reported, not refused. [cancellationCheck] runs as for [decode].
     *
     * @throws IllegalArgumentException if [reduction] is not 1, 2, 4 or 8
     * @throws ImageDecodeException on malformed or truncated input, or input that is not JPEG 2000
     * @throws UnsupportedImageException on a codestream feature this decoder does not implement
     */
    @Throws(ImageDecodeException::class, IllegalArgumentException::class)
    public fun decodeJpxComponents(data: ByteArray, reduction: Int = 1, cancellationCheck: (() -> Unit)? = null): JpxComponents {
        require(reduction == 1 || reduction == 2 || reduction == 4 || reduction == 8) {
            "reduction must be 1, 2, 4 or 8, was $reduction"
        }
        if (detect(data) != ImageFormat.JP2) throw ImageDecodeException("not a JPEG 2000 image (${data.size} bytes)")
        return cancellable(cancellationCheck) { cancel -> JpxDecoder.decodeComponents(data, reduction, cancel) }
    }

    /**
     * [bitmap] converted to sRGB through [profile] with [intent], as [decode] converts with
     * [ColorTarget.Srgb]: for a bitmap decoded before, or one whose profile came from elsewhere,
     * such as a PDF's ICCBased colour space. Every declaration of [profile] counts, in the
     * order [ColorProfile] gives: an ICC profile, H.273 code points (HDR ones tone-mapped to
     * SDR), and a PNG's `sRGB`, `gAMA` and `cHRM`. A gray profile reads the red channel.
     * A profile this does not read, one that already is sRGB, a CMYK profile (whose ink
     * [convertCmykToSrgb] takes), or a gray one with a bitmap that is not gray gives [bitmap]
     * back.
     */
    public fun convertToSrgb(bitmap: KiteBitmap, profile: ColorProfile, intent: RenderingIntent = RenderingIntent.Perceptual): KiteBitmap =
        ColorManagement.converter(profile, intent)?.let { applied(it, bitmap) } ?: bitmap

    /** [convertToSrgb] at 16 bits a sample, at full precision. */
    public fun convertToSrgb(bitmap: KiteBitmap16, profile: ColorProfile, intent: RenderingIntent = RenderingIntent.Perceptual): KiteBitmap16 =
        ColorManagement.converter(profile, intent)?.let { applied(it, bitmap) } ?: bitmap

    /**
     * CMYK ink converted to opaque sRGB through the CMYK [profile] with [intent], or null when
     * [profile] is not a CMYK profile this reads. [ink] holds four samples a pixel, cyan,
     * magenta, yellow and black, from no ink (0) to full (255), as a PDF's DeviceCMYK or
     * ICCBased samples run; a CMYK JPEG's Adobe samples run the other way, and [decode] turns
     * them over itself.
     *
     * @throws IllegalArgumentException if [ink] does not hold [width] times [height] pixels
     */
    @Throws(IllegalArgumentException::class)
    public fun convertCmykToSrgb(
        width: Int,
        height: Int,
        ink: ByteArray,
        profile: ColorProfile,
        intent: RenderingIntent = RenderingIntent.Perceptual,
    ): KiteBitmap? {
        require(width >= 0 && height >= 0 && ink.size.toLong() == width.toLong() * height * 4) {
            "${ink.size} bytes of ink for ${width}x$height pixels"
        }
        val converter = ColorManagement.converter(profile, intent)?.takeIf { it.channels == 4 } ?: return null
        return converter.convertInk(Ink(width, height, ink = ink))
    }

    /** [convertCmykToSrgb] at 16 bits a sample, as RGB at full precision. */
    @Throws(IllegalArgumentException::class)
    public fun convertCmykToSrgb(
        width: Int,
        height: Int,
        ink: ShortArray,
        profile: ColorProfile,
        intent: RenderingIntent = RenderingIntent.Perceptual,
    ): KiteBitmap16? {
        require(width >= 0 && height >= 0 && ink.size.toLong() == width.toLong() * height * 4) {
            "${ink.size} samples of ink for ${width}x$height pixels"
        }
        val converter = ColorManagement.converter(profile, intent)?.takeIf { it.channels == 4 } ?: return null
        return converter.convertInk16(Ink(width, height, ink16 = ink))
    }

    /**
     * [bitmap] through [converter], or null when the two do not fit: a CMYK conversion takes ink,
     * and a gray one turns down colour, as browsers ignore a profile whose space is not the image's.
     */
    private fun applied(converter: SrgbConverter, bitmap: KiteBitmap): KiteBitmap? = when {
        converter.channels == 4 -> null
        converter.channels == 1 && !bitmap.isGray() -> null
        else -> converter.convert(bitmap)
    }

    private fun applied(converter: SrgbConverter, bitmap: KiteBitmap16): KiteBitmap16? = when {
        converter.channels == 4 -> null
        converter.channels == 1 && bitmap.channels >= 3 && !bitmap.isGray() -> null
        else -> converter.convert16(bitmap)
    }

    private fun KiteBitmap.isGray(): Boolean = argb.all { p -> (p shr 16) and 0xFF == (p shr 8) and 0xFF && (p shr 8) and 0xFF == p and 0xFF }

    private fun KiteBitmap16.isGray(): Boolean {
        val n = channels
        for (i in 0 until width * height) {
            val at = i * n
            if (samples[at] != samples[at + 1] || samples[at + 1] != samples[at + 2]) return false
        }
        return true
    }

    /**
     * The CMYK ink of page [page] of [data], at 16 bits with [wide] and else at 8 with each side
     * divided by [reduction], or null when the page holds no ink this reads: a CMYK or YCCK
     * JPEG, alone or in a BMP, a CMYK TIFF, or a CMYK JPEG XR or JPEG XL.
     */
    private fun inkOf(data: ByteArray, page: Int, reduction: Int, wide: Boolean, cancel: (() -> Unit)?): Ink? = when (detect(data)) {
        ImageFormat.JPEG -> if (wide) JpegDecoder.decodeInk16(data, cancel) else JpegDecoder.decodeInk(data, scale = reduction.countTrailingZeroBits(), cancel = cancel)
        ImageFormat.TIFF -> TiffDecoder.decodeInk(data, page, wide, cancel)?.let { if (wide) it else it.reducedBy(reduction) }
        ImageFormat.BMP -> BmpDecoder.embedded(data)?.let { inkOf(it, 0, reduction, wide, cancel) }
        ImageFormat.JXR -> JxrDecoder.decodeInk(data, wide, cancel)?.let { if (wide) it else it.reducedBy(reduction) }
        ImageFormat.JXL -> if (page == 0) JxlDecoder.decodeInk(data, wide, cancel)?.let { if (wide) it else it.reducedBy(reduction) } else null
        else -> null
    }

    /**
     * Page [page] of [data] as [raw] decodes it as stored, with each side divided by
     * [reduction], converted as [colorTarget] asks: a CMYK page through its ink, anything else
     * after [raw].
     */
    private inline fun inColor(
        data: ByteArray,
        page: Int,
        colorTarget: ColorTarget,
        reduction: Int = 1,
        noinline cancel: (() -> Unit)? = null,
        raw: () -> KiteBitmap,
    ): KiteBitmap {
        val conversion = conversion(data, page, colorTarget) ?: return raw()
        val converter = conversion.converter
        if (converter.channels == 4) return inkOf(data, page, reduction, wide = false, cancel)?.let { converter.convertInk(it) } ?: raw()
        // Samples deeper than 8 bits convert at their own precision and narrow after, so a steep
        // curve, PQ's above all, does not stretch the steps of their high byte into bands.
        if (reduction == 1 && conversion.bitDepth > 8) {
            raw16(data, page, cancel)?.let { wide -> applied(converter, wide)?.let { return it.toBitmap() } }
        }
        val bitmap = raw()
        return applied(converter, bitmap) ?: bitmap
    }

    /** Page [page] of [data] at 16 bits, as [decode16] reads it before any conversion, or null for a format it widens. */
    private fun raw16(data: ByteArray, page: Int, cancel: (() -> Unit)?): KiteBitmap16? = when (detect(data)) {
        ImageFormat.TIFF -> TiffDecoder.decode16(data, page, cancel)
        ImageFormat.PNG -> PngDecoder.decode16(data, cancel)
        ImageFormat.JP2 -> JpxDecoder.decode16ForFacade(data, cancel)
        ImageFormat.AVIF, ImageFormat.HEIC -> if (page == 0) AvifDecoder.decode16(data, cancel) else AvifDecoder.decodePage(data, page, wide = true, cancel).second
        ImageFormat.JPEG -> JpegDecoder.decode16(data, cancel)
        ImageFormat.JXL -> JxlDecoder.decode16(data, cancel)
        ImageFormat.JXR -> JxrDecoder.decode16(data, cancel)
        ImageFormat.BMP -> BmpDecoder.embedded(data)?.let { raw16(it, 0, cancel) }
        else -> null
    }

    /** [inColor] at 16 bits a sample. */
    private inline fun inColor16(data: ByteArray, page: Int, colorTarget: ColorTarget, noinline cancel: (() -> Unit)?, raw: () -> KiteBitmap16): KiteBitmap16 {
        val converter = conversion(data, page, colorTarget)?.converter ?: return raw()
        if (converter.channels == 4) return inkOf(data, page, 1, wide = true, cancel)?.let { converter.convertInk16(it) } ?: raw()
        val bitmap = raw()
        return applied(converter, bitmap) ?: bitmap
    }

    /** The conversion [colorTarget] asks of page [page] of [data], or null for none. */
    /** A page's conversion, and how deep its samples are. */
    private class Conversion(val converter: SrgbConverter, val bitDepth: Int)

    private fun conversion(data: ByteArray, page: Int, colorTarget: ColorTarget): Conversion? {
        if (colorTarget !is ColorTarget.Srgb) return null
        val info = try {
            when (detect(data)) {
                ImageFormat.TIFF -> ImageProbe.tiff(data, page)
                // A HEIF page has its own colour (#168).
                ImageFormat.AVIF, ImageFormat.HEIC -> AvifDecoder.probePage(data, page)
                else -> ImageProbe.probe(data)
            }
        } catch (_: ImageDecodeException) {
            null
        } ?: return null
        val channels = if (info.colorChannels == 1) 1 else 3
        val converter = ColorManagement.converter(info.colorProfile ?: return null, colorTarget.intent, channels) ?: return null
        // A profile of another space than the image's fits nothing, and browsers ignore it.
        if (info.colorChannels != 0 && info.colorChannels != converter.channels) return null
        return Conversion(converter, info.bitDepth)
    }

    private fun decodeRaw(data: ByteArray, cancel: (() -> Unit)?): KiteBitmap = when (detect(data)) {
        ImageFormat.PNG -> PngDecoder.decode(data, cancel)
        ImageFormat.BMP -> {
            cancel?.invoke()
            BmpDecoder.decode(data)
        }
        ImageFormat.GIF -> {
            cancel?.invoke()
            GifDecoder.decode(data, maxFrames = 1).frames.first().bitmap
        }
        ImageFormat.JPEG -> JpegDecoder.decode(data, cancel = cancel)
        ImageFormat.JP2 -> jp2ToBitmap(data, cancel = cancel)
        ImageFormat.WEBP -> {
            cancel?.invoke()
            WebpDecoder.decode(data)
        }
        ImageFormat.TIFF -> TiffDecoder.decode(data, cancel = cancel)
        ImageFormat.AVIF, ImageFormat.HEIC -> AvifDecoder.decode(data, cancel)
        ImageFormat.JXL -> JxlDecoder.decode(data, cancel)
        ImageFormat.JXR -> JxrDecoder.decode(data, cancel)
        null -> throw ImageDecodeException(
            "unrecognised image format (${data.size} bytes${
                if (data.size >= 4) {
                    ", starts " + data.take(4).joinToString(" ") { b ->
                        (b.toInt() and 0xFF).toString(16).padStart(2, '0')
                    }
                } else ""
            })",
        )
    }

    /**
     * Encode [bitmap] as a PNG: 8-bit RGB, or RGBA when any pixel carries alpha.
     * Lossless: decoding the result returns the exact same pixels.
     */
    public fun encodePng(bitmap: KiteBitmap): ByteArray = PngEncoder.encode(bitmap)

    /**
     * Encode [animation] as an animated PNG (APNG): 8-bit RGB, or RGBA when any frame
     * carries alpha, each frame's delay to the millisecond and the animation's loop
     * count. Lossless: [decodeAnimation] returns the same frames, delays and loop
     * count, and a reader without APNG support shows the first frame. A single frame
     * is written as a plain PNG. Frames must all be canvas-sized, which is exactly
     * what [decodeAnimation] produces.
     *
     * Each frame after the first stores only the rectangle that changed, replacing the
     * canvas there or drawn over it, whichever compresses smaller.
     *
     * @throws IllegalArgumentException if the frames are not all canvas-sized, or if the
     *   play count does not fit APNG's 32 bits
     */
    @Throws(IllegalArgumentException::class)
    public fun encodePng(animation: KiteAnimation): ByteArray = PngEncoder.encode(animation)

    /**
     * Encode [bitmap] as a PNG in the colour type, bit depth and interlace [options] asks for,
     * with [metadata] written as the overload without options writes it (#182). Lossless:
     * [decode] returns the exact same pixels.
     *
     * With [PngColorType.Auto], the default, the file takes the smallest type that holds the
     * pixels exactly: gray at 1, 2, 4 or 8 bits, a palette of up to 256 colours with their alpha
     * in `tRNS`, RGB, or gray or RGB with an alpha channel. Gray and RGB carry a `tRNS` colour
     * key in place of alpha when every transparent pixel is one colour that no opaque pixel has.
     * An ICC profile in [metadata] keeps the type gray or colour as its colour space is. The
     * overload without options keeps writing 8-bit RGB or RGBA, byte for byte as before.
     *
     * [cancellationCheck] runs between rows and through the compression, and may throw to stop
     * the encode; what it throws comes out of this call.
     *
     * @throws IllegalArgumentException if the fixed [PngEncodeOptions.colorType] or
     *   [PngEncodeOptions.bitDepth] cannot hold the pixels exactly, if it disagrees with the ICC
     *   profile's colour space, or if the rows need more than 2 GB
     */
    @Throws(IllegalArgumentException::class)
    public fun encodePng(
        bitmap: KiteBitmap,
        options: PngEncodeOptions,
        metadata: ImageMetadata = ImageMetadata(),
        cancellationCheck: (() -> Unit)? = null,
    ): ByteArray = cancellable(cancellationCheck) { PngWriter.encode(bitmap, options, metadata, it) }

    /**
     * Encode [bitmap] as a PNG of up to 16 bits a sample, keeping every bit [decode16] gives, as
     * [encodePng] with options writes an 8-bit bitmap (#182). Its channels are gray, gray and
     * alpha, RGB or RGBA, as [KiteBitmap16] holds them.
     *
     * With [PngColorType.Auto], the file takes the smallest type that [decode16] reads back to
     * the same channels and samples: 8 bits where every sample is a multiple of 257 (an 8-bit
     * `v` widened as `v * 257`), gray at 1, 2 or 4 bits where every sample is such a widened
     * value, a palette where a picture of 8-bit samples has 256 colours or fewer, and a `tRNS`
     * colour key in place of an alpha channel where the alpha allows it. A fixed type is written
     * as asked, and [decode16] then gives the channels that type has: a gray type gives one
     * channel, or two with a colour key, and a palette three, or four with `tRNS`.
     *
     * @throws IllegalArgumentException as [encodePng] with options does
     */
    @Throws(IllegalArgumentException::class)
    public fun encodePng16(
        bitmap: KiteBitmap16,
        options: PngEncodeOptions = PngEncodeOptions(),
        metadata: ImageMetadata = ImageMetadata(),
        cancellationCheck: (() -> Unit)? = null,
    ): ByteArray = cancellable(cancellationCheck) { PngWriter.encode16(bitmap, options, metadata, it) }

    /**
     * Encode [animation] as an APNG, as the overload without options does, in the colour type,
     * bit depth and interlace [options] asks for, chosen once for every frame, since a PNG has
     * one colour type (#182). [PngColorType.Auto] picks it by its bits a pixel at every effort;
     * the effort sets the filters and compression of each frame. Gray and palette frames take
     * 8 bits unless [PngEncodeOptions.bitDepth] asks for fewer, as ffmpeg 6 reads the frames
     * after the first wrong at fewer. A frame is drawn over the
     * canvas only where the type has a transparent pixel, which it has when any frame is
     * transparent. [metadata] goes before the image data, as in a still. A single frame is
     * written as [encodePng] with options writes a bitmap.
     *
     * @throws IllegalArgumentException as the overload without options does, or as [encodePng]
     *   with options does
     */
    @Throws(IllegalArgumentException::class)
    public fun encodePng(
        animation: KiteAnimation,
        options: PngEncodeOptions,
        metadata: ImageMetadata = ImageMetadata(),
        cancellationCheck: (() -> Unit)? = null,
    ): ByteArray = cancellable(cancellationCheck) { PngWriter.encode(animation, options, metadata, it) }

    /**
     * Encode [bitmap] as a TIFF: 8-bit gray when every pixel is gray, RGB otherwise, with
     * an unassociated alpha sample when any pixel carries alpha. Lossless: decoding the
     * result returns the exact same pixels.
     *
     * The file is little endian, in strips of about 8 KiB compressed with Deflate after
     * the horizontal differencing predictor, as libtiff writes with `-c zip -p 2`. The
     * overload with [TiffEncodeOptions] writes the other compressions, tiles, bi-level and
     * palette pages, and [encodeTiffCmyk] writes CMYK.
     */
    public fun encodeTiff(bitmap: KiteBitmap): ByteArray = TiffEncoder.encode(listOf(bitmap))

    /**
     * Encode [pages] as one multi-page TIFF, a directory a page in order, each written as
     * [encodeTiff] writes a single bitmap and marked with its page number.
     * [probe] reports their count as `pageCount`, and [decodePage] reads each back.
     *
     * @throws IllegalArgumentException if [pages] is empty
     */
    @Throws(IllegalArgumentException::class)
    public fun encodeTiff(pages: List<KiteBitmap>): ByteArray = TiffEncoder.encode(pages)

    /**
     * Encode [bitmap] as a 16-bit TIFF, keeping every bit [decode16] gives: gray for one or
     * two channels, RGB for three or four, the second or fourth an unassociated alpha
     * sample. Otherwise written as [encodeTiff] writes an 8-bit bitmap, and lossless:
     * [decode16] returns the same samples.
     */
    public fun encodeTiff16(bitmap: KiteBitmap16): ByteArray = TiffEncoder.encode16(listOf(bitmap))

    /**
     * Encode [pages] as one multi-page 16-bit TIFF, each page as [encodeTiff16] writes one.
     *
     * @throws IllegalArgumentException if [pages] is empty
     */
    @Throws(IllegalArgumentException::class)
    public fun encodeTiff16(pages: List<KiteBitmap16>): ByteArray = TiffEncoder.encode16(pages)

    /**
     * Encode [bitmap] as a TIFF as [options] says (#184): the compression (none, PackBits, LZW,
     * Deflate, CCITT Group 3 or 4, or JPEG), strips or tiles, and the samples (bi-level, gray,
     * palette or RGB). `TiffEncodeOptions()` gives what [encodeTiff] without options gives, byte
     * for byte. Every combination is lossless but JPEG, a bi-level page, and a gray page made from
     * colour: decoding the result returns the exact same pixels.
     *
     * [metadata]'s ICC profile goes in InterColorProfile (tag 34675) and its XMP packet in tag 700.
     * Its EXIF block is taken apart (#186): the descriptive tags of its IFD0 (make, model, date,
     * orientation, resolution and the like) join the page's directory in place of the writer's own
     * resolution, and its EXIF and GPS directories follow, rewritten little endian at new offsets,
     * behind tags 34665 and 34853. Its thumbnail directory (IFD1) is left out.
     *
     * [cancellationCheck], when given, runs before each strip or tile and every 64 rows of fax
     * coding, and may throw to stop the encode; what it throws comes out of this call as it is.
     *
     * @throws IllegalArgumentException if [options] do not fit [bitmap]: a CCITT compression with
     *   samples other than bi-level, or wider than 65536 pixels a strip or tile; JPEG with alpha or
     *   with bi-level or palette samples; a palette for a bitmap with alpha or more than 256
     *   colours; or a JPEG strip of rows that is not a whole number of blocks
     */
    @Throws(IllegalArgumentException::class)
    public fun encodeTiff(
        bitmap: KiteBitmap,
        options: TiffEncodeOptions,
        metadata: ImageMetadata = ImageMetadata(),
        cancellationCheck: (() -> Unit)? = null,
    ): ByteArray = TiffEncoder.encode(listOf(bitmap), options, metadata, cancellationCheck)

    /**
     * Encode [pages] as one multi-page TIFF, each page as [encodeTiff] with [options] writes one and
     * marked with its page number. Every page carries [metadata]'s ICC profile; the XMP packet and
     * the EXIF block go with the first.
     *
     * @throws IllegalArgumentException if [pages] is empty, or as [encodeTiff] with options does
     */
    @Throws(IllegalArgumentException::class)
    public fun encodeTiff(
        pages: List<KiteBitmap>,
        options: TiffEncodeOptions,
        metadata: ImageMetadata = ImageMetadata(),
        cancellationCheck: (() -> Unit)? = null,
    ): ByteArray = TiffEncoder.encode(pages, options, metadata, cancellationCheck)

    /**
     * Encode [bitmap] as a 16-bit TIFF as [options] says: none, PackBits, LZW or Deflate, strips or
     * tiles, gray or RGB samples. [TiffPhotometric.Auto] keeps the channels [bitmap] has, as
     * [encodeTiff16] without options does. [TiffPhotometric.Rgb] repeats a gray sample three times,
     * and [TiffPhotometric.Gray] takes the luma of a colour pixel. Alpha is kept. Metadata and
     * cancellation as [encodeTiff] with options.
     *
     * @throws IllegalArgumentException for a CCITT or JPEG compression, or bi-level or palette
     *   samples, which have no 16-bit form
     */
    @Throws(IllegalArgumentException::class)
    public fun encodeTiff16(
        bitmap: KiteBitmap16,
        options: TiffEncodeOptions,
        metadata: ImageMetadata = ImageMetadata(),
        cancellationCheck: (() -> Unit)? = null,
    ): ByteArray = TiffEncoder.encode16(listOf(bitmap), options, metadata, cancellationCheck)

    /**
     * Encode [pages] as one multi-page 16-bit TIFF, each page as [encodeTiff16] with [options]
     * writes one.
     *
     * @throws IllegalArgumentException if [pages] is empty, or as [encodeTiff16] with options does
     */
    @Throws(IllegalArgumentException::class)
    public fun encodeTiff16(
        pages: List<KiteBitmap16>,
        options: TiffEncodeOptions,
        metadata: ImageMetadata = ImageMetadata(),
        cancellationCheck: (() -> Unit)? = null,
    ): ByteArray = TiffEncoder.encode16(pages, options, metadata, cancellationCheck)

    /**
     * Encode CMYK [ink] as a TIFF of separations (photometric 5, InkSet 1, #184), lossless. [ink]
     * holds four samples a pixel, cyan, magenta, yellow and black, from no ink (0) to full (255), row
     * by row from the top, as [convertCmykToSrgb] takes them. Give [metadata] the press's ICC
     * profile, so that a reader can convert the ink; without one, [decode] draws it as libtiff's
     * RGBA interface does.
     *
     * [options] picks the compression and the layout as for [encodeTiff]; its photometric must be
     * [TiffPhotometric.Auto]. The predictor applies to the ink as to colour.
     *
     * @throws IllegalArgumentException if [ink] does not hold [width] times [height] pixels, a side
     *   is not positive, or [options] asks for CCITT, JPEG or a photometric other than Auto
     */
    @Throws(IllegalArgumentException::class)
    public fun encodeTiffCmyk(
        width: Int,
        height: Int,
        ink: ByteArray,
        options: TiffEncodeOptions = TiffEncodeOptions(),
        metadata: ImageMetadata = ImageMetadata(),
        cancellationCheck: (() -> Unit)? = null,
    ): ByteArray = TiffEncoder.encodeCmyk(width, height, ink, null, options, metadata, cancellationCheck)

    /** [encodeTiffCmyk] at 16 bits a sample, from no ink (0) to full (65535). */
    @Throws(IllegalArgumentException::class)
    public fun encodeTiffCmyk(
        width: Int,
        height: Int,
        ink: ShortArray,
        options: TiffEncodeOptions = TiffEncodeOptions(),
        metadata: ImageMetadata = ImageMetadata(),
        cancellationCheck: (() -> Unit)? = null,
    ): ByteArray = TiffEncoder.encodeCmyk(width, height, null, ink, options, metadata, cancellationCheck)

    /**
     * Encode [bitmap] as a lossless WebP: decoding the result returns the exact same
     * pixels, alpha included. It is tried as a palette when it has 256 colours or fewer,
     * and through the subtract-green, predictor and cross-colour transforms, with LZ77
     * and a colour cache, and the smallest of the results is kept. [WebpEncodeOptions] writes
     * lossy WebP.
     *
     * @throws IllegalArgumentException if a side of [bitmap] is larger than 16384, which a
     *   WebP cannot store
     */
    @Throws(IllegalArgumentException::class)
    public fun encodeWebp(bitmap: KiteBitmap): ByteArray = WebpEncoder.encode(bitmap)

    /**
     * Encode [bitmap] as a lossy WebP at [quality] 0..100, with the meaning of `cwebp -q`, and
     * otherwise [WebpEncodeOptions]' defaults: a `VP8 ` image in YUV 4:2:0, with its alpha, when any
     * pixel carries some, kept exact in an `ALPH` chunk (#178).
     *
     * @throws IllegalArgumentException if [quality] is outside 0..100, or a side of [bitmap] is
     *   larger than 16383, which a VP8 frame cannot store
     */
    @Throws(IllegalArgumentException::class)
    public fun encodeWebp(bitmap: KiteBitmap, quality: Int): ByteArray =
        WebpEncoder.encode(bitmap, WebpEncodeOptions(quality = quality), ImageMetadata(), null)

    /**
     * Encode [bitmap] as a WebP as [options] say (#178): lossless as [encodeWebp] without options
     * writes it, or lossy, a `VP8 ` image with its alpha in an `ALPH` chunk. [metadata] goes in the
     * extended format, as the overload that takes only metadata writes it: `VP8X`, `ICCP`, the
     * image, `EXIF` and `XMP `. A lossy image without alpha or metadata is the simple format, one
     * `VP8 ` chunk.
     *
     * [cancellationCheck], when given, runs after each row of macroblocks of a lossy image, and
     * once before a lossless one, and may throw to stop the encode; what it throws comes out of
     * this call as it is.
     *
     * @throws IllegalArgumentException if a side of [bitmap] is larger than a WebP stores: 16384
     *   lossless, 16383 lossy
     */
    @Throws(IllegalArgumentException::class)
    public fun encodeWebp(
        bitmap: KiteBitmap,
        options: WebpEncodeOptions,
        metadata: ImageMetadata = ImageMetadata(),
        cancellationCheck: (() -> Unit)? = null,
    ): ByteArray = WebpEncoder.encode(bitmap, options, metadata, cancellationCheck)

    /**
     * Encode [animation] as an animated WebP (#178): an `ANIM` chunk with the loop count and
     * [WebpEncodeOptions.background], and an `ANMF` chunk for each frame with its delay to the
     * millisecond, every frame lossless or every frame lossy as [options] say. Frames must all be
     * canvas-sized, which is exactly what [decodeAnimation] produces. A single frame is written as
     * a still image.
     *
     * Each frame after the first stores only the rectangle where it differs from what a decoder
     * shows by then, replacing that rectangle or, when every changed pixel is opaque, drawn over
     * it with the unchanged pixels transparent, whichever is smaller. Lossless frames decode to
     * the exact frames. A lossy frame is compared with the decoded frames before it, and leaves in
     * place only pixels within a few levels of the frame, fewer at a higher quality.
     *
     * [cancellationCheck] runs as the still encoder runs it, and after each frame.
     *
     * @throws IllegalArgumentException if the frames are not all canvas-sized, the canvas is
     *   larger than a WebP frame stores, a delay does not fit WebP's 24 bits, or the loop count
     *   is above 65535
     */
    @Throws(IllegalArgumentException::class)
    public fun encodeWebp(
        animation: KiteAnimation,
        options: WebpEncodeOptions,
        metadata: ImageMetadata = ImageMetadata(),
        cancellationCheck: (() -> Unit)? = null,
    ): ByteArray = WebpEncoder.encodeAnimation(animation, options, metadata, cancellationCheck)

    /**
     * Encode [bitmap] as a baseline JPEG at [quality] 1..100 (default 90).
     * Alpha is discarded (JPEG has none); quality ≤ 90 uses 4:2:0 chroma
     * subsampling, above that 4:4:4; stb_image_write's behavior.
     *
     * @throws IllegalArgumentException if [quality] is outside 1..100, or a side of [bitmap]
     *   is larger than 65535, which the frame header cannot store
     */
    @Throws(IllegalArgumentException::class)
    public fun encodeJpeg(bitmap: KiteBitmap, quality: Int = 90): ByteArray =
        JpegEncoder.encode(bitmap, quality)

    /**
     * [encodeJpeg] that also writes [metadata]: the EXIF block as an APP1 `Exif` segment, the
     * XMP packet as an APP1 Adobe XMP segment and the ICC profile as APP2 `ICC_PROFILE` chunks,
     * after the JFIF header (#129). An EXIF block or XMP packet too long for one segment, about
     * 64 KB, is left out. Pixels that a decode with `applyOrientation = true` turned upright take
     * [ImageMetadata.upright], or the copied orientation turns them again.
     *
     * A [ImageMetadata.gainMap] goes unchanged after the image, with an MPF segment and an XMP
     * directory that point to it, when [bitmap] has its aspect ratio; otherwise it is left out
     * together with the `hdrgm` fields of the XMP packet (#130).
     *
     * @throws IllegalArgumentException as [encodeJpeg] does
     */
    @Throws(IllegalArgumentException::class)
    public fun encodeJpeg(bitmap: KiteBitmap, quality: Int, metadata: ImageMetadata): ByteArray =
        JpegEncoder.encode(bitmap, quality, metadata)

    /**
     * [encodeJpeg] with the encoder [encoder]. [JpegEncoderKind.Jpegli] ports Google's Jpegli
     * (#133) with the settings its library gives by default: a baseline YCbCr file with 4:2:0
     * chroma at every quality, Jpegli's quantization matrices scaled by the distance [quality]
     * maps to, adaptive quantization and the standard Huffman tables, and no JFIF header. Its
     * files are smaller than [JpegEncoderKind.Fast]'s at the same [quality], which means
     * something else to each encoder, and it is several times slower.
     *
     * @throws IllegalArgumentException as [encodeJpeg] does
     */
    @Throws(IllegalArgumentException::class)
    public fun encodeJpeg(bitmap: KiteBitmap, quality: Int, encoder: JpegEncoderKind): ByteArray =
        JpegEncoder.encodeWith(bitmap, quality, null, encoder)

    /**
     * [encodeJpeg] with the encoder [encoder] that also writes [metadata], as the overload without
     * an encoder does. [JpegEncoderKind.Jpegli] writes no JFIF header, so its segments come right
     * after SOI.
     *
     * @throws IllegalArgumentException as [encodeJpeg] does
     */
    @Throws(IllegalArgumentException::class)
    public fun encodeJpeg(bitmap: KiteBitmap, quality: Int, metadata: ImageMetadata, encoder: JpegEncoderKind): ByteArray =
        JpegEncoder.encode(bitmap, quality, metadata, encoder)

    /**
     * Encode [bitmap] as a JPEG the way [options] ask (#183): baseline, progressive, arithmetic
     * coded or lossless, one component or three at the subsampling chosen, with optimized
     * Huffman tables and restart markers, and [metadata] written as the overload with metadata
     * writes it. Alpha is discarded. The defaults of [JpegEncodeOptions] write what
     * `encodeJpeg(bitmap, 90)` writes, except that a gray bitmap becomes one component.
     *
     * A lossless file from an 8-bit bitmap keeps every sample: [decode] returns the same pixels.
     * [cancellationCheck], when given, runs once a row of MCUs and once a scan, and may throw to
     * stop the encode; what it throws comes out of this call as it is.
     *
     * @throws IllegalArgumentException if [JpegEncodeOptions.precision] is not 8, which
     *   [encodeJpeg16] writes, if a side of [bitmap] is larger than 65535, or if a lossless
     *   restart interval does not hold whole rows
     */
    @Throws(IllegalArgumentException::class)
    public fun encodeJpeg(
        bitmap: KiteBitmap,
        options: JpegEncodeOptions,
        metadata: ImageMetadata = ImageMetadata(),
        cancellationCheck: (() -> Unit)? = null,
    ): ByteArray = JpegEncoder.encode(bitmap, options, metadata, cancellationCheck)

    /**
     * Encode [bitmap] as a JPEG of [JpegEncodeOptions.precision] bits (#183): a 12-bit DCT file
     * (extended sequential SOF1, progressive SOF2, or their arithmetic forms), or a lossless file
     * of 2 to 16 bits (SOF3, or SOF11 with arithmetic coding). Each sample is the top
     * [JpegEncodeOptions.precision] bits of its 16-bit value, so a lossless file of a bitmap that
     * [decode16] gave from a file of that precision comes back from [decode16] the same, and one
     * of 16 bits keeps every sample of any bitmap. One or two channels are gray, three or four
     * RGB; alpha is discarded. An 8-bit precision writes what [encodeJpeg] writes of the high
     * bytes.
     *
     * [metadata] and [cancellationCheck] work as in [encodeJpeg].
     *
     * @throws IllegalArgumentException if a side of [bitmap] is larger than 65535, or if a
     *   lossless restart interval does not hold whole rows
     */
    @Throws(IllegalArgumentException::class)
    public fun encodeJpeg16(
        bitmap: KiteBitmap16,
        options: JpegEncodeOptions,
        metadata: ImageMetadata = ImageMetadata(),
        cancellationCheck: (() -> Unit)? = null,
    ): ByteArray = JpegEncoder.encode16(bitmap, options, metadata, cancellationCheck)

    /**
     * [bytes], a JPEG, with the same quantized coefficients written again with Huffman tables
     * built for the image (#132), as `jpegtran -optimize -copy all` does: the file gets smaller,
     * and it decodes to the same pixels, byte for byte. Every segment outside the entropy coding
     * stays in its order, the `APPn` and `COM` segments, the quantization tables and the frame
     * header, and so does whatever follows the image, such as an Ultra HDR gain map, whose MPF
     * offsets move with it. The restart interval goes.
     *
     * With [progressive], the coefficients go out in a progressive scan script, which is often
     * smaller still and which a browser draws coarse first. A file this would not make smaller
     * comes back unchanged, and so do arithmetic-coded, lossless and hierarchical files and
     * anything that is not a JPEG it can read.
     */
    public fun repackJpeg(bytes: ByteArray, progressive: Boolean = false): ByteArray = JpegRepack.repack(bytes, progressive)

    /**
     * How close [distorted] looks to [reference], by SSIMULACRA 2 (#134): 100 for identical
     * images, 90 for visually lossless, 70 for artifacts hard to notice without the original, 50
     * for a JPEG of about quality 50, and lower, even below 0, for worse. It is a port of `tools/ssimulacra2.cc` of libjxl: both images go to
     * linear sRGB, then to the XYB colour space, and six scales of SSIM and edge maps are weighed
     * as the reference weighs them. As libjxl's `ssimulacra2` tool does, a [reference] with alpha
     * is scored blended onto a dark and onto a bright gray, and the worse score is returned;
     * otherwise alpha in [distorted] is blended onto a mid gray.
     *
     * It reads the images a row at a time and keeps only the half-size image of the next scale,
     * so a 12-megapixel photo takes about 70 MB besides the two bitmaps.
     *
     * @throws IllegalArgumentException if the two bitmaps are not of one size
     */
    @Throws(IllegalArgumentException::class)
    public fun ssimulacra2(reference: KiteBitmap, distorted: KiteBitmap): Double = Ssimulacra2.score(reference, distorted)

    /**
     * [encodePng] that also writes [metadata]: `iCCP`, `eXIf` and an `iTXt` chunk with the
     * keyword `XML:com.adobe.xmp`, before the image data (#129).
     */
    public fun encodePng(bitmap: KiteBitmap, metadata: ImageMetadata): ByteArray = PngEncoder.encode(bitmap, metadata)

    /**
     * [encodeWebp] that also writes [metadata], in the extended format: `VP8X`, `ICCP`, the
     * image, `EXIF` and `XMP ` (#129).
     *
     * @throws IllegalArgumentException as [encodeWebp] does
     */
    @Throws(IllegalArgumentException::class)
    public fun encodeWebp(bitmap: KiteBitmap, metadata: ImageMetadata): ByteArray = WebpEncoder.encode(bitmap, metadata)

    /**
     * Encode [bitmap] as a HEIC file at [quality], 0 to 100 (default 50), with the other settings of
     * [HeifEncodeOptions]: 8-bit 4:2:0, tiles of 512 for a larger picture. See the overload with options.
     *
     * @throws IllegalArgumentException if [quality] is outside 0..100, or the picture is too large for a
     *   HEIF reader
     * @throws UnsupportedImageException if the HEVC encoder of the `imagekodec-heic` module is not
     *   installed: add the module and call `ImageKodecHeic.install()`
     */
    @Throws(IllegalArgumentException::class, UnsupportedImageException::class)
    public fun encodeHeic(bitmap: KiteBitmap, quality: Int = 50): ByteArray = encodeHeic(bitmap, HeifEncodeOptions(quality = quality))

    /**
     * Encode [bitmap] as a HEIC file (#174): HEIF (ISO/IEC 23008-12) with HEVC pictures, coded by the
     * intra encoder of the `imagekodec-heic` module, which `ImageKodecHeic.install()` registers.
     *
     * Colour converts to Y, Cb and Cr with BT.601's matrix in full range and is signalled as sRGB
     * (`colr` `nclx` 1, 13, 6, full range), as libheif and libavif write it; a lossless file uses the
     * identity matrix in 4:4:4 and decodes to exactly [bitmap]. A gray bitmap codes luma alone, and a
     * bitmap with any alpha below 255 adds an alpha auxiliary image, straight (not premultiplied). A
     * picture wider or taller than [HeifEncodeOptions.tileSize] is a `grid` of tiles, which the runner of
     * [setTileRunner] encodes. [HeifEncodeOptions.thumbnailSize] adds a thumbnail item, and
     * [HeifEncodeOptions.orientation] writes `irot` and `imir`.
     *
     * [metadata] adds its ICC profile as a `colr` `prof` property, its EXIF block as an `Exif` item and
     * its XMP packet as a `mime` item, which [readMetadata] reads back; a gain map is not written.
     * [cancellationCheck] runs at least once a tile and once a row of coding blocks, and what it throws
     * comes out of this call as it is.
     *
     * @throws IllegalArgumentException if the picture is too large for a HEIF reader (65535 on a side, or
     *   more than 256 tiles on a side of a grid), or for one coded picture when [HeifEncodeOptions.tileSize]
     *   is null
     * @throws UnsupportedImageException if the HEVC encoder of the `imagekodec-heic` module is not
     *   installed: add the module and call `ImageKodecHeic.install()`
     */
    @Throws(IllegalArgumentException::class, UnsupportedImageException::class)
    public fun encodeHeic(
        bitmap: KiteBitmap,
        options: HeifEncodeOptions,
        metadata: ImageMetadata = ImageMetadata(),
        cancellationCheck: (() -> Unit)? = null,
    ): ByteArray = cancellable(cancellationCheck) { cancel -> HeifEncode.encode(bitmap, null, options, metadata, "hvc1", cancel) }

    /**
     * Encode [bitmap] as a HEIC file of 10 or 12 bits a sample ([HeifEncodeOptions.bitDepth]; by default
     * 10, or 12 when lossless), as [encodeHeic] writes an 8-bit bitmap. One or two channels code as gray,
     * and so do three or four whose colour channels are equal. HEVC holds 12 bits at most, so
     * [decode16] gives each sample back rounded to the coded depth; a lossless file gives back exactly
     * every sample that a file of its depth can give, such as the samples [decode16] read from one.
     *
     * @throws IllegalArgumentException as [encodeHeic] does
     * @throws UnsupportedImageException as [encodeHeic] does
     */
    @Throws(IllegalArgumentException::class, UnsupportedImageException::class)
    public fun encodeHeic16(
        bitmap: KiteBitmap16,
        options: HeifEncodeOptions,
        metadata: ImageMetadata = ImageMetadata(),
        cancellationCheck: (() -> Unit)? = null,
    ): ByteArray = cancellable(cancellationCheck) { cancel -> HeifEncode.encode(null, bitmap, options, metadata, "hvc1", cancel) }

    /**
     * Encode [bitmap] as an AVIF file at [quality], 0 to 100 (default 50), with the other settings of
     * [HeifEncodeOptions]: 8-bit 4:2:0, effort 2, tiles of 512 for a larger picture. See the overload with options.
     *
     * @throws IllegalArgumentException if [quality] is outside 0..100, or the picture is too large for a HEIF reader
     */
    @Throws(IllegalArgumentException::class)
    public fun encodeAvif(bitmap: KiteBitmap, quality: Int = 50): ByteArray = encodeAvif(bitmap, HeifEncodeOptions(quality = quality))

    /**
     * Encode [bitmap] as an AVIF file (#176): HEIF (ISO/IEC 23008-12) with AV1 pictures, coded by the core's own
     * intra AV1 encoder, as the AV1 Image File Format 1.1 describes. AV1 is royalty-free, so the encoder is part of
     * the core and needs no module.
     *
     * Each picture is one AV1 key frame with a reduced still picture header, as libavif writes a still image.
     * [HeifEncodeOptions.quality] sets the quantizer: base_q_idx 240 at 0, 117 at 50, 43 at 80 and 1 from 97 on,
     * straight between, so 50 and 80 score next to avifenc's `-q 50` and `-q 80`. [HeifEncodeOptions.lossless] codes at base_q_idx 0, AV1's lossless mode with its Walsh-Hadamard
     * transform, and the file decodes to exactly [bitmap]. [HeifEncodeOptions.effort] widens the encoder's search:
     * 1 is fast, 3 tries every mode and transform the encoder knows. Colour converts to Y, Cb and Cr with BT.601's
     * matrix in full range and is signalled as sRGB, both in `colr` and in the sequence header; a lossless file uses
     * the identity matrix in 4:4:4. A gray bitmap codes luma alone (AV1's monochrome), and alpha below 255 adds an
     * alpha auxiliary image. A picture wider or taller than [HeifEncodeOptions.tileSize] is a `grid` of AV1 items,
     * which the runner of [setTileRunner] encodes; with no tile size, a picture codes as one frame of AV1 tiles of at
     * most 4096 samples across. [HeifEncodeOptions.thumbnailSize], [HeifEncodeOptions.orientation] and [metadata]
     * are written as [encodeHeic] writes them. [cancellationCheck] runs at least once a tile and once a row of
     * superblocks, and what it throws comes out of this call as it is.
     *
     * @throws IllegalArgumentException if the picture is too large for a HEIF reader (65535 on a side, or more than
     *   256 tiles on a side of a grid)
     */
    @Throws(IllegalArgumentException::class)
    public fun encodeAvif(
        bitmap: KiteBitmap,
        options: HeifEncodeOptions,
        metadata: ImageMetadata = ImageMetadata(),
        cancellationCheck: (() -> Unit)? = null,
    ): ByteArray = cancellable(cancellationCheck) { cancel -> HeifEncode.encode(bitmap, null, options, metadata, "av01", cancel) }

    /**
     * Encode [animation] as an animated AVIF file (#177): an AV1 image sequence, a track of every frame with its delay,
     * that [decodeAnimation] plays and libavif and ffmpeg read. The first frame is also the primary image item,
     * sharing its bytes, as libavif writes a sequence, so a reader of still images shows it.
     *
     * The first frame is an AV1 key frame and the frames after it are inter frames, which predict each block from
     * the frame before or the one before that, by a motion search, and from effort 2 from both averaged; set
     * [HeifEncodeOptions.keyFrameInterval] for a key frame at least every that many frames. Each frame lasts its
     * [KiteFrame.delayMillis] (1 ms for a delay of 0) on a track of timescale 1000, and [KiteAnimation.loopCount]
     * goes in the edit list as libavif reads a play count, 0 playing for ever. A frame with alpha below 255 adds an
     * alpha track of its own, auxiliary to the colour track. Quality, lossless, chroma, depth, effort, orientation
     * and [metadata] act as [encodeAvif] says for a still, on every frame; a lossless file decodes to exactly the
     * frames. [HeifEncodeOptions.tileSize] and [HeifEncodeOptions.thumbnailSize] apply to stills only: a sequence
     * codes each frame as one AV1 frame. [cancellationCheck] runs at least once a frame and once a row of
     * superblocks, and what it throws comes out of this call as it is.
     *
     * @throws IllegalArgumentException if a frame's bitmap is not the animation's size, a delay is negative, or the
     *   picture is too large for a HEIF reader (65535 on a side)
     */
    @Throws(IllegalArgumentException::class)
    public fun encodeAvif(
        animation: KiteAnimation,
        options: HeifEncodeOptions,
        metadata: ImageMetadata = ImageMetadata(),
        cancellationCheck: (() -> Unit)? = null,
    ): ByteArray = cancellable(cancellationCheck) { cancel -> HeifEncode.encodeSequence(animation, options, metadata, "av01", cancel) }

    /**
     * Encode [animation] as an animated HEIC file (#177): an HEVC image sequence written as [encodeAvif] writes an
     * AV1 one, its pictures coded by the sequence encoder of the `hvc1` encoder that a module registers with
     * [registerHeifEncoder] ([HeifEncoder.openSequence]).
     *
     * @throws IllegalArgumentException as [encodeAvif] with an animation does
     * @throws UnsupportedImageException if no `hvc1` encoder is installed (add the `imagekodec-heic` module and call
     *   `ImageKodecHeic.install()`), or the one installed codes still pictures only
     */
    @Throws(IllegalArgumentException::class, UnsupportedImageException::class)
    public fun encodeHeic(
        animation: KiteAnimation,
        options: HeifEncodeOptions,
        metadata: ImageMetadata = ImageMetadata(),
        cancellationCheck: (() -> Unit)? = null,
    ): ByteArray = cancellable(cancellationCheck) { cancel -> HeifEncode.encodeSequence(animation, options, metadata, "hvc1", cancel) }

    /**
     * Encode [bitmap] as an AVIF file of 10 or 12 bits a sample ([HeifEncodeOptions.bitDepth]; by default 10, or 12
     * when lossless), as [encodeAvif] writes an 8-bit bitmap. One or two channels code as gray, and so do three or
     * four whose colour channels are equal. AV1 holds 12 bits at most, so [decode16] gives each sample back rounded
     * to the coded depth; a lossless file gives back exactly every sample that a file of its depth can give.
     *
     * @throws IllegalArgumentException as [encodeAvif] does
     */
    @Throws(IllegalArgumentException::class)
    public fun encodeAvif16(
        bitmap: KiteBitmap16,
        options: HeifEncodeOptions,
        metadata: ImageMetadata = ImageMetadata(),
        cancellationCheck: (() -> Unit)? = null,
    ): ByteArray = cancellable(cancellationCheck) { cancel -> HeifEncode.encode(null, bitmap, options, metadata, "av01", cancel) }

    /**
     * Encode [bitmap] as a lossy JPEG XR file (#181) at [quality] 0..100 (default 90), which
     * means what `JxrEncApp -q` means at [quality] divided by 100: YUV 4:4:4 and one level of
     * overlap filtering from 50 up, 4:2:0 and two levels below. A gray opaque bitmap is written
     * as 8bppGray, an opaque one as 24bppBGR, and one with alpha as 32bppBGRA with planar alpha,
     * coded losslessly.
     *
     * @throws IllegalArgumentException if [quality] is outside 0..100, or [bitmap] has more than
     *   2^28 pixels
     */
    @Throws(IllegalArgumentException::class)
    public fun encodeJxr(bitmap: KiteBitmap, quality: Int = 90): ByteArray =
        JxrEncoder.encode(bitmap, JxrEncodeOptions(quality = quality), ImageMetadata(), null)

    /**
     * Encode [bitmap] as a JPEG XR file (#181) with every setting of [options]: lossless or at a
     * quality, 4:4:4 or 4:2:0 chroma, the overlap mode, quantizers per channel and per tile,
     * tiles, spatial or frequency order, and planar or interleaved alpha. The ICC profile, XMP
     * packet and EXIF block of [metadata] go into the file's directory, the EXIF orientation as
     * its Transformation tag; a [ImageMetadata.gainMap] is left out. [cancellationCheck] runs
     * before each row of macroblocks of each codestream and may throw to stop the encode.
     *
     * @throws IllegalArgumentException if [bitmap] has more than 2^28 pixels, or [options] asks
     *   for tiles the image does not have
     */
    @Throws(IllegalArgumentException::class)
    public fun encodeJxr(
        bitmap: KiteBitmap,
        options: JxrEncodeOptions,
        metadata: ImageMetadata = ImageMetadata(),
        cancellationCheck: (() -> Unit)? = null,
    ): ByteArray = JxrEncoder.encode(bitmap, options, metadata, cancellationCheck)

    /**
     * [encodeJxr] for 16-bit samples: 16bppGray, 48bppRGB or 64bppRGBA, as [bitmap] has one,
     * three or four channels; gray with alpha goes as 64bppRGBA. Chroma stays 4:4:4.
     *
     * @throws IllegalArgumentException as [encodeJxr] does
     */
    @Throws(IllegalArgumentException::class)
    public fun encodeJxr16(
        bitmap: KiteBitmap16,
        options: JxrEncodeOptions,
        metadata: ImageMetadata = ImageMetadata(),
        cancellationCheck: (() -> Unit)? = null,
    ): ByteArray = JxrEncoder.encode16(bitmap, options, metadata, cancellationCheck)

    /**
     * Encode CMYK [ink] as a JPEG XR file of pixel format 32bppCMYK (#189), through T.832's
     * reversible transform to YUVK. [ink] holds four samples a pixel, cyan, magenta, yellow and
     * black, from no ink (0) to full (255), row by row from the top, as [convertCmykToSrgb] takes
     * them. Give [metadata] the press's ICC profile, so that a reader can convert the ink; without
     * one, [decode] draws it as it draws a CMYK TIFF. [options] works as for [encodeJxr]; chroma
     * stays full, since YUVK has no subsampled form.
     *
     * @throws IllegalArgumentException if [ink] does not hold [width] times [height] pixels, a side
     *   is not positive, the image has more than 2^28 pixels, or [options] asks for
     *   [JxrChroma.Half] or for tiles the image does not have
     */
    @Throws(IllegalArgumentException::class)
    public fun encodeJxrCmyk(
        width: Int,
        height: Int,
        ink: ByteArray,
        options: JxrEncodeOptions = JxrEncodeOptions(),
        metadata: ImageMetadata = ImageMetadata(),
        cancellationCheck: (() -> Unit)? = null,
    ): ByteArray = JxrEncoder.encodeCmyk(width, height, ink, null, options, metadata, cancellationCheck)

    /** [encodeJxrCmyk] at 16 bits a sample, from no ink (0) to full (65535), as 64bppCMYK. */
    @Throws(IllegalArgumentException::class)
    public fun encodeJxrCmyk(
        width: Int,
        height: Int,
        ink: ShortArray,
        options: JxrEncodeOptions = JxrEncodeOptions(),
        metadata: ImageMetadata = ImageMetadata(),
        cancellationCheck: (() -> Unit)? = null,
    ): ByteArray = JxrEncoder.encodeCmyk16(width, height, ink, null, options, metadata, cancellationCheck)

    /**
     * The EXIF block, XMP packet and ICC profile of a JPEG, PNG, WebP, JP2, AVIF, HEIC, TIFF, JPEG XL,
     * JPEG XR, GIF or BMP file. The encoders that take an [ImageMetadata] write them again, so a
     * re-encode keeps them (#129, #186). A JP2 file keeps the XMP and EXIF in UUID boxes and the
     * profile in its colour specification (#180). A HEIF file gives those of its primary image (#149);
     * its orientation is in `irot` and `imir`, which `decode` applies, never in the EXIF tag. The gain
     * map of an Ultra HDR JPEG comes too, found through its Multi-Picture Format segment (#130); the
     * gain map of a HEIF file, which needs a decode, comes from [decodeGainMap] (#153).
     *
     * A TIFF or JPEG XR file has no EXIF block of its own: its first directory points to EXIF and GPS
     * directories, and the block given is built of those and of the directory's descriptive tags,
     * little endian, and only when it points to either. A JPEG XL file gives its `Exif` and `xml `
     * boxes, also from a Brotli-compressed `brob` box, and the profile of its codestream. A GIF
     * gives its XMP and ICC application extensions, and a BMP the profile of a BITMAPV5HEADER.
     *
     * A file of another format, or one that has none, gives empty metadata; a damaged segment, chunk
     * or box is left out.
     */
    public fun readMetadata(data: ByteArray): ImageMetadata = MetadataIo.read(data)

    /**
     * Where the video of a Motion Photo sits, or null for a file that has none (#131): a JPEG with
     * a short MP4 video after the image, as Pixel and Samsung cameras save. Google's format names
     * the video in XMP, with `Camera:MotionPhoto` and a `Container:Directory` item of semantic
     * `MotionPhoto` and its `Item:Length`, or with the older `Camera:MicroVideo` and
     * `Camera:MicroVideoOffset`, under whatever prefixes the XMP binds to their namespaces. A
     * Samsung file puts it after a `MotionPhoto_Data` marker. A HEIC or AVIF file keeps it in its
     * top-level `mpvd` box, with `Camera:MotionPhoto` 1 in its XMP (#155).
     */
    public fun motionPhoto(data: ByteArray): MotionPhotoInfo? = MotionPhoto.find(data)

    /**
     * The still photo of a Motion Photo: every byte up to the video, with the XMP that named the
     * video rewritten to say there is none: `GCamera:MotionPhoto` 0, the video's directory item
     * and the MicroVideo fields removed. A gain map item and its bytes stay. A Samsung file is cut
     * at the end of its JPEG, with its trailer. A HEIC or AVIF file loses its `mpvd` box and keeps
     * its XMP, as Motion Photo 1.0 allows. A file with no video comes back as a copy, and the photo
     * decodes to the same pixels (#131, #155).
     *
     * @throws ImageDecodeException for a HEIF file whose `mpvd` box other boxes follow, since
     *   removing it would move their data
     */
    @Throws(ImageDecodeException::class)
    public fun removeMotion(data: ByteArray): ByteArray = MotionPhoto.remove(data)

    /**
     * Encode [bitmap] as a BMP: 24-bit BI_RGB when it is opaque, or 32-bit
     * BI_BITFIELDS under a V4 header when it carries alpha (plain 32-bit BMP
     * alpha is officially "reserved", and half the world's readers discard it).
     * Uncompressed and lossless either way.
     */
    public fun encodeBmp(bitmap: KiteBitmap): ByteArray = BmpEncoder.encode(bitmap)

    /**
     * Encode [bitmap] as a BMP of the depth [options] gives (#184): a palette of 1, 4 or 8 bits, with
     * BI_RLE4 or BI_RLE8 on request, 16-bit 5-5-5, 24-bit BI_RGB or 32-bit BI_BITFIELDS with alpha.
     * `BmpEncodeOptions()` gives what [encodeBmp] without options gives, byte for byte. Every depth
     * but 16 is lossless.
     *
     * @throws IllegalArgumentException if [bitmap] has alpha and the depth is not 32, or has more
     *   colours than a palette depth holds
     */
    @Throws(IllegalArgumentException::class)
    public fun encodeBmp(bitmap: KiteBitmap, options: BmpEncodeOptions): ByteArray = BmpEncoder.encode(bitmap, options)

    /**
     * [encodeBmp] with [options] that also embeds [metadata]'s ICC profile (#186): with a profile,
     * the header is a BITMAPV5HEADER whose colour space is PROFILE_EMBEDDED, and the profile
     * follows the pixels; without one, the file is what the overload without metadata writes. A
     * BMP has no place for EXIF or XMP, so those are left out. [readMetadata] reads the profile back.
     *
     * @throws IllegalArgumentException as the overload without metadata does, or if the file with
     *   its profile would pass 2 GB
     */
    @Throws(IllegalArgumentException::class)
    public fun encodeBmp(bitmap: KiteBitmap, options: BmpEncodeOptions, metadata: ImageMetadata): ByteArray =
        BmpEncoder.encode(bitmap, options, metadata)

    /**
     * Encode [bitmap] as a GIF89a. Colours are quantised to the format's 256-entry
     * limit; images that already fit encode losslessly. Alpha is reduced to GIF's
     * single transparent index at a 50% threshold.
     *
     * [dither] applies Floyd-Steinberg error diffusion when quantisation actually
     * loses colours, which is what keeps a photograph from banding. It is ignored
     * when the palette came out exact, since there is no error to spread.
     *
     * @throws IllegalArgumentException if a side of [bitmap] is larger than 65535, which a GIF
     *   cannot store
     */
    @Throws(IllegalArgumentException::class)
    public fun encodeGif(bitmap: KiteBitmap, dither: Boolean = true): ByteArray =
        GifEncoder.encode(bitmap, dither)

    /**
     * Encode [animation] as an animated GIF89a: one shared colour table, per-frame
     * delays, and the NETSCAPE2.0 loop count. Frames must all be canvas-sized,
     * which is exactly what [decodeAnimation] produces.
     *
     * @throws IllegalArgumentException if the canvas is larger than 65535 on a side, which a GIF
     *   cannot store, if the frames are not all canvas-sized, or if the total play
     *   count is outside GIF's 0..65536 range (0 means forever)
     */
    @Throws(IllegalArgumentException::class)
    public fun encodeGif(animation: KiteAnimation, dither: Boolean = true): ByteArray =
        GifEncoder.encode(animation, dither)

    /**
     * [encodeGif] that also writes [metadata] (#186), in application extensions before the first
     * frame: the ICC profile in an `ICCRGBG1` `012` extension (ICC.1:2022, B.6), and the XMP
     * packet in an `XMP DataXMP` extension with the trailer XMP Part 3 gives it. A GIF has no place
     * for EXIF, so it is left out. The pixels are those the overload without metadata writes, and
     * [readMetadata] reads both blocks back.
     *
     * @throws IllegalArgumentException as the overload without metadata does
     */
    @Throws(IllegalArgumentException::class)
    public fun encodeGif(bitmap: KiteBitmap, metadata: ImageMetadata, dither: Boolean = true): ByteArray =
        GifEncoder.encode(bitmap, dither, metadata)

    /**
     * [encodeGif] of [animation] that also writes [metadata], as the still overload with metadata does.
     *
     * @throws IllegalArgumentException as the overload without metadata does
     */
    @Throws(IllegalArgumentException::class)
    public fun encodeGif(animation: KiteAnimation, metadata: ImageMetadata, dither: Boolean = true): ByteArray =
        GifEncoder.encode(animation, dither, metadata)

    /**
     * Encode [bitmap] as a lossy JPEG 2000 file (JP2) at [quality] 0..100 (default 75), as
     * [Jp2EncodeOptions] describes with its defaults: the 9/7 wavelet over five resolution levels,
     * code-blocks truncated until the error meets a peak signal-to-noise ratio of
     * `20 + 0.28 * quality` dB. Gray when every pixel is gray, RGB otherwise, with an opacity
     * channel when any pixel carries alpha. [decode] reads it back, and so does OpenJPEG.
     *
     * @throws IllegalArgumentException if [quality] is outside 0..100
     */
    @Throws(IllegalArgumentException::class)
    public fun encodeJp2(bitmap: KiteBitmap, quality: Int = 75): ByteArray =
        encodeJp2(bitmap, Jp2EncodeOptions(quality = quality))

    /**
     * Encode [bitmap] as JPEG 2000 with every choice of [options] (#180): lossy at a quality or at
     * a compression ratio a layer, or lossless with the reversible 5/3 wavelet and colour transform,
     * in a JP2 file or as a bare codestream, in tiles, resolution levels, code-blocks, precincts and
     * quality layers of the sizes asked for, in any progression order. Gray when every pixel is
     * gray, RGB otherwise, with an opacity channel, which a channel definition box (`cdef`)
     * describes, when any pixel carries alpha. A lossless file decodes to [bitmap] exactly.
     *
     * A JP2 file carries [metadata]: the ICC profile as its colour specification when the profile
     * is gray or RGB as the image is, else the enumerated sRGB or greyscale space; the XMP packet in
     * the UUID box XMP assigns to JPEG 2000; and the EXIF block in the UUID box exiftool reads. A
     * bare codestream carries none of it. [cancellationCheck] runs before each tile and each
     * code-block, and what it throws comes out of this call as it is.
     *
     * @throws IllegalArgumentException if [options] asks more than 8 bits a sample of a [KiteBitmap],
     *   or tiles so small that the image needs more than 65535 of them
     */
    @Throws(IllegalArgumentException::class)
    public fun encodeJp2(
        bitmap: KiteBitmap,
        options: Jp2EncodeOptions,
        metadata: ImageMetadata = ImageMetadata(),
        cancellationCheck: (() -> Unit)? = null,
    ): ByteArray = cancellable(cancellationCheck) { cancel -> Jp2Encoder.encode(bitmap, options, metadata, cancel) }

    /**
     * [encodeJp2] for a [KiteBitmap16]: its channels as components, 1 gray, 2 gray and alpha, 3 RGB,
     * 4 RGBA, at 16 bits a sample or at [Jp2EncodeOptions.bitsPerSample], each sample scaled to that
     * depth and rounded. Lossless, [decode16] returns the samples of a 16-bit file exactly, and those
     * of a shallower one as it widens them, so a bitmap [decode16] read from a 12-bit file comes back
     * the same at 12 bits.
     *
     * @throws IllegalArgumentException if [options] asks tiles so small that the image needs more than
     *   65535 of them
     */
    @Throws(IllegalArgumentException::class)
    public fun encodeJp2Wide(
        bitmap: KiteBitmap16,
        options: Jp2EncodeOptions = Jp2EncodeOptions(),
        metadata: ImageMetadata = ImageMetadata(),
        cancellationCheck: (() -> Unit)? = null,
    ): ByteArray = cancellable(cancellationCheck) { cancel -> Jp2Encoder.encode16(bitmap, options, metadata, cancel) }

    /**
     * Encode [bitmap] as a lossy JPEG XL at [quality] 0..100 (default 90), which maps to a
     * distance as `cjxl -q` maps it; see [JxlEncodeOptions]. The colour is coded with VarDCT in
     * the XYB colour space and alpha, when the bitmap has any, losslessly with Modular. The
     * result is a bare codestream (#179).
     *
     * @throws IllegalArgumentException if [quality] is outside 0..100, or a side of [bitmap] is
     *   larger than 262144
     */
    @Throws(IllegalArgumentException::class)
    public fun encodeJxl(bitmap: KiteBitmap, quality: Int = 90): ByteArray =
        JxlEncoder.encode(bitmap, JxlEncodeOptions(quality = quality), ImageMetadata(), null)

    /**
     * Encode [bitmap] as a JPEG XL with [options]: lossy VarDCT at a distance, or, with
     * [JxlEncodeOptions.lossless], lossless Modular, which decodes to exactly the input. A
     * bitmap whose pixels are all gray is coded as gray when lossless, and alpha is kept only
     * when some pixel is not opaque. [metadata]'s ICC profile goes into the codestream, which
     * then describes the samples; its EXIF block and XMP packet go into `Exif` and `xml `
     * boxes, which put the codestream in the ISO BMFF container. [cancellationCheck] runs
     * between the steps of the encode and may throw to stop it (#179).
     *
     * @throws IllegalArgumentException if a side of [bitmap] is larger than 262144
     */
    @Throws(IllegalArgumentException::class)
    public fun encodeJxl(
        bitmap: KiteBitmap,
        options: JxlEncodeOptions,
        metadata: ImageMetadata = ImageMetadata(),
        cancellationCheck: (() -> Unit)? = null,
    ): ByteArray = JxlEncoder.encode(bitmap, options, metadata, cancellationCheck)

    /**
     * [encodeJxl] for samples of 16 bits: gray, gray and alpha, RGB or RGBA as [bitmap] holds
     * them, with a bit depth of 16 in the header. A lossless file decodes to exactly the input
     * with [decode16] (#179).
     *
     * @throws IllegalArgumentException as [encodeJxl] does
     */
    @Throws(IllegalArgumentException::class)
    public fun encodeJxl16(
        bitmap: KiteBitmap16,
        options: JxlEncodeOptions,
        metadata: ImageMetadata = ImageMetadata(),
        cancellationCheck: (() -> Unit)? = null,
    ): ByteArray = JxlEncoder.encode16(bitmap, options, metadata, cancellationCheck)

    /**
     * Encode [animation] as an animated JPEG XL: one frame for each of its frames, with its
     * delay in ticks of a millisecond, and its play count (0 for forever). A frame that changes
     * only a rectangle of the one before is coded as that rectangle over a saved copy of it.
     * Lossy or lossless as [options] says (#179).
     *
     * @throws IllegalArgumentException if a frame is not of the canvas's size, a delay is
     *   negative, the play count does not fit 32 bits, or a side is larger than 262144
     */
    @Throws(IllegalArgumentException::class)
    public fun encodeJxl(
        animation: KiteAnimation,
        options: JxlEncodeOptions,
        metadata: ImageMetadata = ImageMetadata(),
        cancellationCheck: (() -> Unit)? = null,
    ): ByteArray = JxlEncoder.encode(animation, options, metadata, cancellationCheck)

    /**
     * Encode [bitmap] as CCITT Group 4 fax data (ITU-T T.6), ended with EOFB, as [CcittEncodeOptions]
     * gives by default. See the overload with options.
     *
     * @throws IllegalArgumentException as the overload with options does
     */
    @Throws(IllegalArgumentException::class)
    public fun encodeCcitt(bitmap: KiteBitmap): ByteArray = encodeCcitt(bitmap, CcittEncodeOptions())

    /**
     * Encode [bitmap] as CCITT fax data (#185): Group 4, or Group 3 one- or two-dimensional, framed as
     * [options] says. A pixel is black when its luma (BT.601 weights), laid over white by its alpha,
     * is below half, so a transparent pixel is white; the coding of that bilevel page is lossless.
     *
     * Fax data carries no size or parameters of its own. A PDF image needs `/CCITTFaxDecode` with
     * `/K`, `/Columns` (the width), `/Rows` (the height) and the flags of [options] that differ from
     * PDF's defaults; a TIFF needs compression 4, or 3 with `T4Options`, or 2. [CcittFax.decode]
     * reads the data back given the same values, and [CcittFax.encode] takes a packed page.
     *
     * [cancellationCheck], when given, runs every 64 rows and may throw to stop the encode; what it
     * throws comes out of this call as it is.
     *
     * @throws IllegalArgumentException if [bitmap] is wider than 65536 pixels
     */
    @Throws(IllegalArgumentException::class)
    public fun encodeCcitt(
        bitmap: KiteBitmap,
        options: CcittEncodeOptions,
        cancellationCheck: (() -> Unit)? = null,
    ): ByteArray {
        require(bitmap.width <= CCITT_MAX_ENCODE_COLUMNS) { "CCITT: ${bitmap.width} columns is more than $CCITT_MAX_ENCODE_COLUMNS" }
        val rows = bilevelRows(bitmap, options.blackIs1, cancellationCheck)
        return encodeCcittRows(
            rows, bitmap.width, bitmap.height, options.k, options.blackIs1, options.encodedByteAlign,
            options.endOfLine, options.endOfBlock, cancellationCheck,
        )
    }

    /**
     * Encode [bitmap] as a JBIG2 generic region with template 0 and typical prediction, in the
     * embedded form a PDF takes, as [Jbig2EncodeOptions] gives by default. See the overload with
     * options.
     */
    public fun encodeJbig2(bitmap: KiteBitmap): ByteArray = encodeJbig2(bitmap, Jbig2EncodeOptions())

    /**
     * Encode [bitmap] as JBIG2 (ITU-T T.88, #185): one page holding one generic region, coded with the
     * MQ arithmetic coder or, with [Jbig2EncodeOptions.mmr], with T.6. A pixel is black when its luma
     * (BT.601 weights), laid over white by its alpha, is below half, so a transparent pixel is white;
     * the coding of that bilevel page is lossless.
     *
     * By default the result is the embedded stream of PDF's `/JBIG2Decode`: a page information
     * segment and an immediate generic region segment, with no global stream. With
     * [Jbig2EncodeOptions.standalone] it is a `.jb2` file. [Jbig2Decoder.decode] reads the
     * embedded form back. Symbol dictionaries and text regions, which pay only for text and which
     * other encoders make lossy, are not written.
     *
     * [cancellationCheck], when given, runs every 64 rows and may throw to stop the encode; what it
     * throws comes out of this call as it is.
     */
    public fun encodeJbig2(
        bitmap: KiteBitmap,
        options: Jbig2EncodeOptions,
        cancellationCheck: (() -> Unit)? = null,
    ): ByteArray {
        val rows = bilevelRows(bitmap, blackIs1 = true, cancel = cancellationCheck)
        return Jbig2Encoder.encode(
            rows, bitmap.width, bitmap.height, options.template, options.typicalPrediction, options.mmr,
            options.standalone, cancellationCheck,
        )
    }

    /** JPEG 2000 → ARGB via the JPX codec (gray replicated, cdef alpha honored), each side divided by [reduction]. */
    private fun jp2ToBitmap(data: ByteArray, reduction: Int = 1, cancel: (() -> Unit)? = null): KiteBitmap {
        val r = JpxDecoder.decodeForFacade(data, reduction, cancel)
        val n = if (r.colorSpace == "DeviceRGB") 3 else 1
        val argb = IntArray(r.width * r.height)
        for (i in argb.indices) {
            val a = r.alpha?.let { it[i].toInt() and 0xFF } ?: 0xFF
            if (n == 3) {
                argb[i] = (a shl 24) or
                    ((r.pixelBytes[i * 3].toInt() and 0xFF) shl 16) or
                    ((r.pixelBytes[i * 3 + 1].toInt() and 0xFF) shl 8) or
                    (r.pixelBytes[i * 3 + 2].toInt() and 0xFF)
            } else {
                val g = r.pixelBytes[i].toInt() and 0xFF
                argb[i] = (a shl 24) or (g shl 16) or (g shl 8) or g
            }
        }
        return KiteBitmap(r.width, r.height, argb)
    }

    /**
     * Decode [data] as an animation. GIF, APNG, animated WebP, AVIF image sequences
     * and animated JPEG XL return every composited frame with delays and the loop count;
     * static formats return a single zero-delay frame, so this is safe to call on
     * anything [decode] accepts.
     *
     * [maxFrames] stops the decode after that many frames, and nothing past them
     * is read, so a damaged later frame does not fail it. `1` gives the first
     * frame of the animation as it plays, for a thumbnail or a paused image: for
     * an APNG whose default image sits outside the animation that is not what
     * [decode] returns. The result keeps the file's delays and loop count, so it
     * plays as only those frames would.
     *
     * [cancellationCheck], when given, runs between frames of a multi-frame
     * decode and may throw to abandon work whose result no longer matters
     * (pass `{ coroutineContext.ensureActive() }` from a coroutine). A still in a
     * format that cannot animate, and an AVIF or HEIC still, run it as
     * [decode] does.
     *
     * [applyOrientation] honours the EXIF orientation tag on every frame, the
     * same way [decode] does for a still.
     *
     * @throws IllegalArgumentException if [maxFrames] is less than 1
     */
    @Throws(ImageDecodeException::class, IllegalArgumentException::class)
    public fun decodeAnimation(
        data: ByteArray,
        applyOrientation: Boolean = false,
        maxFrames: Int = Int.MAX_VALUE,
        colorTarget: ColorTarget = ColorTarget.Source,
        cancellationCheck: (() -> Unit)? = null,
    ): KiteAnimation {
        require(maxFrames >= 1) { "maxFrames must be at least 1, was $maxFrames" }
        val format = detect(data)
        // A deep still in a format that can animate converts as decode converts it, at its own
        // precision, so the two give the same pixels: narrowed first, a steep curve crushes the
        // tones that only the low byte holds (#113).
        val deepStill = colorTarget is ColorTarget.Srgb &&
            (format == ImageFormat.PNG || format == ImageFormat.AVIF || format == ImageFormat.HEIC || format == ImageFormat.JXL) &&
            probeOrNull(data)?.let { it.frameCount == 1 && it.bitDepth > 8 } == true
        val animation = when (if (deepStill) null else format) {
            ImageFormat.GIF -> GifDecoder.decode(data, maxFrames, cancellationCheck)
            ImageFormat.PNG -> PngDecoder.decodeAnimation(data, maxFrames, cancellationCheck)
            ImageFormat.WEBP -> WebpDecoder.decodeAnimation(data, maxFrames, cancellationCheck)
            ImageFormat.AVIF, ImageFormat.HEIC -> AvifDecoder.decodeAnimation(data, maxFrames, cancellationCheck)
            ImageFormat.JXL -> JxlDecoder.decodeAnimation(data, maxFrames, cancellationCheck)
            else -> {
                // A still converts as decode converts it, CMYK ink included.
                val single = decode(data, colorTarget = colorTarget, cancellationCheck = cancellationCheck)
                return KiteAnimation(
                    width = single.width,
                    height = single.height,
                    frames = listOf(KiteFrame(single, delayMillis = 0, delayRawCentiseconds = 0)),
                    loopCount = 1,
                ).let { if (applyOrientation) it.oriented(probeOrNull(data)?.orientation ?: Orientation.Normal) else it }
            }
        }.let { decoded ->
            val converter = conversion(data, 0, colorTarget)?.converter ?: return@let decoded
            KiteAnimation(
                decoded.width, decoded.height,
                decoded.frames.map { KiteFrame(applied(converter, it.bitmap) ?: it.bitmap, it.delayMillis, it.delayRawCentiseconds) },
                decoded.loopCount,
            )
        }
        if (!applyOrientation) return animation
        val orientation = probeOrNull(data)?.orientation ?: Orientation.Normal
        return animation.oriented(orientation)
    }
}
