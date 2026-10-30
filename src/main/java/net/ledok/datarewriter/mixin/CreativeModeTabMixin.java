package net.ledok.datarewriter.mixin;

import net.ledok.datarewriter.DisabledItems;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackLinkedSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;

/** Client: items the server disabled leave every creative tab and the creative search. */
@Mixin(CreativeModeTab.class)
public abstract class CreativeModeTabMixin {
    @Shadow
    private Collection<ItemStack> displayItems;

    @Shadow
    private Set<ItemStack> displayItemsSearchTab;

    @Inject(method = "buildContents", at = @At("TAIL"))
    private void datarewriter$hideDisabled(CreativeModeTab.ItemDisplayParameters parameters, CallbackInfo ci) {
        if (!DisabledItems.clientActive()) {
            return;
        }
        // Fresh collections: the built ones may be unmodifiable (NeoForge's tab event builds its own).
        Collection<ItemStack> items = new ArrayList<>(displayItems);
        items.removeIf(stack -> DisabledItems.hiddenOnClient(stack.getItem()));
        displayItems = items;
        Set<ItemStack> search = ItemStackLinkedSet.createTypeAndComponentsSet();
        search.addAll(displayItemsSearchTab);
        search.removeIf(stack -> DisabledItems.hiddenOnClient(stack.getItem()));
        displayItemsSearchTab = search;
    }
}
