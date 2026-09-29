# Module kiteimagecodec-coil

A Coil 3 `Decoder` backed by KiteImageCodec, plus `KiteAsyncImage`.

Gives Coil the formats it does not decode on every target, and plays animated
GIF, APNG and WebP where plain `AsyncImage` shows only the first frame. Pulls in
coil3 and Compose; add `kiteimagecodec-compose` too if you want `KiteAnimatedImage`.
