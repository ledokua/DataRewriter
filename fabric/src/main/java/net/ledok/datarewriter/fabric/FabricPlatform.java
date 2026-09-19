package net.ledok.datarewriter.fabric;

import net.fabricmc.fabric.api.loot.v3.FabricLootTableBuilder;
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.fabricmc.fabric.api.loot.v3.LootTableSource;
import net.fabricmc.loader.api.FabricLoader;
import net.ledok.datarewriter.platform.Platform;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.loot.LootTable;

import java.nio.file.Path;

public final class FabricPlatform implements Platform {
    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    /** Fabric's loot events, in the order fabric-api fires them after parsing a table. */
    @Override
    public LootTable applyLootHooks(MinecraftServer server, ResourceKey<LootTable> key, LootTable table) {
        LootTableSource source = key.location().getNamespace().equals("minecraft")
                ? LootTableSource.VANILLA : LootTableSource.MOD;
        LootTable replaced = LootTableEvents.REPLACE.invoker()
                .replaceLootTable(key, table, source, server.registryAccess());
        if (replaced != null) {
            table = replaced;
            source = LootTableSource.REPLACED;
        }
        LootTable.Builder builder = FabricLootTableBuilder.copyOf(table);
        LootTableEvents.MODIFY.invoker().modifyLootTable(key, builder, source, server.registryAccess());
        return builder.build();
    }
}
