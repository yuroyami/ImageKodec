# Contributing to ImageKodec

## Building

The build uses the Kotlin Toolchain: the `./kotlin` script at the repository root.
It downloads the toolchain, the JDKs, Kotlin/Native and Node on its first run.
`project.yaml` lists the modules, and each module has a `module.yaml`.

```sh
./kotlin test -p jvm -m imagekodec
```

That command is the quickest check while you work. Before you open a pull
request, run the JVM tests of all three modules and the public API check:

```sh
./kotlin test -p jvm
./kotlin check abi
```

`./kotlin build` builds every module for every platform. On Linux and Windows, give
it a `-p` list without the Apple platforms. Android needs the Android SDK in
`ANDROID_HOME`.

The build uses JDK 21; the three JVM library variants target Java 11 and restrict
JDK API use to that release. CI also runs their suites on Java 11. The toolchain
runs tests on JDK 17 or later only, so `tools/java11_tests.py` runs the compiled
tests with JUnit on the JDK you name:

```sh
./kotlin build -p jvm
tools/java11_tests.py --java /path/to/jdk-11/bin/java
```

`./kotlin test -p android` runs the common suite as Android host tests.

### JS, Wasm and native tests

The toolchain does not run the JS and wasmWasi tests, and its wasmJs runner stops a
test after 30 seconds, which the fuzz suite passes. Link the test program with
`./kotlin task` and run it with Node instead. CI does the same.

```sh
./kotlin task :imagekodec:linkWasmWasiTestDebug
node --input-type=module --eval \
  'const t = await import((await import("node:url")).pathToFileURL(process.argv[1])); t.startUnitTests();' \
  "$PWD/build/artifacts/CompiledWebArtifact/imagekodecwasmWasiTestdebug/kotlin-output/imagekodec_test.mjs"
```

- wasmJs: link `:imagekodec:linkWasmJsTestDebug`, and run
  `imagekodecwasmJsTestdebug/kotlin-output/imagekodec_test.mjs` in the same way.
- JS: link `:imagekodec:linkJsTest`. Then run mocha in
  `build/artifacts/CompiledWebArtifact/imagekodecjsTestrelease/kotlin-output`:
  `mocha imagekodec_test.mjs --timeout 600s`.
- Native: `./kotlin test -p linuxX64` runs the suite. On a Mac, use `-p macosArm64`
  and `-p iosSimulatorArm64`. Add `-v release` to test the optimised build, which
  runs the fuzz suite about eight times faster.

### How CI runs the same suites faster

The fuzz suite (`FuzzTest`) is most of the test time on every target, and one process
runs it on one core. CI compiles each test program once and then runs it as several
processes at once. Each process takes its own share of the mutants, and the shares
together run every mutant once. You can do the same on a native target:

```sh
./kotlin task :imagekodec:linkMacosArm64TestRelease
tools/run-shards.sh 4 build/tasks/_imagekodec_linkMacosArm64TestRelease/imagekodec_test.kexe
```

- The `Release` link task links the native test program optimised. The link takes
  about a minute longer and the fuzz suite runs about eight times faster.
- `tools/run-shards.sh 4 <command>` runs the command four times at once and sets
  `IMAGEKODEC_FUZZ_SHARD` to `0/4`, `1/4`, `2/4` and `3/4`. Without that variable a
  run takes every mutant, so `./kotlin test` still runs the whole suite.
- `tools/run-shards.sh 2-3/8 <command>` runs shards 2 and 3 of 8 here, for a suite that
  several machines split.

### Apple frameworks

The toolchain compiles the libraries to klibs and links no Apple framework.
`tools/apple_frameworks.py` links them from those klibs with the toolchain's own
Kotlin/Native compiler. It writes each framework to
`<module>/build/bin/<platform>/<debug|release>Framework/`.

```sh
./kotlin build -p iosSimulatorArm64
tools/apple_frameworks.py --build-type debug --no-xcframework \
  --platforms iosSimulatorArm64 --frameworks ImageKodec
```

`tools/ThrowsContract.swift` checks the exported API from a Swift caller:
checked API operations, including exact downscaling, must return recoverable
`NSError`s.
Link the debug `ImageKodec` framework for `iosSimulatorArm64` as shown above,
compile the source against that framework with the simulator SDK, and run it in a
booted simulator.
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
| Colour conversion to sRGB | lcms2 through `tools/icc_lcms.c`, which builds profiles of every kind (matrix/TRC, gray, and `lut8`, `lut16` and `lutAToB` tables for RGB and CMYK) and converts 8- and 16-bit samples to its built-in sRGB in floating point: within one level at 8 bits, in every intent, and the real profiles of `icc-profiles-free` too. CMYK JPEGs convert from ImageMagick's reading of their ink, within two levels for the two decoders' rounding. PNG's `gAMA`, `cHRM` and SDR `cICP` match lcms2 converting through an unsaved profile of the same primaries and curve; PQ and HLG match zimg (`zscale`) for their light and BT.2100 and BT.2390, restated in `PngColorChunksTest`, for the tone mapping |
| JPEG arithmetic decode | libjpeg-turbo's `jpegtran -arithmetic`, and `tools/arith_jpeg.c` for the DAC conditioning, re-encode a `cjpeg` file's coefficients: the arithmetic file must decode to exactly the pixels of the Huffman one |
| JBIG2 | jbig2enc (`jbig2`) writes the streams: generic regions must decode to the source page, symbol mode must match jbig2dec |
| WebP lossless encode | `dwebp` reads our output back pixel for pixel, and it must be smaller than our PNG |
| TIFF encode | libtiff (`tiffinfo`, `tiff2rgba`) and ImageMagick read our output back, every page, at 8 and 16 bits |
| APNG encode | Pillow (`python3` with `PIL`) and ffmpeg's APNG decoder read our output back, frames, delays and loop count |
| AV1 | dav1d through ffmpeg, sample for sample, on stills and sequences from libaom, SVT-AV1 and rav1e |
| AVIF | libavif's float conversion through `tools/avif_rgb.c`, and libheif's `heif-convert` for crops, rotations and mirrors |
| JPEG XL | libjxl (`cjxl` writes the files and `djxl` reads them), with libjpeg-turbo's `cjpeg` for the JPEGs that cjxl recompresses. The bounds are for libjxl 0.11 or newer; an older one gets the plain cases with looser bounds |

The suites that need a binary `assumeTrue`-skip when it is missing, and a
skipped test reports as a pass. If you touch those codecs, install the tools and
check the skip count, not only whether the run passed:

```sh
brew install webp libtiff imagemagick openjpeg jpeg-turbo jbig2enc jbig2dec ffmpeg libavif libheif pillow little-cms2 jpeg-xl     # macOS
sudo apt-get install webp libtiff-tools imagemagick libopenjp2-tools libjpeg-turbo-progs jbig2 jbig2dec ffmpeg libavif-bin libavif-dev libheif-examples python3-pil libjpeg-turbo8-dev liblcms2-dev icc-profiles-free libjxl-tools   # Debian/Ubuntu
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

Explicit API mode is strict (`explicitApi: strict` in
`library.module-template.yaml`), so every public declaration needs an explicit visibility
and return type. Changing the public API changes the committed dumps:

```sh
./kotlin do updateAbi
./kotlin build
./kotlin do updateKlibAbi
```

Commit the resulting `api/` diff with your change. `./kotlin check abi` compares
the JVM API with `api/jvm/<module>.api`. `./kotlin check klibAbi` compares the
native and web API with `api/<module>.klib.api`, and it reads the klibs of the last
`./kotlin build`, so build every platform first. Each check fails when the two
disagree, so an accidental signature change never reaches a release. Nothing
checks the Android API, which only the Compose and Coil modules extend.

## Licensing

ImageKodec is Apache-2.0 and uses permissively licensed references only. If you
work from a new reference, add it to
[reference/REFERENCES.md](reference/REFERENCES.md) with its license, and check
that the license permits the use. GPL and LGPL sources are not usable here.
