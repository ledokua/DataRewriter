package net.ledok.datarewriter.compat.thaumcraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import thaumcraft.aspect.Aspect;
import thaumcraft.aspect.AspectList;
import thaumcraft.crafting.CompoundRecipe;
import thaumcraft.crafting.CraftingRecipeRef;
import thaumcraft.crafting.CrucibleRecipe;
import thaumcraft.crafting.InfusionEnchantmentRecipe;
import thaumcraft.crafting.InfusionRecipe;
import thaumcraft.crafting.ShapedArcaneRecipe;
import thaumcraft.crafting.ShapelessArcaneRecipe;
import thaumcraft.crafting.TcIngredient;
import thaumcraft.crafting.TcRecipe;
import thaumcraft.crafting.TcStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * DataRewriter's JSON form of Thaumcraft's recipe records — the config's {@code thaumcraft.add} entries, the
 * editor's load/save and the client sync all use it:
 * <pre>
 * { id: "Key", type: "arcane_shaped", research: "RESEARCH", result: "mod:item" | {id, count, aspects, data},
 *   aspects: { aer: 5 } | "aer:5, ignis:3",
 *   pattern: ["AB", ...], key: { A: ingredient },          // arcane_shaped
 *   ingredients: [ingredient, ...],                        // arcane_shapeless
 *   catalyst: ingredient,                                  // crucible
 *   central: ingredient, components: [...], instability: n // infusion
 *   enchantment: "minecraft:sharpness", components, instability } // infusion_enchantment
 * </pre>
 * An ingredient is "mod:item", "#mod:tag", a list of those (any of them), or {id, count, aspects, data} for
 * items whose aspects or wand parts matter. Crafting-table references and multiblock ("compound") recipes
 * can only be removed, never written.
 */
public final class TcJson {
    public static final List<String> TYPES = List.of("arcane_shaped", "arcane_shapeless", "crucible", "infusion",
            "infusion_enchantment");

    private TcJson() {
    }

    /** Thrown with a readable message for the config log / the editor. */
    public static final class TcJsonException extends RuntimeException {
        TcJsonException(String message) {
            super(message);
        }
    }

    /** The type name of a recipe record ("arcane_shaped", ..., "compound", "crafting"). */
    public static String type(TcRecipe recipe) {
        return switch (recipe) {
            case ShapedArcaneRecipe r -> "arcane_shaped";
            case ShapelessArcaneRecipe r -> "arcane_shapeless";
            case CrucibleRecipe r -> "crucible";
            case InfusionRecipe r -> "infusion";
            case InfusionEnchantmentRecipe r -> "infusion_enchantment";
            case CompoundRecipe r -> "compound";
            case CraftingRecipeRef r -> "crafting";
        };
    }

    /** "thaumcraft:infusion" and "infusion" both mean infusion. */
    public static String normalizeType(String type) {
        String lower = type.toLowerCase(Locale.ROOT);
        return lower.startsWith("thaumcraft:") ? lower.substring("thaumcraft:".length()) : lower;
    }

    /** Whether DataRewriter can write (add, modify, edit) recipes of this kind. */
    public static boolean editable(TcRecipe recipe) {
        return TYPES.contains(type(recipe));
    }

    // ---- encode ----

    public static JsonObject encode(TcRecipe recipe) {
        JsonObject json = new JsonObject();
        json.addProperty("id", recipe.key());
        json.addProperty("type", type(recipe));
        switch (recipe) {
            case ShapedArcaneRecipe r -> {
                json.addProperty("research", r.research());
                json.add("result", encodeStack(r.result()));
                json.add("aspects", encodeAspects(r.aspects()));
                encodePattern(json, r.width(), r.height(), r.grid());
            }
            case ShapelessArcaneRecipe r -> {
                json.addProperty("research", r.research());
                json.add("result", encodeStack(r.result()));
                json.add("aspects", encodeAspects(r.aspects()));
                json.add("ingredients", encodeList(r.ingredients()));
            }
            case CrucibleRecipe r -> {
                json.addProperty("research", r.research());
                json.add("result", encodeStack(r.result()));
                json.add("aspects", encodeAspects(r.aspects()));
                json.add("catalyst", encodeIngredient(r.catalyst()));
            }
            case InfusionRecipe r -> {
                json.addProperty("research", r.research());
                json.add("result", encodeStack(r.result()));
                json.add("aspects", encodeAspects(r.aspects()));
                json.addProperty("instability", r.instability());
                json.add("central", encodeIngredient(r.central()));
                json.add("components", encodeList(r.components()));
            }
            case InfusionEnchantmentRecipe r -> {
                json.addProperty("research", r.research());
                json.addProperty("enchantment", r.enchantment().toString());
                json.add("aspects", encodeAspects(r.aspects()));
                json.addProperty("instability", r.instability());
                json.add("components", encodeList(r.components()));
            }
            case CompoundRecipe r -> json.addProperty("research", r.research());
            case CraftingRecipeRef r -> {
                json.addProperty("research", r.research());
                json.addProperty("recipe", r.recipe().toString());
            }
        }
        return json;
    }

    private static void encodePattern(JsonObject json, int width, int height, List<TcIngredient> grid) {
        Map<String, Character> letters = new LinkedHashMap<>();
        JsonObject key = new JsonObject();
        JsonArray pattern = new JsonArray();
        for (int row = 0; row < height; row++) {
            StringBuilder line = new StringBuilder();
            for (int col = 0; col < width; col++) {
                TcIngredient ingredient = grid.get(row * width + col);
                if (ingredient instanceof TcIngredient.Empty) {
                    line.append(' ');
                    continue;
                }
                JsonElement encoded = encodeIngredient(ingredient);
                String signature = encoded.toString();
                Character letter = letters.get(signature);
                if (letter == null) {
                    letter = (char) ('A' + letters.size());
                    letters.put(signature, letter);
                    key.add(String.valueOf(letter), encoded);
                }
                line.append(letter);
            }
            pattern.add(line.toString());
        }
        json.add("pattern", pattern);
        json.add("key", key);
    }

    private static JsonArray encodeList(List<TcIngredient> ingredients) {
        JsonArray array = new JsonArray();
        for (TcIngredient ingredient : ingredients) {
            array.add(encodeIngredient(ingredient));
        }
        return array;
    }

    public static JsonElement encodeIngredient(TcIngredient ingredient) {
        return switch (ingredient) {
            case TcIngredient.Empty e -> new JsonPrimitive("");
            case TcIngredient.Stack s -> encodeStack(s.stack());
            case TcIngredient.Tag t -> new JsonPrimitive("#" + t.tag().location());
            case TcIngredient.AnyOf a -> {
                JsonArray array = new JsonArray();
                for (TcIngredient option : a.options()) {
                    array.add(encodeIngredient(option));
                }
                yield array;
            }
        };
    }

    public static JsonElement encodeStack(TcStack stack) {
        if (stack.count() == 1 && stack.aspects() == null && stack.data().isEmpty()) {
            return new JsonPrimitive(stack.item().toString());
        }
        JsonObject obj = new JsonObject();
        obj.addProperty("id", stack.item().toString());
        if (stack.count() != 1) {
            obj.addProperty("count", stack.count());
        }
        if (stack.aspects() != null) {
            obj.add("aspects", encodeAspects(stack.aspects()));
        }
        if (!stack.data().isEmpty()) {
            JsonObject data = new JsonObject();
            stack.data().forEach(data::addProperty);
            obj.add("data", data);
        }
        return obj;
    }

    public static JsonObject encodeAspects(AspectList aspects) {
        JsonObject obj = new JsonObject();
        for (Aspect aspect : aspects.getAspects()) {
            obj.addProperty(aspect.getTag(), aspects.getAmount(aspect));
        }
        return obj;
    }

    // ---- decode ----

    /**
     * The recipe record for one entry. {@code strict} also checks that every item exists (the server's
     * config load and the editor); clients decoding what the server sent skip that.
     */
    public static TcRecipe decode(JsonObject json, boolean strict) {
        String key = string(json, "id");
        if (key == null || key.isBlank()) {
            throw new TcJsonException("missing 'id' (the recipe key, like \"ElementalSword\")");
        }
        String type = string(json, "type");
        if (type == null) {
            throw new TcJsonException("missing 'type' (one of " + String.join(", ", TYPES) + ")");
        }
        String research = json.has("research") ? string(json, "research") : "";
        if (research == null) {
            throw new TcJsonException("'research' must be a research key string");
        }
        return switch (normalizeType(type)) {
            case "arcane_shaped" -> decodeShaped(json, key, research, strict);
            case "arcane_shapeless" -> new ShapelessArcaneRecipe(key, research, result(json, strict),
                    aspects(json.get("aspects"), "aspects"), ingredients(json, "ingredients", strict, true));
            case "crucible" -> new CrucibleRecipe(key, research, result(json, strict),
                    required(json, "catalyst", strict), aspects(json.get("aspects"), "aspects"));
            case "infusion" -> new InfusionRecipe(key, research, result(json, strict), instability(json),
                    aspects(json.get("aspects"), "aspects"), required(json, "central", strict),
                    ingredients(json, "components", strict, false));
            case "infusion_enchantment" -> new InfusionEnchantmentRecipe(key, research, enchantment(json, strict),
                    instability(json), aspects(json.get("aspects"), "aspects"),
                    ingredients(json, "components", strict, true));
            default -> throw new TcJsonException("unknown type '" + type + "' (expected one of "
                    + String.join(", ", TYPES) + ")");
        };
    }

    private static ShapedArcaneRecipe decodeShaped(JsonObject json, String key, String research, boolean strict) {
        if (!(json.get("pattern") instanceof JsonArray pattern) || pattern.isEmpty() || pattern.size() > 3) {
            throw new TcJsonException("'pattern' must be a list of 1 to 3 rows");
        }
        if (!(json.get("key") instanceof JsonObject keyMap)) {
            throw new TcJsonException("'key' must map pattern letters to ingredients");
        }
        List<String> rows = new ArrayList<>();
        int width = 0;
        for (JsonElement row : pattern) {
            if (!(row instanceof JsonPrimitive p) || !p.isString()) {
                throw new TcJsonException("every 'pattern' row must be a string");
            }
            rows.add(p.getAsString());
            width = Math.max(width, p.getAsString().length());
        }
        if (width == 0 || width > 3) {
            throw new TcJsonException("pattern rows must be 1 to 3 characters wide");
        }
        List<TcIngredient> grid = new ArrayList<>();
        for (String row : rows) {
            for (int col = 0; col < width; col++) {
                char symbol = col < row.length() ? row.charAt(col) : ' ';
                if (symbol == ' ') {
                    grid.add(TcIngredient.EMPTY);
                    continue;
                }
                JsonElement value = keyMap.get(String.valueOf(symbol));
                if (value == null) {
                    throw new TcJsonException("pattern letter '" + symbol + "' is not in 'key'");
                }
                grid.add(ingredient(value, strict, "key." + symbol));
            }
        }
        return new ShapedArcaneRecipe(key, research, result(json, strict), aspects(json.get("aspects"), "aspects"),
                width, rows.size(), grid);
    }

    private static TcStack result(JsonObject json, boolean strict) {
        JsonElement value = json.get("result");
        if (value == null) {
            throw new TcJsonException("missing 'result'");
        }
        return stack(value, strict, "result");
    }

    private static TcIngredient required(JsonObject json, String field, boolean strict) {
        JsonElement value = json.get(field);
        if (value == null) {
            throw new TcJsonException("missing '" + field + "'");
        }
        TcIngredient ingredient = ingredient(value, strict, field);
        if (ingredient instanceof TcIngredient.Empty) {
            throw new TcJsonException("'" + field + "' can't be empty");
        }
        return ingredient;
    }

    private static List<TcIngredient> ingredients(JsonObject json, String field, boolean strict, boolean nonEmpty) {
        if (!(json.get(field) instanceof JsonArray array)) {
            if (json.has(field) || nonEmpty) {
                throw new TcJsonException("'" + field + "' must be a list of ingredients");
            }
            return List.of();
        }
        List<TcIngredient> list = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            TcIngredient ingredient = ingredient(array.get(i), strict, field + "[" + i + "]");
            if (!(ingredient instanceof TcIngredient.Empty)) {
                list.add(ingredient);
            }
        }
        if (nonEmpty && list.isEmpty()) {
            throw new TcJsonException("'" + field + "' needs at least one ingredient");
        }
        return list;
    }

    public static TcIngredient ingredient(JsonElement value, boolean strict, String where) {
        if (value == null || value.isJsonNull()) {
            return TcIngredient.EMPTY;
        }
        if (value instanceof JsonPrimitive p && p.isString()) {
            String text = p.getAsString().trim();
            if (text.isEmpty()) {
                return TcIngredient.EMPTY;
            }
            if (text.startsWith("#")) {
                if (Identifier.tryParse(text.substring(1)) == null) {
                    throw new TcJsonException(where + ": '" + text + "' is not a valid tag");
                }
                return TcIngredient.tag(text.substring(1));
            }
            return TcIngredient.of(stack(p, strict, where));
        }
        if (value instanceof JsonArray array) {
            List<TcIngredient> options = new ArrayList<>();
            for (int i = 0; i < array.size(); i++) {
                TcIngredient option = ingredient(array.get(i), strict, where + "[" + i + "]");
                if (!(option instanceof TcIngredient.Empty)) {
                    options.add(option);
                }
            }
            if (options.isEmpty()) {
                return TcIngredient.EMPTY;
            }
            return options.size() == 1 ? options.getFirst() : TcIngredient.anyOf(options.toArray(TcIngredient[]::new));
        }
        if (value instanceof JsonObject obj) {
            // 1.21.1-style {"tag": t} / {"item": x} objects read too.
            if (obj.get("tag") instanceof JsonPrimitive tag && tag.isString()) {
                return ingredient(new JsonPrimitive("#" + tag.getAsString()), strict, where);
            }
            return TcIngredient.of(stack(obj, strict, where));
        }
        throw new TcJsonException(where + ": expected \"mod:item\", \"#tag\", a list or an object");
    }

    public static TcStack stack(JsonElement value, boolean strict, String where) {
        if (value instanceof JsonPrimitive p && p.isString()) {
            return new TcStack(item(p.getAsString(), strict, where), 1, null);
        }
        if (!(value instanceof JsonObject obj)) {
            throw new TcJsonException(where + ": expected \"mod:item\" or {id, count, aspects, data}");
        }
        String id = string(obj, "id");
        if (id == null) {
            id = string(obj, "item");
        }
        if (id == null) {
            throw new TcJsonException(where + ": missing 'id'");
        }
        int count = obj.get("count") instanceof JsonPrimitive c && c.isNumber() ? c.getAsInt() : 1;
        if (count < 1) {
            throw new TcJsonException(where + ": count must be at least 1");
        }
        AspectList aspects = obj.has("aspects") ? aspects(obj.get("aspects"), where + ".aspects") : null;
        Map<String, String> data = new LinkedHashMap<>();
        if (obj.get("data") instanceof JsonObject dataObj) {
            for (Map.Entry<String, JsonElement> entry : dataObj.entrySet()) {
                data.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        return new TcStack(item(id, strict, where), count, aspects, data);
    }

    private static Identifier item(String text, boolean strict, String where) {
        Identifier id = Identifier.tryParse(text.trim());
        if (id == null) {
            throw new TcJsonException(where + ": '" + text + "' is not a valid item id");
        }
        if (strict && !BuiltInRegistries.ITEM.containsKey(id)) {
            throw new TcJsonException(where + ": unknown item '" + id + "'");
        }
        return id;
    }

    /** {aer: 5, ignis: 3} or "aer:5, ignis 3" (the editor's text field); zero amounts are dropped. */
    public static AspectList aspects(@Nullable JsonElement value, String where) {
        AspectList list = new AspectList();
        if (value == null || value.isJsonNull()) {
            return list;
        }
        if (value instanceof JsonObject obj) {
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                if (entry.getValue().isJsonNull()) {
                    continue; // a modify merge deleting the aspect
                }
                if (!(entry.getValue() instanceof JsonPrimitive p) || !p.isNumber()) {
                    throw new TcJsonException(where + "." + entry.getKey() + " must be a number");
                }
                addAspect(list, entry.getKey(), p.getAsInt(), where);
            }
            return list;
        }
        if (value instanceof JsonPrimitive p && p.isString()) {
            for (String part : p.getAsString().split(",")) {
                String entry = part.trim();
                if (entry.isEmpty()) {
                    continue;
                }
                String[] pieces = entry.split("[:=\\s]+");
                if (pieces.length != 2) {
                    throw new TcJsonException(where + ": '" + entry + "' should look like aer:5");
                }
                try {
                    addAspect(list, pieces[0], Integer.parseInt(pieces[1]), where);
                } catch (NumberFormatException e) {
                    throw new TcJsonException(where + ": '" + pieces[1] + "' is not a whole number");
                }
            }
            return list;
        }
        throw new TcJsonException(where + " must be like { aer: 5 } or \"aer:5, ignis:3\"");
    }

    private static void addAspect(AspectList list, String tag, int amount, String where) {
        Aspect aspect = Aspect.getAspect(tag.trim().toLowerCase(Locale.ROOT));
        if (aspect == null) {
            throw new TcJsonException(where + ": unknown aspect '" + tag + "'");
        }
        if (amount < 0) {
            throw new TcJsonException(where + ": " + tag + " can't be negative");
        }
        if (amount > 0) {
            list.add(aspect, amount);
        }
    }

    private static int instability(JsonObject json) {
        if (!json.has("instability")) {
            return 0;
        }
        if (!(json.get("instability") instanceof JsonPrimitive p) || !p.isNumber() || p.getAsInt() < 0) {
            throw new TcJsonException("'instability' must be a whole number, 0 or more");
        }
        return p.getAsInt();
    }

    private static Identifier enchantment(JsonObject json, boolean strict) {
        String text = string(json, "enchantment");
        Identifier id = text == null ? null : Identifier.tryParse(text);
        if (id == null) {
            throw new TcJsonException("'enchantment' must be an enchantment id like \"minecraft:sharpness\"");
        }
        return id;
    }

    private static @Nullable String string(JsonObject json, String key) {
        return json.get(key) instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }

    /** Aspect tags, for the editor's field hints. */
    public static List<String> aspectTags() {
        List<String> tags = new ArrayList<>();
        for (Aspect aspect : Aspect.values()) {
            tags.add(aspect.getTag());
        }
        return tags;
    }
}
