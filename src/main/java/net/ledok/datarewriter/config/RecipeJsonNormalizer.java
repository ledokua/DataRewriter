package net.ledok.datarewriter.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Lets users write ingredients as plain strings ("minecraft:oak_log",
 * "#minecraft:planks") in added recipes. Vanilla 1.21.1 requires the object
 * form ({"item": ...} / {"tag": ...}), so this expands the shorthand for the
 * known vanilla recipe types before the JSON is handed to the recipe codec.
 * Unknown / modded recipe types are left untouched.
 */
final class RecipeJsonNormalizer {
    private RecipeJsonNormalizer() {
    }

    static void normalize(JsonObject recipe) {
        String typeStr = recipe.get("type") != null && recipe.get("type").isJsonPrimitive()
                ? recipe.get("type").getAsString() : null;
        ResourceLocation type = typeStr != null ? ResourceLocation.tryParse(typeStr) : null;
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

    /** "x" -> {"item": "x"}, "#t" -> {"tag": "t"}; arrays element-wise; objects pass through. */
    private static JsonElement normalizeIngredient(JsonElement element) {
        if (element instanceof JsonPrimitive primitive && primitive.isString()) {
            String value = primitive.getAsString();
            JsonObject obj = new JsonObject();
            if (value.startsWith("#")) {
                obj.addProperty("tag", value.substring(1));
            } else {
                obj.addProperty("item", value);
            }
            return obj;
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
