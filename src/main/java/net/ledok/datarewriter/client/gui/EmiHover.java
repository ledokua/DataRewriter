package net.ledok.datarewriter.client.gui;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/**
 * Turns an editor slot's ref into the object EMI's stack lookups (the R and U
 * keys) expect. The editor menus have no real slots for EMI to read, so each
 * screen answers {@code emiStackAt} with an ItemStack, an Item, a Fluid or a
 * "#tag" string, and the EMI plugin maps that to an ingredient — the same
 * plain-object convention {@code emiDrop} uses in the other direction, which
 * keeps this package free of any compile dependency on EMI.
 */
final class EmiHover {
    private EmiHover() {
    }

    /** An item ref, or the ref itself when it names a tag; null when unknown. */
    static Object itemOrTag(String ref) {
        if (ref == null || ref.isEmpty()) {
            return null;
        }
        if (ref.startsWith("#")) {
            return ref;
        }
        Identifier id = Identifier.tryParse(ref);
        if (id == null) {
            return null;
        }
        Item item = BuiltInRegistries.ITEM.getValue(id);
        return item == Items.AIR ? null : item;
    }

    /** A fluid ref (fluid slots hold a bare fluid id); null when unknown. */
    static Object fluid(String ref) {
        Identifier id = ref == null ? null : Identifier.tryParse(ref);
        if (id == null) {
            return null;
        }
        Fluid fluid = BuiltInRegistries.FLUID.getValue(id);
        return fluid == Fluids.EMPTY ? null : fluid;
    }
}
