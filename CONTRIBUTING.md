# Contributing to KiteImage

## Building

```sh
./gradlew :kiteimage:jvmTest
```

That is the fast loop. Before opening a pull request, run what CI runs:

```sh
./gradlew :kiteimage:jvmTest :kiteimage:jsNodeTest :kiteimage:wasmJsNodeTest :kiteimage-compose:jvmTest :kiteimage-coil:jvmTest checkLegacyAbi
```

On a Mac, add the Apple targets:

```sh
./gradlew :kiteimage:macosArm64Test :kiteimage:iosSimulatorArm64Test
```

## The one rule that matters

**A decoder must never trust its input.** Every field in every header is
attacker-controlled. Malformed input has exactly one legal outcome: an
`ImageDecodeException` that names the problem. An index fault, a negative array
size, an unbounded loop or an allocation sized from an unchecked field is a bug,
not an edge case, and `FuzzTest` exists to find them. If you add a decoder or a
new branch in an existing one, add it to that harness's corpus.

## Correctness is proved against a reference, not asserted

Every codec here is a port, and every port is checked against the thing it was
ported from:

| Codec | Oracle |
|---|---|
| PNG, BMP, GIF, JPEG encode | ImageIO, and real `stb_image` when the harness is built |
| JPEG decode | `stb_image`, bit-identical |
| JPEG 2000 | OpenJPEG (`opj_compress` / `opj_decompress`) |
| WebP lossless | libwebp (`cwebp` / `dwebp`), bit-identical |
| TIFF | libtiff (`tiffcp`) read back through ImageIO |

The oracle suites skip themselves when the tools are missing, so install them
locally if you are touching those codecs:

```sh
brew install webp libtiff imagemagick openjpeg     # macOS
sudo apt-get install webp libtiff-tools imagemagick libopenjp2-tools   # Debian/Ubuntu
```

New format work needs a vector in `commonTest` (so every target runs it) *and*,
where a reference tool exists, an oracle test in `jvmTest`.

## Style

Match the file you are editing. Beyond that:

- **Comments explain why, never what.** The code already says what it does. A
  comment earns its place by recording the thing that is not in the code: a spec
  quirk, a compatibility rule, why the obvious approach is wrong.
- **Name the reference.** When a constant or a piece of arithmetic comes from a
  spec or a reference implementation, say which, so the next reader can check it.
- **Prefer deriving over transcribing.** A generated table cannot be
  mis-transcribed; a copied one can, and the failure is silent.
- **The core module depends on `kotlin-stdlib` and nothing else.** Anything that
  needs Compose, Coil or a platform API belongs in one of the binding modules.
- Integer arithmetic in codecs, so output is identical on every target.

## Public API

`explicitApi()` is on, so every public declaration needs an explicit visibility
and return type. Changing the public API changes the committed dumps:

```sh
./gradlew updateLegacyAbi
```

Commit the resulting `api/*.api` diff with your change. `checkLegacyAbi` fails
the build when the two disagree, which is what keeps an accidental signature
change from reaching a release.

## Licensing

KiteImage is Apache-2.0 and ports from permissively-licensed references only.
If you port from a new reference, add it to
[reference/REFERENCES.md](reference/REFERENCES.md) with its licence, and check
the licence permits it. GPL and LGPL sources are not usable here.
