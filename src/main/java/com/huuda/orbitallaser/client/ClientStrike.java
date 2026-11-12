package com.huuda.orbitallaser.client;

import com.huuda.orbitallaser.strike.StrikeManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.UUID;

/** What the client knows about one strike: enough to draw it from the level's game time. */
public final class ClientStrike {
    /** Height of the cracked sky above the target. */
    public static final int SKY_HEIGHT = 150;
    public static final int AFTERMATH_TICKS = 220;
    public static final int CANCEL_FADE_TICKS = 20;

    public final UUID id;
    public final ClientLevel level;
    public BlockPos target;
    public byte phase;
    /** Game time at which the current phase started. */
    public long phaseStart;
    public int chargeTicks;
    public int radius;
    public int depth;
    public long seed;
    /** Game time at which the strike ended or was cancelled, -1 while active. */
    public long endTime = -1;
    public boolean cancelled;
    /** True once the impact burst particles were spawned for this strike. */
    public boolean impactSpawned;

    private List<CrackGeometry.Segment> cracks;

    public ClientStrike(UUID id, ClientLevel level) {
        this.id = id;
        this.level = level;
    }

    public double centerX() {
        return target.getX() + 0.5;
    }

    public double centerZ() {
        return target.getZ() + 0.5;
    }

    public double skyY() {
        return Math.min(target.getY() + SKY_HEIGHT, level.getMaxBuildHeight() + 120);
    }

    /** Overall size of the crack pattern, in blocks. */
    public float crackSize() {
        return Math.max(48.0F, radius * 6.0F);
    }

    public List<CrackGeometry.Segment> cracks() {
        if (cracks == null) {
            cracks = CrackGeometry.build(seed, crackSize());
        }
        return cracks;
    }

    public boolean ended() {
        return endTime >= 0;
    }

    /** Ticks since the core hit the ground; negative while it is still falling or before firing. */
    public double ticksSinceImpact(double now) {
        if (phase == com.huuda.orbitallaser.network.StrikeSyncPacket.CHARGING) {
            return -1;
        }
        return now - phaseStart - StrikeManager.CORE_FALL_TICKS;
    }

    public boolean expired(long gameTime) {
        if (!ended()) {
            return false;
        }
        long age = gameTime - endTime;
        return cancelled ? age > CANCEL_FADE_TICKS : age > AFTERMATH_TICKS;
    }
}
