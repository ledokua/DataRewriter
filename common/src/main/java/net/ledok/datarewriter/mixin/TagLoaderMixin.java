package net.ledok.datarewriter.mixin;

import net.ledok.datarewriter.DisabledItems;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Takes disabled items out of every item tag as the tags are built, before anything binds or syncs them —
 * so tag ingredients, tag loot entries, EMI's tag views and the client all see the pruned tags.
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
        Map<ResourceLocation, Collection<T>> pruned = new HashMap<>(cir.getReturnValue().size());
        for (Map.Entry<ResourceLocation, Collection<T>> entry : cir.getReturnValue().entrySet()) {
            Collection<T> values = entry.getValue();
            if (values.stream().anyMatch(TagLoaderMixin::disabled)) {
                values = values.stream().filter(value -> !disabled(value)).toList();
            }
            pruned.put(entry.getKey(), values);
        }
        cir.setReturnValue(pruned);
    }

    private static boolean disabled(Object value) {
        return value instanceof Holder<?> holder && holder.isBound()
                && holder.value() instanceof Item item && DisabledItems.isDisabled(item);
    }
}
