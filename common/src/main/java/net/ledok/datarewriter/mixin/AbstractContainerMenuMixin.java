package net.ledok.datarewriter.mixin;

import net.ledok.datarewriter.DisabledItems;
import net.minecraft.core.NonNullList;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

/**
 * Converts disabled stacks as the server notices them: {@code broadcastChanges} (every tick, on the open
 * menu only) calls this for each slot whose stack differs from the last one it saw. That covers the
 * player inventory right after joining (nothing was seen yet), every pickup or other slot change, and a
 * container's whole contents when it is opened — without scanning every slot every tick.
 */
@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {
    @Shadow
    @Final
    public NonNullList<Slot> slots;

    @Inject(method = "triggerSlotListeners", at = @At("HEAD"))
    private void datarewriter$convertDisabled(int slotIndex, ItemStack stack, Supplier<ItemStack> copy,
                                              CallbackInfo ci) {
        if (!DisabledItems.active() || stack.isEmpty() || !DisabledItems.isDisabled(stack.getItem())) {
            return;
        }
        try {
            // The listeners still see the old stack this tick; the next broadcast syncs the new one.
            slots.get(slotIndex).set(DisabledItems.convert(stack));
        } catch (RuntimeException ignored) {
            // A modded slot that refuses writes keeps its stack rather than breaking the menu.
        }
    }
}
