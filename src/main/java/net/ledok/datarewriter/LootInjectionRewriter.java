package net.ledok.datarewriter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.ledok.datarewriter.config.RewriteConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.loot.LootTable;

import java.util.List;
import java.util.Map;

/**
 * Post-injection pass for the config's replace_items/remove_items rules.
 * The JSON-level rewrite in LootRewriter runs before vanilla parses the
 * tables, so loot other mods inject at runtime (Fabric loot events) is not
 * covered by it. This pass runs after startup / reload completes: it encodes
 * each live table back to JSON, applies the item rules again (idempotent for
 * the datapack part) and rebinds tables where injected entries matched.
 */
public final class LootInjectionRewriter {
    private LootInjectionRewriter() {
    }

    /** Called from SERVER_STARTED / END_DATA_PACK_RELOAD, after loot events ran. */
    public static void apply(MinecraftServer server) {
        try {
            applyRules(server);
        } catch (Throwable t) {
            // A config convenience must never take the server down — worst
            // case some rules only cover what the pre-parse pass reached.
            Datarewriter.LOGGER.error("Post-load loot item pass failed — "
                    + "#tag rules and injected-loot coverage may be incomplete", t);
        }
    }

    private static void applyRules(MinecraftServer server) {
        RewriteConfig config = RewriteState.config();
        if (config.lootItemReplacements().isEmpty() && config.lootItemRemovals().isEmpty()) {
            return;
        }
        // #tag rules were skipped by the pre-parse JSON pass (tags weren't
        // bound yet) — apply them to the datapack-level cache now so the loot
        // editor's view matches what actually drops.
        for (Map.Entry<ResourceLocation, JsonElement> entry : RewriteState.lootTableJsons.entrySet()) {
            if (!(entry.getValue() instanceof JsonObject table)) {
                continue;
            }
            for (RewriteConfig.LootItemReplacement rule : config.lootItemReplacements()) {
                if (rule.from().tag() != null
                        && (rule.table() == null || rule.table().matches(entry.getKey()))) {
                    LootRewriter.applyReplacement(table, rule);
                }
            }
            for (RewriteConfig.LootItemRemoval rule : config.lootItemRemovals()) {
                if (rule.item().tag() != null
                        && (rule.table() == null || rule.table().matches(entry.getKey()))) {
                    LootRewriter.applyRemoval(table, rule);
                }
            }
        }

        Registry<LootTable> registry = server.reloadableRegistries().get()
                .registryOrThrow(Registries.LOOT_TABLE);
        int entriesChanged = 0;
        int tablesChanged = 0;
        int skipped = 0;
        List<Holder.Reference<LootTable>> holders = registry.holders().toList();
        for (Holder.Reference<LootTable> holder : holders) {
            ResourceLocation id = holder.key().location();
            JsonObject json = LootLiveApplier.encode(server, holder.value());
            if (json == null) {
                skipped++; // holds mod loot entries that can't round-trip
                continue;
            }
            int changed = 0;
            for (RewriteConfig.LootItemReplacement rule : config.lootItemReplacements()) {
                if (rule.table() == null || rule.table().matches(id)) {
                    changed += LootRewriter.applyReplacement(json, rule);
                }
            }
            for (RewriteConfig.LootItemRemoval rule : config.lootItemRemovals()) {
                if (rule.table() == null || rule.table().matches(id)) {
                    changed += LootRewriter.applyRemoval(json, rule);
                }
            }
            if (changed == 0) {
                continue; // nothing matched — the pre-parse pass covered the rest
            }
            LootLiveApplier.Parsed parsed = LootLiveApplier.parse(server, json);
            if (parsed.error() != null) {
                Datarewriter.LOGGER.warn("Post-injection loot pass: {} no longer parses: {}",
                        id, parsed.error());
                continue;
            }
            String error = LootLiveApplier.bindRaw(server, id, parsed.table());
            if (error != null) {
                Datarewriter.LOGGER.warn("Post-injection loot pass: could not rebind {}: {}", id, error);
                continue;
            }
            entriesChanged += changed;
            tablesChanged++;
        }
        if (tablesChanged > 0) {
            Datarewriter.LOGGER.info(
                    "Post-load loot item pass: changed {} entries in {} tables "
                            + "(#tag rules and runtime-injected loot)",
                    entriesChanged, tablesChanged);
        }
        if (skipped > 0) {
            Datarewriter.LOGGER.info(
                    "Post-load loot item pass: skipped {} tables whose mod loot entries "
                            + "can't be encoded back to JSON", skipped);
        }
    }
}
