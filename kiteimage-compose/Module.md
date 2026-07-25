# Module kiteimage-compose

Compose Multiplatform bindings: a `KiteImage` composable that plays animations,
and `KiteBitmap.toImageBitmap`.

Note the name collision — `KiteImage` here is a `@Composable` function, while
`io.github.yuroyami.kiteimage.KiteImage` is the decoder object. Importing both
needs an alias.
