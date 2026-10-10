package io.github.yuroyami.imagekodec

import kotlin.io.encoding.Base64

/**
 * JPEG 2000 files whose last component is opacity with nothing to say so: OpenJPEG 2.5's
 * `opj_compress -n 3` wrote them from 24 by 16 PAM files, red 10x, green 15y and blue 200 (gray
 * 10x alone), with opacity 0 on the left half and 255 on the right. Neither writes a cdef box.
 */
internal object Jp2AlphaFixtures {

    /** RGBA, as a bare codestream. */
    val rgbaCodestream: ByteArray = Base64.decode(
        "/0//UQAyAAAAAAAYAAAAEAAAAAAAAAAAAAAAGAAAABAAAAAAAAAAAAAEBwEBBwEBBwEBBwEB/1IADAAAAAEBAgQEAAH/XAAK" +
        "QEBISFBISFD/ZAAlAAFDcmVhdGVkIGJ5IE9wZW5KUEVHIHZlcnNpb24gMi41LjD/kAAKAAAAAADvAAH/k8+0XBJcvnxRyvgv" +
        "UU+1KGlznessvKw0Jx/D34CQE4xJjqYKpKX8A42NEIJXC+l/34C4AKDB1Eq3L5uc9wz3uxRyAVtJW1OnPuTfgMARUCLSouX9" +
        "o3Akx1fuM3IEZ8dylh/KOLPAfCHB84OAEQAsW88DgmEuf6H1AwAA0llWZp/B84OH1AwsW9MA0llWZp/PwCgcTR3Nt9Ra73Df" +
        "wDpMD5BcAJhLJYkmHKdB0nJSKkwKhQQ3b3/fXxP/SFf/f6B8gUBS5q+2b8D5A0D5AoBLJVTinrdS5q+2b8faCgArsLgcP//Z",
    )

    /** Gray and opacity, as a bare codestream. */
    val grayAlphaCodestream: ByteArray = Base64.decode(
        "/0//UQAsAAAAAAAYAAAAEAAAAAAAAAAAAAAAGAAAABAAAAAAAAAAAAACBwEBBwEB/1IADAAAAAEAAgQEAAH/XAAKQEBISFBI" +
        "SFD/ZAAlAAFDcmVhdGVkIGJ5IE9wZW5KUEVHIHZlcnNpb24gMi41LjD/kAAKAAAAAABlAAH/k9+AkBFlNTw+8qlAAxjsZ6jW" +
        "aypaa9+AwBFQItKi5f2jcCTHV+4zcgRnx3KWH8o4s8HzgwAsW9PPwCgcTR3Nt9Ra73DfwPkDAEslVOKet8faCgArsLgcP//Z",
    )

    /** RGBA, in a JP2 file whose colr says sRGB. */
    val rgbaJp2: ByteArray = Base64.decode(
        "AAAADGpQICANCocKAAAAFGZ0eXBqcDIgAAAAAGpwMiAAAAAtanAyaAAAABZpaGRyAAAAEAAAABgABAcHAAAAAAAPY29scgEA" +
        "AAAAABAAAAFwanAyY/9P/1EAMgAAAAAAGAAAABAAAAAAAAAAAAAAABgAAAAQAAAAAAAAAAAABAcBAQcBAQcBAQcBAf9SAAwA" +
        "AAABAQIEBAAB/1wACkBASEhQSEhQ/2QAJQABQ3JlYXRlZCBieSBPcGVuSlBFRyB2ZXJzaW9uIDIuNS4w/5AACgAAAAAA7wAB" +
        "/5PPtFwSXL58Ucr4L1FPtShpc53rLLysNCcfw9+AkBOMSY6mCqSl/AONjRCCVwvpf9+AuACgwdRKty+bnPcM97sUcgFbSVtT" +
        "pz7k34DAEVAi0qLl/aNwJMdX7jNyBGfHcpYfyjizwHwhwfODgBEALFvPA4JhLn+h9QMAANJZVmafwfODh9QMLFvTANJZVmaf" +
        "z8AoHE0dzbfUWu9w38A6TA+QXACYSyWJJhynQdJyUipMCoUEN29/318T/0hX/3+gfIFAUuavtm/A+QNA+QKASyVU4p63Uuav" +
        "tm/H2goAK7C4HD//2Q==",
    )
}
