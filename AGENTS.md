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
- The JPEG 2000 probe does not check the shared `Budget` guard that the decoder
  checks, and both keep their own pixel ceiling, so the probe can call a file
  decodable that the decoder refuses (#2).
- A format with no file in the `FuzzTest` corpus is not fuzzed at all. JPEG 2000
  had none, so a packet header cut off by the end of the data looped forever and
  no test noticed.
