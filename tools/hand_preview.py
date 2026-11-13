#!/usr/bin/env python3
"""
Renders the Sky Hand in a given pose to a PNG, using the same rig math as the mod
(bone matrix = parent * T(pivot) * Rest * Ry*Rz*Rx * Rest^-1 * T(-pivot)).

usage: hand_preview.py <model_dir> <out.png> <pose> [yaw] [pitch] [size] [texture.png]
"""
import json
import math
import sys

import numpy as np
from PIL import Image


def quat_to_mat(q):
    x, y, z, w = q
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
        [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
        [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])


def rx(d):
    a = math.radians(d); c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def ry(d):
    a = math.radians(d); c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def rz(d):
    a = math.radians(d); c, s = math.cos(a), math.sin(a)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def m4(r=None, t=None):
    m = np.eye(4)
    if r is not None:
        m[:3, :3] = r
    if t is not None:
        m[:3, 3] = t
    return m


def load_obj(path):
    groups = {}
    V, T, N = [], [], []
    cur = None
    for line in open(path):
        p = line.split()
        if not p:
            continue
        if p[0] == 'g':
            cur = p[1]
            groups[cur] = []
        elif p[0] == 'v':
            V.append([float(x) for x in p[1:4]])
        elif p[0] == 'vt':
            T.append([float(p[1]), 1.0 - float(p[2])])
        elif p[0] == 'vn':
            N.append([float(x) for x in p[1:4]])
        elif p[0] == 'f':
            tri = []
            for c in p[1:4]:
                a = c.split('/')
                tri.append((int(a[0]) - 1, int(a[1]) - 1, int(a[2]) - 1))
            groups[cur].append(tri)
    return np.array(V), np.array(T), np.array(N), groups


def bone_matrices(rig, pose):
    mats = {}
    for b in rig['bones']:
        rest = quat_to_mat(b['rest'])
        rot = pose.get(b['name'], [0, 0, 0])
        r = ry(rot[1]) @ rz(rot[2]) @ rx(rot[0])
        p = np.array(b['pivot'])
        local = m4(t=p) @ m4(rest) @ m4(r) @ m4(rest.T) @ m4(t=-p)
        parent = mats[b['parent']] if b['parent'] else np.eye(4)
        mats[b['name']] = parent @ local
    return mats


def render(model_dir, pose_name, yaw=20, pitch=10, size=640, tex_path=None, center=None, span=None):
    rig = json.load(open(f'{model_dir}/sky_hand_rig.json'))
    poses = json.load(open(f'{model_dir}/sky_hand_poses.json'))['poses']
    V, T, N, groups = load_obj(f'{model_dir}/sky_hand.obj')
    mats = bone_matrices(rig, poses[pose_name])
    tex = np.asarray(Image.open(tex_path).convert('RGB'), dtype=float) / 255 if tex_path else None

    cam = ry(yaw) @ rx(pitch)
    camT = cam.T
    tris_p, tris_n, tris_t = [], [], []
    for g, faces in groups.items():
        M = mats[g]
        for f in faces:
            idx = [c[0] for c in f]
            pv = (M[:3, :3] @ V[idx].T).T + M[:3, 3]
            nv = (M[:3, :3] @ N[[c[2] for c in f]].T).T
            tris_p.append(pv)
            tris_n.append(nv)
            tris_t.append(T[[c[1] for c in f]])
    P = np.array(tris_p) @ camT.T
    Nn = np.array(tris_n) @ camT.T
    UV = np.array(tris_t)

    allp = P.reshape(-1, 3)
    if center is None:
        lo, hi = allp.min(0), allp.max(0)
        center = (lo + hi) / 2
        span = max(hi[0] - lo[0], hi[1] - lo[1]) * 1.08
    scale = size / span
    sx = (P[..., 0] - center[0]) * scale + size / 2
    sy = size / 2 - (P[..., 1] - center[1]) * scale
    sz = P[..., 2]

    img = np.zeros((size, size, 3)) + np.array([0.13, 0.13, 0.15])
    zb = np.full((size, size), -1e9)
    light = np.array([0.4, 0.7, 0.6]); light /= np.linalg.norm(light)
    for i in range(len(P)):
        x0, y0 = sx[i], sy[i]
        minx = max(int(np.floor(x0.min())), 0); maxx = min(int(np.ceil(x0.max())), size - 1)
        miny = max(int(np.floor(y0.min())), 0); maxy = min(int(np.ceil(y0.max())), size - 1)
        if minx > maxx or miny > maxy:
            continue
        xs, ys = np.meshgrid(np.arange(minx, maxx + 1) + 0.5, np.arange(miny, maxy + 1) + 0.5)
        (ax, bx, cx), (ay, by, cy) = x0, y0
        den = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
        if abs(den) < 1e-12:
            continue
        w0 = ((by - cy) * (xs - cx) + (cx - bx) * (ys - cy)) / den
        w1 = ((cy - ay) * (xs - cx) + (ax - cx) * (ys - cy)) / den
        w2 = 1 - w0 - w1
        inside = (w0 >= -1e-4) & (w1 >= -1e-4) & (w2 >= -1e-4)
        if not inside.any():
            continue
        z = w0 * sz[i, 0] + w1 * sz[i, 1] + w2 * sz[i, 2]
        yy = (ys - 0.5).astype(int); xx = (xs - 0.5).astype(int)
        upd = inside & (z > zb[yy, xx])
        if not upd.any():
            continue
        n = (w0[..., None] * Nn[i, 0] + w1[..., None] * Nn[i, 1] + w2[..., None] * Nn[i, 2])
        n /= np.linalg.norm(n, axis=-1, keepdims=True) + 1e-9
        if n[upd][:, 2].mean() < 0:
            pass
        shade = 0.38 + 0.62 * np.clip(np.abs(n @ light), 0, 1)
        if tex is not None:
            u = w0 * UV[i, 0, 0] + w1 * UV[i, 1, 0] + w2 * UV[i, 2, 0]
            v = w0 * UV[i, 0, 1] + w1 * UV[i, 1, 1] + w2 * UV[i, 2, 1]
            tu = np.clip((u * tex.shape[1]).astype(int), 0, tex.shape[1] - 1)
            tv = np.clip((v * tex.shape[0]).astype(int), 0, tex.shape[0] - 1)
            col = tex[tv, tu]
        else:
            col = np.ones(xs.shape + (3,)) * np.array([0.9, 0.87, 0.8])
        c = col * shade[..., None]
        img[yy[upd], xx[upd]] = c[upd]
        zb[yy[upd], xx[upd]] = z[upd]
    return Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8))


if __name__ == '__main__':
    d, out, pose = sys.argv[1:4]
    yaw = float(sys.argv[4]) if len(sys.argv) > 4 else 20
    pitch = float(sys.argv[5]) if len(sys.argv) > 5 else 10
    size = int(sys.argv[6]) if len(sys.argv) > 6 else 640
    tex = sys.argv[7] if len(sys.argv) > 7 else None
    render(d, pose, yaw, pitch, size, tex).save(out)
