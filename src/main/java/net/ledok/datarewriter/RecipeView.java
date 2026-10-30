package net.ledok.datarewriter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A parsed recipe's result and ingredients. Since 1.21.2 recipes only expose them through their displays
 * (what the recipe book shows) and their placement info; special and many modded recipes have neither, so
 * the result falls back to the recipe's JSON.
 */
public final class RecipeView {
    private RecipeView() {
    }

    /** The recipe's (first) result, or EMPTY when neither its displays nor its JSON name one. */
    public static ItemStack result(Recipe<?> recipe, HolderLookup.Provider registries, @Nullable JsonElement json) {
        try {
            ContextMap context = new ContextMap.Builder()
                    .withParameter(SlotDisplayContext.REGISTRIES, registries)
                    .create(SlotDisplayContext.CONTEXT);
            for (RecipeDisplay display : recipe.display()) {
                ItemStack result = display.result().resolveForFirstStack(context);
                if (!result.isEmpty()) {
                    return result;
                }
            }
        } catch (Exception e) {
            // context-dependent modded displays: the JSON fallback still applies
        }
        return json instanceof JsonObject obj ? resultFromJson(obj) : ItemStack.EMPTY;
    }

    /**
     * The ingredients the recipe places; empty for special recipes and recipe types that don't declare
     * placement (callers check the JSON for those).
     */
    public static List<Ingredient> ingredients(Recipe<?> recipe) {
        try {
            return recipe.placementInfo().ingredients();
        } catch (Exception e) {
            return List.of(); // be defensive about modded implementations
        }
    }

    /** "result"/"output": a bare id, {"id"|"item": id}, or a list of those (the first one counts). */
    private static ItemStack resultFromJson(JsonObject recipe) {
        for (Map.Entry<String, JsonElement> entry : recipe.entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            if (key.equals("result") || key.equals("output") || key.equals("results") || key.equals("outputs")) {
                Item item = firstItem(entry.getValue());
                if (item != null) {
                    return new ItemStack(item);
                }
            }
        }
        return ItemStack.EMPTY;
    }

    private static @Nullable Item firstItem(JsonElement element) {
        if (element instanceof JsonPrimitive p && p.isString()) {
            Identifier id = Identifier.tryParse(p.getAsString());
            Optional<Item> item = id == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(id);
            return item.orElse(null);
        }
        if (element instanceof JsonObject obj) {
            for (String key : new String[]{"id", "item"}) {
                if (obj.get(key) instanceof JsonPrimitive p) {
                    return firstItem(p);
                }
            }
            return null;
        }
        if (element != null && element.isJsonArray() && !element.getAsJsonArray().isEmpty()) {
            return firstItem(element.getAsJsonArray().get(0));
        }
        return null;
    }
}
