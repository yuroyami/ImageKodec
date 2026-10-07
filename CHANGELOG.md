# Changelog

All notable changes to ImageKodec are recorded here. Version 0.1.0 was published
as KiteImage, under `io.github.yuroyami:kiteimage`. Version 0.2.0 was published as
KiteImageCodec, under `io.github.yuroyami:kiteimagecodec`. The format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions
follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Until 1.0.0 the public API may still change between minor versions; every such
change will be listed here, and the committed `api/*.api` dumps make them
reviewable in the diff.

## [Unreleased]

### Changed

- `KiteAnimation.loopCount` and `ImageInfo.loopCount` are now `Long`, preserving
  the full APNG play field, including larger counts accepted for compatibility
  with deployed writers (#59). Pass `count.toLong()` when constructing metadata
  from an `Int` variable, and compare the count with `0L` when checking unlimited
  playback. Zero means unlimited playback; positive counts mean
  total plays for GIF, APNG and WebP. Negative animation counts are refused.

- Bitmap and animation constructors, `cropped`, `scaled` and bitmap indexing
  declare `IllegalArgumentException` through `@Throws`. Their Swift and
  Objective-C signatures expose an error, so invalid geometry can be caught
  as an `NSError`.

- `encodeJpeg` rejects quality values outside its documented 1..100 range with
  `IllegalArgumentException`. The default remains 90; zero no longer acts as a
  second spelling of that default.

- **The library is now ImageKodec.** It is published as
  `io.github.yuroyami:imagekodec`, `imagekodec-compose` and `imagekodec-coil`, and
  its package is `io.github.yuroyami.imagekodec`. The `KiteImageCodec` object is
  now `ImageKodec`, so `KiteImageCodec.decode(bytes)` becomes
  `ImageKodec.decode(bytes)`. The iOS frameworks are `ImageKodec`,
  `ImageKodecCompose` and `ImageKodecCoil`. The composables `KiteImage()`,
  `KiteAsyncImage` and `KiteAnimatedImage`, the Coil `KiteImageDecoder` and the
  `KiteBitmap`, `KiteFrame` and `KiteAnimation` types keep their names. 0.2.0
  stays on Maven Central under the old coordinates.

### Fixed

- The Coil decoder honors FILL, requests with one defined dimension and
  maxBitmapSize for stills and every animation frame. The core's new
  `downscaledTo` operations retain the exact output geometry chosen by Coil
  without a second aspect-ratio fit (#52).

- `KiteAsyncImage` matches request sizing to ContentScale, requests original
  pixels for None, preserves explicit request size/scale, and updates inferred
  sizing when ContentScale changes (#54).

- `KiteAsyncImage` renders a prebuilt request's error image when no explicit
  composable error painter overrides it (#53).

- Still WebP decode composites the first animation rectangle at its declared
  offset with the same alpha and disposal timing as animation playback, without
  decoding or retaining later frame pixels (#50).

- Flat lossless WebP and fax-compressed TIFF pages use the absolute output
  ceiling; packed PNG/TIFF expansion bounds account for bits per pixel (#83).
  WebP checks frame/header geometry before canvas allocation, and nonconstant
  entropy groups retain a bound derived from their encoded operations.

- GIF converts finite NETSCAPE/ANIMEXTS repetitions to total plays in decode and
  probe, and reverses that conversion when encoding. One play omits the loop
  extension; unrepresentable counts are refused. Buffering and unknown
  application sub-blocks cannot change the play count (#58, part of #82).

- JVM library artifacts target Java 11, restrict JDK API use to that release,
  and declare the minimum in Gradle publication metadata. CI tests the core
  and both bindings on Java 11 as well as Java 21 (#85).

- JBIG2 returns null for unsupported unknown-length segments, Huffman refinement
  dictionaries and multi-instance aggregates, preventing partial pages (#89).
- Malformed JBIG2 symbol dictionaries end decoding instead of emitting blank
  symbols (#40).
- TIFF/EXIF probe offsets are checked without overflowing (#66), and optional
  TIFF tags validate their types and counts (#49).
- BMP preserves explicit transparent alpha and correctly scales wide bitfield
  masks (#43, #44).
- Scaling keeps the exact aspect-fit geometry, bounds its allocations to the
  output pixels, and handles long thin images without overflow (#45, #55, #81).
- The raw JPEG 2000 signature is recognized by `JpxDecoder.isJpx` (#51).
- Inflate preserves prefetched bits at stored-block boundaries and refuses
  invalid distance codes without a raw index exception (#76, #77).
- WebP decodes normal singleton prefix codes, validates complete trees, and
  rejects bitstream truncation at the payload boundary (#73, #79).
- JPEG 2000 validates tile origins and counts before processing tile bodies
  and computes tile geometry without overflow (#88).
- TIFF refuses incomplete uncompressed, PackBits, LZW and Deflate blocks, and
  copies subsampled YCbCr tiles in unit rows (#94, #95).
- TIFF restores horizontal prediction within each strip or padded tile row,
  keeping tile boundaries correct for 8/16-bit and separate-plane images (#93).
- TIFF applies the photometric tag once to CCITT run colors, fixing inverted
  black and white in Modified Huffman and Group 4 images (#91).
- Group 4 fax caches reference runs so valid rows decode in linear work with
  constant extra storage; invalid vertical destinations raise named errors (#61).
- Group 3 fax recognizes EOL and fill bits without consuming EOL-free runs;
  malformed rows and incomplete TIFF fax strips raise decode errors (#92).
- TIFF reads every SampleFormat component and classifies signed/floating samples
  as unsupported in probing and decoding; unsigned/undefined samples agree (#96).
- TIFF normalizes FillOrder=2 before reading packed samples or compressed blocks,
  including fax, LZW, PackBits and Deflate streams (#47).
- TIFF reads ExtraSamples descriptors and converts associated RGB, gray and
  palette colors to straight alpha at native sample precision (#46).
- TIFF reads unsigned RATIONAL ReferenceBlackWhite values for RGB and YCbCr,
  expands chroma with its 127-code range, and retains fractional headroom through
  integer color conversion; malformed ranges raise named decode errors (#48).
- GIF accepts a missing trailer after a complete frame at a block boundary,
  preserving animation pixels, delays and loop count (#80).
- Codec origins and licenses are recorded, and the original zlib notices are
  retained in the altered source ports (#86).

## [0.2.0] - 2026-09-29

### Changed

- **The library is now KiteImageCodec.** It is published as
  `io.github.yuroyami:kiteimagecodec`, `kiteimagecodec-compose` and
  `kiteimagecodec-coil`, and its package is `io.github.yuroyami.kiteimagecodec`.
  The `KiteImage` object is now `KiteImageCodec`, so `KiteImage.decode(bytes)`
  becomes `KiteImageCodec.decode(bytes)`. The iOS frameworks are `KiteImageCodec`,
  `KiteImageCodecCompose` and `KiteImageCodecCoil`. The composable `KiteImage()`,
  `KiteAsyncImage`, `KiteAnimatedImage` and `KiteImageDecoder` keep their names.
  0.1.0 stays on Maven Central under the old coordinates.
- **The functions that throw are now marked `@Throws`.** `probe`, `decode`,
  `decodeReduced`, `decodeAnimation` and `CcittFax.decode` declare
  `ImageDecodeException`. `decodeReduced`, `encodeGif`, `encodeJpeg` and
  `JpxDecoder.decode` with a reduction declare `IllegalArgumentException`. In
  Swift and Objective-C a call is now a `try`, and the exception arrives as an
  `NSError`. Before, a failed decode ended the process.
- **A frame count is checked against the input size.** GIF, APNG and animated
  WebP keep one full canvas per frame, so their frames together must now fit the
  same 4096 pixels per input byte as a still image.
- **`KiteAnimation.durationMillis` stops at `Int.MAX_VALUE`** instead of wrapping
  negative for a very long animation.

### Added

- **`KiteImageCodec.decodeReduced()` and `JpxDecoder.decode(data, reduction)`.**
  Decode with each side divided by 2, 4 or 8, rounded up. A JPEG shrinks inside
  its inverse DCT, as libjpeg's `djpeg -scale` does, so a baseline JPEG needs
  memory only for the smaller image. A JPEG 2000 image drops its finest wavelet
  levels, as OpenJPEG's reduce option does. Other formats decode in full and
  average each block.

### Fixed

- **A JPEG 2000 codestream cut off inside a packet header made the decoder loop
  forever.** The decoder now keeps the packets before the cut and skips the rest
  of the tile, as OpenJPEG does.
- **A JPEG 2000 image whose origin is not zero decoded as noise.** The first
  precinct of a high-pass band lost its first column, so every packet header
  after it read the wrong code-blocks.
- **On WebAssembly, a JPEG 2000 header cut off inside a segment stopped the
  program.** WebAssembly traps on an index out of bounds instead of throwing,
  and the header reader relied on the exception. It now checks its bounds, so
  the decode fails cleanly.
- The JPEG 2000 decoder now checks the input-size budget, so a damaged header in
  a small file cannot allocate planes for tens of megapixels.
- `probe` threw `ArrayIndexOutOfBoundsException` for a JP2 box whose
  length does not fit an `Int`. It now throws `ImageDecodeException`.
- **`encodePng` could write a PNG that no reader could open.** An image with
  enough detail made the literal Huffman tree deeper than the 15-bit limit, and
  the length-limiting step left the code over-subscribed. zlib, `javax.imageio`
  and the library itself rejected the file.
- **A small JPEG could keep the decoder busy for minutes.** Every scan walked the
  whole image, even a scan with no data. A scan now stops when its data ends,
  and the blocks the file never reaches stay mid-gray. A frame may hold at most
  512 scans.
- A GIF frame rectangle was never checked, so a 32-byte file threw
  `NegativeArraySizeException` or reserved a gigabyte. The frame is now checked
  against the input size.
- An APNG frame whose offset was near 2^31 passed the bounds check and then wrote
  outside the canvas.
- A PNG with two palette chunks read past the end of its transparency array. The
  decoder now refuses a second palette chunk, as libpng does.
- A damaged Deflate strip in a TIFF threw an internal exception instead of
  `ImageDecodeException`.
- A tiled TIFF whose tile row passed 2^31 bits threw `NegativeArraySizeException`.
- A TIFF without `RowsPerStrip` was refused, although the format makes the
  default one strip.
- `KiteBitmap`, `cropped` and the Compose animation player compared or summed
  sizes in `Int`, which wraps. An empty array passed for a 65536 by 65536
  bitmap, and a 3300-frame animation showed its last frame first.
- `encodeGif` and `encodeJpeg` wrote an image wider or taller than 65535 pixels
  with its size modulo 65536. They now throw `IllegalArgumentException`.
- **The public CCITT and JBIG2 decoders trusted their sizes.** A negative or zero
  column count, a segment length that wrapped, and a page that claimed a
  gigabyte all ended in an exception, an endless loop or, on WebAssembly, a
  trap. Both now check their geometry, and the JBIG2 reader checks its bounds.
- The Coil factory claimed a lossy WebP whose image chunk lay past its 64 KiB
  peek, and the request then failed. `probe` now reads an image chunk that the
  data cuts off, and reports data that ends before any image chunk as not
  decodable.
- Some README statements did not match the code: the test count, the JPEG 2000
  budget, the streaming limit, the APNG delay rule and the Coil example.

## [0.1.0] - 2026-07-25

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
  VP8L codec: prefix-code groups, meta-prefix images, color cache, LZ77 back
  references, and all four transforms. Animated WebP (`ANIM` / `ANMF`)
  composites through the same source-over operator APNG uses.
- **GIF encoding.** Median-cut quantization, optional Floyd-Steinberg dithering,
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
- **CI**, covering JVM, JS, wasm, native and Android, with the tool-backed codec
  oracles (OpenJPEG, libwebp, libtiff) installed rather than skipped. The
  stb_image oracle needs no tool: its vectors are committed.
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

- **The Compose and Coil bindings ignored EXIF orientation**, so a phone photo
  drew on its side. Both now decode with orientation applied, which is what
  Coil's own platform decoders do; swapping in `KiteImageDecoder` is no longer a
  regression against a stock `ImageLoader`.
- WebP animation frames whose rectangle left the canvas wrote out of bounds.
- TIFF fields with a corrupt value count could size an array before anything
  checked the values existed.
- TIFF fields with a zero value count were indexed as if they held one.
- The oracle test suites resolved their reference binaries from a hardcoded
  `/opt/homebrew/bin`, so they silently skipped everywhere except one kind of
  developer machine, CI included. They now search `PATH`, and CI installs the
  OpenJPEG tools it was previously missing.
- Attribution: the WebP, APNG, TIFF and BMP work was written from published
  specifications and verified against reference *binaries*, with no reference
  source consulted. `NOTICE` and `reference/REFERENCES.md` now record that, and
  the VP8L header comment no longer claims a port it isn't.
- The published POM descriptions still advertised the pre-JPEG format list.
- The sample gallery hand-rolled a BMP because "BMP has no encoder yet", and
  showed none of the newer formats. It now covers APNG, lossless and animated
  WebP, both new encoders, and captions every tile from `probe`.
