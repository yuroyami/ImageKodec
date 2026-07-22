package io.github.yuroyami.kiteimage

/**
 * Image container formats KiteImage can *identify* from magic bytes. Sniffing
 * intentionally recognises more formats than [KiteImage.decode] currently
 * handles: "that's a WebP we can't decode yet" is a far better error than
 * "unknown format".
 */
public enum class ImageFormat {
    PNG,
    JPEG,
    GIF,
    BMP,
    WEBP,
    TIFF,
    JP2,
    ;

    public companion object {
        /**
         * Identify the format from the first bytes of [data], or null if no known
         * magic matches. Needs at most 12 bytes; shorter input is never an error,
         * just an unidentified one.
         */
        public fun sniff(data: ByteArray): ImageFormat? {
            fun at(i: Int): Int = if (i < data.size) data[i].toInt() and 0xFF else -1

            return when {
                // \x89 P N G \r \n \x1a \n
                at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 &&
                    at(4) == 0x0D && at(5) == 0x0A && at(6) == 0x1A && at(7) == 0x0A -> PNG

                // SOI marker FF D8; the third byte is always another FF marker lead-in.
                at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> JPEG

                // "GIF87a" / "GIF89a"
                at(0) == 'G'.code && at(1) == 'I'.code && at(2) == 'F'.code &&
                    at(3) == '8'.code && (at(4) == '7'.code || at(4) == '9'.code) &&
                    at(5) == 'a'.code -> GIF

                // "RIFF" .... "WEBP"
                at(0) == 'R'.code && at(1) == 'I'.code && at(2) == 'F'.code && at(3) == 'F'.code &&
                    at(8) == 'W'.code && at(9) == 'E'.code && at(10) == 'B'.code && at(11) == 'P'.code -> WEBP

                // "II*\0" (little-endian) or "MM\0*" (big-endian)
                at(0) == 'I'.code && at(1) == 'I'.code && at(2) == 0x2A && at(3) == 0x00 -> TIFF
                at(0) == 'M'.code && at(1) == 'M'.code && at(2) == 0x00 && at(3) == 0x2A -> TIFF

                // JPEG 2000: JP2 signature box (length 12, type "jP  ") or a raw
                // J2K codestream's SOC marker FF 4F.
                at(0) == 0x00 && at(1) == 0x00 && at(2) == 0x00 && at(3) == 0x0C &&
                    at(4) == 'j'.code && at(5) == 'P'.code && at(6) == ' '.code && at(7) == ' '.code -> JP2
                at(0) == 0xFF && at(1) == 0x4F -> JP2

                // "BM" last; two ASCII letters is the weakest magic of the set.
                at(0) == 'B'.code && at(1) == 'M'.code -> BMP

                else -> null
            }
        }
    }
}
