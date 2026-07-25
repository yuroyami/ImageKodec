# KiteImage format status

Updated 2026-07-25. This file lists the support level for every format and
feature. For install instructions, targets and limits, read the
[README](README.md).

## Test and target summary

**265 tests on the JVM**: 252 in the core, 3 in compose, 10 in coil. The 195
common tests also run green on JS/Node, wasm/Node and Kotlin/Native (macOS arm64
and linux x64). KitePDF depends on kiteimage, so its 697 tests exercise these
codecs daily.

Each codec's tests compare its output against another implementation, not against
hand-written expectations. JPEG decode is **bit-identical to stb_image**. That
check uses committed vectors which a clang-compiled stb_image produced, so it
runs on every target with no external tool. Three further suites compare against
a binary when it is installed, and skip when it is not: libwebp's `dwebp` for
lossless WebP (pixel-exact), OpenJPEG for JPEG 2000, and libtiff plus ImageMagick
for TIFF.

`kiteimage` builds for 22 targets: Android (minSdk 21), `jvm`,
`iosArm64`, `iosSimulatorArm64`, `iosX64`, `macosArm64`, `tvosArm64`,
`tvosSimulatorArm64`, `watchosArm32`, `watchosArm64`, `watchosDeviceArm64`,
`watchosSimulatorArm64`, `androidNativeArm32`, `androidNativeArm64`,
`androidNativeX64`, `androidNativeX86`, `linuxX64`, `linuxArm64`, `mingwX64`,
`js` (browser and Node), `wasmJs` (browser and Node), `wasmWasi` (Node). There is
no `macosX64`, following Kotlin's deprecation of Intel-Apple native targets.

`kiteimage-compose` and `kiteimage-coil` build for **seven** targets: Android,
`jvm`, `iosArm64`, `iosSimulatorArm64`, `macosArm64`, `js` (browser only) and
`wasmJs` (browser only). That is the set Compose Multiplatform publishes. A
project targeting `iosX64`, Linux, Windows, tvOS, watchOS, androidNative or
wasmWasi resolves the core and then fails to resolve those two.

## Decoders

Two terms used in this table. IFD means Image File Directory, the record that
describes one page of a TIFF file. Chroma subsampling means the file stores color
at a lower resolution than brightness.

| Format | Feature | Status |
|---|---|---|
| **PNG** | signature and chunk walk, CRC on consumed chunks | ✅ |
| | color types 0/2/3/4/6, depths 1/2/4/8/16 | ✅ 16-bit truncated to the high byte |
| | filters None, Sub, Up, Average and Paeth | ✅ |
| | `tRNS` (palette alpha and color-key 0/2) | ✅ |
| | Adam7 interlace: 7 passes, per-pass filtering, sub-byte passes | ✅ |
| | **APNG**: `acTL`, `fcTL` and `fdAT`, dispose none/background/previous, blend source/over, frame rects, loop count, default-image-as-frame-0 rule | ✅ |
| | `CgBI` (Apple) | ❌ detected and named, out of scope |
| | ancillary color chunks (gAMA, iCCP, sRGB) | skipped, raw samples returned |
| **BMP** | BITMAPCOREHEADER (12), BITMAPINFOHEADER (40), V2/V3 (52/56), OS/2 v2 (64), V4/V5 (108/124) | ✅ |
| | depths 1/2/4/8 (palette) and 16/24/32 (direct) | ✅ |
| | BI_RGB, **BI_RLE8**, **BI_RLE4** (runs, absolute mode, line end, delta), **BI_BITFIELDS** and **BI_ALPHABITFIELDS** with arbitrary masks | ✅ |
| | bottom-up and top-down, 4-byte row padding | ✅ |
| | 32-bit with all-zero alpha treated as opaque | ✅ |
| | BI_JPEG and BI_PNG, which embed a whole other image in the pixel array | ❌ rejected by name |
| **GIF** | 87a and 89a, LZW (growth, KwKwK, deferred clear, mid-stream CLEAR) | ✅ |
| | global and local color tables, 4-pass interlace | ✅ |
| | animation compositing: frame rects, transparency, disposal none/keep/bg/prev | ✅ |
| | delays (the browser rule of 1 cs or less becoming 100 ms) plus NETSCAPE and ANIMEXTS loop count | ✅ |
| **JPEG** | baseline SOF0 and extended sequential SOF1, 8-bit | ✅ bit-identical to stb_image |
| | Huffman fast tables, fast-AC, restart intervals, multi-scan non-interleaved | ✅ |
| | subsampling h/v 1..4 (4:2:0, 4:2:2, 4:4:4, 4:1:1), triangle-filter upsampling | ✅ |
| | gray, YCbCr, component-id RGB, CMYK and YCCK (Adobe APP14) | ✅ |
| | progressive (SOF2): spectral selection, successive approximation, EOB runs, DC and AC refinement | ✅ bit-identical to stb_image |
| | **EXIF orientation** | ✅ read by `probe`, applied on request |
| | lossless, arithmetic and hierarchical | ❌ rejected by name |
| **WebP** | RIFF container, `VP8X` extended form, canvas rules | ✅ |
| | **VP8L (lossless)**: prefix groups, meta-prefix image, color cache, LZ77 and plane-code distances, all four transforms | ✅ bit-identical to `dwebp` |
| | **animation** (`ANIM` and `ANMF`): frame rects, blend and dispose flags, loop count, durations | ✅ lossless frames only |
| | VP8 (lossy), in stills and in animation frames alike | ❌ declined by name: see [Excluded, with reasons](#excluded-with-reasons) |
| **JPEG 2000** | JP2 container and raw J2K codestream, part 1 (EBCOT, 5/3 and 9/7 DWT, all progressions, tiles) | ✅ checked against OpenJPEG |
| | RGN, POC, PPM/PPT, non-baseline code-block styles | ❌ named by `probe` from the main header. The decode reports one generic `ImageDecodeException` |
| **JBIG2** | generic-region arithmetic and MMR paths, embedded streams with globals | ✅ parameterized API, since there is no container to sniff |
| **CCITT G3/G4** | T.4 1D and T.6 2D fax, TIFF compressions 3 and 4 | ✅ parameterized `CcittFax` API |
| **TIFF** | II and MM byte order, **strips and tiles** | ✅ |
| | multi-page (the IFD chain) | ❌ first IFD only: a multi-page file decodes to page 1 and reports no error |
| | raw, PackBits, TIFF-LZW with EarlyChange, Deflate, CCITT G3-1D and G4 | ✅ |
| | photometric 0 and 1 (with alpha), 2 (RGB and RGBA), 3 (palette), **6 (YCbCr, including chroma subsampling, chunky units and separate planes)** | ✅ |
| | bits 1/2/4/8/**16**, predictor 2 for 8-bit and 16-bit | ✅ 16-bit truncated to the high byte |
| | **planar configuration 1 and 2** | ✅ |
| | JPEG-in-TIFF (compression 6 and 7), floating-point samples | ❌ rejected by name |
| **AVIF and HEIC** | | permanently out of scope: see [Excluded, with reasons](#excluded-with-reasons) |

## Encoders

| Format | Status |
|---|---|
| PNG | ✅ 8-bit RGB or RGBA chosen automatically, per-row MSAD filter heuristic, vendored deflate. Round-trips pixel-exact, and ImageIO reads the output |
| JPEG | ✅ baseline, `Double` math for cross-target determinism, quality 1 to 100, 4:2:0 at quality 90 or under and 4:4:4 above. PSNR-tested, and ImageIO reads the output |
| GIF | ✅ median-cut quantizer, optional Floyd-Steinberg dithering, real LZW. Stills and animations, with delays and a NETSCAPE loop count. Exact round trip under 256 colors, and ImageIO reads every frame |
| BMP | ✅ 24-bit BI_RGB when opaque, 32-bit BI_BITFIELDS under a V4 header when alpha is present. ImageIO reads the output pixel-exact |
| WebP, TIFF, JPEG 2000 | ❌ no encoder. Decode only |

## Inspection and geometry

| Piece | Status |
|---|---|
| `KiteImage.probe` returns an `ImageInfo`: dimensions, bit depth, declared alpha, frame count, loop count, EXIF orientation, decodability and reason. Header-only for every format | ✅ |
| `ImageInfo.isDecodable` mirrors each decoder's feature refusals: PNG CgBI; JPEG SOF kind, precision, DNL and component count; BMP compression and depth; WebP lossy in stills **and inside animation frames**; TIFF bits, compression, predictor, photometric and G3-2D; JP2 components, precision, subsampling, size ceiling and main-header markers | ✅ features only. A corrupt file that declares supported features still probes decodable and then throws. JP2 is the loosest: the walk stops at the first tile-part and does not range-check COD and QCD |
| `Orientation`, `KiteBitmap.oriented`, and `decode(applyOrientation = true)` | ✅ |
| `rotated90/180/270`, `flippedHorizontal/Vertical`, `transposed`, `transversed`, `cropped` and `oriented` on bitmaps | ✅ animations get `rotated90/180/270`, `cropped`, `scaled` and `oriented` only |
| `KiteBitmap.scaled(maxW, maxH)`: box-filter downscale, aspect-preserving, alpha-weighted, never upscales | ✅ |
| Decode-time (DCT-domain) scaling | not implemented. Scaling still happens after a full decode |

## Infrastructure

| Piece | Status |
|---|---|
| `KiteBitmap` ARGB_8888 pixel buffer | ✅ |
| `ImageFormat.sniff` for PNG, JPEG, GIF, BMP, WEBP, TIFF and JP2 | ✅ |
| vendored flate in `internal.flate`: inflate, zlib framing and CRC-32 | ✅ swaps to the `kitearchive` artifact once that is on Central |
| decompression-bomb guards (a decompression bomb is a small file that expands into a very large image): dimension and pixel ceilings, exact-size inflate caps, **and an input-relative budget** so a corrupt header in a small file cannot size a large buffer | ✅ for PNG, JPEG, GIF, BMP, TIFF and WebP. JPEG 2000 has only its own flat 2^26-pixel ceiling |
| `FuzzTest`: a seeded mutation harness over every decoder (bit flips, byte corruption, truncation at every offset, header tampering, cross-format splices) asserting that only `ImageDecodeException` escapes | ✅ |
| verification suites: stb_image (JPEG decode, committed vectors, always runs), OpenJPEG (JP2), libwebp (WebP), libtiff and ImageMagick (TIFF), ImageIO (PNG, GIF, BMP and JPEG encode, always runs) | ✅ the three tool-backed suites `assumeTrue`-skip when the binary is missing, and a skip reads as a pass. Check the CI log for skip counts, not only for a passing run |
| GitHub Actions CI: JVM, JS, wasm and native on Linux, Apple targets on macOS, Android assemble, Dokka | ✅ |
| public API dumps (`api/*.api`) and `checkLegacyAbi` | ✅ |
| `:kiteimage-compose`: a `KiteImage()` composable that auto-detects animated versus static, decodes off the UI thread, plays delays and loop counts, takes an `animate` flag to force a still frame, and reports through `onError`. `KiteAnimatedImage()` handles pre-decoded animations | ✅ |
| `KiteBitmap.toImageBitmap()`: Android `createBitmap`, or a shared Skiko raster in non-premultiplied form, so the color channels hold the original color | ✅ pixel-tested on JVM |
| `:kiteimage-coil`: `KiteImageDecoder`, `KiteAnimationImage`, and `KiteAsyncImage()` with constraints-fed sizing and a drift-free frame loop | ✅ JVM integration tests through a real ImageLoader |

## Excluded, with reasons

- **WebP lossy (VP8).** The container, the alpha chunk and animation are all
  handled. The codec is not. A correct VP8 decoder needs libwebp's static
  probability tables, which hold over a thousand values, and KiteImage does not
  include them. `probe` therefore reports lossy files as undecodable, and
  `decode` refuses them by name. You can route those files to a platform decoder
  without a failed attempt first.
- **AVIF and HEIC.** AV1 and HEVC entropy decoding is far too large for a pure
  Kotlin implementation, and HEVC adds patent restrictions.
- **JPEG XL.** Not implemented.
