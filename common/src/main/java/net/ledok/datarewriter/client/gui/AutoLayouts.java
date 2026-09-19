package net.ledok.datarewriter.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import net.ledok.datarewriter.Datarewriter;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static net.ledok.datarewriter.client.gui.EditorLayout.FieldDef;
import static net.ledok.datarewriter.client.gui.EditorLayout.FieldType;
import static net.ledok.datarewriter.client.gui.EditorLayout.Kind;
import static net.ledok.datarewriter.client.gui.EditorLayout.SlotDef;
import static net.ledok.datarewriter.client.gui.EditorLayout.SlotFormat;

/**
 * Automatic editor support for modded recipe types: the server syncs every
 * recipe to the client, so for each unknown type one existing recipe is
 * encoded back to JSON with the real codec and a layout is inferred from that
 * sample — ingredient-shaped values become slots, numbers/strings become
 * fields. A hand-written layout file always takes precedence.
 */
public final class AutoLayouts {
    private AutoLayouts() {
    }

    /** Infers layouts for every synced recipe type not in {@code knownTypes}. */
    public static List<EditorLayout> infer(Set<String> knownTypes) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return List.of();
        }

        Map<String, List<RecipeHolder<?>>> byType = new TreeMap<>();
        for (RecipeHolder<?> holder : minecraft.level.getRecipeManager().getRecipes()) {
            ResourceLocation typeId = BuiltInRegistries.RECIPE_SERIALIZER.getKey(holder.value().getSerializer());
            if (typeId == null || typeId.getNamespace().equals("minecraft")) {
                continue; // vanilla types have hand-made layouts
            }
            String key = typeId.toString();
            if (!knownTypes.contains(key)) {
                byType.computeIfAbsent(key, k -> new ArrayList<>()).add(holder);
            }
        }

        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE,
                minecraft.level.registryAccess());
        List<EditorLayout> layouts = new ArrayList<>();
        for (Map.Entry<String, List<RecipeHolder<?>>> entry : byType.entrySet()) {
            // Sorting by id keeps the structure sample (the first one) stable
            // across reopens regardless of map iteration order.
            List<RecipeHolder<?>> holders = entry.getValue();
            holders.sort(Comparator.comparing(RecipeHolder::id));
            List<JsonObject> samples = new ArrayList<>();
            for (RecipeHolder<?> holder : holders) {
                if (samples.size() >= MAX_SAMPLES) {
                    break;
                }
                try {
                    if (Recipe.CODEC.encodeStart(ops, holder.value()).result().orElse(null)
                            instanceof JsonObject sample) {
                        samples.add(sample);
                    }
                } catch (Exception e) {
                    Datarewriter.LOGGER.debug("Recipe editor: could not encode {}: {}",
                            holder.id(), e.toString());
                }
            }
            if (samples.isEmpty()) {
                continue;
            }
            EditorLayout layout = inferLayout(entry.getKey(), samples);
            if (layout != null) {
                layouts.add(layout);
            }
        }
        return layouts;
    }

    private static final int MAX_SAMPLES = 200;
    private static final int MAX_SUGGESTIONS = 12;

    static EditorLayout inferLayout(String typeId, JsonObject sample) {
        return inferLayout(typeId, List.of(sample));
    }

    /**
     * The first sample defines the structure; the rest only contribute the
     * values seen for each text field (shown to the user as suggestions —
     * e.g. which "unit" strings a mod actually accepts).
     */
    static EditorLayout inferLayout(String typeId, List<JsonObject> samples) {
        JsonObject sample = samples.getFirst();
        List<SlotDef> slots = new ArrayList<>();
        List<FieldDef> fields = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : sample.entrySet()) {
            if (!entry.getKey().equals("type")) {
                classify(typeId, entry.getKey(), entry.getValue(), slots, fields);
            }
        }
        if (slots.isEmpty()) {
            return null;
        }

        List<FieldDef> withSuggestions = new ArrayList<>();
        for (FieldDef field : fields) {
            if (field.type() != FieldType.STRING) {
                withSuggestions.add(field);
                continue;
            }
            Set<String> values = new LinkedHashSet<>();
            for (JsonObject other : samples) {
                if (other.get(field.path()) instanceof JsonPrimitive p && p.isString()) {
                    values.add(p.getAsString());
                }
                if (values.size() >= MAX_SUGGESTIONS) {
                    break;
                }
            }
            withSuggestions.add(new FieldDef(field.path(), field.label(), field.type(),
                    field.defaultValue(), field.required(), List.copyOf(values)));
        }
        fields = withSuggestions;

        int inputs = 0;
        int results = 0;
        for (SlotDef slot : slots) {
            if (slot.result()) {
                results++;
            } else {
                inputs++;
            }
        }
        int rows = Math.max((inputs + 2) / 3, results);
        int[] crop = {0, 0, 176, Math.max(52, 24 + rows * 20)};
        EditorLayout.autoPlaceSlots(slots, crop);

        ResourceLocation id = ResourceLocation.parse(typeId);
        String displayName = prettify(id.getPath()) + " (" + id.getNamespace() + ")";
        return new EditorLayout(typeId, displayName, null, crop[0], crop[1], crop[2], crop[3],
                Kind.SLOTS, slots, fields);
    }

    private static void classify(String typeId, String key, JsonElement value,
                                 List<SlotDef> slots, List<FieldDef> fields) {
        boolean result = key.contains("result") || key.contains("output");
        if (value instanceof JsonPrimitive primitive) {
            if (primitive.isBoolean()) {
                fields.add(new FieldDef(key, prettify(key), FieldType.BOOL, primitive.getAsString(), false));
            } else if (primitive.isNumber()) {
                FieldType type = primitive.getAsString().matches("-?\\d+") ? FieldType.INT : FieldType.FLOAT;
                fields.add(new FieldDef(key, prettify(key), type, primitive.getAsString(), false));
            } else {
                String text = primitive.getAsString();
                ResourceLocation id = ResourceLocation.tryParse(text);
                if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                    slots.add(slot(key, result ? SlotFormat.SHORTHAND_RESULT : SlotFormat.STRING, result));
                } else {
                    fields.add(new FieldDef(key, prettify(key), FieldType.STRING, text, false));
                }
            }
            return;
        }
        if (value instanceof JsonObject obj) {
            SlotFormat format = objectFormat(obj);
            if (format != null) {
                slots.add(slot(key, format, result));
            } else {
                Datarewriter.LOGGER.debug("Recipe editor: {} — can't infer field '{}', skipping it",
                        typeId, key);
            }
            return;
        }
        if (value instanceof JsonArray array && !array.isEmpty()) {
            JsonElement first = array.get(0);
            SlotFormat format = null;
            if (first instanceof JsonObject obj) {
                format = objectFormat(obj);
            } else if (first instanceof JsonPrimitive p && p.isString()) {
                ResourceLocation id = ResourceLocation.tryParse(p.getAsString());
                if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                    format = SlotFormat.STRING;
                }
            }
            if (format != null) {
                for (int i = 0; i < array.size(); i++) {
                    slots.add(slot(key + "[]", format, result));
                }
            } else {
                Datarewriter.LOGGER.debug("Recipe editor: {} — can't infer list '{}', skipping it",
                        typeId, key);
            }
        }
    }

    /** Recognizes the common object shapes recipes use for items and fluids. */
    private static SlotFormat objectFormat(JsonObject obj) {
        Set<String> keys = obj.keySet();
        if (keys.equals(Set.of("item")) || keys.equals(Set.of("tag"))) {
            return SlotFormat.INGREDIENT;
        }
        if (obj.has("ingredient")) {
            return SlotFormat.COUNTED_INGREDIENT;
        }
        if (obj.has("item") && obj.has("count")) {
            return SlotFormat.ITEM_NAMED;
        }
        if (obj.get("id") instanceof JsonPrimitive p && p.isString()) {
            ResourceLocation id = ResourceLocation.tryParse(p.getAsString());
            if (id == null) {
                return null;
            }
            // Fluids first: mods sometimes register an item AND a fluid under
            // the same id, and an amount key marks the value as a fluid stack.
            if (BuiltInRegistries.FLUID.containsKey(id)) {
                if (obj.has("amount_mb")) {
                    return SlotFormat.FLUID;
                }
                if (obj.has("amount")) {
                    return SlotFormat.FLUID_AMOUNT;
                }
            }
            if (BuiltInRegistries.ITEM.containsKey(id)) {
                return SlotFormat.ITEM;
            }
        }
        return null;
    }

    private static SlotDef slot(String path, SlotFormat format, boolean result) {
        return new SlotDef(-1, -1, path, format, result, false);
    }

    private static String prettify(String key) {
        String spaced = key.replace('_', ' ').replace('/', ' ');
        return spaced.isEmpty() ? key
                : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
