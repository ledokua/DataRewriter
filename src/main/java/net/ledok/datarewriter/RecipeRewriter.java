package net.ledok.datarewriter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.ledok.datarewriter.config.RewriteConfig;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class RecipeRewriter {
    // Recipes we added/replaced this load; removal rules never touch these,
    // so "remove everything from minecraft:" + a replacement recipe works.
    private static final Set<ResourceLocation> addedIds = ConcurrentHashMap.newKeySet();

    private RecipeRewriter() {
    }

    /**
     * JSON phase. Called from the RecipeManager mixin at the start of recipe
     * loading (runs on every data reload, including /reload). Handles id/mod
     * removals and injects added recipes so vanilla parses them normally.
     */
    public static void rewriteJson(Map<ResourceLocation, JsonElement> recipeJsons) {
        RewriteConfig loaded = RewriteState.config();
        addedIds.clear();

        List<RewriteConfig.RemovalRule> jsonRules = loaded.removals().stream()
                .filter(rule -> !rule.needsParsedRecipe())
                .toList();
        int removed = 0;
        if (!jsonRules.isEmpty()) {
            Iterator<ResourceLocation> iterator = recipeJsons.keySet().iterator();
            while (iterator.hasNext()) {
                ResourceLocation recipeId = iterator.next();
                for (RewriteConfig.RemovalRule rule : jsonRules) {
                    if (rule.matchesId(recipeId)) {
                        iterator.remove();
                        removed++;
                        break;
                    }
                }
            }
        }

        int replaced = 0;
        for (RewriteConfig.AddedRecipe addition : loaded.additions()) {
            if (recipeJsons.put(addition.id(), addition.json()) != null) {
                replaced++;
            }
            addedIds.add(addition.id());
        }

        RewriteState.recipesRemovedJson = removed;
        RewriteState.recipesRemovedParsed = 0;
        RewriteState.recipesAdded = loaded.additions().size();
        RewriteState.recipesReplaced = replaced;
        // Kept for the post-load ingredient-replacement pass (and any future
        // JSON-level consumer): the exact map vanilla is about to parse.
        RewriteState.recipeJsons = new ConcurrentHashMap<>(recipeJsons);

        if (removed + loaded.additions().size() > 0) {
            Datarewriter.LOGGER.info("Removed {} recipes by id/mod, added {} ({} replacing existing ones)",
                    removed, loaded.additions().size(), replaced);
        }
    }

    /**
     * Adds (or replaces) one already-parsed recipe in the running RecipeManager
     * and re-syncs clients — no data reload. Used by the recipe editor; the
     * recipe is also persisted to the config, so the next real reload produces
     * the same state.
     */
    public static void applyLive(MinecraftServer server, RecipeHolder<?> holder) {
        RecipeManager recipeManager = server.getRecipeManager();
        List<RecipeHolder<?>> recipes = new ArrayList<>(recipeManager.getRecipes().size() + 1);
        for (RecipeHolder<?> existing : recipeManager.getRecipes()) {
            if (!existing.id().equals(holder.id())) {
                recipes.add(existing);
            }
        }
        recipes.add(holder);
        recipeManager.replaceRecipes(recipes);
        addedIds.add(holder.id());

        ClientboundUpdateRecipesPacket packet = new ClientboundUpdateRecipesPacket(recipeManager.getRecipes());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.connection.send(packet);
        }
    }

    /**
     * Parsed phase. Called after server start and after /reload, once tags
     * are bound: output/input/type removal rules, then ingredient
     * replacements.
     */
    public static void applyParsedRules(MinecraftServer server) {
        applyRemovalRules(server);
        applyIngredientReplacements(server);
    }

    /**
     * Applies removal rules matching by output/input/type to the running
     * RecipeManager. Returns how many recipes this call removed (also called
     * for a fresh GUI-saved rule — earlier rules already removed their
     * matches, so the count is that rule's own).
     */
    public static int applyRemovalRules(MinecraftServer server) {
        List<RewriteConfig.RemovalRule> rules = RewriteState.config().removals().stream()
                .filter(RewriteConfig.RemovalRule::needsParsedRecipe)
                .toList();
        if (rules.isEmpty()) {
            return 0;
        }

        RecipeManager recipeManager = server.getRecipeManager();
        Collection<RecipeHolder<?>> all = recipeManager.getRecipes();
        List<RecipeHolder<?>> kept = new ArrayList<>(all.size());
        int removed = 0;
        for (RecipeHolder<?> holder : all) {
            boolean matched = false;
            if (!addedIds.contains(holder.id())) {
                for (RewriteConfig.RemovalRule rule : rules) {
                    if (rule.matches(holder, server.registryAccess())) {
                        matched = true;
                        break;
                    }
                }
            }
            if (matched) {
                removed++;
            } else {
                kept.add(holder);
            }
        }

        RewriteState.recipesRemovedParsed += removed;
        Datarewriter.LOGGER.info("Removed {} recipes by output/input/type rules", removed);
        if (removed == 0) {
            return 0;
        }
        recipeManager.replaceRecipes(kept);
        syncRecipes(server);
        return removed;
    }

    /**
     * Applies the config's replace_ingredients rules: rewrites the cached
     * recipe JSONs, re-parses each changed recipe and swaps it into the
     * running RecipeManager. Needs bound tags, so it runs in the parsed
     * phase (and directly after a GUI bulk edit). Returns the number of
     * recipes changed.
     */
    public static int applyIngredientReplacements(MinecraftServer server) {
        List<RewriteConfig.IngredientReplacement> rules = RewriteState.config().ingredientReplacements();
        if (rules.isEmpty()) {
            return 0;
        }
        Map<ResourceLocation, RecipeHolder<?>> changed = new LinkedHashMap<>();
        int failed = 0;
        for (Map.Entry<ResourceLocation, JsonElement> entry : RewriteState.recipeJsons.entrySet()) {
            if (!(entry.getValue() instanceof JsonObject original)) {
                continue;
            }
            // The cache may hold the config's own objects (added recipes) —
            // never mutate those in place.
            JsonObject work = original.deepCopy();
            int hits = 0;
            for (RewriteConfig.IngredientReplacement rule : rules) {
                hits += replaceIngredients(work, rule);
            }
            if (hits == 0) {
                continue;
            }
            ResourceLocation id = entry.getKey();
            try {
                DataResult<Recipe<?>> result = Recipe.CODEC.parse(
                        RegistryOps.create(JsonOps.INSTANCE, server.registryAccess()), work);
                if (result.error().isPresent() || result.result().isEmpty()) {
                    failed++;
                    continue;
                }
                changed.put(id, new RecipeHolder<>(id, result.result().get()));
                RewriteState.recipeJsons.put(id, work);
            } catch (Throwable t) {
                // Mod recipe codecs may throw instead of erroring — skip.
                failed++;
            }
        }
        if (failed > 0) {
            Datarewriter.LOGGER.warn("Ingredient replacement: {} recipes no longer parse "
                    + "after the rewrite and were left unchanged", failed);
        }
        if (changed.isEmpty()) {
            return 0;
        }

        RecipeManager recipeManager = server.getRecipeManager();
        List<RecipeHolder<?>> next = new ArrayList<>(recipeManager.getRecipes().size());
        int swapped = 0;
        for (RecipeHolder<?> holder : recipeManager.getRecipes()) {
            RecipeHolder<?> replacement = changed.get(holder.id());
            if (replacement != null) {
                swapped++;
            }
            next.add(replacement != null ? replacement : holder);
        }
        recipeManager.replaceRecipes(next);
        syncRecipes(server);
        Datarewriter.LOGGER.info("Replaced ingredients in {} recipes", swapped);
        return swapped;
    }

    /**
     * Rewrites every ingredient reference matching the rule in one recipe's
     * JSON — {"item": x} objects (x matching the pattern, or in the tag) and
     * identical {"tag": t} objects. Subtrees under result-ish keys are left
     * alone (results can use "item" too in modded types). Returns the count.
     */
    public static int replaceIngredients(JsonObject recipe, RewriteConfig.IngredientReplacement rule) {
        Set<ResourceLocation> tagItems = rule.from().tag() != null
                ? LootRewriter.tagContents(rule.from().tag()) : Set.of();
        int changed = 0;
        for (Map.Entry<String, JsonElement> entry : recipe.entrySet()) {
            if (!isResultKey(entry.getKey())) {
                changed += replaceIngredientRefs(entry.getValue(), rule, tagItems);
            }
        }
        return changed;
    }

    private static int replaceIngredientRefs(JsonElement element,
                                             RewriteConfig.IngredientReplacement rule,
                                             Set<ResourceLocation> tagItems) {
        int changed = 0;
        if (element instanceof JsonArray array) {
            for (JsonElement child : array) {
                changed += replaceIngredientRefs(child, rule, tagItems);
            }
            return changed;
        }
        if (!(element instanceof JsonObject obj)) {
            return 0;
        }
        if (obj.get("item") instanceof JsonPrimitive p && p.isString()) {
            ResourceLocation id = ResourceLocation.tryParse(p.getAsString());
            boolean matches = id != null && (rule.from().tag() != null
                    ? tagItems.contains(id) : rule.from().pattern().matches(id));
            if (matches && applyTo(obj, "item", rule.to())) {
                changed++;
            }
        } else if (obj.get("tag") instanceof JsonPrimitive p && p.isString()
                && rule.from().tag() != null) {
            ResourceLocation tag = ResourceLocation.tryParse(p.getAsString());
            if (rule.from().tag().equals(tag) && applyTo(obj, "tag", rule.to())) {
                changed++;
            }
        }
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            if (!isResultKey(entry.getKey())) {
                changed += replaceIngredientRefs(entry.getValue(), rule, tagItems);
            }
        }
        return changed;
    }

    /** Swaps the ref for 'to' ("id" or "#tag"); false when it's a no-op. */
    private static boolean applyTo(JsonObject obj, String oldKey, String to) {
        boolean toTag = to.startsWith("#");
        String newKey = toTag ? "tag" : "item";
        String newValue = toTag ? to.substring(1) : to;
        if (newKey.equals(oldKey) && obj.get(oldKey) instanceof JsonPrimitive p
                && newValue.equals(ResourceLocation.tryParse(p.getAsString()) + "")) {
            return false; // already what the rule asks for
        }
        obj.remove(oldKey);
        obj.addProperty(newKey, newValue);
        return true;
    }

    /** Keys whose subtree holds the recipe's RESULT, never ingredients. */
    private static boolean isResultKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.contains("result") || lower.equals("output") || lower.equals("outputs");
    }

    /**
     * Sends the current recipe list to all online players — the vanilla sync
     * packet, so clients do not need the mod.
     */
    private static void syncRecipes(MinecraftServer server) {
        ClientboundUpdateRecipesPacket packet =
                new ClientboundUpdateRecipesPacket(server.getRecipeManager().getRecipes());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.connection.send(packet);
        }
    }
}
