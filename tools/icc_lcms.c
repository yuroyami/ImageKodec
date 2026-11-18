/*
 * The colour-management oracle: builds ICC profiles with lcms2 and converts samples
 * through them to lcms2's built-in sRGB, in floating point with no optimization, so
 * the tests can hold ImageKodec's conversion to it.
 *
 *   cc -O2 -o icc_lcms tools/icc_lcms.c -llcms2 -lm
 *   ./icc_lcms make KIND > profile.icc
 *   ./icc_lcms make-rgb RX RY GX GY BX BY WX WY GAMMA > profile.icc
 *   ./icc_lcms convert-rgb RX RY GX GY BX BY WX WY GAMMA INTENT BYTES < samples > rgb
 *   ./icc_lcms convert profile.icc INTENT CHANNELS [BYTES] < samples > rgb
 *   ./icc_lcms convert-lab < lab > rgb
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
 *   gray-lab-v2    a gray profile with gamma 1.8 and a Lab PCS, so that its curve gives L*,
 *                  as icc-profiles-free's Gray-CIE_L.icc does with gamma 1
 *   cmyk-v2        a CMYK printer profile of lut16Type tables (mft2), Lab PCS, a
 *                  different table for each intent and a paper white
 *   cmyk-v4        the same as lutAToBType (mAB): curves, CLUT, curves, matrix, curves
 *   cmyk-tiny-v2, cmyk-tiny-v4   the same with a CLUT of 3 points a side, and no
 *                  curves (v2) or gamma curves (v4), small enough to keep in a test as text
 *   rgb-lut8-v2    an RGB input profile of lut8Type tables (mft1) with a Lab PCS
 *   rgb-xyz-v2     an RGB input profile of lut16Type tables with an XYZ PCS
 *   rgb-mab-v4     an RGB display profile of lutAToBType tables with a Lab PCS
 *
 * The two-way kinds hold an A2B0 and a B2A0 table of one colour space, wide primaries with a
 * gamma of 2.2, each the other's inverse, so a picture can be coded into the profile and read
 * back. Each table's curves are undone in its grid, so the curves and the grid both count:
 *
 *   io-lut8-v2     lut8Type (mft1), Lab PCS
 *   io-lut16-v2    lut16Type (mft2), Lab PCS in v2's encoding
 *   io-xyz-v2      lut16Type, XYZ PCS, the B2A table with a matrix ahead of its curves
 *   io-mab-v4      lutAToBType and lutBToAType, Lab PCS: curves, grid, curves
 *   io-mab-xyz-v4  the same with an XYZ PCS and every element: curves, matrix with an offset,
 *                  curves, grid, curves
 *   io-gray-v4     a gray profile of lutAToBType and lutBToAType, Lab PCS
 *
 * to-profile reads linear sRGB floats, three a pixel, and writes the profile's device values
 * as floats, relative colorimetric with no black point compensation.
 *
 * convert reads CHANNELS samples a pixel from stdin (1 gray, 3 RGB, 4 CMYK ink), each
 * of BYTES bytes, 1 (the default) or 2 in the machine's byte order, and writes three
 * float32 sRGB values a pixel, 0 to 1, in the machine's byte order. INTENT is
 * 0 perceptual, 1 relative colorimetric, 2 saturation, 3 absolute colorimetric.
 *
 * convert-lab reads L*, a* and b* as three float64 values a pixel and converts them through a
 * D50 Lab profile (cmsCreateLab4Profile), relative colorimetric, to float32 sRGB as convert does.
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

/* ---- Two-way table profiles: an analytic space and its inverse, each through a grid. ---- */

/* Linear RGB of wide primaries (Adobe RGB's, adapted to D50) to D50 XYZ. */
static const double IO_M[9] = {
    0.6097559, 0.2052401, 0.1492240,
    0.3111242, 0.6256560, 0.0632197,
    0.0194811, 0.0608902, 0.7448387,
};

typedef struct {
    int toPcs;       /* A2B, else B2A */
    int lab, v2;     /* the PCS, and v2's Lab encoding */
    int gray;
    int devCurves;   /* gamma curves on the device side, 1 + 0.1 i */
    int pcsCurves;   /* gamma curves on the PCS side, 1 - 0.05 i */
    int matrix;      /* a matrix on the PCS side: 1.9 v + offset into the grid of a B2A table, 0.5 v out of an A2B one */
    double offset;   /* 0.02 for a lutBToAType; a lut16Type's matrix has none */
    int mCurves;     /* gamma curves between that matrix and the grid, 1.2 */
} IoCargo;

static double devGamma(int i) { return 1.0 + 0.1 * i; }
static double pcsGamma(int i) { return 1.0 - 0.05 * i; }

/* D50 XYZ of device values. */
static void ioForward(const IoCargo *c, const double d[3], cmsCIEXYZ *xyz) {
    if (c->gray) {
        double y = pow(d[0], 2.2);
        xyz->X = 0.9642 * y; xyz->Y = y; xyz->Z = 0.8249 * y;
        return;
    }
    double l[3];
    for (int i = 0; i < 3; i++) l[i] = pow(d[i], 2.2);
    xyz->X = IO_M[0] * l[0] + IO_M[1] * l[1] + IO_M[2] * l[2];
    xyz->Y = IO_M[3] * l[0] + IO_M[4] * l[1] + IO_M[5] * l[2];
    xyz->Z = IO_M[6] * l[0] + IO_M[7] * l[1] + IO_M[8] * l[2];
}

/* Device values of D50 XYZ, clipped to the gamut. */
static void ioInverse(const IoCargo *c, const cmsCIEXYZ *xyz, double d[3]) {
    double l[3];
    if (c->gray) {
        l[0] = xyz->Y;
    } else {
        const double *m = IO_M;
        double t[9] = {
            m[4] * m[8] - m[5] * m[7], m[2] * m[7] - m[1] * m[8], m[1] * m[5] - m[2] * m[4],
            m[5] * m[6] - m[3] * m[8], m[0] * m[8] - m[2] * m[6], m[2] * m[3] - m[0] * m[5],
            m[3] * m[7] - m[4] * m[6], m[1] * m[6] - m[0] * m[7], m[0] * m[4] - m[1] * m[3],
        };
        double det = m[0] * t[0] + m[1] * t[3] + m[2] * t[6], v[3] = { xyz->X, xyz->Y, xyz->Z };
        for (int i = 0; i < 3; i++) l[i] = (t[3 * i] * v[0] + t[3 * i + 1] * v[1] + t[3 * i + 2] * v[2]) / det;
    }
    for (int i = 0; i < (c->gray ? 1 : 3); i++) {
        double x = l[i] < 0 ? 0 : l[i] > 1 ? 1 : l[i];
        d[i] = pow(x, 1 / 2.2);
    }
}

/* The PCS of [xyz] in the 0 to 1 encoding a pipeline carries, and back. */
static void pcsEncode(const IoCargo *c, const cmsCIEXYZ *xyz, double v[3]) {
    if (c->lab) {
        cmsCIELab lab;
        cmsXYZ2Lab(NULL, &lab, xyz);
        double f = c->v2 ? 65280.0 / 65535.0 : 1.0;
        v[0] = lab.L / 100 * f; v[1] = (lab.a + 128) / 255 * f; v[2] = (lab.b + 128) / 255 * f;
    } else {
        double s = 1 + 32767.0 / 32768.0;
        v[0] = xyz->X / s; v[1] = xyz->Y / s; v[2] = xyz->Z / s;
    }
}

static void pcsDecode(const IoCargo *c, const double v[3], cmsCIEXYZ *xyz) {
    if (c->lab) {
        double f = c->v2 ? 65535.0 / 65280.0 : 1.0;
        cmsCIELab lab = { v[0] * f * 100, v[1] * f * 255 - 128, v[2] * f * 255 - 128 };
        cmsLab2XYZ(NULL, xyz, &lab);
    } else {
        double s = 1 + 32767.0 / 32768.0;
        xyz->X = v[0] * s; xyz->Y = v[1] * s; xyz->Z = v[2] * s;
    }
}

static double clip01(double x) { return x < 0 ? 0 : x > 1 ? 1 : x; }

/*
 * A grid node: its inputs back through the curves and matrix before the grid to the table's
 * input, the analytic conversion, then forward through nothing but the inverse of what follows.
 */
static int ioSampler(const cmsUInt16Number in[], cmsUInt16Number out[], void *cargo) {
    const IoCargo *c = cargo;
    int devN = c->gray ? 1 : 3;
    double u[3], d[3] = { 0, 0, 0 }, v[3];
    cmsCIEXYZ xyz;
    if (c->toPcs) {
        for (int i = 0; i < devN; i++) {
            u[i] = in[i] / 65535.0;
            d[i] = c->devCurves ? pow(u[i], 1 / devGamma(i)) : u[i];
        }
        ioForward(c, d, &xyz);
        pcsEncode(c, &xyz, v);
        for (int i = 0; i < 3; i++) {
            if (c->pcsCurves) v[i] = pow(clip01(v[i]), 1 / pcsGamma(i));
            if (c->matrix) v[i] = v[i] / 0.5;
            if (c->mCurves) v[i] = pow(clip01(v[i]), 1 / 1.2);
            out[i] = (cmsUInt16Number) floor(clip01(v[i]) * 65535 + 0.5);
        }
    } else {
        for (int i = 0; i < 3; i++) {
            v[i] = in[i] / 65535.0;
            if (c->mCurves) v[i] = pow(v[i], 1 / 1.2);
            if (c->matrix) v[i] = (v[i] - c->offset) / 1.9;
            if (c->pcsCurves) v[i] = pow(clip01(v[i]), 1 / pcsGamma(i));
        }
        pcsDecode(c, v, &xyz);
        ioInverse(c, &xyz, d);
        for (int i = 0; i < devN; i++) {
            double y = c->devCurves ? pow(d[i], 1 / devGamma(i)) : d[i];
            out[i] = (cmsUInt16Number) floor(clip01(y) * 65535 + 0.5);
        }
    }
    return 1;
}

/* Gamma curves, tabulated at one length for a lut8Type or lut16Type, else parametric. */
static cmsStage *gammaCurves(int n, double (*gamma)(int), int tabulated, int eightBit) {
    cmsToneCurve *curves[3];
    for (int i = 0; i < n; i++) {
        double g = gamma(i);
        if (!tabulated) {
            curves[i] = para(1, g, 0, 0, 0, 0, 0, 0);
            continue;
        }
        int len = eightBit ? 256 : 1024;
        cmsUInt16Number t[1024];
        for (int k = 0; k < len; k++) t[k] = (cmsUInt16Number) floor(pow(k / (len - 1.0), g) * 65535 + 0.5);
        curves[i] = cmsBuildTabulatedToneCurve16(NULL, len, t);
    }
    return cmsStageAllocToneCurves(NULL, n, curves);
}

static double mGamma(int i) { (void) i; return 1.2; }

static cmsPipeline *ioTable(IoCargo c, int grid, int legacy, int eightBit) {
    int devN = c.gray ? 1 : 3;
    int in = c.toPcs ? devN : 3, out = c.toPcs ? 3 : devN;
    cmsPipeline *p = cmsPipelineAlloc(NULL, in, out);
    /* A lut8Type or lut16Type has curves on both sides of its grid, and a matrix only ahead of them. */
    if (c.toPcs) {
        if (c.devCurves) cmsPipelineInsertStage(p, cmsAT_END, gammaCurves(devN, devGamma, legacy, eightBit));
    } else {
        /* A lut16Type's matrix comes before its curves; a lutBToAType's after its B curves. */
        double m[9] = { 1.9, 0, 0, 0, 1.9, 0, 0, 0, 1.9 }, o[3] = { c.offset, c.offset, c.offset };
        if (c.matrix && legacy) cmsPipelineInsertStage(p, cmsAT_END, cmsStageAllocMatrix(NULL, 3, 3, m, NULL));
        if (c.pcsCurves) cmsPipelineInsertStage(p, cmsAT_END, gammaCurves(3, pcsGamma, legacy, eightBit));
        if (c.matrix && !legacy) cmsPipelineInsertStage(p, cmsAT_END, cmsStageAllocMatrix(NULL, 3, 3, m, o));
        if (c.mCurves) cmsPipelineInsertStage(p, cmsAT_END, gammaCurves(3, mGamma, 0, 0));
    }
    cmsStage *clut = cmsStageAllocCLut16bit(NULL, grid, in, out, NULL);
    cmsStageSampleCLut16bit(clut, ioSampler, &c, 0);
    cmsPipelineInsertStage(p, cmsAT_END, clut);
    if (c.toPcs) {
        if (c.mCurves) cmsPipelineInsertStage(p, cmsAT_END, gammaCurves(3, mGamma, 0, 0));
        if (c.matrix) {
            double m[9] = { 0.5, 0, 0, 0, 0.5, 0, 0, 0, 0.5 }, o[3] = { 0, 0, 0 };
            cmsPipelineInsertStage(p, cmsAT_END, cmsStageAllocMatrix(NULL, 3, 3, m, o));
        }
        if (c.pcsCurves) cmsPipelineInsertStage(p, cmsAT_END, gammaCurves(3, pcsGamma, legacy, eightBit));
    } else if (c.devCurves) {
        cmsPipelineInsertStage(p, cmsAT_END, gammaCurves(devN, devGamma, legacy, eightBit));
    }
    if (eightBit) cmsPipelineSetSaveAs8bitsFlag(p, TRUE);
    return p;
}

static cmsHPROFILE ioProfile(const char *kind) {
    IoCargo c = { 0 };
    int legacy = 1, eightBit = 0, grid = 17;
    double version = 2.1;
    if (!strcmp(kind, "io-lut8-v2")) {
        c.lab = 1; c.devCurves = 1; c.pcsCurves = 1; eightBit = 1;
    } else if (!strcmp(kind, "io-lut16-v2")) {
        c.lab = 1; c.v2 = 1; c.devCurves = 1; c.pcsCurves = 1;
    } else if (!strcmp(kind, "io-xyz-v2")) {
        c.devCurves = 1; c.pcsCurves = 1; c.matrix = 1;
    } else if (!strcmp(kind, "io-mab-v4")) {
        c.lab = 1; c.devCurves = 1; c.pcsCurves = 1; legacy = 0; version = 4.3;
    } else if (!strcmp(kind, "io-mab-xyz-v4")) {
        c.devCurves = 1; c.pcsCurves = 1; c.matrix = 1; c.mCurves = 1; legacy = 0; version = 4.3;
    } else if (!strcmp(kind, "io-gray-v4")) {
        c.lab = 1; c.gray = 1; c.devCurves = 1; c.pcsCurves = 1; legacy = 0; version = 4.3; grid = 33;
    } else {
        return NULL;
    }
    cmsHPROFILE h = cmsCreateProfilePlaceholder(NULL);
    cmsSetProfileVersion(h, version);
    cmsSetDeviceClass(h, cmsSigDisplayClass);
    cmsSetColorSpace(h, c.gray ? cmsSigGrayData : cmsSigRgbData);
    cmsSetPCS(h, c.lab ? cmsSigLabData : cmsSigXYZData);
    cmsSetHeaderRenderingIntent(h, INTENT_PERCEPTUAL);
    /* A lut16Type holds a matrix ahead of its device curves only, so the A2B table of io-xyz-v2 has none. */
    IoCargo a = c;
    a.toPcs = 1;
    if (legacy) a.matrix = 0;
    cmsPipeline *a2b = ioTable(a, grid, legacy, eightBit);
    c.offset = legacy ? 0 : 0.02;
    cmsPipeline *b2a = ioTable(c, grid, legacy, eightBit);
    cmsWriteTag(h, cmsSigAToB0Tag, a2b);
    cmsWriteTag(h, cmsSigBToA0Tag, b2a);
    cmsPipelineFree(a2b);
    cmsPipelineFree(b2a);
    cmsWriteTag(h, cmsSigMediaWhitePointTag, cmsD50_XYZ());
    return h;
}

/* Writes [h] to stdout and closes it. */
static int emit(cmsHPROFILE h) {
    cmsUInt32Number size = 0;
    cmsSaveProfileToMem(h, NULL, &size);
    unsigned char *mem = malloc(size);
    cmsSaveProfileToMem(h, mem, &size);
    fwrite(mem, 1, size, stdout);
    free(mem);
    cmsCloseProfile(h);
    return 0;
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
    } else if (!strcmp(kind, "gray-lab-v2")) {
        h = cmsCreateGrayProfile(cmsD50_xyY(), cmsBuildGamma(NULL, 1.8));
        cmsSetPCS(h, cmsSigLabData);
        cmsSetProfileVersion(h, 2.1);
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
    } else if ((h = ioProfile(kind)) == NULL) {
        fprintf(stderr, "unknown profile kind %s\n", kind);
        return 2;
    }
    return emit(h);
}

static int convertWith(cmsHPROFILE in, int intent, int channels, int bytes) {
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

static int convert(const char *path, int intent, int channels, int bytes) {
    cmsHPROFILE in = cmsOpenProfileFromFile(path, "r");
    if (in == NULL) return 3;
    return convertWith(in, intent, channels, bytes);
}

/* A v4 RGB profile of the primaries and white, and a power curve, or sRGB's curve for a gamma of 0. */
static cmsHPROFILE rgbOf(char **v) {
    double p[6];
    for (int i = 0; i < 6; i++) p[i] = atof(v[i]);
    double gamma = atof(v[8]);
    // cmsBuildGamma's curve is saved as a curv of one u8Fixed8 entry, 2.19998 becoming 2.19922;
    // lcms2's parametric type 1, ICC's para function 0, keeps the power as s15Fixed16.
    cmsToneCurve *curve = gamma == 0
        ? para(4, 2.4, 1 / 1.055, 0.055 / 1.055, 1 / 12.92, 0.04045, 0, 0)
        : para(1, gamma, 0, 0, 0, 0, 0, 0);
    return rgb(atof(v[6]), atof(v[7]), p, curve, 4.3);
}

static int convertLab(void) {
    cmsHPROFILE in = cmsCreateLab4Profile(NULL);
    cmsHPROFILE out = cmsCreate_sRGBProfile();
    cmsHTRANSFORM x = cmsCreateTransform(in, TYPE_Lab_DBL, out, TYPE_RGB_FLT, INTENT_RELATIVE_COLORIMETRIC, cmsFLAGS_NOOPTIMIZE | cmsFLAGS_NOCACHE);
    if (x == NULL) return 4;
    double lab[3];
    float rgb[3];
    while (fread(lab, sizeof(double), 3, stdin) == 3) {
        cmsDoTransform(x, lab, rgb, 1);
        fwrite(rgb, sizeof(float), 3, stdout);
    }
    cmsDeleteTransform(x);
    cmsCloseProfile(in);
    cmsCloseProfile(out);
    return 0;
}

/*
 * Linear sRGB floats on stdin, three a pixel, to the device values of PROFILE as floats, one a pixel
 * for gray and three for RGB, relative colorimetric: the way a JPEG XL decoder writes a lossy picture
 * into the profile it comes with.
 */
static int toProfile(const char *path, int channels) {
    cmsHPROFILE out = cmsOpenProfileFromFile(path, "r");
    if (out == NULL) return 3;
    const double p[6] = { 0.64, 0.33, 0.30, 0.60, 0.15, 0.06 };
    cmsHPROFILE in = rgb(0.3127, 0.3290, p, para(1, 1.0, 0, 0, 0, 0, 0, 0), 4.3);
    cmsHTRANSFORM x = cmsCreateTransform(in, TYPE_RGB_FLT, out, channels == 1 ? TYPE_GRAY_FLT : TYPE_RGB_FLT,
        INTENT_RELATIVE_COLORIMETRIC, cmsFLAGS_NOOPTIMIZE | cmsFLAGS_NOCACHE);
    if (x == NULL) return 4;
    float v[3], o[3];
    while (fread(v, sizeof(float), 3, stdin) == 3) {
        cmsDoTransform(x, v, o, 1);
        fwrite(o, sizeof(float), channels, stdout);
    }
    cmsDeleteTransform(x);
    cmsCloseProfile(in);
    cmsCloseProfile(out);
    return 0;
}

int main(int argc, char **argv) {
    if (argc == 4 && !strcmp(argv[1], "to-profile")) return toProfile(argv[2], atoi(argv[3]));
    if (argc == 2 && !strcmp(argv[1], "convert-lab")) return convertLab();
    if (argc == 3 && !strcmp(argv[1], "make")) return make(argv[2]);
    if (argc == 11 && !strcmp(argv[1], "make-rgb")) return emit(rgbOf(argv + 2));
    // Saving a profile rounds its colorants to s15Fixed16, so this converts through it unsaved.
    if (argc == 13 && !strcmp(argv[1], "convert-rgb")) return convertWith(rgbOf(argv + 2), atoi(argv[11]), 3, atoi(argv[12]));
    if ((argc == 5 || argc == 6) && !strcmp(argv[1], "convert")) {
        int bytes = argc == 6 ? atoi(argv[5]) : 1;
        if (bytes != 1 && bytes != 2) return 2;
        return convert(argv[2], atoi(argv[3]), atoi(argv[4]), bytes);
    }
    fprintf(stderr, "usage: %s make KIND | make-rgb RX RY GX GY BX BY WX WY GAMMA | convert-rgb ... GAMMA INTENT BYTES | convert PROFILE INTENT CHANNELS [BYTES] | to-profile PROFILE CHANNELS\n", argv[0]);
    return 2;
}
