package com.huuda.orbitallaser.config;

import net.minecraftforge.common.ForgeConfigSpec;

/** Per-player visual settings, stored in config/orbital_laser-client.toml. */
public final class ClientConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue RENDER_EFFECTS;
    public static final ForgeConfigSpec.BooleanValue PARTICLES;
    public static final ForgeConfigSpec.BooleanValue SCREEN_SHAKE;
    public static final ForgeConfigSpec.DoubleValue SHAKE_STRENGTH;
    public static final ForgeConfigSpec.BooleanValue TARGET_PREVIEW;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("effects");
        RENDER_EFFECTS = b.comment("Render the sky crack, dark core and beam.")
                .define("renderEffects", true);
        PARTICLES = b.comment("Spawn the dust column and debris particles.")
                .define("particles", true);
        SCREEN_SHAKE = b.comment("Shake the camera near a strike.")
                .define("screenShake", true);
        SHAKE_STRENGTH = b.comment("Screen shake multiplier.")
                .defineInRange("shakeStrength", 1.0, 0.0, 3.0);
        TARGET_PREVIEW = b.comment("Show the crater outline on the aimed block while holding the item.")
                .define("targetPreview", true);
        b.pop();
        SPEC = b.build();
    }

    private ClientConfig() {
    }
}
