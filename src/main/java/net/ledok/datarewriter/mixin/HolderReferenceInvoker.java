package net.ledok.datarewriter.mixin;

import net.minecraft.core.Holder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Lets the loot editor swap a holder's value in place. Every lookup goes
 * through the holder, so rebinding it live-replaces the loot table without a
 * data reload (exactly what vanilla itself does while loading).
 */
@Mixin(Holder.Reference.class)
public interface HolderReferenceInvoker<T> {
    @Invoker("bindValue")
    void datarewriter$bindValue(T value);
}
