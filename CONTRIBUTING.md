# Contributing to KiteImage

## Building

```sh
./gradlew :kiteimage:jvmTest
```

That command is the quickest check while you work. Before you open a pull
request, run what CI runs:

```sh
./gradlew :kiteimage:jvmTest :kiteimage:jsNodeTest :kiteimage:wasmJsNodeTest \
          :kiteimage:wasmWasiNodeTest :kiteimage:linuxX64Test \
          :kiteimage-compose:jvmTest :kiteimage-coil:jvmTest checkLegacyAbi
```

On a Mac, add the Apple targets:

```sh
./gradlew :kiteimage:macosArm64Test :kiteimage:iosSimulatorArm64Test
```

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
| JPEG decode | `stb_image`, bit-identical, through committed vectors that a clang-compiled `stb_image` produced |
| JPEG 2000 | OpenJPEG (`opj_compress` and `opj_decompress`) |
| WebP lossless | libwebp (`cwebp` and `dwebp`), bit-identical |
| TIFF | libtiff (`tiffcp`) and ImageMagick (`magick`) |

The last three suites `assumeTrue`-skip when their binary is missing, and a
skipped test reports as a pass. If you touch those codecs, install the tools and
check the skip count, not only whether the run passed:

```sh
brew install webp libtiff imagemagick openjpeg     # macOS
sudo apt-get install webp libtiff-tools imagemagick libopenjp2-tools   # Debian/Ubuntu
```

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

KiteImage is Apache-2.0 and uses permissively licensed references only. If you
work from a new reference, add it to
[reference/REFERENCES.md](reference/REFERENCES.md) with its license, and check
that the license permits the use. GPL and LGPL sources are not usable here.
