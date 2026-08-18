package net.ledok.datarewriter.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.ledok.datarewriter.Datarewriter;
import net.ledok.datarewriter.LootLiveApplier;
import net.ledok.datarewriter.LootRewriter;
import net.ledok.datarewriter.RewriteState;
import net.ledok.datarewriter.config.GuiLootSaver;
import net.ledok.datarewriter.config.IdPattern;
import net.ledok.datarewriter.config.ItemMatch;
import net.ledok.datarewriter.config.RewriteConfig;
import net.ledok.datarewriter.config.TableScope;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntries;
import net.minecraft.resources.RegistryOps;
import com.mojang.serialization.JsonOps;
import net.minecraft.world.level.storage.loot.LootTable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Server side of the loot table editor. Vanilla clients never send these
 * payloads; servers without the mod have no receivers (the client checks
 * before sending). All handlers require permission level 2.
 */
public final class LootEditNetworking {
    /** Reassembly buffers for chunked SaveTable payloads, per player. */
    private static final Map<UUID, PendingSave> PENDING_SAVES = new ConcurrentHashMap<>();

    private static final class PendingSave {
        final String mode;
        final String tableId;
        final String[] parts;
        int received;

        PendingSave(String mode, String tableId, int totalParts) {
            this.mode = mode;
            this.tableId = tableId;
            this.parts = new String[totalParts];
        }
    }

    private LootEditNetworking() {
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(LootPayloads.TableListRequest.TYPE,
                LootPayloads.TableListRequest.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(LootPayloads.TableRequest.TYPE,
                LootPayloads.TableRequest.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(LootPayloads.SaveTable.TYPE,
                LootPayloads.SaveTable.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(LootPayloads.BulkItemEdit.TYPE,
                LootPayloads.BulkItemEdit.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(LootPayloads.TableList.TYPE,
                LootPayloads.TableList.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(LootPayloads.TableContent.TYPE,
                LootPayloads.TableContent.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(LootPayloads.SaveResult.TYPE,
                LootPayloads.SaveResult.STREAM_CODEC);

        ServerPlayNetworking.registerGlobalReceiver(LootPayloads.TableListRequest.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (denied(player)) {
                return;
            }
            ServerPlayNetworking.send(player,
                    new LootPayloads.TableList(payload.itemFilter(), tableIds(context.server(), payload.itemFilter())));
        });

        ServerPlayNetworking.registerGlobalReceiver(LootPayloads.TableRequest.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (denied(player)) {
                return;
            }
            ResourceLocation id = ResourceLocation.tryParse(payload.tableId());
            JsonElement json = id == null ? null : RewriteState.lootTableJsons.get(id);
            String injected = id == null ? "" : injectedPools(context.server(), id, json);
            ServerPlayNetworking.send(player, new LootPayloads.TableContent(
                    payload.tableId(), json == null ? "" : json.toString(), injected));
        });

        ServerPlayNetworking.registerGlobalReceiver(LootPayloads.SaveTable.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (denied(player)) {
                return;
            }
            String json = assemble(player.getUUID(), payload);
            if (json != null) {
                handleSave(context.server(), player, payload.mode(), payload.tableId(), json);
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(LootPayloads.BulkItemEdit.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (denied(player)) {
                return;
            }
            handleBulk(context.server(), player, payload.fromItem(), payload.toItem(), payload.tables());
        });
    }

    private static boolean denied(ServerPlayer player) {
        if (player.hasPermissions(2)) {
            return false;
        }
        String text = "You need to be an operator (permission level 2) on this server to edit loot tables"
                + " — nothing was saved.";
        Datarewriter.LOGGER.warn("Loot editor: refused {} — not an operator", player.getGameProfile().getName());
        player.sendSystemMessage(prefix().append(Component.literal(text).withStyle(ChatFormatting.RED)));
        if (ServerPlayNetworking.canSend(player, LootPayloads.SaveResult.TYPE)) {
            ServerPlayNetworking.send(player, new LootPayloads.SaveResult(false, text));
        }
        return true;
    }

    /** Buffers one chunk; returns the full JSON once all parts arrived, else null. */
    private static String assemble(UUID player, LootPayloads.SaveTable payload) {
        if (payload.totalParts() < 1 || payload.totalParts() > 500
                || payload.part() < 0 || payload.part() >= payload.totalParts()) {
            PENDING_SAVES.remove(player);
            return null;
        }
        if (payload.totalParts() == 1) {
            PENDING_SAVES.remove(player);
            return payload.json();
        }
        PendingSave pending = PENDING_SAVES.compute(player, (key, existing) ->
                existing != null && existing.mode.equals(payload.mode())
                        && existing.tableId.equals(payload.tableId())
                        && existing.parts.length == payload.totalParts()
                        ? existing
                        : new PendingSave(payload.mode(), payload.tableId(), payload.totalParts()));
        synchronized (pending) {
            if (pending.parts[payload.part()] == null) {
                pending.received++;
            }
            pending.parts[payload.part()] = payload.json();
            if (pending.received < pending.parts.length) {
                return null;
            }
        }
        PENDING_SAVES.remove(player);
        return String.join("", pending.parts);
    }

    /**
     * The LIVE table encoded to JSON — datapack level plus runtime
     * injections. Cached per table until the table is rebound or reloaded.
     */
    private static JsonObject runtimeTable(MinecraftServer server, ResourceLocation id,
                                           LootTable table) {
        if (RewriteState.runtimeTableJsons.get(id) instanceof JsonObject cached) {
            return cached;
        }
        JsonObject encoded = LootLiveApplier.encode(server, table);
        if (encoded != null) {
            RewriteState.runtimeTableJsons.put(id, encoded);
        }
        return encoded;
    }

    /**
     * What other mods added to a table at runtime, as a JSON object:
     * {@code pools} = whole pools appended after the datapack-level ones,
     * {@code entries} = per pool index, entries present in the live pool but
     * not in the cached (datapack-level) pool — mods that merge into existing
     * pools instead of appending (e.g. loot-weight tweakers). "" = nothing.
     */
    private static String injectedPools(MinecraftServer server, ResourceLocation id,
                                        JsonElement cached) {
        JsonArray cachedPools = cached instanceof JsonObject obj && obj.get("pools") instanceof JsonArray a
                ? a : new JsonArray();
        int base = cachedPools.size();
        LootTable table = server.reloadableRegistries().get()
                .registryOrThrow(Registries.LOOT_TABLE).get(id);
        JsonObject runtime = table == null ? null : runtimeTable(server, id, table);
        if (runtime == null || !(runtime.get("pools") instanceof JsonArray pools)) {
            return "";
        }
        JsonArray tail = new JsonArray();
        for (int i = base; i < pools.size(); i++) {
            tail.add(pools.get(i));
        }
        JsonObject extraEntries = new JsonObject();
        for (int i = 0; i < Math.min(base, pools.size()); i++) {
            if (!(pools.get(i) instanceof JsonObject livePool) || !(livePool.get("entries") instanceof JsonArray liveEntries)
                    || !(cachedPools.get(i) instanceof JsonObject cachedPool)) {
                continue;
            }
            // Multiset difference by JSON text: an entry the live pool has more
            // often than the cached pool was merged in by someone else.
            Map<String, Integer> known = new HashMap<>();
            if (cachedPool.get("entries") instanceof JsonArray cachedEntries) {
                for (JsonElement e : cachedEntries) {
                    known.merge(canonicalEntry(server, e), 1, Integer::sum);
                }
            }
            JsonArray extra = new JsonArray();
            for (JsonElement e : liveEntries) {
                String key = canonicalEntry(server, e);
                int left = known.getOrDefault(key, 0);
                if (left > 0) {
                    known.put(key, left - 1);
                } else {
                    extra.add(e);
                }
            }
            if (!extra.isEmpty()) {
                extraEntries.add(String.valueOf(i), extra);
            }
        }
        if (tail.isEmpty() && extraEntries.isEmpty()) {
            return "";
        }
        JsonObject result = new JsonObject();
        result.add("pools", tail);
        result.add("entries", extraEntries);
        return result.toString();
    }

    /**
     * Entry JSON in the codec's own encoding (key order, defaults, number
     * formats), so hand-written datapack JSON and re-encoded live entries
     * compare equal when they mean the same thing. Falls back to the raw text.
     */
    private static String canonicalEntry(MinecraftServer server, JsonElement entry) {
        try {
            RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
            var parsed = LootPoolEntries.CODEC.parse(ops, entry).result();
            if (parsed.isPresent()) {
                var encoded = LootPoolEntries.CODEC.encodeStart(ops, parsed.get()).result();
                if (encoded.isPresent()) {
                    return encoded.get().toString();
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return entry.toString();
    }

    /**
     * Filtered searches run over the LIVE tables (encoded lazily), so loot
     * other mods inject at runtime is found too — not just the datapack level.
     */
    private static List<String> tableIds(MinecraftServer server, String itemFilter) {
        List<String> ids = new ArrayList<>();
        if (itemFilter.isEmpty()) {
            for (ResourceLocation id : server.reloadableRegistries().getKeys(Registries.LOOT_TABLE)) {
                ids.add(id.toString());
            }
        } else if (itemFilter.startsWith("#")) {
            // Tag filter: tables that drop the tag itself, or any item in it.
            ResourceLocation tagId = ResourceLocation.tryParse(itemFilter.substring(1));
            if (tagId != null) {
                Set<ResourceLocation> tagItems = new HashSet<>();
                BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, tagId)).ifPresent(tag ->
                        tag.forEach(holder -> holder.unwrapKey()
                                .ifPresent(key -> tagItems.add(key.location()))));
                forEachLiveTable(server, (id, table) -> {
                    if (LootRewriter.containsTagOrItems(table, tagId, tagItems)) {
                        ids.add(id.toString());
                    }
                });
            }
        } else {
            IdPattern item = IdPattern.parse(itemFilter);
            if (item != null) {
                forEachLiveTable(server, (id, table) -> {
                    if (LootRewriter.containsItemEntry(table, item)) {
                        ids.add(id.toString());
                    }
                });
            }
        }
        ids.sort(null);
        return ids;
    }

    private static void forEachLiveTable(MinecraftServer server,
                                         BiConsumer<ResourceLocation, JsonObject> visitor) {
        for (Holder.Reference<LootTable> holder : server.reloadableRegistries().get()
                .registryOrThrow(Registries.LOOT_TABLE).holders().toList()) {
            JsonObject table = runtimeTable(server, holder.key().location(), holder.value());
            if (table != null) {
                visitor.accept(holder.key().location(), table);
            }
        }
    }

    private static void handleSave(MinecraftServer server, ServerPlayer player,
                                   String mode, String tableId, String json) {
        ResourceLocation id = ResourceLocation.tryParse(tableId);
        if (id == null) {
            fail(player, "'" + tableId + "' is not a valid loot table id");
            return;
        }
        String error;
        String applied;
        switch (mode) {
            case "replace" -> {
                JsonObject table = parseObject(json);
                if (table == null) {
                    fail(player, "the loot table must be a JSON object");
                    return;
                }
                LootLiveApplier.Parsed parsed = LootLiveApplier.parse(server, table);
                if (parsed.error() != null) {
                    fail(player, parsed.error());
                    return;
                }
                error = GuiLootSaver.saveReplace(tableId, table);
                if (error == null) {
                    error = LootLiveApplier.bind(server, id, parsed.table());
                    RewriteState.lootTableJsons.put(id, table);
                }
                applied = "saved (whole table)";
            }
            case "modify" -> {
                JsonElement parsedJson;
                try {
                    parsedJson = JsonParser.parseString(json);
                } catch (Exception e) {
                    parsedJson = null;
                }
                if (!(parsedJson instanceof JsonArray pools) || pools.isEmpty()) {
                    fail(player, "'modify' needs a non-empty list of pools");
                    return;
                }
                // Merge onto the table's current JSON, exactly like the
                // config's modify does on reload.
                JsonElement current = RewriteState.lootTableJsons.get(id);
                JsonObject merged = current instanceof JsonObject obj ? obj.deepCopy() : new JsonObject();
                JsonArray target = merged.get("pools") instanceof JsonArray existing
                        ? existing : new JsonArray();
                merged.add("pools", target);
                for (JsonElement pool : pools) {
                    target.add(pool);
                }
                LootLiveApplier.Parsed parsed = LootLiveApplier.parse(server, merged);
                if (parsed.error() != null) {
                    fail(player, parsed.error());
                    return;
                }
                error = GuiLootSaver.saveModify(tableId, pools);
                if (error == null) {
                    error = LootLiveApplier.bind(server, id, parsed.table());
                    RewriteState.lootTableJsons.put(id, merged);
                }
                applied = "new pools saved (table's own loot kept)";
            }
            case "remove" -> {
                JsonObject empty = new JsonObject();
                LootLiveApplier.Parsed parsed = LootLiveApplier.parse(server, empty);
                if (parsed.error() != null) {
                    fail(player, parsed.error());
                    return;
                }
                error = GuiLootSaver.saveRemove(tableId);
                if (error == null) {
                    error = LootLiveApplier.bind(server, id, parsed.table());
                    RewriteState.lootTableJsons.put(id, empty);
                }
                applied = "emptied (drops nothing now)";
            }
            default -> {
                fail(player, "unknown save mode '" + mode + "'");
                return;
            }
        }
        if (error != null) {
            fail(player, error);
            return;
        }
        Datarewriter.LOGGER.info("Loot editor: {} — {} (by {})", id, applied, player.getGameProfile().getName());
        succeed(player, "Loot table " + id + " " + applied + " — written to config/datarewriter/"
                + GuiLootSaver.FILE_NAME + " on the server and applied live.");
    }

    private static void handleBulk(MinecraftServer server, ServerPlayer player,
                                   String fromItem, String toItem, String tables) {
        ItemMatch from = ItemMatch.parse(fromItem);
        if (from == null) {
            fail(player, "'" + fromItem + "' is not a valid item id, pattern or #tag");
            return;
        }
        boolean removal = toItem.isEmpty();
        if (!removal && ResourceLocation.tryParse(toItem) == null) {
            fail(player, "'" + toItem + "' is not a valid item id");
            return;
        }
        // "" or "*" = every table; otherwise a pattern list ('!' excludes).
        String scopeText = tables.trim().equals("*") ? "" : tables.trim();
        TableScope scope = null;
        if (!scopeText.isEmpty()) {
            scope = TableScope.parse(scopeText);
            if (scope == null) {
                fail(player, "'" + scopeText + "' is not a valid loot table pattern list");
                return;
            }
        }
        RewriteConfig.LootItemReplacement replacement = removal ? null
                : new RewriteConfig.LootItemReplacement(from, ResourceLocation.parse(toItem),
                        scope, GuiLootSaver.FILE_NAME);
        RewriteConfig.LootItemRemoval remove = removal
                ? new RewriteConfig.LootItemRemoval(from, scope, GuiLootSaver.FILE_NAME) : null;

        // Persist first: if the config file can't be written, nothing changes
        // in memory either — otherwise the editor would show an edit that
        // silently vanishes on the next reload or restart.
        String error = removal
                ? GuiLootSaver.saveRemoveItems(fromItem, scopeText)
                : GuiLootSaver.saveReplaceItems(fromItem, toItem, scopeText);
        if (error != null) {
            fail(player, error);
            return;
        }

        int entriesChanged = 0;
        List<ResourceLocation> changedTables = new ArrayList<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : RewriteState.lootTableJsons.entrySet()) {
            if (!(entry.getValue() instanceof JsonObject table)
                    || (scope != null && !scope.matches(entry.getKey()))) {
                continue;
            }
            int changed = removal
                    ? LootRewriter.applyRemoval(table, remove)
                    : LootRewriter.applyReplacement(table, replacement);
            if (changed > 0) {
                entriesChanged += changed;
                changedTables.add(entry.getKey());
            }
        }

        // Mirror the rule into the in-memory config so the rebinds below (and
        // later editor saves) apply it to runtime-injected loot immediately.
        if (removal) {
            RewriteState.appendLootItemRemoval(remove);
        } else {
            RewriteState.appendLootItemReplacement(replacement);
        }
        // Also rebind tables whose runtime-injected loot matches: rebinding
        // re-runs the mod loot events plus the item rules over the result.
        Set<ResourceLocation> tagItems = from.tag() != null
                ? LootRewriter.tagContents(from.tag()) : Set.of();
        for (Holder.Reference<LootTable> holder : server.reloadableRegistries().get()
                .registryOrThrow(Registries.LOOT_TABLE).holders().toList()) {
            ResourceLocation tableId = holder.key().location();
            if (changedTables.contains(tableId) || (scope != null && !scope.matches(tableId))
                    || !(RewriteState.lootTableJsons.get(tableId) instanceof JsonObject)) {
                continue;
            }
            JsonObject runtime = runtimeTable(server, tableId, holder.value());
            boolean matches = runtime != null && (from.tag() != null
                    ? LootRewriter.containsTagOrItems(runtime, from.tag(), tagItems)
                    : LootRewriter.containsItemEntry(runtime, from.pattern()));
            if (matches) {
                changedTables.add(tableId);
            }
        }

        int applyFailures = 0;
        for (ResourceLocation tableId : changedTables) {
            if (RewriteState.lootTableJsons.get(tableId) instanceof JsonObject table) {
                LootLiveApplier.Parsed parsed = LootLiveApplier.parse(server, table);
                String bindError = parsed.error() != null ? parsed.error()
                        : LootLiveApplier.bind(server, tableId, parsed.table());
                if (bindError != null) {
                    applyFailures++;
                    Datarewriter.LOGGER.warn("Loot editor: could not live-apply {}: {}", tableId, bindError);
                }
            }
        }

        String verb = removal ? "Removed" : "Replaced";
        String summary = verb + " " + entriesChanged + " item entr" + (entriesChanged == 1 ? "y" : "ies")
                + " in " + changedTables.size() + " loot table" + (changedTables.size() == 1 ? "" : "s")
                + (removal ? "" : " with " + toItem)
                + (scope == null ? "" : " (tables: " + scopeText + ")")
                + " — rule saved to config/datarewriter/" + GuiLootSaver.FILE_NAME + " on the server.";
        if (applyFailures > 0) {
            summary += " (" + applyFailures + " table(s) could not be applied live, see log —"
                    + " they will after /reload)";
        }
        Datarewriter.LOGGER.info("Loot editor: bulk {} '{}'{}: {} entries in {} tables",
                removal ? "remove" : "replace", fromItem,
                removal ? "" : " -> '" + toItem + "'", entriesChanged, changedTables.size());
        succeed(player, summary);
    }

    private static JsonObject parseObject(String json) {
        try {
            return JsonParser.parseString(json) instanceof JsonObject obj ? obj : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void fail(ServerPlayer player, String message) {
        String text = "Loot table not saved — " + message;
        Datarewriter.LOGGER.warn("Loot editor ({}): {}", player.getGameProfile().getName(), text);
        player.sendSystemMessage(prefix().append(Component.literal(text).withStyle(ChatFormatting.RED)));
        if (ServerPlayNetworking.canSend(player, LootPayloads.SaveResult.TYPE)) {
            ServerPlayNetworking.send(player, new LootPayloads.SaveResult(false, text));
        }
    }

    private static void succeed(ServerPlayer player, String message) {
        String notice = GuiLootSaver.takeNotice();
        if (notice != null) {
            message += " (" + notice + ")";
        }
        player.sendSystemMessage(prefix().append(Component.literal(message).withStyle(ChatFormatting.GRAY)));
        if (ServerPlayNetworking.canSend(player, LootPayloads.SaveResult.TYPE)) {
            ServerPlayNetworking.send(player, new LootPayloads.SaveResult(true, message));
        }
    }

    private static MutableComponent prefix() {
        return Component.literal("[DataRewriter] ").withStyle(ChatFormatting.GOLD);
    }
}
