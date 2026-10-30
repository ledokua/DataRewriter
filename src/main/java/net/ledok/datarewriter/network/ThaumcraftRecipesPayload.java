package net.ledok.datarewriter.network;

import io.netty.buffer.ByteBuf;
import net.ledok.datarewriter.Datarewriter;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/**
 * S2C: how the server's Thaumcraft recipe map differs from Thaumcraft's own recipes — keys it removed, and
 * the recipes it changed or added in DataRewriter's JSON form — so the client's recipe viewers and
 * Thaumonomicon match. Sent on join, /reload, /datarewriter reload and editor saves.
 */
public record ThaumcraftRecipesPayload(List<String> removed, List<String> changed) implements CustomPacketPayload {
    public static final Type<ThaumcraftRecipesPayload> TYPE = new Type<>(Datarewriter.id("thaumcraft_recipes"));
    public static final StreamCodec<ByteBuf, ThaumcraftRecipesPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), ThaumcraftRecipesPayload::removed,
            ByteBufCodecs.stringUtf8(1 << 20).apply(ByteBufCodecs.list()), ThaumcraftRecipesPayload::changed,
            ThaumcraftRecipesPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
