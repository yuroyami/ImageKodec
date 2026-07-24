# KiteImage: porting status

Updated 2026-07-24. Tests: **260** on the JVM (core 247, compose 3, coil 10), of
which the 189 common ones also run green on JS/Node, wasm/Node and Kotlin/Native
(macOS arm64). KitePDF depends on kiteimage, so its 697 tests exercise these
codecs daily.

Correctness is established against reference implementations, not assertions:
JPEG decode is **bit-identical to stb_image**, WebP lossless is **bit-identical
to libwebp's `dwebp`**, JPEG 2000 is checked against OpenJPEG, and the TIFF
layouts are checked against files libtiff itself wrote. Core targets: Android,
iosArm64, iosSimulatorArm64, iosX64, macosArm64, tvOS, watchOS, androidNative,
linuxX64/Arm64, mingwX64, JVM, JS (browser+node), wasmJs, wasmWasi. Compose and
Coil targets: the CMP 1.11 set (no Intel-Apple variants).

## Decoders

| Format | Feature | Status | Reference |
|---|---|---|---|
| **PNG** | signature + chunk walk, CRC on consumed chunks | ✅ | stb_image / PNG spec |
| | color types 0/2/3/4/6, depths 1/2/4/8/16 | ✅ (16-bit → high byte) | |
| | filters None/Sub/Up/Average/Paeth | ✅ | |
| | `tRNS` (palette alpha + color-key 0/2) | ✅ | |
| | Adam7 interlace (7-pass, per-pass filtering, sub-byte passes) | ✅ | |
| | **APNG**: `acTL`/`fcTL`/`fdAT`, dispose none/background/previous, blend source/over, frame rects, loop count, default-image-as-frame-0 rule | ✅ | APNG spec |
| | `CgBI` (Apple) | ❌ detected + named, out of scope | |
| | ancillary color chunks (gAMA/iCCP/sRGB) | skipped, raw samples returned | |
| **BMP** | BITMAPCOREHEADER (12), BITMAPINFOHEADER (40), V2/V3 (52/56), OS/2 v2 (64), V4/V5 (108/124) | ✅ | stb_image / commons-imaging |
| | depths 1/2/4/8 (palette) and 16/24/32 (direct) | ✅ | |
| | BI_RGB, **BI_RLE8**, **BI_RLE4** (runs, absolute mode, line end, delta), **BI_BITFIELDS** + **BI_ALPHABITFIELDS** with arbitrary masks | ✅ | |
| | bottom-up + top-down, 4-byte row padding | ✅ | |
| | all-zero-alpha 32-bit → opaque (stb rule) | ✅ | |
| | BI_JPEG / BI_PNG (a whole other image in the pixel array) | ❌ rejected by name | |
| **GIF** | 87a/89a, LZW (growth, KwKwK, deferred clear, mid-stream CLEAR) | ✅ | stb_image, giflib |
| | global + local color tables, 4-pass interlace | ✅ | |
| | animation compositing: frame rects, transparency, disposal none/keep/bg/prev | ✅ | |
| | delays (browser ≤1 cs → 100 ms rule) + NETSCAPE/ANIMEXTS loop count | ✅ | |
| **JPEG** | baseline SOF0 + extended sequential SOF1, 8-bit | ✅ bit-identical to stb | stb_image |
| | Huffman fast tables, fast-AC, restart intervals, multi-scan non-interleaved | ✅ | |
| | subsampling h/v 1..4 (4:2:0/4:2:2/4:4:4/4:1:1), triangle-filter upsampling | ✅ | |
| | gray / YCbCr / component-id RGB / CMYK / YCCK (Adobe APP14) | ✅ | |
| | progressive (SOF2): spectral selection, successive approximation, EOB runs, DC/AC refinement | ✅ bit-identical to stb | |
| | **EXIF orientation** | ✅ read by `probe`, applied on request | |
| | lossless/arithmetic/hierarchical | rejected by name (like stb) | |
| **WebP** | RIFF container, `VP8X` extended form, canvas rules | ✅ | libwebp |
| | **VP8L (lossless)**: prefix groups, meta-prefix image, colour cache, LZ77 + plane-code distances, all four transforms | ✅ bit-identical to `dwebp` | libwebp `vp8l_dec.c` |
| | **animation** (`ANIM`/`ANMF`): frame rects, blend and dispose flags, loop count, durations | ✅ | |
| | VP8 (lossy) | ❌ declined by name: see "Excluded" | |
| **JPEG 2000** | JP2 container + raw J2K codestream, part 1 (EBCOT, 5/3+9/7 DWT, all progressions, tiles) | ✅ | KitePDF (oracle vs OpenJPEG) |
| **JBIG2** | generic-region arithmetic + MMR paths, embedded streams w/ globals | ✅ parameterized API (no container to sniff) | KitePDF |
| **CCITT G3/G4** | T.4 1D + T.6 2D fax; TIFF compressions 3/4 | ✅ parameterized `CcittFax` API | KitePDF |
| **TIFF** | II/MM, **strips and tiles**, first IFD | ✅ | commons-imaging / libtiff vectors |
| | raw/PackBits/TIFF-LZW+EarlyChange/Deflate/CCITT G3-1D+G4 | ✅ | |
| | photometric 0/1 (+alpha), 2 (RGB/RGBA), 3 (palette), **6 (YCbCr, incl. chroma subsampling, chunky units and separate planes)** | ✅ | |
| | bits 1/2/4/8/**16**, predictor 2 for 8- and 16-bit | ✅ | |
| | **planar configuration 1 and 2** | ✅ | |
| | JPEG-in-TIFF (compression 6/7), floating-point samples | ❌ rejected by name | |
| **AVIF / HEIC** | | permanently out of scope (see REFERENCES.md) | |

## Encoders

| Format | Status |
|---|---|
| PNG | ✅ 8-bit RGB/RGBA auto-pick, per-row MSAD filter heuristic, vendored KiteArchive deflate: round-trips pixel-exact, ImageIO reads output |
| JPEG | ✅ baseline (stb_image_write port, Double math for cross-target determinism), quality 1–100, 4:2:0 ≤90 / 4:4:4 above; PSNR-tested, ImageIO + real stb read output |
| GIF | ✅ median-cut quantiser, optional Floyd-Steinberg dithering, real LZW; stills and animations with delays + NETSCAPE loop; exact round trip under 256 colours, ImageIO reads every frame |
| BMP | ✅ 24-bit BI_RGB when opaque, 32-bit BI_BITFIELDS under a V4 header when alpha is present; ImageIO reads output pixel-exact |
| WebP | ❌ not planned for v1 (decode is the useful half) |

## Inspection and geometry

| Piece | Status |
|---|---|
| `KiteImage.probe` → `ImageInfo`: dimensions, bit depth, declared alpha, frame count, loop count, EXIF orientation, decodability + reason. Header-only for every format | ✅ |
| `Orientation` + `KiteBitmap.oriented`, and `decode(applyOrientation = true)` | ✅ |
| `rotated90/180/270`, `flippedHorizontal/Vertical`, `transposed`, `transversed`, `cropped`, on bitmaps and whole animations | ✅ |
| `KiteBitmap.scaled(maxW, maxH)`: box-filter downscale, aspect-preserving, alpha-weighted, never upscales | ✅ |
| Decode-time (DCT-domain) scaling | future work: still a post-decode scale today |

## Infrastructure

| Piece | Status |
|---|---|
| `KiteBitmap` ARGB_8888 pixel buffer | ✅ |
| `ImageFormat.sniff` (PNG/JPEG/GIF/BMP/WEBP/TIFF/JP2) | ✅ |
| vendored flate (`internal.flate`: puff inflate, zlib framing, CRC-32) | ✅: swap to `kitearchive` artifact when it's on Central |
| bomb guards: dimension and pixel ceilings, exact-size inflate caps, **and an input-relative budget** so a corrupt header in a small file cannot size a large buffer | ✅ |
| `FuzzTest`: seeded mutation harness over every decoder (bit flips, byte corruption, truncation at every offset, header tampering, cross-format splices) asserting only `ImageDecodeException` escapes | ✅ |
| oracle suites: stb_image (JPEG), OpenJPEG (JP2), libwebp (WebP), libtiff + ImageIO (TIFF), ImageIO (PNG/GIF/BMP/JPEG encode) | ✅ skip cleanly when tools are absent |
| GitHub Actions CI: JVM + JS + wasm + native on Linux, Apple targets on macOS, Android assemble, Dokka | ✅ |
| public API dumps (`api/*.api`) + `checkLegacyAbi` | ✅ |
| `:kiteimage-compose`: `KiteImage()` composable, auto-detects animated vs static, off-UI-thread decode, delays/loop-count playback, `animate` escape hatch, `onError`; `KiteAnimatedImage()` for pre-decoded animations | ✅ |
| `KiteBitmap.toImageBitmap()`: Android `createBitmap` / shared Skiko UNPREMUL raster | ✅ pixel-tested on JVM |
| `:kiteimage-coil`: `KiteImageDecoder`, `KiteAnimationImage`, `KiteAsyncImage()` with constraints-fed sizing and a drift-free frame loop | ✅ jvm integration tests through a real ImageLoader |

## Excluded, with reasons

- **WebP lossy (VP8)**: the container, the alpha chunk and animation are all
  handled; the codec is not. A faithful VP8 port hinges on reproducing libwebp's
  static probability tables exactly (over a thousand values), which is a
  vendoring decision rather than a coding one: one wrong entry corrupts output
  silently, so it is not something to reconstruct by hand. Vendoring libwebp
  under `reference/` the way stb_image and commons-imaging already are would
  make it an ordinary port. Until then `probe` reports lossy files as
  undecodable up front and `decode` declines them by name, so callers can route
  them to a platform decoder without paying for a failed attempt.
- **AVIF / HEIC**: AV1/HEVC entropy decoding is orders of magnitude past a sane
  pure-Kotlin port; HEVC adds patent baggage. A platform-backed opt-in module
  could exist someday; the core will never pretend.
- **JPEG XL**: no demand signal yet; revisit if one appears.
