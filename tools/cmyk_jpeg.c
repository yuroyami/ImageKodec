/*
 * Writes a four-component JPEG with libjpeg from raw samples, so the CMYK and
 * YCCK tests have files that an independent encoder made from known values.
 *
 *   cc -O2 -o cmyk_jpeg tools/cmyk_jpeg.c -ljpeg
 *   ./cmyk_jpeg WIDTH HEIGHT MODE QUALITY < samples > out.jpg
 *
 * The input holds WIDTH * HEIGHT * 4 bytes, four per pixel, row by row. MODE
 * picks what the file stores and its Adobe marker:
 *
 *   cmyk         the samples as given, Adobe transform 0
 *   ycck         the samples as CMYK, which libjpeg stores as YCCK, Adobe transform 2
 *   cmyk-bare    the samples as given, with no Adobe marker
 *
 * The output uses no subsampling, so a decoder's components can be compared
 * with the input sample by sample.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <jpeglib.h>

int main(int argc, char **argv) {
    if (argc != 5) {
        fprintf(stderr, "usage: %s width height cmyk|ycck|cmyk-bare quality\n", argv[0]);
        return 2;
    }
    int w = atoi(argv[1]), h = atoi(argv[2]), quality = atoi(argv[4]);
    const char *mode = argv[3];
    size_t size = (size_t) w * h * 4;
    unsigned char *samples = malloc(size);
    if (!samples || fread(samples, 1, size, stdin) != size) {
        fprintf(stderr, "expected %zu bytes of samples\n", size);
        return 1;
    }

    struct jpeg_compress_struct cinfo;
    struct jpeg_error_mgr jerr;
    cinfo.err = jpeg_std_error(&jerr);
    jpeg_create_compress(&cinfo);
    jpeg_stdio_dest(&cinfo, stdout);
    cinfo.image_width = w;
    cinfo.image_height = h;
    cinfo.input_components = 4;
    cinfo.in_color_space = JCS_CMYK;
    jpeg_set_defaults(&cinfo);
    if (strcmp(mode, "ycck") == 0) {
        jpeg_set_colorspace(&cinfo, JCS_YCCK);
    } else if (strcmp(mode, "cmyk-bare") == 0) {
        cinfo.write_Adobe_marker = FALSE;
    } else if (strcmp(mode, "cmyk") != 0) {
        fprintf(stderr, "unknown mode %s\n", mode);
        return 2;
    }
    for (int c = 0; c < 4; c++) {
        cinfo.comp_info[c].h_samp_factor = 1;
        cinfo.comp_info[c].v_samp_factor = 1;
    }
    jpeg_set_quality(&cinfo, quality, TRUE);
    jpeg_start_compress(&cinfo, TRUE);
    while (cinfo.next_scanline < cinfo.image_height) {
        JSAMPROW row = samples + (size_t) cinfo.next_scanline * w * 4;
        jpeg_write_scanlines(&cinfo, &row, 1);
    }
    jpeg_finish_compress(&cinfo);
    jpeg_destroy_compress(&cinfo);
    free(samples);
    return 0;
}
