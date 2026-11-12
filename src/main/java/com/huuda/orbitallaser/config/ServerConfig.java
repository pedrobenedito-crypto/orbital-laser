package com.huuda.orbitallaser.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Per-server settings, stored in saves/&lt;world&gt;/serverconfig/orbital_laser-server.toml.
 * Radius and batch size are capped here so a server cannot be configured into a freeze.
 */
public final class ServerConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue REQUIRE_OP;
    public static final ForgeConfigSpec.IntValue OP_LEVEL;
    public static final ForgeConfigSpec.IntValue MAX_RANGE;
    public static final ForgeConfigSpec.IntValue MAX_ACTIVE_STRIKES;

    public static final ForgeConfigSpec.IntValue RADIUS;
    public static final ForgeConfigSpec.IntValue DEPTH;
    public static final ForgeConfigSpec.IntValue CLEAR_ABOVE;
    public static final ForgeConfigSpec.DoubleValue IRREGULARITY;
    public static final ForgeConfigSpec.IntValue TERRACE_HEIGHT;

    public static final ForgeConfigSpec.IntValue CHARGE_SECONDS;
    public static final ForgeConfigSpec.IntValue COOLDOWN_SECONDS;
    public static final ForgeConfigSpec.IntValue BLOCKS_PER_TICK;

    public static final ForgeConfigSpec.DoubleValue DAMAGE;
    public static final ForgeConfigSpec.BooleanValue PROTECT_BLOCK_ENTITIES;
    public static final ForgeConfigSpec.BooleanValue FLUIDS_FLOW_IN;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.comment("Who can fire the orbital laser").push("permissions");
        REQUIRE_OP = b.comment("If true, only operators can fire the laser.")
                .define("requireOp", false);
        OP_LEVEL = b.comment("Permission level required when requireOp is true (2 = default operator).")
                .defineInRange("opLevel", 2, 0, 4);
        MAX_RANGE = b.comment("Maximum distance, in blocks, between the player and the target block.")
                .defineInRange("maxRange", 256, 16, 1024);
        MAX_ACTIVE_STRIKES = b.comment("Maximum number of strikes running at the same time on the server.")
                .defineInRange("maxActiveStrikes", 4, 1, 32);
        b.pop();

        b.comment("Crater shape").push("crater");
        RADIUS = b.comment("Crater radius in blocks.")
                .defineInRange("radius", 24, 4, 96);
        DEPTH = b.comment("Crater depth below the target block, at the center.")
                .defineInRange("depth", 32, 4, 128);
        CLEAR_ABOVE = b.comment("How many blocks above the target are also vaporized (hills, trees).")
                .defineInRange("clearAbove", 24, 0, 128);
        IRREGULARITY = b.comment("How irregular the rim is. 0 = perfect circle, 0.5 = very jagged.")
                .defineInRange("irregularity", 0.12, 0.0, 0.5);
        TERRACE_HEIGHT = b.comment("Height of each layer of the pit walls. 1 = smooth bowl.")
                .defineInRange("terraceHeight", 4, 1, 16);
        b.pop();

        b.comment("Timing and performance").push("timing");
        CHARGE_SECONDS = b.comment("Seconds of charge (sky cracking) before the laser fires.")
                .defineInRange("chargeSeconds", 5, 1, 60);
        COOLDOWN_SECONDS = b.comment("Cooldown, per player, after a strike completes. Cancelled strikes do not spend it.")
                .defineInRange("cooldownSeconds", 60, 0, 3600);
        BLOCKS_PER_TICK = b.comment("Maximum blocks removed per server tick. Lower = smoother server, slower crater.")
                .defineInRange("blocksPerTick", 2048, 64, 20000);
        b.pop();

        b.comment("Safety").push("safety");
        DAMAGE = b.comment("Damage dealt to living entities inside the crater area at impact.")
                .defineInRange("damage", 40.0, 0.0, 1000.0);
        PROTECT_BLOCK_ENTITIES = b.comment("If true, chests, furnaces and other block entities are not destroyed.")
                .define("protectBlockEntities", true);
        FLUIDS_FLOW_IN = b.comment("If true, nearby water and lava flow into the crater (neighbor updates on).")
                .define("fluidsFlowIn", true);
        b.pop();

        SPEC = b.build();
    }

    private ServerConfig() {
    }

    /** Reads a config value but never throws if the config is not loaded yet (e.g. on a menu screen). */
    public static int radiusOrDefault() {
        try {
            return RADIUS.get();
        } catch (IllegalStateException e) {
            return 24;
        }
    }

    public static int rangeOrDefault() {
        try {
            return MAX_RANGE.get();
        } catch (IllegalStateException e) {
            return 256;
        }
    }
}
