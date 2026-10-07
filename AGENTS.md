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
- `jsNodeTest`, `wasmJsNodeTest` and `wasmWasiNodeTest` fail offline on a fresh
  clone, because `kotlin-js-store/` is ignored and yarn has no lockfile to work from.
