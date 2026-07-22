# KiteImage — porting status

Updated 2026-07-22 (GIF, Compose, Coil interop, baseline AND progressive JPEG all
landed same day; JPX/JBIG2/CCITT absorbed from KitePDF in the codec consolidation). Tests:
core 103 on JVM + 79 on JS/Node, compose 2, coil 7 — 191 total. KitePDF now
depends on kiteimage (its 697 tests exercise these codecs daily).
JPEG common vectors assert BIT-IDENTICAL output vs clang-compiled stb_image. Core targets: Android,
iosArm64, iosSimulatorArm64, iosX64, macosArm64, JVM, JS (browser+node), wasmJs.
Compose targets: the CMP 1.11 set (no Intel-Apple variants).

## Decoders

| Format | Feature | Status | Reference |
|---|---|---|---|
| **PNG** | signature + chunk walk, CRC on consumed chunks | ✅ | stb_image / PNG spec |
| | color types 0/2/3/4/6, depths 1/2/4/8/16 | ✅ (16-bit → high byte) | |
| | filters None/Sub/Up/Average/Paeth | ✅ | |
| | `tRNS` (palette alpha + color-key 0/2) | ✅ | |
| | Adam7 interlace (7-pass, per-pass filtering, sub-byte passes) | ✅ | |
| | `CgBI` (Apple) | ❌ detected + named, out of scope for now | |
| | ancillary color chunks (gAMA/iCCP/sRGB) | skipped, raw samples returned | |
| **BMP** | BITMAPINFOHEADER/V4/V5, BI_RGB 8/24/32-bit | ✅ | stb_image / commons-imaging |
| | bottom-up + top-down, 4-byte row padding | ✅ | |
| | all-zero-alpha 32-bit → opaque (stb rule) | ✅ | |
| | BI_RLE4/RLE8/BITFIELDS, 1/2/4/16-bit, OS/2 core header | ❌ rejected by name | |
| **GIF** | 87a/89a, LZW (growth, KwKwK, deferred clear, mid-stream CLEAR) | ✅ | stb_image, giflib |
| | global + local color tables, 4-pass interlace | ✅ | |
| | animation compositing: frame rects, transparency, disposal none/keep/bg/prev | ✅ | |
| | delays (browser ≤1 cs → 100 ms rule) + NETSCAPE/ANIMEXTS loop count | ✅ | |
| | `KiteAnimation`/`KiteFrame` API; static formats wrap as 1 frame | ✅ | |
| **JPEG** | baseline SOF0 + extended sequential SOF1, 8-bit | ✅ bit-identical to stb (scalar kernels) | stb_image |
| | Huffman fast tables, fast-AC, restart intervals (real-world DRI file tested), multi-scan non-interleaved | ✅ | |
| | subsampling h/v 1..4 (4:2:0/4:2:2/4:4:4/4:1:1), triangle-filter upsampling | ✅ | |
| | gray / YCbCr / component-id RGB / CMYK / YCCK (Adobe APP14) | ✅ | |
| | progressive (SOF2): spectral selection, successive approximation, EOB runs, DC/AC refinement, deferred dequant+IDCT | ✅ bit-identical to stb | |
| | lossless/arithmetic/hierarchical | rejected by name (like stb) | |
| | EXIF orientation | not applied (metadata layer's job) | |
| **JPEG 2000** | JP2 container + raw J2K codestream, part 1 (EBCOT, 5/3+9/7 DWT, all progressions, tiles) | ✅ moved from KitePDF; facade decode + `JpxDecoder` raw API | KitePDF (T-44 oracle vs OpenJPEG) |
| **JBIG2** | generic-region arithmetic + MMR paths, embedded streams w/ globals | ✅ moved from KitePDF; parameterized `Jbig2Decoder` API (no container to sniff) | KitePDF |
| **CCITT G3/G4** | T.4 1D + T.6 2D fax; TIFF compressions 3/4 groundwork | ✅ moved from KitePDF; parameterized `CcittFax` API | KitePDF |
| **WebP** | VP8/VP8L | ❌ phase 2, sniffed today | libwebp |
| **TIFF** | II/MM, strips, raw/PackBits/TIFF-LZW+EarlyChange/Deflate/CCITT G3-1D+G4, photometric 0/1/2/3, bits 1/8, predictor 2 | ✅ (tiled/16-bit/planar-2/YCbCr rejected by name) | commons-imaging / ffmpeg vectors |
| **AVIF / HEIC** | — | permanently out of scope (see REFERENCES.md) | |

## Encoders

| Format | Status |
|---|---|
| PNG | ✅ 8-bit RGB/RGBA auto-pick, per-row MSAD filter heuristic, vendored KiteArchive deflate — round-trips pixel-exact, ImageIO reads output |
| JPEG | ✅ baseline (stb_image_write port, Double math for cross-target determinism), quality 1–100, 4:2:0 ≤90 / 4:4:4 above — PSNR-tested, ImageIO + real stb read output |
| GIF | ❌ next (commons-imaging writer ref; needs quantizer + real LZW encode) |

## Scaling

`KiteBitmap.scaled(maxW, maxH)` — box-filter downscale, aspect-preserving, never
upscales; the Coil decoder honors `options.size` with it (`isSampled=true`).
Decode-time DCT-domain scaling still future work.

## Infrastructure

| Piece | Status |
|---|---|
| `KiteBitmap` ARGB_8888 pixel buffer | ✅ |
| `ImageFormat.sniff` (PNG/JPEG/GIF/BMP/WEBP/TIFF) | ✅ |
| vendored flate (`internal.flate`: puff inflate, zlib framing, CRC-32) | ✅ — swap to `kitearchive` artifact when it's on Central |
| bomb guards (16M px/side, 268M px total, exact-size IDAT inflate cap) | ✅ |
| commonTest vectors (python3-zlib generated, filters/depths/tRNS pinned) | ✅ 28 |
| jvmTest ImageIO round-trip (organic adaptive-filter data) | ✅ 6 |
| `:kiteimage-compose`: `KiteImage()` composable — auto-detects animated vs static, off-UI-thread decode, delays/loop-count playback, `animate` escape hatch, `onError` | ✅ |
| `KiteBitmap.toImageBitmap()` — Android `createBitmap` / shared Skiko UNPREMUL raster (custom `skikoMain` source set) | ✅ pixel-tested on JVM (opaque exact, semi-alpha ±1, alpha-0 keeps alpha only) |
| Compose module targets (CMP 1.11 set: android, jvm, iosArm64+sim, macosArm64, js, wasmJs) | ✅ compiling |
| `:kiteimage-coil`: `KiteImageDecoder` (claims GIF + supported PNG/BMP subsets, declines rest incl. interlaced PNG/CgBI/RLE-BMP), `KiteAnimationImage` (frame-0 static draw, `shareable=false`), `KiteAsyncImage()` (Coil pipeline + our frame loop) | ✅ — 5 jvm integration tests through a real ImageLoader incl. fall-through to Coil's Skia decoder |

## Excluded, with reasons

- **AVIF / HEIC** — AV1/HEVC entropy decoding is orders of magnitude past a sane
  pure-Kotlin port; HEVC adds patent baggage. A platform-backed opt-in module could
  exist someday; the core will never pretend.
- **JPEG XL** — no demand signal yet; revisit if one appears.
