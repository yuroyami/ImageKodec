# Contributing to ImageKodec

## Building

```sh
./gradlew :imagekodec:jvmTest
```

That command is the quickest check while you work. Before you open a pull
request, run what CI runs:

```sh
./gradlew :imagekodec:jvmTest :imagekodec:jsNodeTest :imagekodec:wasmJsNodeTest \
          :imagekodec:wasmWasiNodeTest :imagekodec:linuxX64Test \
          :imagekodec-compose:jvmTest :imagekodec-coil:jvmTest checkLegacyAbi
```

The build uses JDK 21; the three JVM library variants target Java 11 and restrict
JDK API use to that release. CI also runs their suites on a Java 11 worker:

```sh
./gradlew :imagekodec:jvmJava11Test :imagekodec-compose:jvmJava11Test \
          :imagekodec-coil:jvmJava11Test
```

Install JDK 11 alongside JDK 21 for this check. Gradle still runs on JDK 21 and
selects JDK 11 only for these test workers.

On a Mac, add the Apple targets:

```sh
./gradlew :imagekodec:macosArm64Test :imagekodec:iosSimulatorArm64Test
```

`tools/ThrowsContract.swift` checks the exported API from a Swift caller:
checked API operations, including exact downscaling, must return recoverable
`NSError`s.
Build `:imagekodec:linkDebugFrameworkIosSimulatorArm64`, compile the source
against that framework with the simulator SDK, and run it in a booted simulator.
This complements the JVM reflection test, which checks declarations alone.

## The one rule that matters

**A decoder must never trust its input.** An attacker controls every field in
every header. Malformed input has exactly one legal outcome: an
`ImageDecodeException` that names the problem.

These four are bugs, not edge cases:

- an index fault
- a negative array size
- an unbounded loop
- an allocation sized from an unchecked field

`FuzzTest` exists to find them. If you add a decoder, or a new branch in an
existing one, add it to that harness's corpus.

## Prove correctness against a reference, do not assert it

Every codec's tests compare its output against an independent implementation:

| Codec | Reference used as the oracle |
|---|---|
| PNG, BMP, GIF, JPEG encode | ImageIO reads our output back |
| JPEG decode | `stb_image`, bit-identical, through committed vectors that `tools/stb_dump.c` produced. Its header gives the commands, including the one-line correction of stb's 4:2:2 right-edge weight (#69) |
| JPEG 2000 | OpenJPEG (`opj_compress` and `opj_decompress`) |
| WebP lossless | libwebp (`cwebp` and `dwebp`), bit-identical |
| TIFF | libtiff (`tiffcp`, `tiff2rgba`) and ImageMagick (`magick`, or `convert` on ImageMagick 6) |
| JPEG reduced decode | libjpeg-turbo (`djpeg -scale`) |
| JPEG lossless decode | The T.81 reference software (`t81jpeg`, below) writes every precision from 2 to 16 bits, Huffman and arithmetic, and ffmpeg's `ljpeg` encoder writes subsampled YCbCr: each must decode to its source. ffmpeg's decoder reads the files with a point transform, and the files a small Annex H writer in the tests makes |
| JPEG hierarchical decode | `t81jpeg` writes pyramids of every kind, which must match its own decode within the bounds `JpegHierarchicalOracleTest` explains, and lossless pyramids written to the letter of T.81 Annex J by the tests must decode to their source |
| JPEG 12-bit decode | libjpeg-turbo 3.1 (`cjpeg3 -precision 12` and `djpeg3 -dct int`, below): equal samples where no colour conversion or upsampling stands between, within 2 levels where they do |
| Colour conversion to sRGB | lcms2 through `tools/icc_lcms.c`, which builds profiles of every kind (matrix/TRC, gray, and `lut8`, `lut16` and `lutAToB` tables for RGB and CMYK) and converts 8- and 16-bit samples to its built-in sRGB in floating point: within one level at 8 bits, in every intent, and the real profiles of `icc-profiles-free` too. CMYK JPEGs convert from ImageMagick's reading of their ink, within two levels for the two decoders' rounding |
| JPEG arithmetic decode | libjpeg-turbo's `jpegtran -arithmetic`, and `tools/arith_jpeg.c` for the DAC conditioning, re-encode a `cjpeg` file's coefficients: the arithmetic file must decode to exactly the pixels of the Huffman one |
| JBIG2 | jbig2enc (`jbig2`) writes the streams: generic regions must decode to the source page, symbol mode must match jbig2dec |
| WebP lossless encode | `dwebp` reads our output back pixel for pixel, and it must be smaller than our PNG |
| TIFF encode | libtiff (`tiffinfo`, `tiff2rgba`) and ImageMagick read our output back, every page, at 8 and 16 bits |
| APNG encode | Pillow (`python3` with `PIL`) and ffmpeg's APNG decoder read our output back, frames, delays and loop count |
| AV1 | dav1d through ffmpeg, sample for sample, on stills and sequences from libaom, SVT-AV1 and rav1e |
| AVIF | libavif's float conversion through `tools/avif_rgb.c`, and libheif's `heif-convert` for crops, rotations and mirrors |

The suites that need a binary `assumeTrue`-skip when it is missing, and a
skipped test reports as a pass. If you touch those codecs, install the tools and
check the skip count, not only whether the run passed:

```sh
brew install webp libtiff imagemagick openjpeg jpeg-turbo jbig2enc jbig2dec ffmpeg libavif libheif pillow little-cms2     # macOS
sudo apt-get install webp libtiff-tools imagemagick libopenjp2-tools libjpeg-turbo-progs jbig2 jbig2dec ffmpeg libavif-bin libavif-dev libheif-examples python3-pil libjpeg-turbo8-dev liblcms2-dev icc-profiles-free   # Debian/Ubuntu
```

The lossless and hierarchical JPEG suites also need `t81jpeg`, the `jpeg` tool of the T.81 reference software, built
at release 1.70 with the repository's patch (later commits cannot write lossless files):

```sh
git clone https://github.com/thorfdbg/libjpeg.git t81 && cd t81 && git checkout c719010
git apply ../ImageKodec/tools/t81-libjpeg.patch && ./configure && make
sudo install -m 755 jpeg /usr/local/bin/t81jpeg
```

The 12-bit JPEG suite needs libjpeg-turbo 3.1, which distributions do not package yet,
installed as `cjpeg3` and `djpeg3`:

```sh
git clone --depth 1 --branch 3.1.0 https://github.com/libjpeg-turbo/libjpeg-turbo.git ljt3
cmake -S ljt3 -B ljt3/build -DENABLE_SHARED=0 -DWITH_SIMD=0 && make -C ljt3/build cjpeg-static djpeg-static
sudo install -m 755 ljt3/build/cjpeg-static /usr/local/bin/cjpeg3
sudo install -m 755 ljt3/build/djpeg-static /usr/local/bin/djpeg3
```

CI sets `IMAGEKODEC_REQUIRE_ORACLES=1`, which makes a missing tool fail the test
instead of skipping it. Set it yourself to check that every oracle suite ran.

New format work needs a vector in `commonTest`, so that every target runs it. It
also needs an oracle test in `jvmTest` wherever a reference tool exists.

## Style

Match the file you are editing. Beyond that:

- **Comments explain why, never what.** The code already says what it does. Write
  a comment only to record what the code cannot say: a specification quirk, a
  compatibility rule, or the reason the obvious approach is wrong.
- **Name your source.** A constant or a formula often comes from a specification
  or another implementation. Name that source, so the next reader can check it.
- **Prefer deriving over transcribing.** Nobody can mistype a generated table.
  Someone can mistype a copied table, and the tests may still pass.
- **The core module depends on `kotlin-stdlib` and nothing else.** Anything that
  needs Compose, Coil or a platform API belongs in one of the binding modules.
- Use integer arithmetic in codecs, so the output is identical on every target.

## Public API

`explicitApi()` is on, so every public declaration needs an explicit visibility
and return type. Changing the public API changes the committed dumps:

```sh
./gradlew updateLegacyAbi
```

Commit the resulting `api/*.api` diff with your change. `checkLegacyAbi` fails
the build when the two disagree, so an accidental signature change never reaches
a release.

## Licensing

ImageKodec is Apache-2.0 and uses permissively licensed references only. If you
work from a new reference, add it to
[reference/REFERENCES.md](reference/REFERENCES.md) with its license, and check
that the license permits the use. GPL and LGPL sources are not usable here.
