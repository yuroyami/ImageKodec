<div class="kite-hero" markdown>

# KiteImage

Image codecs written in Kotlin, for Kotlin Multiplatform. Decode PNG, JPEG, GIF,
BMP, TIFF, JPEG 2000 and lossless WebP from a `ByteArray`. The same code runs on
Android, iOS, desktop, native, the browser and Wasm.

<div class="kite-hero-actions" markdown>
[Get started](#install){ .kite-primary }
[API reference](api/)
[GitHub](https://github.com/yuroyami/KiteImage)
</div>

</div>

Compose Multiplatform will draw an image anywhere. Getting the *pixels* is still
per-platform: `BitmapFactory` on Android, CoreGraphics on iOS, Skia on desktop,
and the browser on web. If your common code needs real pixels, you must write one
decoder per platform. A thumbnail hash, a server-side resize and the images
inside a PDF all need this.

KiteImage decodes in common Kotlin. Every format normalizes to non-premultiplied
ARGB_8888 in a plain `IntArray`. Non-premultiplied means the red, green and blue
channels hold the original color, not the color already multiplied by the alpha
value. KiteImage resolves palettes, grayscale, BGR ordering and chroma
subsampling before it returns the pixels. Chroma subsampling means the file
stores color at a lower resolution than brightness.

The core artifact depends on `kotlin-stdlib` and nothing else.

## Install

```kotlin
commonMain.dependencies {
    implementation("io.github.yuroyami:kiteimage:0.1.0")
}
```

The Compose and Coil bindings are separate, optional modules. They build for
**7 targets. The core builds for 22.** A project that targets `iosX64`, Linux,
Windows, tvOS, watchOS, androidNative or wasmWasi will resolve the core and then
fail to resolve those two modules. Read the README's target table first.

## Decode something

```kotlin
import io.github.yuroyami.kiteimage.KiteImage

val bitmap = KiteImage.decode(bytes)   // format sniffed from the magic bytes
val pixel = bitmap[10, 20]             // 0xAARRGGBB
```

One type covers GIF, APNG and animated WebP, already composited. Playback is
therefore "draw frame N, wait delay N":

```kotlin
val anim = KiteImage.decodeAnimation(bytes)
for (frame in anim.frames) draw(frame.bitmap, frame.delayMillis)
```

## Read the header before you decode

`probe` reads the header and nothing else. It allocates no pixel buffer, so it
stays cheap on a 50-megapixel file. Use it to size a layout, or to reject an
upload by dimension before it allocates memory.

```kotlin
val info = KiteImage.probe(bytes)
info.width; info.height          // as stored
info.displayWidth                // after EXIF orientation
info.frameCount; info.hasAlpha
info.isDecodable                 // and info.unsupportedReason when it is not
```

`isDecodable` reflects each decoder's real feature refusals. A TIFF that uses a
compression this build does not implement therefore reports `false` before you
decode, rather than throwing later.

## Check this before you choose KiteImage

**WebP is lossless only.** VP8L still images and animations decode. Lossy VP8
does not, and most `.webp` files published on the internet are lossy. There is no
WebP encoder either. If you load arbitrary images from the internet, check this
limitation first.

Two more are worth knowing early:

- The TIFF decoder reads only the first IFD. A multi-page TIFF decodes to page 1
  and reports no error. IFD means Image File Directory, the record that describes
  one page of a TIFF.
- PNG and TIFF read 16-bit files, but KiteImage keeps only the high byte of each
  16-bit sample.

The README's Limits section has the full list.

## Where to go next

<div class="kite-cards" markdown>

<a class="kite-card" href="https://github.com/yuroyami/KiteImage#readme">
<strong>README</strong>
<span>Per-format support, encoders, geometry helpers, the target tables and the full limits.</span>
</a>

<a class="kite-card" href="api/">
<strong>API reference</strong>
<span>Every public type, generated from source.</span>
</a>

<a class="kite-card" href="https://github.com/yuroyami/KiteImage/blob/main/PORTING_STATUS.md">
<strong>Format status</strong>
<span>The support level for every format and feature, and what is deliberately out of scope.</span>
</a>

</div>
