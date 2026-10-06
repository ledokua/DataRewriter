package net.ledok.datarewriter.mixin;

import net.ledok.datarewriter.DisabledItems;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rewrites every item tag as it is built, before anything binds or syncs it: a disabled item is swapped for
 * its replacement (added once), or just taken out when it has none — so tag ingredients, tag loot entries,
 * EMI's tag views and the client all see the survivor. Tags left empty are reported to
 * {@link DisabledItems#tagEmptied}, so the recipe pass can drop recipes nothing can fill any more.
 */
@Mixin(TagLoader.class)
public abstract class TagLoaderMixin<T> {
    private static final String ITEM_TAGS = Registries.tagsDirPath(Registries.ITEM);

    @Shadow
    @Final
    private String directory;

    @Inject(method = "build(Ljava/util/Map;)Ljava/util/Map;", at = @At("RETURN"), cancellable = true)
    private void datarewriter$pruneDisabledItems(Map<ResourceLocation, List<TagLoader.EntryWithSource>> builders,
                                                 CallbackInfoReturnable<Map<ResourceLocation, Collection<T>>> cir) {
        if (!DisabledItems.active() || !ITEM_TAGS.equals(directory)) {
            return;
        }
        DisabledItems.clearEmptiedTags();
        Map<ResourceLocation, Collection<T>> rewritten = new HashMap<>(cir.getReturnValue().size());
        for (Map.Entry<ResourceLocation, Collection<T>> entry : cir.getReturnValue().entrySet()) {
            Collection<T> values = entry.getValue();
            if (values.stream().anyMatch(value -> disabled(value) != null)) {
                Set<T> next = new LinkedHashSet<>();
                for (T value : values) {
                    Item item = disabled(value);
                    if (item == null) {
                        next.add(value);
                        continue;
                    }
                    Item with = DisabledItems.replacement(item);
                    if (with != null && with != Items.AIR) {
                        // Item holders are the registry's own references, the same objects tags hold.
                        @SuppressWarnings("unchecked")
                        T holder = (T) with.builtInRegistryHolder();
                        next.add(holder);
                    }
                }
                if (next.isEmpty()) {
                    DisabledItems.tagEmptied(entry.getKey());
                }
                values = List.copyOf(next);
            }
            rewritten.put(entry.getKey(), values);
        }
        cir.setReturnValue(rewritten);
    }

    /** The value's item if it is a disabled one, else null. */
    private static Item disabled(Object value) {
        return value instanceof Holder<?> holder && holder.isBound() && holder.value() instanceof Item item
                && DisabledItems.isDisabled(item) ? item : null;
    }
}
