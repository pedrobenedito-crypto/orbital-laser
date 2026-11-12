package com.huuda.orbitallaser.network;

import com.huuda.orbitallaser.client.ClientStrikes;
import com.huuda.orbitallaser.strike.Strike;
import com.huuda.orbitallaser.strike.StrikeState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** Server -> client: tells clients in the dimension which phase a strike is in, so they can draw it. */
public record StrikeSyncPacket(UUID id, BlockPos target, byte phase, int ticksInPhase, int chargeTicks,
                               int radius, int depth, long seed) {

    public static final byte CHARGING = 0;
    public static final byte STRIKING = 1;
    public static final byte ENDED = 2;
    public static final byte CANCELLED = 3;

    public static StrikeSyncPacket of(Strike s) {
        byte phase = s.state == StrikeState.STRIKING ? STRIKING : CHARGING;
        return new StrikeSyncPacket(s.id, s.target, phase, s.ticksInState, s.chargeTicks, s.radius, s.depth, s.seed);
    }

    public static StrikeSyncPacket finished(Strike s, boolean cancelled) {
        return new StrikeSyncPacket(s.id, s.target, cancelled ? CANCELLED : ENDED, 0, s.chargeTicks,
                s.radius, s.depth, s.seed);
    }

    public static void encode(StrikeSyncPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.id);
        buf.writeBlockPos(msg.target);
        buf.writeByte(msg.phase);
        buf.writeVarInt(msg.ticksInPhase);
        buf.writeVarInt(msg.chargeTicks);
        buf.writeVarInt(msg.radius);
        buf.writeVarInt(msg.depth);
        buf.writeLong(msg.seed);
    }

    public static StrikeSyncPacket decode(FriendlyByteBuf buf) {
        return new StrikeSyncPacket(
                buf.readUUID(),
                buf.readBlockPos(),
                buf.readByte(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readLong());
    }

    public static void handle(StrikeSyncPacket msg, Supplier<NetworkEvent.Context> ctx) {
        // Called on the main thread (consumerMainThread). Client classes are only touched on the client.
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientStrikes.handle(msg));
    }
}
