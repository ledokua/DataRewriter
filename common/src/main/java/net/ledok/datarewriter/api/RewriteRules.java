package net.ledok.datarewriter.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.ledok.datarewriter.config.ConfigLoader;
import net.ledok.datarewriter.config.IdPattern;
import net.ledok.datarewriter.config.ItemMatch;
import net.ledok.datarewriter.config.RewriteConfig;
import net.ledok.datarewriter.config.TableScope;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Rules contributed from code instead of a config file. A provider is asked for its rules on every
 * config (re)load — server start and {@code /reload} — so it may depend on state that changes between
 * loads. Contributed rules go through exactly the same pipeline as file rules (same phases, same tag
 * timing, same live-edit interplay) and show up in {@code /datarewriter status} as {@code addon <source>}.
 * A provider that throws contributes nothing for that load and counts as one config error.
 */
public final class RewriteRules {
    /** One registered provider; {@code source} is the label shown in status and error messages. */
    public record Provider(String source, Consumer<Builder> rules) {
    }

    private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();

    private RewriteRules() {
    }

    /**
     * @param source a short label for status and error messages — your mod id is a good choice
     * @param rules  called with a fresh {@link Builder} on every config load
     */
    public static void addProvider(String source, Consumer<Builder> rules) {
        PROVIDERS.add(new Provider(Objects.requireNonNull(source, "source"), Objects.requireNonNull(rules, "rules")));
    }

    public static List<Provider> providers() {
        return Collections.unmodifiableList(PROVIDERS);
    }

    /**
     * Collects one provider's rules. Every method validates its arguments the way the config parser
     * does and throws {@link IllegalArgumentException} on bad input; the operations map one-to-one onto
     * the config file's {@code recipes.*} and {@code loot_tables.*} lists (see the README).
     */
    public static final class Builder {
        private final String source;
        private final List<RewriteConfig.RemovalRule> removals = new ArrayList<>();
        private final List<RewriteConfig.AddedRecipe> additions = new ArrayList<>();
        private final List<RewriteConfig.IngredientReplacement> ingredientReplacements = new ArrayList<>();
        private final List<RewriteConfig.LootRule> lootRemovals = new ArrayList<>();
        private final List<RewriteConfig.AddedLootTable> lootAdditions = new ArrayList<>();
        private final List<RewriteConfig.LootModification> lootModifications = new ArrayList<>();
        private final List<RewriteConfig.LootItemReplacement> lootItemReplacements = new ArrayList<>();
        private final List<RewriteConfig.LootItemRemoval> lootItemRemovals = new ArrayList<>();
        private final List<RewriteConfig.RegistryRemoval> registryRemovals = new ArrayList<>();
        private final List<RewriteConfig.RegistryAddition> registryAdditions = new ArrayList<>();
        private final List<RewriteConfig.RegistryModification> registryModifications = new ArrayList<>();
        private final List<RewriteConfig.DisabledItem> disabledItems = new ArrayList<>();
        private final List<RewriteConfig.FeatureRemoval> featureRemovals = new ArrayList<>();

        public Builder(String source) {
            this.source = source;
        }

        // ---- recipes ----

        /** {@code recipes.remove} by id — exact, or with {@code *} wildcards ({@code "minecraft:*_from_smelting"}). */
        public Builder removeRecipes(String idPattern) {
            return removeRecipes(idPattern, null, null, null, null);
        }

        /** {@code recipes.remove} every recipe of one mod. */
        public Builder removeRecipesByMod(String mod) {
            return removeRecipes(null, mod, null, null, null);
        }

        /**
         * {@code recipes.remove} with the full condition set; all non-null conditions must match (AND).
         * {@code output} and {@code input} take an item id or a {@code "#tag"}; those and {@code type}
         * need parsed recipes, so they apply after startup / {@code /reload} completes, like in a file.
         */
        public Builder removeRecipes(@Nullable String idPattern, @Nullable String mod, @Nullable ResourceLocation type,
                                     @Nullable String output, @Nullable String input) {
            if (idPattern == null && mod == null && type == null && output == null && input == null) {
                throw new IllegalArgumentException("a recipe removal needs at least one of id, mod, type, output, input");
            }
            removals.add(new RewriteConfig.RemovalRule(idPattern == null ? null : idPattern(idPattern), mod, type,
                    output == null ? null : itemOrTag(output), input == null ? null : itemOrTag(input), source));
            return this;
        }

        /** {@code recipes.add}: a recipe in vanilla JSON format; an existing id is replaced. */
        public Builder addRecipe(ResourceLocation id, JsonObject recipeJson) {
            additions.add(new RewriteConfig.AddedRecipe(Objects.requireNonNull(id), recipeJson.deepCopy()));
            return this;
        }

        /** {@code recipes.replace_ingredients}: {@code from} is an item id ({@code *} wildcards) or {@code #tag}, {@code to} an item id or {@code #tag}. */
        public Builder replaceIngredients(String from, String to) {
            ingredientReplacements.add(new RewriteConfig.IngredientReplacement(itemMatch(from), itemOrTag(to), source));
            return this;
        }

        // ---- loot tables ----

        /** {@code loot_tables.remove} by id pattern — matching tables drop nothing. */
        public Builder removeLootTables(String idPattern) {
            lootRemovals.add(new RewriteConfig.LootRule(idPattern(idPattern), null, source));
            return this;
        }

        /** {@code loot_tables.remove} every table of one mod. */
        public Builder removeLootTablesByMod(String mod) {
            lootRemovals.add(new RewriteConfig.LootRule(null, Objects.requireNonNull(mod), source));
            return this;
        }

        /** {@code loot_tables.add}: a table in vanilla JSON format; an existing id is replaced. */
        public Builder addLootTable(ResourceLocation id, JsonObject tableJson) {
            lootAdditions.add(new RewriteConfig.AddedLootTable(Objects.requireNonNull(id), tableJson.deepCopy(), source));
            return this;
        }

        /** {@code loot_tables.modify}: appends {@code pools} to every table matching the id pattern. */
        public Builder modifyLootTables(String idPattern, JsonArray pools) {
            return modifyLootTables(idPattern, null, pools);
        }

        /** {@code loot_tables.modify} with both conditions (AND, either may be null but not both). */
        public Builder modifyLootTables(@Nullable String idPattern, @Nullable String mod, JsonArray pools) {
            if (idPattern == null && mod == null) {
                throw new IllegalArgumentException("a loot modification needs an id pattern or a mod");
            }
            if (pools.isEmpty()) {
                throw new IllegalArgumentException("a loot modification needs at least one pool");
            }
            lootModifications.add(new RewriteConfig.LootModification(
                    new RewriteConfig.LootRule(idPattern == null ? null : idPattern(idPattern), mod, source), pools.deepCopy()));
            return this;
        }

        /**
         * {@code loot_tables.replace_items}: every entry matching {@code from} (item id with {@code *}
         * wildcards, or {@code #tag}) drops {@code to} instead. {@code tables} is the scope as in the config
         * ({@code "minecraft:chests/*, !*:entities/*"}); null or {@code "*"} means every table.
         */
        public Builder replaceLootItems(String from, ResourceLocation to, @Nullable String tables) {
            lootItemReplacements.add(new RewriteConfig.LootItemReplacement(itemMatch(from), Objects.requireNonNull(to),
                    tableScope(tables), source));
            return this;
        }

        /** {@code loot_tables.remove_items}: deletes every entry matching {@code item}; {@code tables} as in {@link #replaceLootItems}. */
        public Builder removeLootItems(String item, @Nullable String tables) {
            lootItemRemovals.add(new RewriteConfig.LootItemRemoval(itemMatch(item), tableScope(tables), source));
            return this;
        }

        // ---- datapack registries ----

        /**
         * {@code registries.remove}: skips loading matching entries ({@code *} wildcards) of the
         * datapack registry. Applied at world load, never on {@code /reload}; removing an entry other
         * data references by id fails world loading, like a datapack deleting the file would.
         */
        public Builder removeRegistryEntries(ResourceLocation registry, String idPattern) {
            registryRemovals.add(new RewriteConfig.RegistryRemoval(Objects.requireNonNull(registry),
                    idPattern(idPattern), source));
            return this;
        }

        /** {@code registries.add}: an entry in the registry's own JSON format; an existing id is replaced. Applied at world load. */
        public Builder addRegistryEntry(ResourceLocation registry, ResourceLocation id, JsonObject entry) {
            registryAdditions.add(new RewriteConfig.RegistryAddition(Objects.requireNonNull(registry),
                    Objects.requireNonNull(id), entry.deepCopy(), source));
            return this;
        }

        /**
         * {@code registries.modify}: merges {@code merge} into matching entries ({@code *} wildcards) —
         * objects merge recursively, everything else is replaced, JSON null deletes a key. Applied at
         * world load; an entry that no longer parses loads unchanged.
         */
        public Builder modifyRegistryEntries(ResourceLocation registry, String idPattern, JsonObject merge) {
            if (merge.isEmpty()) {
                throw new IllegalArgumentException("a registry modification needs a non-empty merge object");
            }
            registryModifications.add(new RewriteConfig.RegistryModification(Objects.requireNonNull(registry),
                    idPattern(idPattern), merge.deepCopy(), source));
            return this;
        }

        // ---- items ----

        /**
         * {@code items.disable}: takes every item matching {@code item} ({@code *} wildcards) out of the game —
         * recipes making it are removed, recipes, loot, trades and existing stacks get {@code replaceWith}
         * instead (or lose it when null), and it leaves every item tag. {@code lootRemove} deletes its loot
         * entries even with a replacement, for tables that already drop the survivor too;
         * {@code redirectRecipes} keeps the recipes making it, producing {@code replaceWith} instead.
         */
        public Builder disableItem(String item, @Nullable ResourceLocation replaceWith, boolean lootRemove,
                                   boolean redirectRecipes) {
            if (redirectRecipes && replaceWith == null) {
                throw new IllegalArgumentException("redirecting recipes needs a replacement item");
            }
            if (item.startsWith("#")) {
                throw new IllegalArgumentException("items can only be disabled by id or '*' pattern, not by #tag");
            }
            disabledItems.add(new RewriteConfig.DisabledItem(itemMatch(item), replaceWith, lootRemove,
                    redirectRecipes, source));
            return this;
        }

        /** {@link #disableItem(String, ResourceLocation, boolean, boolean)}: loot swapped, recipes making it removed. */
        public Builder disableItem(String item, @Nullable ResourceLocation replaceWith) {
            return disableItem(item, replaceWith, false, false);
        }

        // ---- worldgen ----

        /**
         * {@code worldgen.remove_features}: placed features matching {@code idPattern} ({@code *} wildcards)
         * stop generating in every biome, in chunks generated from now on.
         */
        public Builder removeFeatures(String idPattern) {
            featureRemovals.add(new RewriteConfig.FeatureRemoval(idPattern(idPattern), source));
            return this;
        }

        /** The collected rules as one config fragment (error count 0). */
        public RewriteConfig build() {
            return new RewriteConfig(List.copyOf(removals), List.copyOf(additions), List.copyOf(ingredientReplacements),
                    List.copyOf(lootRemovals), List.copyOf(lootAdditions), List.copyOf(lootModifications),
                    List.copyOf(lootItemReplacements), List.copyOf(lootItemRemovals),
                    List.copyOf(registryRemovals), List.copyOf(registryAdditions),
                    List.copyOf(registryModifications), List.copyOf(disabledItems), List.copyOf(featureRemovals), 0);
        }

        private static IdPattern idPattern(String value) {
            IdPattern pattern = IdPattern.parse(Objects.requireNonNull(value));
            if (pattern == null) {
                throw new IllegalArgumentException("'" + value + "' is not a valid id or pattern");
            }
            return pattern;
        }

        private static ItemMatch itemMatch(String value) {
            ItemMatch match = ItemMatch.parse(Objects.requireNonNull(value));
            if (match == null) {
                throw new IllegalArgumentException("'" + value + "' is not a valid item id, pattern or #tag");
            }
            return match;
        }

        private static String itemOrTag(String value) {
            String normalized = ConfigLoader.normalizeItemOrTag(Objects.requireNonNull(value));
            if (normalized == null) {
                throw new IllegalArgumentException("'" + value + "' is not a valid item id or #tag");
            }
            return normalized;
        }

        private static @Nullable TableScope tableScope(@Nullable String tables) {
            if (tables == null || tables.isBlank() || tables.trim().equals("*")) {
                return null;
            }
            TableScope scope = TableScope.parse(tables);
            if (scope == null) {
                throw new IllegalArgumentException("'" + tables + "' is not a valid loot table pattern list");
            }
            return scope;
        }
    }
}
