# Working on ImageKodec

How to build, the test gate and the style rules: [CONTRIBUTING.md](CONTRIBUTING.md).
Open work: [GitHub Issues](https://github.com/yuroyami/ImageKodec/issues).
Format support and known limits: the Limits section of [README.md](README.md).

## Gotchas

Things that already cost someone time. One line each. Delete a line when it stops
being true.

- The OpenJPEG, libtiff, libwebp, libjpeg, jbig2enc, ffmpeg, libavif, libheif and libjxl suites
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
- A Kotlin/Wasm test program that Node runs exits with 0 when a test fails, and reports
  the failure as a TeamCity `testFailed` line. CI runs the test programs without Gradle,
  so `tools/run-shards.sh` reads their output for that line.
- The wasm compiler fails with "Key ic#... is missing in the map" when a test starts to
  use a standard library function that no test used before, because its incremental
  cache is stale. Run the compile task again with `--rerun`. A fresh clone is not affected.
- GitHub keeps 10 GB of caches for a repository and drops the oldest past that. A cache
  key with the commit in it adds an entry on every push, so the Kotlin/Native caches of
  CI are keyed on `gradle/libs.versions.toml` alone.
- Two release framework links at once do not fit in the 7 GB of a macOS runner and take
  three times as long, so CI links them with `--max-workers=1`.
- Kotlin/JS does 64-bit arithmetic in software, and the AV1 inverse transform uses it for
  every sample, so the slowest `FuzzTest` mutant (`avif-oriented byte@348`) takes over a
  minute on JS. More than two JS test processes on one runner push it past the deadline.
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
- `jpegtran -arithmetic` and `cjpeg -arithmetic` always write the default DAC
  conditioning (L 0, U 1, K 5), so a decoder that ignores DAC passes every file they
  write. `tools/arith_jpeg.c` sets other bounds through libjpeg's `arith_dc_L`,
  `arith_dc_U` and `arith_ac_K` (#19).
- thorfdbg/libjpeg after release 1.70 cannot write lossless JPEG ("DQT marker missing"),
  and 1.70 seeds the prediction of a file with a point transform at 2^(P-1) where T.81
  says 2^(P-Pt-1), in its encoder and decoder alike. ffmpeg and libjpeg-turbo follow
  T.81, so such a file round-trips through it and decodes to something else elsewhere.
  The test writer `losslessJpeg` makes conformant ones (#19).
- Ubuntu's libjpeg-turbo is 2.1, which reads and writes 8-bit JPEG only. 12-bit files
  need 3.x (`cjpeg -precision 12`), built from source as `cjpeg3` and `djpeg3` (#109).
- thorfdbg/libjpeg keeps a hierarchical image's samples in fixed point from frame to frame,
  where T.81 hands on integer samples, so its decode of its own pyramid differs from
  T.81's by a level or so, and its "lossless" pyramids do not decode to their source by
  either reading. It cannot read back its own lossless pyramids at 10 or 16 bits, and
  writes Huffman ones only with `-h`. Hold a hierarchical decode to it within bounds, and
  to the test writer `hierarchicalLosslessJpeg` exactly (#19).
- ffmpeg 6 misreads the lossless difference -32768 (category 16, no extra bits) and
  reads a three-component 4:4:4 lossless file as RGB; its `ljpeg` encoder writes BGR
  through a private 9-bit transform no other decoder knows (#19).
- lcms2 interpolates every CLUT an ICC profile holds in 16-bit fixed point, even inside a
  floating-point transform (`EvaluateCLUTfloatIn16`), and its `LinearInterp` works in
  unsigned 32 bits, which a two-entry table overflows in signed arithmetic. A port in
  doubles lands a 16-bit step off now and then (#108).
- lcms2 leaves out a black point compensation or absolute colorimetric layer whose matrix
  and offset sum to within 0.002 of nothing (`IsEmptyLayer`), so a profile whose black is
  almost zero converts as if it had none (#108).
- lcms2 writes a `lut16Type` whose header gives the first curve's length and then each curve
  at its own, and a gamma of 1 is two entries long, so curves of different kinds make a
  broken file. `tools/icc_lcms.c` tabulates every curve at one length (#108).
- lcms2 rounds a profile's colorants to s15Fixed16 when it saves one, which moves a dark
  channel beside a bright one by a dozen 16-bit levels, and saves `cmsBuildGamma` as a
  `curv` of one u8Fixed8 exponent, 2.19998 becoming 2.19922. Hold an exact conversion to
  an unsaved profile with parametric curves (`icc_lcms convert-rgb`) (#108).
- zimg (`zscale`) applies HLG's OOTF to each channel alone, where BT.2100 weighs a pixel by
  its luminance, so the two agree on grays only (#108).
- djxl dithers every 8-bit picture it writes, so a lossy file, or a frame blended by
  its alpha, sits one level away in about a sixth of its samples. Ask it for
  `--bits_per_sample=16` and a PNG; it ignores that option for PNM and PAM output (#42).
- djxl gives a premultiplied file's colour as stored, with no option to divide it, and
  `cjxl --premultiply=1` marks the alpha without multiplying the colour. Compare the
  colour multiplied by its alpha, and only where it does not pass the alpha (#42).
- djxl converts a lossy file that has only an ICC profile to that profile. This decoder
  gives sRGB there, so the reference needs `--color_space=RGB_D65_SRG_Rel_SRG` (#42).
- Ubuntu 24.04 has libjxl 0.7.0: no `jxl_from_tree`, `--photon_noise` where 0.11 has
  `--photon_noise_iso`, other rounding, and a PNG writer that refuses a 16-bit picture
  with alpha. `JxlOracleTest` gives it the plain cases at a bound of two levels, so the
  tight bounds run only where libjxl is 0.11 or newer, which CI is not (#42).
- cjxl 0.7.0 reads a PNM file wrong when its samples are not of 8 or 16 bits, so the
  oracle cases for other depths run only where libjxl is 0.11 or newer (#42).
- cjxl keeps neither a GIF's nor an APNG's play count, and `jxl_from_tree` writes none,
  so `JxlDecoderTest` rewrites a header to test a loop count (#42).
- `jxl_from_tree` gives `Alpha` the size set before it, and every frame of an
  animation with `Alpha` has to have the first frame's size. With `Upsample`, set the
  full size, then `Alpha`, then the stored size (#42).
- A JPEG decoder clips Y, Cb and Cr after the inverse transform, and libjxl clips only
  the RGB they give. A recompressed JPEG therefore reads up to 94 levels away from the
  JPEG decoder's picture at a hard edge, and 1 on average, in djxl as well (#42).
- The C2 compiler of JDK 11 on x64 vectorises a loop that reverses the bits of each byte of
  an array in place, and the vector code gives wrong bytes. arm64 and JDK 21 are not
  affected, so the fault shows only on the Java 11 job of CI. Reverse bits through a table,
  as `TiffDecoder` does.
