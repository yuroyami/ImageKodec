# Module kiteimage-coil

A Coil 3 `Decoder` backed by KiteImage, plus `KiteAsyncImage`.

Gives Coil the formats it does not decode on every target, and plays animated
GIF, APNG and WebP where plain `AsyncImage` shows only the first frame. Pulls in
coil3 and Compose; add `kiteimage-compose` too if you want `KiteAnimatedImage`.
