package net.ledok.datarewriter.platform;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.loot.LootTable;

import java.nio.file.Path;
import java.util.ServiceLoader;

/** Loader-specific odds and ends; each loader module provides one implementation via ServiceLoader. */
public interface Platform {
    Platform INSTANCE = ServiceLoader.load(Platform.class).findFirst()
            .orElseThrow(() -> new IllegalStateException("No DataRewriter Platform service"));

    /** The game's {@code config/} directory. */
    Path configDir();

    boolean isModLoaded(String modId);

    /**
     * Re-runs the loader's own loot table load hooks on a freshly parsed table, exactly like a real
     * (re)load does after parsing — Fabric's {@code LootTableEvents} (REPLACE + MODIFY), NeoForge's
     * {@code LootTableLoadEvent} — so loot other mods inject at runtime survives a live swap. Returns the
     * table to bind; {@link LootTable#EMPTY} when a hook cancelled it.
     */
    LootTable applyLootHooks(MinecraftServer server, ResourceKey<LootTable> key, LootTable table);
}
