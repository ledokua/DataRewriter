package net.ledok.datarewriter.mixin;

import net.minecraft.resources.RegistryLoadTask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RegistryLoadTask.class)
public interface RegistryLoadTaskAccessor {
    @Accessor("SKIPPED_ELEMENT_MARKER")
    static Exception datarewriter$skippedMarker() {
        throw new AssertionError();
    }
}
