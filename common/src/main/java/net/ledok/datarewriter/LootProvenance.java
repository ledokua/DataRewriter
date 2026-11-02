package net.ledok.datarewriter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.ledok.datarewriter.config.ConfigLoader;
import net.ledok.datarewriter.config.GuiLootSaver;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where the parts of each loot table in the JSON cache ({@code RewriteState.lootTableJsons}) come from, for
 * the loot editor: whether a config {@code add} / {@code remove} rule owns the table as a whole, and which
 * {@code modify} rule appended each pool (null = the table's own pool, from its mod/datapack or its add rule).
 * Recorded by {@code LootRewriter} on every (re)load and kept in step by the editor's live saves. Rule pools
 * are never saved by the editor: their rule appends them again on every load.
 */
public final class LootProvenance {
    /** A config rule that replaced/added ({@code "add"}) or emptied ({@code "remove"}) the whole table. */
    public record Owner(String kind, String source) {
    }

    private record Info(@Nullable Owner owner, List<@Nullable String> poolSources) {
    }

    private static volatile Map<ResourceLocation, Info> byTable = new ConcurrentHashMap<>();

    private LootProvenance() {
    }

    /** Collects one (re)load's provenance; installed at the end of {@code LootRewriter.rewrite}. */
    static final class Builder {
        private final Map<ResourceLocation, Owner> owners = new java.util.HashMap<>();
        private final Map<ResourceLocation, List<@Nullable String>> pools = new java.util.HashMap<>();

        void owner(ResourceLocation table, Owner owner) {
            owners.put(table, owner);
        }

        /** {@code count} pools of the table, starting at {@code from}, were appended by the rule in {@code source}. */
        void appended(ResourceLocation table, int from, int count, String source) {
            List<@Nullable String> list = pools.computeIfAbsent(table, id -> new ArrayList<>());
            while (list.size() < from) {
                list.add(null);
            }
            for (int i = 0; i < count; i++) {
                list.add(source);
            }
        }

        void install() {
            Map<ResourceLocation, Info> next = new ConcurrentHashMap<>();
            owners.forEach((id, owner) -> next.put(id, new Info(owner, pools.getOrDefault(id, List.of()))));
            pools.forEach((id, list) -> next.putIfAbsent(id, new Info(null, list)));
            byTable = next;
        }
    }

    /** The rule owning the table as a whole, or null. */
    public static @Nullable Owner owner(ResourceLocation table) {
        Info info = byTable.get(table);
        return info == null ? null : info.owner();
    }

    /** The rule file that appended pool {@code index} of the cached table, or null for the table's own pool. */
    public static @Nullable String poolSource(ResourceLocation table, int index) {
        Info info = byTable.get(table);
        return info == null || index >= info.poolSources().size() ? null : info.poolSources().get(index);
    }

    /** True when a generated config file owns the table: the editor must not save it. */
    public static boolean generatedOwner(ResourceLocation table) {
        Owner owner = owner(table);
        return owner != null && ConfigLoader.generatedNote(owner.source()) != null;
    }

    /** A live editor save replaced the table (or emptied it) through the GUI file; then rule pools were re-appended. */
    public static void savedWhole(ResourceLocation table, String kind, int ownPools, List<String> rulePoolSources) {
        List<@Nullable String> list = new ArrayList<>();
        for (int i = 0; i < ownPools; i++) {
            list.add(null);
        }
        list.addAll(rulePoolSources);
        byTable.put(table, new Info(new Owner(kind, GuiLootSaver.FILE_NAME), list));
    }

    /** A live editor save appended pools through the GUI file's modify rule for the table. */
    public static void savedAppended(ResourceLocation table, int from, int count) {
        Info info = byTable.get(table);
        List<@Nullable String> list = new ArrayList<>(info == null ? List.of() : info.poolSources());
        while (list.size() < from) {
            list.add(null);
        }
        for (int i = 0; i < count; i++) {
            list.add(GuiLootSaver.FILE_NAME);
        }
        byTable.put(table, new Info(info == null ? null : info.owner(), list));
    }

    /**
     * The table's provenance for the editor, aligned with the cached JSON's pools:
     * <pre>{ owner: {kind, source, generated?, gui}, pools: [null | {source, generated?, gui}, ...] }</pre>
     * {@code generated} is the file's note ("" = generated, nothing said); {@code gui} marks the editor's own
     * file, whose pools it folds back into the table on "Save table".
     */
    public static JsonObject describe(ResourceLocation table, int poolCount) {
        JsonObject json = new JsonObject();
        Owner owner = owner(table);
        if (owner != null) {
            JsonObject obj = source(owner.source());
            obj.addProperty("kind", owner.kind());
            json.add("owner", obj);
        }
        JsonArray pools = new JsonArray();
        for (int i = 0; i < poolCount; i++) {
            String source = poolSource(table, i);
            pools.add(source == null ? JsonNull.INSTANCE : source(source));
        }
        json.add("pools", pools);
        return json;
    }

    private static JsonObject source(String file) {
        JsonObject obj = new JsonObject();
        obj.addProperty("source", file);
        String note = ConfigLoader.generatedNote(file);
        if (note != null) {
            obj.addProperty("generated", note);
        }
        obj.addProperty("gui", file.equals(GuiLootSaver.FILE_NAME));
        return obj;
    }

    /** Pools count of a cached table JSON. */
    public static int poolCount(@Nullable JsonElement table) {
        return table instanceof JsonObject obj && obj.get("pools") instanceof JsonArray pools ? pools.size() : 0;
    }
}
