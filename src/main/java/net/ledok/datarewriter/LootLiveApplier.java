package net.ledok.datarewriter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.loot.v3.FabricLootTableBuilder;
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.fabricmc.fabric.api.loot.v3.LootTableSource;
import net.ledok.datarewriter.config.RewriteConfig;
import net.ledok.datarewriter.mixin.HolderReferenceInvoker;
import net.ledok.datarewriter.mixin.MappedRegistryAccessor;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * Applies a loot table edit to the running server without a data reload.
 * Loot tables live in the frozen reloadable registry; every lookup goes
 * through the table's Holder.Reference, so rebinding that holder (what
 * vanilla itself does during load) swaps the table everywhere at once.
 * Brand-new ids are registered by briefly unfreezing the registry.
 */
public final class LootLiveApplier {
    private LootLiveApplier() {
    }

    /** Either an error message or the parsed table. */
    public record Parsed(String error, LootTable table) {
    }

    /** Validates table JSON with the vanilla loot table codec. */
    public static Parsed parse(MinecraftServer server, JsonObject tableJson) {
        try {
            DataResult<LootTable> result = LootTable.DIRECT_CODEC.parse(
                    RegistryOps.create(JsonOps.INSTANCE, server.registryAccess()), tableJson);
            if (result.error().isPresent()) {
                return new Parsed("rejected by the loot table parser: "
                        + result.error().get().message(), null);
            }
            return new Parsed(null, result.result().orElseThrow());
        } catch (Throwable t) {
            // Badly-behaved mod codecs can throw instead of returning an
            // error result — report it like any other rejection.
            return new Parsed("rejected by the loot table parser: " + t, null);
        }
    }

    /**
     * Applies mod loot events + the config's item rules to the table, then
     * swaps it into the live registry. Returns an error or null.
     */
    public static String bind(MinecraftServer server, ResourceLocation id, LootTable table) {
        table = applyModEvents(server, id, table);
        return bindRaw(server, id, table);
    }

    /** Swaps (or registers) the table in the live registry, exactly as given. */
    public static String bindRaw(MinecraftServer server, ResourceLocation id, LootTable table) {
        RewriteState.runtimeTableJsons.remove(id); // the encoded snapshot is stale now
        Registry<LootTable> registry = server.reloadableRegistries().get()
                .registryOrThrow(Registries.LOOT_TABLE);
        if (!(registry instanceof MappedRegistry<LootTable> mapped)) {
            return "cannot apply live: the loot registry is a " + registry.getClass().getName()
                    + " (changes still apply after /reload)";
        }
        @SuppressWarnings("unchecked")
        MappedRegistryAccessor<LootTable> accessor = (MappedRegistryAccessor<LootTable>) (Object) mapped;
        var existing = mapped.getHolder(id);
        if (existing.isPresent()) {
            Holder.Reference<LootTable> holder = existing.get();
            LootTable old = holder.value();
            @SuppressWarnings("unchecked")
            HolderReferenceInvoker<LootTable> invoker = (HolderReferenceInvoker<LootTable>) (Object) holder;
            invoker.datarewriter$bindValue(table);
            // Keep the value-keyed maps consistent with the new instance.
            if (accessor.datarewriter$getByValue().remove(old) != null) {
                accessor.datarewriter$getByValue().put(table, holder);
            }
            var toId = accessor.datarewriter$getToId();
            if (toId.containsKey(old)) {
                toId.put(table, toId.removeInt(old));
            }
        } else {
            accessor.datarewriter$setFrozen(false);
            try {
                mapped.register(ResourceKey.create(Registries.LOOT_TABLE, id), table,
                        RegistrationInfo.BUILT_IN);
            } finally {
                accessor.datarewriter$setFrozen(true);
            }
        }
        return null;
    }

    /**
     * Re-runs Fabric's loot table events on the freshly built table, exactly
     * like a real reload does after parsing — so mods that inject loot at
     * runtime (RPG-series equipment injectors and the like) keep their
     * additions when a table is swapped live. The editor's JSON cache stays
     * at the datapack level, so replay always starts from the base table and
     * injections never stack up. Best-effort: on any failure the plain table
     * is used (a /reload then restores the injections).
     */
    private static LootTable applyModEvents(MinecraftServer server, ResourceLocation id, LootTable table) {
        try {
            ResourceKey<LootTable> key = ResourceKey.create(Registries.LOOT_TABLE, id);
            LootTableSource source = id.getNamespace().equals("minecraft")
                    ? LootTableSource.VANILLA : LootTableSource.MOD;
            LootTable replaced = LootTableEvents.REPLACE.invoker()
                    .replaceLootTable(key, table, source, server.registryAccess());
            if (replaced != null) {
                table = replaced;
                source = LootTableSource.REPLACED;
            }
            LootTable.Builder builder = FabricLootTableBuilder.copyOf(table);
            LootTableEvents.MODIFY.invoker().modifyLootTable(key, builder, source, server.registryAccess());
            // The config's item rules run LAST, over injected loot too — same
            // order as a real reload with the post-injection pass.
            return applyItemOps(server, id, builder.build());
        } catch (Throwable t) {
            Datarewriter.LOGGER.warn("Loot editor: could not re-run mod loot injections for {}: {}",
                    id, t.toString());
            return table;
        }
    }

    /**
     * Applies the config's replace_items/remove_items rules to a built table
     * (codec round-trip), covering entries other mods injected at runtime.
     * Returns the table unchanged when no rule matches.
     */
    public static LootTable applyItemOps(MinecraftServer server, ResourceLocation id, LootTable table) {
        RewriteConfig config = RewriteState.config();
        if (config.lootItemReplacements().isEmpty() && config.lootItemRemovals().isEmpty()) {
            return table;
        }
        JsonObject json = encode(server, table);
        if (json == null) {
            return table;
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
            return table;
        }
        Parsed parsed = parse(server, json);
        return parsed.error() == null ? parsed.table() : table;
    }

    /** The table encoded back to JSON with the vanilla codec, or null. */
    public static JsonObject encode(MinecraftServer server, LootTable table) {
        try {
            DataResult<JsonElement> result = LootTable.DIRECT_CODEC.encodeStart(
                    RegistryOps.create(JsonOps.INSTANCE, server.registryAccess()), table);
            return result.result().orElse(null) instanceof JsonObject obj ? obj : null;
        } catch (Throwable t) {
            // Some mods' custom loot entries cannot round-trip through the
            // codec (e.g. an entry class that claims a vanilla type and then
            // fails the cast). Treat such tables as opaque instead of
            // crashing whatever asked for the JSON.
            return null;
        }
    }

    /** Parse + bind + cache update in one step. Returns an error or null. */
    public static String apply(MinecraftServer server, ResourceLocation id, JsonObject tableJson) {
        Parsed parsed = parse(server, tableJson);
        if (parsed.error() != null) {
            return parsed.error();
        }
        String error = bind(server, id, parsed.table());
        if (error != null) {
            return error;
        }
        RewriteState.lootTableJsons.put(id, tableJson);
        return null;
    }
}
