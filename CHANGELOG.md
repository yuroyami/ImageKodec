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

- TIFF decodes unsigned samples of 1 to 32 bits, signed samples, and IEEE floats of 16, 24, 32 and 64
  bits, which `decode16` gives at 16 bits, as ImageMagick reads them: floats as a fraction of full scale,
  signed samples moved up by half their range. The floating-point predictor (3) and the horizontal
  predictor at 32 and 64 bits decode, and so do 12-bit JPEG strips, JPEG YCbCr in separate planes,
  old-style JPEG in separate planes and in tiles of several columns, and CCITT's uncompressed mode,
  which `CcittFax` reads too. YCbCr past 8 bits, inks other than CMYK, old-style lossless JPEG and
  complex samples stay refused, by name, because no reader gives a picture to check against (#189).
- JBIG2 decodes arithmetic symbol dictionaries that aggregate several instances into a symbol, and
  immediate generic regions of unknown length that end with a row count, as jbig2dec reads them. A
  Huffman dictionary that refines stays refused: jbig2dec misreads one, so nothing could check (#189).
- JPEG XR decodes 32bppRGBE, sample for sample as `JxrDecApp`, its picture in linear light converted
  to sRGB. CMYK and the N-channel formats stay refused: no installed tool writes either (#189).
- AVIF converts BT.2020 and chromaticity-derived constant luminance and ICtCp (PQ and HLG) as zimg
  converts them, within a level of `zscale`. SMPTE ST 2085, `iloc` construction method 2, reserved sample
  transform tokens and tile list OBUs stay refused, each with its reason in `docs/limits.md` (#189).
- JPEG XL writes a lossy picture whose header has only an ICC profile into that profile when it is one
  of matrix and curves, as libjxl does through a colour engine, and describes a header of its own
  primaries with an ICC profile made from it, so `ColorTarget.Srgb` converts it as djxl does. The intensity
  target is the peak that tone mapping takes. A CMYK file is refused by name instead of losing its black (#189).
- PNG decodes Apple's CgBI files, which Xcode writes into iOS apps: the raw deflate stream, and BGR
  and premultiplied BGRA samples at 8 bits, which come back as straight RGB and RGBA. A CgBI file of
  16-bit colour, which Xcode does not write, stays refused by name (#189).

- JPEG 2000 decodes HTJ2K (ITU-T T.814): the HT block coder's cleanup pass, with its MagSgn, MEL and
  VLC streams, and its SigProp and MagRef passes, in HT tile-components alone or beside Part 1 ones.
  The `CAP` and `CPF` segments are read, and a JPH file (`ftyp` brand `jph `) is detected and probed
  as JPEG 2000. Everything outside the block coder is Part 1's: tier 2, the wavelets, ROI, reductions
  and tiles. `opj_decompress` 2.5 reads every HT file the encoder writes to the same samples, at full
  size and at every reduction. Code-block style 0x80 (MIXED) and an HT code-block of more than one HT
  set stay refused, by name (#187).
- `Jp2EncodeOptions(highThroughput = true)` writes HTJ2K: a `CAP` segment, code-block style 0x40 and,
  in a JP2 file, the `jph ` brand. Each code-block is one HT set, picked by the rate control; the file
  has one quality layer (#187).
- The CIELab colour space of a JP2 header (enumerated 14) converts to sRGB, with its default
  parameters or its own, as lcms2 converts a Lab profile, relative colorimetric (#187).

- `readMetadata` reads EXIF, XMP and ICC from TIFF (the first page's EXIF and GPS directories
  rebuilt as a block, tag 700, tag 34675), JPEG XL (`Exif` and `xml ` boxes, also inside Brotli
  `brob` boxes, and the codestream's profile), JPEG XR (its EXIF, GPS, XMP and ICC tags), GIF (the
  `XMP DataXMP` and `ICCRGBG1` `012` application extensions) and BMP (the profile a BITMAPV5HEADER
  embeds), and the UUID boxes ExifTool writes after a JP2 codestream. `encodeTiff` with options now
  writes the EXIF block of its metadata as directories of the first page, where it left it out
  before. `encodeGif(bitmap, metadata, dither)` and `encodeGif(animation, metadata, dither)` write
  the XMP and ICC extensions, and `encodeBmp(bitmap, options, metadata)` embeds the ICC profile under
  a BITMAPV5HEADER. Every call without metadata writes the bytes it wrote before. ExifTool reads every
  block every encoder writes, and `readMetadata` every block ExifTool writes. See
  [Keeping a photo's metadata](docs/encoding.md#keeping-a-photos-metadata) (#186).

- `imagekodec-heic` decodes the tools of the HEVC screen content coding extensions: palette mode
  with its predictor palette and initializers, escape samples and transposition, current picture
  referencing (intra block copy) with `use_integer_mv_flag`, the adaptive colour transform with its
  QP offsets, and disabled intra boundary filtering. It also decodes three separate colour planes
  (`separate_colour_plane_flag`). Every picture of the 15 SCC conformance streams of H.265.1 matches
  its hash SEI and its package MD5, so all 211 conformance streams match (#188).
- `encodeTiff(bitmap, TiffEncodeOptions, metadata, cancellationCheck)`, the same for pages, and
  `encodeTiff16` with options write the layouts the TIFF decoder reads: no compression, PackBits,
  LZW and Deflate with or without the predictor, CCITT Group 4 and Group 3 for a bi-level page
  through the fax encoder of #185, and JPEG with JPEGTables (compression 7); strips of any height
  or tiles; bi-level, gray, palette and RGB samples. `encodeTiffCmyk` writes CMYK separations from
  8- or 16-bit ink. An ICC profile and an XMP packet go into the file. `encodeBmp(bitmap,
  BmpEncodeOptions)` writes 1, 4 and 8-bit palettes, with RLE4 and RLE8 on request, and 16-bit
  5-5-5. The calls without options write the bytes they wrote before. libtiff and ImageIO read
  every TIFF variant to ImageKodec's samples, and ImageIO and ImageMagick every BMP variant.
  See [Writing images](docs/encoding.md#tiff-and-bmp-layouts) (#184).
- `encodeJpeg(bitmap, JpegEncodeOptions, metadata, cancellationCheck)` and
  `encodeJpeg16(bitmap16, JpegEncodeOptions, ...)` write every process of ITU-T T.81 but the
  hierarchical one: progressive files with libjpeg's default scan script, arithmetic coding with
  the conditioning of a DAC segment, Huffman tables built for the file, restart markers, one gray
  component, 4:4:4, 4:2:2 or 4:2:0, 12-bit DCT files, and lossless files at 2 to 16 bits with any
  predictor and point transform, Huffman or arithmetic. Both encoders honour the options of an
  8-bit DCT file. libjpeg-turbo 3.1 and ffmpeg read every file to the samples the coding promises.
  The calls without options write the same bytes as before. See [Writing images](docs/encoding.md)
  (#183).
- PNG writes every colour type and bit depth, a palette, colour keys and Adam7 interlace (#182).
  `encodePng(bitmap, PngEncodeOptions(...), metadata, cancellationCheck)` takes `PngColorType.Auto`,
  the smallest type that holds the pixels exactly (gray at 1 to 16 bits, a palette of up to 256
  colours with `tRNS`, RGB or RGBA, and a `tRNS` colour key in place of alpha where it fits), or a
  fixed type and depth, and an `effort` from 1 to 3. `encodePng16(bitmap16, ...)` writes 16 bits a
  sample for gray, gray and alpha, RGB and RGBA, read back by `decode16`, and `encodePng(animation,
  options, ...)` writes APNG in the same types. At the default effort a picture that a palette or gray
  holds is 32 to 78 percent smaller than `encodePng(bitmap)` writes it, and a photograph is no larger; effort 3 is
  1.2 percent smaller than `optipng -o2` over the test pictures. pngcheck, ImageIO, Pillow and
  ImageMagick read every type and depth back. `encodePng(bitmap)`, `encodePng(bitmap, metadata)` and
  `encodePng(animation)` keep their output byte for byte. See [Writing images](docs/encoding.md).

- `encodeCcitt` writes CCITT fax data, Group 4 (T.6) by default and Group 3 one- or two-dimensional
  (T.4) with `CcittEncodeOptions`, which takes the parameters of PDF's `CCITTFaxDecode`: K, the byte
  alignment, the polarity, EOL and EOFB or RTC. `CcittFax.encode` takes a packed page as
  `CcittFax.decode` returns one. `encodeJbig2` writes a JBIG2 page as one generic region, with the MQ
  coder in any of the four templates, typical prediction and MMR as `Jbig2EncodeOptions` choose, as
  the embedded stream of PDF's `JBIG2Decode` or as a `.jb2` file. Both make a bitmap bilevel at half
  its luma and code it losslessly. libtiff writes the same fax bytes, and jbig2enc the same generic
  region without typical prediction; libtiff and jbig2dec read every variant back to the source page.
  Symbol dictionaries are not written. See [Writing images](docs/encoding.md) (#185).
- `encodeJxl(bitmap, quality)`, `encodeJxl(bitmap, JxlEncodeOptions, metadata, cancellationCheck)`,
  `encodeJxl16` and `encodeJxl(animation, ...)` write JPEG XL, written from ISO/IEC 18181-1 and
  18181-2: lossy VarDCT in XYB with transforms up to 32 by 32 and chroma from luma, lossless
  Modular with a learned tree, a chosen colour transform and palettes, alpha, 16 bits, animations
  with delays and play count, and the container with EXIF, XMP and an ICC profile. libjxl's djxl
  reads every file; lossless files read back exactly. On photo-like scenes lossless files are 11
  percent smaller than `cjxl -d 0 -e 7` of libjxl 0.7, and lossy ones 11 percent smaller at the
  SSIMULACRA 2 score of `cjxl -d 1`. See [Writing images](docs/encoding.md#jpeg-xl) (#179).

- `encodeJp2(bitmap, quality)`, `encodeJp2(bitmap, Jp2EncodeOptions, metadata, cancellationCheck)` and
  `encodeJp2Wide` for a `KiteBitmap16` write JPEG 2000 Part 1, written from ITU-T T.800: lossy with the
  9/7 wavelet, the ICT and rate control by a rate and distortion sort of code-block passes, at a
  quality or at a compression ratio a layer, or lossless with the 5/3 wavelet and the RCT, at 1 to 16
  bits, gray, RGB and alpha described by a channel definition box. Tiles, levels, code-block sizes,
  precincts, layers, the five progression orders, SOP and EPH and every Part 1 code-block style are
  options, in a JP2 file with the metadata or as a bare codestream. OpenJPEG decodes every file to the
  samples ImageKodec's decoder gives, and on photo-like scenes the files match `opj_compress` within
  0.05 dB at equal size. `readMetadata` now reads the XMP, EXIF and ICC profile of a JP2 file. See
  [Writing images](docs/encoding.md) (#180).

- Lossy WebP writing, written from RFC 6386 and RFC 9649: `encodeWebp(bitmap, quality)` writes a VP8
  image at a quality with the meaning of `cwebp -q`, and `encodeWebp(bitmap, options, metadata,
  cancellationCheck)` takes a `WebpEncodeOptions` with `lossless`, `quality`, `effort` 1 to 3 (in
  the place of `cwebp -m 2`, `-m 4` and `-m 6`), `sharpYuv`, `alphaQuality` and a `WebpAlphaFilter`.
  Each macroblock's modes are chosen by distortion plus bits, in four segments, and effort 3 adds a
  trellis. Alpha goes in an `ALPH` chunk, exact by default. On the photo-like scenes of the tests the
  files are 8.5 to 11.6 percent smaller than `cwebp -m 4`'s at the same SSIMULACRA 2 score, and
  `dwebp` reads every file as ImageKodec does. `encodeWebp(animation, options)` writes an animated
  WebP, lossless or lossy, each frame coded as the rectangle that changed, replaced or blended over
  the canvas. `encodeWebp(bitmap)` writes the same lossless bytes as before. See
  [Writing images](docs/encoding.md#lossy-and-animated-webp) (#178).
- `encodeJxr` and `encodeJxr16` write JPEG XR, written from ITU-T T.832: lossy at a quality that
  means what `JxrEncApp -q` means, or lossless at 8 and 16 bits, in gray, RGB and RGBA, with 4:4:4
  or 4:2:0 chroma, the three overlap modes, planar or interleaved alpha, tiles with quantizers of
  their own, spatial or frequency order, and the ICC profile, XMP and EXIF of an `ImageMetadata`.
  `JxrEncodeOptions` holds the settings. On the photo scenes of the tests the files are 5 percent
  smaller than `JxrEncApp`'s at equal SSIMULACRA 2, and every file decodes in `JxrDecApp` to the
  samples ImageKodec's decoder gives. See [Writing images](docs/encoding.md#jpeg-xr) (#181).

- `ImageKodec.encodeAvif(bitmap, quality)`, `encodeAvif(bitmap, options, metadata, cancellationCheck)` and
  `encodeAvif16` write AVIF files with the core's own intra AV1 encoder, registered by default as the HEIF
  encoder of `av01` items, through the HEIF writer of #174 and the same `HeifEncodeOptions` (#176). Each
  picture is one key frame with a reduced still picture header: 8, 10 or 12 bits, 4:2:0, 4:2:2, 4:4:4 and
  gray, alpha as an auxiliary image, lossless at base_q_idx 0 with the identity matrix in 4:4:4. The encoder
  chooses partitions, intra modes (directional with angle deltas, smooth, Paeth, chroma from luma, filter
  intra, and for screen content palettes of luma and of chroma and intra block copy), transform sizes and
  types and levels by rate and distortion, and the loop filter levels, CDEF strengths and restoration
  units, self-guided or Wiener. Intra block copy, on a picture an eighth of whose blocks repeat, makes
  screen content 26 percent smaller at equal SSIMULACRA 2 score, and turns the loop filters off as AV1
  requires. Effort 2 makes files 14 percent smaller than it did when first written, in a fifth less time;
  CDEF filters a block whose taps are all inside the frame without checking each, in the decoder too. It runs the
  core's AV1 decoder in a coding mode, so its reconstruction is the decoder's; dav1d and ImageKodec decode
  every stream of `Av1EncoderOracleTest` to it sample for sample, and libavif, libheif and exiftool read
  the files in `AvifEncodeOracleTest`. A picture wider than 4096 with no tile size codes as AV1 tiles.
  The AV1 decoder's inverse transform skips the rows that hold only zeros and CDEF works out its damping
  once a block, which leaves its output unchanged.
- `ImageKodec.encodeHeic(bitmap, quality)`, `encodeHeic(bitmap, options, metadata, cancellationCheck)` and
  `encodeHeic16` write HEIC files: a HEIF writer in the core (`ftyp`, `meta` with `hdlr`, `pitm`, `iloc`,
  `iinf`, `iref`, `iprp` and `idat`, then `mdat`) around the intra HEVC encoder of `imagekodec-heic`, which
  `ImageKodecHeic.install()` now registers beside the decoder. `HeifEncodeOptions` sets quality, lossless,
  chroma subsampling (`HeifChroma`), bit depth (8, 10 or 12), effort, tile size, thumbnail size and
  orientation. RGB converts with BT.601's matrix in full range, signalled as sRGB; lossless uses the
  identity matrix in 4:4:4 and returns the bitmap exactly. Gray codes as 4:0:0, alpha as an auxiliary
  image, a picture larger than the tile size (512 by default) as a `grid` whose tiles the tile runner of
  `setTileRunner` encodes, and a subsampled picture of odd size with a `clap` crop. The ICC profile, EXIF
  and XMP of an `ImageMetadata` go in as `colr` `prof`, `Exif` and `mime` items, a thumbnail as a `thmb`
  item, and the orientation as `irot` and `imir`. Without the module, `encodeHeic` throws
  `UnsupportedImageException` that names it. libheif, libde265, ffmpeg and exiftool read the files in
  `HeicEncodeOracleTest` (#174).
- `HeifEncoder`, `HeifEncodeParameters`, `HeifCodedPicture` and `ImageKodec.registerHeifEncoder`, under
  `ExperimentalHeifCodecApi`: the encode side of the HEIF codec interface, through which a module adds the
  encoder of an item type (#174).
- `ImageKodec.setTileRunner` sets one `TileRunner` that every decode hands the tiles of a HEIC or
  AVIF grid to, as `TileJob`s, so a caller can decode them on its own threads. Each job decodes one
  tile with its own codec session and copies it into the picture at once, so a decode holds the
  picture and one tile for each job running; a reduced decode hands it one row of tiles at a time.
  The default, `TileRunner.Serial`, runs the jobs in order on the calling thread, as before, and is
  the one to keep on JS and Wasm. The pixels and the error do not depend on the runner: the first
  tile in tile order that fails gives the exception. A grid whose tiles are derived images, or that
  names a tile twice, still decodes in order. On four threads a 12-megapixel grid of 48 tiles
  decodes in 584 ms instead of 1,469 ms (#152).
- `HeicBenchmarkTest`, `HeicNativeBenchmarkTest` and `HeicWebBenchmarkTest` measure the decode of a
  12-megapixel HEIC grid of 48 tiles and of one 10-bit picture with wavefront rows, which x265 writes
  at test time, on the JVM, linuxX64, JS and wasmJs, beside libde265 on one thread.
  `docs/limits.md` has the table (#169).

- `decodeAnimation` plays HEIC image sequences with the `imagekodec-heic` module: every frame of a
  track whose sample entry is `hvc1` or `hev1` and whose handler is `pict` or `vide`, in presentation
  order from the composition offsets of `ctts`, each with its duration, the loop count from the edit
  list and an alpha track through `auxl`. `detect` and `probe` recognise the sequence brands `msf1`,
  `hevc` and `hevx`, and `probe` gives the frame count and size; without the module the error names
  it. The frames of x265 sequences with B pictures in an MP4 from ffmpeg equal ffmpeg's decode, in
  the same order (#157).
- `HeifCodecSession.decodeInOutputOrder` and `HeifCodecSession.flush`, under
  `ExperimentalHeifCodecApi`, let a codec whose output order differs from its decoding order give its
  pictures in output order. Both have default implementations, so a codec that does not reorder
  needs neither (#157).

- `ImageKodec.auxiliaryImages` lists the auxiliary images of a HEIC or AVIF file's primary image,
  alpha, depth and gain maps among them, each with its `auxC` URN, its size, its bit depth and the
  subtype bytes after the URN, unparsed; `decodeAuxiliaryImage` decodes one as a gray `KiteBitmap16`
  of its coded samples, widened to 16 bits. `pug.heic`, an iPhone photo, lists its 768x576 depth
  image and its 2016x1512 gain map, and both equal libde265's decode of their items (#154).
- `ImageKodec.decodeGainMap` returns the HDR gain map of a HEIC or AVIF file as a `HeifGainMap`: the
  decoded map, one or three channels at 16 bits, and its parameters. For a `tmap` item that is
  `GainMapMetadata`, the ISO 21496-1 fields of its payload with the payload itself; for an Apple
  photo's `urn:com:apple:photo:2020:aux:hdrgainmap` image, MakerNote tags 33 and 48 of its EXIF and,
  when both are there, the headroom Apple's formula gives. The map is returned, not applied, and
  `readMetadata` still decodes no picture. `HeifGainMap` is a new type beside `GainMap`, which stays
  the gain map of an Ultra HDR JPEG and keeps its `jpeg` bytes, so no code that reads it changes
  (#153).
- `decodeScaled` and `decodeDownscaledTo` reduce a HEIC or AVIF grid while it decodes. The decoder
  converts one row of tiles at a time to RGB and feeds it to the box filter, so a 48-megapixel grid
  never exists at full size. The result equals `decode` followed by the same scale, pixel for pixel
  (#151).
- A HEIC or AVIF file with several top-level images has a page for each, the primary first:
  `pageCount`, `probePage`, `decodePage` and `decode16(page =)` reach them as they reach the pages
  of a TIFF. `ImageInfo.stereoLeftPage` and `stereoRightPage` name the two eyes of a `ster` group,
  such as an Apple spatial photo (#168).
- `ImageKodec.decodeThumbnail` decodes the thumbnail that a file carries: a `thmb` item of a HEIC or
  AVIF file, or the thumbnail in the EXIF of a JPEG. Of several, it gives the smallest that reaches
  a requested box. `ImageInfo.thumbnailWidth` and `thumbnailHeight` give the largest one's size
  (#150).
- `imagekodec-heic`, the module that holds the HEVC decoder of HEIC, kept out of the core so that
  an app that reads no HEIC carries none of its code. `ImageKodecHeic.install()` registers its codec
  for `hvc1` items and `hvc1` and `hev1` tracks; a second call changes nothing. It reads the HEVC
  parameter sets and slice segment headers of ITU-T H.265, from `hvcC` or from the sample data, so
  `probe` reports a HEIC file's coded size after the conformance window, its depths, chroma format
  and VUI colour. The module is not published (#139, #140).
- `imagekodec-heic` decodes intra HEVC pictures. It reads the CABAC slice data with wavefront rows,
  entry points and several slices, and reconstructs 4:0:0, 4:2:0, 4:2:2 and 4:4:4 pictures with
  scaling lists, transform skip, lossless coding and PCM (#141, #142). It applies the deblocking
  filter and sample adaptive offset, and decodes tiles and dependent slice segments (#143, #144).
  A screen content coding tool is named in `unsupportedReason`.
- `imagekodec-heic` decodes the coding tools of the range extensions: transform skip rotation,
  context and sizes above 4x4, residual DPCM, cross-component prediction, extended precision and
  bit depths up to 16, persistent Rice adaptation, CABAC bypass alignment, disabled intra smoothing,
  chroma QP offset lists and scaled SAO offsets (#148).
- `imagekodec-heic` decodes P and B pictures. It derives the picture order count, the short-term
  and long-term reference picture sets and the reference picture lists, and keeps a decoded picture
  buffer with its output order from sample to sample. It skips the RASL pictures of a CRA picture
  that starts the stream (#156). It derives motion with merge mode, AMVP and temporal candidates,
  for every partition mode with the asymmetric ones (#171). It predicts samples with fractional
  interpolation and default or explicit weighted prediction, and filters inter edges (#172).
- Every picture of the 147 HEVC_v1 and 49 range extension conformance streams of H.265.1 matches
  its hash SEI where it has one, and the output of each stream matches the MD5 its package gives.
- With `imagekodec-heic` installed, `ImageKodec.decode` and `decode16` read HEIC photos end to end:
  grids, alpha, crops, rotation, mirror, overlays and thumbnails. Colour comes from the item's `nclx`,
  else from the VUI of the stream, also beside an ICC profile (#145).
- `ImageFormat.HEIC`: `detect` and `probe` recognise a HEIF file whose image is not AV1, by its HEVC
  brands, or by the type of the image it shows when it names only `mif1` or `msf1`. `probe` reports
  its size and depth from the container. Until a codec reads its HEVC items, `isDecodable` is false
  and the reason names the `imagekodec-heic` module (#138).
- `ImageKodec.registerHeifCodec` and the `HeifCodec` interface, marked `ExperimentalHeifCodecApi`, let
  a module supply the codec of a HEIF item type. The shared HEIF reader joins grids, applies alpha,
  crops and turns, and converts to RGB; a codec only turns coded samples into planes. AV1 is the
  built-in codec (#137).
- `encodeJpeg(bitmap, quality, JpegEncoderKind.Jpegli)`, and the overload with metadata, write a
  JPEG with a port of Google's Jpegli encoder at the settings its library gives by default: its
  quantization matrices scaled by the distance the quality maps to, adaptive quantization, 4:2:0
  and the standard Huffman tables, in a baseline file every reader opens. On photo-like scenes its
  files are 12 to 34 percent smaller than the default encoder's at the same SSIMULACRA 2 score, and
  they match pyjpegli 0.3.2 byte for byte on most images and within 0.1 percent in size on the
  rest. The default encoder is unchanged. See [Writing images](docs/encoding.md) (#133).
- JPEG XR decoding (ITU-T T.832 | ISO/IEC 29199-2), with HD Photo and Windows Media Photo, the
  format of XPS image brushes: `ImageFormat.JXR` sniffs the `II BC` container, `probe` reports its
  size, depth and alpha, and `decode` and `decode16` read the gray, RGB, BGR, BGRA and premultiplied
  PBGRA formats at 8 and 16 bits, the 16-bit and 32-bit fixed-point, half and float formats, converted
  from linear light to sRGB, and the packed 5-5-5, 5-6-5 and 10-10-10 formats. Lossless and lossy
  files, both bitstream orders, the three overlap modes, every internal colour format, soft and hard
  tiles and planar and interleaved alpha decode to the samples of jxrlib's `JxrDecApp`, whose decoder
  this one ports (#125). `decodeReduced`, `decodeScaled` and `decodeDownscaledTo` reduce a JPEG XR
  image inside the decoder by a half, a quarter or an eighth, as jxrlib's thumbnails (`JxrDecApp -T`)
  do, with the same samples.
- `ImageKodec.ssimulacra2` scores a distorted image against its source with SSIMULACRA 2,
  a port of libjxl's `tools/ssimulacra2.cc`: 100 for identical images, lower the more a
  viewer would see. It streams rows through the blur, so a 12-megapixel photo takes about
  70 MB. A reference with alpha is scored on a dark and on a light background, and the worse
  score counts, as libjxl 0.11.1's `ssimulacra2` tool does. See
  [Measuring quality](docs/quality.md) (#134).
- `ImageKodec.repackJpeg` writes the coefficients of a JPEG again with Huffman tables built for
  the image, as `jpegtran -optimize -copy all` does: the output decodes to the same pixels and
  is never larger, every segment outside the entropy coding stays, and a gain map after the
  image keeps its MPF offsets. `progressive = true` writes a progressive scan script. On
  libjpeg-turbo's own baseline files the output is within 0.1 percent of its optimal tables
  (#132).
- `ImageMetadata.gainMap` keeps the gain map of an Ultra HDR JPEG through a re-encode:
  `readMetadata` finds it through the Multi-Picture Format segment, and `encodeJpeg` writes it
  unchanged after the new main image, with an MPF segment and an XMP directory that point to
  it. `upright()` turns it with the pixels; a main image of another aspect ratio drops it with
  its `hdrgm` fields, so no reader looks for a gain map that is gone (#130).
- `ImageKodec.motionPhoto` finds the video of a Motion Photo, in Google's format, its older
  MicroVideo form or a Samsung trailer, and `ImageKodec.removeMotion` returns the still photo
  without it: the bytes up to the video, with the XMP that named it rewritten. A gain map and
  its bytes stay, and the photo decodes to the same pixels (#131).
- `ImageKodec.readMetadata` reads the EXIF block, the XMP packet and the ICC profile of a
  JPEG, PNG or WebP file, and new `encodeJpeg`, `encodePng` and `encodeWebp` overloads write
  an `ImageMetadata` back: APP1 and APP2 segments in JPEG, `eXIf`, `iTXt` and `iCCP` in PNG,
  and the extended format in WebP. `ImageMetadata.upright()` sets the EXIF orientation to 1
  for pixels already turned upright. A re-encoded photo keeps its date, place, camera and
  colours (#129).
- `ImageInfo.colorChannels` is public: 1 for gray, 3 for colour, 4 for CMYK or
  YCCK, and 0 where a header cannot say. For JPEG 2000 it follows the colour boxes,
  so a caller can learn whether an image decodes to gray or colour without
  decoding it (#119).
- The still decodes take a `cancellationCheck`, as `decodeAnimation` does:
  `decode`, `decodePage`, `decode16`, `decodeReduced`, `decodeScaled`,
  `decodeDownscaledTo`, `decodeJpegComponents`, `JpxDecoder.decode` and
  `Jbig2Decoder.decode`. It runs once a row of MCUs of a JPEG, every 64 rows and
  every DEFLATE block of a PNG, before each strip or tile of a TIFF, each tile and
  code block of a JPEG 2000 image, each segment and every 64 rows of a JBIG2 generic
  region, each row of superblocks of an AVIF and each group of a JPEG XL frame, and
  what it throws comes out of the call unchanged, through the catch of `JpxDecoder`
  and `Jbig2Decoder` too. The parameter is new on these JVM signatures, so code built
  against 0.3.0 that calls them must be rebuilt (#123).
- `ImageKodec.decodeJpxComponents` returns the components of a JPEG 2000 image as
  the codestream stores them, after the inverse wavelet, the component transform and
  the DC level shift and before the JP2 palette, channel definitions and colour space,
  as `JpxComponents`, with the file's first colour specification box as
  `JpxColorSpecification`. A PDF that names its own colour space for a `JPXDecode`
  image can read CMYK, indexed and spot colour samples with it. The decode and this
  share one reconstruction and one colour stage (#121).

### Changed

- `encodeHeic`'s `quality` now means what it means to libheif's `heif-enc -q` (x265): equal quality gives
  about equal SSIMULACRA 2 scores, as `encodeAvif`'s follows `avifenc -q`. The QP is 46 less half the quality,
  rounded up, and at least 1, where it was 51 less half the quality, so every lossy file is larger and better
  at the same quality: at quality 50, 0.646 bits a pixel at a mean score of 80.5 on the photo scenes, where
  it was 0.270 at 71.6 and heif-enc gives 0.687 at 80.6 (#176).

- A HEIF file with both an image sequence and a primary item shows what libavif shows: the primary
  item when the major brand is `avif`, the track when it is `avis`, and for any other brand the track.
  Before, the track was shown whatever the brand, and only an AV1 track of handler `pict` counted
  (#157).
- `ImageFormat` has a new value, `HEIC`, so an exhaustive `when` over `ImageFormat` in caller code
  needs a branch for it (#138).
- The build moves from Gradle to the Kotlin Toolchain 0.13 (`./kotlin`, with
  `project.yaml` and a `module.yaml` for each module). Kotlin stays at 2.4.21. The
  coordinates, the targets and the klib names do not change, so libraries built
  against 0.3.0 still link.
- The Wasm artifacts are `<artifact>-wasmjs` and `<artifact>-wasmwasi` instead of
  `<artifact>-wasm-js` and `<artifact>-wasm-wasi`. Gradle consumers find them through
  the module metadata. Only a direct dependency on the old artifact ID breaks.
- The root POM of each artifact depends on its `-jvm` artifact, so a plain Maven
  project can use the root artifact.
- The AAR keeps its classes in `libs/<artifact>-jvm.jar` instead of `classes.jar`,
  and it has no `aar-metadata.properties`, where Gradle wrote `minCompileSdk`.
- `imagekodec-compose` and `imagekodec-coil` depend at runtime on
  `org.jetbrains.compose.components:components-resources`. The Kotlin Toolchain adds
  it to every module with Compose turned on, and no setting removes it.
- The javadoc jars are empty. The API reference is the documentation site.
- The Android ABI dumps (`api/android/`) are gone. The JVM dumps (`api/jvm/`) and the
  klib dumps (`api/*.klib.api`) still guard the public API.
- The Android host tests now run the common test suite.
- The jars of the three libraries carry `META-INF/LICENSE` and `META-INF/NOTICE`, so
  an app that bundles them can pass on the notices that the ported code's licences
  ask for (#115).
- `scaled`, `downscaledTo` and the scaled decodes weight each source pixel by the
  part of it inside each output pixel, as an area average does. Below a 2:1 ratio
  a one-pixel line kept full or half contrast by where it fell, because every
  source pixel went whole to one output pixel. Output pixels change slightly for
  every ratio that is not a whole number (#122).
- JPEG 2000 tier-1 decoding no longer allocates a `Triple` or a `Pair` for almost
  every coefficient, which was about three quarters of a decode's allocation (#116).

### Fixed

- The Coil and Compose bindings decode a still at the size they draw it at through
  `decodeDownscaledTo`, where only a JPEG or JPEG 2000 did in Coil and nothing did in Compose, so a
  HEIC or AVIF grid, and a JPEG XR, reduce while they decode. A cancelled request or a composable
  that leaves composition stops a still at the decoder's next step, such as a HEIC tile. The Coil
  factory claims a HEIC or AVIF file whose `meta` box runs past its 64 KiB peek, by reading the
  top-level boxes to the end of that box. `KiteImage` decodes once it has been laid out, and again
  when its box grows. The KDoc says that HEIC needs `ImageKodecHeic.install()` (#170).
- `decodeAnimation` runs its cancellation check for a still: an AVIF or HEIC still, and a format
  that cannot animate, check as `decode` does (#170).
- A HEIF item whose coded picture is larger than its `ispe` in both directions shows the `ispe`
  size, from its top left. Sony's HEIC thumbnails code 1664 by 1088 samples under an `ispe` of 1616
  by 1080 with no conformance window, and showed 48 columns of padding (#150).
- A gray ICC profile whose PCS is Lab, such as `Gray-CIE_L.icc` of icc-profiles-free, converts to
  sRGB as lcms2 converts it: its curve gives L*, not luminance. Its mid gray came out up to 79
  levels too light (#110).
- A PNG whose inflated rows cannot fit in one array, such as 16384 by 16384 RGBA at
  16 bits, is refused before inflate, and `probe` says so. The inflater grew its
  buffer with an `Int` that wrapped to 0 past 1 GiB and then looped for ever (#127).
- A JBIG2 halftone region whose grid covers its region many times over is refused
  before its gray planes decode, and each pattern draws only the part inside the
  region. A 106-byte stream used to run for about an hour (#128).
- `decodeAnimation(maxFrames = n)` of an APNG stops reading at the fcTL after
  frame `n`, as its KDoc says, so a damaged later frame no longer fails the first
  one, and a paused thumbnail no longer reads and copies the whole file (#114).
- A gray JPEG 2000 image with an RGB ICC profile is left alone under
  `ColorTarget.Srgb()`, as the same image in a PNG is. The JP2 probe did not say
  whether the image decodes to gray or colour, so the profile was applied (#118).
- `probe` of an AVIF still reports the size of its AV1 frame, which is what `decode`
  gives, when the item's `ispe` says another size, and refuses an `ispe` past the
  pixel ceiling as it does a grid or an overlay (#124).
- `decodeAnimation(colorTarget = Srgb())` of a still PNG, AVIF or JPEG XL deeper than
  8 bits converts at the samples' own precision, as `decode` does, so the dark
  tones of a 16-bit PNG with a steep `gAMA`, `iCCP` or `cICP` are no longer crushed
  in `KiteImage` and the Coil decoder (#113).

## [0.3.0] - 2026-10-08

### Added

- JPEG XL decodes on every target, through `decode`, `decode16`, `decodeAnimation`
  and `probe`, from the bare codestream and from the container, whose codestream
  may be split over several boxes. The decoder is a pure-Kotlin port of the decoder
  of libjxl 0.11.1. It reads both coding modes: lossy VarDCT with every transform
  size, and Modular, lossless or lossy, with its palette, colour and squeeze
  transforms. It reads progressive passes, LF frames, patches, splines, noise, the
  Gaborish and edge-preserving filters, upsampling by 2, 4 and 8, samples of up to
  16 bits and float samples, alpha straight or premultiplied, extra channels and
  spot colours. A recompressed JPEG decodes to its pixels. Layers are blended with
  every blend mode, and an animation gives every frame with its duration and the
  loop count. XYB colour converts to the encoding the header names, with the sRGB,
  linear, gamma, BT.709, DCI, PQ or HLG curve. A lossless file reads exactly as
  libjxl's `djxl` reads it. A lossy file gives the 8-bit level nearest to djxl's
  sample, and a 16-bit file stays within 48 of 65535 of it. The orientation, the
  ICC profile and the code points of the colour encoding are reported by `probe`
  (#42).

- Colour conversion to sRGB (#108): every decode entry point takes
  `colorTarget = ColorTarget.Srgb(intent)` and converts through the file's embedded ICC
  profile, and `ImageKodec.convertToSrgb` converts a bitmap through a profile from
  elsewhere. Matrix/TRC profiles (v2 and v4, `curv` gammas and tables, all five `para`
  types), gray profiles and lookup-table profiles (`mft1`, `mft2` and `mAB`, Lab or XYZ
  connection space, CLUTs interpolated in lcms2's 16-bit fixed point) convert in each
  rendering intent as lcms2 converts them to its built-in sRGB, within one level at 8
  bits; `decode16` converts at full precision. A CMYK or YCCK JPEG and a CMYK TIFF convert
  from their ink through their CMYK profile, and `ImageKodec.convertCmykToSrgb` converts
  CMYK ink from elsewhere, such as a PDF's ICCBased image. A profile for another colour
  space than the image's is ignored, as browsers ignore it.

- PNG's `cICP`, `gAMA` and `cHRM` and an AVIF's `nclx` convert to sRGB too, in the
  PNG third edition's order (`cICP`, `iCCP`, `sRGB`, `gAMA` with `cHRM`) and AVIF's
  (profile, then code points), with `cICP`'s narrow range. HDR pictures, PQ and HLG,
  tone-map to SDR as libplacebo does: BT.2408's 203 cd/m² white, BT.2390's EETF from
  the content's peak, BT.2100's OOTF for HLG, and gamut mapping toward luminance.
  `ColorProfile` reports PNG's `cLLi` and `mDCv` and AVIF's `clli` and `mdcv` as
  `contentLight` and `masteringDisplay`. A file deeper than 8 bits converts at full
  precision before an 8-bit decode narrows it.

- CMYK TIFF (photometric 5, InkSet 1) decodes at 8 and 16 bits, chunky or in planes,
  with alpha. Without a profile it draws as libtiff's RGBA interface draws it, exactly;
  `decode16` keeps all 16 bits. Other ink sets are named refusals.

- Arithmetic-coded JPEG decodes: sequential (SOF9) and progressive (SOF10)
  frames, with restart intervals and the conditioning of the DAC marker, through
  T.81's QM decoder laid out as libjpeg's `jdarith.c` lays it out. The
  coefficients go through the same IDCT, reduced decode and upsampling as a
  Huffman file's, so a file `jpegtran -arithmetic` re-encodes decodes to exactly
  the pixels of its original, and `probe` calls it decodable (#19).

- Lossless JPEG decodes: Huffman (SOF3) and arithmetic (SOF11) frames at every
  precision from 2 to 16 bits, with all seven predictors, the point transform and
  restart intervals, as medical images carry them. `decode16` keeps every bit of a
  file deeper than 8, gray as one channel, and `decode` its high byte. The T.81
  reference software and ffmpeg's `ljpeg` encoder write the test files, and each
  decodes to its source (#19).

- 12-bit JPEG decodes, sequential and progressive, Huffman and arithmetic, and
  inside hierarchical images: libjpeg's `jpeg_idct_islow` at 12 bits, so a gray or
  untransformed RGB file gives exactly the samples libjpeg-turbo 3.1's
  `djpeg -dct int` gives. `decode16` keeps all 12 bits and `decode` the high 8. A
  reduced decode averages the full one, as for lossless files (#109).

- Hierarchical JPEG decodes: a DHP segment, then frames of any process (DCT
  sequential or progressive, lossless, Huffman or arithmetic) refined by
  differential frames, each reference doubled as its EXP segment asks. A lossless
  pyramid decodes to its source exactly, and `probe` reports the full size from
  the DHP segment rather than the first, smaller frame's (#19).

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
  and decoded samples do not change unless a `colorTarget` asks (#108). The JDK's sRGB
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

- `decode`, `decodePage`, `decode16`, `decodeReduced`, `decodeScaled`,
  `decodeDownscaledTo` and `decodeAnimation` gained a `colorTarget` parameter, before
  `decodeAnimation`'s `cancellationCheck`. Source calls compile as before; code compiled
  against an earlier version must be recompiled (#108).

- The Compose and Coil bindings convert colours to sRGB by default (#108). `KiteImage`
  and `KiteImageDecoder` decode with `ColorTarget.Srgb()`, so a Display P3 photo, a PNG
  with `gAMA` or an HDR picture draws as a browser draws it. A file that declares
  nothing, or sRGB, draws as before. `KiteImage` gained a `colorTarget` parameter before
  `onError`, and `KiteImageDecoder` and its `Factory` gained one after
  `maxCacheableAnimationBytes`; pass `ColorTarget.Source` to keep the samples as stored.
  Code compiled against an earlier version must be recompiled.

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
