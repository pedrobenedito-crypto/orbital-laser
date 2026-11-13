#!/usr/bin/env python3
"""
Animatic of the Sky Hand sequence, seen from the ground near the target.
It is a Python port of client/hand/HandAnimator.java (same timeline, poses and rig math), meant for
reviewing timing and staging without launching Minecraft. Not pixel-identical to the game.

usage: hand_animatic.py <out_dir> [step_ticks]   -> frames PNG in out_dir (use ffmpeg to make a video)
"""
import json
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(__file__))
import hand_preview as hp  # noqa: E402

MODEL = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources', 'assets', 'orbital_laser',
                     'models', 'sky_hand')
TEX = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources', 'assets', 'orbital_laser',
                   'textures', 'entity', 'sky_hand.png')

# ---- HandAnimator port
P1, P2, P3 = 0.16, 0.36, 0.56
FIST_REACH = 1.19
CONTACT = -FIST_REACH
WINDUP = -(FIST_REACH + 0.9)
ANTIC = WINDUP - 0.35
HIDDEN = -(FIST_REACH + 3.6)
OPEN_EXT = 1.35
CHARGE = 100
FALL = 8


def clamp(v):
    return max(0.0, min(1.0, v))


def lerp(a, b, t):
    return a + (b - a) * t


def ease_out(x):
    i = 1 - clamp(x)
    return 1 - i ** 3


def ease_in(x):
    return clamp(x) ** 3


def ease_io(x):
    c = clamp(x)
    return 4 * c ** 3 if c < 0.5 else 1 - (-2 * c + 2) ** 3 / 2


def delay(b):
    for k, d in (('thumb', 0), ('index', .06), ('middle', .12), ('ring', .18), ('pinky', .24)):
        if b.startswith(k):
            return d
    return 0


def blend(a, b, t, stagger):
    out = {}
    for k in set(a) | set(b):
        va, vb = a.get(k, [0, 0, 0]), b.get(k, [0, 0, 0])
        tt = t
        if stagger:
            dd = delay(k) + (0.04 if k.endswith('_2') else 0.08 if k.endswith('_3') else 0)
            tt = ease_io(clamp((t - dd) / 0.68))
        out[k] = [lerp(va[i], vb[i], tt) for i in range(3)]
    return out


def charge_ext(p):
    if p < 0.06:
        return lerp(HIDDEN, WINDUP, ease_out(p / 0.06))
    for i, imp in enumerate((P1, P2, P3)):
        if p < imp - 0.10:
            return WINDUP
        if p < imp - 0.05:
            return lerp(WINDUP, ANTIC, ease_io((p - (imp - 0.10)) / 0.05))
        if p < imp:
            x = (p - (imp - 0.05)) / 0.05
            return lerp(ANTIC, CONTACT, x ** 3)
        if i < 2 and p < imp + 0.09:
            return lerp(CONTACT, WINDUP, ease_out(clamp((p - imp - 0.01) / 0.08)))
    if p < P3 + 0.06:
        return lerp(CONTACT, 0.25, ease_out((p - P3) / 0.06))
    if p < 0.82:
        return lerp(0.25, OPEN_EXT, ease_io((p - P3 - 0.06) / (0.82 - P3 - 0.06)))
    return OPEN_EXT


def frame(poses, tick):
    """tick: 0..CHARGE charging, then striking, END at CHARGE+60 (retract)."""
    f = {}
    if tick <= CHARGE:
        p = tick / CHARGE
        f['ext'] = charge_ext(p)
        if p < .60:
            f['pose'] = poses['fist']
        elif p < .72:
            f['pose'] = blend(poses['fist'], poses['claw'], ease_io((p - .6) / .12), True)
        elif p < .86:
            f['pose'] = blend(poses['claw'], poses['open'], ease_io((p - .72) / .14), True)
        else:
            f['pose'] = poses['open']
        f['reveal'] = 0 if p < P1 else 0.22 * ease_out((p - P1) / .03) if p < P2 else \
            0.22 + 0.28 * ease_out((p - P2) / .03) if p < P3 else 0.5 + 0.5 * ease_out((p - P3) / .12)
        f['hole'] = 0 if p < P3 else ease_out((p - P3) / .04)
        f['ghost'] = 0.16 + 0.1 * (p >= P1) + 0.1 * (p >= P2) + 0.12 * (p >= P3)
        f['core'] = 0 if p < .8 else ease_out((p - .8) / .2)
        f['phase'] = 'charge'
        return f
    t = tick - CHARGE
    if t <= 60:
        rec = ease_out(t / 5) if t < 5 else 1 - ease_io((t - 5) / 20) if t < 25 else 0
        f['ext'] = OPEN_EXT - 0.35 * rec
        f['pose'] = blend(poses['open'], poses['strain'], ease_out(t / 10), True)
        f.update(reveal=1, hole=1, ghost=0.48, core=1, phase='strike', t=t)
        return f
    base = frame(poses, CHARGE + 60)
    age = t - 60
    base['pose'] = blend(base['pose'], poses['fist'], ease_io(age / 15), True)
    base['ext'] = lerp(base['ext'], HIDDEN, ease_in((age - 8) / 47))
    base['phase'] = 'end'
    base['age'] = age
    base['core'] = 0
    return base


# ---- rendering
W, H = 640, 400
FOCAL = 330.0
SCALE = 24.0
SKY = 150.0
YAW, TILT = 35.0, 18.0


def look_at(eye, target):
    f = target - eye
    f /= np.linalg.norm(f)
    r = np.cross(f, [0, 1, 0])
    r /= np.linalg.norm(r)
    u = np.cross(r, f)
    return np.array([r, u, -f]), eye


def project(R, eye, pts):
    c = (pts - eye) @ R.T
    z = -c[..., 2]
    x = W / 2 + FOCAL * c[..., 0] / z
    y = H / 2 - FOCAL * c[..., 1] / z
    return x, y, z


def raster(tris_w, tris_uv, tris_n, R, eye, tex, light):
    img = np.zeros((H, W, 4))
    zb = np.full((H, W), 1e9)
    wy = np.zeros((H, W))
    sx, sy, sz = project(R, eye, tris_w)
    for i in range(len(tris_w)):
        if (sz[i] <= 1).any():
            continue
        x0, y0 = sx[i], sy[i]
        minx = max(int(x0.min()), 0); maxx = min(int(x0.max()) + 1, W - 1)
        miny = max(int(y0.min()), 0); maxy = min(int(y0.max()) + 1, H - 1)
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
        if not ins.any():
            continue
        z = w0 * sz[i, 0] + w1 * sz[i, 1] + w2 * sz[i, 2]
        yy = (ys - .5).astype(int); xx = (xs - .5).astype(int)
        upd = ins & (z < zb[yy, xx])
        if not upd.any():
            continue
        n = w0[..., None] * tris_n[i, 0] + w1[..., None] * tris_n[i, 1] + w2[..., None] * tris_n[i, 2]
        n /= np.linalg.norm(n, axis=-1, keepdims=True) + 1e-9
        sh = 0.45 + 0.55 * np.clip(np.abs(n @ light), 0, 1)
        u = w0 * tris_uv[i, 0, 0] + w1 * tris_uv[i, 1, 0] + w2 * tris_uv[i, 2, 0]
        v = w0 * tris_uv[i, 0, 1] + w1 * tris_uv[i, 1, 1] + w2 * tris_uv[i, 2, 1]
        col = tex[np.clip((v * tex.shape[0]).astype(int), 0, tex.shape[0] - 1),
                  np.clip((u * tex.shape[1]).astype(int), 0, tex.shape[1] - 1)] * sh[..., None]
        img[yy[upd], xx[upd], :3] = col[upd]
        img[yy[upd], xx[upd], 3] = 1
        zb[yy[upd], xx[upd]] = z[upd]
        py = w0 * tris_w[i, 0, 1] + w1 * tris_w[i, 1, 1] + w2 * tris_w[i, 2, 1]
        wy[yy[upd], xx[upd]] = py[upd]
    return img, wy


def main(out_dir, step):
    os.makedirs(out_dir, exist_ok=True)
    rig = json.load(open(f'{MODEL}/sky_hand_rig.json'))
    poses = json.load(open(f'{MODEL}/sky_hand_poses.json'))['poses']
    V, T, N, groups = hp.load_obj(f'{MODEL}/sky_hand.obj')
    tex = np.asarray(Image.open(TEX).convert('RGB'), dtype=float) / 255
    arm = hp.ry(YAW) @ hp.rx(TILT)
    dvec = arm @ np.array([0, -1, 0])
    eye = np.array([75.0, 1.7, 80.0])
    R, eye = look_at(eye, np.array([0.0, SKY * 0.66, 0.0]))
    light = np.array([0.4, 0.75, 0.5])
    light /= np.linalg.norm(light)
    rnd = np.random.default_rng(3)
    cracks = []
    for i in range(14):
        a = i / 14 * 2 * math.pi + rnd.uniform(-.2, .2)
        pts = [(0.0, 0.0)]
        ang = a
        r = 0
        while r < 140 * rnd.uniform(.6, 1):
            ang += rnd.uniform(-.25, .25)
            r += rnd.uniform(7, 15)
            pts.append((math.cos(ang) * r, math.sin(ang) * r))
        cracks.append(pts)

    total = CHARGE + 60 + 60
    for idx, tick in enumerate(range(0, total + 1, step)):
        f = frame(poses, tick)
        img = Image.new('RGB', (W, H))
        sky = np.zeros((H, W, 3))
        grad = np.linspace(0, 1, H)[:, None]
        sky[:] = (np.array([0.42, 0.62, 0.95]) * (1 - grad) + np.array([0.68, 0.8, 0.98]) * grad)[:, None, :]
        ground_y = int(H * 0.93)
        sky[ground_y:] = np.array([0.35, 0.5, 0.25])
        # darkening and cracks
        canvas = Image.fromarray((sky * 255).astype(np.uint8))
        dr = ImageDraw.Draw(canvas, 'RGBA')
        reveal = f['reveal'] * 140
        for pts in cracks:
            P = np.array([[x, SKY, z] for x, z in pts])
            x, y, z = project(R, eye, P)
            seg = []
            for k in range(len(pts)):
                if math.hypot(*pts[k]) <= reveal + 1e-6:
                    seg.append((x[k], y[k]))
            if len(seg) > 1:
                dr.line(seg, fill=(150, 220, 255, 140), width=5)
                dr.line(seg, fill=(240, 252, 255, 255), width=2)
        if f['hole'] > 0:
            hr = f['hole'] * SCALE * 0.95
            ring = np.array([[math.cos(a) * hr * (0.8 + 0.3 * math.sin(a * 7)), SKY, math.sin(a) * hr * (0.8 + 0.3 * math.sin(a * 7))]
                             for a in np.linspace(0, 2 * math.pi, 40)])
            x, y, z = project(R, eye, ring)
            dr.polygon(list(zip(x, y)), fill=(8, 0, 16, 235), outline=(160, 230, 255, 255))
        # hand
        mats = hp.bone_matrices(rig, f['pose'])
        Wm = np.eye(4); Wm[:3, :3] = arm * SCALE; Wm[:3, 3] = np.array([0, SKY, 0]) + dvec * f['ext'] * SCALE
        tw, tu, tn = [], [], []
        for g, faces in groups.items():
            M = Wm @ mats[g]
            Rn = arm @ mats[g][:3, :3]
            fa = np.array(faces)
            tw.append((M[:3, :3] @ V[fa[..., 0]].reshape(-1, 3).T).T.reshape(-1, 3, 3) + M[:3, 3])
            tu.append(T[fa[..., 1]])
            tn.append((Rn @ N[fa[..., 2]].reshape(-1, 3).T).T.reshape(-1, 3, 3))
        hand_img, wy = raster(np.vstack(tw), np.vstack(tu), np.vstack(tn), R, eye, tex, light)
        base = np.asarray(canvas, dtype=float) / 255
        a = hand_img[..., 3:4]
        ghost_tint = hand_img[..., :3] * np.array([0.62, 0.72, 0.92])
        base = base * (1 - a * f['ghost']) + ghost_tint * a * f['ghost']
        solid = (a[..., 0] > 0) & (wy < SKY) & (f['hole'] > 0)
        base[solid] = hand_img[solid, :3]
        canvas = Image.fromarray((np.clip(base, 0, 1) * 255).astype(np.uint8))
        dr = ImageDraw.Draw(canvas, 'RGBA')
        # palm, core, beam
        palm = (Wm @ mats['hand'] @ np.array([0, -0.55, 0.34, 1]))[:3]
        gpt = np.array([0.0, 0.0, 0.0])
        if f['phase'] == 'strike' and f['t'] >= FALL:
            px, py, _ = project(R, eye, np.array([palm, gpt]))
            for wdt, col in ((26, (120, 200, 255, 70)), (14, (180, 235, 255, 140)), (5, (255, 255, 255, 255))):
                dr.line([(px[0], py[0]), (px[1], py[1])], fill=col, width=wdt)
        if f['core'] > 0:
            pos = palm.copy()
            if f['phase'] == 'strike':
                fall = clamp(f['t'] / FALL)
                pos = palm + (gpt - palm) * fall * fall
                if fall >= 1:
                    pos = None
            if pos is not None:
                x, y, z = project(R, eye, np.array([pos]))
                rr = FOCAL * 24 * 0.3 * f['core'] * 0.5 / z[0]
                dr.ellipse([x[0] - rr * 1.5, y[0] - rr * 1.5, x[0] + rr * 1.5, y[0] + rr * 1.5], fill=(120, 60, 200, 60))
                dr.ellipse([x[0] - rr, y[0] - rr, x[0] + rr, y[0] + rr], fill=(18, 8, 30, 250))
        label = f"{f['phase']}  tick {tick}"
        dr.text((10, 10), label, fill=(255, 255, 255, 255))
        canvas.save(os.path.join(out_dir, f'f{idx:04d}.png'))
    print('frames ->', out_dir)


if __name__ == '__main__':
    main(sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 2)
