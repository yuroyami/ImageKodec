# KiteImage

Image codecs written in Kotlin for Kotlin Multiplatform: decode PNG, JPEG, GIF,
BMP, TIFF, JPEG 2000 and lossless WebP from a `ByteArray`, with the same code on
every target.

[![Maven Central](https://img.shields.io/maven-central/v/io.github.yuroyami/kiteimage)](https://central.sonatype.com/artifact/io.github.yuroyami/kiteimage)
[![CI](https://img.shields.io/github/actions/workflow/status/yuroyami/KiteImage/ci.yml?branch=main&label=CI)](https://github.com/yuroyami/KiteImage/actions/workflows/ci.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Targets](https://img.shields.io/badge/targets-22%20core%2C%207%20UI-blue)](#targets)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

**[Documentation](https://yuroyami.github.io/KiteImage/)** · an overview with
examples, plus the generated API reference.

## What you get

Compose Multiplatform draws an image on every target. Getting the pixels still
goes through the platform: `BitmapFactory` on Android, CoreGraphics on iOS, Skia
on desktop, and the browser on web. Common code that needs real pixels must
therefore call per-platform code. A thumbnail hash, a server-side resize and a
PDF's embedded images all need this.

KiteImage decodes in common Kotlin instead. Every format normalizes to
non-premultiplied ARGB_8888 in a plain `IntArray`. Non-premultiplied means the
red, green and blue channels hold the original color, not the color already
multiplied by the alpha value. KiteImage resolves palettes, grayscale, BGR
ordering and chroma subsampling before it returns the pixels. Chroma subsampling
means the file stores color at a lower resolution than brightness.

The core artifact depends on kotlin-stdlib and nothing else. The Compose and Coil
bindings are separate, optional modules.

```kotlin
import io.github.yuroyami.kiteimage.KiteImage
import io.github.yuroyami.kiteimage.KiteBitmap

// Header only: no pixel buffer is allocated.
val info = KiteImage.probe(bytes)
println("${info.width}x${info.height}, ${info.frameCount} frame(s)")

// KiteImage reads the magic bytes to find the format.
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

Read [Targets](#targets) before you add the optional two. `kiteimage-coil`
declares `kiteimage-compose` as `implementation`, so it does not arrive
transitively. Declare the Compose module yourself if you want the Coil module.

## What it does

### Read a header without decoding

`probe` parses the header alone. It allocates nothing image-sized, so it stays
cheap on a 50-megapixel file.

```kotlin
val info = KiteImage.probe(bytes)
info.width; info.height          // as stored
info.displayWidth                // after the EXIF orientation tag
info.frameCount; info.hasAlpha; info.bitDepth
info.isDecodable                 // and info.unsupportedReason when it is false
```

`isDecodable` is a statement about features. It is false when the file uses
something this build does not implement, and `unsupportedReason` names it.
Examples are lossy WebP, a CgBI PNG, an arithmetic-coded JPEG and JPEG-in-TIFF.
The Coil decoder uses this flag to decide which files to claim.

`isDecodable` stays true for a file that declares only supported features and is
then truncated or corrupt. A decode can therefore still fail after a clean probe.

### Decode a still

```kotlin
val bitmap = KiteImage.decode(bytes)
val upright = KiteImage.decode(bytes, applyOrientation = true)   // honor EXIF
```

Two terms used in this table. IFD means Image File Directory, the record that
describes one page of a TIFF file. Chroma subsampling means the file stores color
at a lower resolution than brightness.

| Format | What decodes |
| --- | --- |
| PNG | color types 0/2/3/4/6, depths 1/2/4/8/16, all five filters, `tRNS` palette alpha and color-key, Adam7 interlace |
| APNG | dispose none/background/previous, blend source/over, frame rects, loop count |
| JPEG | baseline SOF0, extended sequential SOF1, progressive SOF2, restart intervals, sampling factors 1..4 (4:2:0, 4:2:2, 4:4:4, 4:1:1), gray, YCbCr, RGB, CMYK and YCCK |
| GIF | 87a and 89a, full LZW, interlace, all four disposal methods, per-frame delays, NETSCAPE and ANIMEXTS loop counts |
| BMP | header versions 12/40/52/56/64/108/124, depths 1/2/4/8/16/24/32, BI_RGB, RLE4, RLE8, BITFIELDS with arbitrary masks, top-down and bottom-up |
| WebP | lossless VP8L only, still and animated. Lossy VP8 is not implemented at all |
| TIFF | strips and tiles, raw/PackBits/LZW/Deflate/CCITT G3-1D/G4, photometric 0/1/2/3/6 including subsampled YCbCr, bits 1/2/4/8/16, predictor 2, both planar configurations, first IFD only |
| JPEG 2000 | JP2 container and raw J2K codestream, part 1 baseline |

Two rows above are narrower than the format name suggests:

- **Only lossless VP8L WebP decodes.** Lossy VP8 throws
  `UnsupportedImageException`, in stills and inside animation frames alike. Most
  `.webp` files published on the internet are lossy.
- **The TIFF decoder reads only the first IFD.** A multi-page TIFF decodes to
  page 1 and reports no error.

`ImageFormat.sniff` (and `KiteImage.detect`) recognize PNG, JPEG, GIF, BMP, WEBP,
TIFF and JP2. Sniffing is deliberately wider than decoding, which is why `probe`
is worth calling.

`Jbig2Decoder` and `CcittFax` are public as well. Neither format carries magic
bytes or dimensions of its own. Both therefore take their parameters explicitly,
and both return packed 1-bit rows instead of using `decode`.

### Play an animation

One type covers GIF, APNG and animated WebP. Frames are full composited canvases,
with disposal, blending and frame offsets already applied. Playback is therefore
"draw frame N, wait delay N".

```kotlin
val anim = KiteImage.decodeAnimation(bytes)
anim.frames.size
anim.loopCount        // 0 means forever, in each format's own semantics
anim.durationMillis
```

GIF and WebP delays of 10 ms and under are reported as 100 ms, which matches
browser behavior. KiteImage reports an APNG delay exactly as the file states it,
so an fcTL with `delay_num = 0` gives a zero-millisecond frame.
`KiteFrame.delayRawCentiseconds`
is the exact figure a GIF stated. For APNG and WebP it is derived, because
neither format stores centiseconds.

### Write an image out

```kotlin
KiteImage.encodePng(bitmap)                    // 8-bit RGB, or RGBA when alpha is present
KiteImage.encodeJpeg(bitmap, quality = 85)     // baseline; 4:2:0 at quality <= 90, 4:4:4 above
KiteImage.encodeGif(bitmap, dither = true)     // median cut + Floyd-Steinberg, or exact under 256 colors
KiteImage.encodeGif(anim)                      // animated, delays and loop count preserved
KiteImage.encodeBmp(bitmap)                    // 24-bit BI_RGB, or 32-bit V4 BITFIELDS with alpha
```

There is no WebP, TIFF or JPEG 2000 encoder.

### Rotate, crop and scale

```kotlin
bitmap.rotated90(); bitmap.rotated180(); bitmap.rotated270()
bitmap.flippedHorizontal(); bitmap.flippedVertical()
bitmap.transposed(); bitmap.transversed()
bitmap.cropped(x = 10, y = 10, width = 64, height = 64)
bitmap.scaled(maxWidth = 256, maxHeight = 256)
bitmap.oriented(info.orientation)
```

`scaled` is an alpha-weighted box filter. It preserves aspect ratio and never
upscales. When the image already fits the box, it returns the image unchanged.
`oriented`, `cropped`, `scaled` and the three rotations also exist for a whole
`KiteAnimation`. `cropped` throws `IllegalArgumentException` when the rectangle
extends outside the image. It does not clamp the rectangle.

### Show an image in Compose

`kiteimage-compose` provides a `KiteImage` composable. It reads the input and
animates it when the input is animated.

```kotlin
import io.github.yuroyami.kiteimage.compose.KiteImage

KiteImage(
    data = bytes,
    contentDescription = "avatar",
    modifier = Modifier.size(96.dp),
    animate = true,                    // false shows only the first frame
    onError = { log(it) },             // malformed input draws nothing
)
```

Decoding runs on `Dispatchers.Default` and re-runs when `data` changes. The
composable holds its layout slot and draws nothing until decoding finishes. It
applies EXIF orientation, even though `KiteImage.decode` does not apply it by
default.

Two more composables are public, for pipelines that decode themselves: an
overload of `KiteImage` that takes a `KiteBitmap`, and `KiteAnimatedImage` that
takes a `KiteAnimation`. `KiteBitmap.toImageBitmap()` is public as well.

Two public declarations share the name `KiteImage`. One is the `object` in
`io.github.yuroyami.kiteimage`. The other is this `@Composable fun` in
`io.github.yuroyami.kiteimage.compose`. Importing both in one file needs an `as`
alias.

### Use it with Coil

`kiteimage-coil` decodes the image instead of Coil's platform decoder. Coil keeps
network fetching, disk and memory caching, and the request lifecycle.

```kotlin
ImageLoader.Builder(context)
    .components { add(KiteImageDecoder.Factory()) }
    .build()

KiteAsyncImage(model = "https://example.com/reaction.gif", contentDescription = null)
```

`KiteImageDecoder.Factory` claims a file by calling `probe` on a 64 KiB peek and
checking `isDecodable`. It therefore takes what these codecs handle and leaves
the rest to Coil's platform decoders. The factory claims TIFF and JP2 even when
the probe fails. A TIFF's IFD is often further into the file than 64 KiB, and no
platform decoder handles either format.

`KiteAnimationImage` holds no playback state of its own, because the frame
position lives in the composable. The default animation cache limit is 64 MiB,
and `KiteImageDecoder.Factory(maxCacheableAnimationBytes = ...)` changes it.
Coil's memory cache holds an animation when its decoded frames fit under that
limit. Larger ones re-decode from the disk cache instead of evicting everything
else.

`KiteAsyncImage` passes its layout constraints to the request as a target size.
KiteImage then box-filters still images and every animation frame to the size you
actually draw.

This module declares coil3 as `api`, so coil3 types appear on your compile
classpath even when you do not use them.

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

`kiteimage-compose` and `kiteimage-coil` build for seven targets: Android, `jvm`,
`iosArm64`, `iosSimulatorArm64`, `macosArm64`, `js` (browser only) and `wasmJs`
(browser only).

**A project that targets `iosX64`, Linux, Windows, tvOS, watchOS, androidNative
or wasmWasi will resolve the core and then fail to resolve those two modules.**
This is the most common way a first build breaks here. Keep the two optional
modules out of any source set that includes those targets.

On `js` and `wasmJs`, those two modules configure the browser environment only. A
web project that runs under Node should not use them.

## Limits

- **Lossy WebP does not decode**, which covers most `.webp` files published on
  the internet. `probe` reports them as undecodable and `decode` throws
  `UnsupportedImageException`. You can therefore route them to a platform decoder
  without a failed attempt first.
- **KiteImage keeps only the high byte of a 16-bit sample.** PNG and TIFF read
  16-bit files, but the output buffer is 8 bits per channel. KiteImage discards
  the low byte rather than dithering or scaling it. `probe` still reports the
  stored depth.
- **The TIFF decoder reads only the first IFD.** A multi-page TIFF decodes to
  page 1 with no error and a `frameCount` of 1.
- **KiteImage writes PNG, JPEG, GIF and BMP only.** There is no encoder for WebP,
  TIFF or JPEG 2000.
- **The PNG encoder writes 8-bit RGB or RGBA only**, with no interlace, no
  palette and no 16-bit output. It picks a filter per row, which is a compression
  choice, not a format capability.
- **Whole-array only.** Every entry point takes a complete `ByteArray`. There is
  no streaming or partial-decode API, and scaling happens after a full-size
  decode rather than in the DCT domain.
- **Feature refusals are typed, with one exception.** Everything a decoder
  recognizes but cannot handle throws `UnsupportedImageException` naming the
  feature. The JPEG 2000 decoder reports one failure signal for everything, so
  its refusals surface as plain `ImageDecodeException`. `probe` still names the
  main-header ones: RGN, POC, PPM/PPT and non-baseline code-block styles.
- **`probe` covers features, not corruption.** JPEG 2000 is the loosest of the
  seven formats. Its check stops at the first tile-part and does not range-check
  the COD and QCD parameters. A per-tile coding-style override, or an
  out-of-range decomposition count, is therefore only found at decode.
- **Decompression-bomb guards can reject legitimate files.** A decompression bomb
  is a small file that expands into a very large image. PNG, JPEG, GIF, BMP, TIFF
  and WebP cap output at 2^28 pixels and at 4096 decoded pixels per input byte. A
  very large, very well compressed image can hit that second limit. JPEG 2000
  uses its own flat 2^26-pixel ceiling and no input-relative budget.
- `probe` reads EXIF orientation from JPEG and TIFF files and reports it.
  `decode` only applies it when you pass `applyOrientation = true`. The Compose
  binding applies it for you.
- GIF "restore to background" clears to transparent instead of the declared
  background color index. That matches browser behavior rather than the 1989
  specification text.

## Testing

265 tests: 195 in `commonTest`, which run on JVM, JS (Node), Wasm (Node) and
Kotlin/Native, plus 70 JVM-only integration tests across the three modules.

Correctness is checked against other implementations, not against hand-written
expectations:

| Codec | Checked against | Tolerance |
| --- | --- | --- |
| JPEG decode | stb_image, through committed vectors that a clang-compiled stb_image produced | bit-identical |
| PNG, GIF, BMP, JPEG encode | `javax.imageio` reads the output back | exact |
| WebP lossless | libwebp `cwebp` and `dwebp` | pixel-exact |
| JPEG 2000 | OpenJPEG | exact for reversible 5/3, within 4/255 for irreversible 9/7 |
| TIFF | libtiff and ImageMagick | exact, except 16-bit which allows 1 |

The first two rows need no external tool, so they always run. The last three rows
run only when the binary is installed, and skip when it is not. A skipped test
reports as a pass, so read the skip count and not only the pass result.

`FuzzTest` drives seeded bit flips, truncations and cross-format splices through
every decoder. It asserts that nothing but `ImageDecodeException` escapes.

Every unsupported feature fails at a named point, not silently.

`./gradlew :sample:run` opens a desktop gallery. It plays animated GIF, APNG and
animated WebP, shows JPEG, JP2 and TIFF stills, and runs all four encoders at
startup. Every tile is captioned from `probe`.

## Contributing

[CONTRIBUTING.md](CONTRIBUTING.md) covers the build and the reference oracle
behind each codec. The rule that matters is that a decoder never trusts its
input. Security reporting is in [SECURITY.md](SECURITY.md), the feature matrix in
[PORTING_STATUS.md](PORTING_STATUS.md), and the change history in
[CHANGELOG.md](CHANGELOG.md).

## License

Apache-2.0. KiteImage is a clean-room implementation built from permissively
licensed references, mainly [stb_image](https://github.com/nothings/stb) (public
domain or MIT) and
[Apache Commons Imaging](https://github.com/apache/commons-imaging) (Apache-2.0).
The flate paths derive from zlib references by way of KiteArchive. Per-codec
attribution is in [reference/REFERENCES.md](reference/REFERENCES.md) and
[NOTICE](NOTICE).

Part of the Kite family: [KiteCore](https://github.com/yuroyami/KiteCore),
[KitePDF](https://github.com/yuroyami/KitePDF),
[KiteQR](https://github.com/yuroyami/KiteQR).
