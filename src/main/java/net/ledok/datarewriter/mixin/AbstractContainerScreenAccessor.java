package net.ledok.datarewriter.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The editors size their "background" to the UI they lay out in init() (so overlay mods like EMI place their
 * panels beside it); the size became final constructor state in 1.21.9.
 */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
    @Mutable
    @Accessor("imageWidth")
    void datarewriter$setImageWidth(int width);

    @Mutable
    @Accessor("imageHeight")
    void datarewriter$setImageHeight(int height);
}
