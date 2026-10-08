# ImageKodec

Image codecs written in Kotlin for Kotlin Multiplatform: decode PNG, JPEG, GIF,
BMP, TIFF, JPEG 2000, WebP and AVIF from a `ByteArray`, with the same code on every
target.

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

// GIF, APNG, animated WebP and animated AVIF all arrive in this shape, fully composited.
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
Examples are a CgBI PNG, a 12-bit JPEG and a TIFF with floating-point
samples.
The Coil decoder uses this flag to decide which files to claim.

`isDecodable` stays true for a file that declares only supported features and is
then truncated or corrupt. A decode can therefore still fail after a clean probe.
One case is different: WebP data that ends before its first image chunk probes as
not decodable. Until that chunk, nothing says which codec the image uses.

`colorProfile` reports what the file declares about its color space: the embedded
ICC profile in any format, byte for byte, and PNG's `sRGB`, `gAMA`, `cHRM` and
`cICP` chunks. It is null when the file declares nothing, which for nearly every
image means sRGB. ImageKodec does not convert samples to sRGB, so a color pipeline
of your own can use the profile.

```kotlin
info.colorProfile?.iccDescription    // "Display P3", "Adobe RGB (1998)", ...
info.colorProfile?.icc               // the profile's bytes
```

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
| JPEG | baseline SOF0, extended sequential SOF1, progressive SOF2, arithmetic coding SOF9 and SOF10 with DAC conditioning, lossless SOF3 and SOF11 at 2 to 16 bits with every predictor and point transform, hierarchical SOF5 to SOF7 and SOF13 to SOF15 over any of them, restart intervals, sampling factors 1..4 (4:2:0, 4:2:2, 4:4:4, 4:1:1), gray, YCbCr, RGB, CMYK and YCCK |
| GIF | 87a and 89a, full LZW, interlace, all four disposal methods, per-frame delays, NETSCAPE and ANIMEXTS loop counts |
| BMP | header versions 12/40/52/56/64/108/124, depths 1/2/4/8/16/24/32, BI_RGB, RLE4, RLE8, BITFIELDS with arbitrary masks, top-down and bottom-up, and BI_JPEG and BI_PNG, whose embedded file decodes as itself |
| WebP | lossless VP8L and lossy VP8 with its ALPH opacity, still and animated, including frames that mix the two |
| TIFF | strips and tiles, raw/PackBits/LZW/Deflate/CCITT G3 (1D/mixed 2D)/G4/JPEG (Technical Note 2 and old-style), photometric 0/1/2/3/6 including subsampled YCbCr, bits 1/2/4/8/16, predictor 2, both planar configurations, every page |
| JPEG 2000 | JP2 container and raw J2K codestream, all of Part 1: every code-block style, progression order changes (POC), packed packet headers (PPM and PPT) and regions of interest (RGN) |
| AVIF | still images and animated image sequences: every AV1 coding tool, intra and inter, with the loop filter, CDEF, superres, loop restoration and film grain, 8 to 12 bits in 4:0:0, 4:2:0, 4:2:2 and 4:4:4, alpha straight or premultiplied, grids, sample transforms, overlays, clean aperture, rotation and mirror |

A TIFF can hold any number of pages. `decode` returns the first,
`probe(bytes).pageCount` says how many there are, and `decodePage` and
`probePage` reach the others:

```kotlin
val pages = ImageKodec.probe(bytes).pageCount
val third = ImageKodec.decodePage(bytes, page = 2)
```

Pages are separate images with their own size and orientation, not animation
frames, so `frameCount` stays 1 and `decodeAnimation` returns the first page.

Lossy WebP decodes to the pixels libwebp's `dwebp` writes: its VP8 decoder is a
port of libwebp's, and the chroma is upsampled as dwebp's default output does.

AVIF decodes with an AV1 decoder written from the AV1 specification, whose planes
match dav1d's sample for sample, film grain included. YUV converts to RGB with the
matrix and range the `colr` box or the AV1 sequence header gives, chroma upsampled
bilinearly, as libavif's own float path does; the result matches it to within one
level, and mostly exactly. Rotation and mirroring are reported as
`ImageInfo.orientation`, as EXIF orientation is for a JPEG, and `applyOrientation`
applies them; the clean aperture always crops. An image sequence plays through
`decodeAnimation`, every frame with the duration its track gives and the loop
count its edit list gives, alpha track included; `decode` shows its first frame.

`ImageFormat.sniff` (and `ImageKodec.detect`) recognize PNG, JPEG, GIF, BMP, WEBP,
TIFF, JP2 and AVIF. Sniffing is deliberately wider than decoding, which is why `probe`
is worth calling.

`Jbig2Decoder` and `CcittFax` are public as well. Neither format carries magic
bytes or dimensions of its own. Both therefore take their parameters explicitly,
and both return packed 1-bit rows instead of using `decode`.

### Play an animation

One type covers GIF, APNG, animated WebP and animated AVIF. Frames are full composited canvases,
with disposal, blending and frame offsets already applied. Playback is therefore
"draw frame N, wait delay N".

```kotlin
val anim = ImageKodec.decodeAnimation(bytes)
anim.frames.size
anim.loopCount        // Long total plays: 0 means forever, 1 means once
anim.durationMillis

// A thumbnail or a paused image needs only the first frame, and nothing past it is read.
val first = ImageKodec.decodeAnimation(bytes, maxFrames = 1)

// From a coroutine, stop between frames once the result no longer matters.
val frames = ImageKodec.decodeAnimation(bytes) { coroutineContext.ensureActive() }
```

`KiteAnimation.loopCount` and `ImageInfo.loopCount` use `Long`. GIF's stored repeat
count is normalized to total plays; encoding one play omits the loop extension.
The GIF writer accepts total counts in 0..65536 and refuses larger counts rather
than changing them. APNG preserves its full four-byte play field, including
counts above the PNG Third Edition integer limit as a compatibility extension
for deployed writers such as Pillow.

GIF, APNG, WebP and AVIF delays of 10 ms and under are reported as 100 ms, which matches
browser behavior. An APNG `fcTL` with `delay_num = 0` therefore gives a 100 ms frame.
`KiteFrame.delayRawCentiseconds` is the exact figure a GIF stated. For APNG, WebP and
AVIF it is derived from the stated delay, because none of them stores centiseconds.

### Keep 16-bit samples

`decode` returns 8 bits a channel. For a depth map, a scientific or medical image
or a graded photograph, `decode16` keeps every bit a 16-bit PNG or TIFF stores,
every bit of a JPEG 2000 component up to 16 bits deep, and every bit of a lossless
JPEG of 9 to 16 bits, as medical images carry them, in the channels the file has
once a palette is looked up: gray, gray and alpha, RGB or RGBA. A 10- or 12-bit
AVIF converts to RGB at 16 bits.

```kotlin
val wide = ImageKodec.decode16(bytes)
wide.channels                  // 1 for a gray depth map, which stays 2 bytes a pixel
wide[x, y, 0]                  // 0..65535
wide.toBitmap()                // the high byte of each sample: exactly what decode returns
```

Narrower samples replicate up, so an 8-bit value `v` becomes `v * 257` and a 12-bit
one `v shl 4 or (v shr 8)`, and every other format decodes as `decode` does and
widens the same way.

### Write an image out

```kotlin
ImageKodec.encodePng(bitmap)                    // 8-bit RGB, or RGBA when alpha is present
ImageKodec.encodeJpeg(bitmap, quality = 85)     // baseline; 4:2:0 at quality <= 90, 4:4:4 above
ImageKodec.encodeGif(bitmap, dither = true)     // median cut + Floyd-Steinberg, or exact under 256 colors
ImageKodec.encodeGif(anim)                      // animated, delays and loop count preserved
ImageKodec.encodePng(anim)                      // APNG: lossless frames, alpha, delays and loop count
ImageKodec.encodeBmp(bitmap)                    // 24-bit BI_RGB, or 32-bit V4 BITFIELDS with alpha
ImageKodec.encodeTiff(bitmap)                   // gray or RGB, alpha when present, Deflate with the predictor
ImageKodec.encodeTiff(pages)                    // one page a bitmap, read back with decodePage
ImageKodec.encodeTiff16(bitmap16)               // the same at 16 bits, read back with decode16
ImageKodec.encodeWebp(bitmap)                   // lossless VP8L, smaller than the PNG
```

There is no lossy WebP or JPEG 2000 encoder.

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
avoid that, decode straight to the size you need:

```kotlin
ImageKodec.decodeScaled(bytes, maxWidth = 256, maxHeight = 256)   // the size scaled(256, 256) gives
ImageKodec.decodeDownscaledTo(bytes, width = 300, height = 200)   // exactly 300 by 200, as for a centre crop
```

Each reads the header, decodes a JPEG or JPEG 2000 at the largest reduction
below whose output still covers the target, and finishes with the same box
filter, so the result is never smaller than asked for and never upscaled. A
6000 by 4000 JPEG asked for at 200 by 200 takes about a fifth of the time of a
full decode and `scaled`, and allocates 2.6 MiB where that allocates 126 MiB.
Other formats decode in full and filter once. To pick the reduction yourself:

```kotlin
ImageKodec.decodeReduced(bytes, reduction = 4)  // each side divided by 4, rounded up
```

The reduction is 1, 2, 4 or 8. A JPEG shrinks inside its inverse DCT, as
libjpeg's `djpeg -scale` does, so a baseline JPEG needs memory only for the
smaller image. Each reduced pixel is the average of the full inverse DCT over
the pixels it stands for, and subsampled chroma reduces across and down apart,
so it keeps the detail the smaller image can show. A progressive JPEG still keeps the coefficients of the full size
until its last scan. A JPEG 2000 image drops its finest wavelet levels, as
OpenJPEG's reduce option does. Other formats decode in full, then average each
block of pixels.

All these geometry helpers also work on a whole `KiteAnimation`, preserving
frame delays and the play count. `cropped` throws `IllegalArgumentException`
when the rectangle extends outside the image. It does not clamp the rectangle.

### Read a JPEG's stored samples

Some callers know better than the decoder what a JPEG's samples mean. A PDF's
CMYK images are the usual case: the PDF says whether the samples are inverted
and whether they hold YCCK. `decodeJpegComponents` stops before the color
conversion and returns every component as the file stores it:

```kotlin
val c = ImageKodec.decodeJpegComponents(bytes, reduction = 2)
c.componentCount   // 1, 3 or 4
c.adobeTransform   // 0, 1 or 2 from the Adobe marker, -1 without one
c[x, y, 3]         // the K sample of a CMYK pixel, 0..255
```

It runs the same decode and upsampling as `decodeReduced`, at about the same
cost, and the decoder's own color conversion of these samples gives exactly the
pixels `decodeReduced` returns.

### Show an image in Compose

`imagekodec-compose` provides a `KiteImage` composable. It reads the input and
animates it when the input is animated.

```kotlin
import io.github.yuroyami.imagekodec.compose.KiteImage

KiteImage(
    data = bytes,
    contentDescription = "avatar",
    modifier = Modifier.size(96.dp),
    animate = true,                    // false decodes and shows only the first frame
    onError = { log(it) },             // malformed input draws nothing
)
```

Decoding runs on `Dispatchers.Default` and re-runs when `data` changes. It stops
at the next frame once the composable leaves composition, so a list scrolled past
animated images does not keep decoding them. Frames become `ImageBitmap`s on
`Dispatchers.Default` too, ahead of playback, so the thread that draws never
copies a frame. The composable holds its layout slot and draws nothing until the
first frame is ready. It applies EXIF orientation, even though
`ImageKodec.decode` does not apply it by default.

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
without a second whole-file byte array. It accepts VP8L frames and VP8 key frames,
and declines a file with any other image chunk, so Coil can choose its platform
decoder.
The factory claims TIFF and JP2 even when the probe fails. A TIFF's IFD is often
further into the file than 64 KiB, and no platform decoder handles either format.

`KiteAnimationImage` holds no playback state of its own, because the frame
position lives in the composable. The default animation cache limit is 64 MiB,
and `KiteImageDecoder.Factory(maxCacheableAnimationBytes = ...)` changes it.
Coil's memory cache holds an animation when its decoded frames fit under that
limit. Larger ones re-decode from the disk cache instead of evicting everything
else.

`ImageRequest.Builder.maxFrames(n)` decodes at most `n` frames of an animation,
and `maxFrames(1)` returns its first frame as a still. The count is part of the
memory cache key, so a still never answers a request for the animation.
`KiteAsyncImage(animate = false)` asks for one frame this way, so a grid of
paused thumbnails decodes one frame of each; turning `animate` on requests the
rest while that frame stays up.

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

- **`decode` keeps only the high byte of a 16-bit sample**, because `KiteBitmap`
  is 8 bits a channel. `decode16` keeps the whole sample for PNG, TIFF, JPEG 2000
  and lossless JPEG, and converts AVIF at 16 bits; other formats come through it at
  8 bits, widened.
- **ImageKodec writes PNG and APNG, JPEG, GIF, BMP, TIFF and lossless WebP.** There
  is no encoder for lossy WebP, JPEG 2000 or AVIF.
- **The PNG encoder writes 8-bit RGB or RGBA only**, with no interlace, no
  palette and no 16-bit output. It picks a filter per row, which is a compression
  choice, not a format capability.
- **Whole-array only.** Every entry point takes a complete `ByteArray`. There is
  no streaming or partial-decode API. `decodeScaled`, `decodeDownscaledTo` and
  `decodeReduced` shrink a JPEG or a JPEG 2000 image inside the decoder, and
  `scaled` works after a full-size decode.
- **JPEG 2000 refusals name their cause.** The high-throughput block coder of
  Part 15 (HTJ2K) throws `UnsupportedImageException`, and so do files with more
  than 1000 layers or more than 32 progression order changes in one header,
  OpenJPEG's limit. Malformed
  headers name the marker, field and byte position. The public nullable
  `JpxDecoder.decode` overloads still return null on failure; `probe` names
  unsupported features in main and tile-part headers.
- **AVIF colour stops at the matrix.** AV1 matrices that are not linear, BT.2020
  and chromaticity-derived constant luminance, SMPTE ST 2085 and ICtCp, throw
  `UnsupportedImageException`, as libavif refuses them. An ICC profile or a
  transfer function is reported, not applied. An image sequence whose edit list is
  missing loops for ever, as browsers play it; a frame coded at another size than
  the first, which AV1 allows, is scaled to the first frame's size. Large scale tile
  lists, which no image uses, are refused.
- **JPEG 2000 colour comes from the JP2 header, without a colour engine.** The
  palette, channel definitions and colour specification apply as T.800 Annex I
  orders them. sRGB, greyscale, bi-level, sYCC, e-sYCC, CMYK and CMY convert to
  RGB or gray; an ICC profile picks gray, RGB or CMYK but is not applied. Other
  colour spaces, such as CIELab, throw `UnsupportedImageException` naming them.
- **`probe` covers declared features, not pixel integrity.** JPEG 2000 checks
  main and tile-part headers with the decoder's coding and quantization readers,
  skipping packet bytes by their declared lengths. A corrupt packet payload can
  still probe decodable and fail during decoding.
- **Output limits use a shared ceiling.** All image decoders cap output at
  2^28 pixels (about 1 GiB of ARGB).
  WebP, fax-compressed TIFF, `CcittFax` and `Jbig2Decoder` use that absolute
  ceiling without an input-size ratio: constant VP8L pixels and fax reference
  rows can take no entropy bits or one bit per row. WebP animation frames
  together must fit the same ceiling. Other paths retain a loose input-relative
  bound of 4096 pixels per input byte, scaled by eight divided by the stored
  bits per pixel for PNG/TIFF pixels smaller than a byte. Those guards can still refuse unusually
  compressed files; PNG/GIF animations also count their full composited canvases
  against the input-relative limit. JPEG 2000 probe and decode share the same
  size decision. The ceiling bounds output size; it does not guarantee that a
  device has enough free memory.
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
| JPEG decode | stb_image, through committed vectors that `tools/stb_dump.c` produced; stb's 4:2:2 right-edge weight is corrected to libjpeg's, and ImageIO checks that edge | bit-identical |
| PNG, GIF, BMP encode | `javax.imageio` reads the output back | exact |
| JPEG encode | `javax.imageio` reads the output back | lossy: per-pixel/mean-error and PSNR thresholds |
| JPEG reduced decode | libjpeg-turbo `djpeg -scale` | pixel comparisons with a stated tolerance |
| WebP lossless | libwebp `cwebp` and `dwebp` | pixel-exact |
| WebP lossy | libwebp `cwebp`, `dwebp`, `img2webp` and `anim_dump`, and libvpx key frames through ffmpeg | pixel-exact |
| JPEG 2000 | OpenJPEG | exact for reversible 5/3, within 1/255 and a mean of 0.05 for irreversible 9/7, 16-bit included |
| TIFF | libtiff and ImageMagick | exact, except 16-bit which allows 1, and JPEG strips, which allow the last bits of a JPEG decoder |
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
