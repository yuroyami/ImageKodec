# Working on ImageKodec

How to build, the test gate and the style rules: [CONTRIBUTING.md](CONTRIBUTING.md).
Open work: [GitHub Issues](https://github.com/yuroyami/ImageKodec/issues).
Format support and known limits: the Limits section of [README.md](README.md).

## Gotchas

Things that already cost someone time. One line each. Delete a line when it stops
being true.

- The OpenJPEG, libtiff, libwebp, libjpeg and jbig2enc suites skip themselves when their
  binary is missing, and a skipped test reports as a pass, so read the skip count
  and not only the green run. CI sets `IMAGEKODEC_REQUIRE_ORACLES=1` to fail
  instead; set it locally to check the same (#14).
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
