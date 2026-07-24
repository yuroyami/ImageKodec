# Changelog

All notable changes to KiteImage are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Until 1.0.0 the public API may still change between minor versions; every such
change will be listed here, and the committed `api/*.api` dumps make them
reviewable in the diff.

## [Unreleased]

### Added

- **`KiteImage.probe()` and `ImageInfo`.** Header-only inspection: dimensions,
  bit depth, declared alpha, animation frame count, loop count, EXIF
  orientation, and whether this build can decode the file at all. No pixels are
  decoded and no image-sized buffer is allocated.
- **EXIF orientation.** Read from JPEG `APP1` and from TIFF tag 274, exposed as
  `ImageInfo.orientation`, and applied on request with
  `decode(bytes, applyOrientation = true)`.
- **Geometric operations** on `KiteBitmap` and `KiteAnimation`: `rotated90`,
  `rotated180`, `rotated270`, `flippedHorizontal`, `flippedVertical`,
  `transposed`, `transversed`, `cropped` and `oriented`.
- **APNG decoding.** `acTL` / `fcTL` / `fdAT`, all three dispose operations, both
  blend operations, frame rectangles, loop count, and the rule that decides
  whether the default image is also frame zero. `decode` still returns the
  default image, which is what a non-APNG viewer shows.
- **WebP decoding (lossless).** The full RIFF container including `VP8X`, and the
  VP8L codec: prefix-code groups, meta-prefix images, colour cache, LZ77 back
  references, and all four transforms. Animated WebP (`ANIM` / `ANMF`)
  composites through the same source-over operator APNG uses.
- **GIF encoding.** Median-cut quantisation, optional Floyd-Steinberg dithering,
  a real LZW compressor, single-image and animated output with per-frame delays
  and the NETSCAPE loop block.
- **BMP encoding.** 24-bit `BI_RGB` when opaque, 32-bit `BI_BITFIELDS` under a V4
  header when the bitmap carries alpha.
- **BMP decoding completed.** `BI_RLE8` and `BI_RLE4` (runs, absolute mode,
  line ends, delta jumps), `BI_BITFIELDS` and `BI_ALPHABITFIELDS` with arbitrary
  channel masks, depths 1/2/4/16, and the OS/2 `BITMAPCOREHEADER`.
- **TIFF decoding completed.** Tiled layouts, 16-bit samples, separate planes
  (`PlanarConfiguration = 2`), YCbCr photometric with chroma subsampling in both
  the chunky unit layout and separate planes, and 2/4-bit depths.
- **Fuzz suite.** A seeded, platform-independent mutation harness over every
  decoder: bit flips, byte corruption, truncation at every offset, header-field
  tampering and cross-format splices. It asserts that malformed input can only
  ever produce an `ImageDecodeException`.
- **CI**, covering JVM, JS, wasm, native and Android, with the codec oracles
  (stb_image, OpenJPEG, libwebp, libtiff) installed rather than skipped.
- **Public API tracking** via committed `api/*.api` dumps and `checkLegacyAbi`.

### Changed

- **Decompression-bomb guards are now input-relative.** Dimensions are checked
  against a budget derived from the input size as well as the absolute ceiling,
  so a corrupted header cannot make a 250-byte file reserve a gigabyte.
- `decodeAnimation`'s parameters gained `applyOrientation` before
  `cancellationCheck`. Callers using the trailing-lambda form are unaffected.
- The Dokka site now includes `kiteimage-coil`, which was previously missing
  from the aggregate.

### Fixed

- WebP animation frames whose rectangle left the canvas wrote out of bounds.
- TIFF fields with a corrupt value count could size an array before anything
  checked the values existed.
- TIFF fields with a zero value count were indexed as if they held one.
