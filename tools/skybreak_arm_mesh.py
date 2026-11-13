#!/usr/bin/env python3
"""
Turns the Sky Breaks' sculpted arm from Stink-O's Orbital Laser (MIT; sculpted from MakeHuman's CC0 base mesh) into
a triangle mesh for this mod.

The original keeps the arm as 18 small grids of signed distances, one per rigid piece, ray-marched by its shader
(mod/assets/sb_hand.bin, written by its tools/bake_hand.py; the grid table is in mod/shaders/skybreak.wgsl's
`[bake table]`). This reads them, smooths and upsamples each grid, extracts the zero surface with marching cubes and
writes one OBJ group per piece, in the piece's own frame and units (the original's "bones' units"; 2.8 blocks each):

  piece_0 .. piece_11   the fingers' bones (finger * 3 + bone, index to little), in each bone's frame (x across, y back, z along)
  piece_12 .. piece_14  the thumb's bones
  piece_15              the palm, in the hand's frame (x across to the thumb, y its back, z to the fingers)
  piece_16              the forearm, in the wrist's frame (z toward the hand; the elbow is at negative z)
  piece_17              the upper arm, in the elbow's frame (the shoulder is at negative z)

usage: skybreak_arm_mesh.py <path to sb_hand.bin> <out.obj> [upsample]
"""
import math
import sys

import numpy as np
from scipy import ndimage
from skimage import measure

LO, STEP = -5.0, 0.01173021
# (offset in words, samples nx ny nz, first sample, samples per unit) from skybreak.wgsl's bake table
TABLE = [
    (4, (17, 18, 40), (-5.1, -5.4, -4.8), (1.666667, 1.666667, 1.666667)),
    (4324, (18, 17, 31), (-4.9, -4.85, -4.3), (1.818182, 1.818182, 1.818182)),
    (7486, (18, 18, 27), (-3.75, -3.8, -3.8), (2.222222, 2.222222, 2.222222)),
    (10402, (18, 19, 45), (-5.1, -5.4, -5.4), (1.666667, 1.666667, 1.666667)),
    (15532, (18, 17, 35), (-4.35, -4.3, -4.85), (1.818182, 1.818182, 1.818182)),
    (19102, (19, 18, 29), (-3.75, -3.8, -3.8), (2.222222, 2.222222, 2.222222)),
    (22756, (18, 18, 42), (-5.1, -5.4, -4.8), (1.666667, 1.666667, 1.666667)),
    (27292, (17, 15, 32), (-4.35, -3.75, -4.3), (1.818182, 1.818182, 1.818182)),
    (30172, (18, 18, 29), (-3.75, -3.8, -3.8), (2.222222, 2.222222, 2.222222)),
    (33304, (16, 16, 34), (-3.9, -4.8, -3.6), (1.666667, 1.666667, 1.666667)),
    (36568, (17, 15, 28), (-3.8, -3.75, -4.3), (1.818182, 1.818182, 1.818182)),
    (39088, (17, 16, 24), (-3.75, -3.35, -3.35), (2.222222, 2.222222, 2.222222)),
    (41392, (25, 24, 46), (-10.5, -9.0, -9.75), (1.333333, 1.333333, 1.333333)),
    (51328, (21, 19, 37), (-7.1, -5.9, -6.0), (1.666667, 1.666667, 1.666667)),
    (56249, (18, 18, 30), (-4.5, -4.5, -4.5), (2.0, 2.0, 2.0)),
    (59489, (40, 28, 59), (-16.4, -15.4, -37.2), (1.111111, 1.111111, 1.111111)),
    (82617, (23, 21, 52), (-13.2, -16.4, -112.4), (0.625, 0.625, 0.416667)),
    (91353, (20, 20, 27), (-20.0, -20.0, -60.0), (0.5, 0.5, 0.333333)),
]
HB_FZ = 7.6     # the forearm's grid is used up to here past the wrist
HB_UZ = -57.0   # the upper arm's grid ends here; past it the arm runs on as it is there
UPPER_TO = -142.0  # how far the upper arm is run on toward the shoulder (its bone is 138 units)

# UV rectangles (u0, v0, u1, v1) on the 1024x1024 skin, one per piece (cylindrical mapping round its z axis)
UV_ARM = (0.0, 0.0, 0.5, 0.62)
UV_UPPER = (0.0, 0.63, 0.5, 0.93)
UV_PALM = (0.51, 0.0, 0.98, 0.3)


def uv_rect(pid):
    if pid == 16:
        return UV_ARM
    if pid == 17:
        return UV_UPPER
    if pid == 15:
        return UV_PALM
    col, row = pid % 3, pid // 3
    return (0.51 + col * 0.155, 0.31 + row * 0.125, 0.51 + col * 0.155 + 0.15, 0.31 + row * 0.125 + 0.12)


def decode(words, pid):
    off, n, bmin, inv = TABLE[pid]
    nx, ny, nz = n
    nxw = (nx + 2) // 3
    d = np.zeros((nx, ny, nz), np.float32)
    for z in range(nz):
        for y in range(ny):
            r = off + (z * ny + y) * nxw
            for x in range(nx):
                w = int(words[r + x // 3])
                q = (w >> (10 * (x % 3))) & 1023
                d[x, y, z] = LO + STEP * q
    h = np.array([1.0 / v for v in inv])
    return d, np.array(bmin, float), h


def prepare(d, bmin, h, pid):
    """Pads and edits a grid so marching cubes gives a closed, sensible surface."""
    if pid == 16:
        # (the forearm: cut at HB_FZ past the wrist, closed at both ends)
        kz = int(math.floor((HB_FZ - bmin[2]) / h[2]))
        d = d[:, :, :kz + 1].copy()
    if pid == 17:
        # (the upper arm: run on toward the shoulder as its cross-section at HB_UZ)
        k0 = int(round((HB_UZ - bmin[2]) / h[2]))
        extra = int(math.ceil((HB_UZ - UPPER_TO) / h[2]))
        sl = d[:, :, k0:k0 + 1]
        d = np.concatenate([np.repeat(sl, extra, axis=2), d[:, :, k0:]], axis=2)
        bmin = np.array([bmin[0], bmin[1], HB_UZ - extra * h[2]])
    # (close every open side with a layer of air)
    d = np.pad(d, 1, mode='constant', constant_values=3.0)
    bmin = bmin - h
    return d, bmin, h


# (resampling per piece: the fingers at the grid's own spacing, the big pieces coarser; ~35k triangles in all)
UP = {15: 0.72, 16: 0.75, 17: 0.55}


def mesh_piece(words, pid, up):
    up = UP.get(pid, up)
    d, bmin, h = decode(words, pid)
    d, bmin, h = prepare(d, bmin, h, pid)
    if up != 1.0:
        d = ndimage.zoom(d, up, order=3, mode='nearest')
        h = h / up
    d = ndimage.gaussian_filter(d, 0.6)
    verts, faces, _, _ = measure.marching_cubes(d, level=0.0, spacing=tuple(h))
    verts = verts + bmin
    # smooth normals from the field's gradient
    g = np.gradient(d, *h)
    idx = (verts - bmin) / h
    nrm = np.stack([ndimage.map_coordinates(gi, idx.T, order=1, mode='nearest') for gi in g], axis=1)
    nrm /= np.linalg.norm(nrm, axis=1, keepdims=True) + 1e-9
    # cylindrical UVs round the piece's z axis
    u0, v0, u1, v1 = uv_rect(pid)
    ang = (np.arctan2(verts[:, 1], verts[:, 0]) / (2 * math.pi)) % 1.0
    zmin, zmax = verts[:, 2].min(), verts[:, 2].max()
    uv = np.stack([u0 + (u1 - u0) * ang, v0 + (v1 - v0) * (verts[:, 2] - zmin) / max(zmax - zmin, 1e-6)], axis=1)
    # marching cubes faces wind clockwise seen from outside here: flip to counter-clockwise
    faces = faces[:, ::-1]
    return verts, nrm, uv, faces


def main():
    src, out = sys.argv[1], sys.argv[2]
    up = float(sys.argv[3]) if len(sys.argv) > 3 else 1.0
    words = np.frombuffer(open(src, 'rb').read(), dtype='<u4')
    assert words[0] == 0x53424844, 'not a Sky Breaks hand grid file'
    lines = ['# Sky Breaks arm, from Stink-O/orbital-laser (MIT; sculpted from MakeHuman CC0).',
             '# Generated by tools/skybreak_arm_mesh.py. One group per rigid piece, in its own frame.']
    base = 1
    total = 0
    for pid in range(18):
        v, n, t, f = mesh_piece(words, pid, up)
        # (a piece's z span shown on the uv seam would be visible on fingers only: fine)
        lines.append(f'g piece_{pid}')
        lines += ['v %.4f %.4f %.4f' % tuple(p) for p in v]
        lines += ['vt %.5f %.5f' % (a, 1.0 - b) for a, b in t]
        lines += ['vn %.4f %.4f %.4f' % tuple(p) for p in n]
        for a, b, c in f + base:
            lines.append(f'f {a}/{a}/{a} {b}/{b}/{b} {c}/{c}/{c}')
        base += len(v)
        total += len(f)
        print(f'piece {pid:2d}: {len(v):6d} vertices {len(f):6d} triangles')
    open(out, 'w').write('\n'.join(lines) + '\n')
    print('triangles in all:', total, '->', out)


if __name__ == '__main__':
    main()
