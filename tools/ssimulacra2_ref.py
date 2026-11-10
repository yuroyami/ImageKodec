#!/usr/bin/env python3
"""A plain full-plane transcription of libjxl's tools/ssimulacra2.cc in NumPy, float32 like the
reference, for SsimulacraPlainTest (#134). It checks ImageKodec's streaming port against the
straightforward order of the reference: every plane whole, blurred by rows and then by columns.

Usage: ssimulacra2_ref.py reference.png distorted.png  -> prints the score
"""
import math
import sys

import numpy as np
from PIL import Image

f32 = np.float32


def gaussian(sigma=1.5):
    radius = round(3.2795 * sigma + 0.2546)
    pi_div_2r = math.pi / (2.0 * radius)
    omega = [pi_div_2r, 3.0 * pi_div_2r, 5.0 * pi_div_2r]
    p1, p3, p5 = 1.0 / math.tan(0.5 * omega[0]), -1.0 / math.tan(0.5 * omega[1]), 1.0 / math.tan(0.5 * omega[2])
    r1, r3, r5 = p1 * p1 / math.sin(omega[0]), -p3 * p3 / math.sin(omega[1]), p5 * p5 / math.sin(omega[2])
    rho = [math.exp(-0.5 * sigma * sigma * w * w) / radius for w in omega]
    d13 = p1 * r3 - r1 * p3
    d35 = p3 * r5 - r3 * p5
    d51 = p5 * r1 - r5 * p1
    z15, z35 = d35 / d13, d51 / d13
    a = np.linalg.inv(np.array([[p1, p3, p5], [r1, r3, r5], [z15, z35, 1.0]]))
    beta = a @ np.array([1.0, radius * radius - sigma * sigma, z15 * rho[0] + z35 * rho[1] + rho[2]])
    n2 = [f32(-beta[i] * math.cos(omega[i] * (radius + 1.0))) for i in range(3)]
    d1 = [f32(-2.0 * math.cos(omega[i])) for i in range(3)]
    return radius, n2, d1


RADIUS, N2, D1 = gaussian()


def blur_axis0(plane):
    """The recursive filter down axis 0, zero outside, all columns at once."""
    n = RADIUS
    size = plane.shape[0]
    out = np.zeros_like(plane)
    prev = [np.zeros(plane.shape[1:], f32) for _ in range(3)]
    prev2 = [np.zeros(plane.shape[1:], f32) for _ in range(3)]
    zero = np.zeros(plane.shape[1:], f32)
    for i in range(-n + 1, size):
        left, right = i - n - 1, i + n - 1
        s = (plane[left] if left >= 0 else zero) + (plane[right] if right < size else zero)
        total = None
        for k in range(3):
            y = (N2[k] * s - prev2[k] - D1[k] * prev[k]).astype(f32)
            prev2[k], prev[k] = prev[k], y
            total = y if total is None else total + y
        if i >= 0:
            out[i] = prev[0] + (prev[1] + prev[2])
    return out


def blur(plane):
    return blur_axis0(blur_axis0(plane.T).T)


def to_linear(img, bg):
    v = np.asarray(img.convert('RGBA'), dtype=np.float64)
    a = (v[..., 3:] / 255.0).astype(f32)
    c = v[..., :3] / 255.0
    # AlphaBlend, in float32 on the encoded values, before the transfer to linear.
    blended = a * c.astype(f32) + (f32(1) - a) * f32(bg)
    c = np.where(a < 1.0, blended.astype(np.float64), c)
    lin = np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
    return [lin[..., i].astype(f32) for i in range(3)]


M = [[f32(0.30), f32(1.0) - f32(0.078) - f32(0.30), f32(0.078)],
     [f32(0.23), f32(1.0) - f32(0.078) - f32(0.23), f32(0.078)],
     [f32(0.24342268924547819), f32(0.20476744424496821), f32(1.0) - f32(0.24342268924547819) - f32(0.20476744424496821)]]
BIAS = f32(0.0037930732552754493)


def to_xyb(rgb):
    r, g, b = rgb
    mixed = [np.maximum(M[i][0] * r + (M[i][1] * g + (M[i][2] * b + BIAS)), f32(0)) for i in range(3)]
    l, m, s = [(np.cbrt(x) - np.cbrt(BIAS)).astype(f32) for x in mixed]
    x = f32(0.5) * (l - m)
    y = f32(0.5) * (l + m)
    return [x * f32(14) + f32(0.42), y + f32(0.01), (s - y) + f32(0.55)]


def downsample(plane):
    h, w = plane.shape
    ys = np.minimum(np.arange((h + 1) // 2 * 2), h - 1)
    xs = np.minimum(np.arange((w + 1) // 2 * 2), w - 1)
    p = plane[ys][:, xs]
    return ((p[0::2, 0::2] + p[0::2, 1::2]) + p[1::2, 0::2] + p[1::2, 1::2]) * f32(0.25)


# The 108 weights of Msssim::Score.
WEIGHTS = [0.0, 0.0007376606707406586, 0.0, 0.0, 0.0007793481682867309, 0.0, 0.0,
    0.0004371155730107379, 0.0, 1.1041726426657346, 0.00066284834129271, 0.00015231632783718752,
    0.0, 0.0016406437456599754, 0.0, 1.8422455520539298, 11.441172603757666, 0.0,
    0.0007989109436015163, 0.000176816438078653, 0.0, 1.8787594979546387, 10.94906990605142, 0.0,
    0.0007289346991508072, 0.9677937080626833, 0.0, 0.00014003424285435884, 0.9981766977854967,
    0.00031949755934435053, 0.0004550992113792063, 0.0, 0.0, 0.0013648766163243398, 0.0, 0.0, 0.0,
    0.0, 0.0, 7.466890328078848, 0.0, 17.445833984131262, 0.0006235601634041466, 0.0, 0.0,
    6.683678146179332, 0.00037724407979611296, 1.027889937768264, 225.20515300849274, 0.0, 0.0,
    19.213238186143016, 0.0011401524586618361, 0.001237755635509985, 176.39317598450694, 0.0, 0.0,
    24.43300999870476, 0.28520802612117757, 0.0004485436923833408, 0.0, 0.0, 0.0,
    34.77906344483772, 44.835625328877896, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
    0.0008680556573291698, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0005313191874358747, 0.0,
    0.00016533814161379112, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0004179171803251336, 0.0017290828234722833,
    0.0, 0.0020827005846636437, 0.0, 0.0, 8.826982764996862, 23.19243343998926, 0.0,
    95.1080498811086, 0.9863978034400682, 0.9834382792465353, 0.0012286405048278493,
    171.2667255897307, 0.9807858872435379, 0.0, 0.0, 0.0, 0.0005130064588990679, 0.0,
    0.00010854057858411537]


def has_alpha(img):
    return 'A' in img.getbands() or 'transparency' in img.info


def score(ref, dist, bg=0.5):
    lin1, lin2 = to_linear(ref, bg), to_linear(dist, bg)
    scales = []
    w, h = ref.size
    for scale in range(6):
        if w < 8 or h < 8:
            break
        if scale:
            lin1 = [downsample(p) for p in lin1]
            lin2 = [downsample(p) for p in lin2]
            h, w = lin1[0].shape
        x1, x2 = to_xyb(lin1), to_xyb(lin2)
        avg_ssim, avg_edge = [0.0] * 6, [0.0] * 12
        for c in range(3):
            a, b = x1[c], x2[c]
            s11, s22, s12 = blur(a * a), blur(b * b), blur(a * b)
            mu1, mu2 = blur(a), blur(b)
            num_m = (1.0 - ((mu1 - mu2) * (mu1 - mu2)).astype(np.float64)).astype(f32)
            num_s = f32(2) * (s12 - mu1 * mu2) + f32(0.0009)
            denom_s = (s11 - mu1 * mu1) + (s22 - mu2 * mu2) + f32(0.0009)
            d = np.maximum(1.0 - (num_m * num_s / denom_s).astype(np.float64), 0.0)
            n = d.size
            avg_ssim[c * 2] = d.sum() / n
            avg_ssim[c * 2 + 1] = math.sqrt(math.sqrt((d ** 4).sum() / n))
            d1 = (1.0 + np.abs(b - mu2).astype(np.float64)) / (1.0 + np.abs(a - mu1).astype(np.float64)) - 1.0
            art, lost = np.maximum(d1, 0.0), np.maximum(-d1, 0.0)
            avg_edge[c * 4] = art.sum() / n
            avg_edge[c * 4 + 1] = math.sqrt(math.sqrt((art ** 4).sum() / n))
            avg_edge[c * 4 + 2] = lost.sum() / n
            avg_edge[c * 4 + 3] = math.sqrt(math.sqrt((lost ** 4).sum() / n))
        scales.append((avg_ssim, avg_edge))
    total, i = 0.0, 0
    for c in range(3):
        for avg_ssim, avg_edge in scales:
            for n in range(2):
                total += WEIGHTS[i] * abs(avg_ssim[c * 2 + n]); i += 1
                total += WEIGHTS[i] * abs(avg_edge[c * 4 + n]); i += 1
                total += WEIGHTS[i] * abs(avg_edge[c * 4 + n + 2]); i += 1
    total *= 0.9562382616834844
    total = 2.326765642916932 * total - 0.020884521182843837 * total ** 2 + 6.248496625763138e-05 * total ** 3
    return 100.0 - 10.0 * total ** 0.6276336467831387 if total > 0 else 100.0


def tool_score(ref, dist):
    """What tools/ssimulacra2_main.cc of libjxl 0.11.1 prints: when the original has alpha, both
    images blended onto 0.1 and onto 0.9, and the worse of the two scores."""
    if not has_alpha(ref):
        return score(ref, dist)
    return min(score(ref, dist, 0.1), score(ref, dist, 0.9))


if __name__ == '__main__':
    print('%.6f' % tool_score(Image.open(sys.argv[1]), Image.open(sys.argv[2])))
