package net.ledok.datarewriter;

import com.google.gson.JsonElement;
import net.ledok.datarewriter.config.RewriteConfig;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
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
     * Parsed phase. Called after server start and after /reload, once tags are
     * bound. Applies rules matching by output/input/type.
     */
    public static void applyParsedRules(MinecraftServer server) {
        List<RewriteConfig.RemovalRule> rules = RewriteState.config().removals().stream()
                .filter(RewriteConfig.RemovalRule::needsParsedRecipe)
                .toList();
        if (rules.isEmpty()) {
            return;
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

        RewriteState.recipesRemovedParsed = removed;
        Datarewriter.LOGGER.info("Removed {} recipes by output/input/type rules", removed);
        if (removed == 0) {
            return;
        }
        recipeManager.replaceRecipes(kept);

        // On /reload, vanilla has already synced the unfiltered list to online
        // players, so send the filtered one. This is the vanilla sync packet —
        // clients do not need the mod.
        ClientboundUpdateRecipesPacket packet = new ClientboundUpdateRecipesPacket(recipeManager.getRecipes());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.connection.send(packet);
        }
    }
}
