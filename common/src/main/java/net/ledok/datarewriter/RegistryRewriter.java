package net.ledok.datarewriter;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.serialization.Decoder;
import com.mojang.serialization.JsonOps;
import net.ledok.datarewriter.config.RewriteConfig;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.WritableRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies the config's {@code registries} rules while the world's datapack registries load
 * (Forbidden Arcanus rituals, enchantments, ...). Hooked from {@code RegistryDataLoaderMixin}:
 * removals cancel an entry's load, modifications and whole-entry replacements rewrite its JSON
 * before vanilla parses it, and additions are registered after the datapack files of each registry.
 * Datapack registries only load with the world, so none of this reacts to {@code /reload}.
 */
public final class RegistryRewriter {
    /**
     * The element decoder of every datapack registry seen loading this session, stashed by the mixin.
     * The decoders are the {@code RegistryData.elementCodec}s, so they are full codecs — the live
     * applier uses them to encode entries back to JSON for the ritual editor.
     */
    private static final Map<ResourceLocation, Decoder<?>> DECODERS = new ConcurrentHashMap<>();

    private static volatile int entriesRemoved;
    private static volatile int entriesModified;
    private static volatile int entriesReplaced;
    private static volatile int entriesAdded;
    private static volatile int entriesFailed;

    private RegistryRewriter() {
    }

    /**
     * Head of a registry load cycle. The dimension-only pass (level stems) that follows the main pass
     * within the same world start is left alone so the stats survive to the chat summary; any other
     * pass re-reads the config — registries load before the loot scan, so this is now the earliest
     * config reader of a world start.
     */
    public static void beginLoad(List<RegistryDataLoader.RegistryData<?>> registries) {
        boolean dimensionsOnly = registries.stream()
                .allMatch(data -> data.key().location().equals(Registries.LEVEL_STEM.location()));
        if (dimensionsOnly) {
            return;
        }
        RewriteState.reloadConfig();
        entriesRemoved = 0;
        entriesModified = 0;
        entriesReplaced = 0;
        entriesAdded = 0;
        entriesFailed = 0;
    }

    public static void stashDecoder(ResourceLocation registryId, Decoder<?> decoder) {
        DECODERS.put(registryId, decoder);
    }

    /** The element decoder (really the element codec) of a registry, once it has loaded this session. */
    public static @Nullable Decoder<?> decoder(ResourceLocation registryId) {
        return DECODERS.get(registryId);
    }

    /** True when a {@code registries.remove} rule matches — the entry's load is skipped entirely. */
    public static boolean removed(ResourceKey<?> key) {
        ResourceLocation registryId = key.registry();
        for (RewriteConfig.RegistryRemoval rule : RewriteState.config().registryRemovals()) {
            if (rule.matches(registryId, key.location())) {
                entriesRemoved++;
                Datarewriter.LOGGER.info("Registry rewrite: removed {} from {} ({})",
                        key.location(), registryId, rule.source());
                return true;
            }
        }
        return false;
    }

    /**
     * Rewrites one entry's freshly parsed JSON: a whole-entry replacement from {@code registries.add}
     * first, then every matching {@code registries.modify} merge. The result is validated with the
     * registry's own decoder — if it no longer parses, the original JSON is loaded instead and the
     * failure is logged, so a bad rule can't take the world down.
     */
    public static JsonElement rewriteElement(JsonElement parsed, ResourceKey<?> key,
                                             Decoder<?> decoder, RegistryOps<JsonElement> ops) {
        ResourceLocation registryId = key.registry();
        RewriteConfig config = RewriteState.config();
        JsonElement result = parsed;
        boolean replaced = false;
        for (RewriteConfig.RegistryAddition rule : config.registryAdditions()) {
            if (rule.registry().equals(registryId) && rule.id().equals(key.location())) {
                result = rule.entry().deepCopy();
                replaced = true;
            }
        }
        boolean modified = false;
        for (RewriteConfig.RegistryModification rule : config.registryModifications()) {
            if (rule.matches(registryId, key.location())) {
                if (result instanceof JsonObject target) {
                    deepMerge(target, rule.merge());
                    modified = true;
                } else {
                    Datarewriter.LOGGER.warn("Registry rewrite: cannot merge into {} of {} — "
                            + "the entry is not a JSON object ({})", key.location(), registryId, rule.source());
                }
            }
        }
        if (!replaced && !modified) {
            return parsed;
        }
        String error = validate(decoder, ops, result);
        if (error != null) {
            entriesFailed++;
            Datarewriter.LOGGER.error("Registry rewrite: {} of {} no longer parses after the config's "
                    + "rules — loading it unchanged: {}", key.location(), registryId, error);
            return parsed;
        }
        if (replaced) {
            entriesReplaced++;
            Datarewriter.LOGGER.info("Registry rewrite: replaced {} in {}", key.location(), registryId);
        }
        if (modified) {
            entriesModified++;
            Datarewriter.LOGGER.info("Registry rewrite: modified {} in {}", key.location(), registryId);
        }
        return result;
    }

    /**
     * Tail of one registry's load: {@code registries.add} entries whose id no datapack file provided
     * are registered here. A broken addition is logged and skipped — never handed to vanilla's error
     * collector, which would fail the whole world load.
     */
    @SuppressWarnings("unchecked")
    public static <E> void registerAdditions(WritableRegistry<E> registry, Decoder<E> decoder,
                                             RegistryOps.RegistryInfoLookup lookup) {
        ResourceLocation registryId = registry.key().location();
        RegistryOps<JsonElement> ops = null;
        for (RewriteConfig.RegistryAddition rule : RewriteState.config().registryAdditions()) {
            if (!rule.registry().equals(registryId)) {
                continue;
            }
            ResourceKey<E> key = ResourceKey.create((ResourceKey<? extends Registry<E>>) registry.key(), rule.id());
            if (registry.containsKey(rule.id())) {
                continue; // the datapack file existed; rewriteElement already replaced its content
            }
            if (ops == null) {
                ops = RegistryOps.create(JsonOps.INSTANCE, lookup);
            }
            try {
                E value = decoder.parse(ops, rule.entry()).getOrThrow();
                registry.register(key, value, RegistrationInfo.BUILT_IN);
                entriesAdded++;
                Datarewriter.LOGGER.info("Registry rewrite: added {} to {} ({})",
                        rule.id(), registryId, rule.source());
            } catch (Exception e) {
                entriesFailed++;
                Datarewriter.LOGGER.error("Registry rewrite: could not add {} to {} ({}): {}",
                        rule.id(), registryId, rule.source(), e.getMessage());
            }
        }
    }

    /**
     * Merges {@code merge} into {@code target} in place: objects merge recursively, everything else
     * (arrays included) replaces the old value, and a JSON null deletes the key.
     */
    static void deepMerge(JsonObject target, JsonObject merge) {
        for (String key : merge.keySet()) {
            JsonElement value = merge.get(key);
            if (value instanceof JsonNull) {
                target.remove(key);
            } else if (value instanceof JsonObject nested && target.get(key) instanceof JsonObject existing) {
                deepMerge(existing, nested);
            } else {
                target.add(key, value.deepCopy());
            }
        }
    }

    private static @Nullable String validate(Decoder<?> decoder, RegistryOps<JsonElement> ops,
                                             JsonElement json) {
        try {
            var result = decoder.parse(ops, json);
            return result.error().map(e -> e.message()).orElse(null);
        } catch (Throwable t) {
            return t.toString();
        }
    }

    /** Chat-summary fragment for {@code RewriteState.announce}. */
    public static String statsSummary() {
        String text = entriesModified + " modified, " + entriesReplaced + " replaced, "
                + entriesRemoved + " removed, " + entriesAdded + " added";
        if (entriesFailed > 0) {
            text += ", " + entriesFailed + " FAILED (see log)";
        }
        return text;
    }
}
