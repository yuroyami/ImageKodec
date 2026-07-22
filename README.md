# KiteImage

[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/docs/multiplatform.html)
[![Targets](https://img.shields.io/badge/targets-Android%20|%20iOS%20|%20macOS%20|%20JVM%20|%20JS%20|%20WASM-success)](#install)
[![Core deps](https://img.shields.io/badge/core%20dependencies-kotlin--stdlib%20only-blue)](#why-kiteimage)
[![Ported from](https://img.shields.io/badge/ports-stb__image%20·%20commons--imaging-orange)](reference/REFERENCES.md)
[![License](https://img.shields.io/badge/license-Apache--2.0-lightgrey)](#license--credits)
[![Status](https://img.shields.io/badge/status-PNG%20%2F%20BMP%20%2F%20GIF%20%2F%20JPEG%20decode-brightgreen)](PORTING_STATUS.md)
[![Tests](https://img.shields.io/badge/tests-192%20passing-brightgreen)](PORTING_STATUS.md)

**A pure-Kotlin image codec toolkit for Kotlin Multiplatform: from-scratch ports of the canonical references (stb_image, commons-imaging). The same `.kt` decodes on Android, iOS, desktop JVM, the browser and WASM. No BitmapFactory, no CoreGraphics, no native binary.**

```kotlin
// decode: sniffs the format from magic bytes, returns packed ARGB
val bitmap: KiteBitmap = KiteImage.decode(bytes)
bitmap.width; bitmap.height
bitmap[x, y]                    // 0xAARRGGBB

// animations: every frame arrives fully composited (disposal methods,
// transparency and frame offsets already applied), so playback is just
// "draw frame N, wait delay N"
val anim: KiteAnimation = KiteImage.decodeAnimation(bytes)
anim.frames.forEach { frame -> frame.bitmap; frame.delayMillis }
anim.loopCount                  // NETSCAPE semantics: 0 = forever
anim.isAnimated                 // false for static formats (single frame)

// encode
KiteImage.encodePng(bitmap)             // lossless, RGB/RGBA auto
KiteImage.encodeJpeg(bitmap, quality = 85)

// resize (box filter, never upscales)
bitmap.scaled(maxWidth = 256, maxHeight = 256)

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
- **Wide sniffing, honest decoding.** `detect` recognises more formats than `decode`
  handles, so "that's a WebP, and this build can't decode WebP yet" beats "unknown
  format". Unsupported features throw `UnsupportedImageException` naming the feature.
- **Hostile-input guards.** Dimension and decompression-bomb limits, CRC verification
  on consumed PNG chunks, and truncation surfaces as `ImageDecodeException`, never an
  index crash. Malformed-input behavior is part of the test suite.
- **Kite lineage.** The PNG flate paths are the same zlib ports that already ship in
  [KiteArchive](https://github.com/yuroyami/KiteArchive); references are ported
  clean-room from permissively-licensed canonical sources.

## Format support

Today, at v0.0.1:

| Format | Decode | Encode |
|---|---|---|
| PNG | ✅ everything: all color types, depths, filters, Adam7 interlace | ✅ RGB/RGBA, filter heuristic |
| BMP | ✅ 8/24/32-bit BI_RGB, both row orders | roadmap |
| GIF | ✅ 87a/89a incl. animation: full LZW, interlace, disposal compositing, delays, loop count | roadmap |
| JPEG | ✅ baseline + extended sequential + **progressive**: restarts, 4:2:0/4:2:2/4:4:4/4:1:1, gray/YCbCr/RGB/CMYK/YCCK, bit-identical to stb_image | ✅ baseline, quality 1-100, 4:2:0/4:4:4 |
| JPEG 2000 | ✅ JP2/J2K part 1 (moved from KitePDF, OpenJPEG-oracle-tested) | — |
| JBIG2 / CCITT G3+G4 | ✅ parameterized codec APIs (scan-world formats without container magic) | — |
| TIFF | ✅ baseline strips: raw/PackBits/LZW/Deflate/CCITT G3+G4, gray/RGB(A)/palette/1-bit, predictor | — |
| WebP | recognised, not decoded | — |
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
delegate, and coil-gif is an Android-only module). `KiteImageDecoder` claims only
what KiteImage fully decodes: GIF, JPEG (baseline + progressive), TIFF, JP2 and the
supported PNG/BMP subsets. It declines the rest (SVG, CgBI PNG, exotic BMPs) so
Coil's platform decoders keep them and nothing regresses. Animated results skip
Coil's memory cache (`shareable = false`, the same tradeoff coil-gif makes) and
re-decode from disk cache. Plain `AsyncImage` still shows their first frame;
`KiteAsyncImage` plays them.

## Sample

`./gradlew :sample:run` opens a desktop gallery: an animated GIF playing through the
composable, JPEG/JP2/TIFF/BMP tiles, and both encoders dogfooded at runtime.

## Install

Not yet published. Building from source works today:

```sh
./gradlew :kiteimage:publishToMavenLocal
```

```kotlin
commonMain.dependencies {
    implementation("io.github.yuroyami:kiteimage:0.0.1-SNAPSHOT")
}
```

## License & credits

Apache License 2.0. Ported clean-room from permissively-licensed references,
primarily [stb_image](https://github.com/nothings/stb) (public domain / MIT) and
[Apache Commons Imaging](https://github.com/apache/commons-imaging) (Apache-2.0).
The flate paths derive from zlib references via KiteArchive. Details in
[reference/REFERENCES.md](reference/REFERENCES.md) and [NOTICE](NOTICE).
