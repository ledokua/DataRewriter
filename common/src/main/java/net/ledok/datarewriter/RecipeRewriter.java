package net.ledok.datarewriter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.ledok.datarewriter.config.RewriteConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class RecipeRewriter {
    // Recipes we added/replaced this load; removal rules never touch these,
    // so "remove everything from minecraft:" + a replacement recipe works.
    private static final Set<ResourceLocation> addedIds = ConcurrentHashMap.newKeySet();

    // Live edits apply to the server immediately, but the client-side recipe
    // list is only pushed on demand: every push makes recipe viewers (EMI,
    // JEI, REI) drop everything and reload, which takes seconds on a big pack
    // and would interrupt whoever is still editing. This counts edits made
    // since the last push — see pushToClients / '/datarewriter reload'.
    private static volatile int pendingClientEdits;

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
     * — no data reload. Used by the recipe editor; the recipe is also persisted
     * to the config, so the next real reload produces the same state. Crafting
     * uses it at once (the server is authoritative); connected clients only see
     * it in their recipe list after the next push (see {@link #pushToClients}).
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
        pendingClientEdits++;
    }

    /**
     * Parsed phase. Called after server start and after /reload, once tags
     * are bound: output/input/type removal rules, then ingredient
     * replacements.
     */
    public static void applyParsedRules(MinecraftServer server) {
        boolean changed = applyRemovalRules(server, false) > 0;
        changed |= applyIngredientReplacements(server, false) > 0;
        changed |= applyDisabledItems(server) > 0;
        // A data reload has just pushed the un-rewritten list to everyone, so
        // this correction has to go out now — viewers are reloading anyway.
        if (changed) {
            syncRecipes(server);
        }
    }

    /**
     * Applies removal rules matching by output/input/type to the running
     * RecipeManager. Returns how many recipes this call removed (also called
     * for a fresh GUI-saved rule — earlier rules already removed their
     * matches, so the count is that rule's own).
     */
    public static int applyRemovalRules(MinecraftServer server) {
        return applyRemovalRules(server, true);
    }

    private static int applyRemovalRules(MinecraftServer server, boolean markStale) {
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
        if (markStale) {
            pendingClientEdits++;
        }
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
        return applyIngredientReplacements(server, true);
    }

    private static int applyIngredientReplacements(MinecraftServer server, boolean markStale) {
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
        if (markStale) {
            pendingClientEdits++;
        }
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
     * The recipe half of {@code items.disable} that the derived replace_ingredients rules can't do
     * (those already swapped replaced items out of ingredients): removes recipes whose result is a
     * disabled item, and recipes an ingredient of which only disabled items could fill. Secondary
     * results (a cutting board's extra drops, say) get the replacement, or leave the result list —
     * the recipe goes only when that empties it. Runs on every start and reload. Returns the number
     * of recipes removed or changed.
     */
    private static int applyDisabledItems(MinecraftServer server) {
        if (!DisabledItems.active()) {
            RewriteState.recipesDisabled = 0;
            return 0;
        }
        RecipeManager recipeManager = server.getRecipeManager();
        List<RecipeHolder<?>> next = new ArrayList<>(recipeManager.getRecipes().size());
        // Signatures of the recipes the survivors of make: "redirect" rules already have, so a redirected
        // copy that only duplicates one of them is dropped (filled lazily, then by each redirect kept).
        Set<String> survivorRecipes = null;
        int removed = 0;
        int changed = 0;
        int redirected = 0;
        for (RecipeHolder<?> holder : recipeManager.getRecipes()) {
            Recipe<?> recipe = holder.value();
            JsonElement json = RewriteState.recipeJsons.get(holder.id());
            ItemStack result = resultOf(server, recipe);
            if (!result.isEmpty() && DisabledItems.isDisabled(result.getItem())) {
                if (!DisabledItems.redirectsRecipes(result.getItem()) || !(json instanceof JsonObject original)
                        || onlyDisabledFits(recipe, json)) {
                    removed++;
                    continue;
                }
                JsonObject work = original.deepCopy();
                RecipeHolder<?> reparsed = rewriteSecondaryResults(work) ? reparse(server, holder.id(), work) : null;
                ItemStack newResult = reparsed == null ? ItemStack.EMPTY : resultOf(server, reparsed.value());
                if (newResult.isEmpty() || DisabledItems.isDisabled(newResult.getItem())) {
                    Datarewriter.LOGGER.warn("Disabled items: could not redirect {} to make {} — removed",
                            holder.id(), DisabledItems.replacement(result.getItem()));
                    removed++;
                    continue;
                }
                if (survivorRecipes == null) {
                    survivorRecipes = survivorSignatures(server, recipeManager.getRecipes());
                }
                if (!survivorRecipes.add(signature(reparsed.value(), newResult))) {
                    removed++; // the survivor can already be made exactly this way
                    continue;
                }
                RewriteState.recipeJsons.put(holder.id(), work);
                next.add(reparsed);
                redirected++;
                continue;
            }
            if (onlyDisabledFits(recipe, json)) {
                removed++;
                continue;
            }
            if (json instanceof JsonObject original && mentionsDisabledResult(original)) {
                // No real result to tell the primary one by: whatever it makes counts as made.
                if (result.isEmpty()) {
                    removed++;
                    continue;
                }
                JsonObject work = original.deepCopy();
                if (!rewriteSecondaryResults(work)) {
                    removed++;
                    continue;
                }
                RecipeHolder<?> reparsed = reparse(server, holder.id(), work);
                if (reparsed == null) {
                    Datarewriter.LOGGER.warn("Disabled items: {} no longer parses without its disabled "
                            + "secondary result — removed", holder.id());
                    removed++;
                    continue;
                }
                RewriteState.recipeJsons.put(holder.id(), work);
                next.add(reparsed);
                changed++;
                continue;
            }
            next.add(holder);
        }
        RewriteState.recipesDisabled = removed;
        if (removed + changed + redirected == 0) {
            return 0;
        }
        recipeManager.replaceRecipes(next);
        Datarewriter.LOGGER.info("Disabled items: removed {} recipes, redirected {} to the replacement, "
                + "fixed secondary results in {}", removed, redirected, changed);
        return removed + changed + redirected;
    }

    private static ItemStack resultOf(MinecraftServer server, Recipe<?> recipe) {
        try {
            ItemStack result = recipe.getResultItem(server.registryAccess());
            return result == null ? ItemStack.EMPTY : result;
        } catch (Exception e) {
            return ItemStack.EMPTY; // context-dependent modded results — the JSON checks still apply
        }
    }

    /** Signatures of every recipe making a redirect target (the replacement of a make: "redirect" item). */
    private static Set<String> survivorSignatures(MinecraftServer server, Collection<RecipeHolder<?>> recipes) {
        Set<String> signatures = new java.util.HashSet<>();
        for (RecipeHolder<?> holder : recipes) {
            ItemStack result = resultOf(server, holder.value());
            if (!result.isEmpty() && !DisabledItems.isDisabled(result.getItem())) {
                signatures.add(signature(holder.value(), result));
            }
        }
        return signatures;
    }

    /**
     * Type, result item and ingredients (each as its sorted item ids, the list sorted too — so a shaped
     * recipe's layout and the result count don't count). Same signature = the same thing from the same inputs.
     */
    private static String signature(Recipe<?> recipe, ItemStack result) {
        List<String> ingredients = new ArrayList<>();
        try {
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient.isEmpty()) {
                    continue;
                }
                List<String> ids = new ArrayList<>();
                for (ItemStack item : ingredient.getItems()) {
                    ids.add(BuiltInRegistries.ITEM.getKey(item.getItem()).toString());
                }
                ids.sort(null);
                ingredients.add(String.join("|", ids));
            }
        } catch (Exception e) {
            return "unique:" + System.identityHashCode(recipe); // can't tell — never a duplicate
        }
        ingredients.sort(null);
        return BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()) + ">" + BuiltInRegistries.ITEM.getKey(result.getItem())
                + "<" + String.join(",", ingredients);
    }

    /**
     * Does some ingredient accept nothing but disabled items? Tags were pruned and replaced items were
     * swapped already, so this is what is left: a plain ingredient of a disabled item without a
     * replacement. Recipe types that list no ingredients are checked on their JSON instead.
     */
    private static boolean onlyDisabledFits(Recipe<?> recipe, JsonElement json) {
        try {
            List<Ingredient> ingredients = recipe.getIngredients();
            if (ingredients.isEmpty()) {
                return json instanceof JsonObject obj && mentionsDisabledIngredient(obj);
            }
            for (Ingredient ingredient : ingredients) {
                ItemStack[] items = ingredient.getItems();
                if (items.length == 0) {
                    continue;
                }
                boolean allDisabled = true;
                for (ItemStack item : items) {
                    if (!DisabledItems.isDisabled(item.getItem())) {
                        allDisabled = false;
                        break;
                    }
                }
                if (allDisabled) {
                    return true;
                }
            }
        } catch (Exception e) {
            return false; // be defensive about modded ingredient implementations
        }
        return false;
    }

    private static boolean mentionsDisabledIngredient(JsonObject recipe) {
        for (Map.Entry<String, JsonElement> entry : recipe.entrySet()) {
            if (!isResultKey(entry.getKey()) && mentionsDisabled(entry.getValue(), false)) {
                return true;
            }
        }
        return false;
    }

    private static boolean mentionsDisabledResult(JsonObject recipe) {
        for (Map.Entry<String, JsonElement> entry : recipe.entrySet()) {
            if (isResultKey(entry.getKey()) && mentionsDisabled(entry.getValue(), true)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Any item reference to a disabled item in the subtree: an {"item"|"id": x} value, or (for results)
     * a bare id string. {@code anyDisabled} false = only items without a replacement count.
     */
    private static boolean mentionsDisabled(JsonElement element, boolean anyDisabled) {
        if (element instanceof JsonPrimitive primitive) {
            return anyDisabled && primitive.isString() && disabledId(primitive.getAsString(), true);
        }
        if (element instanceof JsonArray array) {
            for (JsonElement child : array) {
                if (mentionsDisabled(child, anyDisabled)) {
                    return true;
                }
            }
            return false;
        }
        if (!(element instanceof JsonObject obj)) {
            return false;
        }
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            JsonElement value = entry.getValue();
            if ((entry.getKey().equals("item") || entry.getKey().equals("id"))
                    && value instanceof JsonPrimitive p && p.isString()) {
                if (disabledId(p.getAsString(), anyDisabled)) {
                    return true;
                }
            } else if (!(value instanceof JsonPrimitive) && mentionsDisabled(value, anyDisabled)) {
                return true;
            }
        }
        return false;
    }

    private static boolean disabledId(String text, boolean anyDisabled) {
        ResourceLocation id = ResourceLocation.tryParse(text);
        if (id == null) {
            return false;
        }
        Optional<Item> item = BuiltInRegistries.ITEM.getOptional(id);
        if (item.isEmpty() || !DisabledItems.isDisabled(item.get())) {
            return false;
        }
        return anyDisabled || DisabledItems.replacement(item.get()) == Items.AIR;
    }

    /**
     * Secondary results: swaps replaced items in place, and drops list elements naming an item without
     * a replacement. False when that leaves a result empty (the recipe should go).
     */
    private static boolean rewriteSecondaryResults(JsonObject recipe) {
        for (Map.Entry<String, JsonElement> entry : recipe.entrySet()) {
            if (!isResultKey(entry.getKey())) {
                continue;
            }
            if (entry.getValue() instanceof JsonPrimitive p && p.isString()) {
                String with = replacementId(p.getAsString());
                if (with != null) {
                    entry.setValue(new JsonPrimitive(with)); // "result": "mod:item"
                }
            }
            swapReplaced(entry.getValue());
            if (entry.getValue() instanceof JsonArray array) {
                boolean hadAny = !array.isEmpty();
                for (Iterator<JsonElement> it = array.iterator(); it.hasNext(); ) {
                    if (mentionsDisabled(it.next(), false)) {
                        it.remove();
                    }
                }
                if (hadAny && array.isEmpty()) {
                    return false;
                }
            } else if (mentionsDisabled(entry.getValue(), false)) {
                return false;
            }
        }
        return true;
    }

    /** Replaces every disabled-with-replacement id ({"item"|"id": x} or a bare string in a list) in place. */
    private static void swapReplaced(JsonElement element) {
        if (element instanceof JsonArray array) {
            for (int i = 0; i < array.size(); i++) {
                if (array.get(i) instanceof JsonPrimitive p && p.isString()) {
                    String with = replacementId(p.getAsString());
                    if (with != null) {
                        array.set(i, new JsonPrimitive(with));
                    }
                } else {
                    swapReplaced(array.get(i));
                }
            }
        } else if (element instanceof JsonObject obj) {
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                if ((entry.getKey().equals("item") || entry.getKey().equals("id"))
                        && entry.getValue() instanceof JsonPrimitive p && p.isString()) {
                    String with = replacementId(p.getAsString());
                    if (with != null) {
                        entry.setValue(new JsonPrimitive(with));
                    }
                } else {
                    swapReplaced(entry.getValue());
                }
            }
        }
    }

    private static @Nullable String replacementId(String text) {
        ResourceLocation id = ResourceLocation.tryParse(text);
        if (id == null) {
            return null;
        }
        Item with = BuiltInRegistries.ITEM.getOptional(id).map(DisabledItems::replacement).orElse(null);
        return with == null || with == Items.AIR ? null : BuiltInRegistries.ITEM.getKey(with).toString();
    }

    private static @Nullable RecipeHolder<?> reparse(MinecraftServer server, ResourceLocation id, JsonObject json) {
        try {
            DataResult<Recipe<?>> result = Recipe.CODEC.parse(
                    RegistryOps.create(JsonOps.INSTANCE, server.registryAccess()), json);
            return result.result().<RecipeHolder<?>>map(recipe -> new RecipeHolder<>(id, recipe)).orElse(null);
        } catch (Throwable t) {
            return null; // mod recipe codecs may throw instead of erroring
        }
    }

    /** Live edits made since the last push to clients (0 = viewers are current). */
    public static int pendingClientEdits() {
        return pendingClientEdits;
    }

    /**
     * Pushes the current recipe list to every connected client, which is what
     * makes edits show up in EMI/JEI/REI and the recipe book. Returns the
     * number of edits this push carried. Players joining later get the live
     * list in their join packet, so this only concerns clients already online.
     */
    public static int pushToClients(MinecraftServer server) {
        int carried = pendingClientEdits;
        syncRecipes(server);
        return carried;
    }

    /**
     * Re-syncs recipes to all online players with the same packet sequence
     * vanilla's /reload uses: tags first, then recipes, then the recipe book.
     * Vanilla clients only need the recipes packet, but recipe viewers (EMI,
     * and JEI/REI work the same way) reload only once they have seen a
     * MATCHED tags+recipes pair — a lone recipes packet leaves their state
     * machine waiting for tags, and the next join's early tags packet then
     * completes that stale pair before the client world exists, killing the
     * reload ("World is null") and freezing the "Reloading..." overlay.
     */
    private static void syncRecipes(MinecraftServer server) {
        pendingClientEdits = 0;
        ClientboundUpdateTagsPacket tags = new ClientboundUpdateTagsPacket(
                TagNetworkSerialization.serializeTagsToNetwork(server.registries()));
        ClientboundUpdateRecipesPacket recipes =
                new ClientboundUpdateRecipesPacket(server.getRecipeManager().getRecipes());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.connection.send(tags);
            player.connection.send(recipes);
            player.getRecipeBook().sendInitialRecipeBook(player);
        }
    }
}
