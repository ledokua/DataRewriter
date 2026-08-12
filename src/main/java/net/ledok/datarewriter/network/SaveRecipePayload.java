package net.ledok.datarewriter.network;

import io.netty.buffer.ByteBuf;
import net.ledok.datarewriter.Datarewriter;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C2S: the recipe editor screen asks the server to save a recipe to the config. */
public record SaveRecipePayload(String recipeJson) implements CustomPacketPayload {
    public static final Type<SaveRecipePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Datarewriter.MOD_ID, "save_recipe"));
    public static final StreamCodec<ByteBuf, SaveRecipePayload> STREAM_CODEC =
            ByteBufCodecs.STRING_UTF8.map(SaveRecipePayload::new, SaveRecipePayload::recipeJson);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
