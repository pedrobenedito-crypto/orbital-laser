package com.huuda.orbitallaser.client.skybreak;

import com.huuda.orbitallaser.OrbitalLaserMod;
import com.huuda.orbitallaser.config.ClientConfig;
import com.huuda.orbitallaser.skybreak.ArmPlanner;
import com.huuda.orbitallaser.skybreak.ArmRig;
import com.huuda.orbitallaser.skybreak.SbMath;
import com.huuda.orbitallaser.skybreak.SbTime;
import com.huuda.orbitallaser.skybreak.SkyBreakPlan;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Draws the Sky Breaks (the sequence of Stink-O's Orbital Laser, MIT, redrawn with geometry instead of its WGSL
 * ray-marching pass): the land rising into the sky, the sky bowing under the hand's pressure, the crack, the hole and
 * the dark behind it, the eye looking through, the panes falling, the sculpted arm punching and slamming, the glitter,
 * the shockwave and the sky healing.
 * <p>
 * The sky is 560 blocks up and its cracks run to the horizon, past the game's far plane. Every vertex drawn here is
 * pulled in along its own line of sight (warp), so it stays where it is on screen and keeps its depth order, but never
 * leaves the far plane.
 */
@Mod.EventBusSubscriber(modid = OrbitalLaserMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SkyBreakRenderer {
    private static final double H = SbTime.SB_H;
    private static final float[] VOID = {0.02F, 0.0F, 0.05F};

    private static Matrix4f view;
    private static Matrix3f viewN;
    private static double camX;
    private static double camY;
    private static double camZ;
    private static double warp0;
    private static double warp1;
    private static final Vector3f TMP = new Vector3f();
    private static float[][] sphere;

    private SkyBreakRenderer() {
    }

    // ================================================================= frame

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER || SkyBreakClient.isEmpty()) {
            return;
        }
        if (!ClientConfig.RENDER_EFFECTS.get()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        float partial = event.getPartialTick();
        double now = level.getGameTime() + partial;
        Vec3 cam = event.getCamera().getPosition();
        camX = cam.x;
        camY = cam.y;
        camZ = cam.z;
        view = new Matrix4f(event.getPoseStack().last().pose());
        viewN = new Matrix3f(event.getPoseStack().last().normal());
        double far = mc.gameRenderer.getDepthFar();
        warp1 = far * 0.96;
        warp0 = far * 0.55;

        float oldFog = RenderSystem.getShaderFogStart();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE);
        for (SkyBreakClient.Sky s : SkyBreakClient.all()) {
            if (s.level != level) {
                continue;
            }
            double t = s.t(now);
            if (t < 0.0 || t > SbTime.SB_LEN + 2.0) {
                continue;
            }
            try {
                renderSky(mc, s, t, now, partial);
            } catch (RuntimeException e) {
                OrbitalLaserMod.LOGGER.error("[orbital_laser] sky breaks render failed", e);
            }
        }
        RenderSystem.setShaderFogStart(oldFog);
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
    }

    private static void renderSky(Minecraft mc, SkyBreakClient.Sky s, double t, double now, float partial) {
        double heal = 1.0 - SbMath.ease((t - SbTime.SB_HEAL) / (SbTime.SB_HEALED - SbTime.SB_HEAL));
        double crackA = t < SbTime.SB_CRACK ? 0.0 : heal;
        double holeS = holeScale(t) * heal;
        float flicker = 0.88F + 0.12F * (float) Math.sin(now * 0.9);
        Quads q = new Quads();

        // ---- 1. the sky under strain, the ripple, the star crack (before the punch)
        prepareFx();
        additive();
        if (t > SbTime.LF_RIPPLE && t < SbTime.LF_PRESS) {
            double a = t - SbTime.LF_RIPPLE;
            for (int i = 0; i < 3; i++) {
                double r = 30.0 + (a - i * 0.35) * 260.0;
                if (r > 20.0) {
                    ringSky(q.get(), s, r - 6.0, r + 6.0, 0.0, 0.8F, 0.92F, 1.0F, (float) (0.25 * Math.exp(-a * 1.2)));
                }
            }
        }
        if (t > SbTime.LF_PRESS - 0.5 && t < SbTime.SB_CRACK + 0.4) {
            double press = SbMath.ease((t - SbTime.LF_PRESS + 0.5) / 1.4) * (1.0 - SbMath.ease((t - SbTime.SB_CRACK) / 0.4));
            discSky(q.get(), s, 220.0, -2.0, 0.95F, 0.97F, 1.0F, (float) (0.32 * press), 0.0F);
        }
        if (t > SbTime.LF_STAR && t < SbTime.SB_CRACK + 0.3) {
            for (int i = 0; i < 7; i++) {
                double a = SbMath.TAU * (i + 0.3 * SbMath.mh(i, s.seed, 0x91)) / 7.0;
                double l = 14.0 + 18.0 * SbMath.mh(i, s.seed, 0x92);
                segSky(q.get(), s, 0, 0, Math.cos(a) * l, Math.sin(a) * l, 0.0, 1.6, 0.95F, 0.98F, 1.0F, 0.8F);
            }
        }
        q.draw();

        // ---- 2. the dark behind the sky, through the hole and where panes broke free
        RenderSystem.defaultBlendFunc();
        if (holeS > 0.01) {
            holeSky(q.get(), s, holeS, 1.0, VOID[0], VOID[1], VOID[2], 0.97F);
        }
        for (SkyBreakPlan.Pane p : s.plan.panes) {
            if (t >= p.free && heal > 0.01) {
                paneHoleSky(q.get(), s, p, heal, VOID[0], VOID[1], VOID[2], 0.95F);
            }
        }
        q.draw();

        // ---- 3. the cracks
        if (crackA > 0.0) {
            additive();
            double rad = (t - SbTime.SB_CRACK) / 0.1 * SkyCracks.FAR;
            for (SkyCracks.Seg g : s.cracks.segs) {
                if (!g.chord() && g.at() > rad) {
                    continue;
                }
                if (g.chord() && t < SbTime.SB_CRACK + 0.25 * Math.sqrt(g.at() / SkyCracks.FAR)) {
                    continue;
                }
                double mx = (g.x1() + g.x2()) * 0.5;
                double mz = (g.z1() + g.z2()) * 0.5;
                if (holeS > 0.01 && Math.hypot(mx, mz) < holeR(s, Math.atan2(mz, mx), holeS)) {
                    continue;
                }
                double w = 0.9 + g.at() * 0.0035;
                segSky(q.get(), s, g.x1(), g.z1(), g.x2(), g.z2(), -0.3, w * 3.0, 0.5F, 0.82F, 1.0F, (float) (0.18 * crackA) * flicker);
                segSky(q.get(), s, g.x1(), g.z1(), g.x2(), g.z2(), -0.4, w, 0.93F, 0.98F, 1.0F, (float) (0.9 * crackA));
            }
            // (the panes' outlines, still in the sky)
            for (SkyBreakPlan.Pane p : s.plan.panes) {
                if (t < p.free - SbTime.SB_SAG && t > SbTime.SB_SPREAD) {
                    paneOutlineSky(q.get(), s, p, (float) (0.9 * crackA));
                }
            }
            if (holeS > 0.01) {
                holeSky(q.get(), s, holeS, 1.06, 0.55F, 0.88F, 1.0F, (float) (0.35 * crackA) * flicker);
            }
            q.draw();
        }

        // ---- 4. the hand behind the glass: its dark through the sky, before the punch
        ArmPlanner.Pose pose = s.arm.pose(t);
        if (pose != null && t < SbTime.SB_CRACK + 0.25 && t >= SbTime.PUNCH_FROM) {
            double presence = SbMath.ease((t - SbTime.PUNCH_FROM) / 0.5) * (1.0 - SbMath.ease((t - SbTime.SB_CRACK) / 0.25));
            renderArm(mc, s, pose, RenderType.entityTranslucent(ArmMesh.TEXTURE), 0.55F, 0.62F, 0.78F, (float) (0.35 * presence));
            prepareFx();
        }

        // ---- 5. the sky itself as a depth-only shell, open where it is broken
        RenderSystem.colorMask(false, false, false, false);
        RenderSystem.depthMask(true);
        occluder(q.get(), s, holeS, t, heal);
        q.draw();
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(false);

        // ---- 6. what is behind the sky and what comes through it
        if (t >= SbTime.EYE_ARRIVE && t < SbTime.EYE_AWAY + 1.0) {
            renderEye(q, s, t, now);
        }
        if (s.lift.length > 0 && t >= SbTime.LF_CUT && t < SbTime.LF_IN + 0.3) {
            renderIsland(mc, s, t);
            prepareFx();
        }
        if (pose != null && pose.k > 0.01) {
            renderArm(mc, s, pose, RenderType.entityCutoutNoCull(ArmMesh.TEXTURE), 1.0F, 1.0F, 1.0F, 1.0F);
            prepareFx();
        }

        // ---- 7. the panes falling, the glitter, the shockwave
        float[] sky = skyColor(mc, partial);
        for (SkyBreakPlan.Pane p : s.plan.panes) {
            if (t >= p.free - SbTime.SB_SAG && t < p.lands() + 1.3) {
                renderPane(q, s, p, t, sky);
            }
        }
        if (t >= SbTime.SB_COLLAPSE && t < SbTime.SB_COLLAPSE + 6.0) {
            glitter(q, s, t - SbTime.SB_COLLAPSE, 0.0, 0.8, 150, 0x41, sky);
        }
        if (t >= SbTime.HD_KNOCK && t < SbTime.HD_KNOCK + 6.0) {
            glitter(q, s, t - SbTime.HD_KNOCK, 0.8, 1.0, 70, 0x42, sky);
        }
        if (t >= SbTime.HD_PRESS && t < SbTime.HD_PRESS + 3.0) {
            double a = t - SbTime.HD_PRESS;
            additive();
            double r = 30.0 + 250.0 * Math.pow(a / 3.0, 0.7);
            ringWorld(q.get(), s.cx(), s.groundY + 1.5, s.cz(), r - 5.0, r + 5.0, 0.95F, 0.9F, 0.8F, (float) (0.6 * (1.0 - a / 3.0)));
            q.draw();
        }
    }

    /** The hole's radius at bearing a: the middle's ragged edge, never less than the arm needs (skybreak.rs's SB_OPEN). */
    private static double holeR(SkyBreakClient.Sky s, double a, double holeS) {
        if (holeS <= 0.0) {
            return 0.0;
        }
        return Math.max(s.cracks.middleAt(a) * holeS, SbTime.SB_OPEN * Math.min(1.0, holeS / 0.8));
    }

    /** How open the middle is: blown out by the fist, its outer ring knocked loose by the slam. */
    private static double holeScale(double t) {
        if (t < SbTime.SB_CRACK) {
            return 0.0;
        }
        double s = 0.8 * SbMath.ease((t - SbTime.SB_CRACK) / 0.15);
        if (t > SbTime.HD_KNOCK) {
            s += 0.2 * SbMath.ease((t - SbTime.HD_KNOCK) / 0.85);
        }
        return s;
    }

    // ================================================================= the arm

    private static void renderArm(Minecraft mc, SkyBreakClient.Sky s, ArmPlanner.Pose pose, RenderType rt,
                                  float r, float g, float b, float a) {
        ArmMesh mesh = ArmMesh.get();
        if (mesh == null || a <= 0.005F) {
            return;
        }
        ArmRig rig = new ArmRig();
        rig.set(pose, s.arm.side(), ArmPlanner.SC);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffers.getBuffer(rt);
        double bx = s.cx() - camX;
        double by = s.groundY - camY;
        double bz = s.cz() - camZ;
        Vector3f p = new Vector3f();
        Vector3f n = new Vector3f();
        int light = LightTexture.FULL_BRIGHT;
        int overlay = OverlayTexture.NO_OVERLAY;
        for (int i = 0; i < ArmRig.PIECES; i++) {
            float[] d = mesh.pieces[i];
            if (d == null) {
                continue;
            }
            double[] m = rig.mats[i];
            double sx = 1.0 / Math.max(Math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]), 1e-6);
            double sy = 1.0 / Math.max(Math.sqrt(m[3] * m[3] + m[4] * m[4] + m[5] * m[5]), 1e-6);
            double sz = 1.0 / Math.max(Math.sqrt(m[6] * m[6] + m[7] * m[7] + m[8] * m[8]), 1e-6);
            for (int t = 0; t + 24 <= d.length; t += 24) {
                for (int k = 0; k < 4; k++) {
                    int o = t + Math.min(k, 2) * 8;
                    double qx = d[o];
                    double qy = d[o + 1];
                    double qz = d[o + 2];
                    double x = m[0] * qx + m[3] * qy + m[6] * qz + m[9] + bx;
                    double y = m[1] * qx + m[4] * qy + m[7] * qz + m[10] + by;
                    double z = m[2] * qx + m[5] * qy + m[8] * qz + m[11] + bz;
                    warpTo(x, y, z, p);
                    view.transformPosition(p);
                    double nx = d[o + 5] * sx;
                    double ny = d[o + 6] * sy;
                    double nz = d[o + 7] * sz;
                    n.set((float) (m[0] * nx + m[3] * ny + m[6] * nz), (float) (m[1] * nx + m[4] * ny + m[7] * nz),
                            (float) (m[2] * nx + m[5] * ny + m[8] * nz));
                    viewN.transform(n);
                    float len = n.length();
                    if (len > 1e-6F) {
                        n.div(len);
                    }
                    vc.vertex(p.x(), p.y(), p.z(), r, g, b, a, d[o + 3], d[o + 4], overlay, light, n.x(), n.y(), n.z());
                }
            }
        }
        buffers.endBatch(rt);
    }

    // ================================================================= the eye

    private static float[][] sphere() {
        if (sphere == null) {
            int lat = 28;
            int lon = 44;
            float[][] out = new float[lat * lon * 4][];
            int i = 0;
            for (int a = 0; a < lat; a++) {
                for (int b = 0; b < lon; b++) {
                    out[i++] = sp(a, b, lat, lon);
                    out[i++] = sp(a, b + 1, lat, lon);
                    out[i++] = sp(a + 1, b + 1, lat, lon);
                    out[i++] = sp(a + 1, b, lat, lon);
                }
            }
            sphere = out;
        }
        return sphere;
    }

    private static float[] sp(int a, int b, int lat, int lon) {
        double th = Math.PI * a / lat;
        double ph = SbMath.TAU * b / lon;
        return new float[]{(float) (Math.sin(th) * Math.cos(ph)), (float) Math.cos(th), (float) (Math.sin(th) * Math.sin(ph))};
    }

    private static void renderEye(Quads q, SkyBreakClient.Sky s, double t, double now) {
        double rr = SbTime.EYE_R;
        // (it slides in from the side of the hole, and away again before the fist is thrown)
        double slide = 2.6 * rr * (1.0 - SbMath.ease((t - SbTime.EYE_ARRIVE) / (SbTime.EYE_THERE - SbTime.EYE_ARRIVE)))
                + 2.6 * rr * SbMath.ease((t - SbTime.EYE_AWAY) / 0.9);
        double[] side = {Math.cos(s.seed * 0.001), 0.0, Math.sin(s.seed * 0.001)};
        double[] c = {s.cx() + side[0] * slide - camX, s.groundY + H + rr * 0.9 - camY, s.cz() + side[2] * slide - camZ};
        // (its gaze: wandering, then on the player, then turned away)
        double[] wander = SbMath.norm(new double[]{0.55 * SbMath.drift(t * 0.35 + 3.0), -1.0, 0.55 * SbMath.drift(t * 0.31 + 17.0)});
        double[] toMe = SbMath.norm(new double[]{-c[0], -c[1], -c[2]});
        double lock = SbMath.ease((t - SbTime.EYE_LOCK) / 0.45) * (1.0 - SbMath.ease((t - SbTime.EYE_AWAY) / 0.3));
        double[] g = SbMath.norm(SbMath.mix(wander, toMe, lock));
        double[] up0 = Math.abs(g[1]) > 0.95 ? new double[]{1.0, 0.0, 0.0} : SbMath.UP;
        double[] u = SbMath.norm(SbMath.sub(up0, SbMath.mul(g, SbMath.dot(up0, g))));
        // (the lids: opening, the blink, shut)
        double open = SbMath.ease((t - SbTime.EYE_OPEN) / 0.6);
        double bl = t - SbTime.EYE_BLINK;
        if (bl > 0.0 && bl < 0.35) {
            open *= bl < 0.1 ? 1.0 - bl / 0.1 : (bl - 0.1) / 0.25;
        }
        open *= 1.0 - SbMath.ease((t - SbTime.EYE_SHUT) / 0.15);
        double pupil = 0.13 - 0.04 * lock;
        double flare = 0.0;
        double fa = t - SbTime.HD_FLARE;
        if (fa > -0.05) {
            flare = SbMath.smooth(-0.05, 0.03, fa) * Math.exp(-Math.max(fa - 0.03, 0.0) / 0.75);
        }
        float[][] sph = sphere();
        BufferBuilder b = q.get();
        double yU = -0.02 + 0.62 * open;
        double yL = 0.02 - 0.52 * open;
        RenderSystem.defaultBlendFunc();
        for (int i = 0; i < sph.length; i += 4) {
            for (int k = 0; k < 4; k++) {
                float[] n = sph[i + k];
                double dg = n[0] * g[0] + n[1] * g[1] + n[2] * g[2];
                double ang = Math.acos(Math.max(-1.0, Math.min(1.0, dg)));
                double[] col;
                if (ang < pupil) {
                    col = new double[]{0.01, 0.01, 0.02};
                } else if (ang < 0.40) {
                    double az = Math.atan2(n[0] * u[2] - n[2] * u[0], n[1] * u[1] + 0.001);
                    double streak = 0.08 * Math.sin(az * 38.0) + 0.05 * Math.sin(az * 17.0 + 1.3);
                    double m = (ang - pupil) / (0.40 - pupil);
                    col = new double[]{0.18 + streak + 0.1 * m, 0.3 + streak + 0.12 * m, 0.48 + streak + 0.1 * m};
                    col = SbMath.add(col, SbMath.mul(new double[]{0.7, 0.85, 1.0}, flare * 1.2));
                    if (m > 0.85) {
                        col = SbMath.mul(col, 0.45);
                    }
                } else {
                    double e = SbMath.clamp01((ang - 0.4) / 1.0);
                    col = new double[]{0.86 - 0.35 * e, 0.83 - 0.38 * e, 0.8 - 0.36 * e};
                }
                double shade = 0.35 + 0.65 * Math.max(0.0, -n[1] * 0.8 + 0.2);
                double x = c[0] + n[0] * rr;
                double y = c[1] + n[1] * rr;
                double z = c[2] + n[2] * rr;
                vtx(b, x, y, z, (float) (col[0] * shade), (float) (col[1] * shade), (float) (col[2] * shade), 1.0F);
            }
        }
        // (the lids: dark skin over everything outside the opening, front side)
        for (int i = 0; i < sph.length; i += 4) {
            boolean covered = true;
            for (int k = 0; k < 4 && covered; k++) {
                float[] n = sph[i + k];
                double dg = n[0] * g[0] + n[1] * g[1] + n[2] * g[2];
                double y = n[0] * u[0] + n[1] * u[1] + n[2] * u[2];
                covered = dg > -0.35 && (y > yU || y < yL);
            }
            if (!covered) {
                continue;
            }
            for (int k = 0; k < 4; k++) {
                float[] n = sph[i + k];
                double sh = 0.4 + 0.6 * Math.max(0.0, -n[1] * 0.8 + 0.2);
                vtx(b, c[0] + n[0] * rr * 1.035, c[1] + n[1] * rr * 1.035, c[2] + n[2] * rr * 1.035,
                        (float) (0.16 * sh), (float) (0.11 * sh), (float) (0.13 * sh), 1.0F);
            }
        }
        q.draw();
    }

    // ================================================================= the island

    /** How high the torn-out land has risen (blocks over the ground) at t. */
    public static double liftY(double t) {
        if (t < SbTime.LF_TEAR) {
            return 0.0;
        }
        if (t < SbTime.LF_CLIMB) {
            return 10.0 * SbMath.ease((t - SbTime.LF_TEAR) / (SbTime.LF_CLIMB - SbTime.LF_TEAR));
        }
        if (t < SbTime.LF_TOUCH) {
            double u = (t - SbTime.LF_CLIMB) / (SbTime.LF_TOUCH - SbTime.LF_CLIMB);
            return 10.0 + (H - 40.0) * (u * u * (3.0 - 2.0 * u));
        }
        return H - 30.0 + 60.0 * SbMath.ease((t - SbTime.LF_TOUCH) / (SbTime.LF_IN - SbTime.LF_TOUCH));
    }

    private static void renderIsland(Minecraft mc, SkyBreakClient.Sky s, double t) {
        int[] l = s.lift;
        double y = liftY(t);
        double yaw = 0.12 * (t - SbTime.LF_TEAR);
        double tilt = 0.06 * Math.sin((t - SbTime.LF_TEAR) * 0.8);
        PoseStack ps = new PoseStack();
        ps.mulPoseMatrix(view);
        ps.translate(s.cx() - camX, s.groundY + y - camY, s.cz() - camZ);
        ps.mulPose(new Quaternionf().rotationY((float) yaw).rotateX((float) tilt));
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        var dispatcher = mc.getBlockRenderer();
        for (int i = 0; i + 3 < l.length; i += 4) {
            BlockState st = Block.stateById(l[i + 3]);
            if (st.isAir()) {
                continue;
            }
            ps.pushPose();
            ps.translate(l[i] - 0.5, l[i + 1], l[i + 2] - 0.5);
            dispatcher.renderSingleBlock(st, ps, buffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            ps.popPose();
        }
        buffers.endBatch();
    }

    // ================================================================= the panes

    private static void renderPane(Quads q, SkyBreakClient.Sky s, SkyBreakPlan.Pane p, double t, float[] sky) {
        double hx;
        double hz;
        double y;
        Quaternionf rot = new Quaternionf();
        double ground = s.groundAt(s.cx() + p.to[0], s.cz() + p.to[1]) - 1.0;
        double top = s.groundY + H;
        if (t < p.free) {
            double sag = SbMath.ease((t - (p.free - SbTime.SB_SAG)) / SbTime.SB_SAG);
            hx = p.from[0];
            hz = p.from[1];
            y = top - 5.0 * sag;
            rot.rotationAxis((float) (0.05 * sag), (float) Math.cos(p.spinAxis), 0.0F, (float) Math.sin(p.spinAxis));
        } else {
            double u = Math.min(1.0, (t - p.free) / SbTime.SB_FALL);
            hx = p.from[0] + (p.to[0] - p.from[0]) * u;
            hz = p.from[1] + (p.to[1] - p.from[1]) * u;
            y = top - 5.0 - (top - 5.0 - ground) * u * u;
            Quaternionf rest = new Quaternionf().rotationAxis((float) p.tilt, (float) -Math.sin(p.tiltDir), 0.0F, (float) Math.cos(p.tiltDir));
            Quaternionf tumble = new Quaternionf().rotationAxis((float) (p.spin * Math.sin(Math.PI * u)),
                    (float) Math.cos(p.spinAxis), 0.0F, (float) Math.sin(p.spinAxis));
            rot = tumble.mul(new Quaternionf().slerp(rest, (float) (u * u)));
        }
        double cx = s.cx() + hx - camX;
        double cy = y - camY;
        double cz = s.cz() + hz - camZ;
        double[][] o = p.outline;
        int n = o.length;
        double[][] bot = new double[n][];
        double[][] tp = new double[n][];
        Vector3f v = new Vector3f();
        for (int i = 0; i < n; i++) {
            rot.transform(v.set((float) o[i][0], -1.0F, (float) o[i][1]));
            bot[i] = new double[]{cx + v.x(), cy + v.y(), cz + v.z()};
            rot.transform(v.set((float) o[i][0], 1.0F, (float) o[i][1]));
            tp[i] = new double[]{cx + v.x(), cy + v.y(), cz + v.z()};
        }
        rot.transform(v.set(0.0F, -1.0F, 0.0F));
        double[] cb = {cx + v.x(), cy + v.y(), cz + v.z()};
        rot.transform(v.set(0.0F, 1.0F, 0.0F));
        double[] ct = {cx + v.x(), cy + v.y(), cz + v.z()};
        RenderSystem.defaultBlendFunc();
        BufferBuilder b = q.get();
        // (its underside still shows the sky; its back is the dark behind it)
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            vtx(b, cb, sky[0] * 1.06F, sky[1] * 1.04F, sky[2], 1.0F);
            vtx(b, bot[i], sky[0], sky[1], sky[2], 1.0F);
            vtx(b, bot[j], sky[0], sky[1], sky[2], 1.0F);
            vtx(b, cb, sky[0] * 1.06F, sky[1] * 1.04F, sky[2], 1.0F);
            vtx(b, ct, 0.05F, 0.03F, 0.1F, 1.0F);
            vtx(b, tp[i], 0.05F, 0.03F, 0.1F, 1.0F);
            vtx(b, tp[j], 0.05F, 0.03F, 0.1F, 1.0F);
            vtx(b, ct, 0.05F, 0.03F, 0.1F, 1.0F);
            // (its edge)
            vtx(b, bot[i], 0.85F, 0.92F, 1.0F, 1.0F);
            vtx(b, bot[j], 0.85F, 0.92F, 1.0F, 1.0F);
            vtx(b, tp[j], 0.6F, 0.7F, 0.85F, 1.0F);
            vtx(b, tp[i], 0.6F, 0.7F, 0.85F, 1.0F);
        }
        q.draw();
    }

    private static void glitter(Quads q, SkyBreakClient.Sky s, double age, double r0, double r1, int count, int salt, float[] sky) {
        additive();
        BufferBuilder b = q.get();
        double top = s.groundY + H;
        float fade = (float) Math.max(0.0, 1.0 - age / 6.0);
        Quaternionf rot = new Quaternionf();
        Vector3f v = new Vector3f();
        for (int i = 0; i < count; i++) {
            double a = SbMath.TAU * SbMath.mh(i, s.seed, salt);
            double rr = s.cracks.middleAt(a) * (r0 + (r1 - r0) * Math.sqrt(SbMath.mh(i, s.seed, salt + 1)));
            double vy = -(4.0 + 12.0 * SbMath.mh(i, s.seed, salt + 2));
            double y = top + vy * age - 0.5 * 48.0 * age * age;
            if (y < s.groundY) {
                continue;
            }
            double drift = 1.0 + 0.04 * age;
            double x = s.cx() + Math.cos(a) * rr * drift - camX;
            double z = s.cz() + Math.sin(a) * rr * drift - camZ;
            double size = 2.0 + 6.0 * SbMath.mh(i, s.seed, salt + 3);
            rot.rotationXYZ((float) (age * (1.0 + 3.0 * SbMath.mh(i, s.seed, salt + 4)) + i), (float) a, (float) (age * 2.0));
            double[][] c = new double[3][];
            for (int k = 0; k < 3; k++) {
                double ca = k * 2.1 + 0.4 * SbMath.mh(i * 3 + k, s.seed, salt + 5);
                rot.transform(v.set((float) (Math.cos(ca) * size), 0.0F, (float) (Math.sin(ca) * size)));
                c[k] = new double[]{x + v.x(), y - camY + v.y(), z + v.z()};
            }
            float al = 0.55F * fade;
            vtx(b, c[0], sky[0] + 0.3F, sky[1] + 0.3F, 1.0F, al);
            vtx(b, c[1], sky[0] + 0.3F, sky[1] + 0.3F, 1.0F, al);
            vtx(b, c[2], 0.9F, 0.95F, 1.0F, al);
            vtx(b, c[2], 0.9F, 0.95F, 1.0F, al);
        }
        q.draw();
    }

    // ================================================================= the sky's shapes

    /** A point of the sky's plane (plane coordinates from the point over the target), camera-relative. */
    private static double[] skyAt(SkyBreakClient.Sky s, double lx, double lz, double dy) {
        return new double[]{s.cx() + lx - camX, s.groundY + H + dy - camY, s.cz() + lz - camZ};
    }

    private static void segSky(BufferBuilder b, SkyBreakClient.Sky s, double x1, double z1, double x2, double z2,
                               double dy, double w, float r, float g, float bl, float a) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        double len = Math.hypot(dx, dz);
        if (len < 1e-4) {
            return;
        }
        double nx = -dz / len * w * 0.5;
        double nz = dx / len * w * 0.5;
        vtx(b, skyAt(s, x1 + nx, z1 + nz, dy), r, g, bl, a);
        vtx(b, skyAt(s, x2 + nx, z2 + nz, dy), r, g, bl, a);
        vtx(b, skyAt(s, x2 - nx, z2 - nz, dy), r, g, bl, a);
        vtx(b, skyAt(s, x1 - nx, z1 - nz, dy), r, g, bl, a);
    }

    private static void discSky(BufferBuilder b, SkyBreakClient.Sky s, double radius, double dy,
                                float r, float g, float bl, float aIn, float aOut) {
        int n = 48;
        for (int i = 0; i < n; i++) {
            double a0 = SbMath.TAU * i / n;
            double a1 = SbMath.TAU * (i + 1) / n;
            vtx(b, skyAt(s, 0, 0, dy), r, g, bl, aIn);
            vtx(b, skyAt(s, Math.cos(a0) * radius, Math.sin(a0) * radius, dy), r, g, bl, aOut);
            vtx(b, skyAt(s, Math.cos(a1) * radius, Math.sin(a1) * radius, dy), r, g, bl, aOut);
            vtx(b, skyAt(s, 0, 0, dy), r, g, bl, aIn);
        }
    }

    private static void ringSky(BufferBuilder b, SkyBreakClient.Sky s, double r0, double r1, double dy,
                                float r, float g, float bl, float a) {
        int n = 64;
        for (int i = 0; i < n; i++) {
            double a0 = SbMath.TAU * i / n;
            double a1 = SbMath.TAU * (i + 1) / n;
            vtx(b, skyAt(s, Math.cos(a0) * r0, Math.sin(a0) * r0, dy), r, g, bl, a);
            vtx(b, skyAt(s, Math.cos(a0) * r1, Math.sin(a0) * r1, dy), r, g, bl, a);
            vtx(b, skyAt(s, Math.cos(a1) * r1, Math.sin(a1) * r1, dy), r, g, bl, a);
            vtx(b, skyAt(s, Math.cos(a1) * r0, Math.sin(a1) * r0, dy), r, g, bl, a);
        }
    }

    /** The middle's ragged hole as a filled shape (k = its size, grow = drawn this much larger). */
    private static void holeSky(BufferBuilder b, SkyBreakClient.Sky s, double k, double grow, float r, float g, float bl, float a) {
        int n = 90;
        for (int i = 0; i < n; i++) {
            double a0 = SbMath.TAU * i / n;
            double a1 = SbMath.TAU * (i + 1) / n;
            double r0 = holeR(s, a0, k) * grow;
            double r1 = holeR(s, a1, k) * grow;
            if (grow > 1.0) {
                // (a rim: between the hole's edge and a little past it)
                double i0 = holeR(s, a0, k);
                double i1 = holeR(s, a1, k);
                vtx(b, skyAt(s, Math.cos(a0) * i0, Math.sin(a0) * i0, -0.2), r, g, bl, a);
                vtx(b, skyAt(s, Math.cos(a0) * r0, Math.sin(a0) * r0, -0.2), r, g, bl, 0.0F);
                vtx(b, skyAt(s, Math.cos(a1) * r1, Math.sin(a1) * r1, -0.2), r, g, bl, 0.0F);
                vtx(b, skyAt(s, Math.cos(a1) * i1, Math.sin(a1) * i1, -0.2), r, g, bl, a);
            } else {
                vtx(b, skyAt(s, 0, 0, 0.5), r, g, bl, a);
                vtx(b, skyAt(s, Math.cos(a0) * r0, Math.sin(a0) * r0, 0.5), r, g, bl, a);
                vtx(b, skyAt(s, Math.cos(a1) * r1, Math.sin(a1) * r1, 0.5), r, g, bl, a);
                vtx(b, skyAt(s, 0, 0, 0.5), r, g, bl, a);
            }
        }
    }

    private static void paneHoleSky(BufferBuilder b, SkyBreakClient.Sky s, SkyBreakPlan.Pane p, double k,
                                    float r, float g, float bl, float a) {
        double[][] o = p.outline;
        for (int i = 0; i < o.length; i++) {
            int j = (i + 1) % o.length;
            vtx(b, skyAt(s, p.from[0], p.from[1], 0.5), r, g, bl, a);
            vtx(b, skyAt(s, p.from[0] + o[i][0] * k, p.from[1] + o[i][1] * k, 0.5), r, g, bl, a);
            vtx(b, skyAt(s, p.from[0] + o[j][0] * k, p.from[1] + o[j][1] * k, 0.5), r, g, bl, a);
            vtx(b, skyAt(s, p.from[0], p.from[1], 0.5), r, g, bl, a);
        }
    }

    private static void paneOutlineSky(BufferBuilder b, SkyBreakClient.Sky s, SkyBreakPlan.Pane p, float a) {
        double[][] o = p.outline;
        for (int i = 0; i < o.length; i++) {
            int j = (i + 1) % o.length;
            segSky(b, s, p.from[0] + o[i][0], p.from[1] + o[i][1], p.from[0] + o[j][0], p.from[1] + o[j][1], -0.4, 1.4,
                    0.93F, 0.98F, 1.0F, a);
        }
    }

    /**
     * The unbroken sky as a depth-only shell: a polar grid out to the horizon, leaving out the middle's hole and the
     * holes where panes broke free, so what is behind the sky shows only through them.
     */
    private static void occluder(BufferBuilder b, SkyBreakClient.Sky s, double holeS, double t, double heal) {
        int na = 96;
        double[] radii = {0, 15, 30, 45, 60, 75, 90, 105, 120, 140, 165, 195, 230, 270, 320, 380, 450, 540, 650, 800,
                1000, 1300, 1700, 2300, 3100, 4200, 5600, 7500, 9500};
        for (int i = 0; i < na; i++) {
            double a0 = SbMath.TAU * i / na;
            double a1 = SbMath.TAU * (i + 1) / na;
            double am = (a0 + a1) * 0.5;
            double hole = holeS > 0.01 ? holeR(s, am, holeS) : 0.0;
            for (int k = 0; k + 1 < radii.length; k++) {
                double r0 = radii[k];
                double r1 = radii[k + 1];
                double rm = (r0 + r1) * 0.5;
                if (rm < hole) {
                    continue;
                }
                double ra = Math.max(r0, Math.min(hole, r1));
                double mx = Math.cos(am) * rm;
                double mz = Math.sin(am) * rm;
                boolean inPane = false;
                for (SkyBreakPlan.Pane p : s.plan.panes) {
                    if (t >= p.free && heal > 0.05 && p.covers((mx - p.from[0]) / heal, (mz - p.from[1]) / heal)) {
                        inPane = true;
                        break;
                    }
                }
                if (inPane) {
                    continue;
                }
                vtx(b, skyAt(s, Math.cos(a0) * ra, Math.sin(a0) * ra, 0.0), 0, 0, 0, 1);
                vtx(b, skyAt(s, Math.cos(a0) * r1, Math.sin(a0) * r1, 0.0), 0, 0, 0, 1);
                vtx(b, skyAt(s, Math.cos(a1) * r1, Math.sin(a1) * r1, 0.0), 0, 0, 0, 1);
                vtx(b, skyAt(s, Math.cos(a1) * ra, Math.sin(a1) * ra, 0.0), 0, 0, 0, 1);
            }
        }
    }

    private static void ringWorld(BufferBuilder b, double x, double y, double z, double r0, double r1,
                                  float r, float g, float bl, float a) {
        int n = 96;
        for (int i = 0; i < n; i++) {
            double a0 = SbMath.TAU * i / n;
            double a1 = SbMath.TAU * (i + 1) / n;
            vtx(b, new double[]{x + Math.cos(a0) * r0 - camX, y - camY, z + Math.sin(a0) * r0 - camZ}, r, g, bl, a);
            vtx(b, new double[]{x + Math.cos(a0) * r1 - camX, y - camY, z + Math.sin(a0) * r1 - camZ}, r, g, bl, a);
            vtx(b, new double[]{x + Math.cos(a1) * r1 - camX, y - camY, z + Math.sin(a1) * r1 - camZ}, r, g, bl, a);
            vtx(b, new double[]{x + Math.cos(a1) * r0 - camX, y - camY, z + Math.sin(a1) * r0 - camZ}, r, g, bl, a);
        }
    }

    // ================================================================= low level

    private static float[] skyColor(Minecraft mc, float partial) {
        Vec3 c = mc.level.getSkyColor(mc.gameRenderer.getMainCamera().getPosition(), partial);
        return new float[]{(float) c.x, (float) c.y, (float) c.z};
    }

    /** Pulls a camera-relative point in along its line of sight past warp0, never past warp1. */
    private static void warpTo(double x, double y, double z, Vector3f out) {
        double d = Math.sqrt(x * x + y * y + z * z);
        if (d > warp0) {
            double span = warp1 - warp0;
            double nd = warp0 + span * (1.0 - Math.exp(-(d - warp0) / span));
            double k = nd / d;
            x *= k;
            y *= k;
            z *= k;
        }
        out.set((float) x, (float) y, (float) z);
    }

    private static void vtx(BufferBuilder b, double[] p, float r, float g, float bl, float a) {
        vtx(b, p[0], p[1], p[2], r, g, bl, a);
    }

    private static void vtx(BufferBuilder b, double x, double y, double z, float r, float g, float bl, float a) {
        warpTo(x, y, z, TMP);
        view.transformPosition(TMP);
        b.vertex(TMP.x(), TMP.y(), TMP.z()).color(r, g, bl, a).endVertex();
    }

    private static void prepareFx() {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.enableDepthTest();
    }

    private static void additive() {
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
    }

    /** Lazily begins a QUADS / POSITION_COLOR batch; draws only if something was added. */
    private static final class Quads {
        private BufferBuilder builder;

        BufferBuilder get() {
            if (builder == null) {
                RenderSystem.setShader(GameRenderer::getPositionColorShader);
                builder = Tesselator.getInstance().getBuilder();
                builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            }
            return builder;
        }

        void draw() {
            if (builder != null) {
                Tesselator.getInstance().end();
                builder = null;
            }
        }
    }
}
