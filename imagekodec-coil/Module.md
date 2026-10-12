# Module imagekodec-coil

A Coil 3 `Decoder` backed by ImageKodec, and the `KiteAsyncImage` composable.

It gives Coil the formats that Coil does not decode on every target. `KiteAsyncImage` plays GIF,
APNG, animated WebP, animated AVIF and animated JPEG XL, where a plain `AsyncImage` shows only the
first frame. The module brings Coil and Compose. Add `imagekodec-compose` too if you call
`KiteImage` or `KiteAnimatedImage` directly.
