<div class="kite-hero" markdown>

# ImageKodec

Image codecs written in Kotlin, for Kotlin Multiplatform. Decode PNG, JPEG, GIF, BMP, TIFF,
JPEG 2000, WebP, AVIF and JPEG XL from a `ByteArray`. The same code runs on Android, iOS, desktop,
native, the browser and Wasm.

<div class="kite-hero-actions" markdown>
[Get started](#install){ .kite-primary }
[API reference](api/)
[GitHub](https://github.com/yuroyami/ImageKodec)
</div>

</div>

Compose Multiplatform draws an image on every target, but getting the pixels still goes through
the platform: `BitmapFactory` on Android, CoreGraphics on iOS, Skia on desktop and the browser on
web. Common code that needs real pixels (a thumbnail hash, a server-side resize, the images inside
a PDF) therefore has to call per-platform code.

ImageKodec decodes in common Kotlin instead. Every format comes back as non-premultiplied ARGB_8888
in a plain `IntArray`, and the core artifact depends on `kotlin-stdlib` and nothing else.

## Install

```kotlin
commonMain.dependencies {
    implementation("io.github.yuroyami:imagekodec:0.3.0")
}
```

The Compose and Coil bindings are separate, optional modules that build for 7 of the 22 targets
of the core. Read [Compose and Coil](compose-and-coil.md) before you add them.

## Decode something

```kotlin
import io.github.yuroyami.imagekodec.ImageKodec

val info = ImageKodec.probe(bytes)      // the header alone, no pixel buffer
val bitmap = ImageKodec.decode(bytes)   // format sniffed from the magic bytes
val pixel = bitmap[10, 20]              // 0xAARRGGBB
```

GIF, APNG, animated WebP, animated AVIF and animated JPEG XL all come back as one type, with every
frame already composited:

```kotlin
val anim = ImageKodec.decodeAnimation(bytes)
for (frame in anim.frames) draw(frame.bitmap, frame.delayMillis)
```

## Guides

<div class="kite-cards" markdown>

<a class="kite-card" href="decoding/">
<strong>Reading images</strong>
<span>Probe a header, decode a still, what each format decodes, TIFF pages and 16-bit samples.</span>
</a>

<a class="kite-card" href="color/">
<strong>Color management</strong>
<span>Convert to sRGB through ICC profiles, PNG color chunks and HDR tone mapping.</span>
</a>

<a class="kite-card" href="animation/">
<strong>Animation</strong>
<span>Composited frames, play counts, frame delays and decoding only the first frame.</span>
</a>

<a class="kite-card" href="encoding/">
<strong>Writing images</strong>
<span>PNG, APNG, JPEG, GIF, BMP, TIFF and lossless WebP.</span>
</a>

<a class="kite-card" href="resizing/">
<strong>Resizing and orientation</strong>
<span>Rotate, crop and scale, or decode a JPEG straight to a smaller size.</span>
</a>

<a class="kite-card" href="compose-and-coil/">
<strong>Compose and Coil</strong>
<span>The KiteImage composable and the Coil 3 decoder.</span>
</a>

<a class="kite-card" href="limits/">
<strong>Limits</strong>
<span>What ImageKodec does not do, and the bounds it puts on hostile input.</span>
</a>

<a class="kite-card" href="api/">
<strong>API reference</strong>
<span>Every public type, generated from source.</span>
</a>

</div>
