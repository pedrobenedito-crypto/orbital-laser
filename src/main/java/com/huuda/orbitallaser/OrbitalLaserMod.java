package com.huuda.orbitallaser;

import com.huuda.orbitallaser.config.ClientConfig;
import com.huuda.orbitallaser.config.ServerConfig;
import com.huuda.orbitallaser.network.Network;
import com.huuda.orbitallaser.registry.ModItems;
import com.huuda.orbitallaser.registry.ModSounds;
import com.mojang.logging.LogUtils;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(OrbitalLaserMod.MODID)
public class OrbitalLaserMod {
    public static final String MODID = "orbital_laser";
    public static final Logger LOGGER = LogUtils.getLogger();

    public OrbitalLaserMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModItems.ITEMS.register(modBus);
        ModSounds.SOUNDS.register(modBus);
        modBus.addListener(this::commonSetup);
        modBus.addListener(this::addCreative);

        // SERVER config lives in <world>/serverconfig and is synced to clients, so it is per server.
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, ServerConfig.SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(Network::register);
    }

    private void addCreative(final BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.COMBAT) {
            event.accept(ModItems.ORBITAL_LASER);
            event.accept(ModItems.SKY_BREAKS);
        }
    }
}
