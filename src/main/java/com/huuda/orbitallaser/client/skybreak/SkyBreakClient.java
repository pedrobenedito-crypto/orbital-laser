package com.huuda.orbitallaser.client.skybreak;

import com.huuda.orbitallaser.OrbitalLaserMod;
import com.huuda.orbitallaser.config.ClientConfig;
import com.huuda.orbitallaser.network.SkyBreakPackets;
import com.huuda.orbitallaser.registry.ModSounds;
import com.huuda.orbitallaser.skybreak.ArmPlanner;
import com.huuda.orbitallaser.skybreak.SbTime;
import com.huuda.orbitallaser.skybreak.SkyBreakPlan;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client side of the Sky Breaks: the strikes to draw, their sounds on the original's timeline (a port of
 * sb_sounds.rs's `sb_cues` and skybreak.rs's `sb_pane_events`, Stink-O's Orbital Laser, MIT), dust, shake and flash.
 */
@Mod.EventBusSubscriber(modid = OrbitalLaserMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SkyBreakClient {

    /** One strike as this client sees it. */
    public static final class Sky {
        public final UUID id;
        public final ClientLevel level;
        public final BlockPos target;
        public final int groundY;
        public final int seed;
        public final boolean terrain;
        /** Game time at the strike's start. */
        public final long start;
        public final SkyBreakPlan plan;
        public final ArmPlanner arm;
        public final SkyCracks cracks;
        public int[] lift = new int[0];
        public long endTime = -1;
        final List<Cue> cues = new ArrayList<>();
        int nextCue;
        /** For shake and flash: when and how hard. */
        final List<double[]> jolts = new ArrayList<>();
        final List<double[]> flashes = new ArrayList<>();
        boolean dustImpact;
        final boolean[] paneDust = new boolean[SbTime.SB_NS];

        Sky(SkyBreakPackets.Start m, ClientLevel level) {
            this.id = m.id();
            this.level = level;
            this.target = m.target();
            this.groundY = m.groundY();
            this.seed = m.seed();
            this.terrain = m.terrain();
            this.start = level.getGameTime() - m.ticks();
            this.plan = new SkyBreakPlan(seed, new double[]{m.meX(), m.meY(), m.meZ()});
            this.arm = plan.arm(0.0);
            this.cracks = new SkyCracks(seed);
        }

        public double cx() {
            return target.getX() + 0.5;
        }

        public double cz() {
            return target.getZ() + 0.5;
        }

        public double t(double now) {
            return (now - start) / 20.0;
        }

        /** The land's height under a point of a pane's landing (world y). */
        public double groundAt(double x, double z) {
            return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(z));
        }
    }

    record Cue(double t, String key, float volume, float pitch) {
    }

    private static final Map<UUID, Sky> SKIES = new LinkedHashMap<>();

    private SkyBreakClient() {
    }

    public static Collection<Sky> all() {
        return SKIES.values();
    }

    public static boolean isEmpty() {
        return SKIES.isEmpty();
    }

    // ================================================================= network

    public static void onStart(SkyBreakPackets.Start m) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        Sky s = new Sky(m, level);
        SKIES.put(s.id, s);
        if (mc.player != null) {
            buildCues(s, mc.player);
            // (a late join: skip what has already played)
            double t = m.ticks() / 20.0;
            while (s.nextCue < s.cues.size() && s.cues.get(s.nextCue).t() < t - 0.5) {
                s.nextCue++;
            }
        }
    }

    public static void onLift(SkyBreakPackets.Lift m) {
        Sky s = SKIES.get(m.id());
        if (s != null) {
            s.lift = m.blocks();
        }
    }

    public static void onEnd(SkyBreakPackets.End m) {
        Sky s = SKIES.get(m.id());
        if (s != null && s.endTime < 0) {
            s.endTime = s.level.getGameTime();
        }
    }

    // ================================================================= sounds (the original's cues)

    private static double farShare(double d, double near, double all) {
        double u = Math.max(0.0, Math.min(1.0, (d - near) / (all - near)));
        return u * u * (3.0 - 2.0 * u);
    }

    private static void buildCues(Sky s, Player player) {
        double[] me = {player.getX() - s.cx(), player.getY() - s.groundY, player.getZ() - s.cz()};
        double d = Math.sqrt(me[0] * me[0] + me[1] * me[1] + me[2] * me[2]);
        double late = d / 343.0;
        double skyD = Math.sqrt(me[0] * me[0] + Math.pow(SbTime.SB_H - me[1], 2) + me[2] * me[2]);
        double skyLate = Math.min(skyD / 343.0, 1.2);
        List<Cue> c = s.cues;

        double far = farShare(d, 200.0, 800.0);
        double v = Math.min(1.25 / (1.0 + d / 400.0), 1.0);
        cue(c, 0.0, "skybreak.tear", v * Math.sqrt(1.0 - far), 1.0F);
        cue(c, 0.0, "skybreak.tear_far", v * Math.sqrt(far), 1.0F);
        cue(c, 0.0, "skybreak.rise", 0.75 + 0.25 / (1.0 + d / 300.0), 1.0F);
        cue(c, SbTime.LF_TOUCH - 0.3, "skybreak.sink", 1.0, 1.0F);
        cue(c, SbTime.LF_RIPPLE - 0.05, "skybreak.ripple", 1.0, 1.0F);
        cue(c, SbTime.LF_RIPPLE, "skybreak.pressure", 1.0, 1.0F);
        cue(c, SbTime.SB_CRACK - 0.02, "skybreak.crack", 1.0, 1.0F);
        cue(c, SbTime.SB_CRACK + 0.3, "skybreak.withdraw", 1.0, 1.0F);
        cue(c, SbTime.SB_CRACK + 0.6, "skybreak.strain", 1.0, 1.0F);
        cue(c, SbTime.EYE_LOCK + 0.45 - 7.6, "skybreak.gaze", 1.0, 1.0F);
        cue(c, SbTime.SB_COLLAPSE - 0.15 + skyLate * 0.5, "skybreak.collapse", 1.0, 1.0F);
        cue(c, 9.5 + SbTime.SB_LEAD, "skybreak.void", 1.0, 1.0F);

        // ---- the panes (sb_pane_events)
        double lastHit = SbTime.SB_COLLAPSE;
        for (SkyBreakPlan.Pane p : s.plan.panes) {
            int i = p.index;
            double tr = p.free;
            double hit = p.lands();
            double[] foot = {p.to[0], 0.0, p.to[1]};
            double pd = Math.sqrt(Math.pow(foot[0] - me[0], 2) + Math.pow(me[1], 2) + Math.pow(foot[2] - me[2], 2));
            double pl = pd / 343.0;
            float pitch = (float) (0.92 + 0.16 * com.huuda.orbitallaser.skybreak.SbMath.mh(i, s.seed, 0x70));
            cue(c, tr - SbTime.SB_SAG - 0.2 + skyLate * 0.4, i % 2 == 0 ? "skybreak.release" : "skybreak.release2", 1.0, pitch);
            cue(c, hit - 4.6, "skybreak.fall", 0.55 + 0.45 * Math.min(1.0 / (1.0 + pd / 500.0), 1.0), 1.0F);
            double tg = hit + pd / 1715.0;
            double gap = hit + pl - tg;
            double gv = Math.max(0.3, Math.min(1.0, 1.0 / (1.0 + pd / 900.0))) * Math.pow(Math.max(0.25, Math.min(1.0, (gap - 0.05) / 0.9)), 1.5);
            cue(c, tg - 0.02, "skybreak.ground", gv, 1.0F);
            double pf = farShare(pd, 300.0, 900.0);
            String take = new String[]{"skybreak.impact", "skybreak.impact2", "skybreak.impact3"}[i % 3];
            if (pf < 1.0) {
                cue(c, hit + pl - 0.02, take, 1.0 - pf, pitch);
            }
            if (pf > 0.0) {
                cue(c, hit + pl - 0.02, "skybreak.impact_far", pf, pitch);
            }
            cue(c, hit + 0.34 + pl - 0.01, "skybreak.slam", 0.6 * (1.0 - 0.6 * pf), pitch);
            double fo = Math.max(0.2, Math.min(1.0, 1.5 / (1.0 + pd / 260.0)));
            s.jolts.add(new double[]{hit + pl, fo, 0.6});
            s.jolts.add(new double[]{hit + 0.34 + pl, 0.85 * fo, 0.35});
            s.flashes.add(new double[]{hit + pl, 0.25 * fo});
            if (hit < SbTime.HD_FLARE - 3.0) {
                lastHit = Math.max(lastHit, hit);
            }
        }
        cue(c, lastHit + 2.0, "skybreak.rumble", 1.0, 1.0F);

        // ---- the eye's flare, the hand coming down, the slam
        cue(c, SbTime.HD_FLARE - 4.0, "skybreak.flare", 1.0, 1.0F);
        cue(c, SbTime.HD_DOWN + late, "skybreak.descend", 1.0, 1.0F);
        double tg = SbTime.HD_PRESS + d / 1715.0;
        double gap = SbTime.HD_PRESS + late - tg;
        cue(c, tg - 0.02, "payload.ground_alt",
                Math.max(0.3, Math.min(1.0, 1.0 / (1.0 + d / 900.0))) * Math.pow(Math.max(0.25, Math.min(1.0, (gap - 0.05) / 0.9)), 1.5), 1.0F);
        double sf = farShare(d, 500.0, 1500.0);
        cue(c, SbTime.HD_PRESS + late - 0.02, "payload.blast_alt", 1.0 - sf, 1.0F);
        cue(c, SbTime.HD_PRESS + late - 0.02, "payload.blast_alt_far", sf, 1.0F);
        cue(c, SbTime.HD_PRESS + late - 0.15, "payload.rush", 1.0, 1.0F);
        cue(c, SbTime.HD_PRESS + late + 2.4, "payload.tail", 1.0, 1.0F);
        cue(c, Math.max(SbTime.HD_KNOCK + skyLate * 0.5, SbTime.HD_PRESS + late + 0.3) - 0.1, "skybreak.shudder", 1.0, 1.0F);
        cue(c, SbTime.SB_HEAL - 1.0, "skybreak.heal", 1.0, 1.0F);

        // ---- jolts and flashes of the big moments
        double fall = Math.min(1.0, 1.4 / (1.0 + d / 100.0));
        s.jolts.add(new double[]{SbTime.LF_TEAR, 0.35 * fall, 0.6});
        s.jolts.add(new double[]{SbTime.SB_CRACK, 0.9, 0.5});
        s.jolts.add(new double[]{SbTime.HD_PRESS + d / 1715.0, Math.max(0.25, fall), 1.2});
        s.flashes.add(new double[]{SbTime.SB_CRACK, 0.85});
        s.flashes.add(new double[]{SbTime.HD_FLARE, 0.35});
        s.flashes.add(new double[]{SbTime.HD_PRESS + late, 0.45 * fall});

        c.sort((a, b) -> Double.compare(a.t(), b.t()));
    }

    private static void cue(List<Cue> c, double t, String key, double volume, float pitch) {
        if (volume > 0.001) {
            c.add(new Cue(Math.max(0.0, t), key, (float) Math.max(0.0, Math.min(1.0, volume)), pitch));
        }
    }

    private static void play(Cue c) {
        SoundEvent ev = ModSounds.get(c.key());
        if (ev == null) {
            return;
        }
        Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(ev.getLocation(), SoundSource.AMBIENT,
                c.volume(), c.pitch(), RandomSource.create(), false, 0, SoundInstance.Attenuation.NONE,
                0.0, 0.0, 0.0, true));
    }

    // ================================================================= tick

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            SKIES.clear();
            return;
        }
        if (mc.isPaused()) {
            return;
        }
        long now = level.getGameTime();
        Iterator<Sky> it = SKIES.values().iterator();
        while (it.hasNext()) {
            Sky s = it.next();
            if (s.level != level || (s.endTime >= 0 && now - s.endTime > 100) || s.t(now) > SbTime.SB_LEN + 140.0) {
                it.remove();
                continue;
            }
            double t = s.t(now);
            while (s.nextCue < s.cues.size() && s.cues.get(s.nextCue).t() <= t) {
                Cue c = s.cues.get(s.nextCue++);
                if (t - c.t() < 1.0) {
                    play(c);
                }
            }
            if (mc.player != null && ClientConfig.PARTICLES.get()) {
                particles(level, s, t, mc.player);
            }
        }
    }

    private static void particles(ClientLevel level, Sky s, double t, Player player) {
        double dx = player.getX() - s.cx();
        double dz = player.getZ() - s.cz();
        if (dx * dx + dz * dz > 900.0 * 900.0) {
            return;
        }
        RandomSource r = level.random;
        double gy = s.groundY;
        // (the lift: dust rolling out from where the land tore, dirt falling off its underside)
        if (t >= SbTime.LF_TEAR && t < SbTime.LF_TEAR + 1.5) {
            for (int i = 0; i < 6; i++) {
                double a = r.nextDouble() * Math.PI * 2.0;
                double rr = SbTime.LF_R + r.nextDouble() * 3.0;
                level.addAlwaysVisibleParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, true, s.cx() + Math.cos(a) * rr, gy + 0.5,
                        s.cz() + Math.sin(a) * rr, Math.cos(a) * 0.25, 0.02, Math.sin(a) * 0.25);
            }
        }
        if (t >= SbTime.LF_TEAR && t < SbTime.LF_TOUCH) {
            double y = SkyBreakRenderer.liftY(t);
            for (int i = 0; i < 3; i++) {
                level.addAlwaysVisibleParticle(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.DIRT.defaultBlockState()), true,
                        s.cx() + r.nextGaussian() * 3.0, gy + y - 6.0, s.cz() + r.nextGaussian() * 3.0, 0.0, -0.3, 0.0);
            }
        }
        // (the panes' landings)
        for (SkyBreakPlan.Pane p : s.plan.panes) {
            if (!s.paneDust[p.index] && t >= p.lands()) {
                s.paneDust[p.index] = true;
                double x = s.cx() + p.to[0];
                double z = s.cz() + p.to[1];
                double y = s.groundAt(x, z);
                for (int i = 0; i < 10; i++) {
                    level.addAlwaysVisibleParticle(ParticleTypes.EXPLOSION_EMITTER, true, x + r.nextGaussian() * 12.0, y + 2.0,
                            z + r.nextGaussian() * 12.0, 0.0, 0.0, 0.0);
                }
                for (int i = 0; i < 160; i++) {
                    level.addAlwaysVisibleParticle(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.PACKED_ICE.defaultBlockState()), true,
                            x + r.nextGaussian() * 16.0, y + 4.0 + r.nextDouble() * 6.0, z + r.nextGaussian() * 16.0,
                            r.nextGaussian() * 0.5, 0.5 + r.nextDouble(), r.nextGaussian() * 0.5);
                }
            }
        }
        // (the fist: a wall of dust running out over the land)
        if (t >= SbTime.HD_PRESS && t < SbTime.HD_PRESS + 6.0) {
            double age = t - SbTime.HD_PRESS;
            if (!s.dustImpact) {
                s.dustImpact = true;
                for (int i = 0; i < 24; i++) {
                    level.addAlwaysVisibleParticle(ParticleTypes.EXPLOSION_EMITTER, true, s.cx() + r.nextGaussian() * 25.0, gy + 3.0,
                            s.cz() + r.nextGaussian() * 25.0, 0.0, 0.0, 0.0);
                }
            }
            double ring = 40.0 + 160.0 * Math.pow(age / 6.0, 0.6);
            int n = (int) (40 * (1.0 - age / 6.0)) + 4;
            for (int i = 0; i < n; i++) {
                double a = r.nextDouble() * Math.PI * 2.0;
                double rr = ring * (0.85 + 0.3 * r.nextDouble());
                double x = s.cx() + Math.cos(a) * rr;
                double z = s.cz() + Math.sin(a) * rr;
                level.addAlwaysVisibleParticle(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, true, x, s.groundAt(x, z) + 1.0, z,
                        Math.cos(a) * 0.4, 0.06 + r.nextDouble() * 0.1, Math.sin(a) * 0.4);
            }
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        SKIES.clear();
    }

    // ================================================================= shake and flash

    private static double nowT(Sky s, double partial) {
        return s.t(s.level.getGameTime() + partial);
    }

    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (SKIES.isEmpty() || !ClientConfig.SCREEN_SHAKE.get()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        double amp = 0.0;
        double tt = 0.0;
        for (Sky s : SKIES.values()) {
            if (s.level != mc.level) {
                continue;
            }
            double t = nowT(s, event.getPartialTick());
            for (double[] j : s.jolts) {
                double a = t - j[0];
                if (a >= 0.0 && a < j[2] * 4.0) {
                    amp = Math.max(amp, j[1] * Math.exp(-a / j[2]));
                }
            }
            // (the pressure building on the glass)
            if (t > SbTime.LF_PRESS && t < SbTime.SB_CRACK) {
                amp = Math.max(amp, 0.08 * (t - SbTime.LF_PRESS) / (SbTime.SB_CRACK - SbTime.LF_PRESS));
            }
            tt = t;
        }
        if (amp < 0.002) {
            return;
        }
        double k = amp * 3.0 * ClientConfig.SHAKE_STRENGTH.get();
        double x = tt * 20.0;
        event.setPitch((float) (event.getPitch() + (Math.sin(x * 1.9) + 0.5 * Math.sin(x * 4.7)) * k));
        event.setYaw((float) (event.getYaw() + (Math.cos(x * 2.3) + 0.4 * Math.sin(x * 5.3)) * k));
        event.setRoll((float) (event.getRoll() + Math.sin(x * 3.1) * k * 0.6));
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (SKIES.isEmpty() || !ClientConfig.SKYBREAK_FLASH.get()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        double a = 0.0;
        for (Sky s : SKIES.values()) {
            if (s.level != mc.level) {
                continue;
            }
            double t = nowT(s, event.getPartialTick());
            for (double[] f : s.flashes) {
                double age = t - f[0];
                if (age >= 0.0 && age < 0.6) {
                    a = Math.max(a, f[1] * Math.exp(-age / 0.12));
                }
            }
        }
        if (a < 0.01) {
            return;
        }
        int alpha = (int) (Math.min(1.0, a) * 255.0);
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        event.getGuiGraphics().fill(0, 0, w, h, (alpha << 24) | 0xF2F8FF);
    }
}
