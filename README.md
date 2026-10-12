# ImageKodec

Image codecs written in Kotlin for Kotlin Multiplatform: decode PNG, JPEG, GIF,
BMP, TIFF, JPEG 2000, WebP, AVIF and JPEG XL from a `ByteArray`, with the same code
on every target.

<sub>AI tools wrote much of the code, tests and documentation in this project, under the
direction and review of its author.</sub>

[![Maven Central](https://img.shields.io/maven-central/v/io.github.yuroyami/imagekodec)](https://central.sonatype.com/artifact/io.github.yuroyami/imagekodec)
[![CI](https://img.shields.io/github/actions/workflow/status/yuroyami/ImageKodec/ci.yml?branch=main&label=CI)](https://github.com/yuroyami/ImageKodec/actions/workflows/ci.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.21-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

**[Documentation](https://yuroyami.github.io/ImageKodec/)**: guides with examples, plus the
generated API reference.

Compose Multiplatform draws an image on every target, but getting the pixels still goes through
the platform: `BitmapFactory` on Android, CoreGraphics on iOS, Skia on desktop and the browser on
web. Common code that needs real pixels (a thumbnail hash, a server-side resize, the images inside
a PDF) therefore has to call per-platform code.

ImageKodec decodes in common Kotlin instead. Every format comes back as non-premultiplied
ARGB_8888 in a plain `IntArray`, which means the red, green and blue channels hold the original
color instead of a color already multiplied by the alpha value. Palettes, grayscale, BGR ordering
and chroma subsampling (color stored at a lower resolution than brightness) are resolved before
you see a pixel.

The core artifact depends on kotlin-stdlib and nothing else. Compose and Coil bindings come as
two optional modules.

## Install

```kotlin
commonMain.dependencies {
    implementation("io.github.yuroyami:imagekodec:0.3.0")
}
```

A project on the Kotlin Toolchain lists the same coordinates in its `module.yaml`:

```yaml
dependencies:
  - io.github.yuroyami:imagekodec:0.3.0
```

The optional modules are `imagekodec-compose` and `imagekodec-coil`, at the same version.

> [!IMPORTANT]
> The two optional modules build for seven targets only: Android, `jvm`, `iosArm64`,
> `iosSimulatorArm64`, `macosArm64`, `js` and `wasmJs`. A project that also targets `iosX64`,
> Linux, Windows, tvOS, watchOS, androidNative or wasmWasi resolves the core and then fails to
> resolve them. Keep them out of any source set that includes those targets.

The optional modules depend on Compose Multiplatform `1.13.0-alpha02`, so dependency resolution
can raise the Compose version of your app to that release. On Android they need `compileSdk` 37.1
or newer.

Versions before 0.3.0 use other coordinates and another Kotlin package: 0.2.0 is
`io.github.yuroyami:kiteimagecodec` with the package `io.github.yuroyami.kiteimagecodec`, and
0.1.0 is `io.github.yuroyami:kiteimage`.

## Usage

### Read the header first

`probe` reads the header and allocates no pixel buffer, so it stays cheap on a 50-megapixel file.
Use it to size a layout, or to refuse an upload by its dimensions before it costs any memory.

```kotlin
import io.github.yuroyami.imagekodec.ImageKodec

val info = ImageKodec.probe(bytes)
println("${info.width}x${info.height}, ${info.frameCount} frame(s)")
if (!info.isDecodable) println(info.unsupportedReason)
```

### Decode an image

```kotlin
val bitmap = ImageKodec.decode(bytes)                              // format found from the magic bytes
val upright = ImageKodec.decode(bytes, applyOrientation = true)   // apply the EXIF orientation
val pixel = bitmap[10, 20]                                         // 0xAARRGGBB
```

Malformed input throws `ImageDecodeException`, and a feature that this build lacks throws its
subtype `UnsupportedImageException`. From Swift, both arrive as an `NSError`. `decode16` keeps
every bit of a sample deeper than 8 bits, and `decodePage` reads the other pages of a TIFF. See
[Reading images](docs/decoding.md).

### Play an animation

GIF, APNG, animated WebP, animated AVIF and animated JPEG XL all come back as one type. Every frame
is already composited, so playback is "draw frame N, wait delay N".

```kotlin
val anim = ImageKodec.decodeAnimation(bytes)
for (frame in anim.frames) draw(frame.bitmap, frame.delayMillis)
```

See [Animation](docs/animation.md) for play counts, frame delays and decoding only the first frame.

### Show colors as a browser does

A decode returns the samples as the file stores them, so a Display P3 photo draws dull on an sRGB
screen. Ask for sRGB and the decode converts through the embedded ICC profile of the file, as
lcms2 would, with HDR tone-mapped to SDR:

```kotlin
val bitmap = ImageKodec.decode(bytes, colorTarget = ColorTarget.Srgb())
```

The Compose and Coil bindings convert to sRGB by default. See [Color management](docs/color.md).

### Make a thumbnail

`decodeScaled` decodes a JPEG or JPEG 2000 at a reduced size inside the decoder, so the full-size
image never exists. A 6000 by 4000 JPEG decoded for a 200 by 200 box takes about a fifth of the time
of a full decode and allocates 2.6 MiB instead of 126 MiB.

```kotlin
val thumbnail = ImageKodec.decodeScaled(bytes, maxWidth = 256, maxHeight = 256)
```

[Resizing and orientation](docs/resizing.md) covers exact sizes, crops and rotations.

### Write a file

```kotlin
val png: ByteArray = ImageKodec.encodePng(bitmap)
val jpeg: ByteArray = ImageKodec.encodeJpeg(bitmap, quality = 85)
```

There are encoders for PNG and APNG, JPEG, GIF, BMP, TIFF and lossless WebP. See
[Writing images](docs/encoding.md).

### Show an image in Compose

```kotlin
KiteImage(
    data = bytes,
    contentDescription = "avatar",
    modifier = Modifier.size(96.dp),
)
```

`KiteImage` from `imagekodec-compose` decodes off the main thread, plays an animation, applies the
EXIF orientation and converts to sRGB. `imagekodec-coil` gives Coil 3 a `KiteImageDecoder`, so Coil
keeps its fetching and caching while ImageKodec decodes. See [Compose and Coil](docs/compose-and-coil.md).

## Formats

| Format | Decodes | Encodes |
| --- | --- | --- |
| PNG and APNG | every color type and depth, interlace, animation | 8-bit RGB or RGBA, and APNG |
| JPEG | baseline, progressive, arithmetic, lossless and hierarchical, at 8 or 12 bits and lossless up to 16 | baseline |
| GIF | still and animated | still and animated |
| BMP | every header version and depth, RLE, embedded JPEG and PNG | 24-bit, or 32-bit with alpha |
| TIFF | strips and tiles, the common compressions, every page, up to 16 bits | 8 or 16 bits, many pages |
| WebP | lossy and lossless, still and animated | lossless |
| JPEG 2000 | all of Part 1, JP2 and raw codestream | no |
| AVIF | stills and image sequences, 8 to 12 bits | no |
| JPEG XL | both coding modes, layers and animation | no |

`Jbig2Decoder` and `CcittFax` decode JBIG2 and CCITT fax data as a PDF embeds it. Neither format
carries a header of its own, so both take their parameters explicitly. [Reading images](docs/decoding.md#what-each-format-decodes) lists every feature of each
format.

## Platforms

The core builds for 22 targets:

| Family | Targets |
| --- | --- |
| Android, JVM | Android (minSdk 21), `jvm` (Java 11 or newer) |
| Apple | `iosArm64`, `iosSimulatorArm64`, `iosX64`, `macosArm64`, `tvosArm64`, `tvosSimulatorArm64`, `watchosArm32`, `watchosArm64`, `watchosDeviceArm64`, `watchosSimulatorArm64` |
| Other native | `linuxX64`, `linuxArm64`, `mingwX64`, `androidNativeArm32`, `androidNativeArm64`, `androidNativeX64`, `androidNativeX86` |
| Web | `js`, `wasmJs`, `wasmWasi` |

`macosX64` is not built, because Kotlin deprecates Intel macOS. The Compose and
Coil modules build for the seven targets that the [Install](#install) section names. Compose draws
in a browser on the web, so those two modules are for browser apps.

## Limits

- Every entry point takes the complete file as a `ByteArray`. There is no streaming decode.
- `decode` returns 8 bits a channel. Use `decode16` for deeper samples.
- There is no encoder for lossy WebP, JPEG 2000, AVIF or JPEG XL.
- A decode refuses an image of more than 2^28 pixels (about 1 GiB of ARGB).

[Limits](docs/limits.md) has the full list, format by format.

## Contributing

[CONTRIBUTING.md](CONTRIBUTING.md) covers the build, the tests and the reference implementation
that checks each codec. The rule that matters most is that a decoder never trusts its input.
Report a security problem as [SECURITY.md](SECURITY.md) describes. Open work is in
[GitHub Issues](https://github.com/yuroyami/ImageKodec/issues), and the change history is in
[CHANGELOG.md](CHANGELOG.md).

## License

Apache-2.0. ImageKodec combines specification implementations with ports of permissively licensed
code, including [stb_image](https://github.com/nothings/stb) (public domain or MIT),
[Apache Commons Imaging](https://github.com/apache/commons-imaging) (Apache-2.0), libwebp and
libjxl (BSD-3-Clause). The DEFLATE code comes from [ArchiveKodec](https://github.com/yuroyami/ArchiveKodec),
the archive library of the same family. [reference/REFERENCES.md](reference/REFERENCES.md) and
[NOTICE](NOTICE) give the attribution for each codec.

Part of the Kite family: [KiteCore](https://github.com/yuroyami/KiteCore),
[KitePDF](https://github.com/yuroyami/KitePDF),
[KiteQR](https://github.com/yuroyami/KiteQR).
