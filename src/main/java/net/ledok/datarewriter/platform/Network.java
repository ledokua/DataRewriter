package net.ledok.datarewriter.platform;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.ServiceLoader;

/** Loader-specific packet sending; the payload definitions themselves are shared. */
public interface Network {
    Network INSTANCE = ServiceLoader.load(Network.class).findFirst()
            .orElseThrow(() -> new IllegalStateException("No DataRewriter Network service"));

    void sendToPlayer(ServerPlayer player, CustomPacketPayload payload);

    /** Whether the player's client declared the payload — false for vanilla clients and clients without the mod. */
    boolean canSendToPlayer(ServerPlayer player, CustomPacketPayload.Type<?> type);

    /** Client only. */
    void sendToServer(CustomPacketPayload payload);

    /** Client only: whether the server declared the payload — false on servers without the mod. */
    boolean canSendToServer(CustomPacketPayload.Type<?> type);
}
