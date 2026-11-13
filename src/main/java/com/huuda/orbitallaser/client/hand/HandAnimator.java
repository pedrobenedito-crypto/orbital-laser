package com.huuda.orbitallaser.client.hand;

import com.huuda.orbitallaser.client.ClientStrike;
import com.huuda.orbitallaser.network.StrikeSyncPacket;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Keyframed animation of the Sky Hand, driven by the strike's phase and the level's game time.
 * <p>
 * Timeline (fractions of the charge time, default 5 s):
 * <pre>
 * 0.00-0.06  the fist comes down behind the sky (seen dimly through the glass)
 * 0.06-0.16  anticipation, then PUNCH 1 at 0.16: first cracks
 * 0.16-0.25  rebound
 * 0.26-0.36  anticipation, PUNCH 2 at 0.36: cracks spread
 * 0.46-0.56  anticipation, PUNCH 3 at 0.56: the glass bursts, shards fall, the fist goes through
 * 0.56-0.82  the arm pushes down through the hole; fist -> claw (0.60-0.72) -> open palm (0.72-0.86),
 *            fingers opening one after the other (overlapping action)
 * 0.80-1.00  the dark core gathers in the palm, fingers tremble
 * FIRE       recoil of the arm, fingers strain while the beam runs
 * END        the hand closes and pulls back up through the hole
 * </pre>
 * {@code ext} is the wrist position along the arm direction, in model units: negative = behind the sky.
 */
public final class HandAnimator {
    public static final float P1 = 0.16F;
    public static final float P2 = 0.36F;
    public static final float P3 = 0.56F;
    public static final float[] PUNCHES = {P1, P2, P3};

    /** Wrist to knuckles of the closed fist, model units. */
    private static final float FIST_REACH = 1.19F;
    private static final float CONTACT = -FIST_REACH;
    private static final float WINDUP = -(FIST_REACH + 0.9F);
    private static final float ANTICIPATION = WINDUP - 0.35F;
    private static final float HIDDEN = -(FIST_REACH + 3.6F);
    private static final float OPEN_EXT = 1.35F;

    private static final Set<String> FINGER_BONES = new HashSet<>();

    static {
        for (String f : new String[]{"index", "middle", "ring", "pinky", "thumb"}) {
            for (int i = 1; i <= 3; i++) {
                FINGER_BONES.add(f + "_" + i);
            }
        }
    }

    private HandAnimator() {
    }

    public static final class Frame {
        public boolean visible = true;
        /** Wrist position along the arm, model units. Negative = behind the sky. */
        public float ext;
        public Map<String, float[]> pose = Map.of();
        /** 0..1 share of the crack pattern revealed. */
        public float crackReveal;
        /** 0..1 opening of the hole in the sky. */
        public float hole;
        /** Game time when the glass burst, or NaN. */
        public double breakTime = Double.NaN;
        /** Opacity of the hand seen through the glass. */
        public float ghostAlpha;
        /** 0..1 size of the dark core in the palm. */
        public float coreGrow;
    }

    public static Frame compute(ClientStrike s, HandModel model, double now) {
        if (!s.ended()) {
            return active(s, model, now);
        }
        // Ended or cancelled: freeze the last active frame, then close the hand and pull it back.
        Frame base = active(s, model, s.endTime);
        double age = now - s.endTime;
        Frame f = new Frame();
        f.crackReveal = base.crackReveal;
        f.hole = base.hole;
        f.breakTime = base.breakTime;
        f.ghostAlpha = base.ghostAlpha;
        f.coreGrow = 0.0F;
        if (s.cancelled) {
            float t = (float) clamp(age / 20.0);
            f.pose = blend(base.pose, model.pose("fist"), easeOut(clamp(age / 8.0)), false);
            f.ext = lerp(base.ext, HIDDEN, easeIn(t));
            f.visible = age < 22.0;
        } else {
            f.pose = blend(base.pose, model.pose("fist"), easeInOut(clamp(age / 15.0)), true);
            f.ext = lerp(base.ext, HIDDEN, easeIn(clamp((age - 8.0) / 47.0)));
            f.visible = age < 60.0;
        }
        return f;
    }

    private static Frame active(ClientStrike s, HandModel model, double now) {
        Frame f = new Frame();
        Map<String, float[]> fist = model.pose("fist");
        Map<String, float[]> claw = model.pose("claw");
        Map<String, float[]> open = model.pose("open");
        Map<String, float[]> strain = model.pose("strain");

        if (s.phase == StrikeSyncPacket.CHARGING) {
            double p = clamp((now - s.phaseStart) / s.chargeTicks);
            f.ext = chargeExt(p);
            f.breakTime = p >= P3 ? s.phaseStart + P3 * s.chargeTicks : Double.NaN;

            if (p < 0.60) {
                f.pose = fist;
            } else if (p < 0.72) {
                f.pose = blend(fist, claw, easeInOut((p - 0.60) / 0.12), true);
            } else if (p < 0.86) {
                f.pose = blend(claw, open, easeInOut((p - 0.72) / 0.14), true);
            } else {
                f.pose = open;
            }

            if (p < P1) {
                f.crackReveal = 0.0F;
            } else if (p < P2) {
                f.crackReveal = 0.22F * easeOut(clamp((p - P1) / 0.03));
            } else if (p < P3) {
                f.crackReveal = 0.22F + 0.28F * easeOut(clamp((p - P2) / 0.03));
            } else {
                f.crackReveal = 0.5F + 0.5F * easeOut(clamp((p - P3) / 0.12));
            }
            f.hole = p < P3 ? 0.0F : easeOut(clamp((p - P3) / 0.04));
            f.ghostAlpha = 0.16F + (p >= P1 ? 0.1F : 0.0F) + (p >= P2 ? 0.1F : 0.0F) + (p >= P3 ? 0.12F : 0.0F);
            f.coreGrow = p < 0.80 ? 0.0F : easeOut((p - 0.80) / 0.20);

            float tremble = p > 0.80 ? (float) ((p - 0.80) / 0.20) * 2.5F : 0.0F;
            f.pose = tremble(f.pose, now, tremble);
            if (p > 0.82) {
                f.ext += (float) Math.sin(now * 0.15) * 0.04F;
            }
        } else {
            // STRIKING: phaseStart = moment of fire.
            double t = now - s.phaseStart;
            f.breakTime = s.phaseStart - s.chargeTicks * (1.0 - P3);
            float recoil;
            if (t < 5.0) {
                recoil = easeOut(t / 5.0);
            } else if (t < 25.0) {
                recoil = 1.0F - easeInOut((t - 5.0) / 20.0);
            } else {
                recoil = 0.0F;
            }
            f.ext = OPEN_EXT - 0.35F * recoil + (float) Math.sin(now * 2.1) * 0.015F;
            f.pose = tremble(blend(open, strain, easeOut(clamp(t / 10.0)), true), now, 3.5F);
            f.crackReveal = 1.0F;
            f.hole = 1.0F;
            f.ghostAlpha = 0.48F;
            f.coreGrow = 1.0F;
        }
        return f;
    }

    /** Wrist position during the charge. Fractions match the class comment. */
    private static float chargeExt(double p) {
        if (p < 0.06) {
            return lerp(HIDDEN, WINDUP, easeOut(p / 0.06));
        }
        float ext = WINDUP;
        for (int i = 0; i < 3; i++) {
            double impact = PUNCHES[i];
            if (p < impact - 0.10) {
                return ext;
            }
            if (p < impact - 0.05) {
                return lerp(WINDUP, ANTICIPATION, easeInOut((p - (impact - 0.10)) / 0.05));
            }
            if (p < impact) {
                // Accelerating strike.
                float x = (float) ((p - (impact - 0.05)) / 0.05);
                return lerp(ANTICIPATION, CONTACT, x * x * x);
            }
            if (i < 2) {
                if (p < impact + 0.09) {
                    // Rebound off the glass, with a short stop on contact.
                    double x = clamp((p - impact - 0.01) / 0.08);
                    return lerp(CONTACT, WINDUP, easeOut(x));
                }
                ext = WINDUP;
            }
        }
        // After the third punch: through the glass.
        if (p < P3 + 0.06) {
            return lerp(CONTACT, 0.25F, easeOut((p - P3) / 0.06));
        }
        if (p < 0.82) {
            return lerp(0.25F, OPEN_EXT, easeInOut((p - (P3 + 0.06)) / (0.82 - P3 - 0.06)));
        }
        return OPEN_EXT;
    }

    // ================================================================= pose helpers

    /** Fingers start later from index to pinky (overlapping action), so the hand does not move as one block. */
    private static float delayFor(String bone) {
        if (bone.startsWith("thumb")) {
            return 0.0F;
        }
        if (bone.startsWith("index")) {
            return 0.06F;
        }
        if (bone.startsWith("middle")) {
            return 0.12F;
        }
        if (bone.startsWith("ring")) {
            return 0.18F;
        }
        if (bone.startsWith("pinky")) {
            return 0.24F;
        }
        return 0.0F;
    }

    public static Map<String, float[]> blend(Map<String, float[]> a, Map<String, float[]> b, float t, boolean stagger) {
        Map<String, float[]> out = new HashMap<>();
        Set<String> keys = new HashSet<>(a.keySet());
        keys.addAll(b.keySet());
        for (String k : keys) {
            float[] va = a.getOrDefault(k, ZERO);
            float[] vb = b.getOrDefault(k, ZERO);
            float tt = t;
            if (stagger) {
                float d = delayFor(k);
                // Distal joints follow a little after the proximal ones.
                if (k.endsWith("_2")) {
                    d += 0.04F;
                } else if (k.endsWith("_3")) {
                    d += 0.08F;
                }
                tt = (float) clamp((t - d) / (1.0F - 0.32F));
                tt = easeInOut(tt);
            }
            out.put(k, new float[]{lerp(va[0], vb[0], tt), lerp(va[1], vb[1], tt), lerp(va[2], vb[2], tt)});
        }
        return out;
    }

    private static Map<String, float[]> tremble(Map<String, float[]> pose, double now, float amplitude) {
        if (amplitude <= 0.0F) {
            return pose;
        }
        Map<String, float[]> out = new HashMap<>(pose);
        int i = 0;
        for (String k : FINGER_BONES) {
            float[] v = pose.getOrDefault(k, ZERO);
            float n = (float) (Math.sin(now * 1.9 + i * 1.7) * 0.6 + Math.sin(now * 3.3 + i * 0.9) * 0.4);
            out.put(k, new float[]{v[0] + n * amplitude, v[1], v[2] + n * amplitude * 0.3F});
            i++;
        }
        return out;
    }

    private static final float[] ZERO = {0.0F, 0.0F, 0.0F};

    static double clamp(double v) {
        return v < 0.0 ? 0.0 : Math.min(1.0, v);
    }

    static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    static float easeOut(double x) {
        double i = 1.0 - clamp(x);
        return (float) (1.0 - i * i * i);
    }

    static float easeIn(double x) {
        double c = clamp(x);
        return (float) (c * c * c);
    }

    static float easeInOut(double x) {
        double c = clamp(x);
        return (float) (c < 0.5 ? 4.0 * c * c * c : 1.0 - Math.pow(-2.0 * c + 2.0, 3.0) / 2.0);
    }
}
