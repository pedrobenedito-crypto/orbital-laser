package com.huuda.orbitallaser.strike;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.Random;
import java.util.UUID;

/**
 * One strike on one target. The crater shape is fully defined by the values stored here (plus the seed),
 * so excavation can resume exactly where it stopped after a save and reload.
 */
public final class Strike {
    public static final int OUTSIDE = Integer.MAX_VALUE;

    public final UUID id;
    public final UUID owner;
    public final ResourceKey<Level> dimension;
    public final BlockPos target;
    public final int radius;
    public final int depth;
    public final int clearAbove;
    public final int terrace;
    public final int chargeTicks;
    public final double irregularity;
    public final long seed;
    public final int topY;
    public final int bottomY;

    public StrikeState state;
    public int ticksInState;
    /** Index of the next position to process; see {@link #positionX(long)} and friends. */
    public long progress;
    public int stalledTicks;
    /** Chunks this strike force-loaded (and must release). Packed with ChunkPos.asLong. */
    public long[] forcedChunks = new long[0];

    private int[] columnBottomCache;

    public Strike(UUID id, UUID owner, ResourceKey<Level> dimension, BlockPos target,
                  int radius, int depth, int clearAbove, int terrace, int chargeTicks,
                  double irregularity, long seed, int topY, int bottomY) {
        this.id = id;
        this.owner = owner;
        this.dimension = dimension;
        this.target = target;
        this.radius = radius;
        this.depth = depth;
        this.clearAbove = clearAbove;
        this.terrace = Math.max(1, terrace);
        this.chargeTicks = chargeTicks;
        this.irregularity = irregularity;
        this.seed = seed;
        this.topY = topY;
        this.bottomY = bottomY;
        this.state = StrikeState.CHARGING;
    }

    // ---------------------------------------------------------------- geometry

    /** Half-size of the square that contains the crater footprint. */
    public int half() {
        return (int) Math.ceil(radius * (1.0 + irregularity)) + 1;
    }

    public int side() {
        return half() * 2 + 1;
    }

    public long totalPositions() {
        long layers = Math.max(0, topY - bottomY + 1);
        long s = side();
        return layers * s * s;
    }

    public int positionY(long index) {
        long perLayer = (long) side() * side();
        return topY - (int) (index / perLayer);
    }

    public int columnIndex(long index) {
        long perLayer = (long) side() * side();
        return (int) (index % perLayer);
    }

    public int columnX(int column) {
        return target.getX() + column / side() - half();
    }

    public int columnZ(int column) {
        return target.getZ() + column % side() - half();
    }

    /** Lowest Y removed in this column, or {@link #OUTSIDE} if the column is outside the crater. */
    public int columnBottom(int column) {
        if (columnBottomCache == null) {
            columnBottomCache = buildColumns();
        }
        return columnBottomCache[column];
    }

    private int[] buildColumns() {
        Random rnd = new Random(seed);
        double p1 = rnd.nextDouble() * Math.PI * 2.0;
        double p2 = rnd.nextDouble() * Math.PI * 2.0;
        double p3 = rnd.nextDouble() * Math.PI * 2.0;
        int h = half();
        int s = side();
        int[] out = new int[s * s];
        for (int ix = 0; ix < s; ix++) {
            for (int iz = 0; iz < s; iz++) {
                int dx = ix - h;
                int dz = iz - h;
                double d = Math.sqrt(dx * dx + dz * dz);
                double a = Math.atan2(dz, dx);
                double noise = Math.sin(3 * a + p1) * 0.5 + Math.sin(7 * a + p2) * 0.3 + Math.sin(13 * a + p3) * 0.2;
                double edge = radius * (1.0 + irregularity * noise);
                if (d > edge) {
                    out[ix * s + iz] = OUTSIDE;
                    continue;
                }
                double t = d / edge;
                int floorDepth = (int) Math.floor(depth * (1.0 - t * t));
                floorDepth = (floorDepth / terrace) * terrace;
                out[ix * s + iz] = Math.max(bottomY, target.getY() - floorDepth);
            }
        }
        return out;
    }

    /** True if the horizontal position is inside the crater footprint. */
    public boolean insideFootprint(double x, double z) {
        double dx = x - (target.getX() + 0.5);
        double dz = z - (target.getZ() + 0.5);
        return dx * dx + dz * dz <= (radius + 0.5) * (radius + 0.5);
    }

    // ---------------------------------------------------------------- persistence

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putUUID("owner", owner);
        tag.putString("dim", dimension.location().toString());
        tag.putLong("target", target.asLong());
        tag.putInt("radius", radius);
        tag.putInt("depth", depth);
        tag.putInt("clearAbove", clearAbove);
        tag.putInt("terrace", terrace);
        tag.putInt("chargeTicks", chargeTicks);
        tag.putDouble("irregularity", irregularity);
        tag.putLong("seed", seed);
        tag.putInt("topY", topY);
        tag.putInt("bottomY", bottomY);
        tag.putString("state", state.name());
        tag.putInt("ticksInState", ticksInState);
        tag.putLong("progress", progress);
        tag.putLongArray("forced", forcedChunks);
        return tag;
    }

    public static Strike load(CompoundTag tag) {
        Strike s = new Strike(
                tag.getUUID("id"),
                tag.getUUID("owner"),
                ResourceKey.create(Registries.DIMENSION, new ResourceLocation(tag.getString("dim"))),
                BlockPos.of(tag.getLong("target")),
                tag.getInt("radius"),
                tag.getInt("depth"),
                tag.getInt("clearAbove"),
                tag.getInt("terrace"),
                tag.getInt("chargeTicks"),
                tag.getDouble("irregularity"),
                tag.getLong("seed"),
                tag.getInt("topY"),
                tag.getInt("bottomY"));
        s.state = StrikeState.byName(tag.getString("state"));
        s.ticksInState = tag.getInt("ticksInState");
        s.progress = tag.getLong("progress");
        s.forcedChunks = tag.getLongArray("forced");
        return s;
    }
}
