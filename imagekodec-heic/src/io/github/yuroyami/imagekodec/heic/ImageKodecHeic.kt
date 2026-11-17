package io.github.yuroyami.imagekodec.heic

import io.github.yuroyami.imagekodec.ImageKodec

/**
 * The entry point of the HEIC module: it adds the HEVC codec to the HEIF reader of the core.
 *
 * ```kotlin
 * ImageKodecHeic.install()
 * val info = ImageKodec.probe(heicBytes)
 * ```
 *
 * The module decodes intra, P and B pictures of HEVC in 4:0:0, 4:2:0, 4:2:2 and 4:4:4 at 8 to 16 bits,
 * with both loop filters, tiles, wavefront rows and dependent slice segments, and HEVC image sequences
 * through `ImageKodec.decodeAnimation`, in presentation order. A picture that needs a tool it lacks,
 * such as palette mode, reports why in `ImageInfo.unsupportedReason`, and `decode` throws
 * `UnsupportedImageException`.
 */
public object ImageKodecHeic {
    /**
     * Registers the HEVC codec of `hvc1` items and of `hvc1` and `hev1` tracks with
     * [ImageKodec.registerHeifCodec]. Call it once at startup; a second call changes nothing.
     * It is safe to call from several threads.
     */
    public fun install() {
        // The registry replaces a codec of the same item type, so registering again leaves it as it was.
        ImageKodec.registerHeifCodec(HevcCodec)
    }
}
