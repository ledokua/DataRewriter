package net.ledok.datarewriter.neoforge.client;

import net.ledok.datarewriter.client.ClientPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import org.jetbrains.annotations.Nullable;

public final class NeoForgeClientPlatform implements ClientPlatform {
    @Override
    public @Nullable FluidSprite fluidSprite(Fluid fluid) {
        IClientFluidTypeExtensions extensions = IClientFluidTypeExtensions.of(fluid);
        ResourceLocation still = extensions.getStillTexture();
        if (still == null) {
            return null;
        }
        TextureAtlasSprite sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(still);
        return sprite == null ? null : new FluidSprite(sprite, extensions.getTintColor());
    }

    /** Models registered through {@code ModelEvent.RegisterAdditional} live under the standalone variant. */
    @Override
    public @Nullable BakedModel standaloneModel(ResourceLocation id) {
        return Minecraft.getInstance().getModelManager().getModel(ModelResourceLocation.standalone(id));
    }
}
