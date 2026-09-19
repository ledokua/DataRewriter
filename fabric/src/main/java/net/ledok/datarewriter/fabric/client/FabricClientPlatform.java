package net.ledok.datarewriter.fabric.client;

import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandler;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry;
import net.ledok.datarewriter.client.ClientPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;
import org.jetbrains.annotations.Nullable;

public final class FabricClientPlatform implements ClientPlatform {
    @Override
    public @Nullable FluidSprite fluidSprite(Fluid fluid) {
        FluidRenderHandler handler = FluidRenderHandlerRegistry.INSTANCE.get(fluid);
        if (handler == null) {
            return null;
        }
        TextureAtlasSprite[] sprites = handler.getFluidSprites(null, null, fluid.defaultFluidState());
        if (sprites == null || sprites.length == 0 || sprites[0] == null) {
            return null;
        }
        return new FluidSprite(sprites[0], handler.getFluidColor(null, null, fluid.defaultFluidState()));
    }

    /** Fabric's model loading API injects {@code getModel(ResourceLocation)} for models added through its plugins. */
    @Override
    public @Nullable BakedModel standaloneModel(ResourceLocation id) {
        return Minecraft.getInstance().getModelManager().getModel(id);
    }
}
