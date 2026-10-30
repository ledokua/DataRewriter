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
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
    private static final Map<Identifier, Decoder<?>> DECODERS = new ConcurrentHashMap<>();

    private static final AtomicInteger entriesRemoved = new AtomicInteger();
    private static final AtomicInteger entriesModified = new AtomicInteger();
    private static final AtomicInteger entriesReplaced = new AtomicInteger();
    private static final AtomicInteger entriesAdded = new AtomicInteger();
    private static final AtomicInteger entriesFailed = new AtomicInteger();

    private RegistryRewriter() {
    }

    /**
     * Head of a registry load cycle. The dimension-only pass (level stems) that follows the main pass
     * within the same world start is left alone so the stats survive to the chat summary; any other
     * pass re-reads the config — registries load before the loot scan, so this is now the earliest
     * config reader of a world start.
     */
    public static void beginLoad(List<RegistryDataLoader.RegistryData<?>> registries) {
        // Each registry's element codec, for validation and the ritual editor's encode/decode.
        for (RegistryDataLoader.RegistryData<?> data : registries) {
            DECODERS.put(data.key().identifier(), data.elementCodec());
        }
        boolean dimensionsOnly = registries.stream()
                .allMatch(data -> data.key().identifier().equals(Registries.LEVEL_STEM.identifier()));
        if (dimensionsOnly) {
            return;
        }
        RewriteState.reloadConfig();
        entriesRemoved.set(0);
        entriesModified.set(0);
        entriesReplaced.set(0);
        entriesAdded.set(0);
        entriesFailed.set(0);
    }

    /** The element decoder (really the element codec) of a registry, once it has loaded this session. */
    public static @Nullable Decoder<?> decoder(Identifier registryId) {
        return DECODERS.get(registryId);
    }

    /** True when a {@code registries.remove} rule matches — the entry's load is skipped entirely. */
    public static boolean removed(ResourceKey<?> key) {
        Identifier registryId = key.registry();
        for (RewriteConfig.RegistryRemoval rule : RewriteState.config().registryRemovals()) {
            if (rule.matches(registryId, key.identifier())) {
                entriesRemoved.incrementAndGet();
                Datarewriter.LOGGER.info("Registry rewrite: removed {} from {} ({})",
                        key.identifier(), registryId, rule.source());
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
        Identifier registryId = key.registry();
        RewriteConfig config = RewriteState.config();
        JsonElement result = parsed;
        boolean replaced = false;
        for (RewriteConfig.RegistryAddition rule : config.registryAdditions()) {
            if (rule.registry().equals(registryId) && rule.id().equals(key.identifier())) {
                result = rule.entry().deepCopy();
                replaced = true;
            }
        }
        boolean modified = false;
        for (RewriteConfig.RegistryModification rule : config.registryModifications()) {
            if (rule.matches(registryId, key.identifier())) {
                if (result instanceof JsonObject target) {
                    deepMerge(target, rule.merge());
                    modified = true;
                } else {
                    Datarewriter.LOGGER.warn("Registry rewrite: cannot merge into {} of {} — "
                            + "the entry is not a JSON object ({})", key.identifier(), registryId, rule.source());
                }
            }
        }
        if (!replaced && !modified) {
            return parsed;
        }
        String error = validate(decoder, ops, result);
        if (error != null) {
            entriesFailed.incrementAndGet();
            Datarewriter.LOGGER.error("Registry rewrite: {} of {} no longer parses after the config's "
                    + "rules — loading it unchanged: {}", key.identifier(), registryId, error);
            return parsed;
        }
        if (replaced) {
            entriesReplaced.incrementAndGet();
            Datarewriter.LOGGER.info("Registry rewrite: replaced {} in {}", key.identifier(), registryId);
        }
        if (modified) {
            entriesModified.incrementAndGet();
            Datarewriter.LOGGER.info("Registry rewrite: modified {} in {}", key.identifier(), registryId);
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
        Identifier registryId = registry.key().identifier();
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
                entriesAdded.incrementAndGet();
                Datarewriter.LOGGER.info("Registry rewrite: added {} to {} ({})",
                        rule.id(), registryId, rule.source());
            } catch (Exception e) {
                entriesFailed.incrementAndGet();
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
        String text = entriesModified.get() + " modified, " + entriesReplaced.get() + " replaced, "
                + entriesRemoved.get() + " removed, " + entriesAdded.get() + " added";
        if (entriesFailed.get() > 0) {
            text += ", " + entriesFailed.get() + " FAILED (see log)";
        }
        return text;
    }
}
