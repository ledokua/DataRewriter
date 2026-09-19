package net.ledok.datarewriter.mixin;

import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * Used by the loot editor to register a brand-new loot table into the frozen
 * reloadable registry (unfreeze, register, refreeze) and to keep the
 * value-keyed maps consistent after rebinding an existing holder.
 */
@Mixin(MappedRegistry.class)
public interface MappedRegistryAccessor<T> {
    @Accessor("frozen")
    void datarewriter$setFrozen(boolean frozen);

    @Accessor("byValue")
    Map<T, Holder.Reference<T>> datarewriter$getByValue();

    @Accessor("toId")
    Reference2IntMap<T> datarewriter$getToId();
}
