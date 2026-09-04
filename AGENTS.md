# Working on KiteImage

How to build, the test gate and the style rules: [CONTRIBUTING.md](CONTRIBUTING.md).
Open work: [GitHub Issues](https://github.com/yuroyami/KiteImage/issues).
Format support and known limits: the Limits section of [README.md](README.md).

## Gotchas

Things that already cost someone time. One line each. Delete a line when it stops
being true.

- The OpenJPEG, libtiff and libwebp suites skip themselves when their binary is
  missing, and a skipped test reports as a pass, so read the skip count and not
  only the green run (#14).
- JPEG 2000 does not use the shared `Budget` guard and keeps its own pixel
  ceiling in two places, so the probe and the decode can disagree about which
  files are too big (#2).
