package com.huuda.orbitallaser.skybreak;

import static com.huuda.orbitallaser.skybreak.SbMath.*;

/**
 * Places the 18 rigid pieces of the sculpted arm for a pose: a Java port of `hd_set`, `hb_bone`, `hd_hand_b` and
 * `hd_arm_b` from Stink-O's Orbital Laser (MIT), mod/shaders/skybreak.wgsl.
 * <p>
 * Each piece's matrix maps its own frame (as in skybreak_arm.obj) to the world, in blocks from the target's ground
 * point. Matrices are 3x4, column-major: {x axis, y axis, z axis, origin}, 12 doubles.
 */
public final class ArmRig {
    public static final int PIECES = 18;

    /** Each finger's knuckle (x, y, z) and its head's radius, in the hand's frame (index to little). */
    private static final double[][] KN = {{10.9, 0.9, 7.0, 4.0}, {3.5, 1.6, 7.7, 4.2}, {-3.9, 1.1, 4.8, 3.8}, {-11.0, -0.3, 1.6, 3.3}};
    /** Each finger's length, half-width at its root, splay (radians, to the thumb), and how its curl leans in. */
    private static final double[][] DM = {{29.5, 3.8, 0.07, 0.05}, {33.1, 3.9, 0.012, -0.03}, {31.7, 3.65, -0.05, -0.15}, {25.2, 3.25, -0.14, -0.28}};
    private static final double[] LENS = {0.48, 0.29, 0.23};
    private static final double[] THUMB_LEN = {15.6, 10.8, 8.6};
    private static final double[] THUMB_ROLL = {0.4, 0.8, 1.0};

    public final double[][] mats = new double[PIECES][12];
    /** The palm's middle and the hand's axes, for effects (world, from the target's ground). */
    public double[] palm = new double[3];
    public double[] handAx = new double[3];
    public double[] handAy = new double[3];
    public double[] handAz = new double[3];

    public void set(ArmPlanner.Pose h, double side, double sc) {
        double[] ax = cross(h.ay, h.az);
        double[] p = h.palm();
        palm = p;
        handAx = ax;
        handAy = h.ay;
        handAz = h.az;
        // the hand's frame: l -> p + sc * (side * l.x * ax + l.y * ay + l.z * az)
        double[] hx = mul(ax, sc * side);
        double[] hy = mul(h.ay, sc);
        double[] hz = mul(h.az, sc);

        // ---- fingers
        double cv = clamp01((h.fl[0] + h.fl[1] + h.fl[2] + h.fl[3]) * 0.25);
        for (int i = 0; i < 4; i++) {
            double[] kn = {KN[i][0], KN[i][1], KN[i][2]};
            double[] dm = DM[i];
            double c = h.fl[i];
            kn[1] -= cv * (i > 1 ? (i == 3 ? 1.7 : 0.7) : 0.0);
            double spl = dm[2] * (1.0 + 1.6 * h.spread) * (1.0 - 0.6 * clamp01(c));
            double[] f = {Math.sin(spl), 0.0, Math.cos(spl)};
            double[] u = {dm[3] * clamp01(c * 1.4), 1.0, 0.0};
            u = norm(sub(u, mul(f, dot(u, f))));
            double t1 = 1.5 * (c - 0.5 * h.hk[i]);
            double t2 = 1.92 * clamp01(c + 0.5 * h.hk[i]);
            double t3 = 0.67 * t2;
            double[] ang = {t1, t1 + t2, t1 + t2 + t3};
            double[] pos = kn;
            for (int s = 0; s < 3; s++) {
                double[] fs = sub(mul(f, Math.cos(ang[s])), mul(u, Math.sin(ang[s])));
                double[] us = add(mul(u, Math.cos(ang[s])), mul(f, Math.sin(ang[s])));
                double[] rs = cross(us, fs);
                bone(i * 3 + s, p, hx, hy, hz, pos, rs, us, fs);
                pos = add(pos, mul(fs, LENS[s] * dm[0]));
            }
        }

        // ---- thumb
        double c = clamp01(h.th);
        double s = h.spread;
        double[] o1 = {0.54 + 0.2 * s, -0.28, 0.78};
        double[] o2 = {0.5 + 0.22 * s, -0.24, 0.83};
        double[] c1 = {-0.52, -0.59, 0.62};
        double[] c2 = {-0.95, 0.0, 0.3};
        double[] m1 = {0.3, -0.5, 0.81};
        double[][] tf = {
                norm(mix(new double[]{0.7 + 0.2 * s, -0.33, 0.63 - 0.15 * s}, m1, c)),
                norm(mix(o1, c1, c)),
                norm(mix(o2, c2, c))};
        double[] tp = {8.6, -3.4, -16.5};
        for (int k = 0; k < 3; k++) {
            double[] uk = norm(mix(new double[]{0.8, 0.55, -0.2}, new double[]{0.25, -0.95, 0.15}, c * THUMB_ROLL[k]));
            double[] tu = norm(sub(uk, mul(tf[k], dot(uk, tf[k]))));
            double[] tr = cross(tu, tf[k]);
            bone(12 + k, p, hx, hy, hz, tp, tr, tu, tf[k]);
            tp = add(tp, mul(tf[k], THUMB_LEN[k]));
        }

        // ---- palm: the hand's frame itself
        put(15, hx, hy, hz, p);

        // ---- forearm: the wrist's frame, z toward the hand
        double[] a = sub(h.el, h.w);
        double lf = Math.max(len(a), 1e-3);
        double[] ad = mul(a, 1.0 / lf);
        double[] x0 = norm(add(sub(ax, mul(ad, dot(ax, ad))), new double[]{1e-4, 0.0, 0.0}));
        double[] y0 = cross(x0, ad);
        double zs = ArmPlanner.HD_FORE * sc / lf;
        put(16, mul(x0, side * sc), mul(y0, sc), mul(ad, -sc / zs), h.w);

        // ---- upper arm: the forearm's frame turned the short way to the shoulder's, from the elbow
        double[] ue = norm(sub(h.el, h.sh));
        double[] b = mul(ue, -1.0);
        double[] ab = add(ad, b);
        double kk = 1.0 / Math.max(1.0 + dot(ad, b), 0.05);
        double[] x1 = add(sub(x0, mul(ab, dot(ab, x0) * kk)), mul(b, 2.0 * dot(ad, x0)));
        double[] y1 = add(sub(y0, mul(ab, dot(ab, y0) * kk)), mul(b, 2.0 * dot(ad, y0)));
        put(17, mul(x1, side * sc), mul(y1, sc), mul(ue, sc), h.el);
    }

    /** A bone piece: q -> hand(a + q.x * r + q.y * u + q.z * f). */
    private void bone(int k, double[] p, double[] hx, double[] hy, double[] hz,
                      double[] a, double[] r, double[] u, double[] f) {
        double[] cx = lin(hx, hy, hz, r);
        double[] cy = lin(hx, hy, hz, u);
        double[] cz = lin(hx, hy, hz, f);
        double[] o = add(p, lin(hx, hy, hz, a));
        put(k, cx, cy, cz, o);
    }

    private static double[] lin(double[] hx, double[] hy, double[] hz, double[] v) {
        return new double[]{
                hx[0] * v[0] + hy[0] * v[1] + hz[0] * v[2],
                hx[1] * v[0] + hy[1] * v[1] + hz[1] * v[2],
                hx[2] * v[0] + hy[2] * v[1] + hz[2] * v[2]};
    }

    private void put(int k, double[] cx, double[] cy, double[] cz, double[] o) {
        double[] m = mats[k];
        m[0] = cx[0];
        m[1] = cx[1];
        m[2] = cx[2];
        m[3] = cy[0];
        m[4] = cy[1];
        m[5] = cy[2];
        m[6] = cz[0];
        m[7] = cz[1];
        m[8] = cz[2];
        m[9] = o[0];
        m[10] = o[1];
        m[11] = o[2];
    }
}
