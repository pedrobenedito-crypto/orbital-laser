package com.huuda.orbitallaser.client;

import com.huuda.orbitallaser.OrbitalLaserMod;
import com.huuda.orbitallaser.client.hand.HandAnimator;
import com.huuda.orbitallaser.config.ClientConfig;
import com.huuda.orbitallaser.network.StrikeSyncPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Client-side list of strikes to draw, plus the dust column particles and the screen shake. */
@Mod.EventBusSubscriber(modid = OrbitalLaserMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientStrikes {
    private static final Map<UUID, ClientStrike> STRIKES = new LinkedHashMap<>();

    private ClientStrikes() {
    }

    public static Collection<ClientStrike> all() {
        return STRIKES.values();
    }

    public static boolean isEmpty() {
        return STRIKES.isEmpty();
    }

    // ================================================================= network

    public static void handle(StrikeSyncPacket msg) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        long now = level.getGameTime();

        if (msg.phase() == StrikeSyncPacket.ENDED || msg.phase() == StrikeSyncPacket.CANCELLED) {
            ClientStrike s = STRIKES.get(msg.id());
            if (s != null && !s.ended()) {
                s.endTime = now;
                s.cancelled = msg.phase() == StrikeSyncPacket.CANCELLED;
            }
            return;
        }

        ClientStrike s = STRIKES.get(msg.id());
        if (s == null || s.level != level) {
            s = new ClientStrike(msg.id(), level);
            STRIKES.put(msg.id(), s);
        }
        if (s.phase != msg.phase()) {
            s.impactSpawned = false;
        }
        s.target = msg.target();
        s.phase = msg.phase();
        s.phaseStart = now - msg.ticksInPhase();
        s.chargeTicks = Math.max(1, msg.chargeTicks());
        s.radius = msg.radius();
        s.depth = msg.depth();
        s.seed = msg.seed();
        s.endTime = -1;
        s.cancelled = false;
    }

    // ================================================================= tick: cleanup + particles

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            STRIKES.clear();
            return;
        }
        if (mc.isPaused()) {
            return;
        }
        long gameTime = level.getGameTime();
        Iterator<ClientStrike> it = STRIKES.values().iterator();
        while (it.hasNext()) {
            ClientStrike s = it.next();
            if (s.level != level || s.expired(gameTime)) {
                it.remove();
            }
        }
        if (mc.player == null) {
            return;
        }
        for (ClientStrike s : STRIKES.values()) {
            detectPunches(level, s, gameTime, mc.player);
        }
        if (!ClientConfig.PARTICLES.get()) {
            return;
        }
        for (ClientStrike s : STRIKES.values()) {
            spawnParticles(level, s, gameTime, mc.player);
        }
    }

    /** Plays the glass impacts of the Sky Hand's three punches and kicks the screen shake. */
    private static void detectPunches(ClientLevel level, ClientStrike s, long gameTime, Player player) {
        if (s.phase != StrikeSyncPacket.CHARGING || s.ended()) {
            return;
        }
        float p = (gameTime - s.phaseStart) / (float) s.chargeTicks;
        float last = s.lastChargeP;
        s.lastChargeP = p;
        if (Float.isNaN(last)) {
            return;
        }
        for (int i = 0; i < HandAnimator.PUNCHES.length; i++) {
            float at = HandAnimator.PUNCHES[i];
            if (last < at && p >= at) {
                boolean breaks = i == HandAnimator.PUNCHES.length - 1;
                s.lastPunch = gameTime;
                s.punchStrength = breaks ? 1.0F : 0.55F + i * 0.15F;
                if (breaks) {
                    skySound(level, s, player, SoundEvents.GLASS_BREAK, 1.0F, 0.5F);
                    skySound(level, s, player, SoundEvents.GLASS_BREAK, 1.0F, 0.75F);
                    skySound(level, s, player, SoundEvents.GLASS_BREAK, 0.9F, 1.0F);
                    skySound(level, s, player, SoundEvents.GENERIC_EXPLODE, 0.9F, 0.55F);
                } else {
                    skySound(level, s, player, SoundEvents.ANVIL_LAND, 0.7F, 0.45F + i * 0.1F);
                    skySound(level, s, player, SoundEvents.GLASS_BREAK, 0.8F, 0.6F + i * 0.1F);
                }
            }
        }
    }

    /** A sound from the sky, heard from far away: played near the listener, quieter with distance. */
    private static void skySound(ClientLevel level, ClientStrike s, Player player, SoundEvent sound,
                                 float volume, float pitch) {
        double dx = s.centerX() - player.getX();
        double dy = s.skyY() - player.getEyeY();
        double dz = s.centerZ() - player.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        float falloff = (float) Math.max(0.12, 1.0 - dist / 700.0);
        double k = dist > 1.0 ? 6.0 / dist : 0.0;
        level.playLocalSound(player.getX() + dx * k, player.getEyeY() + dy * k, player.getZ() + dz * k,
                sound, SoundSource.BLOCKS, volume * falloff, pitch, false);
    }

    private static void spawnParticles(ClientLevel level, ClientStrike s, long gameTime, Player player) {
        double cx = s.centerX();
        double cz = s.centerZ();
        double dx = player.getX() - cx;
        double dz = player.getZ() - cz;
        if (dx * dx + dz * dz > 700.0 * 700.0) {
            return;
        }
        RandomSource r = level.random;
        double groundY = s.target.getY() + 1.0;

        if (s.phase == StrikeSyncPacket.CHARGING) {
            if (s.ended()) {
                return;
            }
            float p = Math.min(1.0F, (gameTime - s.phaseStart) / (float) s.chargeTicks);
            // The ground answers: sparks and ash lift inside the target area.
            int lifts = (int) (p * 5);
            for (int i = 0; i < lifts; i++) {
                double a = r.nextDouble() * Math.PI * 2.0;
                double d = Math.sqrt(r.nextDouble()) * s.radius;
                level.addAlwaysVisibleParticle(ParticleTypes.END_ROD, true,
                        cx + Math.cos(a) * d, groundY + r.nextDouble() * 2.0, cz + Math.sin(a) * d,
                        0.0, 0.08 + p * 0.25, 0.0);
                level.addParticle(ParticleTypes.WHITE_ASH,
                        cx + Math.cos(a) * d, groundY + r.nextDouble() * 3.0, cz + Math.sin(a) * d,
                        0.0, 0.05, 0.0);
            }
            // Glass flakes drifting down from the crack.
            if (r.nextFloat() < p) {
                level.addAlwaysVisibleParticle(ParticleTypes.END_ROD, true,
                        cx + r.nextGaussian() * s.radius * 0.3, s.skyY() - 3.0, cz + r.nextGaussian() * s.radius * 0.3,
                        0.0, -0.5, 0.0);
            }
            return;
        }

        if (s.cancelled) {
            return;
        }
        double since = s.ticksSinceImpact(gameTime);
        if (since < 0) {
            return;
        }

        if (!s.impactSpawned && !s.ended()) {
            s.impactSpawned = true;
            for (int i = 0; i < 8; i++) {
                level.addAlwaysVisibleParticle(ParticleTypes.EXPLOSION_EMITTER, true,
                        cx + r.nextGaussian() * s.radius * 0.4, groundY, cz + r.nextGaussian() * s.radius * 0.4,
                        0.0, 0.0, 0.0);
            }
            level.addAlwaysVisibleParticle(ParticleTypes.FLASH, true, cx, groundY + 2.0, cz, 0.0, 0.0, 0.0);
        }

        // Dust column: thick while the beam digs, thinning out in the aftermath.
        float rate;
        if (!s.ended()) {
            rate = since < 40 ? 14.0F : 8.0F;
        } else {
            float age = (gameTime - s.endTime) / (float) ClientStrike.AFTERMATH_TICKS;
            rate = 8.0F * Math.max(0.0F, 1.0F - age);
        }
        int count = (int) rate + (r.nextFloat() < rate - (int) rate ? 1 : 0);
        for (int i = 0; i < count; i++) {
            double a = r.nextDouble() * Math.PI * 2.0;
            double d = Math.sqrt(r.nextDouble()) * s.radius * 0.9;
            double y = groundY - r.nextDouble() * s.depth * 0.6;
            level.addAlwaysVisibleParticle(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, true,
                    cx + Math.cos(a) * d, y, cz + Math.sin(a) * d,
                    (r.nextDouble() - 0.5) * 0.03, 0.08 + r.nextDouble() * 0.1, (r.nextDouble() - 0.5) * 0.03);
        }
        // Debris puffs on the rim and embers in the middle while the beam is on.
        if (!s.ended()) {
            for (int i = 0; i < 3; i++) {
                double a = r.nextDouble() * Math.PI * 2.0;
                double d = s.radius * (0.9 + r.nextDouble() * 0.25);
                level.addAlwaysVisibleParticle(ParticleTypes.LARGE_SMOKE, true,
                        cx + Math.cos(a) * d, groundY + r.nextDouble() * 3.0, cz + Math.sin(a) * d,
                        Math.cos(a) * 0.2, 0.15, Math.sin(a) * 0.2);
                level.addAlwaysVisibleParticle(ParticleTypes.LAVA, true,
                        cx + r.nextGaussian() * s.radius * 0.25, groundY - r.nextDouble() * s.depth * 0.5,
                        cz + r.nextGaussian() * s.radius * 0.25, 0.0, 0.0, 0.0);
            }
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        STRIKES.clear();
    }

    // ================================================================= screen shake

    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (STRIKES.isEmpty() || !ClientConfig.SCREEN_SHAKE.get()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        double now = mc.level.getGameTime() + event.getPartialTick();
        float intensity = shakeIntensity(mc.player, now);
        if (intensity <= 0.001F) {
            return;
        }
        float amp = (float) (intensity * 2.5 * ClientConfig.SHAKE_STRENGTH.get());
        event.setPitch(event.getPitch() + (float) (Math.sin(now * 1.9) + Math.sin(now * 4.7) * 0.5) * amp);
        event.setYaw(event.getYaw() + (float) (Math.cos(now * 2.3) + Math.sin(now * 5.3) * 0.4) * amp);
        event.setRoll(event.getRoll() + (float) Math.sin(now * 3.1) * amp * 0.6F);
    }

    private static float shakeIntensity(Player player, double now) {
        float best = 0.0F;
        for (ClientStrike s : STRIKES.values()) {
            if (s.level != player.level() || s.cancelled) {
                continue;
            }
            double dx = player.getX() - s.centerX();
            double dz = player.getZ() - s.centerZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            float falloff = (float) Math.max(0.0, 1.0 - dist / (s.radius * 14.0 + 64.0));
            if (falloff <= 0.0F) {
                continue;
            }
            float i;
            if (s.phase == StrikeSyncPacket.CHARGING) {
                i = s.ended() ? 0.0F : 0.12F * (float) Math.min(1.0, (now - s.phaseStart) / s.chargeTicks);
            } else {
                double since = s.ticksSinceImpact(now);
                if (since < 0) {
                    i = 0.25F;
                } else if (!s.ended()) {
                    i = since < 40 ? (float) (1.0 - since / 40.0 * 0.7) : 0.3F;
                } else {
                    double age = now - s.endTime;
                    i = (float) Math.max(0.0, 0.3 * (1.0 - age / 40.0));
                }
            }
            double sincePunch = now - s.lastPunch;
            if (sincePunch >= 0.0 && sincePunch < 30.0) {
                i = Math.max(i, s.punchStrength * (float) Math.exp(-sincePunch / 5.0));
            }
            best = Math.max(best, i * falloff);
        }
        return best;
    }
}
