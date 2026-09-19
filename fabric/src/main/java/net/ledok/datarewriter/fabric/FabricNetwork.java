package net.ledok.datarewriter.fabric;

import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.ledok.datarewriter.platform.Network;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

public final class FabricNetwork implements Network {
    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        ServerPlayNetworking.send(player, payload);
    }

    @Override
    public boolean canSendToPlayer(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        return ServerPlayNetworking.canSend(player, type);
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) ClientPlayNetworking.send(payload);
    }

    @Override
    public boolean canSendToServer(CustomPacketPayload.Type<?> type) {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT && ClientPlayNetworking.canSend(type);
    }
}
