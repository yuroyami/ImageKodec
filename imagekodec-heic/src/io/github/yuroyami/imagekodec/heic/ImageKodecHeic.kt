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
 * The module reads the parameter sets and slice headers of HEVC today, so `probe` reports the coded
 * size, depths and colour of a HEIC file. Decoding the pictures is not implemented yet: every HEVC
 * item reports why in `ImageInfo.unsupportedReason`, and `decode` throws `UnsupportedImageException`.
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
