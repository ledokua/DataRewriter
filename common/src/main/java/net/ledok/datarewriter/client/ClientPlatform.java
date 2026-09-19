package net.ledok.datarewriter.client;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;
import org.jetbrains.annotations.Nullable;

import java.util.ServiceLoader;

/** Client-side loader specifics; only ever loaded on the physical client. */
public interface ClientPlatform {
    ClientPlatform INSTANCE = ServiceLoader.load(ClientPlatform.class).findFirst()
            .orElseThrow(() -> new IllegalStateException("No DataRewriter ClientPlatform service"));

    /** A fluid's still sprite (block atlas) and the ARGB tint the loader's fluid renderer applies to it. */
    record FluidSprite(TextureAtlasSprite sprite, int color) {
    }

    /** How the loader draws this fluid, or null when it has no renderer registered for it. */
    @Nullable
    FluidSprite fluidSprite(Fluid fluid);

    /**
     * A standalone (non-blockstate, non-item) baked model another mod registered with the loader's model
     * loading API — Flywheel partials, for instance. Each loader files those under its own
     * {@code ModelResourceLocation} variant. Null when no such model is loaded.
     */
    @Nullable
    BakedModel standaloneModel(ResourceLocation id);
}
