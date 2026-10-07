/*
 * libavif's own reading of an AVIF file, through its built-in YUV to RGB conversion rather than
 * libyuv: avoidLibYUV set and chroma upsampled in its best quality (bilinear). AvifOracleTest
 * compiles this against the installed libavif and compares ImageKodec's samples with it.
 *
 *     cc -O2 -o avif_rgb tools/avif_rgb.c -lavif
 *     ./avif_rgb in.avif 8|16 out.raw
 *
 * The output is width, height and channel count as 32-bit little-endian integers, then the
 * samples row by row: one byte each at depth 8, two bytes little endian at depth 16. The image
 * comes without its clean aperture, rotation or mirror, as libavif leaves those to the caller.
 */
#include <avif/avif.h>
#include <stdio.h>
#include <stdlib.h>

static void put32(FILE * f, uint32_t v)
{
    unsigned char b[4] = { (unsigned char)v, (unsigned char)(v >> 8), (unsigned char)(v >> 16), (unsigned char)(v >> 24) };
    fwrite(b, 1, 4, f);
}

int main(int argc, char ** argv)
{
    if (argc != 4) {
        fprintf(stderr, "usage: %s in.avif 8|16 out.raw\n", argv[0]);
        return 2;
    }
    const int depth = atoi(argv[2]);
    avifDecoder * decoder = avifDecoderCreate();
    avifImage * image = avifImageCreateEmpty();
    avifResult result = avifDecoderReadFile(decoder, image, argv[1]);
    if (result != AVIF_RESULT_OK) {
        fprintf(stderr, "decode failed: %s\n", avifResultToString(result));
        return 1;
    }
    avifRGBImage rgb;
    avifRGBImageSetDefaults(&rgb, image);
    rgb.depth = (uint32_t)depth;
    rgb.format = image->alphaPlane ? AVIF_RGB_FORMAT_RGBA : AVIF_RGB_FORMAT_RGB;
    rgb.chromaUpsampling = AVIF_CHROMA_UPSAMPLING_BEST_QUALITY;
    rgb.avoidLibYUV = AVIF_TRUE;
    if (avifRGBImageAllocatePixels(&rgb) != AVIF_RESULT_OK) return 1;
    result = avifImageYUVToRGB(image, &rgb);
    if (result != AVIF_RESULT_OK) {
        fprintf(stderr, "conversion failed: %s\n", avifResultToString(result));
        return 1;
    }
    FILE * out = fopen(argv[3], "wb");
    if (!out) return 1;
    const uint32_t channels = image->alphaPlane ? 4 : 3;
    put32(out, rgb.width);
    put32(out, rgb.height);
    put32(out, channels);
    const uint32_t bytes = rgb.width * channels * (depth > 8 ? 2 : 1);
    for (uint32_t y = 0; y < rgb.height; ++y) fwrite(&rgb.pixels[y * rgb.rowBytes], 1, bytes, out);
    fclose(out);
    avifRGBImageFreePixels(&rgb);
    avifImageDestroy(image);
    avifDecoderDestroy(decoder);
    return 0;
}
