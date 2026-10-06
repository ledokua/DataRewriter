package net.ledok.datarewriter.mixin;

import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Clearing the cached parameters makes the next creative inventory rebuild every tab. */
@Mixin(CreativeModeTabs.class)
public interface CreativeModeTabsAccessor {
    @Accessor("CACHED_PARAMETERS")
    static void datarewriter$setCachedParameters(CreativeModeTab.ItemDisplayParameters parameters) {
        throw new AssertionError();
    }
}
