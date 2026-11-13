package com.huuda.orbitallaser.strike;

import com.huuda.orbitallaser.OrbitalLaserMod;
import com.huuda.orbitallaser.config.ServerConfig;
import com.huuda.orbitallaser.network.Network;
import com.huuda.orbitallaser.network.StrikeSyncPacket;
import com.huuda.orbitallaser.registry.ModDamageTypes;
import com.huuda.orbitallaser.registry.ModItems;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side owner of every strike and every cooldown. Stored in the overworld's data folder
 * (data/orbital_laser_strikes.dat), so strikes and cooldowns survive reloads and restarts.
 *
 * <pre>
 * IDLE -> (use item) -> CHARGING -> (charge done) -> STRIKING -> (crater done) -> COOLDOWN -> IDLE
 *              sneak + use / owner dies / owner logs out: CHARGING -> IDLE (cooldown not spent)
 * </pre>
 */
public final class StrikeManager extends SavedData {
    private static final String DATA_NAME = "orbital_laser_strikes";

    /** Ticks the dark core takes to fall from the sky to the target. Matches the client renderer. */
    public static final int CORE_FALL_TICKS = 8;
    /** The beam stays at least this long, even if the crater is finished earlier. */
    private static final int MIN_BEAM_TICKS = 60;
    /** If a chunk refuses to load for this long, its positions are skipped instead of blocking forever. */
    private static final int MAX_STALL_TICKS = 200;

    private final Map<UUID, Strike> strikes = new LinkedHashMap<>();
    /** Player UUID -> overworld game time when the laser is ready again. */
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    public static StrikeManager get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(StrikeManager::load, StrikeManager::new, DATA_NAME);
    }

    // ================================================================= player actions

    /** Item "use" entry point. Handles both fire (use) and cancel (sneak + use). */
    public void handleUse(ServerPlayer player) {
        MinecraftServer server = player.server;
        Strike own = findByOwner(player.getUUID());

        if (player.isShiftKeyDown()) {
            if (own != null && own.state == StrikeState.CHARGING) {
                ServerLevel level = server.getLevel(own.dimension);
                cancel(server, level, own, "cancelled by owner");
                tell(player, Component.translatable("message.orbital_laser.cancelled").withStyle(ChatFormatting.YELLOW));
            } else {
                tell(player, Component.translatable("message.orbital_laser.nothing_to_cancel").withStyle(ChatFormatting.GRAY));
            }
            return;
        }

        if (own != null) {
            tell(player, Component.translatable("message.orbital_laser.already_active").withStyle(ChatFormatting.RED));
            return;
        }

        long remaining = cooldownRemaining(server, player.getUUID());
        if (remaining > 0) {
            tell(player, Component.translatable("message.orbital_laser.cooldown", (remaining + 19) / 20)
                    .withStyle(ChatFormatting.RED));
            return;
        }

        if (ServerConfig.REQUIRE_OP.get() && !player.hasPermissions(ServerConfig.OP_LEVEL.get())) {
            tell(player, Component.translatable("message.orbital_laser.no_permission").withStyle(ChatFormatting.RED));
            return;
        }

        if (strikes.size() >= ServerConfig.MAX_ACTIVE_STRIKES.get()) {
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
        if (!level.hasChunkAt(target)) {
            tell(player, Component.translatable("message.orbital_laser.not_loaded").withStyle(ChatFormatting.RED));
            return;
        }
        if (!level.mayInteract(player, target)) {
            tell(player, Component.translatable("message.orbital_laser.protected").withStyle(ChatFormatting.RED));
            return;
        }

        start(level, player, target);
    }

    private void start(ServerLevel level, ServerPlayer player, BlockPos target) {
        int radius = ServerConfig.RADIUS.get();
        int depth = ServerConfig.DEPTH.get();
        int clearAbove = ServerConfig.CLEAR_ABOVE.get();
        int topY = Math.min(level.getMaxBuildHeight() - 1, target.getY() + clearAbove);
        int bottomY = Math.max(level.getMinBuildHeight(), target.getY() - depth);

        Strike strike = new Strike(
                UUID.randomUUID(),
                player.getUUID(),
                level.dimension(),
                target.immutable(),
                radius,
                depth,
                clearAbove,
                ServerConfig.TERRACE_HEIGHT.get(),
                ServerConfig.CHARGE_SECONDS.get() * 20,
                ServerConfig.IRREGULARITY.get(),
                level.random.nextLong(),
                topY,
                bottomY);

        forceChunks(level, strike);
        strikes.put(strike.id, strike);
        setDirty();

        log(strike, "IDLE -> TARGETING -> CHARGING by " + player.getGameProfile().getName());
        Network.toDimension(level.dimension(), StrikeSyncPacket.of(strike));

        tell(player, Component.translatable("message.orbital_laser.locked",
                target.getX(), target.getY(), target.getZ()).withStyle(ChatFormatting.AQUA));
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 1.0F, 0.6F);
    }

    // ================================================================= tick

    public void tick(MinecraftServer server) {
        if (strikes.isEmpty()) {
            return;
        }
        List<Strike> snapshot = new ArrayList<>(strikes.values());
        for (Strike strike : snapshot) {
            ServerLevel level = server.getLevel(strike.dimension);
            if (level == null) {
                strikes.remove(strike.id);
                OrbitalLaserMod.LOGGER.warn("[orbital_laser] strike {} dropped: dimension {} is gone",
                        strike.id, strike.dimension.location());
                setDirty();
                continue;
            }
            if (strike.state == StrikeState.CHARGING) {
                tickCharging(server, level, strike);
            } else {
                tickStriking(server, level, strike);
            }
        }
    }

    private void tickCharging(MinecraftServer server, ServerLevel level, Strike strike) {
        ServerPlayer owner = server.getPlayerList().getPlayer(strike.owner);
        if (owner == null || !owner.isAlive()) {
            cancel(server, level, strike, owner == null ? "owner offline" : "owner died");
            return;
        }

        strike.ticksInState++;
        setDirty();

        // The glass impacts are played by the clients, in sync with the Sky Hand's punches.
        float progress = Math.min(1.0F, strike.ticksInState / (float) strike.chargeTicks);
        if (strike.ticksInState % 40 == 1) {
            skySound(level, strike, SoundEvents.BEACON_AMBIENT, 1.5F, 0.5F + progress);
        }

        if (strike.ticksInState >= strike.chargeTicks) {
            fire(server, level, strike, owner);
        }
    }

    private void fire(MinecraftServer server, ServerLevel level, Strike strike, @Nullable ServerPlayer owner) {
        strike.state = StrikeState.STRIKING;
        strike.ticksInState = 0;
        strike.progress = 0;
        setDirty();
        log(strike, "CHARGING -> STRIKING (fire)");
        Network.toDimension(level.dimension(), StrikeSyncPacket.of(strike));

        skySound(level, strike, SoundEvents.WARDEN_SONIC_BOOM, 1.6F, 0.5F);
        skySound(level, strike, SoundEvents.LIGHTNING_BOLT_THUNDER, 2.0F, 0.6F);

        if (owner != null && !level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) {
            tell(owner, Component.translatable("message.orbital_laser.mob_griefing_off").withStyle(ChatFormatting.GRAY));
        }
    }

    private void tickStriking(MinecraftServer server, ServerLevel level, Strike strike) {
        strike.ticksInState++;
        setDirty();
        int t = strike.ticksInState;

        if (t == CORE_FALL_TICKS) {
            // Impact: the core hits the ground.
            double x = strike.target.getX() + 0.5;
            double y = strike.target.getY() + 0.5;
            double z = strike.target.getZ() + 0.5;
            level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 16.0F, 0.5F);
            skySound(level, strike, SoundEvents.GENERIC_EXPLODE, 1.5F, 0.4F);
            log(strike, "IMPACT");
        }
        if (t >= CORE_FALL_TICKS && (t - CORE_FALL_TICKS) % 10 == 0 && t <= CORE_FALL_TICKS + 40) {
            damageEntities(server, level, strike);
        }

        boolean griefing = level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING);
        long total = strike.totalPositions();
        if (griefing && t >= CORE_FALL_TICKS && strike.progress < total) {
            excavate(level, strike, total);
            if (t % 15 == 0) {
                level.playSound(null, strike.target.getX() + 0.5, strike.target.getY(), strike.target.getZ() + 0.5,
                        SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 8.0F, 0.6F + level.random.nextFloat() * 0.2F);
            }
        }

        boolean craterDone = !griefing || strike.progress >= total;
        if (craterDone && t >= MIN_BEAM_TICKS) {
            finish(server, level, strike);
        }
    }

    // ================================================================= excavation

    private void excavate(ServerLevel level, Strike strike, long total) {
        int removeBudget = ServerConfig.BLOCKS_PER_TICK.get();
        int examineBudget = removeBudget * 8;
        boolean protectBlockEntities = ServerConfig.PROTECT_BLOCK_ENTITIES.get();
        int flags = ServerConfig.FLUIDS_FLOW_IN.get()
                ? Block.UPDATE_CLIENTS
                : Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        int removed = 0;
        int examined = 0;
        while (strike.progress < total && removed < removeBudget && examined < examineBudget) {
            long index = strike.progress;
            int column = strike.columnIndex(index);
            int y = strike.positionY(index);
            int bottom = strike.columnBottom(column);
            examined++;

            if (bottom == Strike.OUTSIDE || y < bottom) {
                strike.progress++;
                continue;
            }

            pos.set(strike.columnX(column), y, strike.columnZ(column));
            if (!level.hasChunkAt(pos)) {
                // Wait for the force-loaded chunk; skip it only if it never shows up.
                strike.stalledTicks++;
                if (strike.stalledTicks > MAX_STALL_TICKS) {
                    strike.progress++;
                    strike.stalledTicks = 0;
                }
                break;
            }
            strike.stalledTicks = 0;

            BlockState state = level.getBlockState(pos);
            strike.progress++;
            if (state.isAir()) {
                continue;
            }
            // Bedrock, barriers, end portal frames and anything else unbreakable stays.
            if (state.getDestroySpeed(level, pos) < 0) {
                continue;
            }
            if (protectBlockEntities && state.hasBlockEntity()) {
                continue;
            }
            level.setBlock(pos, air, flags);
            removed++;
        }
    }

    private void damageEntities(MinecraftServer server, ServerLevel level, Strike strike) {
        double amount = ServerConfig.DAMAGE.get();
        if (amount <= 0) {
            return;
        }
        int r = strike.radius;
        AABB box = new AABB(
                strike.target.getX() - r, strike.bottomY, strike.target.getZ() - r,
                strike.target.getX() + r + 1, strike.topY + 1, strike.target.getZ() + r + 1);
        ServerPlayer owner = server.getPlayerList().getPlayer(strike.owner);
        DamageSource source = ModDamageTypes.laser(level, owner);
        List<LivingEntity> victims = level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e.isAlive() && strike.insideFootprint(e.getX(), e.getZ()));
        for (LivingEntity e : victims) {
            e.hurt(source, (float) amount);
        }
    }

    // ================================================================= end of a strike

    private void finish(MinecraftServer server, ServerLevel level, Strike strike) {
        strikes.remove(strike.id);
        releaseChunks(level, strike);

        int cooldownTicks = ServerConfig.COOLDOWN_SECONDS.get() * 20;
        if (cooldownTicks > 0) {
            cooldowns.put(strike.owner, server.overworld().getGameTime() + cooldownTicks);
        }
        setDirty();

        log(strike, "STRIKING -> COOLDOWN (" + cooldownTicks / 20 + "s)");
        Network.toDimension(level.dimension(), StrikeSyncPacket.finished(strike, false));
        skySound(level, strike, SoundEvents.BEACON_DEACTIVATE, 1.0F, 0.5F);

        ServerPlayer owner = server.getPlayerList().getPlayer(strike.owner);
        if (owner != null) {
            if (cooldownTicks > 0) {
                owner.getCooldowns().addCooldown(ModItems.ORBITAL_LASER.get(), cooldownTicks);
                owner.getCooldowns().addCooldown(ModItems.SKY_BREAKS.get(), cooldownTicks);
            }
            tell(owner, Component.translatable("message.orbital_laser.complete").withStyle(ChatFormatting.AQUA));
        }
    }

    private void cancel(MinecraftServer server, @Nullable ServerLevel level, Strike strike, String reason) {
        strikes.remove(strike.id);
        if (level != null) {
            releaseChunks(level, strike);
        }
        setDirty();
        log(strike, strike.state + " -> IDLE (" + reason + ", cooldown not spent)");
        Network.toDimension(strike.dimension, StrikeSyncPacket.finished(strike, true));
    }

    /** Owner died: a charge in progress ends safely. A strike already fired keeps going. */
    public void onOwnerDeath(MinecraftServer server, UUID owner) {
        Strike s = findByOwner(owner);
        if (s != null && s.state == StrikeState.CHARGING) {
            cancel(server, server.getLevel(s.dimension), s, "owner died");
        }
    }

    public void onOwnerLogout(MinecraftServer server, UUID owner) {
        Strike s = findByOwner(owner);
        if (s != null && s.state == StrikeState.CHARGING) {
            cancel(server, server.getLevel(s.dimension), s, "owner logged out");
        }
    }

    // ================================================================= sync and helpers

    /** Sends every active strike in the player's dimension (on login, respawn or dimension change). */
    public void syncTo(ServerPlayer player) {
        for (Strike s : strikes.values()) {
            if (s.dimension.equals(player.level().dimension())) {
                Network.toPlayer(player, StrikeSyncPacket.of(s));
            }
        }
    }

    /** Re-applies the visual item cooldown after a relog. */
    public void restoreItemCooldown(ServerPlayer player) {
        long remaining = cooldownRemaining(player.server, player.getUUID());
        if (remaining > 0) {
            player.getCooldowns().addCooldown(ModItems.ORBITAL_LASER.get(), (int) Math.min(Integer.MAX_VALUE, remaining));
            player.getCooldowns().addCooldown(ModItems.SKY_BREAKS.get(), (int) Math.min(Integer.MAX_VALUE, remaining));
        }
    }

    /** Starts a player's cooldown (shared by every laser of this mod). */
    public void startCooldown(MinecraftServer server, UUID player, int ticks) {
        if (ticks > 0) {
            cooldowns.put(player, server.overworld().getGameTime() + ticks);
            setDirty();
        }
    }

    public long cooldownRemaining(MinecraftServer server, UUID player) {
        Long until = cooldowns.get(player);
        if (until == null) {
            return 0;
        }
        long remaining = until - server.overworld().getGameTime();
        if (remaining <= 0) {
            cooldowns.remove(player);
            setDirty();
            return 0;
        }
        return remaining;
    }

    @Nullable
    private Strike findByOwner(UUID owner) {
        for (Strike s : strikes.values()) {
            if (s.owner.equals(owner)) {
                return s;
            }
        }
        return null;
    }

    private static void forceChunks(ServerLevel level, Strike strike) {
        int h = strike.half();
        int minCx = (strike.target.getX() - h) >> 4;
        int maxCx = (strike.target.getX() + h) >> 4;
        int minCz = (strike.target.getZ() - h) >> 4;
        int maxCz = (strike.target.getZ() + h) >> 4;
        LongSet alreadyForced = level.getForcedChunks();
        LongArrayList added = new LongArrayList();
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                long key = ChunkPos.asLong(cx, cz);
                if (!alreadyForced.contains(key)) {
                    level.setChunkForced(cx, cz, true);
                    added.add(key);
                }
            }
        }
        strike.forcedChunks = added.toLongArray();
    }

    private static void releaseChunks(ServerLevel level, Strike strike) {
        for (long key : strike.forcedChunks) {
            level.setChunkForced(ChunkPos.getX(key), ChunkPos.getZ(key), false);
        }
        strike.forcedChunks = new long[0];
    }

    /**
     * Plays a "sky" sound to every player in the dimension within 640 blocks, at the player's own position,
     * louder the closer they are. The sky event is meant to be heard from far away.
     */
    private static void skySound(ServerLevel level, Strike strike, SoundEvent sound, float volume, float pitch) {
        double maxDist = 640.0;
        for (ServerPlayer p : level.players()) {
            double dx = p.getX() - (strike.target.getX() + 0.5);
            double dz = p.getZ() - (strike.target.getZ() + 0.5);
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > maxDist) {
                continue;
            }
            float falloff = (float) Math.max(0.15, 1.0 - dist / maxDist);
            p.playNotifySound(sound, SoundSource.BLOCKS, volume * falloff, pitch);
        }
    }

    private static void tell(ServerPlayer player, Component message) {
        player.displayClientMessage(message, true);
    }

    private static void log(Strike strike, String change) {
        OrbitalLaserMod.LOGGER.info("[orbital_laser] strike {} at {} in {}: {}",
                strike.id.toString().substring(0, 8), strike.target.toShortString(),
                strike.dimension.location(), change);
    }

    // ================================================================= persistence

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Strike s : strikes.values()) {
            list.add(s.save());
        }
        tag.put("strikes", list);

        ListTag cds = new ListTag();
        for (Map.Entry<UUID, Long> e : cooldowns.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putUUID("player", e.getKey());
            c.putLong("until", e.getValue());
            cds.add(c);
        }
        tag.put("cooldowns", cds);
        return tag;
    }

    public static StrikeManager load(CompoundTag tag) {
        StrikeManager m = new StrikeManager();
        ListTag list = tag.getList("strikes", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            Strike s = Strike.load(list.getCompound(i));
            m.strikes.put(s.id, s);
            OrbitalLaserMod.LOGGER.info("[orbital_laser] resuming strike {} at {} ({}, {} / {} positions)",
                    s.id.toString().substring(0, 8), s.target.toShortString(), s.state, s.progress, s.totalPositions());
        }
        ListTag cds = tag.getList("cooldowns", Tag.TAG_COMPOUND);
        for (int i = 0; i < cds.size(); i++) {
            CompoundTag c = cds.getCompound(i);
            m.cooldowns.put(c.getUUID("player"), c.getLong("until"));
        }
        return m;
    }
}
