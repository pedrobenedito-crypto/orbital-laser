package com.huuda.orbitallaser.skybreak;

/**
 * The Sky Breaks' timeline, in seconds after the strike starts. Values from Stink-O's Orbital Laser (MIT):
 * mod/src/strike/skybreak.rs, sb_hand.rs, sb_lift.rs and shaders/skybreak.wgsl.
 */
public final class SbTime {
    // ---- the lift: a disc of land tears free and rises into the sky (sb_lift.rs)
    public static final double LF_RING = 0.08;
    public static final double LF_CUT = 0.55;
    public static final double LF_TEAR = 0.6;
    public static final double LF_CLIMB = 2.0;
    public static final double LF_TOUCH = 4.2;
    public static final double LF_IN = 5.15;
    public static final double LF_STAR = 5.3;
    public static final double LF_RIPPLE = 5.4;
    public static final double LF_PRESS = 7.0;
    public static final double LF_CURL = 8.35;
    public static final double LF_PUNCH = 8.75;
    /** Disc radius (its edge wanders 0.85..1.15 of it), depth at the middle and at the edge. */
    public static final double LF_R = 7.0;
    public static final double LF_DEEP = 14.0;
    public static final double LF_SHALLOW = 3.0;

    // ---- the sky (skybreak.rs)
    /** The sky's pane, this far over the target's ground. */
    public static final double SB_H = 560.0;
    public static final double SB_LEAD = 8.75;
    /** The hand punches through: the first crack. */
    public static final double SB_CRACK = SB_LEAD + 0.25;
    public static final double SB_SPREAD = SB_CRACK + 0.25;
    public static final double SB_COLLAPSE = SB_CRACK + 0.03;
    /** The first pane breaks free, and the spacing of the rest; how long one falls; how long it sags first. */
    public static final double SB_FIRST = SB_LEAD + 8.0;
    public static final double SB_EVERY = 1.45;
    public static final double SB_FALL = 5.3;
    public static final double SB_SAG = 1.3;
    public static final double SB_SETTLE = 0.85;
    public static final int SB_NS = 7;
    /** The sky heals over from SB_HEAL to SB_HEALED; the strike ends at SB_LEN. */
    public static final double SB_HEAL = 40.0;
    public static final double SB_HEALED = 46.5;
    public static final double SB_LEN = 49.5;
    /** The ragged middle that falls out first, and the opening always left for the arm. */
    public static final double SB_MIDDLE = 105.0;
    public static final double SB_OPEN = 75.0;

    // ---- the eye (skybreak.wgsl)
    public static final double EYE_R = 78.0;
    public static final double EYE_ARRIVE = 12.0;
    public static final double EYE_THERE = 14.0;
    public static final double EYE_OPEN = EYE_THERE + 1.2;
    public static final double EYE_LOCK = SB_LEAD + 10.4;
    public static final double EYE_BLINK = SB_LEAD + 21.4;
    public static final double EYE_AWAY = 35.55;
    public static final double EYE_SHUT = 35.75;

    // ---- the hand (sb_hand.rs)
    public static final double HD_LET_GO = 10.0;
    public static final double HD_FLARE = 35.1;
    public static final double HD_DOWN = 36.0;
    public static final double HD_PRESS = 37.5;
    public static final double HD_KNOCK = HD_PRESS + 0.22;
    public static final double HD_LIFT = 38.9;
    public static final double HD_GONE = 42.6;
    /** Blocks to the hand's bones' unit. */
    public static final double HD_SC = 2.8;
    /** The arm's windows: the punch and the slam (when the hand exists at all). */
    public static final double PUNCH_FROM = 6.3;
    public static final double PUNCH_TO = 12.9;
    public static final double SLAM_FROM = 33.8;
    public static final double SLAM_TO = 43.0;

    // ---- the slam's crater (sb_hand.rs)
    public static final double KILL_SLAM = 125.0;
    public static final double KILL_PANE = 70.0;

    private SbTime() {
    }
}
