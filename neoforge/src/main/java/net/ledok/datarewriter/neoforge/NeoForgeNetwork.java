package net.ledok.datarewriter.neoforge;

import net.ledok.datarewriter.neoforge.client.NeoForgeClientNetwork;
import net.ledok.datarewriter.platform.Network;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;

/** The client-side checks live in {@link NeoForgeClientNetwork}, only ever called on the physical client. */
public final class NeoForgeNetwork implements Network {
    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    @Override
    public boolean canSendToPlayer(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        return player.connection != null && player.connection.hasChannel(type);
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        if (FMLEnvironment.dist.isClient()) PacketDistributor.sendToServer(payload);
    }

    @Override
    public boolean canSendToServer(CustomPacketPayload.Type<?> type) {
        return FMLEnvironment.dist.isClient() && NeoForgeClientNetwork.canSend(type);
    }
}
