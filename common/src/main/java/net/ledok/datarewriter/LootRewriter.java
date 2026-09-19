package net.ledok.datarewriter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.ledok.datarewriter.config.IdPattern;
import net.ledok.datarewriter.config.RewriteConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

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

        // Item-level ops run last so they also cover pools added/injected above.
        int itemsReplaced = 0;
        int itemsRemoved = 0;
        if (!loaded.lootItemReplacements().isEmpty() || !loaded.lootItemRemovals().isEmpty()) {
            for (Map.Entry<ResourceLocation, JsonElement> entry : tableJsons.entrySet()) {
                if (!(entry.getValue() instanceof JsonObject table)) {
                    continue;
                }
                for (RewriteConfig.LootItemReplacement rule : loaded.lootItemReplacements()) {
                    // #tag rules need bound item tags, which this early hook
                    // runs before — the post-load pass applies them instead.
                    if (rule.from().tag() == null
                            && (rule.table() == null || rule.table().matches(entry.getKey()))) {
                        itemsReplaced += replaceItemEntries(table, rule.from().pattern(),
                                rule.to().toString());
                    }
                }
                for (RewriteConfig.LootItemRemoval rule : loaded.lootItemRemovals()) {
                    if (rule.item().tag() == null
                            && (rule.table() == null || rule.table().matches(entry.getKey()))) {
                        itemsRemoved += removeItemEntries(table, rule.item().pattern());
                    }
                }
            }
        }

        RewriteState.lootRemoved = removed;
        RewriteState.lootAdded = loaded.lootAdditions().size();
        RewriteState.lootReplaced = replaced;
        RewriteState.lootModified = modified;
        RewriteState.lootItemsReplaced = itemsReplaced;
        RewriteState.lootItemsRemoved = itemsRemoved;

        // The loot editor (networking) reads and updates this cache later.
        RewriteState.lootTableJsons = new HashMap<>(tableJsons);
        RewriteState.runtimeTableJsons = new ConcurrentHashMap<>();

        if (removed + loaded.lootAdditions().size() + modified + itemsReplaced + itemsRemoved > 0) {
            Datarewriter.LOGGER.info("Emptied {} loot tables, added {} ({} replacing existing ones), "
                            + "modified {}; item entries: {} replaced, {} removed",
                    removed, loaded.lootAdditions().size(), replaced, modified, itemsReplaced, itemsRemoved);
            if (!loaded.lootAdditions().isEmpty()) {
                Datarewriter.LOGGER.info("Loot tables added: {}", loaded.lootAdditions().stream()
                        .map(a -> a.id() + " (" + a.source() + ")")
                        .collect(java.util.stream.Collectors.joining(", ")));
            }
        }
    }

    /**
     * Rewrites every item entry whose item matches 'from' to drop 'to'
     * instead, anywhere in the table (including nested alternatives/groups).
     * Returns the number of entries changed.
     */
    public static int replaceItemEntries(JsonObject table, IdPattern from, String to) {
        return replaceEntries(table, from::matches, tag -> false, to);
    }

    /**
     * Deletes every item entry whose item matches the pattern, anywhere in
     * the table. Returns the number of entries deleted.
     */
    public static int removeItemEntries(JsonObject table, IdPattern item) {
        return removeEntries(table, item::matches, tag -> false);
    }

    /**
     * Predicate form: rewrites matching item entries to drop 'to', and turns
     * matching "minecraft:tag" entries into an item entry for 'to' (used by
     * #tag rules — replacing the whole tag drop with one item).
     */
    public static int replaceEntries(JsonObject table, Predicate<ResourceLocation> item,
                                     Predicate<ResourceLocation> tag, String to) {
        return walkEntries(table, entry -> {
            ResourceLocation name = itemEntryName(entry);
            if (name != null) {
                if (!item.test(name) || name.toString().equals(to)) {
                    return EntryOp.KEEP;
                }
                entry.addProperty("name", to);
                return EntryOp.CHANGED;
            }
            ResourceLocation tagName = entryName(entry, "tag");
            if (tagName != null && tag.test(tagName)) {
                entry.addProperty("type", "minecraft:item");
                entry.addProperty("name", to);
                entry.remove("expand");
                return EntryOp.CHANGED;
            }
            return EntryOp.KEEP;
        });
    }

    /** Predicate form: deletes matching item entries and matching tag entries. */
    public static int removeEntries(JsonObject table, Predicate<ResourceLocation> item,
                                    Predicate<ResourceLocation> tag) {
        return walkEntries(table, entry -> {
            ResourceLocation name = itemEntryName(entry);
            if (name != null) {
                return item.test(name) ? EntryOp.DELETE : EntryOp.KEEP;
            }
            ResourceLocation tagName = entryName(entry, "tag");
            return tagName != null && tag.test(tagName) ? EntryOp.DELETE : EntryOp.KEEP;
        });
    }

    /**
     * Applies one replacement rule to a table's JSON (no scope check). #tag
     * rules resolve the tag's items, so they only work once tags are bound.
     */
    public static int applyReplacement(JsonObject table, RewriteConfig.LootItemReplacement rule) {
        if (rule.from().tag() != null) {
            Set<ResourceLocation> items = tagContents(rule.from().tag());
            return replaceEntries(table, items::contains, rule.from().tag()::equals,
                    rule.to().toString());
        }
        return replaceItemEntries(table, rule.from().pattern(), rule.to().toString());
    }

    /** Same as {@link #applyReplacement} for removal rules. */
    public static int applyRemoval(JsonObject table, RewriteConfig.LootItemRemoval rule) {
        if (rule.item().tag() != null) {
            Set<ResourceLocation> items = tagContents(rule.item().tag());
            return removeEntries(table, items::contains, rule.item().tag()::equals);
        }
        return removeItemEntries(table, rule.item().pattern());
    }

    /** The item ids in a tag (empty when the tag doesn't exist or isn't bound yet). */
    public static Set<ResourceLocation> tagContents(ResourceLocation tagId) {
        Set<ResourceLocation> items = new HashSet<>();
        BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, tagId)).ifPresent(tag ->
                tag.forEach(holder -> holder.unwrapKey()
                        .ifPresent(key -> items.add(key.location()))));
        return items;
    }

    /** Does any entry in the table drop an item matching the pattern? */
    public static boolean containsItemEntry(JsonObject table, IdPattern item) {
        boolean[] found = {false};
        walkEntries(table, entry -> {
            ResourceLocation name = itemEntryName(entry);
            if (name != null && item.matches(name)) {
                found[0] = true;
            }
            return EntryOp.KEEP;
        });
        return found[0];
    }

    /**
     * Does any entry drop the tag itself (a "minecraft:tag" entry) or one of
     * the given items (the tag's contents, resolved by the caller)?
     */
    public static boolean containsTagOrItems(JsonObject table, ResourceLocation tagId,
                                             Set<ResourceLocation> items) {
        boolean[] found = {false};
        walkEntries(table, entry -> {
            ResourceLocation item = itemEntryName(entry);
            if (item != null ? items.contains(item) : tagId.equals(entryName(entry, "tag"))) {
                found[0] = true;
            }
            return EntryOp.KEEP;
        });
        return found[0];
    }

    /** The entry's item id if it is a "minecraft:item" entry, else null. */
    private static ResourceLocation itemEntryName(JsonObject entry) {
        return entryName(entry, "item");
    }

    /** The entry's "name" id if the entry has the given vanilla type, else null. */
    private static ResourceLocation entryName(JsonObject entry, String typePath) {
        if (!(entry.get("type") instanceof JsonPrimitive type) || !type.isString()
                || !(entry.get("name") instanceof JsonPrimitive name) || !name.isString()) {
            return null;
        }
        ResourceLocation typeId = ResourceLocation.tryParse(type.getAsString());
        if (typeId == null || !typeId.equals(ResourceLocation.withDefaultNamespace(typePath))) {
            return null;
        }
        return ResourceLocation.tryParse(name.getAsString());
    }

    private enum EntryOp {
        KEEP, CHANGED, DELETE
    }

    private interface EntryVisitor {
        EntryOp visit(JsonObject entry);
    }

    private static int walkEntries(JsonObject table, EntryVisitor visitor) {
        int changed = 0;
        if (table.get("pools") instanceof JsonArray pools) {
            for (JsonElement pool : pools) {
                if (pool instanceof JsonObject poolObj
                        && poolObj.get("entries") instanceof JsonArray entries) {
                    changed += walkEntryArray(entries, visitor);
                }
            }
        }
        return changed;
    }

    private static int walkEntryArray(JsonArray entries, EntryVisitor visitor) {
        int changed = 0;
        for (Iterator<JsonElement> it = entries.iterator(); it.hasNext(); ) {
            if (!(it.next() instanceof JsonObject entry)) {
                continue;
            }
            // Composite entries (alternatives/group/sequence) nest children;
            // one left empty by deletions is inert, so it goes too.
            if (entry.get("children") instanceof JsonArray children) {
                changed += walkEntryArray(children, visitor);
                if (children.isEmpty()) {
                    it.remove();
                }
                continue;
            }
            switch (visitor.visit(entry)) {
                case CHANGED -> changed++;
                case DELETE -> {
                    it.remove();
                    changed++;
                }
                case KEEP -> {
                }
            }
        }
        return changed;
    }
}
