package com.huuda.orbitallaser.network;

import com.huuda.orbitallaser.client.skybreak.SkyBreakClient;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** Server -> client messages of the Sky Breaks. */
public final class SkyBreakPackets {
    private SkyBreakPackets() {
    }

    /** A strike is on: everything a client needs to replay it from its seed. */
    public record Start(UUID id, BlockPos target, int groundY, int seed, float meX, float meY, float meZ,
                        int ticks, boolean terrain) {
        public static void encode(Start m, FriendlyByteBuf b) {
            b.writeUUID(m.id);
            b.writeBlockPos(m.target);
            b.writeVarInt(m.groundY);
            b.writeInt(m.seed);
            b.writeFloat(m.meX);
            b.writeFloat(m.meY);
            b.writeFloat(m.meZ);
            b.writeVarInt(m.ticks);
            b.writeBoolean(m.terrain);
        }

        public static Start decode(FriendlyByteBuf b) {
            return new Start(b.readUUID(), b.readBlockPos(), b.readVarInt(), b.readInt(), b.readFloat(), b.readFloat(),
                    b.readFloat(), b.readVarInt(), b.readBoolean());
        }

        public static void handle(Start m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> SkyBreakClient.onStart(m));
        }
    }

    /** The disc of land torn out at the start: 4 ints a block (dx, dy, dz from the target's ground, block state id). */
    public record Lift(UUID id, int[] blocks) {
        public static void encode(Lift m, FriendlyByteBuf b) {
            b.writeUUID(m.id);
            b.writeVarIntArray(m.blocks);
        }

        public static Lift decode(FriendlyByteBuf b) {
            return new Lift(b.readUUID(), b.readVarIntArray(1 << 18));
        }

        public static void handle(Lift m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> SkyBreakClient.onLift(m));
        }
    }

    /** The strike is over. */
    public record End(UUID id) {
        public static void encode(End m, FriendlyByteBuf b) {
            b.writeUUID(m.id);
        }

        public static End decode(FriendlyByteBuf b) {
            return new End(b.readUUID());
        }

        public static void handle(End m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> SkyBreakClient.onEnd(m));
        }
    }
}
