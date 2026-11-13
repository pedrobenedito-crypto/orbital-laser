package com.huuda.orbitallaser.skybreak;

import static com.huuda.orbitallaser.skybreak.SbMath.*;

/**
 * The crater the Sky Breaks' fist leaves: a Java port of `Print::dig` and `floor_block` from Stink-O's Orbital Laser
 * (MIT), mod/src/strike/sb_hand.rs. A bowl falling away in broken steps to the fist's own print at its bottom (the
 * four knuckles' dents, the fingers' grooves behind them), the earth it pushed out heaped round it, and a band past
 * that where everything that stood is swept off. Coordinates: blocks from the target's column.
 */
public final class CraterPrint {
    private static final double SC = SbTime.HD_SC;
    private static final double SB_DEEP = 12.0;
    private static final double[] BOWL = {24.0, 25.5};
    private static final double BOWL_DEEP = 20.0;
    private static final double[][] STEPS = {{0.92, 2.0}, {0.77, 2.6}, {0.6, 2.2}};
    private static final double RIM = 1.4;
    private static final double BAND = 24.0;
    /** The fist's knuckles as they go into the land (u along its back, v across to its thumb; how much less deep; radius). */
    private static final double[][] KNUCKLES = {{6.9, 10.9, 0.5, 4.0}, {7.6, 3.5, 0.0, 4.2}, {6.4, -3.9, 1.4, 3.8}, {4.0, -11.0, 3.0, 3.3}};
    private static final double[] FACE_MID = {0.0, 0.2};
    private static final double[] FACE_H = {11.8, 14.6};
    private static final double FACE_R = 4.0;

    public enum Part { PRINT, BOWL, STEP, RIM, BAND, OUT }

    /** What dig() found at a column. */
    public static final class Cut {
        public double down;
        public double up;
        public Part part = Part.OUT;
        public double fd = 99.0;
        public double r;
    }

    private final double[] dir;
    private final double side;
    private final int seed;

    public CraterPrint(double[] dir, double side, int seed) {
        this.dir = dir;
        this.side = side;
        this.seed = seed;
    }

    /** How far from the target its edits reach (blocks). */
    public static double reach() {
        return 0.5 * (BOWL[0] + BOWL[1]) * SC * RIM + BAND + 2.0;
    }

    public double[] local(double x, double z) {
        return new double[]{x * dir[0] + z * dir[1], side * (x * dir[1] - z * dir[0])};
    }

    private double bowlR(double u, double v) {
        double fu = u / SC;
        double fv = v / SC;
        double a = Math.atan2(fv, fu);
        double rag = 1.0 + 0.18 * (noise(3.0 * Math.cos(a) + 7.0, 3.0 * Math.sin(a), seed ^ 0x51) - 0.5);
        return Math.hypot(fu / BOWL[0], fv / BOWL[1]) / rag;
    }

    private static double[] seg(double[] p, double[] a, double[] b) {
        double ex = b[0] - a[0];
        double ez = b[1] - a[1];
        double h = clamp01(((p[0] - a[0]) * ex + (p[1] - a[1]) * ez) / Math.max(ex * ex + ez * ez, 1e-9));
        double dx = p[0] - a[0] - ex * h;
        double dz = p[1] - a[1] - ez * h;
        return new double[]{Math.sqrt(dx * dx + dz * dz), h};
    }

    private static double rbox(double x, double y, double[] h, double r) {
        double qx = Math.abs(x) - h[0] + r;
        double qy = Math.abs(y) - h[1] + r;
        return Math.hypot(Math.max(qx, 0.0), Math.max(qy, 0.0)) + Math.min(Math.max(qx, qy), 0.0) - r;
    }

    /** The crater at (x, z) from the target's column. n: noise 0..1 there. */
    public Cut dig(double x, double z, double n) {
        double[] uv = local(x, z);
        double u = uv[0];
        double v = uv[1];
        Cut c = new Cut();
        double r = bowlR(u, v);
        c.r = r;
        double out = (r - RIM) * 0.5 * (BOWL[0] + BOWL[1]) * SC;
        if (out > BAND) {
            c.part = Part.OUT;
            return c;
        }
        double down = 0.0;
        Part part = Part.BAND;
        if (r < 1.0) {
            double rj = r + 0.025 * (n - 0.5);
            down = BOWL_DEEP * Math.pow(1.0 - r * r, 1.4);
            part = Part.BOWL;
            for (double[] st : STEPS) {
                if (rj < st[0]) {
                    down += st[1];
                    if (st[0] - rj < 0.022) {
                        part = Part.STEP;
                    }
                }
            }
        }
        double fu = u / SC;
        double fv = v / SC;
        double fd = rbox(fu - FACE_MID[0], fv - FACE_MID[1], new double[]{FACE_H[0] - 2.5, FACE_H[1] - 3.0}, FACE_R);
        if (fd < 8.0) {
            double face = SB_DEEP - 4.4 - 1.2 * smooth(-2.0, -11.0, fu) - 0.13 * Math.max(-fv, 0.0);
            for (double[] k : KNUCKLES) {
                double ku = k[0];
                double kv = k[1];
                double less = k[2];
                double kr = k[3];
                double[] dh = seg(new double[]{fu, fv}, new double[]{ku, kv}, new double[]{-9.5, kv * 0.92});
                double w = kr * 1.05;
                fd = Math.min(fd, dh[0] - w);
                if (dh[0] < w) {
                    face = Math.max(face, SB_DEEP - less - 1.0 - 1.6 * dh[1] - 1.6 * Math.pow(dh[0] / w, 2));
                }
                double dk = Math.hypot(fu - ku, fv - kv);
                if (dk < kr) {
                    face = Math.max(face, SB_DEEP - less - 1.5 * Math.pow(dk / kr, 2));
                }
                double dj = Math.hypot(fu + 9.5, fv - kv * 0.92);
                if (dj < 3.0) {
                    face = Math.max(face, SB_DEEP - less - 2.4 - 1.2 * Math.pow(dj / 3.0, 2));
                }
            }
            double w = smooth(3.5, -1.0, fd);
            double pressed = face * SC * w;
            if (pressed > down) {
                down = pressed;
                if (fd < 0.3) {
                    part = Part.PRINT;
                }
            }
        }
        double up = 0.0;
        if (r > 0.94 && r < RIM) {
            up = (4.5 + 4.5 * n) * Math.exp(-Math.pow((r - 1.07) / 0.11, 2))
                    + 2.2 * Math.exp(-Math.max(r - 1.0, 0.0) / 0.22) * (0.4 + n);
            if (down < 0.5) {
                part = Part.RIM;
            }
        }
        c.down = down;
        c.up = up;
        c.part = part;
        c.fd = fd;
        return c;
    }

    /** The crater's floor block (its top two blocks), as the original's floor_block. */
    public static String floorBlock(Part part, double n, double n2, double fd, double r) {
        switch (part) {
            case PRINT:
                if (n2 > 0.9) {
                    return "minecraft:crying_obsidian";
                }
                return n < 0.42 ? "minecraft:obsidian" : n < 0.85 ? "minecraft:blackstone" : "minecraft:basalt";
            case STEP:
                return n < 0.45 ? "minecraft:stone" : n2 < 0.5 ? "minecraft:tuff" : "minecraft:andesite";
            default:
                break;
        }
        if (fd < 5.0 || r < 0.42) {
            return n2 > 0.7 ? "minecraft:blackstone" : n < 0.5 ? "minecraft:cobbled_deepslate" : "minecraft:tuff";
        }
        if (r > 0.78 && n2 > 0.35) {
            return n > 0.55 ? "minecraft:grass_block" : "minecraft:coarse_dirt";
        }
        if (n2 > 0.72) {
            return "minecraft:gravel";
        }
        return n < 0.35 ? "minecraft:cobblestone" : n < 0.7 ? "minecraft:coarse_dirt" : "minecraft:tuff";
    }
}
