# KiteImage: porting references

These trees are **for study and clean-room porting only**. They are **git-ignored and
never distributed** (`reference/` is in `.gitignore`, and nothing under it ships in the
published artifact). Every reference here is under a **permissive license** (public
domain / MIT / zlib / Apache-2.0) so reading and re-implementing from it is legally
clean for an Apache-2.0 library. Where a per-file copyright applies to a near-1:1 port,
retain the original notice in the ported `.kt` header (the Kite-lineage convention).

> **Deliberately NOT fetched: AVIF / HEIC references (dav1d, libheif, libde265).**
> AV1/HEVC decoders are orders of magnitude beyond a realistic pure-Kotlin port, and
> libde265/x265 carry HEVC patent baggage on top. Both formats are permanently out of
> scope for the core (a platform-backed decode could someday live in a separate opt-in
> module, never here). Same story for JPEG XL for now: revisit if demand appears.

| KiteImage target | Reference tree | Key files | License |
|---|---|---|---|
| **Primary all-rounder**: JPEG (baseline+progressive), PNG, GIF, BMP, TGA, PSD decode | `stb` | `stb_image.h` (single file, ~8k lines, readable) | public domain / MIT |
| PNG + JPEG + BMP + TGA **encode** | `stb` | `stb_image_write.h` | public domain / MIT |
| PNG encode (cleanest standalone ref) | `lodepng` | `lodepng.cpp`, `lodepng.h` | zlib |
| TIFF, ICO, PNM, PCX decode/encode + **EXIF/metadata layer** | `commons-imaging` | `src/main/java/org/apache/commons/imaging/formats/tiff/*`, `formats/ico/*`, `formats/pnm/*`, `common/bytesource/*`, `formats/tiff/taginfos/*` | Apache-2.0 |
| GIF **encode** (pure-Java writer, mechanical port) | `commons-imaging` | `src/main/java/org/apache/commons/imaging/formats/gif/*` | Apache-2.0 |
| Image resampling (phase 2, `kiteimage-ops`) | `stb` | `stb_image_resize2.h` | public domain / MIT |

### Not ported from a tree: written from the specification

Some codecs here were implemented from their published specification and then
checked against a reference **binary**, with no reference source consulted. That
is a stronger clean-room position than a port, and it is recorded here so the
provenance is not guesswork later.

| KiteImage target | Written from | Verified against | Notes |
|---|---|---|---|
| **WebP lossless (VP8L)** + the RIFF/`VP8X` container and `ANIM`/`ANMF` animation | [WebP Lossless Bitstream Specification](https://developers.google.com/speed/webp/docs/webp_lossless_bitstream_specification) and the [WebP container spec](https://developers.google.com/speed/webp/docs/riff_container) | libwebp's `cwebp` / `dwebp` binaries as a decode oracle (`WebpOracleTest`), bit-exact | libwebp is BSD-3-Clause; **no libwebp source was read**, and none is vendored |
| **APNG** animation over the PNG decoder | [APNG specification](https://wiki.mozilla.org/APNG_Specification) | hand-built vectors whose composites are computed from the spec's own blend formula | |
| TIFF tiles, 16-bit samples, planar config 2, YCbCr | TIFF 6.0 specification | libtiff's `tiffcp` for fixture generation, ImageIO as an independent reader (`TiffOracleTest`) | |
| BMP RLE4/RLE8, BITFIELDS, OS/2 headers | Microsoft `wingdi.h` DIB documentation | ImageIO (`GifBmpInteropTest`) | |

## Not cloned, used indirectly

| What | Where it lives | Why |
|---|---|---|
| **inflate / zlib / CRC-32 / Adler-32** | vendored from `KiteArchive` (`kiteimage/…/internal/flate/`): itself a clean port of zlib's `contrib/puff` | PNG IDAT needs it; KiteArchive already ported + tested it. Vendored (not a dependency) so the core keeps zero deps and no publish-order coupling. Swap to the `kitearchive` artifact once it's on Central. |
| PNG spec (RFC 2083 / W3C PNG 3) | w3.org/TR/png-3 | filter + chunk semantics ground truth |
| GIF89a spec | w3.org/Graphics/GIF/spec-gif89a.txt | disposal methods, NETSCAPE2.0 loop extension |

## Refresh

```sh
cd reference
for d in stb lodepng commons-imaging; do (cd "$d" && git pull --depth 1); done
```
