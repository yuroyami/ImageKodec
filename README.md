# KiteImage

Image codecs written in Kotlin for Kotlin Multiplatform: decode PNG, JPEG, GIF,
BMP, TIFF, JPEG 2000 and lossless WebP from a `ByteArray`, with the same code on
every target.

[![Maven Central](https://img.shields.io/maven-central/v/io.github.yuroyami/kiteimage)](https://central.sonatype.com/artifact/io.github.yuroyami/kiteimage)
[![CI](https://img.shields.io/github/actions/workflow/status/yuroyami/KiteImage/ci.yml?branch=main&label=CI)](https://github.com/yuroyami/KiteImage/actions/workflows/ci.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Targets](https://img.shields.io/badge/targets-22%20core%2C%207%20UI-blue)](#targets)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

## What you get

Compose Multiplatform draws an image on every target, but getting the pixels
still goes through the platform: `BitmapFactory` on Android, CoreGraphics on
iOS, Skia on desktop, the browser on web. The moment common code needs actual
pixels — a thumbnail hash, a server-side resize, a PDF's embedded images — that
is per-platform code again.

KiteImage decodes in common Kotlin instead. Every format normalises to
non-premultiplied ARGB_8888 in a plain `IntArray`, so palettes, grayscale, BGR
ordering and chroma subsampling are resolved before the caller sees anything.
The core artifact depends on kotlin-stdlib and nothing else; the Compose and
Coil bindings are separate, optional modules.

```kotlin
import io.github.yuroyami.kiteimage.KiteImage
import io.github.yuroyami.kiteimage.KiteBitmap

// Header only: no pixel buffer is allocated.
val info = KiteImage.probe(bytes)
println("${info.width}x${info.height}, ${info.frameCount} frame(s)")

// Format is sniffed from the magic bytes.
val bitmap: KiteBitmap = KiteImage.decode(bytes)
val pixel = bitmap[10, 20]                       // 0xAARRGGBB

// GIF, APNG and animated WebP all arrive in this shape, fully composited.
val anim = KiteImage.decodeAnimation(bytes)
for (frame in anim.frames) draw(frame.bitmap, frame.delayMillis)

val png: ByteArray = KiteImage.encodePng(bitmap)
```

## Install

```kotlin
commonMain.dependencies {
    implementation("io.github.yuroyami:kiteimage:0.1.0")
    // Optional, and both build for far fewer targets than the core.
    implementation("io.github.yuroyami:kiteimage-compose:0.1.0")
    implementation("io.github.yuroyami:kiteimage-coil:0.1.0")
}
```

Read [Targets](#targets) before adding the optional two. `kiteimage-coil`
declares `kiteimage-compose` as `implementation`, so it does not arrive
transitively. If you want the Coil module, declare the Compose module yourself.

## What it does

### Read a header without decoding

`probe` parses the header alone. Nothing image-sized is allocated, so this stays
cheap on a 50-megapixel file.

```kotlin
val info = KiteImage.probe(bytes)
info.width; info.height          // as stored
info.displayWidth                // after the EXIF orientation tag
info.frameCount; info.hasAlpha; info.bitDepth
info.isDecodable                 // and info.unsupportedReason when it is false
```

`isDecodable` is a statement about features. It is false when the file uses
something this build does not implement — lossy WebP, a CgBI PNG, an
arithmetic-coded JPEG, JPEG-in-TIFF — and `unsupportedReason` names it. That is
what the Coil decoder uses to decide which files to claim. It stays true for a
file that declares only supported features and is then truncated or corrupt, so
a decode can still fail after a clean probe.

### Decode a still

```kotlin
val bitmap = KiteImage.decode(bytes)
val upright = KiteImage.decode(bytes, applyOrientation = true)   // honour EXIF
```

| Format | What decodes |
| --- | --- |
| PNG | colour types 0/2/3/4/6, depths 1/2/4/8/16, all five filters, `tRNS` palette alpha and colour-key, Adam7 interlace |
| APNG | dispose none/background/previous, blend source/over, frame rects, loop count |
| JPEG | baseline SOF0, extended sequential SOF1, progressive SOF2, restart intervals, sampling factors 1..4 (4:2:0, 4:2:2, 4:4:4, 4:1:1), gray, YCbCr, RGB, CMYK and YCCK |
| GIF | 87a and 89a, full LZW, interlace, all four disposal methods, per-frame delays, NETSCAPE and ANIMEXTS loop counts |
| BMP | header versions 12/40/52/56/64/108/124, depths 1/2/4/8/16/24/32, BI_RGB, RLE4, RLE8, BITFIELDS with arbitrary masks, top-down and bottom-up |
| WebP | lossless VP8L only, still and animated. Lossy VP8 is not implemented at all |
| TIFF | strips and tiles, raw/PackBits/LZW/Deflate/CCITT G3-1D/G4, photometric 0/1/2/3/6 including subsampled YCbCr, bits 1/2/4/8/16, predictor 2, both planar configurations, first IFD only |
| JPEG 2000 | JP2 container and raw J2K codestream, part 1 baseline |

Two rows in that table are narrower than their format name suggests, so they get
said twice. **Only lossless VP8L WebP decodes.** Lossy VP8 throws
`UnsupportedImageException`, in stills and inside animation frames alike, and
most `.webp` files on the web are lossy. **Only a TIFF's first IFD is read**, so
a multi-page TIFF quietly gives you page 1.

`ImageFormat.sniff` (and `KiteImage.detect`) recognise PNG, JPEG, GIF, BMP, WEBP,
TIFF and JP2. Sniffing is deliberately wider than decoding, which is what makes
`probe` worth calling.

`Jbig2Decoder` and `CcittFax` are public as well. Neither format carries magic
bytes or dimensions of its own, so both take their parameters explicitly and
return packed 1-bit rows instead of going through `decode`.

### Play an animation

GIF, APNG and animated WebP come back through one type. Frames are full
composited canvases with disposal, blending and frame offsets already applied,
so playback is "draw frame N, wait delay N".

```kotlin
val anim = KiteImage.decodeAnimation(bytes)
anim.frames.size
anim.loopCount        // 0 means forever, in each format's own semantics
anim.durationMillis
```

GIF and WebP delays of 10 ms and under are reported as 100 ms, matching what
browsers do. APNG is passed through as stated, so an fcTL with `delay_num = 0`
gives a zero-millisecond frame. `KiteFrame.delayRawCentiseconds` is the exact
figure a GIF stated; for APNG and WebP it is derived, since neither stores
centiseconds.

### Write an image out

```kotlin
KiteImage.encodePng(bitmap)                    // 8-bit RGB, or RGBA when alpha is present
KiteImage.encodeJpeg(bitmap, quality = 85)     // baseline; 4:2:0 at quality <= 90, 4:4:4 above
KiteImage.encodeGif(bitmap, dither = true)     // median cut + Floyd-Steinberg, or exact under 256 colours
KiteImage.encodeGif(anim)                      // animated, delays and loop count preserved
KiteImage.encodeBmp(bitmap)                    // 24-bit BI_RGB, or 32-bit V4 BITFIELDS with alpha
```

There is no WebP encoder and no TIFF encoder.

### Rotate, crop and scale

```kotlin
bitmap.rotated90(); bitmap.rotated180(); bitmap.rotated270()
bitmap.flippedHorizontal(); bitmap.flippedVertical()
bitmap.transposed(); bitmap.transversed()
bitmap.cropped(x = 10, y = 10, width = 64, height = 64)
bitmap.scaled(maxWidth = 256, maxHeight = 256)
bitmap.oriented(info.orientation)
```

`scaled` is an alpha-weighted box filter that preserves aspect ratio and never
upscales; an image already inside the box is returned unchanged. `oriented`,
`cropped`, `scaled` and the three rotations also exist for a whole
`KiteAnimation`. `cropped` throws `IllegalArgumentException` on a rect that
leaves the image rather than clamping it.

### Show an image in Compose

`kiteimage-compose` ships `KiteImage`, which decides for itself whether to
animate.

```kotlin
import io.github.yuroyami.kiteimage.compose.KiteImage

KiteImage(
    data = bytes,
    contentDescription = "avatar",
    modifier = Modifier.size(96.dp),
    animate = true,                    // false pins the first frame
    onError = { log(it) },             // malformed input draws nothing
)
```

Decoding runs on `Dispatchers.Default` and re-runs when `data` changes; until it
finishes, the composable holds its layout slot and draws nothing. EXIF
orientation is applied here even though `KiteImage.decode` leaves it off by
default. Two more composables are public for pipelines that decode themselves:
an overload of `KiteImage` taking a `KiteBitmap`, and `KiteAnimatedImage` taking
a `KiteAnimation`. `KiteBitmap.toImageBitmap()` is public as well.

Note that two public things are called `KiteImage`: the `object` in
`io.github.yuroyami.kiteimage` and this `@Composable fun` in
`io.github.yuroyami.kiteimage.compose`. Importing both in one file needs an
`as` alias.

### Use it with Coil

`kiteimage-coil` keeps network fetching, disk and memory caching, and the request
lifecycle with Coil, and takes over decoding.

```kotlin
ImageLoader.Builder(context)
    .components { add(KiteImageDecoder.Factory()) }
    .build()

KiteAsyncImage(model = "https://example.com/reaction.gif", contentDescription = null)
```

`KiteImageDecoder.Factory` claims a file by calling `probe` on a 64 KiB peek and
checking `isDecodable`, so it takes what these codecs handle and leaves the rest
to Coil's platform decoders. TIFF and JP2 are claimed even when that probe fails
outright, because a TIFF's IFD often sits past the peek and neither format has a
platform decoder to fall back to.

`KiteAnimationImage` holds no playback state of its own; the frame position lives
in the composable. Animations whose decoded frames fit under
`KiteImageDecoder.Factory(maxCacheableAnimationBytes = ...)` (64 MiB by default)
therefore go through Coil's memory cache, and larger ones re-decode from the disk
cache instead of evicting everything else. `KiteAsyncImage` feeds its layout
constraints to the request as a target size, so still images and every animation
frame are box-filtered down to what is actually drawn.

This module declares coil3 as `api`, so coil3 types land on your compile
classpath whether you reference them or not.

## Targets

`kiteimage` builds for 22 targets.

| Family | Targets |
| --- | --- |
| Android, JVM | Android (minSdk 21), `jvm` |
| Apple | `iosArm64`, `iosSimulatorArm64`, `iosX64`, `macosArm64`, `tvosArm64`, `tvosSimulatorArm64`, `watchosArm32`, `watchosArm64`, `watchosDeviceArm64`, `watchosSimulatorArm64` |
| Other native | `linuxX64`, `linuxArm64`, `mingwX64`, `androidNativeArm32`, `androidNativeArm64`, `androidNativeX64`, `androidNativeX86` |
| Web | `js` (browser and Node), `wasmJs` (browser and Node), `wasmWasi` (Node) |

`macosX64` is not built, following Kotlin's deprecation of Intel-Apple native
targets.

`kiteimage-compose` and `kiteimage-coil` build for seven: Android, `jvm`,
`iosArm64`, `iosSimulatorArm64`, `macosArm64`, `js` (browser only) and `wasmJs`
(browser only). **A project that targets `iosX64`, Linux, Windows, tvOS, watchOS,
androidNative, wasmWasi, or Node on `js`/`wasmJs` will resolve the core and then
fail to resolve those two modules**, which is the most common way a first build
breaks here. Keep them out of a source set that includes those targets.

## Limits

- **Lossy WebP does not decode**, which covers most `.webp` files in the wild.
  `probe` reports them as undecodable and `decode` throws
  `UnsupportedImageException`, so they can be routed to a platform decoder
  without a failed attempt first.
- **16-bit samples are truncated to the high byte.** PNG and TIFF read 16-bit
  files, but the output buffer is 8 bits per channel, so the low byte is
  discarded rather than dithered or scaled. `probe` still reports the stored
  depth.
- **TIFF reads the first IFD only.** A multi-page TIFF decodes to page 1 with no
  error and `frameCount` of 1.
- **Only PNG, JPEG, GIF and BMP can be written.** There is no encoder for WebP,
  TIFF or JPEG 2000.
- **The PNG encoder writes 8-bit RGB or RGBA only**, with no interlace, no
  palette and no 16-bit output. It picks a filter per row, which is a
  compression choice, not a format capability.
- **Whole-array only.** Every entry point takes a complete `ByteArray`; there is
  no streaming or partial-decode API, and scaling happens after a full-size
  decode rather than in the DCT domain.
- **Feature refusals are typed, with one exception.** Everything a decoder
  recognises but cannot handle throws `UnsupportedImageException` naming the
  feature. The JPEG 2000 decoder reports one failure signal for everything, so
  its refusals surface as plain `ImageDecodeException`; `probe` still names the
  main-header ones (RGN, POC, PPM/PPT, non-baseline code-block styles).
- **`probe` covers features, not corruption.** JPEG 2000 is the loosest of the
  seven. Its check stops at the first tile-part and does not range-check the COD
  and QCD parameters, so a per-tile coding-style override or an out-of-range
  decomposition count is only found at decode.
- **Decompression-bomb guards can reject legitimate files.** PNG, JPEG, GIF, BMP,
  TIFF and WebP cap output at 2^28 pixels and at 4096 decoded pixels per input
  byte, so a very large, very well compressed image can hit the second limit.
  JPEG 2000 uses its own flat 2^26-pixel ceiling and no input-relative budget.
- EXIF orientation is read for JPEG and TIFF and reported by `probe`, but
  `decode` only applies it when you pass `applyOrientation = true`. The Compose
  binding applies it for you.
- GIF "restore to background" clears to transparent instead of the declared
  background colour index, matching what browsers do rather than the 1989 text.

## Testing

265 tests: 195 in `commonTest`, which run on JVM, JS (Node), Wasm (Node) and
Kotlin/Native, plus 70 JVM-only integration tests across the three modules.

Correctness is pinned against other implementations rather than against
hand-written expectations. JPEG decode is bit-identical to stb_image, checked
with vectors a clang-compiled stb_image produced, so that comparison runs on
every target with no external tool. `javax.imageio` reads back everything the
encoders write, which also needs no external tool. Three further suites compare
against a binary when it is installed and skip when it is not: libwebp's
`cwebp`/`dwebp` (pixel-exact for VP8L), OpenJPEG (exact for reversible 5/3,
within 4/255 for irreversible 9/7) and libtiff plus ImageMagick (exact except
16-bit, which allows 1). A skipped test reports as a pass, so read the skip
count rather than only the green tick.

`FuzzTest` drives seeded bit flips, truncations and cross-format splices through
every decoder and asserts nothing but `ImageDecodeException` escapes.

There are no TODOs, stubs or unimplemented branches in the source. Every gap is
a refusal at a named point.

`./gradlew :sample:run` opens a desktop gallery with animated GIF, APNG and
animated WebP playing, JPEG/JP2/TIFF stills, and all four encoders run at
startup. Every tile is captioned from `probe`.

## Contributing

[CONTRIBUTING.md](CONTRIBUTING.md) covers the build and the reference oracle
behind each codec. The rule that matters is that a decoder never trusts its
input. Security reporting is in [SECURITY.md](SECURITY.md); the feature matrix in
[PORTING_STATUS.md](PORTING_STATUS.md); the change history in
[CHANGELOG.md](CHANGELOG.md).

## License

Apache-2.0. Ported clean-room from permissively licensed references, primarily
[stb_image](https://github.com/nothings/stb) (public domain / MIT) and
[Apache Commons Imaging](https://github.com/apache/commons-imaging) (Apache-2.0);
the flate paths derive from zlib references by way of KiteArchive. Per-codec
provenance is in [reference/REFERENCES.md](reference/REFERENCES.md) and
[NOTICE](NOTICE).

Part of the Kite family: [KiteCore](https://github.com/yuroyami/KiteCore),
[KitePDF](https://github.com/yuroyami/KitePDF),
[KiteQR](https://github.com/yuroyami/KiteQR).
