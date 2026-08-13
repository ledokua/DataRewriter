package net.ledok.datarewriter.network;

import io.netty.buffer.ByteBuf;
import net.ledok.datarewriter.Datarewriter;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S: a bulk recipe edit from the recipe tweaks screen. mode is
 * "remove_output" (remove recipes producing 'from'), "remove_input" (remove
 * recipes using 'from' as an ingredient) or "replace" (rewrite ingredient
 * 'from' to 'to'). 'from' is an item id or "#tag"; 'to' is only used by
 * "replace".
 */
public record BulkRecipeEditPayload(String mode, String from, String to)
        implements CustomPacketPayload {
    public static final Type<BulkRecipeEditPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Datarewriter.MOD_ID, "bulk_recipe_edit"));
    public static final StreamCodec<ByteBuf, BulkRecipeEditPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, BulkRecipeEditPayload::mode,
                    ByteBufCodecs.STRING_UTF8, BulkRecipeEditPayload::from,
                    ByteBufCodecs.STRING_UTF8, BulkRecipeEditPayload::to,
                    BulkRecipeEditPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
