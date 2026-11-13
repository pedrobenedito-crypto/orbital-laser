package com.huuda.orbitallaser.registry;

import com.huuda.orbitallaser.OrbitalLaserMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.HashMap;
import java.util.Map;

/**
 * The Sky Breaks' sounds, from Stink-O's Orbital Laser (MIT; synthesized, plus CC0 glass recordings: see
 * NOTICE-STINK-O.txt). Keys match assets/orbital_laser/sounds.json.
 */
public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, OrbitalLaserMod.MODID);

    private static final String[] SKYBREAK = {
            "collapse", "crack", "descend", "fall", "flare", "gaze", "ground", "heal", "impact", "impact2", "impact3",
            "impact_far", "pressure", "release", "release2", "ripple", "rise", "rumble", "shudder", "sink", "slam",
            "strain", "tear", "tear_far", "void", "withdraw"};
    private static final String[] PAYLOAD = {"ground_alt", "blast_alt", "blast_alt_far", "rush", "tail"};

    private static final Map<String, RegistryObject<SoundEvent>> BY_NAME = new HashMap<>();

    static {
        for (String n : SKYBREAK) {
            add("skybreak." + n);
        }
        for (String n : PAYLOAD) {
            add("payload." + n);
        }
    }

    private ModSounds() {
    }

    private static void add(String key) {
        BY_NAME.put(key, SOUNDS.register(key,
                () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(OrbitalLaserMod.MODID, key))));
    }

    /** "skybreak.crack", "payload.rush"... or null if unknown. */
    public static SoundEvent get(String key) {
        RegistryObject<SoundEvent> r = BY_NAME.get(key);
        return r != null && r.isPresent() ? r.get() : null;
    }
}
