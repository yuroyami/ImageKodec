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

### Added

- `ImageKodec.encodeWebp` writes a lossless WebP. The image is tried as a palette
  when it has 256 colours or fewer, alone or with the predictor over its indices,
  and through the subtract-green, predictor and cross-colour transforms, each
  tile's predictor and colour transform chosen by the bits it adds; then LZ77
  with VP8L's two-dimensional distance codes, a colour cache sized by what it
  saves, and an entropy image whose regions get prefix codes of their own,
  clustered by k-means. Every candidate is written and the smallest kept. dwebp
  reads every file back exactly, and each is smaller than the PNG (#12).

- `ImageKodec.encodeTiff` writes a TIFF of one page or of many, and
  `encodeTiff16` the same at 16 bits from `KiteBitmap16`: gray when every pixel is,
  RGB otherwise, with an unassociated alpha sample when there is alpha, in strips
  of about 8 KiB compressed with Deflate after the horizontal predictor, each page
  of a multi-page file marked with its page number. `decode`, `decodePage` and
  `decode16` read it back exactly, and so do libtiff and ImageMagick (#9).

- `ImageKodec.encodePng(animation)` writes an animated PNG, so an animation keeps
  full colour and partial alpha where a GIF would quantise it: 8-bit RGB, or RGBA
  when any frame has alpha, each frame's delay to the millisecond and the loop
  count. The first frame is the default image; each later frame stores the
  rectangle that changed, replacing the canvas there or drawn over it with the
  unchanged pixels transparent, whichever compresses smaller. Pillow and ffmpeg
  read every frame back exactly (#64).

- AVIF decodes on every target, through `decode`, `decode16`, `probe` and the
  sized and reduced decodes. A pure-Kotlin AV1 decoder follows the decoding process
  of the AV1 specification for every intra coding tool, with the loop filter, CDEF,
  superres, loop restoration and film grain synthesis, at 8, 10 and 12 bits in
  4:0:0, 4:2:0, 4:2:2 and 4:4:4; its planes match dav1d's sample for sample. The
  container reads the primary item, falling back through an `altr` group to an item
  it can show; item data in `mdat` or `idat`, in any number of extents; `grid`,
  sample transform (`sato`) and overlay (`iovl`) derived images, and a tone-mapped
  image's base image; an alpha auxiliary image, straight or premultiplied; and the
  first frame of an image sequence. YUV converts to RGB with the `colr` box's or the
  sequence header's matrix and range, as libavif's float path does, within one level
  and mostly exactly. The clean aperture crops; rotation and mirroring are reported
  as `ImageInfo.orientation` and applied with `applyOrientation`. The ICC profile
  and the code points are reported in `ImageInfo.colorProfile`. Samples are kept at
  16 bits and every post filter works in place, so a 12-megapixel AVIF decodes in a
  96 MB heap, its 45 MB result included (#41).

- Animated AVIF plays through `decodeAnimation`. The AV1 decoder does inter
  prediction with every tool of the specification: the motion vector stack with its
  temporal candidates from projected motion fields, single and compound references
  with averaged, distance, difference and wedge masks, inter-intra, overlapped block
  motion compensation, local and global warps, switchable and dual interpolation
  filters, skip mode, segmentation maps kept or predicted from earlier frames, short
  reference signalling, and references of another size, scaled as the frame size
  changes or under superres. Every frame of sequences from libaom, SVT-AV1 and rav1e
  matches dav1d's sample for sample. Each frame takes its duration from the track's
  timing and the animation its loop count from the edit list as libavif counts it;
  the alpha track decodes beside the colour track, straight or premultiplied, and
  `maxFrames` and the cancellation check work as for GIF, APNG and WebP (#41).

- `ImageKodec.decode16` keeps every bit of a JPEG 2000 component up to 16 bits
  deep, replicated up to 16 bits, through the palette, channel definitions, sYCC
  and e-sYCC, CMYK and CMY, and straight or premultiplied opacity, in the channels
  the colour stage gives. A value the colour stage computes stays in the byte the
  8-bit decode gives, so `toBitmap` still returns exactly what `decode` does. 10-,
  12- and 16-bit gray, RGB, RGBA and sYCC files match opj_decompress's samples at
  their own precision exactly, and 9/7 files within one level (#107).

- JPEG 2000 decodes all of Part 1. Every code-block style: the arithmetic coding
  bypass with its raw passes, context reset, termination on each pass,
  vertically causal contexts and segmentation symbols, alone or together, each
  codeword segment its own decoder run as T.800 Annex D lays them out; progression
  order changes, a packet read where the first change reaches it and a tile's
  changes replacing the main header's as A.6.6 orders them; packet headers packed
  into PPM or PPT segments, a PPM chunk going to the tile-part it belongs to even
  when tiles take turns; and RGN regions of interest by the Maxshift method. Every
  style and mix of them, ROI shifts and POC orders match opj_decompress sample for
  sample on lossless files, and PPM and PPT files rebuilt from OpenJPEG's SOP and
  EPH codestreams decode as the codestream they came from. Only the high-throughput
  coder of Part 15 is still refused, by name (#5).

- `ImageInfo.colorProfile` reports what a file declares about its color space,
  as `probe` reads it from the header area: the embedded ICC profile byte for
  byte (PNG `iCCP`, JPEG `APP2` segments joined in sequence order, TIFF tag
  34675 per page, WebP `ICCP`, JPEG 2000 `colr`, a BMP V5 header's profile and
  GIF's `ICCRGBG1012` extension), PNG's `sRGB`, `gAMA`, `cHRM` and `cICP`, and
  the profile's color space and description. A damaged color chunk is ignored,
  and decoded samples do not change; converting them is #108. The JDK's sRGB
  and PhotoYCC profiles embedded by ImageMagick come back byte for byte from
  every format it writes them to (#16).

- `ImageKodec.decode16` and `KiteBitmap16` keep every bit of a 16-bit PNG or
  TIFF, which `decode` narrows to the high byte, including a TIFF palette's
  16-bit ColorMap. The samples come in the channels the file stores once a
  palette is looked up, so a gray depth map stays one sample a pixel; narrower
  samples replicate up, and every other format widens what `decode` gives,
  each byte times 257. `KiteBitmap16.toBitmap` narrows back to exactly what
  `decode` returns, and `oriented` applies an EXIF orientation. ImageMagick's
  16-bit PNGs and TIFFs match ImageIO's 16-bit rasters sample for sample (#10).

- TIFFs whose strips or tiles are JPEG decode. Compression 7, which Technical
  Note 2 defines and current writers use, splices the JPEGTables into each
  block, converts YCbCr as libtiff's RGB color mode has libjpeg do it, which is
  what ImageMagick and Pillow read, and passes RGB and gray samples through.
  Old-style JPEG, compression 6, is rebuilt into one stream the way libtiff
  does it, from headers at JPEGInterchangeFormat or bare tables behind the
  table tags, with a restart marker between strips, and its samples go to the
  TIFF YCbCr path with the file's ReferenceBlackWhite. Every layout decodes
  within 4 levels of libtiff's `tiff2rgba`, and so do the old-style samples in
  libtiff's own test images. ReferenceBlackWhite stored as SHORT or LONG values
  is read as libtiff reads it, where it was refused (#8).

- Every page of a multi-page TIFF is reachable. `ImageInfo.pageCount` counts
  the image file directories in the file's main chain, as libtiff, ImageIO and
  libvips do, and `ImageKodec.decodePage` and `ImageKodec.probePage` decode and
  describe any of them, each with its own size and orientation. `decode` still
  returns the first page. Pages are not frames, so `frameCount` stays 1. A
  chain ends at a link that loops back, leads outside the file or is cut off,
  and the pages before it stay readable, as in libtiff; `probe` counted one
  page and nothing said the others were there (#7).

- `ImageKodec.decodeAnimation` takes `maxFrames` and stops after that many
  frames without reading past them, so a damaged later frame does not fail
  them. `maxFrames = 1` gives the first frame of the animation as it plays,
  which for an APNG whose default image sits outside the animation is not what
  `decode` returns. The result keeps the file's delays and loop count (#74).

- `ImageRequest.Builder.maxFrames` asks `KiteImageDecoder` for at most that
  many frames of an animation, and `maxFrames(1)` gives the first frame as a
  still. The count is part of the memory cache key, so a still never answers a
  request for the animation, and `ImageRequest.maxFrames` and
  `Options.maxFrames` read it back. `KiteAsyncImage(animate = false)` asks for
  one frame, where it decoded and downscaled every frame and then showed the
  first; turning `animate` on requests the rest while that frame stays up, and
  turning it off once every frame is there makes no new request (#106).

- Lossy WebP decodes: VP8 key frames, their `ALPH` opacity (raw or VP8L
  compressed, with any of the three prediction filters), and animation frames
  that are lossy or mix both codecs. The VP8 decoder is a port of libwebp
  1.3.2's, and the chroma is upsampled as dwebp's default output does, so every
  pixel matches `dwebp`, checked across cwebp's quality, filter, segment, noise
  shaping and method settings, every alpha coding, libvpx key frames with up to
  eight token partitions and filter deltas, and img2webp animations against
  `anim_dump`. The constant tables come from libwebp's source through
  `tools/vp8_tables.py`, which records their checksums. `probe` reports lossy
  files as decodable, and `KiteImageDecoder` claims them (#11).

- `ImageKodec.decodeScaled` and `ImageKodec.decodeDownscaledTo` decode straight
  to the size `scaled` or `downscaledTo` would give after a full decode. They
  read the header, decode a JPEG or JPEG 2000 at the largest reduction whose
  output still covers the target, and finish with the same box filter, so the
  result is never smaller than asked for and never upscaled. A 6000 by 4000
  JPEG asked for at 200 by 200 takes 65 ms of CPU time and allocates 2.6 MiB,
  where a full decode and `scaled` take 331 ms and 126 MiB, and the pixels
  differ by 1.3 to 2.5 levels on average. Other formats decode in full and
  filter once (#13).

- `KiteImageDecoder` passes Coil's computed size down for a still JPEG or
  JPEG 2000, so a 4000 by 3000 photo in a 100 by 100 slot takes 30 ms and
  1.3 MiB where it took 157 ms and 63 MiB. Sizes and the sampled flag stay
  those of Coil's own decoder (#13).

- A BMP whose header declares BI_JPEG or BI_PNG, as printer drivers and some
  Windows clipboard paths write them, decodes to the JPEG or PNG file its pixel
  array holds, where it used to be refused. As in ImageMagick, the file runs
  from the pixel offset to the end of the data and its own size is what decodes
  and what `probe` reports. It must be the declared format, so a BMP inside a
  BMP is refused rather than read recursively, and `decodeReduced` reduces an
  embedded JPEG inside its inverse DCT (#17).

- `ImageKodec.decodeJpegComponents` and `JpegComponents` return a JPEG's samples
  after the inverse DCT and the upsampling, before any color conversion: every
  component in the frame header's order, and the Adobe transform flag. It takes
  the same reductions as `decodeReduced` and costs about as much, so a CMYK or
  YCCK image takes one decode where KitePDF needed four (#103).

- Animation-wide `flippedHorizontal`, `flippedVertical`, `transposed` and
  `transversed` helpers, matching the bitmap geometry API and preserving every
  frame's timing and the play count (#15).

### Changed

- `ImageFormat` has a new entry, `AVIF`, which `sniff` and `detect` return for an
  ISO base media file whose `ftyp` names the `avif` or `avis` brand. A `when` over
  `ImageFormat` that lists every entry needs a branch for it (#41).

- `ImageInfo`'s constructor takes `pageCount` and `colorProfile` last,
  defaulting to 1 and null. Code compiled against the old constructor must be
  compiled again (#7, #16).

- `decodeAnimation` takes `maxFrames` before `cancellationCheck`. A call that
  passes the check as a trailing lambda or by name compiles unchanged; one that
  passes it third by position needs its name. Code compiled against the old
  signature must be compiled again (#74).

- `KiteImage(data)` does its work on `Dispatchers.Default` and stops it when
  nobody will see it. Its decode stops at the next frame once it leaves
  composition or `data` changes, where it ran to the end and threw the frames
  away. `animate = false` decodes only the first frame: 3 to 45 ms and 1.2 MB
  for a 120-frame 640 by 480 GIF, where all of it took 207 to 469 ms and 147 MB.
  Turning `animate` on later decodes the rest while that frame stays up. The
  first frame becomes an `ImageBitmap` in the same background step, and
  `KiteAnimatedImage` converts the others there ahead of playback, so the
  thread that draws never copies a frame; when one comes due before it is
  ready, the newest converted frame stays up. `KiteImage(bitmap)` converts off
  that thread as well, except a bitmap of at most 65,536 pixels, which
  converts at once because that costs less than a frame of nothing. While a
  bitmap converts, the composable lays out at its size and draws nothing (#74).

- `toImageBitmap` on every target but Android premultiplies into Skia's
  native N32 raster itself, where it had Skia draw an unpremultiplied image
  into a new bitmap. The bitmap is byte for byte the same, and a 4000 by 3000
  photo converts in 38 ms where it took 165 ms (#74).

- The CCITT decoder behind TIFF fax compression, `CcittFax` and JBIG2's MMR
  regions holds each row as its changing elements, as libtiff and xpdf do,
  instead of one int per pixel, and writes each row's runs straight into one
  packed buffer instead of packing every pixel into a new array and copying
  every row again. A peek reads a whole word, and a run length is one lookup
  per code. A 5100 by 6600 Group 4 text page decodes in about 11 ms of thread
  CPU time where it took about 75, to the same bytes as libtiff, and 36,000
  valid, damaged and random streams decode to the same bytes or the same
  error as before (#105).

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

- JPEG 2000 sYCC converts to RGB as OpenJPEG's `sycc_to_rgb` does, each product in
  double precision before it truncates. The fixed point it used before put a
  12-bit product on the other side of an integer now and then, one level off (#107).
- `FuzzTest` runs every mutant against a deadline, which on the JVM also stops a
  loop that never ends, and drives the JPEG 2000 and JBIG2 decoders through
  internal entry points that let a fault escape, where the facade and their
  public entry points turn any exception into a refusal. It also takes the
  reductions 2 and 4 and the sized decode, and seeds written by libtiff (tiled
  LZW with a 16-bit predictor), ffmpeg, cjpeg (4:2:2), libjpeg (YCCK),
  OpenJPEG (multi-precinct RPCL and CPRL, palette, CMYK and premultiplied
  opacity) and ImageIO (an MMR-coded JBIG2 region). Two weak tests now assert
  their outcome: every WebP truncation is refused, and a GIF's pixels survive
  ten LZW table resets (#102).

- What the stronger fuzzing found: a JPEG 2000 packet header whose code-block
  length runs past 32 bits is refused, as OpenJPEG refuses it, where the read
  wrapped to a negative length, and a component subsampled past the whole image
  is refused by name, where it had no samples to read. JBIG2's refusals are
  `ImageDecodeException` throughout, and its page and regions keep only the
  rows the caller's page shows, so a damaged size in a stream of a few hundred
  bytes no longer costs seconds and hundreds of megabytes. A region more than
  four times wider than the page, which no encoder writes and which would have
  to be decoded in full, is refused (#102).

- The deflate encoder behind PNG and the zlib and gzip framers writes stored
  blocks and splits its output into blocks, as zlib's `_tr_flush_block` does.
  Each block takes whichever of stored, fixed or dynamic Huffman is smallest,
  and neighbouring blocks join while one block costs less than two. A MiB of
  random bytes now takes 85 bytes more than its size, 5 for each stored block,
  where it took 751 more, and a photo's PNG comes out slightly smaller (#20).

- `probe` and the decoders agree on PNG sizes and JPEG 2000 alpha. A PNG side of
  0 or past 2^31 - 1 throws `ImageDecodeException` on both sides, where `probe`
  used to report a zero or negative width, and a size past the decoder's limits
  or input budget makes `probe` name the refusal the decode throws. A JPEG 2000
  component beyond the colours that no `cdef` box describes is opacity on both
  sides, as OpenJPEG writes and Pillow reads RGBA and gray-and-alpha
  codestreams. `ProbeTest` checks its agreements on every format now (#82).

- A JP2 file's palette (`pclr` with `cmap`), channel definitions (`cdef`, with
  their associations and premultiplied opacity) and colour specification
  (`colr`) now apply, as T.800 Annex I orders them. sYCC, e-sYCC, CMYK, CMY and
  bi-level samples convert as OpenJPEG converts them, and an ICC profile picks
  gray, RGB or CMYK. These boxes used to be ignored, so such files decoded into
  the wrong colours. A colour space with no exact conversion here, such as
  CIELab, is refused by name, and `probe` reports the same reason and reads its
  alpha from the same boxes (#57).

- JPEG 2000's RPCL, PCRL and CPRL progressions visit each precinct where
  T.800 B.12.1.3 to B.12.1.5 reach it on the reference grid, instead of pairing
  precincts by their ordinal across resolutions and components. A file with more
  than one precinct per resolution, or with subsampled chroma, used to decode
  into scrambled pixels in those orders. All five orders now match OpenJPEG
  exactly, tiled, offset, reduced and subsampled (#56).

- Irreversible (9/7) JPEG 2000 keeps its coefficients and samples in wide fixed
  point through the wavelet synthesis and the ICT, and rounds once at the end,
  as OpenJPEG keeps real values until its output conversion. Full-scale 16-bit
  content used to overflow the lifting, so half of a checkerboard's samples came
  out wrong, and every 9/7 image was truncated half a level dark. Against
  `opj_decompress` the mean difference falls from 1.03 to under 0.02 levels and
  the worst from 4 to 1, at full size and reduced. Values a damaged stream
  pushes past the representable range are refused instead of wrapping (#101).

- The JPEG suite covers every feature the README lists with a test that runs on
  every target: restart intervals in baseline and progressive libjpeg-turbo
  files, a one-component file, CMYK, YCCK, 4:1:1, 4:4:0 and 4x2 sampling, each
  bit-identical to stb_image. Every committed stb vector regenerates from
  `tools/stb_dump.c`. ImageIO checks restart intervals from 1 to 100 in 4:2:0,
  4:2:2 and 4:4:4, baseline and progressive, in place of a test that only ran on
  macOS and passed without asserting elsewhere (#72). The libtiff oracle no
  longer holds 4:4 YCbCr tiles to a libtiff bug fixed in 4.7.2 (#104).

- A progressive JPEG's AC refinement no longer walks the coefficients of a block
  that holds none: each block keeps the index of its last nonzero coefficient,
  as ffmpeg does. A refinement scan whose bit positions skip a bit is refused, as
  libjpeg refuses it, and one that does not continue its band's progression is
  passed over instead of applied, so no coefficient takes more than 13
  refinements. A 17 KB file of 512 such scans took 8 s and now takes about as
  long as one scan; conforming files decode exactly as before (#67).

- A JPEG frame with 2 or more than 4 components, or with sampling factors that
  do not divide the largest one, throws `UnsupportedImageException` naming the
  feature, as the 12-bit, lossless, arithmetic and hierarchical refusals do,
  instead of a plain `ImageDecodeException`. `probe` and the decoder take their
  refusals from one list, so `probe` no longer calls a file with unsupported
  sampling decodable, and each refusal's message starts with the reason `probe`
  reports (#70).

- A reduced JPEG decode reduces each chroma plane across and down by its own
  power of two, as libjpeg 9 does, so a file sampled differently each way, such
  as 4:2:2, 4:1:1 or 4x2, keeps the chroma detail of its finer direction instead
  of reducing it too far and upsampling it back. Upsampling left over for a ratio
  of 4 replicates, as the full decode does, so a reduced image stays the average
  of the full one. A 4:1:1 file at an eighth used to be 13 levels off that
  average and is now under 1; libjpeg-turbo's `djpeg -scale` stays as far off
  as before, since it keeps one size for both directions (#63).

- A reduced JPEG decode at 1/2 and 1/4 averages the full inverse DCT over each
  output pixel, as libjpeg's `jidctred.c` does, instead of keeping only the
  lowest frequencies of each block. One-pixel detail used to leave stripes up to
  44 levels off where libjpeg gives a flat average. `decodeReduced` now lands
  within a mean of 0.06 and a worst of 3 levels of `djpeg -scale` on photo-like
  images, from 1.6 and 12, and runs faster at a quarter and an eighth (#62).

- A four-component JPEG without an Adobe marker decodes as CMYK, and one whose
  Adobe transform is neither 0 nor 2 as YCCK, the color spaces libjpeg picks.
  Both used to go through YCbCr with the black component dropped, so an
  unmarked CMYK file lost everything its K carried. The samples follow Adobe's
  inverted convention, as browsers and ImageMagick read every CMYK JPEG. The
  tests use libjpeg-written CMYK, YCCK and unmarked files from
  `tools/cmyk_jpeg.c` (#65).

- 4:2:2 JPEGs weight the nearer chroma sample by three at the right edge of each
  row, as libjpeg does. stb_image swaps the last two weights there, which put a
  wrong pixel next to the edge, up to 88 levels off on a sharp border. The
  decoder stays bit-identical to stb elsewhere, and `tools/stb_dump.c` now
  commits the program and the commands behind the stb vectors (#69).

- Signed JPEG 2000 components map their full centered range into unsigned display
  samples before clipping. Gray, RGB, mixed-sign components and opacity retain
  their negative samples, for both wavelet filters and component transforms;
  unsigned components keep their existing DC restoration (#100).

- JPEG 2000 scalar-derived quantization follows the band's resolution order.
  LL and the coarsest details keep the base exponent; finer details decrease
  it successively. QCD/QCC in both main and tile headers reconstruct the same
  pixels as their equivalent explicit step tables (#99).

- JPEG 2000 reversible code-blocks that stop partway through a bitplane apply
  reconstruction bias at each coefficient's last decoded plane. Rate-limited
  5/3 streams match OpenJPEG exactly, including reduced output; fully decoded
  coefficients retain their exact integer value (#98).

- JPEG 2000 irreversible 9/7 reconstruction applies the single-sample odd-origin
  division in the fixed-point domain, correcting large errors in small tiles
  and reduced decodes while preserving reversible output (#97).
- Positive CCITT `K` selects mixed Group 3 decoding with per-row mode tags,
  reference-row resets, EOL/fill framing, byte alignment and RTC termination.
  TIFF T4Options bit 0 uses the same decoder; uncompressed fax extensions remain
  named unsupported features. Invalid or incomplete mixed rows throw (#60).

- JPEG 2000 probe reads main and tile-part coding/quantization declarations with
  the decoder's shared bounded readers. It checks later tile-parts while
  skipping packet payloads, reports matching field refusals and stays inside
  the codestream box. All eight legal guard-bit counts remain supported (#4).

- JPEG 2000 probe and decode share the input-relative pixel budget and its
  2^28-pixel absolute ceiling. The probe names the same size refusal as decode;
  the separate 64-megapixel constants are removed (#2).

- JPEG 2000 header failures name the missing marker, malformed field or
  truncated segment and its byte position. Unsupported markers and code-block
  styles use typed refusals. Segment readers stay inside their declared bounds;
  public nullable decoder methods and partial-packet recovery are preserved (#3).

- The Coil factory checks every WebP frame codec beyond its initial peek, so
  mixed animations with later lossy frames fall through to the platform decoder.
  Large lossless animations remain supported; chunk lengths, padding and
  truncation are checked before claiming the stream (#75).

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
