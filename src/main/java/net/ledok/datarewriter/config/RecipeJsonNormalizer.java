package net.ledok.datarewriter.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Keeps configs written for 1.21.1 working: ingredients there were objects
 * ({"item": ...} / {"tag": ...}), which the Ingredient codec no longer accepts
 * since 1.21.2 — it takes plain strings ("minecraft:oak_log",
 * "#minecraft:planks") or lists of them. This converts the old objects before
 * the JSON is handed to the recipe codec: for the known vanilla recipe types, and
 * for any recipe built on vanilla's pattern/key structure whatever its type —
 * mods embed that codec as-is (Create's mechanical crafting, for one), so the
 * values there are always plain ingredients. Everything else is left untouched,
 * since only the type's own codec knows what its fields mean.
 */
final class RecipeJsonNormalizer {
    private RecipeJsonNormalizer() {
    }

    static void normalize(JsonObject recipe) {
        if (recipe.get("pattern") instanceof JsonArray && recipe.get("key") instanceof JsonObject) {
            normalizeKeyMap(recipe);
            normalizeResult(recipe);
        }
        String typeStr = recipe.get("type") != null && recipe.get("type").isJsonPrimitive()
                ? recipe.get("type").getAsString() : null;
        Identifier type = typeStr != null ? Identifier.tryParse(typeStr) : null;
        if (type == null || !type.getNamespace().equals("minecraft")) {
            return;
        }
        switch (type.getPath()) {
            case "crafting_shaped" -> {
                normalizeKeyMap(recipe);
                normalizeResult(recipe);
            }
            case "crafting_shapeless" -> {
                normalizeIngredientList(recipe, "ingredients");
                normalizeResult(recipe);
            }
            case "smelting", "blasting", "smoking", "campfire_cooking", "stonecutting" -> {
                normalizeIngredientField(recipe, "ingredient");
                normalizeResult(recipe);
            }
            case "smithing_transform" -> {
                normalizeIngredientField(recipe, "template");
                normalizeIngredientField(recipe, "base");
                normalizeIngredientField(recipe, "addition");
                normalizeResult(recipe);
            }
            case "smithing_trim" -> {
                normalizeIngredientField(recipe, "template");
                normalizeIngredientField(recipe, "base");
                normalizeIngredientField(recipe, "addition");
            }
            default -> {
            }
        }
    }

    private static void normalizeKeyMap(JsonObject recipe) {
        if (recipe.get("key") instanceof JsonObject key) {
            for (String symbol : List.copyOf(key.keySet())) {
                key.add(symbol, normalizeIngredient(key.get(symbol)));
            }
        }
    }

    private static void normalizeIngredientList(JsonObject recipe, String field) {
        if (recipe.get(field) instanceof JsonArray list) {
            JsonArray normalized = new JsonArray(list.size());
            for (JsonElement element : list) {
                normalized.add(normalizeIngredient(element));
            }
            recipe.add(field, normalized);
        }
    }

    private static void normalizeIngredientField(JsonObject recipe, String field) {
        JsonElement value = recipe.get(field);
        if (value != null) {
            recipe.add(field, normalizeIngredient(value));
        }
    }

    /**
     * {"item": "x"} -> "x", {"tag": "t"} -> "#t"; arrays element-wise (a list of item ids stays a list,
     * the codec reads it as alternatives); everything else (strings, NeoForge custom ingredients) passes
     * through.
     */
    private static JsonElement normalizeIngredient(JsonElement element) {
        if (element instanceof JsonObject obj && obj.size() == 1) {
            if (obj.get("item") instanceof JsonPrimitive item && item.isString()) {
                return new JsonPrimitive(item.getAsString());
            }
            if (obj.get("tag") instanceof JsonPrimitive tag && tag.isString()) {
                return new JsonPrimitive("#" + tag.getAsString());
            }
        }
        if (element instanceof JsonArray array) {
            JsonArray normalized = new JsonArray(array.size());
            for (JsonElement inner : array) {
                normalized.add(normalizeIngredient(inner));
            }
            return normalized;
        }
        return element;
    }

    /** result: "minecraft:diamond" -> result: {"id": "minecraft:diamond"} */
    private static void normalizeResult(JsonObject recipe) {
        if (recipe.get("result") instanceof JsonPrimitive primitive && primitive.isString()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", primitive.getAsString());
            recipe.add("result", obj);
        }
    }
}
