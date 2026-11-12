package com.huuda.orbitallaser.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Builds a glass-like fracture pattern in a horizontal plane: jagged radial cracks from the center,
 * short branches, and ring cracks connecting neighbouring radials (like a shattered window).
 * Coordinates are relative to the crack center. Deterministic per seed, so every client sees the same sky.
 */
public final class CrackGeometry {

    /** One straight crack piece. {@code reveal} is the distance from the center at which it appears. */
    public record Segment(float x1, float z1, float x2, float z2, float reveal, float width) {
    }

    private CrackGeometry() {
    }

    public static List<Segment> build(long seed, float size) {
        Random rnd = new Random(seed ^ 0x5DEECE66DL);
        List<Segment> out = new ArrayList<>();

        int radials = 11 + rnd.nextInt(6);
        float core = size * 0.035F;
        List<List<float[]>> points = new ArrayList<>(radials);

        for (int i = 0; i < radials; i++) {
            List<float[]> pts = new ArrayList<>();
            double base = i * Math.PI * 2.0 / radials + (rnd.nextDouble() - 0.5) * 0.35;
            double angle = base;
            float maxLen = size * (0.55F + rnd.nextFloat() * 0.45F);
            float x = (float) Math.cos(angle) * core;
            float z = (float) Math.sin(angle) * core;
            float d = core;
            pts.add(new float[]{x, z, d});

            while (d < maxLen) {
                float step = size * (0.045F + rnd.nextFloat() * 0.06F);
                angle += (rnd.nextDouble() - 0.5) * 0.55;
                angle = clamp(angle, base - 0.32, base + 0.32);
                float nx = x + (float) Math.cos(angle) * step;
                float nz = z + (float) Math.sin(angle) * step;
                float nd = (float) Math.sqrt(nx * nx + nz * nz);
                out.add(new Segment(x, z, nx, nz, d, width(d, size)));

                if (rnd.nextFloat() < 0.28F && d > size * 0.1F) {
                    branch(rnd, out, nx, nz, nd, angle, size);
                }
                x = nx;
                z = nz;
                d = nd;
                pts.add(new float[]{x, z, d});
            }
            points.add(pts);
        }

        // Ring cracks between neighbouring radials.
        float[] rings = {0.10F, 0.2F, 0.33F, 0.48F, 0.64F, 0.8F};
        for (float ringFrac : rings) {
            float ringR = size * ringFrac;
            for (int i = 0; i < radials; i++) {
                if (rnd.nextFloat() > 0.72F) {
                    continue;
                }
                float[] a = nearest(points.get(i), ringR, size * 0.07F);
                float[] b = nearest(points.get((i + 1) % radials), ringR, size * 0.07F);
                if (a == null || b == null) {
                    continue;
                }
                // Bend the ring piece outward a little through a jittered midpoint.
                float mx = (a[0] + b[0]) * 0.5F;
                float mz = (a[1] + b[1]) * 0.5F;
                float push = 1.0F + rnd.nextFloat() * 0.12F;
                mx *= push;
                mz *= push;
                float w = width(ringR, size) * 0.8F;
                out.add(new Segment(a[0], a[1], mx, mz, ringR, w));
                out.add(new Segment(mx, mz, b[0], b[1], ringR, w));
            }
        }
        return out;
    }

    private static void branch(Random rnd, List<Segment> out, float x, float z, float d, double angle,
                               float size) {
        double a = angle + (rnd.nextBoolean() ? 1 : -1) * (0.5 + rnd.nextDouble() * 0.5);
        int pieces = 1 + rnd.nextInt(3);
        for (int i = 0; i < pieces; i++) {
            float step = size * (0.03F + rnd.nextFloat() * 0.04F);
            a += (rnd.nextDouble() - 0.5) * 0.6;
            float nx = x + (float) Math.cos(a) * step;
            float nz = z + (float) Math.sin(a) * step;
            out.add(new Segment(x, z, nx, nz, d, width(d, size) * 0.6F));
            x = nx;
            z = nz;
            d = (float) Math.sqrt(nx * nx + nz * nz);
        }
    }

    private static float[] nearest(List<float[]> pts, float r, float tolerance) {
        float[] best = null;
        float bestDiff = tolerance;
        for (float[] p : pts) {
            float diff = Math.abs(p[2] - r);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = p;
            }
        }
        return best;
    }

    /** Thicker near the center, thinner at the tips. In blocks. */
    private static float width(float d, float size) {
        float t = Math.min(1.0F, d / size);
        return (0.35F + 1.6F * (1.0F - t)) * Math.max(1.0F, size / 144.0F);
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : Math.min(v, max);
    }
}
