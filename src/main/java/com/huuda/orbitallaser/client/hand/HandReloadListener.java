package com.huuda.orbitallaser.client.hand;

import com.huuda.orbitallaser.OrbitalLaserMod;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Drops the cached Sky Hand mesh when resources reload (F3+T or a resource pack change). */
@Mod.EventBusSubscriber(modid = OrbitalLaserMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class HandReloadListener {
    private HandReloadListener() {
    }

    @SubscribeEvent
    public static void register(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) resourceManager -> {
            HandModel.invalidate();
            com.huuda.orbitallaser.client.skybreak.ArmMesh.invalidate();
        });
    }
}
