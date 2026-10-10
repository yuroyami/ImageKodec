package io.github.yuroyami.imagekodec

import kotlin.io.encoding.Base64

/**
 * Four-component JPEGs that libjpeg wrote with `tools/cmyk_jpeg.c` from known samples, so the
 * CMYK and YCCK paths have files from an independent encoder.
 *
 * The blocks files are 32 by 16, eight flat 8 by 8 blocks of [BLOCKS] (stored values, row by
 * row of blocks), at quality 100 with no subsampling. The split files are 16 by 16 at quality 75:
 * C, M and Y stored as 128 everywhere, K stored as 0 on the left half and 255 on the right.
 */
internal object JpegCmykFixtures {

    /** The stored C, M, Y and K of each block, left to right, then the second row of blocks. */
    val BLOCKS: List<IntArray> = listOf(
        intArrayOf(0, 0, 0, 0), intArrayOf(255, 255, 255, 255), intArrayOf(200, 30, 90, 10), intArrayOf(15, 180, 60, 240),
        intArrayOf(128, 128, 128, 0), intArrayOf(128, 128, 128, 255), intArrayOf(70, 220, 5, 128), intArrayOf(250, 100, 170, 40),
    )

    /** `cmyk_jpeg 32 16 cmyk 100`: the samples as given, Adobe transform 0. */
    val blocksAdobeCmyk: ByteArray = Base64.decode(
        "/9j/7gAOQWRvYmUAZAAAAAAA/9sAQwABAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEB" +
        "AQEBAQEBAQEBAQEBAQEB/8AAFAgAEAAgBEMRAE0RAFkRAEsRAP/EAB8AAAEFAQEBAQEBAAAAAAAAAAABAgMEBQYHCAkKC//E" +
        "ALUQAAIBAwMCBAMFBQQEAAABfQECAwAEEQUSITFBBhNRYQcicRQygZGhCCNCscEVUtHwJDNicoIJChYXGBkaJSYnKCkqNDU2" +
        "Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6g4SFhoeIiYqSk5SVlpeYmZqio6Slpqeoqaqys7S1tre4ubrC" +
        "w8TFxsfIycrS09TV1tfY2drh4uPk5ebn6Onq8fLz9PX29/j5+v/aAA4EQwBNAFkASwAAPwD/AD/6/wA/+v8AP/r/AD/6/wB/" +
        "iv8Af4r/AH+K/wB/ivxHr/D3r/LXr/BXr/I3r/Swr4Pr/cwr+4ivxfr+iCv8H+iiiv8Af4r8L6/rgr+Cev4B6/1oK/g/r/Uo" +
        "r+T+v//Z",
    )

    /** `cmyk_jpeg 32 16 ycck 100`: libjpeg stores the samples as YCCK, Adobe transform 2. */
    val blocksAdobeYcck: ByteArray = Base64.decode(
        "/9j/7gAOQWRvYmUAZAAAAAAC/9sAQwABAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEB" +
        "AQEBAQEBAQEBAQEBAQEB/9sAQwEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEB" +
        "AQEBAQEBAQEBAQEB/8AAFAgAEAAgBAERAAIRAQMRAQQRAP/EAB8AAAEFAQEBAQEBAAAAAAAAAAABAgMEBQYHCAkKC//EALUQ" +
        "AAIBAwMCBAMFBQQEAAABfQECAwAEEQUSITFBBhNRYQcicRQygZGhCCNCscEVUtHwJDNicoIJChYXGBkaJSYnKCkqNDU2Nzg5" +
        "OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6g4SFhoeIiYqSk5SVlpeYmZqio6Slpqeoqaqys7S1tre4ubrCw8TF" +
        "xsfIycrS09TV1tfY2drh4uPk5ebn6Onq8fLz9PX29/j5+v/EAB8BAAMBAQEBAQEBAQEAAAAAAAABAgMEBQYHCAkKC//EALUR" +
        "AAIBAgQEAwQHBQQEAAECdwABAgMRBAUhMQYSQVEHYXETIjKBCBRCkaGxwQkjM1LwFWJy0QoWJDThJfEXGBkaJicoKSo1Njc4" +
        "OTpDREVGR0hJSlNUVVZXWFlaY2RlZmdoaWpzdHV2d3h5eoKDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLD" +
        "xMXGx8jJytLT1NXW19jZ2uLj5OXm5+jp6vLz9PX29/j5+v/aAA4EAQACEQMRBAAAPwD+/igD/P8A6/wB6AP9/iv9Tiuc/wCX" +
        "8/wV6+F6/wBCD/0yD/cwryev8/z/AJtz/B/ooA/3+K+R6/6kD/dA/gHrPr/kfP8AgHP5P6//2Q==",
    )

    /** `cmyk_jpeg 32 16 cmyk-bare 100`: the samples as given, with no Adobe marker. */
    val blocksBare: ByteArray = Base64.decode(
        "/9j/2wBDAAEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQH/" +
        "wAAUCAAQACAEQxEATREAWREASxEA/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQA" +
        "AAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVW" +
        "V1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ" +
        "2uHi4+Tl5ufo6erx8vP09fb3+Pn6/9oADgRDAE0AWQBLAAA/AP8AP/r/AD/6/wA/+v8AP/r/AH+K/wB/iv8Af4r/AH+K/Eev" +
        "8Pev8tev8Fev8jev9LCvg+v9zCv7iK/F+v6IK/wf6KKK/wB/ivwvr+uCv4J6/gHr/Wgr+D+v9Siv5P6//9k=",
    )

    /** `cmyk_jpeg 16 16 cmyk 75`: the split, Adobe transform 0. */
    val splitAdobeCmyk: ByteArray = Base64.decode(
        "/9j/7gAOQWRvYmUAZAAAAAAA/9sAQwAIBgYHBgUIBwcHCQkICgwUDQwLCwwZEhMPFB0aHx4dGhwcICQuJyAiLCMcHCg3KSww" +
        "MTQ0NB8nOT04MjwuMzQy/8AAFAgAEAAQBEMRAE0RAFkRAEsRAP/EAB8AAAEFAQEBAQEBAAAAAAAAAAABAgMEBQYHCAkKC//E" +
        "ALUQAAIBAwMCBAMFBQQEAAABfQECAwAEEQUSITFBBhNRYQcicRQygZGhCCNCscEVUtHwJDNicoIJChYXGBkaJSYnKCkqNDU2" +
        "Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6g4SFhoeIiYqSk5SVlpeYmZqio6Slpqeoqaqys7S1tre4ubrC" +
        "w8TFxsfIycrS09TV1tfY2drh4uPk5ebn6Onq8fLz9PX29/j5+v/aAA4EQwBNAFkASwAAPwAoor5/ooor7/ooor4Aooor7/r/" +
        "2Q==",
    )

    /** `cmyk_jpeg 16 16 cmyk-bare 75`: the split with no Adobe marker, the shape of #65. */
    val splitBare: ByteArray = Base64.decode(
        "/9j/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/" +
        "wAAUCAAQABAEQxEATREAWREASxEA/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQA" +
        "AAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVW" +
        "V1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ" +
        "2uHi4+Tl5ufo6erx8vP09fb3+Pn6/9oADgRDAE0AWQBLAAA/ACiivn+iiivv+iiivgCiiivv+v/Z",
    )
}
