# Working on ImageKodec

How to build, the test gate and the style rules: [CONTRIBUTING.md](CONTRIBUTING.md).
Open work: [GitHub Issues](https://github.com/yuroyami/ImageKodec/issues).
Format support and known limits: the Limits section of [README.md](README.md).

## Gotchas

Things that already cost someone time. One line each. Delete a line when it stops
being true.

- The OpenJPEG, libtiff, libwebp, libjpeg, jbig2enc, ffmpeg, libavif and libheif suites
  skip themselves when their binary is missing, and a skipped test reports as a pass,
  so read the skip count and not only the green run. CI sets
  `IMAGEKODEC_REQUIRE_ORACLES=1` to fail instead; set it locally to check the same (#14).
- Ubuntu's ImageMagick is version 6, which has no `magick` command, only
  `convert`. `Tools.find("magick")` falls back to it; before it did, the TIFF
  oracle suite skipped on every Ubuntu machine, CI included (#14).
- A format with no file in the `FuzzTest` corpus is not fuzzed at all. JPEG 2000
  had none, so a packet header cut off by the end of the data looped forever and
  no test noticed.
- On WebAssembly an index out of bounds or a huge `ByteArray` is a trap, not an
  exception, so a reader has to check its bounds and a size has to be checked
  before the allocation, or a hostile file stops the whole program (#22, #33).
- `FuzzTest` stops at a PNG chunk's checksum and cannot make two fields agree, so
  a fault that needs either has to be found with a test built by hand (#28, #29).
- `ImageKodec.decode` reaches `CcittFax` through TIFF compressions 2, 3 and 4, so
  the `FuzzTest` corpus fuzzes it through its fax TIFF seeds. It never reaches
  `Jbig2Decoder`, and the facade turns any exception from `JpxDecoder` into a
  decode error, so `FuzzTest` also drives both decoders through internal entry
  points that let a fault escape (#102).
- jbig2enc's symbol mode moves some glyphs by a pixel, with no pattern to derive.
  jbig2dec decodes the same moved page, so symbol-mode tests compare against
  jbig2dec, not against the source page (#40).
- `anim_dump` sets every fully transparent pixel to 0 before it writes a frame
  (`CleanupTransparentPixels` in libwebp's `examples/anim_util.c`), so its frames
  differ from any decoder's under alpha 0. Clean the decoded frame the same way
  before comparing (#11).
- cwebp writes neither several token partitions nor loop filter deltas, and
  libvpx turns its key-frame loop filter off at constant quality. ffmpeg's
  libvpx with `-slices` and a tight `-b:v` writes both (#11).
- libtiff before 4.7.2, Ubuntu 24.04's included, reads a clipped 4:4 YCbCr tile
  wrong in `tiff2rgba`, so that oracle case skips on older versions (#104).
- libtiff's fixed-point ReferenceBlackWhite tables truncate, so a studio-range
  YCbCr TIFF reads about a third of a level darker in `tiff2rgba` than our exact
  conversion, mean 0.5 and worst 3 on every value. ImageIO agrees with ours (#8).
- ImageMagick 6 writes a JPEG TIFF with RGB photometric unless told
  `-colorspace YCbCr`, and then only at 4:4:4; `tiffcp -c jpeg` writes 4:2:0.
  A `cjpeg` stream wrapped in a strip reaches the other subsamplings (#8).
- `jsNodeTest`, `wasmJsNodeTest` and `wasmWasiNodeTest` fail offline on a fresh
  clone, because `kotlin-js-store/` is ignored and yarn has no lockfile to work from.
- `opj_compress -POC` writes only the packets its first change reaches and leaves
  the rest of the tile out, so both decoders agree on a file that holds half the
  image. `JpxFeatureOracleTest` builds POC files by reordering the packets of an
  LRCP codestream instead, and holds OpenJPEG's reading of them to the original (#5).
- OpenJPEG appends a tile's POC entries to the main header's, where T.800 A.6.6
  has the tile's replace them, so a file with both cannot be checked against
  `opj_decompress` (#5).
- ffmpeg's `-aom-params` reaches only libaom's control options, so superres, which
  is part of its encoder configuration, cannot be asked for there. SVT-AV1 writes it
  (`superres-mode=1`), but declines it on some pictures, and libaom turns on intra
  block copy only for screen content of 256 by 128 or more. `Av1OracleTest` reads each
  stream's headers and fails when the tool it is there for is off (#41).
- avifdec converts through libyuv's fixed point whenever libyuv has the matrix, and has
  no switch to stop it, so it differs from an exact conversion by two or three levels.
  `tools/avif_rgb.c` sets libavif's `avoidLibYUV`, and `AvifOracleTest` builds it (#41).
- libavif rounds the colour of a premultiplied 4:4:4 image to 8 bits before it divides
  by alpha, so a nearly transparent pixel comes out far from an exact reading. Compare
  premultiplied colour multiplied by its alpha (#41).
- avifdec leaves the clean aperture, rotation and mirror to its caller; libheif's
  heif-convert applies all three, so geometry is checked against it (#41).
- ffmpeg scales every frame it decodes to the first frame's size when a stream's size
  changes, so a reference for an AV1 sequence that resizes needs `-autoscale 0` (#41).
- avifenc encodes a sequence without lookahead, so its frames never use compound
  prediction or its masks. ffmpeg's `-f avif` muxer around libaom with the inter tools
  turned on writes an AVIF that does (#41).
- ffmpeg's AVIF muxer writes a repeating edit list over a track duration near 2^63,
  which libavif reads as looping for ever, since the repetition count passes
  `Int.MAX_VALUE`. A count of the plays that does not cap it reads a huge number (#41).
- A segmentation map can be kept or predicted from an earlier frame only by an encoder
  that segments: libaom with `aq-mode=1` keeps it, and realtime libaom with `aq-mode=3`
  predicts it. Neither is on by default (#41).
