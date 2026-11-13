#!/usr/bin/env python3
"""
Poses the Sky Breaks arm mesh the way the mod does (a Python twin of client/skybreak/ArmRig.java, itself a port of
the original's skybreak.wgsl `hd_set`) and renders it, to check the pieces line up.

usage: skybreak_arm_preview.py <skybreak_arm.obj> <out.png>
"""
import math
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, __file__.rsplit('/', 1)[0])
from hand_preview import ry, rx  # noqa: E402

KN = [(10.9, 0.9, 7.0, 4.0), (3.5, 1.6, 7.7, 4.2), (-3.9, 1.1, 4.8, 3.8), (-11.0, -0.3, 1.6, 3.3)]
DM = [(29.5, 3.8, 0.07, 0.05), (33.1, 3.9, 0.012, -0.03), (31.7, 3.65, -0.05, -0.15), (25.2, 3.25, -0.14, -0.28)]


def nz(v):
    v = np.asarray(v, float)
    return v / max(np.linalg.norm(v), 1e-9)


def sat(x):
    return min(1.0, max(0.0, x))


def mix(a, b, t):
    return np.asarray(a, float) * (1 - t) + np.asarray(b, float) * t


def bones(fl, th, spread, hk=(0, 0, 0, 0)):
    """15 bones: (a, f, u, r) in the hand's frame."""
    out = [None] * 15
    for i in range(4):
        kn = np.array(KN[i][:3], float)
        dm = DM[i]
        c = fl[i]
        kn[1] -= sat(sum(fl) * 0.25) * ((1.7 if i == 3 else 0.7) if i > 1 else 0.0)
        spl = dm[2] * (1.0 + 1.6 * spread) * (1.0 - 0.6 * sat(c))
        f = np.array([math.sin(spl), 0.0, math.cos(spl)])
        u = np.array([dm[3] * sat(c * 1.4), 1.0, 0.0])
        u = nz(u - f * np.dot(u, f))
        t1 = 1.5 * (c - 0.5 * hk[i])
        t2 = 1.92 * sat(c + 0.5 * hk[i])
        t3 = 0.67 * t2
        ang = [t1, t1 + t2, t1 + t2 + t3]
        lens = [0.48, 0.29, 0.23]
        p = kn.copy()
        for s in range(3):
            fs = f * math.cos(ang[s]) - u * math.sin(ang[s])
            us = u * math.cos(ang[s]) + f * math.sin(ang[s])
            out[i * 3 + s] = (p.copy(), fs, us, np.cross(us, fs))
            p = p + fs * lens[s] * dm[0]
    c = sat(th)
    s = spread
    o1 = np.array([0.54 + 0.2 * s, -0.28, 0.78])
    o2 = np.array([0.5 + 0.22 * s, -0.24, 0.83])
    c1 = np.array([-0.52, -0.59, 0.62])
    c2 = np.array([-0.95, 0.0, 0.3])
    m1 = np.array([0.3, -0.5, 0.81])
    tf = [nz(mix([0.7 + 0.2 * s, -0.33, 0.63 - 0.15 * s], m1, c)), nz(mix(o1, c1, c)), nz(mix(o2, c2, c))]
    tl = [15.6, 10.8, 8.6]
    tp = np.array([8.6, -3.4, -16.5])
    roll = [0.4, 0.8, 1.0]
    for k in range(3):
        uk = nz(mix([0.8, 0.55, -0.2], [0.25, -0.95, 0.15], c * roll[k]))
        tu = nz(uk - tf[k] * np.dot(uk, tf[k]))
        out[12 + k] = (tp.copy(), tf[k], tu, np.cross(tu, tf[k]))
        tp = tp + tf[k] * tl[k]
    return out


def load_obj(path):
    groups = {}
    V, N = [], []
    cur = None
    for line in open(path):
        p = line.split()
        if not p:
            continue
        if p[0] == 'g':
            cur = int(p[1].split('_')[1])
            groups[cur] = []
        elif p[0] == 'v':
            V.append([float(x) for x in p[1:4]])
        elif p[0] == 'vn':
            N.append([float(x) for x in p[1:4]])
        elif p[0] == 'f':
            groups[cur].append([int(c.split('/')[0]) - 1 for c in p[1:4]])
    return np.array(V), np.array(N), groups


def piece_mats(fl, th, spread, wrist_flex=0.0):
    """Model matrices (hand frame = world here, sc = 1, side = 1): palm at the origin, fingers +z, back +y."""
    mats = {}
    for k, (a, f, u, r) in enumerate(bones(fl, th, spread)):
        M = np.eye(4)
        M[:3, 0], M[:3, 1], M[:3, 2], M[:3, 3] = r, u, f, a
        mats[k] = M
    mats[15] = np.eye(4)
    az = np.array([0, 0, 1.0])
    w = -az * 24.0
    # the forearm runs back from the wrist (bent by wrist_flex about x)
    ad = nz(rx(wrist_flex) @ np.array([0, 0, -1.0]))
    el = w + ad * 96.0
    x0 = nz(np.array([1.0, 0, 0]) - ad * ad[0] + np.array([1e-4, 0, 0]))
    y0 = np.cross(x0, ad)
    lf = 96.0
    zs = 96.0 / lf
    M = np.eye(4)
    M[:3, 0], M[:3, 1], M[:3, 2], M[:3, 3] = x0, y0, -ad / zs, w
    mats[16] = M
    ue = ad  # straight arm: shoulder further along
    a_, b_ = ad, -ue
    kk = 1.0 / max(1.0 + np.dot(a_, b_), 0.05)
    x1 = x0 - (a_ + b_) * (np.dot(a_ + b_, x0) * kk) + 2.0 * b_ * np.dot(a_, x0)
    y1 = y0 - (a_ + b_) * (np.dot(a_ + b_, y0) * kk) + 2.0 * b_ * np.dot(a_, y0)
    M = np.eye(4)
    M[:3, 0], M[:3, 1], M[:3, 2], M[:3, 3] = x1, y1, ue * -1.0 * -1.0, el
    # (ue points from the shoulder to the elbow; with the shoulder further from the wrist, that is -ad)
    M[:3, 2] = -ad
    mats[17] = M
    return mats


def render(V, N, groups, mats, yaw, pitch, size=520, hand_only=True):
    cam = (ry(yaw) @ rx(pitch)).T
    tris, nrms = [], []
    for g, faces in groups.items():
        if hand_only and g == 17:
            continue
        M = mats[g]
        f = np.array(faces)
        tris.append((M[:3, :3] @ V[f].reshape(-1, 3).T).T.reshape(-1, 3, 3) + M[:3, 3])
        R = M[:3, :3] / np.linalg.norm(M[:3, :3], axis=0)
        nrms.append((R @ N[f].reshape(-1, 3).T).T.reshape(-1, 3, 3))
    P = np.vstack(tris) @ cam.T
    Nn = np.vstack(nrms) @ cam.T
    keep = P[..., 2].max(1) > -1e9
    if hand_only:
        sel = P.reshape(-1, 3)
    lo, hi = P.reshape(-1, 3).min(0), P.reshape(-1, 3).max(0)
    center = (lo + hi) / 2
    if hand_only:
        center = (np.array([0, 0, 0]) @ cam.T)
        span = 90.0
    else:
        span = max(hi[0] - lo[0], hi[1] - lo[1]) * 1.05
    sc = size / span
    img = np.zeros((size, size, 3)) + 0.12
    zb = np.full((size, size), -1e9)
    light = nz([0.4, 0.7, 0.6])
    sx = (P[..., 0] - center[0]) * sc + size / 2
    sy = size / 2 - (P[..., 1] - center[1]) * sc
    for i in range(len(P)):
        x0, y0 = sx[i], sy[i]
        minx, maxx = max(int(x0.min()), 0), min(int(x0.max()) + 1, size - 1)
        miny, maxy = max(int(y0.min()), 0), min(int(y0.max()) + 1, size - 1)
        if minx > maxx or miny > maxy:
            continue
        xs, ys = np.meshgrid(np.arange(minx, maxx + 1) + .5, np.arange(miny, maxy + 1) + .5)
        (ax, bx, cx), (ay, by, cy) = x0, y0
        den = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
        if abs(den) < 1e-9:
            continue
        w0 = ((by - cy) * (xs - cx) + (cx - bx) * (ys - cy)) / den
        w1 = ((cy - ay) * (xs - cx) + (ax - cx) * (ys - cy)) / den
        w2 = 1 - w0 - w1
        ins = (w0 >= 0) & (w1 >= 0) & (w2 >= 0)
        z = w0 * P[i, 0, 2] + w1 * P[i, 1, 2] + w2 * P[i, 2, 2]
        yy, xx = (ys - .5).astype(int), (xs - .5).astype(int)
        upd = ins & (z > zb[yy, xx])
        if not upd.any():
            continue
        n = w0[..., None] * Nn[i, 0] + w1[..., None] * Nn[i, 1] + w2[..., None] * Nn[i, 2]
        n /= np.linalg.norm(n, axis=-1, keepdims=True) + 1e-9
        shade = 0.3 + 0.7 * np.clip(n @ light, 0, 1)
        img[yy[upd], xx[upd]] = (np.array([0.85, 0.82, 0.78]) * shade[..., None])[upd]
        zb[yy[upd], xx[upd]] = z[upd]
    return Image.fromarray((img * 255).astype(np.uint8))


if __name__ == '__main__':
    V, N, groups = load_obj(sys.argv[1])
    shots = []
    for fl, th, sp, yaw, pitch in [((0, 0, 0, 0), 0, 0.3, 0, -90), ((0, 0, 0, 0), 0, 0.3, 180, -90),
                                   ((0.3, 0.35, 0.4, 0.5), 0.2, 0.2, 60, -40), ((1, 1, 1, 1), 1, 0, 0, -90),
                                   ((1, 1, 1, 1), 1, 0, 90, -20), ((0.3, 0.35, 0.4, 0.5), 0.2, 0.2, 90, 0)]:
        shots.append(render(V, N, groups, piece_mats(fl, th, sp), yaw, pitch))
    W = Image.new('RGB', (520 * 3, 520 * 2))
    for i, im in enumerate(shots):
        W.paste(im, ((i % 3) * 520, (i // 3) * 520))
    W.save(sys.argv[2])
