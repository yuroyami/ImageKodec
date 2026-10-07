# ImageKodec: porting references

This catalog distinguishes adapted Kotlin code, reference sources consulted,
and specification implementations. Third-party reference source trees are
ignored by Git and are not distributed; this catalog is tracked. A source port
must retain its applicable copyright and license notice, and identify alterations.

> **Deliberately NOT fetched: AVIF / HEIC references (dav1d, libheif, libde265).**
> AV1/HEVC decoders are orders of magnitude beyond a realistic pure-Kotlin port, and
> libde265/x265 carry HEVC patent baggage on top. Both formats are permanently out of
> scope for the core (a platform-backed decode could someday live in a separate opt-in
> module, never here). Same story for JPEG XL for now: revisit if demand appears.

| ImageKodec target | Reference tree | Key files | License |
|---|---|---|---|
| **Primary all-rounder**: JPEG (baseline+progressive), PNG, GIF and BMP decode | `stb` | `stb_image.h` (single file, ~8k lines, readable) | public domain / MIT |
| JPEG + BMP **encode** | `stb` | `stb_image_write.h` | public domain / MIT |
| PNG encode (cleanest standalone ref) | `lodepng` | `lodepng.cpp`, `lodepng.h` | zlib |
| TIFF decode and **EXIF/metadata layer** | `commons-imaging` | `src/main/java/org/apache/commons/imaging/formats/tiff/*`, `common/bytesource/*`, `formats/tiff/taginfos/*` | Apache-2.0 |
| GIF **encode** (pure-Java writer, mechanical port) | `commons-imaging` | `src/main/java/org/apache/commons/imaging/formats/gif/*` | Apache-2.0 |

JPEG table lifetime follows [libjpeg-turbo `jdinput.c`](https://github.com/libjpeg-turbo/libjpeg-turbo/blob/main/src/jdinput.c), under its [IJG/BSD-compatible license](https://github.com/libjpeg-turbo/libjpeg-turbo/blob/main/LICENSE.md). Each component keeps its table from the first scan; no source is vendored.

The JPEG decoder departs from stb_image where libjpeg-turbo, under the same license, reads a file differently: the right-edge weight of 4:2:2 upsampling follows `h2v1_fancy_upsample` in [`jdsample.c`](https://github.com/libjpeg-turbo/libjpeg-turbo/blob/main/src/jdsample.c), and the color space of a four-component file follows `default_decompress_parms` in [`jdapimin.c`](https://github.com/libjpeg-turbo/libjpeg-turbo/blob/main/src/jdapimin.c). No source is vendored. A reduced decode derives its transforms as `jidctred.c` describes its own, as averages of the full IDCT's outputs, and sizes a chroma plane's IDCT across and down apart, as IJG libjpeg 9's `jdmaster.c` sizes `DCT_h_scaled_size` and `DCT_v_scaled_size`; the tables are computed, not copied. `tools/stb_dump.c` regenerates the stb vectors and `tools/cmyk_jpeg.c` writes the four-component fixtures with libjpeg.

### Specification implementations and later corrections

Some codecs here were implemented from their published specification and then
checked against a reference **binary**. The notes record any reference source
consulted for subsequent corrections, so the provenance remains accurate.

| ImageKodec target | Written from | Verified against | Notes |
|---|---|---|---|
| **WebP lossless (VP8L)** + the RIFF/`VP8X` container and `ANIM`/`ANMF` animation | [WebP Lossless Bitstream Specification](https://developers.google.com/speed/webp/docs/webp_lossless_bitstream_specification) and the [WebP container spec](https://developers.google.com/speed/webp/docs/riff_container) | libwebp's `cwebp` / `dwebp` binaries as a decode oracle (`WebpOracleTest`), bit-exact | Prefix-tree singleton and completeness checks were verified against [libwebp huffman_utils.c](https://github.com/webmproject/libwebp/blob/main/src/utils/huffman_utils.c), [BSD-3-Clause](https://github.com/webmproject/libwebp/blob/main/COPYING); no source is vendored |
| **APNG** animation over the PNG decoder | [APNG specification](https://wiki.mozilla.org/APNG_Specification) | hand-built vectors whose composites are computed from the spec's own blend formula | |
| TIFF tiles, 16-bit samples, planar config 2, YCbCr | TIFF 6.0 specification | libtiff's `tiffcp` for fixture generation, `tiff2rgba` for subsampled YCbCr, and ImageIO as an independent reader (`TiffOracleTest`) | |
| BMP RLE4/RLE8, BITFIELDS, OS/2 headers | Microsoft `wingdi.h` DIB documentation | ImageIO (`GifBmpInteropTest`) | |

TIFF FillOrder normalization was checked against libtiff's [tif_read.c](https://github.com/libsdl-org/libtiff/blob/master/libtiff/tif_read.c) and [tif_write.c](https://github.com/libsdl-org/libtiff/blob/master/libtiff/tif_write.c), under its [permissive Sam Leffler / Silicon Graphics license](https://github.com/libsdl-org/libtiff/blob/master/LICENSE.md). The reversal table is derived, and no source is vendored. `TiffFillOrderOracleTest` checks libtiff-written strips and tiles across compression, sample depth, byte order, prediction and planar layout.

TIFF alpha follows TIFF 6.0 section 18 and the straight-ARGB bitmap contract. The semantic review also consulted libtiff's [tif_getimage.c](https://github.com/libsdl-org/libtiff/blob/master/libtiff/tif_getimage.c), under the same permissive license, and ImageMagick's [tiff.c](https://github.com/ImageMagick/ImageMagick/blob/main/coders/tiff.c), under the [permissive ImageMagick license](https://github.com/ImageMagick/ImageMagick/blob/main/LICENSE); no source is vendored. Native-depth unassociation is checked against ImageMagick's TIFF reader and ImageIO-written alpha fixtures (`TiffAlphaOracleTest`).

JPEG in TIFF follows TIFF Technical Note 2 for compression 7: JPEGTables spliced before each strip or tile, and YCbCr converted by the JPEG decoder as libtiff's RGB color mode has libjpeg do it. Old-style JPEG (compression 6) is rebuilt into one stream the way libtiff's [tif_ojpeg.c](https://gitlab.com/libtiff/libtiff/-/blob/master/libtiff/tif_ojpeg.c) does it, under the same permissive Sam Leffler / Silicon Graphics license: markers read from JPEGInterchangeFormat on into the strips, missing tables built from JPEGQTables, JPEGDCTables and JPEGACTables, a restart interval of one strip with a restart marker between strips, and the samples passed on without upsampling to the TIFF YCbCr path. No source is vendored. `TiffJpegOracleTest` and `TiffOldJpegOracleTest` check ImageMagick, `tiffcp` and `cjpeg`-built files against `tiff2rgba`; the three old-style samples in libtiff's own test images decode within 4 levels of it as well.

TIFF ReferenceBlackWhite follows TIFF 6.0 sections 20/21. The review consulted libtiff's [tif_color.c](https://github.com/libsdl-org/libtiff/blob/master/libtiff/tif_color.c) under the same permissive Sam Leffler / Silicon Graphics license; no source is vendored. Unsigned RATIONAL expansion uses bounded 128-bit integers with 16 fractional bits and exact CCIR 601-1 matrix fractions. `TiffReferenceOracleTest` checks libtiff and ImageIO channel codes, and independently checks wide arithmetic and full-domain range expansion against JVM BigInteger and exact rational color conversion.

CCITT Group 4 reference-run caching was reviewed against libtiff's [tif_fax3.c](https://github.com/libsdl-org/libtiff/blob/master/libtiff/tif_fax3.c), under its permissive Sam Leffler / Silicon Graphics license; no source is vendored. The implementation keeps separate next-change pairs for each coding color with constant extra storage. `CcittReferenceTest` checks hand-built horizontal, vertical and pass modes; `CcittReferenceOracleTest` compares varied ImageIO-written Group 4 pages with libtiff.

CCITT mixed Group 3 framing follows [ITU-T T.4 sections 4.2.2–4.2.4](https://www.itu.int/rec/dologin_pub.asp?id=T-REC-T.4-199904-S%21%21PDF-E&lang=e&type=items) and the CCITTFaxDecode parameter semantics in [Adobe's PDF reference, table 3.9](https://opensource.adobe.com/dc-acrobat-sdk-docs/pdfstandards/pdfreference1.5_v6.pdf). The review consulted libtiff's `Fax3Decode2D` under the same permissive license; no source is vendored. `CcittMixedTest` checks mode transitions, framing, termination, polarity and direct mutations; `CcittMixedOracleTest` compares libtiff-generated strips and tiles with their source and libtiff's independent reader, and checks the public fax API on extracted strips.


The JBIG2 multi-instance refusal fixture uses an independently encoded integer prefix from jbig2enc's [Apache-2.0 arithmetic encoder](https://github.com/agl/jbig2enc/blob/d0dfca46216c98f11312a9c9f15615ed490cd7b3/src/jbig2arith.cc): initialize, encode IADH=1, IADW=1, IAAI=2, finalize. Its four-byte output is checked in `Jbig2UnsupportedTest`; unsupported content is refused before consuming the aggregate body. No encoder source is vendored.

Expansion-budget policy was checked against libwebp's [vp8l_dec.c](https://github.com/webmproject/libwebp/blob/main/src/dec/vp8l_dec.c), under its BSD-3-Clause license, and libtiff's [allocation-limit API](https://libtiff.gitlab.io/libtiff/functions/TIFFOpenOptions.html), under its permissive Sam Leffler / Silicon Graphics license. VP8L singleton groups and CCITT reference rows have no useful input-size ratio; packed PNG/TIFF bounds account for stored bits per pixel. No reference source is vendored. `ExpansionBudgetOracleTest` checks libwebp-written flat images, libtiff-written blank pages and independently readable hand-built streams.

## Adapted code from the Kite libraries

| Implementation | Origin and format reference | Code license |
|---|---|---|
| JPEG 2000 (`JpxDecoder`) | Adapted from [KitePDF's original decoder](https://github.com/yuroyami/KitePDF/commit/3bbc54ce3207481ee90c3cc5782a7428868d80a9); ITU-T T.800, including Annex C arithmetic coding | Apache-2.0, KitePDF authors |
| JBIG2 (`Jbig2Decoder`) | Adapted from [KitePDF's original decoder](https://github.com/yuroyami/KitePDF/commit/bfbaa8e24de0e52666f7aa4b46788b40bb79b657); ITU-T T.88 | Apache-2.0, KitePDF authors |
| CCITT (`CcittFax`) | Adapted from KitePDF's `/CCITTFaxDecode` filter; ITU-T T.4 and T.6 | Apache-2.0, KitePDF authors |
| MQ arithmetic (`MqDecoder`) | [Extracted from KitePDF's JBIG2 decoder](https://github.com/yuroyami/KitePDF/commit/31f707dbd83ab30b9ac54770e92cb766d9f0ff8c); T.88 Annex E and T.800 Annex C define the shared coder and state tables | Apache-2.0, KitePDF authors |
| DEFLATE inflate (`internal/flate/Inflate`) | Vendored from ArchiveKodec, via KiteTorrent/libtorrent's preserved [puff source](https://github.com/arvidn/libtorrent/blob/master/src/puff.cpp); Mark Adler's [puff.h notice](https://github.com/madler/zlib/blob/master/contrib/puff/puff.h) | zlib-style license; original notice retained in the altered Kotlin source |
| DEFLATE encode (`internal/flate/Deflate`) | Vendored from ArchiveKodec, originating in KitePDF's FlateDecode encoder; Huffman construction is an altered implementation of zlib's [trees.c](https://github.com/madler/zlib/blob/master/trees.c) `build_tree`/`gen_bitlen` | zlib; original notice retained in the altered Kotlin source |
| CRC-32 / Adler-32 and zlib framing | Vendored from ArchiveKodec; RFC 1950/1951 and zlib references | Apache-2.0 Kotlin implementation; zlib reference license |
| Lossy WebP (`Vp8Decoder`, `Vp8Tables`) | Ported from [libwebp v1.3.2](https://github.com/webmproject/libwebp/tree/v1.3.2)'s `src/dec` (VP8 headers, tokens, reconstruction and loop filter), `src/dsp/dec.c`, `src/utils/bit_reader*` and its output path (`upsampling.c`, `yuv.h`, `io_dec.c`), with the ALPH rules of `alpha_dec.c` and `filters.c`; RFC 6386 defines the format. The tables are generated from libwebp's source by `tools/vp8_tables.py`, which records each file's SHA-256 and each table's CRC-32 for `Vp8TablesTest`. `WebpLossyOracleTest` checks every pixel against `dwebp` and `anim_dump` | [BSD-3-Clause](https://github.com/webmproject/libwebp/blob/v1.3.2/COPYING), Google; notice retained in the altered Kotlin sources and reproduced in NOTICE |

The four image/fax decoders above entered this repository in
[the absorption commit](https://github.com/yuroyami/ImageKodec/commit/24bee5954a0e44f09f606400b05446a8380f416c).
KitePDF's LICENSE is Apache-2.0, and its NOTICE identifies software developed by
the KitePDF authors. Standard-defined tables and behavior are attributed to the
relevant ITU recommendations; their listing is not a claim that a standard's
publication text is licensed as software.

## Other format specifications

- PNG chunk/filter semantics: W3C PNG and RFC 2083.
- GIF structure, disposal and loop extension: GIF89a and the NETSCAPE2.0 extension.

Animation play counts follow the [Skia repetition contract](https://github.com/google/skia/blob/main/include/codec/SkCodec.h), which excludes the initial play for GIF. Skia is under its [BSD-3-Clause license](https://github.com/google/skia/blob/main/LICENSE); its installed codec independently checks GIF metadata in `AnimationLoopSceneTest`. APNG preserves the full unsigned play field for compatibility with [Pillow's PNG reader/writer](https://github.com/python-pillow/Pillow/blob/main/src/PIL/PngImagePlugin.py), under its [MIT-CMU license](https://github.com/python-pillow/Pillow/blob/main/LICENSE), while documenting the narrower PNG Third Edition integer rule. No reference source is vendored. The complete Pillow fixture from issue #59 and wire-field boundary variants are checked in `AnimationLoopCountTest`.

The first composited WebP canvas is independently checked through Skia's `Codec.readPixels` in `WebpFirstFrameOracleTest`, under the same BSD-3-Clause license. The partial-frame regression stream was written with libwebp's cwebp/webpmux tools and is preserved from issue #50; offset, blend and disposal behavior follows the WebP container specification. No reference source is vendored.

Coil error-image precedence and request sizing were checked against [Coil 3.5.0 compose utilities](https://github.com/coil-kt/coil/blob/3.5.0/coil-compose-core/src/commonMain/kotlin/coil3/compose/internal/utils.kt), under its [Apache-2.0 license](https://github.com/coil-kt/coil/blob/3.5.0/LICENSE.txt). `KiteAsyncImageErrorTest` renders both the binding and stock `AsyncImage` with real failing requests, checking the request's image and explicit painter overrides. `KiteAsyncImageSizingTest` compares real request dimensions and scales with stock AsyncImage, including original-size drawing, explicit request fields and recomposition. No reference source is vendored.

Coil decoder geometry uses the public [Coil 3.5.0 DecodeUtils](https://github.com/coil-kt/coil/blob/3.5.0/coil-core/src/commonMain/kotlin/coil3/decode/DecodeUtils.kt) sizing operations and the independent-side flooring used by its [Skia bitmap conversion](https://github.com/coil-kt/coil/blob/3.5.0/coil-core/src/nonAndroidMain/kotlin/coil3/util/utils.nonAndroid.kt), under Apache-2.0. `KiteImageDecoderSizingTest` compares real stock ImageLoader outputs with the binding for FIT/FILL, one-axis requests, non-integral ratios, original size and maximum bitmap size; animated frames are checked against the same stock geometry. No reference source is vendored.

Coil WebP claiming follows the [WebP RIFF container specification](https://developers.google.com/speed/webp/docs/riff_container). `KiteImageFactoryClaimTest` uses a cwebp/webpmux-written mixed animation and checks stock-decoder fallback beyond the initial peek; libwebp tools (BSD-3-Clause) independently confirm its frame codecs. Synthetic chunk fixtures check bounds, padding, fragmented reads and source preservation. No reference source is vendored.

JPEG 2000 diagnostic and marker-boundary validation was checked against [OpenJPEG’s j2k.c](https://github.com/uclouvain/openjpeg/blob/master/src/lib/openjp2/j2k.c), under its [BSD-2-Clause license](https://github.com/uclouvain/openjpeg/blob/master/LICENSE). `Jp2DiagnosticsTest` checks distinct marker/field failures, typed unsupported features and nullable API compatibility; the existing partial-packet and OpenJPEG pixel comparisons remain covered. No reference source is vendored.

JPEG 2000 probe and decode share the COD/COC and QCD/QCC parameter readers. `Jp2HeaderProbeTest` checks both header scopes, later tile-parts, packet skipping and codestream-box bounds. OpenJPEG's marker validation and table shapes informed the range checks; `JpxOracleTest` compares all eight legal guard-bit encodings against OpenJPEG, with exact reversible pixels. No reference source is vendored.

## Refresh

```sh
cd reference
for d in stb lodepng commons-imaging; do (cd "$d" && git pull --depth 1); done
```

JPEG 2000 singleton reconstruction follows [ITU-T T.800 section F.3.6](https://www.fit.vut.cz/person/ibarina/public/tmp/20_T-REC-T_1_.800-200208-I__PDF-E.pdf) for both filters. `Jp2SingletonTest` contains native FFmpeg codestreams with 16×16 tiles and committed OpenJPEG reference pixels for horizontal, vertical and corner singleton cases, plus reversible and ordinary OpenJPEG controls. The source gray ramp is `x*181/(width-1) + y*71/(height-1)` with integer divisions. FFmpeg is used only as an encoder oracle; no source is copied. `Jp2SingletonOracleTest` independently checks full output and all public reduction factors against OpenJPEG.

Reversible JPEG 2000 reconstruction follows T.800 E.1.2 (E-7/E-8), cross-checked against [OpenJPEG’s tier-1 decoder](https://github.com/uclouvain/openjpeg/blob/master/src/lib/openjp2/t1.c), under its BSD-2-Clause license. `Jp2ReconstructionTest` preserves native FFmpeg `-pred 1 -layer_rates 4` streams of the same integer gray ramp used for singleton tests, at 16×16, 32×32 and 64×64. OpenJPEG reconstructs all three sources exactly. `JpxOracleTest` compares these streams and independently generated rate-limited RGB/gray streams, without a final lossless layer, at full size and every public reduction factor. No reference source is vendored.

Scalar-derived JPEG 2000 quantization follows T.800 E-5 and [OpenJPEG’s QCD/QCC table expansion in j2k.c](https://github.com/uclouvain/openjpeg/blob/master/src/lib/openjp2/j2k.c), under its BSD-2-Clause license. `Jp2DerivedQuantTest` contains 16×16 native FFmpeg `-pred 0 -layer_rates 16` packet data with specification-derived QCD pairs: six decomposition levels, two guard bits, (epsilon0, mu0) of (8, 0), (8, 768) and (9, 1536). The undecomposed control uses OpenJPEG `-I -n 1 -r 4`. Each derived/expounded pair has byte-identical OpenJPEG output; the common tests preserve those reference pixels, and `Jp2DerivedQuantOracleTest` checks all main/tile QCD/QCC scopes at full size and supported wavelet reductions. No reference source is vendored.

Signed JPEG 2000 display conversion follows [OpenJPEG’s signed output midpoint adjustment in convert.c](https://github.com/uclouvain/openjpeg/blob/master/src/bin/jp2/convert.c), under its BSD-2-Clause license, while T.800 G.1 keeps inverse DC shifting specific to unsigned components. `Jp2SignedTest` contains 16×16 public C API encoder streams: three resolutions, one layer at rate 0, explicit precisions 1–16, signed/unsigned gray and RGB with no MCT, RCT or ICT, plus declared unassociated gray opacity. Gray source samples are `i*((1<<precision)-1)/255`, minus the midpoint for signed components. At 8 bits RGB is `(x*17, y*17, (x xor y)*17)` and gray/opacity is `(x*17, y*17)`, translated per declared sign. Signed/unsigned raw pairs were independently encoded and differ only in their SIZ sign bits. `Jp2SignedOracleTest` checks full and reduced output through PGX, preserving raw sign/precision before conversion; reversible output is exact, irreversible output retains the documented tolerance. No reference source is vendored.
