package com.huuda.orbitallaser.registry;

import com.huuda.orbitallaser.OrbitalLaserMod;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import javax.annotation.Nullable;
import java.util.Optional;

/** Data-driven damage type, defined in data/orbital_laser/damage_type/orbital_laser.json. */
public final class ModDamageTypes {
    public static final ResourceKey<DamageType> ORBITAL_LASER =
            ResourceKey.create(Registries.DAMAGE_TYPE, new ResourceLocation(OrbitalLaserMod.MODID, "orbital_laser"));

    private ModDamageTypes() {
    }

    public static DamageSource laser(ServerLevel level, @Nullable Entity owner) {
        Optional<Holder.Reference<DamageType>> holder = level.registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolder(ORBITAL_LASER);
        if (holder.isPresent()) {
            return new DamageSource(holder.get(), null, owner);
        }
        return level.damageSources().magic();
    }
}
