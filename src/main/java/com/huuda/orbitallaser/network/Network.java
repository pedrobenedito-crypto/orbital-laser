package com.huuda.orbitallaser.network;

import com.huuda.orbitallaser.OrbitalLaserMod;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class Network {
    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(OrbitalLaserMod.MODID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals);

    private static boolean registered = false;

    private Network() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        CHANNEL.messageBuilder(StrikeSyncPacket.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(StrikeSyncPacket::encode)
                .decoder(StrikeSyncPacket::decode)
                .consumerMainThread(StrikeSyncPacket::handle)
                .add();
    }

    public static void toDimension(ResourceKey<Level> dimension, Object msg) {
        CHANNEL.send(PacketDistributor.DIMENSION.with(() -> dimension), msg);
    }

    public static void toPlayer(ServerPlayer player, Object msg) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), msg);
    }
}
