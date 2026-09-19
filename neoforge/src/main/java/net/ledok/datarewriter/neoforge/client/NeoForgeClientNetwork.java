package net.ledok.datarewriter.neoforge.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client-only half of {@link net.ledok.datarewriter.neoforge.NeoForgeNetwork}. */
public final class NeoForgeClientNetwork {
    private NeoForgeClientNetwork() {
    }

    public static boolean canSend(CustomPacketPayload.Type<?> type) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        return connection != null && connection.hasChannel(type);
    }
}
