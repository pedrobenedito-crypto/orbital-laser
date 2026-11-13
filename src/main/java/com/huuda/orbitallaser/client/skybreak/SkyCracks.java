package com.huuda.orbitallaser.client.skybreak;

import static com.huuda.orbitallaser.skybreak.SbMath.*;

import java.util.ArrayList;
import java.util.List;

/**
 * The crack pattern across the whole sky, as glass breaks under a point impact (after skybreak.rs's `Mesh`, Stink-O's
 * Orbital Laser, MIT): 15 primary radial cracks straight out from over the target to the horizon, a fork in most
 * sectors, and between them concentric breaks as short slanted chords, dense near the impact and further apart out
 * from it. Also the ragged middle that falls out. Plane coordinates (x, z) from the point over the target.
 */
public final class SkyCracks {
    public static final int K = 15;
    public static final double FAR = 7000.0;

    /** A crack piece; `at` is how far out it is (radials reveal outward), `chord` marks the rings. */
    public record Seg(double x1, double z1, double x2, double z2, double at, boolean chord) {
    }

    public final List<Seg> segs = new ArrayList<>();
    /** The radials' bearings and the middle's radius in each sector. */
    public final double[] bearing = new double[K];
    public final double[] middle = new double[K];

    public SkyCracks(int seed) {
        // ---- the radials, uneven sectors
        double acc = 0.0;
        double[] w = new double[K];
        for (int k = 0; k < K; k++) {
            w[k] = 0.6 + 0.8 * mh(k, seed, 0x51);
            acc += w[k];
        }
        double a0 = TAU * mh(99, seed, 0x52);
        double run = 0.0;
        for (int k = 0; k < K; k++) {
            bearing[k] = a0 + TAU * run / acc;
            run += w[k];
            middle[k] = 105.0 * (0.65 + 0.7 * mh(k, seed, 0x5f));
        }
        for (int k = 0; k < K; k++) {
            radial(seed, k, bearing[k], 4.0, FAR * (0.75 + 0.25 * mh(k, seed, 0x53)), 0x60);
            // (a fork in most sectors: a branch splitting off at a small angle)
            if (mh(k, seed, 0x54) < 0.75) {
                double tf = 60.0 + 900.0 * mh(k, seed, 0x55) * mh(k, seed, 0x56);
                double next = bearing[(k + 1) % K] + (k + 1 == K ? TAU : 0.0);
                double gap = next - bearing[k];
                double b = bearing[k] + gap * (0.25 + 0.3 * mh(k, seed, 0x57));
                radial(seed, k + 100, b, tf, FAR * 0.8, 0x61);
            }
        }
        // ---- the chords between neighbouring radials
        for (int k = 0; k < K; k++) {
            double ba = bearing[k];
            double bb = bearing[(k + 1) % K] + (k + 1 == K ? TAU : 0.0);
            double r = 8.0;
            int n = 0;
            double dens = 0.7 + 0.8 * mh(k, seed, 0x63);
            while (r < FAR && n < 40) {
                double g = 0.2 + 0.6 * smooth01((r - 40.0) / 300.0) + 0.5 * smooth01((r - 400.0) / 1500.0);
                double step = Math.max(r, 6.0) * g * dens * (0.6 + 0.8 * mh(k * 64 + n, seed, 0x64));
                r += Math.max(step, 4.0);
                n++;
                if (r >= FAR) {
                    break;
                }
                if (n > 2 && mh(k * 64 + n, seed, 0x65) < 0.22 + 0.35 * smooth01((r - 150.0) / 1200.0)) {
                    continue;
                }
                double sl = step * 0.5 * (mh(k * 64 + n, seed, 0x66) - 0.5);
                double ra = r + sl;
                double rb = r - sl;
                // (bent a little outward through a middle point)
                double am = (ba + bb) * 0.5;
                double rm = (ra + rb) * 0.5 * (1.0 + 0.04 * (mh(k * 64 + n, seed, 0x67) - 0.3));
                segs.add(new Seg(Math.cos(ba) * ra, Math.sin(ba) * ra, Math.cos(am) * rm, Math.sin(am) * rm, r, true));
                segs.add(new Seg(Math.cos(am) * rm, Math.sin(am) * rm, Math.cos(bb) * rb, Math.sin(bb) * rb, r, true));
            }
        }
    }

    private void radial(int seed, int key, double bearing, double from, double to, int salt) {
        double x = Math.cos(bearing) * from;
        double z = Math.sin(bearing) * from;
        double d = from;
        double a = bearing;
        int i = 0;
        while (d < to && i < 200) {
            double step = Math.max(12.0, d * 0.12) * (0.7 + 0.6 * mh(key * 256 + i, seed, salt));
            a = bearing + 0.03 * (mh(key * 256 + i, seed, salt + 1) - 0.5) * Math.min(1.0, 200.0 / Math.max(d, 1.0));
            double nx = x + Math.cos(a) * step;
            double nz = z + Math.sin(a) * step;
            segs.add(new Seg(x, z, nx, nz, d, false));
            x = nx;
            z = nz;
            d = Math.hypot(x, z);
            i++;
        }
    }

    private static double smooth01(double x) {
        double u = clamp01(x);
        return u * u * (3.0 - 2.0 * u);
    }

    /** The middle's radius at bearing a (between the radials). */
    public double middleAt(double a) {
        for (int k = 0; k < K; k++) {
            double b0 = bearing[k];
            double b1 = bearing[(k + 1) % K] + (k + 1 == K ? TAU : 0.0);
            double aa = a;
            while (aa < b0) {
                aa += TAU;
            }
            while (aa >= b0 + TAU) {
                aa -= TAU;
            }
            if (aa < b1) {
                double u = (aa - b0) / (b1 - b0);
                // (ragged: each sector's own radius, with a notch where the chords meet)
                return middle[k] * (1.0 - 0.18 * Math.sin(u * Math.PI));
            }
        }
        return middle[0];
    }
}
