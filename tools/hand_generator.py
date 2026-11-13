#!/usr/bin/env python3
"""
Generates the Sky Hand mesh used by the Orbital Laser mod.

Outputs (into src/main/resources/assets/orbital_laser/models/sky_hand/):
  sky_hand.obj        triangulated mesh, one group per bone (g <bone>), with UVs and normals.
                      Opens in Blender (File > Import > Wavefront, Y up, -Z forward).
  sky_hand_rig.json   bones: parent, pivot (joint position) and rest rotation (quaternion x,y,z,w)
  sky_hand_poses.json named poses: per-bone euler rotation in degrees [x, y, z], applied in the bone's
                      rest frame as Ry * Rz * Rx. Negative x closes a finger toward the palm.

Bind pose / units:
  +Y up, the arm hangs down: elbow at y = 4, wrist at the origin, fingers point to -Y.
  Palm faces +Z, back of the hand faces -Z, thumb on +X. 1 unit = palm length.
  The mod scales the whole model at runtime (about 24 blocks per unit for a radius 24 crater).

To replace the procedural mesh with a sculpted one: keep the same group names and pivots,
model each bone as a rigid piece (no skinning), triangulate, export OBJ with UVs and normals.
"""
import json
import math
import os
import sys

import numpy as np

OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(__file__), '..', 'src', 'main', 'resources', 'assets', 'orbital_laser', 'models', 'sky_hand')

# ----------------------------------------------------------------------------- math helpers


def norm(v):
    v = np.asarray(v, dtype=float)
    return v / np.linalg.norm(v)


def rot_x(deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def rot_y(deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def rot_z(deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def basis_from_dir(down, flex_motion):
    """Rest frame whose local -Y points along `down` and whose positive curl (rotation about -X)
    moves the tip along `flex_motion`."""
    t = norm(down)
    x = norm(np.cross(flex_motion, t))
    y = -t
    z = np.cross(x, y)
    return np.column_stack([x, y, z])


def mat_to_quat(m):
    """3x3 rotation matrix -> quaternion (x, y, z, w)."""
    tr = m[0, 0] + m[1, 1] + m[2, 2]
    if tr > 0:
        s = math.sqrt(tr + 1.0) * 2
        w = 0.25 * s
        x = (m[2, 1] - m[1, 2]) / s
        y = (m[0, 2] - m[2, 0]) / s
        z = (m[1, 0] - m[0, 1]) / s
    elif m[0, 0] > m[1, 1] and m[0, 0] > m[2, 2]:
        s = math.sqrt(1.0 + m[0, 0] - m[1, 1] - m[2, 2]) * 2
        w = (m[2, 1] - m[1, 2]) / s
        x = 0.25 * s
        y = (m[0, 1] + m[1, 0]) / s
        z = (m[0, 2] + m[2, 0]) / s
    elif m[1, 1] > m[2, 2]:
        s = math.sqrt(1.0 + m[1, 1] - m[0, 0] - m[2, 2]) * 2
        w = (m[0, 2] - m[2, 0]) / s
        x = (m[0, 1] + m[1, 0]) / s
        y = 0.25 * s
        z = (m[1, 2] + m[2, 1]) / s
    else:
        s = math.sqrt(1.0 + m[2, 2] - m[0, 0] - m[1, 1]) * 2
        w = (m[1, 0] - m[0, 1]) / s
        x = (m[0, 2] + m[2, 0]) / s
        y = (m[1, 2] + m[2, 1]) / s
        z = 0.25 * s
    q = np.array([x, y, z, w])
    return q / np.linalg.norm(q)


# ----------------------------------------------------------------------------- mesh building

class Part:
    def __init__(self):
        self.v = []   # positions
        self.t = []   # uvs
        self.n = []   # normals
        self.f = []   # triangles (indices)

    def add_grid(self, pos, uv, nrm, rows, cols, center_of_row):
        """pos/uv/nrm: arrays [rows][cols+1]. Triangulates and orients faces outward."""
        base = len(self.v)
        for i in range(rows):
            for j in range(cols + 1):
                self.v.append(pos[i][j])
                self.t.append(uv[i][j])
                self.n.append(nrm[i][j])
        w = cols + 1
        for i in range(rows - 1):
            for j in range(cols):
                a = base + i * w + j
                b = base + i * w + j + 1
                c = base + (i + 1) * w + j + 1
                d = base + (i + 1) * w + j
                for tri in ((a, b, c), (a, c, d)):
                    p0, p1, p2 = (np.array(self.v[k]) for k in tri)
                    fn = np.cross(p1 - p0, p2 - p0)
                    if np.linalg.norm(fn) < 1e-12:
                        continue
                    cen = (p0 + p1 + p2) / 3.0
                    ri = i + 0.5
                    c0 = center_of_row(min(rows - 1, int(ri)))
                    c1 = center_of_row(min(rows - 1, int(ri) + 1))
                    axis_c = (c0 + c1) / 2.0
                    if np.dot(fn, cen - axis_c) < 0:
                        tri = (tri[0], tri[2], tri[1])
                    self.f.append(tri)


def loft(part, origin, basis, profile, section, cols, uvrect, cap0=True, cap1=True, cap_rings=7):
    """
    Surface of revolution-like loft along the local -Y axis of `basis`, starting at `origin`.
    profile: list of (s, a, b, cx, cz) with s = distance along the bone, a/b = half sizes along local X/Z,
             cx/cz = section center offset in local X/Z.
    section(theta, s, a, b) -> (x, z) local offset of the surface point.
    Rounded caps are added at both ends.
    """
    prof = list(profile)
    # Build the list of rings including caps.
    rings = []
    s0, a0, b0, cx0, cz0 = prof[0]
    s1, a1, b1, cx1, cz1 = prof[-1]
    if cap0:
        cl = min(a0, b0) * 0.95
        for k in range(cap_rings):
            phi = -math.pi / 2 + (math.pi / 2) * k / cap_rings
            rings.append((s0 + cl * math.sin(phi), a0 * math.cos(phi), b0 * math.cos(phi), cx0, cz0))
    for p in prof:
        rings.append(p)
    if cap1:
        cl = min(a1, b1) * 0.95
        for k in range(1, cap_rings + 1):
            phi = (math.pi / 2) * k / cap_rings
            rings.append((s1 + cl * math.sin(phi), a1 * math.cos(phi), b1 * math.cos(phi), cx1, cz1))

    nr = len(rings)
    # arc length along the profile for v
    arc = [0.0]
    for i in range(1, nr):
        arc.append(arc[-1] + abs(rings[i][0] - rings[i - 1][0]) + 1e-6)
    total = arc[-1]

    pos = np.zeros((nr, cols + 1, 3))
    uvs = np.zeros((nr, cols + 1, 2))
    centers = []
    for i, (s, a, b, cx, cz) in enumerate(rings):
        center_local = np.array([cx, -s, cz])
        centers.append(origin + basis @ center_local)
        for j in range(cols + 1):
            th = 2 * math.pi * (j % cols) / cols
            x, z = section(th, s, a, b)
            local = np.array([cx + x, -s, cz + z])
            pos[i, j] = origin + basis @ local
            u0, v0, u1, v1 = uvrect
            uvs[i, j] = (u1 - (u1 - u0) * j / cols, v0 + (v1 - v0) * arc[i] / total)

    # smooth normals from the grid with wrap-around
    nrm = np.zeros_like(pos)
    for i in range(nr):
        for j in range(cols):
            jp = (j + 1) % cols
            jm = (j - 1) % cols
            ip = min(nr - 1, i + 1)
            im = max(0, i - 1)
            du = pos[i, jp] - pos[i, jm]
            dv = pos[ip, j] - pos[im, j]
            n = np.cross(du, dv)
            ln = np.linalg.norm(n)
            if ln < 1e-12:
                # pole: point along the axis
                n = pos[i, j] - centers[max(0, min(nr - 1, i - 1 if i == nr - 1 else i + 1))]
                if np.linalg.norm(n) < 1e-12:
                    n = basis @ np.array([0, -1 if i > nr / 2 else 1, 0])
                ln = np.linalg.norm(n)
            n = n / ln
            # orient outward
            if np.dot(n, pos[i, j] - centers[i]) < 0 and np.linalg.norm(pos[i, j] - centers[i]) > 1e-9:
                n = -n
            nrm[i, j] = n
        nrm[i, cols] = nrm[i, 0]
    # poles: use the axis direction
    for i in (0, nr - 1):
        if rings[i][1] < 1e-6 or rings[i][2] < 1e-6:
            axis = basis @ np.array([0, 1 if i == 0 else -1, 0])
            for j in range(cols + 1):
                nrm[i, j] = axis

    part.add_grid(pos, uvs, nrm, nr, cols, lambda r: centers[r])


def ellipse(th, s, a, b):
    return a * math.cos(th), b * math.sin(th)


def superellipse(n):
    def f(th, s, a, b):
        c, si = math.cos(th), math.sin(th)
        x = a * math.copysign(abs(c) ** (2.0 / n), c)
        z = b * math.copysign(abs(si) ** (2.0 / n), si)
        return x, z
    return f


def capsule(part, p0, p1, r0, r1, flat, cols, uvrect, roll_ref=None):
    """Tapered capsule from p0 to p1 (model space)."""
    p0 = np.asarray(p0, float)
    p1 = np.asarray(p1, float)
    d = p1 - p0
    length = np.linalg.norm(d)
    ref = roll_ref if roll_ref is not None else np.array([0, 0, 1.0])
    y = -d / length
    x = norm(np.cross(y, ref))
    z = np.cross(x, y)
    basis = np.column_stack([x, y, z])
    profile = [(0.0, r0, r0 * flat, 0, 0), (length, r1, r1 * flat, 0, 0)]
    loft(part, p0, basis, profile, ellipse, cols, uvrect)


# ----------------------------------------------------------------------------- the hand

FINGERS = {
    #          knuckle (x, y, z)        splay  lengths                 radii at joints (4)
    'index':  ((0.290, -0.955, -0.01),   6.0, (0.37, 0.23, 0.19),   (0.116, 0.106, 0.096, 0.084)),
    'middle': ((0.098, -0.995, -0.015),  0.0, (0.41, 0.255, 0.205), (0.120, 0.110, 0.100, 0.087)),
    'ring':   ((-0.095, -0.970, -0.01), -5.0, (0.385, 0.245, 0.20), (0.113, 0.104, 0.095, 0.082)),
    'pinky':  ((-0.272, -0.900, 0.0),  -12.0, (0.30, 0.18, 0.17),   (0.100, 0.092, 0.084, 0.073)),
}
THUMB_PIVOT = np.array([0.245, -0.16, 0.05])
THUMB_DIR = norm([0.55, -0.75, 0.36])
THUMB_FLEX = norm([-0.70, -0.20, 0.68])
THUMB_LEN = (0.40, 0.28, 0.24)
THUMB_RAD = (0.15, 0.13, 0.12, 0.105)

ARM_LEN = 4.0
NAIL_UV = (0.945, 0.945, 0.995, 0.995)

# UV atlas regions (u0, v0, u1, v1). The writing texture leaves the nail corner blank.
UV_ARM = (0.00, 0.00, 0.55, 0.93)
UV_PALM = (0.56, 0.00, 0.98, 0.30)
UV_THENAR = (0.56, 0.31, 0.70, 0.42)
UV_HYPO = (0.71, 0.31, 0.84, 0.42)
UV_WRIST = (0.85, 0.31, 0.98, 0.42)


def finger_cell(k):
    col = k % 3
    row = k // 3
    return (0.56 + col * 0.14, 0.43 + row * 0.10, 0.56 + col * 0.14 + 0.13, 0.43 + row * 0.10 + 0.095)


def build():
    bones = []      # (name, parent, pivot, basis)
    parts = {}

    def bone(name, parent, pivot, basis):
        bones.append((name, parent, np.asarray(pivot, float), basis))
        parts[name] = Part()
        return parts[name]

    eye = np.eye(3)

    # ---- forearm: pivot at the elbow, hangs down to the wrist
    arm = bone('arm', None, (0, ARM_LEN, 0), eye)
    arm_profile = [
        (0.00, 0.44, 0.38, 0.0, 0.0),
        (0.60, 0.445, 0.385, 0.0, -0.01),
        (1.30, 0.43, 0.37, 0.0, -0.01),
        (2.00, 0.40, 0.33, 0.0, 0.0),
        (2.70, 0.35, 0.26, 0.0, 0.0),
        (3.30, 0.305, 0.20, 0.0, 0.0),
        (3.75, 0.29, 0.175, 0.0, 0.0),
        (4.08, 0.285, 0.17, 0.0, 0.0),
    ]

    def arm_section(th, s, a, b):
        # flexor / extensor muscle masses: subtle bulges that fade toward the wrist
        fade = max(0.0, 1.0 - s / 3.4)
        bump = 1.0 + fade * (0.045 * math.cos(2 * th - 0.6) + 0.025 * math.sin(3 * th + 0.4))
        return a * math.cos(th) * bump, b * math.sin(th) * bump

    loft(arm, np.array([0, ARM_LEN, 0]), eye, arm_profile, arm_section, 40, UV_ARM, cap0=True, cap1=True)

    # ---- hand (palm): pivot at the wrist
    hand = bone('hand', 'arm', (0, 0, 0), eye)
    palm_profile = [
        (0.04, 0.255, 0.140, 0.0, 0.00),
        (0.14, 0.30, 0.150, 0.01, 0.008),
        (0.32, 0.355, 0.148, 0.01, 0.012),
        (0.55, 0.385, 0.140, 0.005, 0.014),
        (0.78, 0.395, 0.130, 0.0, 0.008),
        (0.93, 0.388, 0.118, 0.0, 0.0),
    ]

    def palm_section(th, s, a, b):
        x, z = superellipse(2.35)(th, s, a, b)
        if z > 0:
            # palm side is a little fuller in the middle
            z *= 1.0 + 0.18 * math.sin(min(math.pi, max(0.0, (s - 0.1) / 0.8 * math.pi)))
        return x, z

    loft(hand, np.array([0, 0, 0]), eye, palm_profile, palm_section, 36, UV_PALM)
    # thenar (thumb muscle), hypothenar (pinky edge) and the wrist ball
    capsule(hand, (0.235, -0.20, 0.07), (0.16, -0.58, 0.075), 0.14, 0.09, 0.78, 20, UV_THENAR)
    capsule(hand, (-0.28, -0.30, 0.05), (-0.30, -0.82, 0.04), 0.09, 0.075, 0.85, 16, UV_HYPO)
    capsule(hand, (0.0, 0.04, -0.005), (0.0, -0.08, 0.0), 0.255, 0.255, 0.64, 28, UV_WRIST)

    # ---- fingers
    k = 0
    for name, (knuckle, splay, lengths, radii) in FINGERS.items():
        basis = rot_z(splay)
        pivot = np.array(knuckle, float)
        parent = 'hand'
        for seg in range(3):
            bname = f'{name}_{seg + 1}'
            p = bone(bname, parent, pivot, basis)
            L = lengths[seg]
            r0, r1 = radii[seg], radii[seg + 1]
            profile = [(0.0, r0, r0 * 0.86, 0, 0),
                       (L * 0.5, (r0 + r1) * 0.5 * 0.94, (r0 + r1) * 0.5 * 0.8, 0, 0.004),
                       (L if seg < 2 else L - r1 * 0.6, r1, r1 * 0.86, 0, 0)]
            loft(p, pivot, basis, profile, ellipse, 18, finger_cell(k))
            if seg == 2:
                add_nail(p, pivot, basis, L, r1)
            k += 1
            parent = bname
            pivot = pivot + basis @ np.array([0, -L, 0])

    # ---- thumb
    basis = basis_from_dir(THUMB_DIR, THUMB_FLEX)
    pivot = THUMB_PIVOT.copy()
    parent = 'hand'
    for seg in range(3):
        bname = f'thumb_{seg + 1}'
        p = bone(bname, parent, pivot, basis)
        L = THUMB_LEN[seg]
        r0, r1 = THUMB_RAD[seg], THUMB_RAD[seg + 1]
        profile = [(0.0, r0, r0 * 0.85, 0, 0),
                   (L * 0.5, (r0 + r1) * 0.5 * 0.95, (r0 + r1) * 0.5 * 0.82, 0, 0),
                   (L if seg < 2 else L - r1 * 0.6, r1, r1 * 0.85, 0, 0)]
        loft(p, pivot, basis, profile, ellipse, 18, finger_cell(k))
        if seg == 2:
            add_nail(p, pivot, basis, L, r1)
        k += 1
        parent = bname
        pivot = pivot + basis @ np.array([0, -L, 0])

    return bones, parts


def add_nail(part, pivot, basis, L, r):
    """Flattened ellipsoid on the back (-Z) of the last phalanx."""
    s_center = L - r * 0.6 - L * 0.22
    center_local = np.array([0.0, -s_center, -r * 0.66])
    origin = pivot + basis @ center_local
    half_len = L * 0.27
    profile = [(-half_len * 0.0, r * 0.62, r * 0.24, 0, 0), (half_len * 1.0, r * 0.58, r * 0.22, 0, 0)]
    nb = basis
    loft(part, origin + nb @ np.array([0, half_len * 0.5, 0]), nb, profile, ellipse, 14, NAIL_UV, cap_rings=4)


# ----------------------------------------------------------------------------- poses

def finger_pose(c1, c2, c3, spread=0.0):
    return [[-c1, 0.0, spread], [-c2, 0.0, 0.0], [-c3, 0.0, 0.0]]


def make_poses():
    poses = {}

    def pose(hand, fingers, thumb):
        p = {'hand': hand}
        for name, segs in fingers.items():
            for i, rot in enumerate(segs):
                p[f'{name}_{i + 1}'] = rot
        for i, rot in enumerate(thumb):
            p[f'thumb_{i + 1}'] = rot
        return p

    poses['bind'] = pose([0, 0, 0],
                         {f: finger_pose(0, 0, 0) for f in FINGERS},
                         [[0, 0, 0], [0, 0, 0], [0, 0, 0]])
    poses['relaxed'] = pose([8, 0, 0],
                            {'index': finger_pose(12, 18, 10, 2), 'middle': finger_pose(15, 22, 12),
                             'ring': finger_pose(18, 25, 14, -2), 'pinky': finger_pose(22, 28, 16, -4)},
                            [[-8, 0, 0], [-12, 0, 0], [-10, 0, 0]])
    poses['fist'] = pose([-6, 0, 0],
                         {'index': finger_pose(88, 100, 62, -2), 'middle': finger_pose(90, 102, 62),
                          'ring': finger_pose(92, 100, 60, 2), 'pinky': finger_pose(95, 98, 58, 5)},
                         [[-38, -10, 0], [-42, 0, 0], [-38, 0, 0]])
    # The concept pose: a reaching, grabbing claw.
    poses['claw'] = pose([32, 0, 0],
                         {'index': finger_pose(32, 52, 38, 5), 'middle': finger_pose(28, 58, 42),
                          'ring': finger_pose(30, 56, 40, -4), 'pinky': finger_pose(34, 52, 36, -9)},
                         [[6, 0, 0], [-14, 0, 0], [-26, 0, 0]])
    # Firing: palm wide open toward the target, fingers spread.
    poses['open'] = pose([72, 0, 0],
                         {'index': finger_pose(4, 6, 4, 9), 'middle': finger_pose(2, 5, 3, 1),
                          'ring': finger_pose(4, 6, 4, -6), 'pinky': finger_pose(6, 8, 5, -15)},
                         [[16, 0, 0], [-4, 0, 0], [-6, 0, 0]])
    # Strain while the laser fires: fingers hyper-extended and a bit curled at the tips.
    poses['strain'] = pose([80, 0, 0],
                           {'index': finger_pose(-8, 14, 18, 12), 'middle': finger_pose(-10, 16, 20, 1),
                            'ring': finger_pose(-8, 14, 18, -8), 'pinky': finger_pose(-6, 12, 16, -19)},
                           [[22, 0, 0], [-8, 0, 0], [-14, 0, 0]])
    return poses


# ----------------------------------------------------------------------------- output

def write(bones, parts, poses, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    lines = ['# Orbital Laser - Sky Hand. Generated by tools/hand_generator.py',
             '# One group per bone. Y up, fingers toward -Y, palm toward +Z. 1 unit = palm length.']
    vi = 1
    for name, _, _, _ in bones:
        p = parts[name]
        lines.append(f'g {name}')
        for v in p.v:
            lines.append('v %.5f %.5f %.5f' % tuple(v))
        for t in p.t:
            lines.append('vt %.5f %.5f' % (t[0], 1.0 - t[1]))   # OBJ v goes up
        for n in p.n:
            n = norm(n)
            lines.append('vn %.4f %.4f %.4f' % tuple(n))
        for f in p.f:
            a, b, c = (i + vi for i in f)
            lines.append(f'f {a}/{a}/{a} {b}/{b}/{b} {c}/{c}/{c}')
        vi += len(p.v)
    with open(os.path.join(out_dir, 'sky_hand.obj'), 'w') as fh:
        fh.write('\n'.join(lines) + '\n')

    rig = {'units': 'palm length', 'bones': []}
    for name, parent, pivot, basis in bones:
        rig['bones'].append({
            'name': name,
            'parent': parent,
            'pivot': [round(float(x), 5) for x in pivot],
            'rest': [round(float(x), 6) for x in mat_to_quat(basis)],
        })
    with open(os.path.join(out_dir, 'sky_hand_rig.json'), 'w') as fh:
        json.dump(rig, fh, indent=2)

    with open(os.path.join(out_dir, 'sky_hand_poses.json'), 'w') as fh:
        json.dump({'convention': 'degrees [x, y, z] in the bone rest frame, applied as Ry*Rz*Rx; '
                                 'negative x closes a finger toward the palm',
                   'poses': poses}, fh, indent=1)

    tris = sum(len(parts[n].f) for n, _, _, _ in bones)
    verts = sum(len(parts[n].v) for n, _, _, _ in bones)
    print(f'{len(bones)} bones, {verts} vertices, {tris} triangles -> {out_dir}')


if __name__ == '__main__':
    b, p = build()
    write(b, p, make_poses(), OUT)
