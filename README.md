# ImageKodec

Image codecs written in Kotlin for Kotlin Multiplatform: decode PNG, JPEG, GIF,
BMP, TIFF, JPEG 2000 and lossless WebP from a `ByteArray`, with the same code on
every target.

[![Maven Central](https://img.shields.io/maven-central/v/io.github.yuroyami/imagekodec)](https://central.sonatype.com/artifact/io.github.yuroyami/imagekodec)
[![CI](https://img.shields.io/github/actions/workflow/status/yuroyami/ImageKodec/ci.yml?branch=main&label=CI)](https://github.com/yuroyami/ImageKodec/actions/workflows/ci.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Targets](https://img.shields.io/badge/targets-22%20core%2C%207%20UI-blue)](#targets)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

**[Documentation](https://yuroyami.github.io/ImageKodec/)** · an overview with
examples, plus the generated API reference.

## What you get

Compose Multiplatform draws an image on every target. Getting the pixels still
goes through the platform: `BitmapFactory` on Android, CoreGraphics on iOS, Skia
on desktop, and the browser on web. Common code that needs real pixels must
therefore call per-platform code. A thumbnail hash, a server-side resize and a
PDF's embedded images all need this.

ImageKodec decodes in common Kotlin instead. Every format normalizes to
non-premultiplied ARGB_8888 in a plain `IntArray`. Non-premultiplied means the
red, green and blue channels hold the original color, not the color already
multiplied by the alpha value. ImageKodec resolves palettes, grayscale, BGR
ordering and chroma subsampling before it returns the pixels. Chroma subsampling
means the file stores color at a lower resolution than brightness.

The core artifact depends on kotlin-stdlib and nothing else. The Compose and Coil
bindings are separate, optional modules.

```kotlin
import io.github.yuroyami.imagekodec.ImageKodec
import io.github.yuroyami.imagekodec.KiteBitmap

// Header only: no pixel buffer is allocated.
val info = ImageKodec.probe(bytes)
println("${info.width}x${info.height}, ${info.frameCount} frame(s)")

// ImageKodec reads the magic bytes to find the format.
val bitmap: KiteBitmap = ImageKodec.decode(bytes)
val pixel = bitmap[10, 20]                       // 0xAARRGGBB

// GIF, APNG and animated WebP all arrive in this shape, fully composited.
val anim = ImageKodec.decodeAnimation(bytes)
for (frame in anim.frames) draw(frame.bitmap, frame.delayMillis)

val png: ByteArray = ImageKodec.encodePng(bitmap)
```

## Install

The coordinates below describe the next 0.3.0 release. They are not yet on
Maven Central. The published 0.2.0 artifacts are `io.github.yuroyami:kiteimagecodec`,
`kiteimagecodec-compose` and `kiteimagecodec-coil`, all at version `0.2.0`.
Their Kotlin package is `io.github.yuroyami.kiteimagecodec`; this repository's
examples use the next release's names.

```kotlin
commonMain.dependencies {
    implementation("io.github.yuroyami:imagekodec:0.3.0")
    // Optional, and both build for far fewer targets than the core.
    implementation("io.github.yuroyami:imagekodec-compose:0.3.0")
    implementation("io.github.yuroyami:imagekodec-coil:0.3.0")
}
```

Read [Targets](#targets) before you add the optional two. `imagekodec-coil`
declares `imagekodec-compose` as an implementation dependency, so it arrives
at runtime. Declare the Compose module directly when you want to call its
public functions, such as `KiteImage` or `KiteAnimatedImage`.

The optional bindings depend on Compose Multiplatform `1.12.0-beta02`.
Gradle can raise an application's Compose version to that beta during dependency
resolution. Android consumers of those bindings need `compileSdk` 37 or newer;
the core does not impose that Compose dependency.

Versions before 0.3.0 use older coordinates. 0.2.0 is `io.github.yuroyami:kiteimagecodec`,
and 0.1.0 is `io.github.yuroyami:kiteimage`.

## What it does

### Read a header without decoding

`probe` parses the header alone. It allocates nothing image-sized, so it stays
cheap on a 50-megapixel file.

```kotlin
val info = ImageKodec.probe(bytes)
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
One case is different: WebP data that ends before its first image chunk probes as
not decodable. Until that chunk, nothing says whether the image is lossy.

### Decode a still

```kotlin
val bitmap = ImageKodec.decode(bytes)
val upright = ImageKodec.decode(bytes, applyOrientation = true)   // honor EXIF
```

A file that cannot be decoded throws `ImageDecodeException`. A feature this build lacks
throws its subtype, `UnsupportedImageException`. The functions that throw carry `@Throws`.
Swift and Objective-C therefore see them as throwing functions, and the exception arrives
as an `NSError`.

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

`ImageFormat.sniff` (and `ImageKodec.detect`) recognize PNG, JPEG, GIF, BMP, WEBP,
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
val anim = ImageKodec.decodeAnimation(bytes)
anim.frames.size
anim.loopCount        // Long total plays: 0 means forever, 1 means once
anim.durationMillis
```

`KiteAnimation.loopCount` and `ImageInfo.loopCount` use `Long`. GIF's stored repeat
count is normalized to total plays; encoding one play omits the loop extension.
The GIF writer accepts total counts in 0..65536 and refuses larger counts rather
than changing them. APNG preserves its full four-byte play field, including
counts above the PNG Third Edition integer limit as a compatibility extension
for deployed writers such as Pillow.

GIF, APNG and WebP delays of 10 ms and under are reported as 100 ms, which matches
browser behavior. An APNG `fcTL` with `delay_num = 0` therefore gives a 100 ms frame.
`KiteFrame.delayRawCentiseconds` is the exact figure a GIF stated. For APNG and WebP
it is derived from the stated delay, because neither format stores centiseconds.

### Write an image out

```kotlin
ImageKodec.encodePng(bitmap)                    // 8-bit RGB, or RGBA when alpha is present
ImageKodec.encodeJpeg(bitmap, quality = 85)     // baseline; 4:2:0 at quality <= 90, 4:4:4 above
ImageKodec.encodeGif(bitmap, dither = true)     // median cut + Floyd-Steinberg, or exact under 256 colors
ImageKodec.encodeGif(anim)                      // animated, delays and loop count preserved
ImageKodec.encodeBmp(bitmap)                    // 24-bit BI_RGB, or 32-bit V4 BITFIELDS with alpha
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

`bitmap.downscaledTo(targetWidth = 128, targetHeight = 75)` uses the same filter
with exact output dimensions. It leaves the aspect ratio to the caller and
throws `IllegalArgumentException` if a side is not positive or exceeds the
source. `KiteAnimation.downscaledTo` applies it to every frame and preserves
timing and the play count.

`scaled` works on a decoded image, so the full-size image exists first. To
avoid that for a JPEG, decode at a smaller size:

```kotlin
ImageKodec.decodeReduced(bytes, reduction = 4)  // each side divided by 4, rounded up
```

The reduction is 1, 2, 4 or 8. A JPEG shrinks inside its inverse DCT, as
libjpeg's `djpeg -scale` does, so a baseline JPEG needs memory only for the
smaller image. A progressive JPEG still keeps the coefficients of the full size
until its last scan. A JPEG 2000 image drops its finest wavelet levels, as
OpenJPEG's reduce option does. Other formats decode in full, then average each
block of pixels.

All these geometry helpers also work on a whole `KiteAnimation`, preserving
frame delays and the play count. `cropped` throws `IllegalArgumentException`
when the rectangle extends outside the image. It does not clamp the rectangle.

### Show an image in Compose

`imagekodec-compose` provides a `KiteImage` composable. It reads the input and
animates it when the input is animated.

```kotlin
import io.github.yuroyami.imagekodec.compose.KiteImage

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
applies EXIF orientation, even though `ImageKodec.decode` does not apply it by
default.

Two more composables are public, for pipelines that decode themselves: an
overload of `KiteImage` that takes a `KiteBitmap`, and `KiteAnimatedImage` that
takes a `KiteAnimation`. `KiteBitmap.toImageBitmap()` is public as well.

### Use it with Coil

`imagekodec-coil` decodes the image instead of Coil's platform decoder. Coil keeps
network fetching, disk and memory caching, and the request lifecycle.

```kotlin
val loader = ImageLoader.Builder(context)
    .components { add(KiteImageDecoder.Factory()) }
    .build()

KiteAsyncImage(
    model = "https://example.com/reaction.gif",
    contentDescription = null,
    imageLoader = loader,
)
```

Pass the loader to `KiteAsyncImage`. Without it, the composable uses Coil's singleton
loader, which does not have `KiteImageDecoder`.

`KiteImageDecoder.Factory` probes the first 64 KiB for supported features.
For WebP, it walks the complete RIFF and frame chunk headers, skipping payloads
without a second whole-file byte array. It accepts lossless frames and declines
the file if any frame uses lossy VP8, so Coil can choose its platform decoder.
The factory claims TIFF and JP2 even when the probe fails. A TIFF's IFD is often
further into the file than 64 KiB, and no platform decoder handles either format.

`KiteAnimationImage` holds no playback state of its own, because the frame
position lives in the composable. The default animation cache limit is 64 MiB,
and `KiteImageDecoder.Factory(maxCacheableAnimationBytes = ...)` changes it.
Coil's memory cache holds an animation when its decoded frames fit under that
limit. Larger ones re-decode from the disk cache instead of evicting everything
else.

`KiteAsyncImage` uses its layout constraints as the target size, or original
pixels for `ContentScale.None`. Fit/Inside request FIT; the other drawing scales
request FILL. Explicit request size and scale take precedence. The decoder uses
Coil's sizing rules, including one defined side and `maxBitmapSize`, and
box-filters still images and every animation frame to those dimensions without
upscaling.

This module declares coil3 as `api`, so coil3 types appear on your compile
classpath even when you do not use them.

## Targets

`imagekodec` builds for 22 targets.

| Family | Targets |
| --- | --- |
| Android, JVM | Android (minSdk 21), `jvm` (Java 11 or newer) |
| Apple | `iosArm64`, `iosSimulatorArm64`, `iosX64`, `macosArm64`, `tvosArm64`, `tvosSimulatorArm64`, `watchosArm32`, `watchosArm64`, `watchosDeviceArm64`, `watchosSimulatorArm64` |
| Other native | `linuxX64`, `linuxArm64`, `mingwX64`, `androidNativeArm32`, `androidNativeArm64`, `androidNativeX64`, `androidNativeX86` |
| Web | `js` (browser and Node), `wasmJs` (browser and Node), `wasmWasi` (Node) |

`macosX64` is not built, following Kotlin's deprecation of Intel-Apple native
targets.

`imagekodec-compose` and `imagekodec-coil` build for seven targets: Android, `jvm`,
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
- **ImageKodec keeps only the high byte of a 16-bit sample.** PNG and TIFF read
  16-bit files, but the output buffer is 8 bits per channel. ImageKodec discards
  the low byte rather than dithering or scaling it. `probe` still reports the
  stored depth.
- **The TIFF decoder reads only the first IFD.** A multi-page TIFF decodes to
  page 1 with no error and a `frameCount` of 1.
- **ImageKodec writes PNG, JPEG, GIF and BMP only.** There is no encoder for WebP,
  TIFF or JPEG 2000.
- **The PNG encoder writes 8-bit RGB or RGBA only**, with no interlace, no
  palette and no 16-bit output. It picks a filter per row, which is a compression
  choice, not a format capability.
- **Whole-array only.** Every entry point takes a complete `ByteArray`. There is
  no streaming or partial-decode API. `decodeReduced` shrinks a JPEG or a
  JPEG 2000 image inside the decoder, and `scaled` works after a full-size decode.
- **Feature refusals are typed, with one exception.** Everything a decoder
  recognizes but cannot handle throws `UnsupportedImageException` naming the
  feature. The JPEG 2000 decoder reports one failure signal for everything, so
  its refusals surface as plain `ImageDecodeException`. `probe` still names the
  main-header ones: RGN, POC, PPM/PPT and non-baseline code-block styles.
- **`probe` covers features, not corruption.** JPEG 2000 is the loosest of the
  seven formats. Its check stops at the first tile-part and does not range-check
  the COD and QCD parameters. A per-tile coding-style override, or an
  out-of-range decomposition count, is therefore only found at decode.
- **Output limits depend on the format.** PNG, JPEG, GIF, BMP, TIFF and WebP
  cap output at 2^28 pixels (about 1 GiB of ARGB); JPEG 2000 caps it at 2^26.
  WebP, fax-compressed TIFF, `CcittFax` and `Jbig2Decoder` use that absolute
  ceiling without an input-size ratio: constant VP8L pixels and fax reference
  rows can take no entropy bits or one bit per row. WebP animation frames
  together must fit the same ceiling. Other paths retain a loose input-relative
  bound of 4096 pixels per input byte, scaled by eight divided by the stored
  bits per pixel for PNG/TIFF pixels smaller than a byte. Those guards can still refuse unusually
  compressed files; PNG/GIF animations also count their full composited canvases
  against the input-relative limit. JPEG 2000 applies its input-relative limit
  at decode, while `probe` checks only its pixel ceiling. The ceiling bounds
  output size; it does not guarantee that a device has enough free memory.
- `probe` reads EXIF orientation from JPEG and TIFF files and reports it.
  `decode` only applies it when you pass `applyOrientation = true`. The Compose
  binding applies it for you.
- GIF "restore to background" clears to transparent instead of the declared
  background color index. That matches browser behavior rather than the 1989
  specification text.

## Testing

Most tests live in `commonTest`. They run on JVM, JS (Node), Wasm (Node) and
Kotlin/Native. The JVM-only tests include the comparisons against other
implementations, and the Compose and Coil integration tests.

Correctness is checked against other implementations, not against hand-written
expectations:

| Codec | Checked against | Tolerance |
| --- | --- | --- |
| JPEG decode | stb_image, through committed vectors that a clang-compiled stb_image produced | bit-identical |
| PNG, GIF, BMP encode | `javax.imageio` reads the output back | exact |
| JPEG encode | `javax.imageio` reads the output back | lossy: per-pixel/mean-error and PSNR thresholds |
| JPEG reduced decode | libjpeg-turbo `djpeg -scale` | pixel comparisons with a stated tolerance |
| WebP lossless | libwebp `cwebp` and `dwebp` | pixel-exact |
| JPEG 2000 | OpenJPEG | exact for reversible 5/3, within 4/255 for irreversible 9/7 |
| TIFF | libtiff and ImageMagick | exact, except 16-bit which allows 1 |
| JBIG2 | jbig2enc's streams: generic regions against the source page, symbol mode against jbig2dec | exact |

The committed stb vectors and ImageIO tests need no external binary. The
libjpeg-turbo, libwebp, OpenJPEG, libtiff/ImageMagick and JBIG2 oracle tests run
when their binaries are installed, and skip when they are not. A skipped test
reports as a pass, so read the skip count and not only the pass result. CI sets
`IMAGEKODEC_REQUIRE_ORACLES=1`, which turns a missing tool into a failure.

`FuzzTest` drives seeded bit flips, truncations and cross-format splices through
every decoder that `ImageKodec.decode` dispatches to. It asserts that nothing but
`ImageDecodeException` escapes. `CcittFax` and `Jbig2Decoder` have tests of their
own and are not part of that corpus. `Jbig2DecoderTest` mutates committed jbig2enc
streams in the same way.

Every unsupported feature fails at a named point, not silently.

`./gradlew :sample:run` opens a desktop gallery. It plays animated GIF, APNG and
animated WebP, shows JPEG, JP2 and TIFF stills, and runs all four encoders at
startup. Every tile is captioned from `probe`.

## Contributing

[CONTRIBUTING.md](CONTRIBUTING.md) covers the build and the reference oracle
behind each codec. The rule that matters is that a decoder never trusts its
input. Security reporting is in [SECURITY.md](SECURITY.md), the change history in
[CHANGELOG.md](CHANGELOG.md), and open work in
[GitHub Issues](https://github.com/yuroyami/ImageKodec/issues).

## License

Apache-2.0. ImageKodec combines specification implementations with ports of
permissively licensed code, including [stb_image](https://github.com/nothings/stb)
(public domain or MIT) and
[Apache Commons Imaging](https://github.com/apache/commons-imaging) (Apache-2.0).
The flate paths derive from zlib references by way of ArchiveKodec. Per-codec
attribution is in [reference/REFERENCES.md](reference/REFERENCES.md) and
[NOTICE](NOTICE).

Part of the Kite family: [KiteCore](https://github.com/yuroyami/KiteCore),
[KitePDF](https://github.com/yuroyami/KitePDF),
[KiteQR](https://github.com/yuroyami/KiteQR).
