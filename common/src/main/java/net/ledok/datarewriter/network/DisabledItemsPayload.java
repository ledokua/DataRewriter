package net.ledok.datarewriter.network;

import io.netty.buffer.ByteBuf;
import net.ledok.datarewriter.Datarewriter;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** S2C: every item {@code items.disable} took out of the game, so the client can hide it (EMI, creative tabs). */
public record DisabledItemsPayload(List<ResourceLocation> items) implements CustomPacketPayload {
    public static final Type<DisabledItemsPayload> TYPE = new Type<>(Datarewriter.id("disabled_items"));
    public static final StreamCodec<ByteBuf, DisabledItemsPayload> STREAM_CODEC =
            ResourceLocation.STREAM_CODEC.apply(ByteBufCodecs.list())
                    .map(DisabledItemsPayload::new, DisabledItemsPayload::items);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
