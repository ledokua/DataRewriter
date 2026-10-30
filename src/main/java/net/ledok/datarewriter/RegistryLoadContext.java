package net.ledok.datarewriter;

import net.minecraft.resources.RegistryOps;

/**
 * Implemented on every registry load task (RegistryLoadTaskMixin): the lookup a datapack-resource load
 * parses with, so {@code registries.add} entries can be parsed against the same registries. Unset for
 * loads from the network.
 */
public interface RegistryLoadContext {
    void datarewriter$setContext(RegistryOps.RegistryInfoLookup context);
}
