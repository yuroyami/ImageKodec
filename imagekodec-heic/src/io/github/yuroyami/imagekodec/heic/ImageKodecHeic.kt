package io.github.yuroyami.imagekodec.heic

import io.github.yuroyami.imagekodec.ImageKodec

/**
 * The entry point of the HEIC module: it adds the HEVC codec to the HEIF reader of the core, and the
 * intra HEVC encoder to its HEIF writer.
 *
 * ```kotlin
 * ImageKodecHeic.install()
 * val info = ImageKodec.probe(heicBytes)
 * val heic = ImageKodec.encodeHeic(bitmap, quality = 60)
 * ```
 *
 * The module decodes intra, P and B pictures of HEVC in 4:0:0, 4:2:0, 4:2:2 and 4:4:4 at 8 to 16 bits,
 * with the tools of the range and screen content coding extensions, separate colour planes, both loop
 * filters, tiles, wavefront rows and dependent slice segments, and HEVC image sequences through
 * `ImageKodec.decodeAnimation`, in presentation order. A picture that needs a tool it lacks, such as a
 * CTB of 8 samples, reports why in `ImageInfo.unsupportedReason`, and `decode` throws
 * `UnsupportedImageException`.
 */
public object ImageKodecHeic {
    /**
     * Registers the HEVC codec of `hvc1` items and of `hvc1` and `hev1` tracks with
     * [ImageKodec.registerHeifCodec], and the intra HEVC encoder of `hvc1` items with
     * [ImageKodec.registerHeifEncoder], which `ImageKodec.encodeHeic` uses. Call it once at startup;
     * a second call changes nothing. It is safe to call from several threads.
     */
    public fun install() {
        // The registries replace a codec of the same item type, so registering again leaves them as they were.
        ImageKodec.registerHeifCodec(HevcCodec)
        ImageKodec.registerHeifEncoder(HevcHeifEncoder)
    }
}
