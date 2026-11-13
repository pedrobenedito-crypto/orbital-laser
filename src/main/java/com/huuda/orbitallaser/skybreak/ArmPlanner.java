package com.huuda.orbitallaser.skybreak;

import static com.huuda.orbitallaser.skybreak.SbMath.*;

/**
 * Where the Sky Breaks' arm is at any moment: a Java port of `Arm` in Stink-O's Orbital Laser (MIT),
 * mod/src/strike/sb_hand.rs. Positions are blocks from the target's ground point.
 * <p>
 * The punch (6.3 s to 12.9 s): an open hand laid on the sky from behind, rolled up onto its knuckles, drawn back and
 * thrown through the glass at SB_CRACK, then drawn back up through the hole. The slam (33.8 s to 43 s): a fist thrown
 * straight down through the hole on to the target at HD_PRESS, held in the land, then lifted away.
 */
public final class ArmPlanner {
    public static final double SC = SbTime.HD_SC;
    public static final double HD_FORE = 96.0;
    public static final double HD_UPPER = 138.0;
    private static final double HD_REACH = 36.0;
    public static final double HD_PALM = 24.0;
    private static final double HD_FIST_MID = 6.0;
    private static final double SB_DEEP = 12.0;
    private static final double SLAM_BEND_UP = 0.56;
    private static final double SLAM_BEND_HIT = 0.29;
    private static final double SLAM_TOP = 200.0;
    private static final double SLAM_WHIP = 2.4;

    /** One pose of the arm (the original's ArmPose). */
    public static final class Pose {
        public double[] w;
        public double[] el;
        public double[] sh;
        /** The way to the fingers and the way the back of the hand faces. */
        public double[] az;
        public double[] ay;
        public final double[] fl = new double[4];
        public final double[] hk = new double[4];
        public double th;
        public double spread;
        /** How much of the arm there is (0 = gone). */
        public double k;

        public double[] palm() {
            return add(w, mul(az, HD_PALM * SC));
        }
    }

    private final double[] fw;
    private final double[] dir;
    private final double side;
    private final double g0;
    private final double ph;
    private final double[][] liftKeys;
    private final double bR;
    private final double vbR;

    /**
     * @param me    the player's position at firing, from the target's ground point
     * @param dir   the way the back of the fist faces along the land (unit x, z)
     * @param side  which hand (1 or -1)
     * @param g0    the land's height at the fist's site, from the target's ground (usually 0)
     * @param seed  the strike's seed
     */
    public ArmPlanner(double[] me, double[] dir, double side, double g0, int seed) {
        double l = Math.hypot(me[0], me[2]);
        this.fw = l > 1.0 ? new double[]{me[0] / l, 0.0, me[2] / l} : new double[]{0.0, 0.0, 1.0};
        this.dir = dir;
        this.side = side;
        this.g0 = g0;
        this.ph = 40.0 * mh(5, seed, 0x1a4);
        // (the slam's keys, worked out once)
        double[] wIn = {slamHeights()[1]};
        double h = 1.0 / 240.0;
        Object[] p0 = planted(SbTime.HD_LIFT - h);
        Object[] p1 = planted(SbTime.HD_LIFT + h);
        Object[] pr = planted(SbTime.HD_LIFT);
        double[][] k = new double[6][7];
        double[] q0 = slamPolar((double[]) pr[0], SbTime.HD_LIFT);
        double[] qa = slamPolar((double[]) p0[0], SbTime.HD_LIFT - h);
        double[] qb = slamPolar((double[]) p1[0], SbTime.HD_LIFT + h);
        double[] vel = mul(sub(qb, qa), 0.5 / h);
        set(k[0], SbTime.HD_LIFT, q0, vel);
        set(k[1], SbTime.HD_LIFT + 0.45, keyPose(0.98, wIn[0] + 34.0, SbTime.HD_LIFT + 0.45, 0.12), new double[3]);
        set(k[2], SbTime.HD_LIFT + 0.95, keyPose(0.66, g0 + 215.0, SbTime.HD_LIFT + 0.95, 0.2), new double[3]);
        set(k[3], SbTime.HD_LIFT + 1.65, new double[]{700.0, 0.006, 0.0}, new double[3]);
        set(k[4], SbTime.HD_LIFT + 2.6, new double[]{1080.0, 0.0, 0.0}, new double[3]);
        set(k[5], SbTime.HD_LIFT + 3.8, new double[]{1300.0, 0.0, 0.0}, new double[3]);
        for (int i = 1; i < 5; i++) {
            double dt = k[i + 1][0] - k[i - 1][0];
            for (int c = 0; c < 3; c++) {
                k[i][4 + c] = (k[i + 1][1 + c] - k[i - 1][1 + c]) / dt;
            }
        }
        this.liftKeys = k;
        this.bR = (double) pr[1];
        this.vbR = ((double) p1[1] - (double) p0[1]) * 0.5 / h;
    }

    private static void set(double[] row, double t, double[] p, double[] v) {
        row[0] = t;
        row[1] = p[0];
        row[2] = p[1];
        row[3] = p[2];
        row[4] = v[0];
        row[5] = v[1];
        row[6] = v[2];
    }

    public double side() {
        return side;
    }

    /** The arm at t (seconds after the strike started), or null when it is not there. */
    public Pose pose(double t) {
        if (t >= SbTime.PUNCH_FROM && t <= SbTime.PUNCH_TO) {
            return punch(t);
        }
        if (t >= SbTime.SLAM_FROM && t <= SbTime.SLAM_TO) {
            return slam(t);
        }
        return null;
    }

    // ================================================================= chains

    /** An arm hung from shoulder s down through the hole at p, its elbow bent by beta toward hint: {elbow, wrist}. */
    private static double[][] hang(double[] s, double[] p, double beta, double[] hint, double lu, double lf) {
        double[] n = sub(p, s);
        double dd = Math.max(len(n), 1e-6);
        n = mul(n, 1.0 / dd);
        double[] b = squareTo(hint, n);
        double sb = Math.sin(beta);
        double cb = Math.cos(beta);
        double x = -lu * cb + Math.sqrt(Math.max(dd * dd - lu * lu * sb * sb, 0.0));
        double g = Math.asin(clamp(corner(x, 30.0) * sb / dd, -1.0, 1.0));
        double[] u = sub(mul(n, Math.cos(g)), mul(b, Math.sin(g)));
        double[] f = add(mul(n, Math.cos(beta - g)), mul(b, Math.sin(beta - g)));
        double[] el = add(s, mul(u, lu));
        return new double[][]{el, add(el, mul(f, lf))};
    }

    /** The shoulder that puts such an arm's wrist at w. */
    private static double[] shoulderFor(double[] w, double[] p, double beta, double[] hint, double lu, double lf) {
        double[] pw = sub(w, p);
        double r = Math.max(len(pw), 1e-6);
        double[] n = mul(pw, 1.0 / r);
        double[] b = squareTo(hint, n);
        double sb = Math.sin(beta);
        double a = -lf * Math.cos(beta) + Math.sqrt(Math.max(r * r - lf * lf * sb * sb, 0.0));
        double g = Math.asin(clamp(lf * sb / r, -1.0, 1.0));
        double[] u = sub(mul(n, Math.cos(g)), mul(b, Math.sin(g)));
        return sub(p, mul(u, lu - a));
    }

    /** An arm thrown down through p along n, its wrist r along it: {shoulder, elbow, wrist}. */
    private static double[][] thrown(double[] p, double[] n, double r, double beta, double[] hint, double lu, double lf) {
        double[] b = squareTo(hint, n);
        double g = Math.asin(clamp(lf * Math.sin(beta) / (lf + corner(r - lf, 70.0)), -1.0, 1.0));
        double[] f = add(mul(n, Math.cos(beta - g)), mul(b, Math.sin(beta - g)));
        double[] u = sub(mul(n, Math.cos(g)), mul(b, Math.sin(g)));
        double[] w = add(p, mul(n, r));
        double[] el = sub(w, mul(f, lf));
        return new double[][]{sub(el, mul(u, lu)), el, w};
    }

    /** A hand's frame from the way to its fingers and a hint of its back's way: {az, ay}. */
    private static double[][] frame(double[] az, double[] back) {
        double[] z = norm(az);
        double[] y = norm(add(sub(back, mul(z, dot(back, z))), new double[]{1e-6, 0.0, 0.0}));
        return new double[][]{z, y};
    }

    private static double[][] handFrame(double[] az, double[] ay, double[] f, double roll, double flex) {
        double[] z = rotAbout(f, roll, az);
        double[] y = rotAbout(f, roll, ay);
        double s = Math.sin(flex);
        double c = Math.cos(flex);
        return frame(sub(mul(z, c), mul(y, s)), add(mul(y, c), mul(z, s)));
    }

    // ================================================================= the slam's helpers

    private double[][] slamDirs() {
        double[] d = {dir[0], 0.0, dir[1]};
        return new double[][]{d, {-d[2], 0.0, d[0]}};
    }

    private double[] slamPort(double t) {
        return add(new double[]{0.0, SbTime.SB_H, 0.0}, mul(slamDirs()[0], 2.5 * drift(t * 0.4 + ph + 40.0)));
    }

    private double[] slamHeights() {
        double top = g0 + HD_REACH * SC;
        return new double[]{top - SB_DEEP * SC * 0.55, top - SB_DEEP * SC};
    }

    private double[] slamAt(double k, double y) {
        double[] d = slamDirs()[0];
        double[] wxz = add(new double[]{0.0, 0.0, 0.0}, mul(d, HD_FIST_MID * SC));
        return new double[]{wxz[0] * k, y, wxz[2] * k};
    }

    private double[] slamIk(double[] w, double t, double beta) {
        return shoulderFor(w, slamPort(t), beta, mul(slamDirs()[0], -1.0), HD_UPPER * SC, HD_FORE * SC);
    }

    private double[] slamPolar(double[] sh, double t) {
        double[][] ds = slamDirs();
        double[] r = sub(sh, slamPort(t));
        double x = dot(r, ds[0]);
        double y = r[1];
        return new double[]{Math.hypot(x, y), Math.atan2(-x, y), dot(r, ds[1])};
    }

    private double[] keyPose(double k, double y, double t, double beta) {
        return slamPolar(slamIk(slamAt(k, y), t, beta), t);
    }

    /** {shoulder, beta} while the fist is planted in the land. */
    private Object[] planted(double t) {
        double a = t - SbTime.HD_PRESS;
        double[] hs = slamHeights();
        double wHit = hs[0];
        double wIn = hs[1];
        double bHit = SLAM_BEND_HIT;
        double y = wHit + (wIn - wHit) * (0.85 * (1.0 - Math.exp(-a / 0.07)) + 0.15 * ease5(a / 1.1));
        double x = a / 0.2;
        double beta = bHit + 0.18 * (1.0 - Math.exp(-a / 0.05)) - 0.12 * (1.0 - (1.0 + x) * Math.exp(-x))
                + 0.012 * ease5((a - 0.3) / 0.5) * drift(t * 0.8 + ph + 40.0)
                - (bHit + 0.06 - 0.11) * ease5((a - 0.9) / 0.9);
        return new Object[]{slamIk(slamAt(1.0, y), t, beta), beta};
    }

    // ================================================================= the punch

    private Pose punch(double t) {
        double a = t - SbTime.SB_CRACK;
        double sc = SC;
        double lf = HD_FORE * sc;
        double lu = HD_UPPER * sc;
        double[] out = {-fw[2], 0.0, fw[0]};
        double live = ease5((a - 0.1) / 0.5);
        final double vHit = 1150.0;
        final double prFrom = -2.7;
        final double prTouch = -1.85;
        final double prLean = -1.5;
        final double prFist = -1.08;
        final double prFlat = -1.0;
        double reach = HD_REACH * sc;
        double far = 214.0;
        double tau = 0.16;
        double dk;
        if (a < 0.0) {
            dk = chan(new double[][]{{prFrom, reach - 430.0, 480.0}, {prTouch, reach - 24.0, 0.0}, {prLean, reach - 21.0, 0.0},
                    {prFist, 3.0, 0.0}, {-0.42, -286.0, 0.0}, {0.0, 0.0, vHit}}, a);
        } else {
            dk = far - (far + (far / tau - vHit) * a) * Math.exp(-a / tau);
        }
        dk -= chan(new double[][]{{0.4, 0.0, 0.0}, {1.3, 26.0, 55.0}, {2.35, 222.0, 330.0}, {3.75, 800.0, 420.0}}, a);
        dk += 2.5 * live * drift(a * 0.9 + ph);
        double leanOut = chan(new double[][]{{prFrom, 0.14, 0.0}, {prTouch, 0.1, 0.0}, {prFist, 0.09, 0.0}, {-0.3, 0.11, 0.0},
                {0.0, 0.06, -0.35}, {0.45, -0.015, 0.0}, {1.4, 0.01, 0.05}, {2.5, 0.13, 0.12}, {3.8, 0.2, 0.0}}, a)
                + 0.012 * live * drift(a * 0.7 + ph + 3.1);
        double leanBack = chan(new double[][]{{prFrom, 0.6, 0.0}, {prTouch, 0.7, 0.0}, {prLean, 0.74, 0.0}, {prFist, 0.12, 0.0},
                {-0.3, 0.085, 0.0}, {0.0, 0.045, -0.3}, {0.5, -0.03, 0.0}, {1.4, -0.01, 0.04}, {2.6, 0.1, 0.1}, {3.8, 0.16, 0.0}}, a)
                + 0.01 * live * drift(a * 0.6 + ph + 7.7);
        double[] f0 = fore(0.06, 0.045, out);
        double[] f = fore(leanOut, leanBack, out);
        double slide = chan(new double[][]{{0.0, 0.0, 0.0}, {0.5, -9.0, 0.0}, {1.4, -7.0, 6.0}, {2.3, 14.0, 14.0}, {3.3, 22.0, 0.0}}, a);
        double back = chan(new double[][]{{prFrom, 100.0, 0.0}, {prTouch, 78.0, 0.0}, {prLean, 76.0, 0.0}, {prFist, 0.0, 0.0}}, a);
        double[] port = add(
                add(mul(fw, HD_FIST_MID * sc + slide - back), mul(new double[]{f0[0], 0.0, f0[2]}, reach / -f0[1])),
                add(mul(fw, 2.5 * live * drift(a * 0.5 + ph + 11.0)), mul(out, 3.0 * live * drift(a * 0.45 + ph + 19.0))));
        double below = dk - reach;
        double[] w = add(new double[]{port[0], SbTime.SB_H, port[2]}, mul(f, below / -f[1]));
        double[] el = sub(w, mul(f, lf));
        double beta = chan(new double[][]{{prFrom, 1.1, 0.0}, {prTouch, 1.3, 0.0}, {prLean, 1.4, 0.0}, {prFist, 1.62, 0.0},
                {-0.4, 1.85, 0.0}, {0.0, 0.8, -2.6}, {0.42, 0.3, 0.0}, {1.3, 0.36, 0.12}, {2.6, 0.85, 0.4}, {3.8, 1.1, 0.0}}, a);
        double[] bend = squareTo(add(mul(fw, 0.9), mul(out, -0.4)), f);
        double[] sh = sub(el, mul(add(mul(f, Math.cos(beta)), mul(bend, Math.sin(beta))), lu));
        double carry = 1.0 - 0.45 * ease5((a - 1.3) / 1.4);
        double[] fh = norm(mix(f0, f, carry));
        double roll = chan(new double[][]{{prFist - 0.1, 0.0, 0.0}, {-0.34, 0.55, 0.0}, {0.0, 0.0, -2.0}, {0.32, -0.28, 0.0},
                {1.2, -0.12, 0.08}, {2.4, 0.1, 0.25}, {3.8, 0.4, 0.0}}, a)
                + 0.03 * live * drift(a * 0.55 + ph + 23.0);
        double flex = chan(new double[][]{{prFrom, -0.74, 0.0}, {prTouch + 0.08, prFlat, 0.0}, {prLean, prFlat - 0.015, 0.0},
                {prFist, -0.12, 0.0}, {-0.3, -0.16, 0.0}, {0.0, 0.0, 0.9}, {0.24, 0.13, 0.0}, {0.8, 0.04, -0.03},
                {1.5, 0.06, 0.08}, {2.35, 0.2, 0.2}, {3.1, 0.5, 0.2}, {3.8, 0.6, 0.0}}, a)
                + 0.02 * live * drift(a * 0.65 + ph + 29.0);
        double[][] hf = handFrame(rotTo(f0, fh, new double[]{0.0, -1.0, 0.0}), rotTo(f0, fh, fw), f, roll, flex);

        Pose p = new Pose();
        p.w = w;
        p.el = el;
        p.sh = sh;
        p.az = hf[0];
        p.ay = hf[1];
        double sq = 0.05 * chan(new double[][]{{-0.1, 0.0, 0.0}, {0.22, 1.0, 0.0}, {1.3, 0.5, -0.4}, {2.3, 0.0, 0.0}}, a);
        double[] open = {0.32, 0.42, 0.5, 0.62};
        double[] laidA = {0.22, 0.24, 0.27, 0.32};
        double[] laidB = {0.07, 0.08, 0.09, 0.11};
        double[] lag = {0.0, 0.09, 0.19, 0.31};
        for (int i = 0; i < 4; i++) {
            double shut = 1.0 + sq * (0.75 + 0.25 * i);
            double t0 = 2.45 + lag[i];
            double laid = laidA[i] + (laidB[i] - laidA[i]) * ease5((a - prTouch + 0.4) / 0.45);
            double tc = prLean + 0.03 * i;
            double m = laid + (shut - laid) * ease5((a - tc) / 0.42) + (open[i] - shut) * ease5((a - t0) / 0.8);
            double pp = laid + (shut - laid) * ease5((a - tc + 0.08) / 0.42) + (open[i] - shut) * ease5((a - t0 - 0.2) / 0.85);
            p.fl[i] = 0.5 * (m + pp);
            p.hk[i] = pp - m;
        }
        p.th = Math.min(0.12 * (1.0 - ease5((a - prTouch + 0.35) / 0.4)) + ease5((a - prLean - 0.1) / 0.45), 1.0)
                * (1.0 - 0.6 * ease5((a - 2.8) / 0.9));
        p.spread = (0.25 + 0.4 * ease5((a - prTouch + 0.3) / 0.4)) * (1.0 - ease5((a - prLean) / 0.3));
        p.k = ease((a + 0.6) / 0.3) * (1.0 - ease((a - 2.9) / 0.7));
        return p;
    }

    private double[] fore(double lo, double lb, double[] out) {
        return norm(add(new double[]{0.0, -1.0, 0.0}, sub(mul(fw, lb), mul(out, lo))));
    }

    // ================================================================= the slam

    private double[][] blow(double t) {
        double lf = HD_FORE * SC;
        double lu = HD_UPPER * SC;
        double down = SbTime.HD_DOWN;
        double hit = SbTime.HD_PRESS;
        double[] line = sub(slamAt(1.0, slamHeights()[0]), slamPort(hit));
        double far = len(line);
        double v = SLAM_WHIP / (hit - down);
        double r = chan(new double[][]{{down, -SLAM_TOP, 0.0}, {hit, far, v * (far + SLAM_TOP)}}, t);
        double beta = chan(new double[][]{{down, SLAM_BEND_UP, 0.0}, {hit, SLAM_BEND_HIT, v * (SLAM_BEND_HIT - SLAM_BEND_UP)}}, t);
        return thrown(slamPort(t), mul(line, 1.0 / far), r, beta, mul(slamDirs()[0], -1.0), lu, lf);
    }

    private double liveAt(double t) {
        return ease5((t - SbTime.HD_LIFT - 0.1) / 0.9);
    }

    private double[][] armAt(double t) {
        if (t <= SbTime.HD_PRESS) {
            return blow(t);
        }
        double lf = HD_FORE * SC;
        double lu = HD_UPPER * SC;
        double[][] ds = slamDirs();
        double[] d = ds[0];
        double[] s = ds[1];
        double ph2 = ph + 40.0;
        double live = liveAt(t);
        double[] sh;
        double beta;
        if (t >= SbTime.HD_LIFT) {
            beta = chan(new double[][]{{SbTime.HD_LIFT, bR, vbR}, {SbTime.HD_LIFT + 0.45, 0.12, 0.0}, {SbTime.HD_LIFT + 0.95, 0.2, 0.0},
                    {SbTime.HD_LIFT + 1.65, 0.12, -0.05}, {SbTime.HD_LIFT + 2.6, 0.1, 0.0}}, t);
            double[] q = chan3(liftKeys, t);
            double[] port = slamPort(t);
            sh = add(port, add(add(mul(d, -q[0] * Math.sin(q[1])), mul(UP, q[0] * Math.cos(q[1]))), mul(s, q[2])));
        } else {
            Object[] pl = planted(t);
            sh = (double[]) pl[0];
            beta = (double) pl[1];
        }
        sh = add(sh, mul(d, 3.0 * live * drift(t * 0.5 + ph2 + 3.0)));
        sh = add(sh, mul(UP, 3.0 * live * drift(t * 0.43 + ph2 + 7.0)));
        sh = add(sh, mul(s, 4.0 * live * drift(t * 0.37 + ph2 + 11.0)));
        beta += 0.012 * live * drift(t * 0.6 + ph2 + 17.0);
        double[] hint = norm(add(mul(d, -1.0), mul(s, 0.14 * live * drift(t * 0.33 + ph2 + 23.0))));
        double[] p = add(slamPort(t), mul(s, 2.0 * live * drift(t * 0.29 + ph2 + 29.0)));
        double[][] ew = hang(sh, p, beta, hint, lu, lf);
        return new double[][]{sh, ew[0], ew[1]};
    }

    private Pose slam(double t) {
        double[][] ds = slamDirs();
        double[] d = ds[0];
        double[] s = ds[1];
        double ph2 = ph + 40.0;
        double hit = SbTime.HD_PRESS;
        double[][] arm = armAt(t);
        double live = liveAt(t);
        double[][] lag = armAt(t - 0.12);
        double[] trail = norm(sub(lag[2], lag[1]));
        double c = chan(new double[][]{{hit - 0.45, 0.34, 0.0}, {hit, 0.0, -1.8}}, t);
        double[] azT = add(mul(UP, -Math.cos(c)), mul(d, Math.sin(c)));
        double[] backT = add(mul(d, Math.cos(c)), mul(UP, Math.sin(c)));
        double[] azH = norm(mix(trail, mul(UP, -1.0), 0.55));
        double wH = ease5((t - SbTime.HD_LIFT - 0.05) / 1.1);
        double[] az = mix(azT, azH, wH);
        double[] back = add(mix(backT, d, wH), mul(s, 0.035 * live * drift(t * 0.7 + ph2 + 37.0)));
        double[][] fr = frame(az, back);

        Pose p = new Pose();
        p.sh = arm[0];
        p.el = arm[1];
        p.w = arm[2];
        p.az = fr[0];
        p.ay = fr[1];
        double tOpen = SbTime.HD_LIFT + 1.95;
        double[] slackK = {0.2, 0.17, 0.14, 0.12};
        double[] lagK = {0.0, 0.09, 0.19, 0.31};
        double[] openM = {0.2, 0.26, 0.32, 0.4};
        double[] openP = {0.4, 0.46, 0.52, 0.6};
        for (int i = 0; i < 4; i++) {
            double tight = 1.0 + 0.04 * ease5((t - hit + 0.6) / 0.45);
            double slack = ease5((t - SbTime.HD_LIFT - 0.2 - 0.07 * i) / 0.9);
            double m = tight - slackK[i] * slack;
            double pp = tight - 0.06 * slack;
            m += (openM[i] - m) * ease5((t - tOpen - lagK[i]) / 0.8);
            pp += (openP[i] - pp) * ease5((t - tOpen - lagK[i] - 0.2) / 0.85);
            p.fl[i] = 0.5 * (m + pp);
            p.hk[i] = pp - m;
        }
        p.th = 1.0 - 0.7 * ease5((t - tOpen - 0.25) / 0.9);
        p.spread = 0.2 * ease5((t - tOpen - 0.3) / 0.9);
        p.k = ease((t - SbTime.HD_DOWN) / 0.4) * (1.0 - ease((t - (SbTime.HD_GONE - 1.4)) / 1.4));
        return p;
    }
}
