package net.ledok.datarewriter.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public record RewriteConfig(List<RemovalRule> removals, List<AddedRecipe> additions,
                            List<IngredientReplacement> ingredientReplacements,
                            List<LootRule> lootRemovals, List<AddedLootTable> lootAdditions,
                            List<LootModification> lootModifications,
                            List<LootItemReplacement> lootItemReplacements,
                            List<LootItemRemoval> lootItemRemovals,
                            List<RegistryRemoval> registryRemovals,
                            List<RegistryAddition> registryAdditions,
                            List<RegistryModification> registryModifications,
                            int errorCount) {
    public static final RewriteConfig EMPTY =
            new RewriteConfig(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(), List.of(), 0);

    /**
     * Skips loading every entry of the datapack registry {@code registry} whose id matches (with '*'
     * wildcards). Applied while the world's registries load — never on /reload. An entry another data
     * file references by id must not be removed: the reference cannot resolve and world loading fails,
     * exactly as if a datapack had deleted the file.
     */
    public record RegistryRemoval(ResourceLocation registry, IdPattern id, String source) {
        public boolean matches(ResourceLocation registryId, ResourceLocation entryId) {
            return this.registry.equals(registryId) && this.id.matches(entryId);
        }
    }

    /**
     * A new (or replacement) entry of the datapack registry {@code registry}, in that registry's own
     * JSON format. An existing id is replaced wholesale; a new id is registered after the datapack
     * files. Applied while the world's registries load — never on /reload.
     */
    public record RegistryAddition(ResourceLocation registry, ResourceLocation id, JsonElement entry,
                                   String source) {
    }

    /**
     * Merges {@code merge} into every entry of {@code registry} whose id matches (with '*' wildcards):
     * objects merge recursively, everything else (arrays included) is replaced, and a JSON null deletes
     * the key. An entry that no longer parses after the merge is loaded unchanged and the failure is
     * logged. Applied while the world's registries load — never on /reload.
     */
    public record RegistryModification(ResourceLocation registry, IdPattern id, JsonObject merge,
                                       String source) {
        public boolean matches(ResourceLocation registryId, ResourceLocation entryId) {
            return this.registry.equals(registryId) && this.id.matches(entryId);
        }
    }

    /** A new (or replacement) recipe in vanilla recipe JSON format. */
    public record AddedRecipe(ResourceLocation id, JsonElement json) {
    }

    /**
     * Rewrites every recipe ingredient matching 'from' (an item id with '*'
     * wildcards, or a '#tag' — items in the tag plus identical tag refs) to
     * 'to' (an item id or a '#tag'). Result/output slots are left alone.
     */
    public record IngredientReplacement(ItemMatch from, String to, String source) {
    }

    /** A new (or replacement) loot table in vanilla loot table JSON format. */
    public record AddedLootTable(ResourceLocation id, JsonElement json, String source) {
        public AddedLootTable(ResourceLocation id, JsonElement json) {
            this(id, json, "?");
        }
    }

    /**
     * Injects extra pools into every loot table matching the target rule,
     * leaving the table's own loot untouched. Applied after remove/add.
     */
    public record LootModification(LootRule target, JsonArray pools) {
    }

    /**
     * Rewrites every item entry matching 'from' (wildcards, or a '#tag' —
     * items in the tag plus identical tag entries) to drop 'to' instead, in
     * every loot table matching 'table' (null = all tables). Runs after
     * remove/add/modify, so user-added pools are covered too.
     */
    public record LootItemReplacement(ItemMatch from, ResourceLocation to, @Nullable TableScope table,
                                      String source) {
    }

    /**
     * Deletes every item entry matching 'item' (wildcards or '#tag', like in
     * replacements) from every loot table matching 'table' (null = all
     * tables). The rest of the table is untouched.
     */
    public record LootItemRemoval(ItemMatch item, @Nullable TableScope table, String source) {
    }

    /**
     * One loot table removal rule; matching tables are emptied so they drop
     * nothing. All non-null conditions must match (AND); id supports '*'
     * wildcards, e.g. "minecraft:chests/*".
     */
    public record LootRule(@Nullable IdPattern id, @Nullable String mod, String source) {
        public boolean matches(ResourceLocation tableId) {
            if (id != null && !id.matches(tableId)) {
                return false;
            }
            return mod == null || mod.equals(tableId.getNamespace());
        }
    }

    /**
     * One removal rule. All non-null conditions must match (AND).
     * Rules with only id/mod can be applied on the raw JSON map;
     * output/input/type need the parsed recipe and bound tags.
     */
    public record RemovalRule(
            @Nullable IdPattern id,
            @Nullable String mod,
            @Nullable ResourceLocation type,
            @Nullable String output,
            @Nullable String input,
            String source // file the rule came from, for error messages
    ) {
        public boolean needsParsedRecipe() {
            return output != null || input != null || type != null;
        }

        /** Matching for the raw JSON phase; only used for rules where needsParsedRecipe() is false. */
        public boolean matchesId(ResourceLocation recipeId) {
            if (id != null && !id.matches(recipeId)) {
                return false;
            }
            return mod == null || mod.equals(recipeId.getNamespace());
        }

        /** Full matching against a parsed recipe. Runs after tags are bound. */
        public boolean matches(RecipeHolder<?> holder, RegistryAccess registries) {
            ResourceLocation recipeId = holder.id();
            if (id != null && !id.matches(recipeId)) {
                return false;
            }
            if (mod != null && !mod.equals(recipeId.getNamespace())) {
                return false;
            }
            Recipe<?> recipe = holder.value();
            if (type != null && !type.equals(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()))) {
                return false;
            }
            if (output != null && !outputMatches(recipe, registries)) {
                return false;
            }
            return input == null || inputMatches(recipe, registries);
        }

        private boolean outputMatches(Recipe<?> recipe, RegistryAccess registries) {
            ItemStack result;
            try {
                result = recipe.getResultItem(registries);
            } catch (Exception e) {
                return false; // some modded recipes throw for context-dependent results
            }
            if (result == null || result.isEmpty()) {
                return false;
            }
            if (output.startsWith("#")) {
                TagKey<Item> tag = TagKey.create(Registries.ITEM, ResourceLocation.parse(output.substring(1)));
                return result.is(tag);
            }
            return BuiltInRegistries.ITEM.getKey(result.getItem()).equals(ResourceLocation.parse(output));
        }

        private boolean inputMatches(Recipe<?> recipe, RegistryAccess registries) {
            List<ItemStack> probes = new ArrayList<>();
            if (input.startsWith("#")) {
                TagKey<Item> tag = TagKey.create(Registries.ITEM, ResourceLocation.parse(input.substring(1)));
                registries.registryOrThrow(Registries.ITEM).getTag(tag).ifPresent(holders -> {
                    for (Holder<Item> holder : holders) {
                        probes.add(new ItemStack(holder));
                    }
                });
            } else {
                Optional<Item> item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(input));
                item.ifPresent(value -> probes.add(new ItemStack(value)));
            }
            if (probes.isEmpty()) {
                return false;
            }
            try {
                for (Ingredient ingredient : recipe.getIngredients()) {
                    for (ItemStack probe : probes) {
                        if (ingredient.test(probe)) {
                            return true;
                        }
                    }
                }
            } catch (Exception e) {
                return false; // be defensive about modded ingredient implementations
            }
            return false;
        }
    }
}
