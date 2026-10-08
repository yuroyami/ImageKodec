/*
 * The colour-management oracle: builds ICC profiles with lcms2 and converts samples
 * through them to lcms2's built-in sRGB, in floating point with no optimization, so
 * the tests can hold ImageKodec's conversion to it.
 *
 *   cc -O2 -o icc_lcms tools/icc_lcms.c -llcms2 -lm
 *   ./icc_lcms make KIND > profile.icc
 *   ./icc_lcms convert profile.icc INTENT CHANNELS [BYTES] < samples > rgb
 *
 * make writes a profile of KIND:
 *
 *   p3-v4          Display P3: P3 primaries, D65, the sRGB curve as parametric type 4
 *   adobe-v2       Adobe RGB (1998)'s primaries, D65, gamma 563/256 as a v2 curv
 *   prophoto-v2    ProPhoto: its primaries, D50, gamma 1.8
 *   rec2020-v4     BT.2020 primaries with BT.709's OETF inverted, parametric type 3
 *   para0-v4 .. para4-v4   wide primaries with one curve of each parametric type
 *   table-v2       BT.709 primaries with a 1024-entry curv table
 *   gray-v2        a gray profile with gamma 2.2
 *   gray-v4        a gray profile with the L* curve, parametric type 4
 *   cmyk-v2        a CMYK printer profile of lut16Type tables (mft2), Lab PCS, a
 *                  different table for each intent and a paper white
 *   cmyk-v4        the same as lutAToBType (mAB): curves, CLUT, curves, matrix, curves
 *   cmyk-tiny-v2, cmyk-tiny-v4   the same with a CLUT of 3 points a side, and no
 *                  curves (v2) or gamma curves (v4), small enough to keep in a test as text
 *   rgb-lut8-v2    an RGB input profile of lut8Type tables (mft1) with a Lab PCS
 *   rgb-xyz-v2     an RGB input profile of lut16Type tables with an XYZ PCS
 *   rgb-mab-v4     an RGB display profile of lutAToBType tables with a Lab PCS
 *
 * convert reads CHANNELS samples a pixel from stdin (1 gray, 3 RGB, 4 CMYK ink), each
 * of BYTES bytes, 1 (the default) or 2 in the machine's byte order, and writes three
 * float32 sRGB values a pixel, 0 to 1, in the machine's byte order. INTENT is
 * 0 perceptual, 1 relative colorimetric, 2 saturation, 3 absolute colorimetric.
 */
#include <lcms2.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static cmsHPROFILE rgb(double wx, double wy, const double p[6], cmsToneCurve *curve, double version) {
    cmsCIExyY white = { wx, wy, 1.0 };
    cmsCIExyYTRIPLE primaries = { { p[0], p[1], 1.0 }, { p[2], p[3], 1.0 }, { p[4], p[5], 1.0 } };
    cmsToneCurve *curves[3] = { curve, curve, curve };
    cmsHPROFILE h = cmsCreateRGBProfile(&white, &primaries, curves);
    cmsSetProfileVersion(h, version);
    return h;
}

static cmsToneCurve *para(int type, double g, double a, double b, double c, double d, double e, double f) {
    double params[7] = { g, a, b, c, d, e, f };
    return cmsBuildParametricToneCurve(NULL, type, params);
}

static const double P3[6] = { 0.680, 0.320, 0.265, 0.690, 0.150, 0.060 };
static const double ADOBE[6] = { 0.640, 0.330, 0.210, 0.710, 0.150, 0.060 };
static const double PROPHOTO[6] = { 0.7347, 0.2653, 0.1596, 0.8404, 0.0366, 0.0001 };
static const double BT2020[6] = { 0.708, 0.292, 0.170, 0.797, 0.131, 0.046 };
static const double BT709[6] = { 0.640, 0.330, 0.300, 0.600, 0.150, 0.060 };

/* What each intent's table does to a colour, so a test can tell which table was read. */
typedef struct { int intent; int lab; int v2; } Cargo;

/* A colour as a print might render it: L* lifted and compressed for perceptual, chroma
 * pushed for saturation, as it comes for relative colorimetric. */
static void render(double rgbLinear[3], int intent, cmsCIELab *lab) {
    static const double m[9] = {
        0.4360747, 0.3850649, 0.1430804,
        0.2225045, 0.7168786, 0.0606169,
        0.0139322, 0.0971045, 0.7141733,
    };
    cmsCIEXYZ xyz = {
        m[0] * rgbLinear[0] + m[1] * rgbLinear[1] + m[2] * rgbLinear[2],
        m[3] * rgbLinear[0] + m[4] * rgbLinear[1] + m[5] * rgbLinear[2],
        m[6] * rgbLinear[0] + m[7] * rgbLinear[1] + m[8] * rgbLinear[2],
    };
    cmsXYZ2Lab(NULL, lab, &xyz);
    if (intent == 0) { lab->L = 4 + lab->L * 0.94; lab->a *= 0.9; lab->b *= 0.9; }
    if (intent == 2) { lab->a *= 1.15; lab->b *= 1.15; }
}

static void encode(const cmsCIELab *lab, int asLab, int v2, cmsUInt16Number out[3]) {
    if (asLab) {
        if (v2) cmsFloat2LabEncodedV2(out, lab); else cmsFloat2LabEncoded(out, lab);
    } else {
        cmsCIEXYZ xyz;
        cmsLab2XYZ(NULL, &xyz, lab);
        cmsFloat2XYZEncoded(out, &xyz);
    }
}

static int cmykSampler(const cmsUInt16Number in[], cmsUInt16Number out[], void *cargo) {
    const Cargo *c = cargo;
    double k = in[3] / 65535.0, rgb[3];
    for (int i = 0; i < 3; i++) {
        double v = (1 - in[i] / 65535.0 * 0.97) * (1 - k * 0.95);
        rgb[i] = pow(v, 1.8);
    }
    cmsCIELab lab;
    render(rgb, c->intent, &lab);
    encode(&lab, c->lab, c->v2, out);
    return 1;
}

static int rgbSampler(const cmsUInt16Number in[], cmsUInt16Number out[], void *cargo) {
    const Cargo *c = cargo;
    double rgb[3];
    for (int i = 0; i < 3; i++) rgb[i] = pow(in[i] / 65535.0, 2.0) * 0.9 + 0.1 * in[(i + 1) % 3] / 65535.0;
    cmsCIELab lab;
    render(rgb, c->intent, &lab);
    encode(&lab, c->lab, c->v2, out);
    return 1;
}

static cmsPipeline *table(int inputs, int grid, int intent, int lab, int v2, int curves, int eightBit) {
    cmsPipeline *p = cmsPipelineAlloc(NULL, inputs, 3);
    cmsToneCurve *in[4], *out[3];
    if (curves) {
        // lut8Type holds 256-entry tables only.
        for (int i = 0; i < inputs; i++) {
            if (curves == 2) {
                // Parametric, which a lutAToBType keeps as a few numbers.
                in[i] = cmsBuildGamma(NULL, 1.0 + 0.1 * i);
            } else if (eightBit) {
                cmsUInt16Number t[256];
                for (int v = 0; v < 256; v++) t[v] = (cmsUInt16Number) floor(pow(v / 255.0, 1.0 + 0.1 * i) * 65535 + 0.5);
                in[i] = cmsBuildTabulatedToneCurve16(NULL, 256, t);
            } else {
                // Tabulated, all of one length: lcms2 writes a lut16Type with the first curve's
                // length in its header and each curve at its own, and a gamma of 1 has two entries.
                cmsUInt16Number t[1024];
                for (int v = 0; v < 1024; v++) t[v] = (cmsUInt16Number) floor(pow(v / 1023.0, 1.0 + 0.1 * i) * 65535 + 0.5);
                in[i] = cmsBuildTabulatedToneCurve16(NULL, 1024, t);
            }
        }
        cmsPipelineInsertStage(p, cmsAT_END, cmsStageAllocToneCurves(NULL, inputs, in));
    }
    Cargo cargo = { intent, lab, v2 };
    cmsStage *clut = cmsStageAllocCLut16bit(NULL, grid, inputs, 3, NULL);
    cmsStageSampleCLut16bit(clut, inputs == 4 ? cmykSampler : rgbSampler, &cargo, 0);
    cmsPipelineInsertStage(p, cmsAT_END, clut);
    if (curves) {
        for (int i = 0; i < 3; i++) {
            if (curves == 2) {
                out[i] = cmsBuildGamma(NULL, 1.0 - 0.02 * i);
                continue;
            }
            int n = eightBit ? 256 : 1024;
            cmsUInt16Number t[1024];
            for (int v = 0; v < n; v++) t[v] = (cmsUInt16Number) floor(pow(v / (n - 1.0), 1.0 - 0.02 * i) * 65535 + 0.5);
            out[i] = cmsBuildTabulatedToneCurve16(NULL, n, t);
        }
        cmsPipelineInsertStage(p, cmsAT_END, cmsStageAllocToneCurves(NULL, 3, out));
    }
    if (eightBit) cmsPipelineSetSaveAs8bitsFlag(p, TRUE);
    return p;
}

static cmsHPROFILE lutProfile(cmsColorSpaceSignature space, cmsProfileClassSignature cls, cmsColorSpaceSignature pcs,
                              double version, int grid, int curves, int eightBit) {
    cmsHPROFILE h = cmsCreateProfilePlaceholder(NULL);
    cmsSetProfileVersion(h, version);
    cmsSetDeviceClass(h, cls);
    cmsSetColorSpace(h, space);
    cmsSetPCS(h, pcs);
    int inputs = space == cmsSigCmykData ? 4 : 3;
    int lab = pcs == cmsSigLabData;
    int v2 = version < 4;
    cmsTagSignature tags[3] = { cmsSigAToB0Tag, cmsSigAToB1Tag, cmsSigAToB2Tag };
    for (int intent = 0; intent < 3; intent++) {
        cmsPipeline *p = table(inputs, grid, intent, lab, v2, curves, eightBit);
        cmsWriteTag(h, tags[intent], p);
        cmsPipelineFree(p);
    }
    cmsCIEXYZ paper = { 0.93, 0.955, 0.79 };
    cmsWriteTag(h, cmsSigMediaWhitePointTag, &paper);
    return h;
}

static int make(const char *kind) {
    cmsHPROFILE h = NULL;
    if (!strcmp(kind, "p3-v4")) {
        h = rgb(0.3127, 0.3290, P3, para(4, 2.4, 1 / 1.055, 0.055 / 1.055, 1 / 12.92, 0.04045, 0, 0), 4.3);
    } else if (!strcmp(kind, "adobe-v2")) {
        h = rgb(0.3127, 0.3290, ADOBE, cmsBuildGamma(NULL, 563.0 / 256.0), 2.1);
    } else if (!strcmp(kind, "prophoto-v2")) {
        h = rgb(0.3457, 0.3585, PROPHOTO, cmsBuildGamma(NULL, 1.8), 2.1);
    } else if (!strcmp(kind, "rec2020-v4")) {
        // BT.709's OETF inverted: (x + 0.099) / 1.099 to the 1/0.45 above 0.081, x / 4.5 below.
        h = rgb(0.3127, 0.3290, BT2020, para(4, 1 / 0.45, 1 / 1.099, 0.099 / 1.099, 1 / 4.5, 0.081, 0, 0), 4.3);
    } else if (!strncmp(kind, "para", 4) && strlen(kind) == 8) {
        int type = kind[4] - '0';
        cmsToneCurve *c = NULL;
        switch (type) {
        case 0: c = para(1, 2.2, 0, 0, 0, 0, 0, 0); break;
        case 1: c = para(2, 2.0, 0.95, 0.05, 0, 0, 0, 0); break;
        case 2: c = para(3, 2.0, 0.9, 0.08, 0.02, 0, 0, 0); break;
        case 3: c = para(4, 2.6, 0.92, 0.08, 0.1, 0.06, 0, 0); break;
        case 4: c = para(5, 2.3, 0.9, 0.1, 0.12, 0.08, 0.01, 0.005); break;
        default: return 2;
        }
        h = rgb(0.3127, 0.3290, P3, c, 4.3);
    } else if (!strcmp(kind, "table-v2")) {
        cmsUInt16Number t[1024];
        for (int i = 0; i < 1024; i++) {
            double x = i / 1023.0;
            double y = x < 0.081 ? x / 4.5 : pow((x + 0.099) / 1.099, 1 / 0.45);
            t[i] = (cmsUInt16Number) floor(y * 65535 + 0.5);
        }
        h = rgb(0.3127, 0.3290, BT709, cmsBuildTabulatedToneCurve16(NULL, 1024, t), 2.1);
    } else if (!strcmp(kind, "gray-v2")) {
        h = cmsCreateGrayProfile(cmsD50_xyY(), cmsBuildGamma(NULL, 2.2));
        cmsSetProfileVersion(h, 2.1);
    } else if (!strcmp(kind, "gray-v4")) {
        // CIE L* as a parametric type 4 curve.
        h = cmsCreateGrayProfile(cmsD50_xyY(), para(4, 3.0, 1 / 1.16, 0.16 / 1.16, 2700.0 / 24389.0, 0.08, 0, 0));
        cmsSetProfileVersion(h, 4.3);
    } else if (!strcmp(kind, "cmyk-v2")) {
        h = lutProfile(cmsSigCmykData, cmsSigOutputClass, cmsSigLabData, 2.1, 9, 1, 0);
    } else if (!strcmp(kind, "cmyk-v4")) {
        h = lutProfile(cmsSigCmykData, cmsSigOutputClass, cmsSigLabData, 4.3, 11, 1, 0);
    } else if (!strcmp(kind, "cmyk-tiny-v2")) {
        h = lutProfile(cmsSigCmykData, cmsSigOutputClass, cmsSigLabData, 2.1, 3, 0, 0);
    } else if (!strcmp(kind, "cmyk-tiny-v4")) {
        h = lutProfile(cmsSigCmykData, cmsSigOutputClass, cmsSigLabData, 4.3, 3, 2, 0);
    } else if (!strcmp(kind, "rgb-lut8-v2")) {
        h = lutProfile(cmsSigRgbData, cmsSigInputClass, cmsSigLabData, 2.1, 17, 1, 1);
    } else if (!strcmp(kind, "rgb-xyz-v2")) {
        h = lutProfile(cmsSigRgbData, cmsSigInputClass, cmsSigXYZData, 2.1, 17, 1, 0);
    } else if (!strcmp(kind, "rgb-mab-v4")) {
        h = lutProfile(cmsSigRgbData, cmsSigDisplayClass, cmsSigLabData, 4.3, 17, 1, 0);
    } else {
        fprintf(stderr, "unknown profile kind %s\n", kind);
        return 2;
    }
    cmsUInt32Number size = 0;
    cmsSaveProfileToMem(h, NULL, &size);
    unsigned char *mem = malloc(size);
    cmsSaveProfileToMem(h, mem, &size);
    fwrite(mem, 1, size, stdout);
    free(mem);
    cmsCloseProfile(h);
    return 0;
}

static int convert(const char *path, int intent, int channels, int bytes) {
    cmsHPROFILE in = cmsOpenProfileFromFile(path, "r");
    if (in == NULL) return 3;
    cmsHPROFILE out = cmsCreate_sRGBProfile();
    cmsUInt32Number format = bytes == 2
        ? (channels == 1 ? TYPE_GRAY_16 : channels == 3 ? TYPE_RGB_16 : TYPE_CMYK_16)
        : (channels == 1 ? TYPE_GRAY_8 : channels == 3 ? TYPE_RGB_8 : TYPE_CMYK_8);
    cmsHTRANSFORM x = cmsCreateTransform(in, format, out, TYPE_RGB_FLT, intent, cmsFLAGS_NOOPTIMIZE | cmsFLAGS_NOCACHE);
    if (x == NULL) return 4;
    unsigned char sample[8];
    float rgb[3];
    while (fread(sample, bytes, channels, stdin) == (size_t) channels) {
        cmsDoTransform(x, sample, rgb, 1);
        fwrite(rgb, sizeof(float), 3, stdout);
    }
    cmsDeleteTransform(x);
    cmsCloseProfile(in);
    cmsCloseProfile(out);
    return 0;
}

int main(int argc, char **argv) {
    if (argc == 3 && !strcmp(argv[1], "make")) return make(argv[2]);
    if ((argc == 5 || argc == 6) && !strcmp(argv[1], "convert")) {
        int bytes = argc == 6 ? atoi(argv[5]) : 1;
        if (bytes != 1 && bytes != 2) return 2;
        return convert(argv[2], atoi(argv[3]), atoi(argv[4]), bytes);
    }
    fprintf(stderr, "usage: %s make KIND | convert PROFILE INTENT CHANNELS [BYTES]\n", argv[0]);
    return 2;
}
