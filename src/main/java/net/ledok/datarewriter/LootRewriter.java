package net.ledok.datarewriter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.ledok.datarewriter.config.RewriteConfig;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class LootRewriter {
    private LootRewriter() {
    }

    /**
     * Called from the scanDirectory mixin with the raw loot table JSON map,
     * before vanilla parses it. This is the earliest hook of a (re)load, so it
     * also refreshes the config. Matching tables are emptied rather than
     * deleted — blocks/mobs/chests simply drop nothing, and mods that look
     * tables up by id keep working.
     */
    public static void rewrite(Map<ResourceLocation, JsonElement> tableJsons) {
        RewriteConfig loaded = RewriteState.reloadConfig();

        Set<ResourceLocation> addedIds = new HashSet<>();
        for (RewriteConfig.AddedLootTable addition : loaded.lootAdditions()) {
            addedIds.add(addition.id());
        }

        int removed = 0;
        if (!loaded.lootRemovals().isEmpty()) {
            for (Map.Entry<ResourceLocation, JsonElement> entry : tableJsons.entrySet()) {
                if (addedIds.contains(entry.getKey())) {
                    continue; // removal rules never touch tables the user adds
                }
                for (RewriteConfig.LootRule rule : loaded.lootRemovals()) {
                    if (rule.matches(entry.getKey())) {
                        entry.setValue(new JsonObject()); // empty loot table
                        removed++;
                        break;
                    }
                }
            }
        }

        int replaced = 0;
        for (RewriteConfig.AddedLootTable addition : loaded.lootAdditions()) {
            if (tableJsons.put(addition.id(), addition.json()) != null) {
                replaced++;
            }
        }

        // Modifications run last and also apply to tables added above.
        int modified = 0;
        if (!loaded.lootModifications().isEmpty()) {
            for (Map.Entry<ResourceLocation, JsonElement> entry : tableJsons.entrySet()) {
                if (!(entry.getValue() instanceof JsonObject table)) {
                    continue;
                }
                boolean changed = false;
                for (RewriteConfig.LootModification modification : loaded.lootModifications()) {
                    if (!modification.target().matches(entry.getKey())) {
                        continue;
                    }
                    JsonArray pools;
                    if (table.get("pools") instanceof JsonArray existing) {
                        pools = existing;
                    } else {
                        pools = new JsonArray();
                        table.add("pools", pools);
                    }
                    for (JsonElement pool : modification.pools()) {
                        pools.add(pool.deepCopy());
                    }
                    changed = true;
                }
                if (changed) {
                    modified++;
                }
            }
        }

        RewriteState.lootRemoved = removed;
        RewriteState.lootAdded = loaded.lootAdditions().size();
        RewriteState.lootReplaced = replaced;
        RewriteState.lootModified = modified;

        if (removed + loaded.lootAdditions().size() + modified > 0) {
            Datarewriter.LOGGER.info("Emptied {} loot tables, added {} ({} replacing existing ones), modified {}",
                    removed, loaded.lootAdditions().size(), replaced, modified);
        }
    }
}
