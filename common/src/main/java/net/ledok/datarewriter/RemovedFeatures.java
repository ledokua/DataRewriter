package net.ledok.datarewriter;

import net.ledok.datarewriter.config.RewriteConfig;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code worldgen.remove_features}, resolved: the placed feature instances whose biome placement is
 * skipped (PlacedFeatureMixin). Checking at placement time rather than editing biome JSON also catches
 * features that Fabric's BiomeModifications or NeoForge's biome modifiers add after the biomes load.
 * Resolved lazily against the generating world's registries, again whenever the config was reloaded —
 * worldgen starts before SERVER_STARTED (spawn chunks), so this can't wait for a lifecycle event.
 */
public final class RemovedFeatures {
    private record Resolved(RewriteConfig config, Set<PlacedFeature> features) {
    }

    private static volatile Resolved resolved;

    private RemovedFeatures() {
    }

    /** Is this placed feature switched off? Called once per feature per chunk — a plain set lookup. */
    public static boolean isRemoved(PlacedFeature feature, RegistryAccess registries) {
        RewriteConfig config = RewriteState.config();
        if (config.featureRemovals().isEmpty()) {
            return false;
        }
        Resolved current = resolved;
        if (current == null || current.config() != config) {
            current = resolve(config, registries);
            resolved = current; // racing worker threads resolve the same set — harmless
        }
        return current.features().contains(feature);
    }

    private static Resolved resolve(RewriteConfig config, RegistryAccess registries) {
        Registry<PlacedFeature> registry = registries.registryOrThrow(Registries.PLACED_FEATURE);
        Set<PlacedFeature> features = Collections.newSetFromMap(new IdentityHashMap<>());
        for (RewriteConfig.FeatureRemoval rule : config.featureRemovals()) {
            int before = features.size();
            for (Map.Entry<ResourceKey<PlacedFeature>, PlacedFeature> entry : registry.entrySet()) {
                if (rule.feature().matches(entry.getKey().location())) {
                    features.add(entry.getValue());
                }
            }
            if (features.size() == before) {
                Datarewriter.LOGGER.warn("[{}] worldgen remove_features: '{}' matches no placed feature",
                        rule.source(), rule.feature());
            }
        }
        List<String> ids = features.stream().map(f -> String.valueOf(registry.getKey(f))).sorted().toList();
        Datarewriter.LOGGER.info("Worldgen: {} placed features no longer generate: {}", ids.size(), ids);
        return new Resolved(config, Set.copyOf(features));
    }
}
