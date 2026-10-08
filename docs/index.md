<div class="kite-hero" markdown>

# ImageKodec

Image codecs written in Kotlin, for Kotlin Multiplatform. Decode PNG, JPEG, GIF,
BMP, TIFF, JPEG 2000, WebP, AVIF and JPEG XL from a `ByteArray`. The same code runs on
Android, iOS, desktop, native, the browser and Wasm.

<div class="kite-hero-actions" markdown>
[Get started](#install){ .kite-primary }
[API reference](api/)
[GitHub](https://github.com/yuroyami/ImageKodec)
</div>

</div>

Compose Multiplatform will draw an image anywhere. Getting the *pixels* is still
per-platform: `BitmapFactory` on Android, CoreGraphics on iOS, Skia on desktop,
and the browser on web. If your common code needs real pixels, you must write one
decoder per platform. A thumbnail hash, a server-side resize and the images
inside a PDF all need this.

ImageKodec decodes in common Kotlin. Every format normalizes to non-premultiplied
ARGB_8888 in a plain `IntArray`. Non-premultiplied means the red, green and blue
channels hold the original color, not the color already multiplied by the alpha
value. ImageKodec resolves palettes, grayscale, BGR ordering and chroma
subsampling before it returns the pixels. Chroma subsampling means the file
stores color at a lower resolution than brightness.

The core artifact depends on `kotlin-stdlib` and nothing else.

## Install

This example describes the next 0.3.0 release; the new coordinates are not yet
published. For the current 0.2.0 release, use
`io.github.yuroyami:kiteimagecodec:0.2.0` and the
`io.github.yuroyami.kiteimagecodec` Kotlin package.

```kotlin
commonMain.dependencies {
    implementation("io.github.yuroyami:imagekodec:0.3.0")
}
```

The Compose and Coil bindings are separate, optional modules. They build for
**7 targets. The core builds for 22.** A project that targets `iosX64`, Linux,
Windows, tvOS, watchOS, androidNative or wasmWasi will resolve the core and then
fail to resolve those two modules. Read the README's target table first.

## Decode something

```kotlin
import io.github.yuroyami.imagekodec.ImageKodec

val bitmap = ImageKodec.decode(bytes)   // format sniffed from the magic bytes
val pixel = bitmap[10, 20]             // 0xAARRGGBB
```

One type covers GIF, APNG, animated WebP, animated AVIF and animated JPEG XL, already composited. Playback is
therefore "draw frame N, wait delay N":

```kotlin
val anim = ImageKodec.decodeAnimation(bytes)
for (frame in anim.frames) draw(frame.bitmap, frame.delayMillis)
```

## Read the header before you decode

`probe` reads the header and nothing else. It allocates no pixel buffer, so it
stays cheap on a 50-megapixel file. Use it to size a layout, or to reject an
upload by dimension before it allocates memory.

```kotlin
val info = ImageKodec.probe(bytes)
info.width; info.height          // as stored
info.displayWidth                // after EXIF orientation
info.frameCount; info.hasAlpha
info.isDecodable                 // and info.unsupportedReason when it is not
```

`isDecodable` reflects each decoder's real feature refusals. A TIFF that uses a
compression this build does not implement therefore reports `false` before you
decode, rather than throwing later.

## Check this before you choose ImageKodec

**WebP decodes, lossy and lossless, but does not encode.** Lossy files decode to
the pixels libwebp's `dwebp` writes. There is no WebP encoder.

PNG, TIFF, JPEG 2000, AVIF, JPEG XL and JPEG (12-bit or lossless) read files deeper than 8 bits. `decode` keeps the high
byte of each sample, and `decode16` keeps all of it, in a `KiteBitmap16`.

The README's Limits section has the full list.

## Where to go next

<div class="kite-cards" markdown>

<a class="kite-card" href="https://github.com/yuroyami/ImageKodec#readme">
<strong>README</strong>
<span>Per-format support, encoders, geometry helpers, the target tables and the full limits.</span>
</a>

<a class="kite-card" href="api/">
<strong>API reference</strong>
<span>Every public type, generated from source.</span>
</a>

<a class="kite-card" href="https://github.com/yuroyami/ImageKodec/issues">
<strong>Open issues</strong>
<span>Known gaps and what is planned, grouped by format and by area.</span>
</a>

</div>
