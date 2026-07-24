# KiteImage

[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/docs/multiplatform.html)
[![Targets](https://img.shields.io/badge/targets-Android%20|%20iOS%20|%20macOS%20|%20JVM%20|%20JS%20|%20WASM-success)](#install)
[![Core deps](https://img.shields.io/badge/core%20dependencies-kotlin--stdlib%20only-blue)](#why-kiteimage)
[![Ported from](https://img.shields.io/badge/ports-stb__image%20·%20libwebp%20·%20commons--imaging-orange)](reference/REFERENCES.md)
[![License](https://img.shields.io/badge/license-Apache--2.0-lightgrey)](#license--credits)
[![Status](https://img.shields.io/badge/decodes-PNG%20%2F%20APNG%20%2F%20JPEG%20%2F%20GIF%20%2F%20BMP%20%2F%20WebP%20%2F%20TIFF%20%2F%20JP2-brightgreen)](PORTING_STATUS.md)
[![Tests](https://img.shields.io/badge/tests-260%20passing-brightgreen)](PORTING_STATUS.md)

**A pure-Kotlin image codec toolkit for Kotlin Multiplatform: from-scratch ports of the canonical references (stb_image, commons-imaging). The same `.kt` decodes on Android, iOS, desktop JVM, the browser and WASM. No BitmapFactory, no CoreGraphics, no native binary.**

```kotlin
// decode: sniffs the format from magic bytes, returns packed ARGB
val bitmap: KiteBitmap = KiteImage.decode(bytes)
bitmap.width; bitmap.height
bitmap[x, y]                    // 0xAARRGGBB

// look before you leap: header only, no pixels decoded, no big allocation
val info: ImageInfo = KiteImage.probe(bytes)
info.width; info.height         // as stored
info.displayWidth               // after EXIF orientation
info.frameCount; info.hasAlpha; info.bitDepth
info.isDecodable                // and info.unsupportedReason when it isn't

// animations: GIF, APNG and animated WebP through one shape. Every frame
// arrives fully composited (disposal, blending and frame offsets already
// applied), so playback is just "draw frame N, wait delay N"
val anim: KiteAnimation = KiteImage.decodeAnimation(bytes)
anim.frames.forEach { frame -> frame.bitmap; frame.delayMillis }
anim.loopCount                  // 0 = forever, in every format's own semantics
anim.isAnimated                 // false for static formats (single frame)

// encode
KiteImage.encodePng(bitmap)             // lossless, RGB/RGBA auto
KiteImage.encodeJpeg(bitmap, quality = 85)
KiteImage.encodeGif(bitmap)             // quantised + dithered, or exact under 256 colours
KiteImage.encodeGif(anim)               // animated, delays and loop count preserved
KiteImage.encodeBmp(bitmap)

// geometry
bitmap.scaled(maxWidth = 256, maxHeight = 256)   // box filter, never upscales
bitmap.cropped(x = 10, y = 10, width = 64, height = 64)
bitmap.rotated90(); bitmap.flippedHorizontal()
KiteImage.decode(bytes, applyOrientation = true) // phone photos come back upright

// or just identify
KiteImage.detect(bytes)         // -> ImageFormat.PNG / JPEG / GIF / BMP / WEBP / TIFF / JP2
```

## Why KiteImage

Compose Multiplatform can *display* images everywhere, but *decoding* them everywhere
still leans on each platform: `BitmapFactory` on Android, CoreGraphics on iOS, Skia
codecs on desktop, the browser on web. The moment you need pixels in common code (a
PDF embedding its images, a thumbnail hash, a server-side resize, a wasm tool) you're
stitching per-platform decoders again.

KiteImage is one decoder for all of them. Pure computation on `ByteArray`, kotlin-stdlib
only, identical output on every target.

- **One pixel layout.** Every format normalises to non-premultiplied ARGB_8888 in a
  plain `IntArray`. Grayscale, palette, BGR, 16-bit: gone before you see them.
- **Ask before you decode.** `probe` answers from the header alone: size, depth,
  alpha, frame count, EXIF orientation, and whether this build can decode the file
  at all. Size a layout or reject a hostile upload without allocating a bitmap.
- **Honest refusals.** Anything KiteImage recognises but cannot decode throws an
  `UnsupportedImageException` naming the exact feature, and `probe` says so up
  front, so you can route it elsewhere instead of catching a failure.
- **Hostile-input guards.** Dimension and decompression-bomb limits, an
  input-relative budget so a corrupt header can't size a huge buffer, CRC
  verification on consumed PNG chunks, and truncation that surfaces as
  `ImageDecodeException` rather than an index crash. A seeded fuzz harness drives
  thousands of malformed files through every decoder on every CI run.
- **Checked against the real thing.** JPEG decode is bit-identical to stb_image,
  WebP lossless is bit-identical to libwebp's `dwebp`, JPEG 2000 is checked
  against OpenJPEG, and TIFF against files libtiff itself wrote.
- **Kite lineage.** The PNG flate paths are the same zlib ports that already ship in
  [KiteArchive](https://github.com/yuroyami/KiteArchive); references are ported
  clean-room from permissively-licensed canonical sources.

## Format support

Today, at v0.0.1:

| Format | Decode | Encode |
|---|---|---|
| PNG | ✅ everything: all colour types, depths, filters, Adam7 interlace | ✅ RGB/RGBA, filter heuristic |
| APNG | ✅ full animation: dispose and blend ops, frame rects, loop count | — |
| JPEG | ✅ baseline + extended sequential + **progressive**: restarts, 4:2:0/4:2:2/4:4:4/4:1:1, gray/YCbCr/RGB/CMYK/YCCK, EXIF orientation, bit-identical to stb_image | ✅ baseline, quality 1-100, 4:2:0/4:4:4 |
| GIF | ✅ 87a/89a incl. animation: full LZW, interlace, disposal compositing, delays, loop count | ✅ median-cut + dithering, stills and animations |
| BMP | ✅ every header version, depths 1-32, RLE4/RLE8, BITFIELDS with arbitrary masks | ✅ 24-bit, or 32-bit V4 when alpha is present |
| WebP | ✅ **lossless (VP8L)** + animation, bit-identical to libwebp; lossy (VP8) declined by name | — |
| TIFF | ✅ strips **and tiles**, raw/PackBits/LZW/Deflate/CCITT G3+G4, gray/RGB(A)/palette/**YCbCr**, 1-16 bit, predictor, **both planar configs** | — |
| JPEG 2000 | ✅ JP2/J2K part 1 (moved from KitePDF, OpenJPEG-oracle-tested) | — |
| JBIG2 / CCITT G3+G4 | ✅ parameterized codec APIs (scan-world formats without container magic) | — |
| AVIF / HEIC | out of scope (see [REFERENCES.md](reference/REFERENCES.md)) | — |

The full matrix with per-feature detail lives in [PORTING_STATUS.md](PORTING_STATUS.md).

## Compose

`kiteimage-compose` ships one composable. It decides on its own whether to animate.
Feed it anything:

```kotlin
KiteImage(
    data = bytes,                  // GIF plays; PNG/BMP draws. No branching.
    contentDescription = "avatar",
    modifier = Modifier.size(96.dp),
    // animate = false,            // escape hatch: pin the first frame (thumbnails)
    // onError = { log(it) },      // malformed input draws nothing + callback
)
```

Decoding runs off the UI thread. Animated inputs honor per-frame delays (with the
browser rule that clamps delays of 10 ms and under to 100 ms), disposal compositing,
and the NETSCAPE loop count. Finite loops end holding the last frame.
`KiteBitmap.toImageBitmap()` is public for custom pipelines, and an overload takes an
already-decoded `KiteBitmap`.

## Coil interop

`kiteimage-coil` splits the work the right way: **Coil owns the pipes** (network,
disk + memory cache, request lifecycle), **KiteImage owns the pixels**. Two pieces:

```kotlin
// 1. plug our codecs into Coil's pipeline
ImageLoader.Builder(context)
    .components { add(KiteImageDecoder.Factory()) }
    .build()

// 2. remote GIF, fetched + cached by Coil, animated by KiteImage, on every target
KiteAsyncImage(
    model = "https://example.com/reaction.gif",
    contentDescription = null,
)
```

Coil itself can't animate outside Android (its non-Android decode is a Skia
delegate, and coil-gif is an Android-only module), and it has no APNG or animated
WebP anywhere. `KiteImageDecoder` decides what to claim by asking `probe`, so it
takes exactly what these codecs handle (PNG and APNG, JPEG, GIF, BMP, lossless
and animated WebP, TIFF, JP2) and declines the rest (SVG, CgBI PNG, lossy WebP)
so Coil's platform decoders keep them and nothing regresses.

Unlike coil-gif's stateful drawable, `KiteAnimationImage` is stateless (playback
position lives in the composable), so animations under a configurable threshold
(`KiteImageDecoder.Factory(maxCacheableAnimationBytes = ...)`, default 64 MiB of
decoded frames) **do** use Coil's memory cache: scrolling a GIF back into view is
a cache hit, not a re-decode. Bigger animations fall back to re-decoding from the
disk cache so one huge file can't evict everything else. `KiteAsyncImage` also
feeds its layout constraints to the request as the target size, so still images
and *every animation frame* are box-filtered down to what's actually drawn.
Playback picks frames by elapsed frame-clock time: long loops don't drift, and
janky frames get skipped, not stretched. Plain `AsyncImage` still shows an
animation's first frame; `KiteAsyncImage` plays it.

## Sample

`./gradlew :sample:run` opens a desktop gallery: an animated GIF playing through the
composable, JPEG/JP2/TIFF/BMP tiles, and both encoders dogfooded at runtime.

## Install

Not yet published. Building from source works today:

```sh
./gradlew publishToMavenLocal
```

```kotlin
commonMain.dependencies {
    // the codecs: kotlin-stdlib and nothing else
    implementation("io.github.yuroyami:kiteimage:0.0.1-SNAPSHOT")
    // optional: the KiteImage() composable and KiteBitmap -> ImageBitmap
    implementation("io.github.yuroyami:kiteimage-compose:0.0.1-SNAPSHOT")
    // optional: the Coil decoder and KiteAsyncImage()
    implementation("io.github.yuroyami:kiteimage-coil:0.0.1-SNAPSHOT")
}
```

## Contributing

[CONTRIBUTING.md](CONTRIBUTING.md) covers the build, the reference oracles each
codec is checked against, and the one rule that matters: a decoder never trusts
its input. Security reporting is in [SECURITY.md](SECURITY.md), and the change
history is in [CHANGELOG.md](CHANGELOG.md).

## License & credits

Apache License 2.0. Ported clean-room from permissively-licensed references,
primarily [stb_image](https://github.com/nothings/stb) (public domain / MIT) and
[Apache Commons Imaging](https://github.com/apache/commons-imaging) (Apache-2.0).
The flate paths derive from zlib references via KiteArchive. Details in
[reference/REFERENCES.md](reference/REFERENCES.md) and [NOTICE](NOTICE).
