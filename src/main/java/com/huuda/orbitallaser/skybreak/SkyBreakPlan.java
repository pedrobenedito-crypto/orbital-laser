package com.huuda.orbitallaser.skybreak;

import static com.huuda.orbitallaser.skybreak.SbMath.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything about one Sky Breaks strike that is decided once, as it fires, from its seed and where the player was:
 * the same on the server (which edits the world) and on every client (which draws it).
 * Ported in spirit from Stink-O's Orbital Laser (MIT): skybreak.rs (panes), sb_hand.rs (Print::new).
 * All positions are blocks from the target's ground point.
 */
public final class SkyBreakPlan {
    /** The most a pane reaches from its middle to a corner (scaled down from the original's 92 to keep edits light). */
    public static final double PANE_BIG = 46.0;
    private static final double PANE_APART = 80.0;
    private static final double PANE_ME = 60.0;

    public final int seed;
    /** The player's place at firing. */
    public final double[] me;
    /** The way the back of the slamming fist faces along the land, and which hand (1 or -1). */
    public final double[] dir;
    public final double side;
    public final List<Pane> panes = new ArrayList<>();

    /** A pane of sky that breaks free and falls. */
    public static final class Pane {
        public final int index;
        /** Where it was in the sky (x, z) and where it lands (x, z). */
        public final double[] from;
        public final double[] to;
        /** Its outline, relative to its middle: (x, z) pairs. */
        public final double[][] outline;
        /** When it breaks free (seconds), and its spin as it falls. */
        public final double free;
        public final double spinAxis;
        public final double spin;
        /** The tilt it lies at, and which way it leans. */
        public final double tilt;
        public final double tiltDir;

        Pane(int index, double[] from, double[] to, double[][] outline, double free, double spinAxis, double spin,
             double tilt, double tiltDir) {
            this.index = index;
            this.from = from;
            this.to = to;
            this.outline = outline;
            this.free = free;
            this.spinAxis = spinAxis;
            this.spin = spin;
            this.tilt = tilt;
            this.tiltDir = tiltDir;
        }

        public double lands() {
            return free + SbTime.SB_FALL;
        }

        public double size() {
            double r = 0.0;
            for (double[] p : outline) {
                r = Math.max(r, Math.hypot(p[0], p[1]));
            }
            return r;
        }

        /** Whether (x, z) relative to where it lands is inside its outline. */
        public boolean covers(double x, double z) {
            int n = outline.length;
            boolean in = false;
            for (int i = 0, j = n - 1; i < n; j = i++) {
                double xi = outline[i][0];
                double zi = outline[i][1];
                double xj = outline[j][0];
                double zj = outline[j][1];
                if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) {
                    in = !in;
                }
            }
            return in;
        }
    }

    public SkyBreakPlan(int seed, double[] me) {
        this.seed = seed;
        this.me = me;
        // ---- the fist's line (sb_hand.rs Print::new): side on to the player
        double meL = Math.hypot(me[0], me[2]);
        double bme = meL > 1.0 ? Math.atan2(me[2], me[0]) : TAU * mh(3, seed, 0x1a1);
        double first = mh(1, seed, 0x1a2) < 0.5 ? 1.0 : -1.0;
        double a = bme + first * 0.5 * Math.PI;
        this.dir = new double[]{Math.cos(a), Math.sin(a)};
        double cross = me[0] * dir[1] - me[2] * dir[0];
        this.side = meL > 1.0 ? (cross >= 0.0 ? 1.0 : -1.0) : first;

        // ---- the panes: up to SB_NS, spaced out round the target, never on the player
        int made = 0;
        for (int tries = 0; tries < 60 && made < SbTime.SB_NS; tries++) {
            double ang = TAU * mh(tries, seed, 0x301);
            double dist = 70.0 + 150.0 * Math.sqrt(mh(tries, seed, 0x302));
            double[] to = {Math.cos(ang) * dist, Math.sin(ang) * dist};
            if (Math.hypot(to[0] - me[0], to[1] - me[2]) < PANE_ME) {
                continue;
            }
            boolean clash = false;
            for (Pane p : panes) {
                if (Math.hypot(to[0] - p.to[0], to[1] - p.to[1]) < PANE_APART) {
                    clash = true;
                    break;
                }
            }
            if (clash) {
                continue;
            }
            // (where it hung in the sky: further out from the middle, drifting in a little as it falls)
            double[] from = {to[0] * 1.12, to[1] * 1.12};
            int corners = 4 + (int) (mh(tries, seed, 0x303) * 3.0);
            double size = PANE_BIG * (0.55 + 0.45 * mh(tries, seed, 0x304));
            double[][] outline = new double[corners][2];
            double rot = TAU * mh(tries, seed, 0x305);
            for (int k = 0; k < corners; k++) {
                double ca = rot + TAU * (k + 0.35 * (mh(tries * 16 + k, seed, 0x306) - 0.5)) / corners;
                double cr = size * (0.6 + 0.4 * mh(tries * 16 + k, seed, 0x307));
                outline[k][0] = Math.cos(ca) * cr;
                outline[k][1] = Math.sin(ca) * cr;
            }
            double free = SbTime.SB_FIRST + SbTime.SB_EVERY * made;
            panes.add(new Pane(made, from, to, outline, free,
                    TAU * mh(tries, seed, 0x308), (mh(tries, seed, 0x309) - 0.5) * 1.6,
                    0.12 + 0.18 * mh(tries, seed, 0x30a), TAU * mh(tries, seed, 0x30b)));
            made++;
        }
    }

    /** The planner for this strike's arm (g0: the land's height at the fist, from the target's ground). */
    public ArmPlanner arm(double g0) {
        return new ArmPlanner(me, dir, side, g0, seed);
    }
}
