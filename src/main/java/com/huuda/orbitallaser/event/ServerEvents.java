package com.huuda.orbitallaser.event;

import com.huuda.orbitallaser.OrbitalLaserMod;
import com.huuda.orbitallaser.skybreak.SkyBreakManager;
import com.huuda.orbitallaser.strike.StrikeManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

@Mod.EventBusSubscriber(modid = OrbitalLaserMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ServerEvents {

    private ServerEvents() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        StrikeManager.get(server).tick(server);
        SkyBreakManager.get(server).tick(server);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            StrikeManager manager = StrikeManager.get(player.server);
            manager.syncTo(player);
            manager.restoreItemCooldown(player);
            SkyBreakManager.get(player.server).syncTo(player);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            StrikeManager.get(player.server).onOwnerLogout(player.server, player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            StrikeManager.get(player.server).syncTo(player);
            SkyBreakManager.get(player.server).syncTo(player);
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            StrikeManager.get(player.server).syncTo(player);
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            StrikeManager.get(player.server).onOwnerDeath(player.server, player.getUUID());
        }
    }
}
