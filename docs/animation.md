# Animation

One type, `KiteAnimation`, covers GIF, APNG, animated WebP, animated AVIF and animated JPEG XL.
Every frame is a full composited canvas, with disposal, blending and frame offsets already applied.
Playback is therefore "draw frame N, wait delay N".

```kotlin
val anim = ImageKodec.decodeAnimation(bytes)
anim.frames.size
anim.loopCount        // Long total plays: 0 means forever, 1 means once
anim.durationMillis
for (frame in anim.frames) draw(frame.bitmap, frame.delayMillis)
```

A still image decodes as an animation of one frame.

## Decode less

A thumbnail or a paused image needs only the first frame. With `maxFrames = 1`, nothing past it is
read:

```kotlin
val first = ImageKodec.decodeAnimation(bytes, maxFrames = 1)
```

From a coroutine, pass a check that stops the decode between frames once the result no longer
matters. The check is not a suspend function, so read the context first:

```kotlin
val context = currentCoroutineContext()
val frames = ImageKodec.decodeAnimation(bytes) { context.ensureActive() }
```

## Play counts

`KiteAnimation.loopCount` and `ImageInfo.loopCount` are a `Long` that counts total plays. GIF
stores a repeat count, which ImageKodec turns into total plays.

When you encode a GIF, one play omits the loop extension. The GIF writer accepts total counts in
0..65536 and refuses a larger count instead of changing it. APNG keeps its full four-byte play
field. That includes counts above the integer limit of the PNG third edition, as a compatibility
extension for writers such as Pillow that already produce them.

## Frame delays

A delay of 10 ms or less is reported as 100 ms, which matches browsers. So an APNG `fcTL` with
`delay_num = 0` gives a 100 ms frame.

`KiteFrame.delayRawCentiseconds` is the exact figure that a GIF stated. APNG, WebP, AVIF and JPEG
XL store no centiseconds, so for them the figure comes from the stated delay.

## GIF background disposal

GIF "restore to background" clears to transparent instead of to the declared background color.
That matches browsers instead of the text of the 1989 specification.
