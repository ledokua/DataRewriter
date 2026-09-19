package net.ledok.datarewriter.neoforge;

import net.ledok.datarewriter.platform.Platform;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.EventHooks;

import java.nio.file.Path;

public final class NeoForgePlatform implements Platform {
    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    /**
     * NeoForge fires {@code LootTableLoadEvent} once per table while loading; this is the very same call.
     * A null result means a listener cancelled the table (or emptied it), which loading maps to no table.
     */
    @Override
    public LootTable applyLootHooks(MinecraftServer server, ResourceKey<LootTable> key, LootTable table) {
        LootTable result = EventHooks.loadLootTable(server.registryAccess(), key.location(), table);
        return result == null ? LootTable.EMPTY : result;
    }
}
