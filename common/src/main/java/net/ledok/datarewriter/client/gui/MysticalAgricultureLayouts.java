package net.ledok.datarewriter.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

import static net.ledok.datarewriter.client.gui.EditorLayout.FieldDef;
import static net.ledok.datarewriter.client.gui.EditorLayout.FieldType;
import static net.ledok.datarewriter.client.gui.EditorLayout.Kind;
import static net.ledok.datarewriter.client.gui.EditorLayout.SlotDef;
import static net.ledok.datarewriter.client.gui.EditorLayout.SlotFormat;

/**
 * Bundled layouts for Mystical Agriculture (verified against its 1.21.1 sources; the mod is
 * NeoForge-only, so these only ever activate on that loader). Drawn on the mod's own JEI textures
 * with its slot positions:
 * <ul>
 * <li>{@code infusion} — the infusion altar: seed in the middle, up to eight pedestal ingredients
 * around it. The mod's own seed recipes use its dynamic {@code crop_component} ingredients, which
 * the slots can't show or write — those load with empty input slots; recipes made here use plain
 * items and {@code #tags}, which the altar accepts just the same.</li>
 * <li>{@code awakening} — the awakening altar: the corners are the four essence vessel stacks
 * (counted item stacks, in vessel order), the edges the up-to-four pedestal ingredients.</li>
 * <li>{@code enchanter} — two counted ingredients and the enchantment id they produce (always at
 * the enchantment's max level).</li>
 * <li>{@code reprocessor} — one input, one result, furnace-style.</li>
 * <li>{@code soul_extraction} — one input plus the mob soul type id and how many souls it holds.</li>
 * <li>{@code soulium_spawner} — a counted input and up to four weighted entity ids, written as
 * entity/weight field pairs that save into the codec's {@code entities} list.</li>
 * </ul>
 * {@code farmland_till} and {@code soul_jar_empty} are dynamic crafting recipes under
 * {@code minecraft:crafting}, so they never show up as types of their own; JEI's "crux" page is
 * generated display data, not recipes.
 */
final class MysticalAgricultureLayouts {
    private static final String NS = "mysticalagriculture";
    private static final ResourceLocation INFUSION_GUI =
            ResourceLocation.fromNamespaceAndPath(NS, "textures/jei/infusion.png");
    private static final ResourceLocation ENCHANTER_GUI =
            ResourceLocation.fromNamespaceAndPath(NS, "textures/jei/enchanter.png");
    private static final ResourceLocation REPROCESSOR_GUI =
            ResourceLocation.fromNamespaceAndPath(NS, "textures/jei/reprocessor.png");
    private static final ResourceLocation SPAWNER_GUI =
            ResourceLocation.fromNamespaceAndPath(NS, "textures/jei/soulium_spawner.png");
    /** The altar ring in the mod's JEI order: NW, N, NE, E, SE, S, SW, W around the center (33, 33). */
    private static final int[][] RING = {
            {7, 7}, {33, 1}, {59, 7}, {65, 33}, {59, 59}, {33, 64}, {7, 59}, {1, 33}};

    private MysticalAgricultureLayouts() {
    }

    static List<EditorLayout> forInstalledTypes() {
        List<EditorLayout> layouts = new ArrayList<>();
        if (serializerExists(NS + ":infusion")) {
            layouts.add(infusion());
        }
        if (serializerExists(NS + ":awakening")) {
            layouts.add(awakening());
        }
        if (serializerExists(NS + ":enchanter")) {
            layouts.add(enchanter());
        }
        if (serializerExists(NS + ":reprocessor")) {
            layouts.add(reprocessor());
        }
        if (serializerExists(NS + ":soul_extraction")) {
            layouts.add(soulExtraction());
        }
        if (serializerExists(NS + ":soulium_spawner")) {
            layouts.add(souliumSpawner());
        }
        return layouts;
    }

    private static boolean serializerExists(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        return location != null && BuiltInRegistries.RECIPE_SERIALIZER.containsKey(location);
    }

    /** Cucumber's IngredientWithCount: {@code {"item"|"tag": id, "count": n}}. */
    private static JsonElement countedIngredient(String ref, int count) {
        JsonObject value = new JsonObject();
        if (ref.startsWith("#")) {
            value.addProperty("tag", ref.substring(1));
        } else {
            value.addProperty("item", ref);
        }
        value.addProperty("count", Math.max(1, count));
        return value;
    }

    private static EditorLayout infusion() {
        List<SlotDef> slots = new ArrayList<>();
        boolean first = true;
        for (int[] position : RING) {
            slots.add(new SlotDef(position[0], position[1], "ingredients[]", SlotFormat.INGREDIENT,
                    false, first));
            first = false;
        }
        slots.add(new SlotDef(33, 33, "input", SlotFormat.INGREDIENT, false, true));
        slots.add(new SlotDef(123, 33, "result", SlotFormat.ITEM, true, true));
        List<FieldDef> fields = List.of(
                new FieldDef("transfer_components", "Keep input's data", FieldType.BOOL, "", false));
        return new EditorLayout(NS + ":infusion", "Infusion altar",
                INFUSION_GUI, 0, 0, 144, 81, Kind.SLOTS, slots, fields);
    }

    private static EditorLayout awakening() {
        List<SlotDef> slots = new ArrayList<>();
        // Corners: the four essence vessel stacks; edges: the up-to-four pedestal ingredients —
        // the same alternating ring the mod's JEI page shows.
        int[][] corners = {{7, 7}, {59, 7}, {59, 59}, {7, 59}};
        int[][] edges = {{33, 1}, {65, 33}, {33, 64}, {1, 33}};
        for (int i = 0; i < 4; i++) {
            slots.add(new SlotDef(corners[i][0], corners[i][1], "essences[]", SlotFormat.ITEM,
                    false, i == 0));
        }
        for (int i = 0; i < 4; i++) {
            slots.add(new SlotDef(edges[i][0], edges[i][1], "ingredients[]", SlotFormat.INGREDIENT,
                    false, i == 0));
        }
        slots.add(new SlotDef(33, 33, "input", SlotFormat.INGREDIENT, false, true));
        slots.add(new SlotDef(123, 33, "result", SlotFormat.ITEM, true, true));
        List<FieldDef> fields = List.of(
                new FieldDef("transfer_components", "Keep input's data", FieldType.BOOL, "", false));
        return new EditorLayout(NS + ":awakening", "Awakening altar",
                INFUSION_GUI, 0, 0, 144, 81, Kind.SLOTS, slots, fields);
    }

    private static EditorLayout enchanter() {
        EditorLayout.SlotValueBuilder counted = (ref, count, chance) -> countedIngredient(ref, count);
        List<SlotDef> slots = List.of(
                new SlotDef(1, 5, "ingredients[]", SlotFormat.ITEM_NAMED, false, true, false, true, counted),
                new SlotDef(23, 5, "ingredients[]", SlotFormat.ITEM_NAMED, false, false, false, true, counted));
        List<FieldDef> fields = List.of(
                new FieldDef("enchantment", "Enchantment", FieldType.STRING, "", true,
                        List.of("minecraft:sharpness", "minecraft:protection", "minecraft:mending",
                                "minecraft:fortune", "minecraft:looting", "minecraft:unbreaking")));
        return new EditorLayout(NS + ":enchanter", "Enchanter",
                ENCHANTER_GUI, 0, 0, 144, 26, Kind.SLOTS, slots, fields);
    }

    private static EditorLayout reprocessor() {
        List<SlotDef> slots = List.of(
                new SlotDef(1, 5, "input", SlotFormat.INGREDIENT, false, true),
                new SlotDef(61, 5, "result", SlotFormat.ITEM, true, true));
        EditorLayout layout = new EditorLayout(NS + ":reprocessor", "Reprocessor",
                REPROCESSOR_GUI, 0, 0, 82, 26, Kind.SLOTS, slots, List.of());
        layout.decoration = (graphics, left, top, partialTick, view) ->
                graphics.blit(REPROCESSOR_GUI, left + 24, top + 4, 85, 0, 24, 17);
        return layout;
    }

    private static EditorLayout soulExtraction() {
        List<SlotDef> slots = List.of(
                new SlotDef(1, 5, "input", SlotFormat.INGREDIENT, false, true));
        List<FieldDef> fields = List.of(
                new FieldDef("result.type", "Mob soul type", FieldType.STRING, "", true,
                        List.of(NS + ":zombie", NS + ":skeleton", NS + ":creeper",
                                NS + ":spider", NS + ":enderman", NS + ":blaze")),
                new FieldDef("result.souls", "Souls", FieldType.FLOAT, "1.0", true));
        EditorLayout layout = new EditorLayout(NS + ":soul_extraction", "Soul extractor",
                REPROCESSOR_GUI, 0, 0, 82, 26, Kind.SLOTS, slots, fields);
        layout.decoration = (graphics, left, top, partialTick, view) ->
                graphics.blit(REPROCESSOR_GUI, left + 24, top + 4, 85, 0, 24, 17);
        return layout;
    }

    private static EditorLayout souliumSpawner() {
        EditorLayout.SlotValueBuilder counted = (ref, count, chance) -> countedIngredient(ref, count);
        List<SlotDef> slots = List.of(
                new SlotDef(1, 5, "input", SlotFormat.ITEM_NAMED, false, true, false, true, counted));
        List<FieldDef> fields = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            fields.add(new FieldDef("entity_" + i, "Entity " + i, FieldType.STRING, "", i == 1,
                    List.of("minecraft:zombie", "minecraft:skeleton", "minecraft:creeper")));
            fields.add(new FieldDef("weight_" + i, "Weight " + i, FieldType.INT, i == 1 ? "1" : "", false));
        }
        EditorLayout layout = new EditorLayout(NS + ":soulium_spawner", "Soulium spawner",
                SPAWNER_GUI, 0, 0, 82, 26, Kind.SLOTS, slots, fields);
        layout.decoration = (graphics, left, top, partialTick, view) ->
                graphics.blit(SPAWNER_GUI, left + 24, top + 4, 85, 0, 24, 17);
        layout.finishSave = MysticalAgricultureLayouts::packEntities;
        layout.prepareLoad = MysticalAgricultureLayouts::unpackEntities;
        return layout;
    }

    /** Turns the {@code entity_N}/{@code weight_N} scratch fields into the codec's weighted list. */
    private static JsonObject packEntities(JsonObject recipe) {
        JsonArray entities = new JsonArray();
        for (int i = 1; i <= 4; i++) {
            JsonElement entity = recipe.remove("entity_" + i);
            JsonElement weight = recipe.remove("weight_" + i);
            if (entity instanceof JsonPrimitive p && !p.getAsString().isBlank()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("entity", p.getAsString());
                entry.addProperty("weight", weight instanceof JsonPrimitive w ? w.getAsInt() : 1);
                entities.add(entry);
            }
        }
        recipe.add("entities", entities);
        return recipe;
    }

    /** The inverse: spreads the {@code entities} list into the scratch fields for loading. */
    private static JsonObject unpackEntities(JsonObject recipe) {
        if (recipe.get("entities") instanceof JsonArray entities) {
            int slot = 1;
            for (JsonElement element : entities) {
                if (slot > 4 || !(element instanceof JsonObject entry)) {
                    break;
                }
                recipe.add("entity_" + slot, entry.get("entity"));
                recipe.add("weight_" + slot, entry.get("weight"));
                slot++;
            }
        }
        return recipe;
    }
}
