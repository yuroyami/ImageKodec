/*
 * Re-encodes a JPEG's quantized coefficients with arithmetic coding, as
 * `jpegtran -arithmetic` does, with the conditioning of the DAC marker set
 * from the command line, which jpegtran leaves at the T.81 defaults. The
 * coefficients do not change, so the output must decode to exactly the pixels
 * of the input.
 *
 *   cc -O2 -o arith_jpeg tools/arith_jpeg.c -ljpeg
 *   ./arith_jpeg L U K RESTART PROGRESSIVE < in.jpg > out.jpg
 *
 * L and U bound the DC conditioning (0 <= L <= U <= 15) and K the AC one
 * (1 to 63), for every table. RESTART is the restart interval in MCUs, 0 for
 * none, and PROGRESSIVE (0 or 1) writes libjpeg's default progression.
 */
#include <stdio.h>
#include <stdlib.h>
#include <jpeglib.h>

int main(int argc, char **argv) {
    if (argc != 6) {
        fprintf(stderr, "usage: %s L U K restart progressive < in.jpg > out.jpg\n", argv[0]);
        return 2;
    }
    int l = atoi(argv[1]), u = atoi(argv[2]), k = atoi(argv[3]);
    int restart = atoi(argv[4]), progressive = atoi(argv[5]);

    struct jpeg_decompress_struct in;
    struct jpeg_compress_struct out;
    struct jpeg_error_mgr inErr, outErr;
    in.err = jpeg_std_error(&inErr);
    jpeg_create_decompress(&in);
    jpeg_stdio_src(&in, stdin);
    jpeg_read_header(&in, TRUE);
    jvirt_barray_ptr *coefficients = jpeg_read_coefficients(&in);

    out.err = jpeg_std_error(&outErr);
    jpeg_create_compress(&out);
    jpeg_copy_critical_parameters(&in, &out);
    out.arith_code = TRUE;
    out.optimize_coding = FALSE;
    out.restart_interval = restart;
    if (progressive) jpeg_simple_progression(&out);
    for (int i = 0; i < NUM_ARITH_TBLS; i++) {
        out.arith_dc_L[i] = (UINT8) l;
        out.arith_dc_U[i] = (UINT8) u;
        out.arith_ac_K[i] = (UINT8) k;
    }
    jpeg_stdio_dest(&out, stdout);
    jpeg_write_coefficients(&out, coefficients);
    jpeg_finish_compress(&out);
    jpeg_destroy_compress(&out);
    jpeg_finish_decompress(&in);
    jpeg_destroy_decompress(&in);
    return 0;
}
