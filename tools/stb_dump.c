/*
 * Prints what stb_image decodes from a file, as one hex string of 8-bit RGB
 * samples, row by row. JpegDecoderTest's expected vectors come from it.
 *
 * The decoder follows stb_image bit for bit, with one deliberate exception:
 * stbi__resample_row_h_2 swaps the weights of the last two chroma samples of a
 * row, and ImageKodec gives the nearer sample the weight of three, as
 * libjpeg-turbo's h2v1_fancy_upsample does (#69). The vectors therefore come
 * from stb with that one line corrected:
 *
 *   curl -sSO https://raw.githubusercontent.com/nothings/stb/2c980bb59875b0d32144a71867fbdebb2f77cd20/stb_image.h
 *   sed -i 's/stbi__div4(input\[w-2\]\*3 + input\[w-1\] + 2)/stbi__div4(input[w-1]*3 + input[w-2] + 2)/' stb_image.h
 *   clang -O2 -DSTBI_NO_SIMD -o stb_dump tools/stb_dump.c -I. -lm
 *   ./stb_dump fixture.jpg
 *
 * STBI_NO_SIMD keeps the scalar kernels, which are the ones the Kotlin port
 * translates.
 */
#define STB_IMAGE_IMPLEMENTATION
#include "stb_image.h"
#include <stdio.h>

int main(int argc, char **argv) {
    if (argc != 2) {
        fprintf(stderr, "usage: %s image\n", argv[0]);
        return 2;
    }
    int w, h, n;
    unsigned char *rgb = stbi_load(argv[1], &w, &h, &n, 3);
    if (!rgb) {
        fprintf(stderr, "%s: %s\n", argv[1], stbi_failure_reason());
        return 1;
    }
    printf("%d %d\n", w, h);
    for (long i = 0; i < (long) w * h * 3; i++) printf("%02x", rgb[i]);
    printf("\n");
    stbi_image_free(rgb);
    return 0;
}
