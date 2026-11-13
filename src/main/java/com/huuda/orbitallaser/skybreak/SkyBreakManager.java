package com.huuda.orbitallaser.skybreak;

import com.huuda.orbitallaser.OrbitalLaserMod;
import com.huuda.orbitallaser.config.ServerConfig;
import com.huuda.orbitallaser.network.Network;
import com.huuda.orbitallaser.network.SkyBreakPackets;
import com.huuda.orbitallaser.registry.ModItems;
import com.huuda.orbitallaser.strike.StrikeManager;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server side of the Sky Breaks: starts strikes, runs their world changes on the original's timeline and saves them
 * (data/orbital_laser_skybreak.dat in the overworld). The pictures and sounds are the clients' (SkyBreakClient).
 * <p>
 * World changes: at 0.55 s a disc of land round the target is torn out (and drawn rising by the clients); each pane
 * lodges in the land as ice and obsidian where it lands; at 37.5 s the fist's crater is dug. Mobs near a pane's
 * landing and within 125 blocks of the fist die; players are spared. All terrain edits obey the mobGriefing rule.
 */
public final class SkyBreakManager extends SavedData {
    private static final String DATA_NAME = "orbital_laser_skybreak";
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /** One strike. */
    static final class Strike {
        UUID id;
        UUID owner;
        ResourceKey<Level> dim;
        BlockPos target;
        int groundY;
        int seed;
        double[] me;
        long start;
        boolean terrain;
        boolean lifted;
        int[] liftBlocks = new int[0];
        /** Bits: pane i lodged (i), pane i's mobs killed (8 + i), fist's mobs killed (16), crater started (17). */
        int done;
        /** Per job: columns finished (pane i: i, crater: 7). */
        int[] progress = new int[8];
        long[] forced = new long[0];

        transient SkyBreakPlan plan;
        transient List<long[]> craterColumns;

        SkyBreakPlan plan() {
            if (plan == null) {
                plan = new SkyBreakPlan(seed, me);
            }
            return plan;
        }

        double t(long gameTime) {
            return (gameTime - start) / 20.0;
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("id", id);
            t.putUUID("owner", owner);
            t.putString("dim", dim.location().toString());
            t.putLong("target", target.asLong());
            t.putInt("groundY", groundY);
            t.putInt("seed", seed);
            t.putDouble("meX", me[0]);
            t.putDouble("meY", me[1]);
            t.putDouble("meZ", me[2]);
            t.putLong("start", start);
            t.putBoolean("terrain", terrain);
            t.putBoolean("lifted", lifted);
            t.putIntArray("lift", liftBlocks);
            t.putInt("done", done);
            t.putIntArray("progress", progress);
            t.putLongArray("forced", forced);
            return t;
        }

        static Strike load(CompoundTag t) {
            Strike s = new Strike();
            s.id = t.getUUID("id");
            s.owner = t.getUUID("owner");
            s.dim = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(t.getString("dim")));
            s.target = BlockPos.of(t.getLong("target"));
            s.groundY = t.getInt("groundY");
            s.seed = t.getInt("seed");
            s.me = new double[]{t.getDouble("meX"), t.getDouble("meY"), t.getDouble("meZ")};
            s.start = t.getLong("start");
            s.terrain = t.getBoolean("terrain");
            s.lifted = t.getBoolean("lifted");
            s.liftBlocks = t.getIntArray("lift");
            s.done = t.getInt("done");
            int[] p = t.getIntArray("progress");
            s.progress = p.length == 8 ? p : new int[8];
            s.forced = t.getLongArray("forced");
            return s;
        }
    }

    private final Map<UUID, Strike> strikes = new LinkedHashMap<>();

    public static SkyBreakManager get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(SkyBreakManager::load, SkyBreakManager::new, DATA_NAME);
    }

    // ================================================================= firing

    public void handleUse(ServerPlayer player) {
        MinecraftServer server = player.server;
        for (Strike s : strikes.values()) {
            if (s.owner.equals(player.getUUID())) {
                tell(player, Component.translatable("message.orbital_laser.already_active").withStyle(ChatFormatting.RED));
                return;
            }
        }
        StrikeManager laser = StrikeManager.get(server);
        long remaining = laser.cooldownRemaining(server, player.getUUID());
        if (remaining > 0) {
            tell(player, Component.translatable("message.orbital_laser.cooldown", (remaining + 19) / 20).withStyle(ChatFormatting.RED));
            return;
        }
        if (ServerConfig.REQUIRE_OP.get() && !player.hasPermissions(ServerConfig.OP_LEVEL.get())) {
            tell(player, Component.translatable("message.orbital_laser.no_permission").withStyle(ChatFormatting.RED));
            return;
        }
        if (strikes.size() >= Math.max(1, ServerConfig.MAX_ACTIVE_STRIKES.get() / 2)) {
            tell(player, Component.translatable("message.orbital_laser.too_many").withStyle(ChatFormatting.RED));
            return;
        }
        int range = ServerConfig.MAX_RANGE.get();
        HitResult hit = player.pick(range, 1.0F, false);
        if (hit.getType() != HitResult.Type.BLOCK || !(hit instanceof BlockHitResult blockHit)) {
            tell(player, Component.translatable("message.orbital_laser.no_target", range).withStyle(ChatFormatting.RED));
            return;
        }
        ServerLevel level = player.serverLevel();
        BlockPos target = blockHit.getBlockPos();
        if (!level.hasChunkAt(target) || !level.mayInteract(player, target)) {
            tell(player, Component.translatable("message.orbital_laser.protected").withStyle(ChatFormatting.RED));
            return;
        }

        Strike s = new Strike();
        s.id = UUID.randomUUID();
        s.owner = player.getUUID();
        s.dim = level.dimension();
        s.target = target.immutable();
        s.groundY = target.getY() + 1;
        s.seed = level.random.nextInt();
        s.me = new double[]{player.getX() - (target.getX() + 0.5), player.getY() - s.groundY, player.getZ() - (target.getZ() + 0.5)};
        s.start = level.getGameTime();
        s.terrain = level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING);
        strikes.put(s.id, s);
        setDirty();
        log(s, "fired by " + player.getGameProfile().getName());
        Network.toDimension(s.dim, startPacket(s, level.getGameTime()));
        tell(player, Component.translatable("message.orbital_laser.skybreak_fired").withStyle(ChatFormatting.AQUA));
        if (!s.terrain) {
            tell(player, Component.translatable("message.orbital_laser.mob_griefing_off").withStyle(ChatFormatting.GRAY));
        }
    }

    private static SkyBreakPackets.Start startPacket(Strike s, long now) {
        return new SkyBreakPackets.Start(s.id, s.target, s.groundY, s.seed, (float) s.me[0], (float) s.me[1], (float) s.me[2],
                (int) Math.max(0, now - s.start), s.terrain);
    }

    public void syncTo(ServerPlayer player) {
        long now = player.serverLevel().getGameTime();
        for (Strike s : strikes.values()) {
            if (s.dim.equals(player.level().dimension())) {
                Network.toPlayer(player, startPacket(s, now));
                if (s.liftBlocks.length > 0) {
                    Network.toPlayer(player, new SkyBreakPackets.Lift(s.id, s.liftBlocks));
                }
            }
        }
    }

    // ================================================================= tick

    public void tick(MinecraftServer server) {
        if (strikes.isEmpty()) {
            return;
        }
        for (Strike s : new ArrayList<>(strikes.values())) {
            ServerLevel level = server.getLevel(s.dim);
            if (level == null) {
                strikes.remove(s.id);
                setDirty();
                continue;
            }
            tickStrike(server, level, s);
        }
    }

    private void tickStrike(MinecraftServer server, ServerLevel level, Strike s) {
        double t = s.t(level.getGameTime());
        SkyBreakPlan plan = s.plan();
        double cx = s.target.getX() + 0.5;
        double cz = s.target.getZ() + 0.5;
        int budget = ServerConfig.BLOCKS_PER_TICK.get();

        // ---- the lift: the disc of land torn out
        if (!s.lifted && t >= SbTime.LF_CUT) {
            s.lifted = true;
            s.liftBlocks = tearDisc(level, s);
            if (s.liftBlocks.length > 0) {
                Network.toDimension(s.dim, new SkyBreakPackets.Lift(s.id, s.liftBlocks));
            }
            setDirty();
        }

        // ---- the panes
        for (SkyBreakPlan.Pane p : plan.panes) {
            int i = p.index;
            if (t >= p.lands() && (s.done & (1 << (8 + i))) == 0) {
                s.done |= 1 << (8 + i);
                killNear(level, cx + p.to[0], s.groundY, cz + p.to[1], SbTime.KILL_PANE);
                setDirty();
            }
            if (s.terrain && t >= p.lands() + SbTime.SB_SETTLE && (s.done & (1 << i)) == 0 && budget > 0) {
                budget -= lodgePane(level, s, p, budget);
                setDirty();
            }
        }

        // ---- the fist
        if (t >= SbTime.HD_PRESS && (s.done & (1 << 16)) == 0) {
            s.done |= 1 << 16;
            killNear(level, cx, s.groundY, cz, SbTime.KILL_SLAM);
            log(s, "the fist lands");
            setDirty();
        }
        if (s.terrain && t >= SbTime.HD_PRESS && (s.done & (1 << 17)) == 0 && budget > 0) {
            budget -= digCrater(level, s, budget);
            setDirty();
        }

        // ---- the end: once the strike's time is up and the land's work is done
        boolean landDone = !s.terrain || ((s.done & 0x7F) == (1 << plan.panes.size()) - 1 && (s.done & (1 << 17)) != 0);
        if (t >= SbTime.SB_LEN && landDone) {
            finish(server, level, s);
        } else if (t >= SbTime.SB_LEN + 120.0) {
            log(s, "gave up on unfinished land edits");
            finish(server, level, s);
        }
    }

    private void finish(MinecraftServer server, ServerLevel level, Strike s) {
        strikes.remove(s.id);
        releaseChunks(level, s);
        setDirty();
        Network.toDimension(s.dim, new SkyBreakPackets.End(s.id));
        int cooldown = ServerConfig.COOLDOWN_SECONDS.get() * 20;
        StrikeManager.get(server).startCooldown(server, s.owner, cooldown);
        ServerPlayer owner = server.getPlayerList().getPlayer(s.owner);
        if (owner != null && cooldown > 0) {
            owner.getCooldowns().addCooldown(ModItems.SKY_BREAKS.get(), cooldown);
            owner.getCooldowns().addCooldown(ModItems.ORBITAL_LASER.get(), cooldown);
        }
        log(s, "over");
    }

    // ================================================================= the lift

    /** Takes the disc of land out of the world and returns it (dx, dy, dz, state id) from the target's ground. */
    private int[] tearDisc(ServerLevel level, Strike s) {
        if (!s.terrain) {
            return new int[0];
        }
        IntArrayList out = new IntArrayList();
        int tx = s.target.getX();
        int tz = s.target.getZ();
        int r = (int) Math.ceil(SbTime.LF_R * 1.2 + 3.5);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double ang = Math.atan2(dz, dx);
                double edge = SbTime.LF_R * (0.85 + 0.3 * SbMath.noise(2.0 * Math.cos(ang) + 3.0, 2.0 * Math.sin(ang), s.seed ^ 0x4c1));
                double d = Math.hypot(dx, dz);
                boolean inDisc = d <= edge;
                boolean inCanopy = d <= edge + 3.5;
                if (!inCanopy) {
                    continue;
                }
                int x = tx + dx;
                int z = tz + dz;
                if (!level.hasChunkAt(pos.set(x, s.groundY, z))) {
                    continue;
                }
                int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                int ground = groundBelow(level, x, top, z);
                int bottom;
                if (inDisc) {
                    double u = d / Math.max(edge, 1e-3);
                    double deep = SbTime.LF_SHALLOW + (SbTime.LF_DEEP - SbTime.LF_SHALLOW) * (1.0 - u * u);
                    deep += 1.5 * (SbMath.noise(x * 0.4, z * 0.4, s.seed) - 0.5);
                    bottom = Math.max(level.getMinBuildHeight() + 1, ground - (int) Math.round(deep));
                } else {
                    // (past the disc only the trees' leaves go with it)
                    bottom = ground + 1;
                }
                for (int y = top; y >= bottom; y--) {
                    pos.set(x, y, z);
                    BlockState st = level.getBlockState(pos);
                    if (st.isAir() || st.getDestroySpeed(level, pos) < 0 || st.hasBlockEntity()) {
                        continue;
                    }
                    if (!inDisc && !st.is(BlockTags.LEAVES)) {
                        continue;
                    }
                    out.add(dx);
                    out.add(y - s.groundY);
                    out.add(dz);
                    out.add(Block.getId(st));
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
                }
            }
        }
        log(s, "tore out " + out.size() / 4 + " blocks");
        return out.toIntArray();
    }

    /** The first block under `top` that is ground: not leaves, logs, plants or air. */
    private static int groundBelow(ServerLevel level, int x, int top, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, top, z);
        int min = Math.max(level.getMinBuildHeight(), top - 48);
        for (int y = top; y > min; y--) {
            pos.setY(y);
            BlockState st = level.getBlockState(pos);
            if (st.isAir() || st.is(BlockTags.LEAVES) || st.is(BlockTags.LOGS) || st.canBeReplaced() || !st.getFluidState().isEmpty()
                    && !st.blocksMotion()) {
                continue;
            }
            return y;
        }
        return top;
    }

    // ================================================================= the panes

    /** Lodges pane p in the land; returns the blocks spent. */
    private int lodgePane(ServerLevel level, Strike s, SkyBreakPlan.Pane p, int budget) {
        int i = p.index;
        double px = s.target.getX() + 0.5 + p.to[0];
        double pz = s.target.getZ() + 0.5 + p.to[1];
        int r = (int) Math.ceil(p.size()) + 1;
        int side = 2 * r + 1;
        int total = side * side;
        if (s.progress[i] == 0) {
            forceArea(level, s, (int) px - r, (int) pz - r, (int) px + r, (int) pz + r);
        }
        int cy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(px), (int) Math.floor(pz));
        double tdx = Math.cos(p.tiltDir);
        double tdz = Math.sin(p.tiltDir);
        double slope = Math.tan(p.tilt);
        BlockState top = Blocks.PACKED_ICE.defaultBlockState();
        BlockState mid = Blocks.BLUE_ICE.defaultBlockState();
        BlockState under = Blocks.OBSIDIAN.defaultBlockState();
        BlockState snow = Blocks.SNOW_BLOCK.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int spent = 0;
        while (s.progress[i] < total && spent < budget) {
            int idx = s.progress[i];
            int dx = idx / side - r;
            int dz = idx % side - r;
            int x = (int) Math.floor(px) + dx;
            int z = (int) Math.floor(pz) + dz;
            if (!level.hasChunkAt(pos.set(x, cy, z))) {
                break;
            }
            s.progress[i]++;
            if (!p.covers(dx, dz)) {
                continue;
            }
            // (lying low, a third of it buried: its surface a tilted plane through the land at its middle)
            int surf = cy - 1 + (int) Math.round((dx * tdx + dz * tdz) * slope);
            double n = SbMath.noise(x * 0.3, z * 0.3, s.seed ^ 0x9a);
            for (int k = 0; k < 3; k++) {
                int y = surf - k;
                pos.set(x, y, z);
                BlockState cur = level.getBlockState(pos);
                if (cur.getDestroySpeed(level, pos) < 0 || cur.hasBlockEntity()) {
                    continue;
                }
                BlockState put = k == 0 ? (n > 0.72 ? snow : top) : k == 1 ? mid : under;
                level.setBlock(pos, put, FLAGS);
                spent++;
            }
            // (and clear what stood on it)
            for (int y = surf + 1; y <= surf + 12; y++) {
                pos.set(x, y, z);
                BlockState cur = level.getBlockState(pos);
                if (cur.isAir()) {
                    continue;
                }
                if (cur.getDestroySpeed(level, pos) < 0 || cur.hasBlockEntity()) {
                    continue;
                }
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
                spent++;
            }
        }
        if (s.progress[i] >= total) {
            s.done |= 1 << i;
            log(s, "pane " + i + " lodged");
        }
        return spent;
    }

    // ================================================================= the crater

    private int digCrater(ServerLevel level, Strike s, int budget) {
        SkyBreakPlan plan = s.plan();
        CraterPrint print = new CraterPrint(plan.dir, plan.side, s.seed);
        int reach = (int) Math.ceil(CraterPrint.reach());
        int tx = s.target.getX();
        int tz = s.target.getZ();
        if (s.craterColumns == null) {
            // (the middle first, then out from it)
            List<long[]> cols = new ArrayList<>();
            for (int dx = -reach; dx <= reach; dx++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    if (dx * dx + dz * dz <= reach * reach) {
                        cols.add(new long[]{(long) dx * dx + (long) dz * dz, dx, dz});
                    }
                }
            }
            cols.sort((a, b) -> Long.compare(a[0], b[0]));
            s.craterColumns = cols;
        }
        if (s.progress[7] == 0) {
            forceArea(level, s, tx - reach, tz - reach, tx + reach, tz + reach);
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int spent = 0;
        List<long[]> cols = s.craterColumns;
        while (s.progress[7] < cols.size() && spent < budget) {
            long[] c = cols.get(s.progress[7]);
            int dx = (int) c[1];
            int dz = (int) c[2];
            int x = tx + dx;
            int z = tz + dz;
            if (!level.hasChunkAt(pos.set(x, s.groundY, z))) {
                break;
            }
            s.progress[7]++;
            double n = SbMath.noise(x * 0.21, z * 0.21, s.seed ^ 0x77);
            double n2 = SbMath.noise(x * 0.57 + 13.0, z * 0.57, s.seed ^ 0x78);
            CraterPrint.Cut cut = print.dig(dx + 0.5, dz + 0.5, n);
            if (cut.part == CraterPrint.Part.OUT) {
                continue;
            }
            int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
            int ground = groundBelow(level, x, top, z);
            // (everything that stood on the land is swept off)
            for (int y = top; y > ground; y--) {
                pos.set(x, y, z);
                BlockState st = level.getBlockState(pos);
                if (!st.isAir() && st.getDestroySpeed(level, pos) >= 0 && !st.hasBlockEntity()) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
                    spent++;
                }
            }
            if (cut.part == CraterPrint.Part.BAND) {
                continue;
            }
            int floor = ground - (int) Math.round(cut.down);
            floor = Math.max(level.getMinBuildHeight() + 1, floor);
            for (int y = ground; y > floor; y--) {
                pos.set(x, y, z);
                BlockState st = level.getBlockState(pos);
                if (!st.isAir() && st.getDestroySpeed(level, pos) >= 0 && !st.hasBlockEntity()) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
                    spent++;
                }
            }
            BlockState fb = block(CraterPrint.floorBlock(cut.part, n, n2, cut.fd, cut.r));
            for (int k = 0; k < 2; k++) {
                pos.set(x, floor - k, z);
                BlockState st = level.getBlockState(pos);
                if (st.getDestroySpeed(level, pos) >= 0 && !st.hasBlockEntity()) {
                    level.setBlock(pos, fb, FLAGS);
                    spent++;
                }
            }
            // (the earth it pushed out, heaped round it)
            int heap = (int) Math.round(cut.up);
            for (int k = 1; k <= heap; k++) {
                pos.set(x, floor + k, z);
                if (level.getBlockState(pos).isAir()) {
                    level.setBlock(pos, k == heap && n2 > 0.4 ? Blocks.COARSE_DIRT.defaultBlockState()
                            : n > 0.6 ? Blocks.GRAVEL.defaultBlockState() : Blocks.DIRT.defaultBlockState(), FLAGS);
                    spent++;
                }
            }
        }
        if (s.progress[7] >= cols.size()) {
            s.done |= 1 << 17;
            log(s, "crater dug");
        }
        return spent;
    }

    private static BlockState block(String id) {
        Block b = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(id));
        return b == null ? Blocks.STONE.defaultBlockState() : b.defaultBlockState();
    }

    // ================================================================= mobs, chunks, helpers

    private static void killNear(ServerLevel level, double x, double y, double z, double r) {
        AABB box = new AABB(x - r, y - 64, z - r, x + r, y + 600, z + r);
        for (Entity e : level.getEntities((Entity) null, box, e -> !(e instanceof Player))) {
            double dx = e.getX() - x;
            double dz = e.getZ() - z;
            if (dx * dx + dz * dz > r * r) {
                continue;
            }
            if (e instanceof LivingEntity living) {
                living.kill();
            } else if (e instanceof ItemEntity) {
                e.discard();
            }
        }
    }

    private void forceArea(ServerLevel level, Strike s, int x0, int z0, int x1, int z1) {
        LongSet already = level.getForcedChunks();
        LongArrayList added = new LongArrayList(s.forced);
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                long key = ChunkPos.asLong(cx, cz);
                if (!already.contains(key) && !added.contains(key)) {
                    level.setChunkForced(cx, cz, true);
                    added.add(key);
                }
            }
        }
        s.forced = added.toLongArray();
    }

    private static void releaseChunks(ServerLevel level, Strike s) {
        for (long key : s.forced) {
            level.setChunkForced(ChunkPos.getX(key), ChunkPos.getZ(key), false);
        }
        s.forced = new long[0];
    }

    private static void tell(ServerPlayer player, Component message) {
        player.displayClientMessage(message, true);
    }

    private static void log(Strike s, String what) {
        OrbitalLaserMod.LOGGER.info("[orbital_laser] sky breaks {} at {}: {}", s.id.toString().substring(0, 8),
                s.target.toShortString(), what);
    }

    // ================================================================= persistence

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Strike s : strikes.values()) {
            list.add(s.save());
        }
        tag.put("strikes", list);
        return tag;
    }

    public static SkyBreakManager load(CompoundTag tag) {
        SkyBreakManager m = new SkyBreakManager();
        ListTag list = tag.getList("strikes", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            Strike s = Strike.load(list.getCompound(i));
            m.strikes.put(s.id, s);
        }
        return m;
    }
}
