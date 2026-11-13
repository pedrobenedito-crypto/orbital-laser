package com.huuda.orbitallaser.skybreak;

/**
 * Small vector and noise helpers shared by the Sky Breaks' server and client code.
 * Ported from Stink-O's Orbital Laser (MIT): mod/src/strike.rs (pcg, mh) and mod/src/strike/sb_hand.rs.
 * Vectors are double[3].
 */
public final class SbMath {
    public static final double TAU = Math.PI * 2.0;
    public static final double[] UP = {0.0, 1.0, 0.0};

    private SbMath() {
    }

    // ---------------------------------------------------------------- hashing (identical to the original's)

    public static int pcg(int x) {
        int s = x * 747796405 + (int) 2891336453L;
        int w = ((s >>> ((s >>> 28) + 4)) ^ s) * 277803737;
        return (w >>> 22) ^ w;
    }

    /** A random number in [0, 1) for (i, seed, k). */
    public static double mh(int i, int seed, int k) {
        return (pcg(i ^ pcg(seed ^ pcg(k))) & 0xFFFFFFFFL) / 4294967296.0;
    }

    /** Smooth value noise in [0, 1) (stands in for the original's qnoise). */
    public static double noise(double x, double z, int seed) {
        int ix = (int) Math.floor(x);
        int iz = (int) Math.floor(z);
        double fx = x - ix;
        double fz = z - iz;
        double a = mh(ix * 73856093 ^ iz * 19349663, seed, 1);
        double b = mh((ix + 1) * 73856093 ^ iz * 19349663, seed, 1);
        double c = mh(ix * 73856093 ^ (iz + 1) * 19349663, seed, 1);
        double d = mh((ix + 1) * 73856093 ^ (iz + 1) * 19349663, seed, 1);
        double ux = fx * fx * (3.0 - 2.0 * fx);
        double uz = fz * fz * (3.0 - 2.0 * fz);
        return a + (b - a) * ux + (c - a) * uz + (a - b - c + d) * ux * uz;
    }

    // ---------------------------------------------------------------- vectors

    public static double[] v(double x, double y, double z) {
        return new double[]{x, y, z};
    }

    public static double[] add(double[] a, double[] b) {
        return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    public static double[] sub(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    public static double[] mul(double[] a, double k) {
        return new double[]{a[0] * k, a[1] * k, a[2] * k};
    }

    public static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    public static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    public static double len(double[] a) {
        return Math.sqrt(dot(a, a));
    }

    public static double[] norm(double[] a) {
        return mul(a, 1.0 / Math.max(len(a), 1e-9));
    }

    public static double[] mix(double[] a, double[] b, double u) {
        return add(a, mul(sub(b, a), u));
    }

    public static double clamp01(double x) {
        return x < 0.0 ? 0.0 : Math.min(1.0, x);
    }

    public static double clamp(double x, double lo, double hi) {
        return x < lo ? lo : Math.min(hi, x);
    }

    /** smoothstep on [0, 1]. */
    public static double ease(double u) {
        double x = clamp01(u);
        return x * x * (3.0 - 2.0 * x);
    }

    /** 0 to 1 with no speed or acceleration at either end. */
    public static double ease5(double u) {
        double x = clamp01(u);
        return x * x * x * (x * (x * 6.0 - 15.0) + 10.0);
    }

    public static double smooth(double a, double b, double x) {
        double u = clamp01((x - a) / (b - a));
        return u * u * (3.0 - 2.0 * u);
    }

    /**
     * A channel through knots (time, value, slope): a quintic between two knots that leaves and arrives without
     * acceleration. Before the first and after the last it holds their values. Knots: {t, value, slope} rows.
     */
    public static double chan(double[][] k, double t) {
        if (t <= k[0][0]) {
            return k[0][1];
        }
        for (int i = 1; i < k.length; i++) {
            if (t < k[i][0]) {
                double t0 = k[i - 1][0];
                double p0 = k[i - 1][1];
                double v0 = k[i - 1][2];
                double t1 = k[i][0];
                double p1 = k[i][1];
                double v1 = k[i][2];
                double d = t1 - t0;
                double u = (t - t0) / d;
                double u3 = u * u * u;
                double u4 = u3 * u;
                double u5 = u4 * u;
                double h01 = 10.0 * u3 - 15.0 * u4 + 6.0 * u5;
                return p0 + (p1 - p0) * h01 + (u - 6.0 * u3 + 8.0 * u4 - 3.0 * u5) * d * v0
                        + (-4.0 * u3 + 7.0 * u4 - 3.0 * u5) * d * v1;
            }
        }
        return k[k.length - 1][1];
    }

    /** chan for vectors: rows {t, x, y, z, vx, vy, vz}. */
    public static double[] chan3(double[][] k, double t) {
        double[] out = new double[3];
        for (int c = 0; c < 3; c++) {
            double[][] one = new double[k.length][3];
            for (int i = 0; i < k.length; i++) {
                one[i][0] = k[i][0];
                one[i][1] = k[i][1 + c];
                one[i][2] = k[i][4 + c];
            }
            out[c] = chan(one, t);
        }
        return out;
    }

    /** A slow wander in -1..1 that never repeats (the original's `drift`). */
    public static double drift(double x) {
        return 0.7 * n1(x) + 0.3 * n1(x * 2.17 + 31.4);
    }

    private static double n1(double x) {
        double i = Math.floor(x);
        return h(i) + (h(i + 1.0) - h(i)) * ease5(x - i);
    }

    private static double h(double i) {
        long n = ((long) i) * 0x9E3779B97F4A7C15L;
        n ^= n >>> 29;
        n *= 0xBF58476D1CE4E5B9L;
        n ^= n >>> 32;
        return (n & 0xFFFF) / 32767.5 - 1.0;
    }

    /** v turned by the least turn that takes unit a to unit b. */
    public static double[] rotTo(double[] a, double[] b, double[] v) {
        double[] k = cross(a, b);
        double c = dot(a, b);
        return add(add(mul(v, c), cross(k, v)), mul(k, dot(k, v) / Math.max(1.0 + c, 1e-6)));
    }

    /** v turned by ang about unit k. */
    public static double[] rotAbout(double[] k, double ang, double[] v) {
        double s = Math.sin(ang);
        double c = Math.cos(ang);
        return add(add(mul(v, c), mul(cross(k, v), s)), mul(k, dot(k, v) * (1.0 - c)));
    }

    /** The part of v square to unit n, made a unit. */
    public static double[] squareTo(double[] v, double[] n) {
        return norm(sub(v, mul(n, dot(v, n))));
    }

    /** x where well over 0, 0 where well under, a smooth bend within r of 0. */
    public static double corner(double x, double r) {
        if (x <= -r) {
            return 0.0;
        }
        if (x >= r) {
            return x;
        }
        double u = (x + r) / (2.0 * r);
        return r * u * u * u * (2.0 - u);
    }
}
