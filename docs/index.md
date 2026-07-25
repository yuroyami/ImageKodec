<div class="kite-hero" markdown>

# KiteImage

Image codecs written in Kotlin, for Kotlin Multiplatform. Decode PNG, JPEG, GIF,
BMP, TIFF, JPEG 2000 and lossless WebP from a `ByteArray` — the same code on
Android, iOS, desktop, native, the browser and Wasm.

<div class="kite-hero-actions" markdown>
[Get started](#install){ .kite-primary }
[API reference](api/)
[GitHub](https://github.com/yuroyami/KiteImage)
</div>

</div>

Compose Multiplatform will draw an image anywhere. Getting the *pixels* is still
per-platform: `BitmapFactory` on Android, CoreGraphics on iOS, Skia on desktop,
the browser on web. So the moment common code needs real pixels — a thumbnail
hash, a server-side resize, the images inside a PDF — you are writing four
decoders again.

KiteImage decodes in common Kotlin. Every format normalises to
non-premultiplied ARGB_8888 in a plain `IntArray`, so palettes, grayscale, BGR
ordering and chroma subsampling are gone before you see anything. The core
artifact depends on `kotlin-stdlib` and nothing else.

## Install

Not published yet. Build and install it locally:

```bash
./gradlew publishToMavenLocal
```

```kotlin
commonMain.dependencies {
    implementation("io.github.yuroyami:kiteimage:0.0.1-SNAPSHOT")
}
```

The Compose and Coil bindings are separate, optional modules that build for
**7 targets against the core's 22** — check the README's target table before
adding them.

## Decode something

```kotlin
import io.github.yuroyami.kiteimage.KiteImage

val bitmap = KiteImage.decode(bytes)   // format sniffed from the magic bytes
val pixel = bitmap[10, 20]             // 0xAARRGGBB
```

Animations — GIF, APNG and animated WebP — come back through one shape, already
composited, so playback is "draw frame N, wait delay N":

```kotlin
val anim = KiteImage.decodeAnimation(bytes)
for (frame in anim.frames) draw(frame.bitmap, frame.delayMillis)
```

## Ask before you allocate

`probe` reads the header and nothing else — no pixel buffer, so it stays cheap
on a 50-megapixel file. Use it to size a layout, or to reject an upload by
dimension before it costs you memory.

```kotlin
val info = KiteImage.probe(bytes)
info.width; info.height          // as stored
info.displayWidth                // after EXIF orientation
info.frameCount; info.hasAlpha
info.isDecodable                 // and info.unsupportedReason when it is not
```

`isDecodable` reflects the decoders' actual feature refusals, so a TIFF using a
compression this build does not implement reports `false` up front rather than
throwing later.

## The thing to know before you commit

**WebP is lossless only.** VP8L still images and animations decode; lossy VP8
does not, and most `.webp` files on the open web are lossy. There is no WebP
encoder. If you are loading arbitrary images off the internet, that is the
limitation that will decide whether KiteImage fits.

Two more worth knowing early: TIFF reads the first IFD only, so a multi-page
TIFF silently gives you page 1; and 16-bit PNG samples are truncated to 8 bits.
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
<strong>Porting status</strong>
<span>What is ported from which reference, and what is deliberately out of scope.</span>
</a>

</div>
