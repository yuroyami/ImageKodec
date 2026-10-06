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

The four image/fax decoders above entered this repository in
[the absorption commit](https://github.com/yuroyami/ImageKodec/commit/24bee5954a0e44f09f606400b05446a8380f416c).
KitePDF's LICENSE is Apache-2.0, and its NOTICE identifies software developed by
the KitePDF authors. Standard-defined tables and behavior are attributed to the
relevant ITU recommendations; their listing is not a claim that a standard's
publication text is licensed as software.

## Other format specifications

- PNG chunk/filter semantics: W3C PNG and RFC 2083.
- GIF structure, disposal and loop extension: GIF89a and the NETSCAPE2.0 extension.

## Refresh

```sh
cd reference
for d in stb lodepng commons-imaging; do (cd "$d" && git pull --depth 1); done
```
