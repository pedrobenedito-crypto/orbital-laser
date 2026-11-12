package com.huuda.orbitallaser.registry;

import com.huuda.orbitallaser.OrbitalLaserMod;
import com.huuda.orbitallaser.item.OrbitalLaserItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, OrbitalLaserMod.MODID);

    public static final RegistryObject<Item> ORBITAL_LASER = ITEMS.register("orbital_laser",
            () -> new OrbitalLaserItem(new Item.Properties()
                    .stacksTo(1)
                    .rarity(Rarity.EPIC)
                    .fireResistant()));

    private ModItems() {
    }
}
