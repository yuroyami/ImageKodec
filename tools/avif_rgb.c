/*
 * libavif's own reading of an AVIF file, through its built-in YUV to RGB conversion rather than
 * libyuv: avoidLibYUV set and chroma upsampled in its best quality (bilinear). AvifOracleTest
 * compiles this against the installed libavif and compares ImageKodec's samples with it.
 *
 *     cc -O2 -o avif_rgb tools/avif_rgb.c -lavif
 *     ./avif_rgb in.avif 8|16 out.raw [all]
 *
 * The output is width, height and channel count as 32-bit little-endian integers, then the
 * samples row by row: one byte each at depth 8, two bytes little endian at depth 16. The image
 * comes without its clean aperture, rotation or mirror, as libavif leaves those to the caller.
 *
 * With "all", every frame of an image sequence is written, after its frame count, libavif's
 * repetition count (-1 infinite, -2 unknown) and its timescale: each frame its duration in
 * that timescale, then the frame as above.
 */
#include <avif/avif.h>
#include <stdio.h>
#include <stdlib.h>

static void put32(FILE * f, uint32_t v)
{
    unsigned char b[4] = { (unsigned char)v, (unsigned char)(v >> 8), (unsigned char)(v >> 16), (unsigned char)(v >> 24) };
    fwrite(b, 1, 4, f);
}

/* Writes [image] converted to RGB at [depth] bits. */
static int writeImage(FILE * out, const avifImage * image, int depth)
{
    avifRGBImage rgb;
    avifRGBImageSetDefaults(&rgb, image);
    rgb.depth = (uint32_t)depth;
    rgb.format = image->alphaPlane ? AVIF_RGB_FORMAT_RGBA : AVIF_RGB_FORMAT_RGB;
    rgb.chromaUpsampling = AVIF_CHROMA_UPSAMPLING_BEST_QUALITY;
    rgb.avoidLibYUV = AVIF_TRUE;
    if (avifRGBImageAllocatePixels(&rgb) != AVIF_RESULT_OK) return 1;
    avifResult result = avifImageYUVToRGB(image, &rgb);
    if (result != AVIF_RESULT_OK) {
        fprintf(stderr, "conversion failed: %s\n", avifResultToString(result));
        return 1;
    }
    const uint32_t channels = image->alphaPlane ? 4 : 3;
    put32(out, rgb.width);
    put32(out, rgb.height);
    put32(out, channels);
    const uint32_t bytes = rgb.width * channels * (depth > 8 ? 2 : 1);
    for (uint32_t y = 0; y < rgb.height; ++y) fwrite(&rgb.pixels[y * rgb.rowBytes], 1, bytes, out);
    avifRGBImageFreePixels(&rgb);
    return 0;
}

int main(int argc, char ** argv)
{
    if (argc != 4 && argc != 5) {
        fprintf(stderr, "usage: %s in.avif 8|16 out.raw [all]\n", argv[0]);
        return 2;
    }
    const int depth = atoi(argv[2]);
    const int all = argc == 5;
    avifDecoder * decoder = avifDecoderCreate();
    FILE * out = fopen(argv[3], "wb");
    if (!out) return 1;
    if (!all) {
        avifImage * image = avifImageCreateEmpty();
        avifResult result = avifDecoderReadFile(decoder, image, argv[1]);
        if (result != AVIF_RESULT_OK) {
            fprintf(stderr, "decode failed: %s\n", avifResultToString(result));
            return 1;
        }
        if (writeImage(out, image, depth) != 0) return 1;
        avifImageDestroy(image);
    } else {
        avifResult result = avifDecoderSetIOFile(decoder, argv[1]);
        if (result == AVIF_RESULT_OK) result = avifDecoderParse(decoder);
        if (result != AVIF_RESULT_OK) {
            fprintf(stderr, "parse failed: %s\n", avifResultToString(result));
            return 1;
        }
        put32(out, (uint32_t)decoder->imageCount);
        put32(out, (uint32_t)decoder->repetitionCount);
        put32(out, (uint32_t)decoder->timescale);
        while ((result = avifDecoderNextImage(decoder)) == AVIF_RESULT_OK) {
            put32(out, (uint32_t)decoder->imageTiming.durationInTimescales);
            if (writeImage(out, decoder->image, depth) != 0) return 1;
        }
        if (result != AVIF_RESULT_NO_IMAGES_REMAINING) {
            fprintf(stderr, "decode failed: %s\n", avifResultToString(result));
            return 1;
        }
    }
    fclose(out);
    avifDecoderDestroy(decoder);
    return 0;
}
