package net.ledok.datarewriter.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.ledok.datarewriter.platform.Platform;
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
 * Bundled layouts for Forbidden Arcanus (verified against its 1.21.1 sources; the mod is NeoForge-only,
 * so these only ever activate on that loader):
 * <ul>
 * <li>{@code clibano_combustion} — the clibano furnace, drawn on the mod's own JEI recipe background.
 * One or two ingredients: the codec takes a single ingredient OR a {@code {"first","second"}} pair, so
 * the save collapses to whichever form fits ({@link EditorLayout#finishSave}) and loads expand it back.
 * Result is a {@code {"id","count"}} stack; extra fields for experience, cooking time, fire type
 * (picks the flame the decoration draws), residue type + chance, and the optional enhancer — written
 * as its definition id, which for the mod's own enhancers equals the relic item's id, so the slot picks
 * it as an item.</li>
 * <li>{@code apply_modifier} — a smithing-table recipe (template + addition + the modifier's id); the
 * base slot stays empty because the recipe applies to every item the modifier accepts, exactly like in
 * the smithing table itself.</li>
 * </ul>
 * {@code combine_aureal_tank} is a dynamic crafting recipe under {@code minecraft:crafting}, so it never
 * shows up as a type of its own and needs no hiding.
 */
final class ForbiddenArcanusLayouts {
    private static final String NS = "forbidden_arcanus";
    /** The mod's JEI category background (256x256 sheet; the panel is the top-left 147x97). */
    private static final ResourceLocation CLIBANO_GUI =
            ResourceLocation.fromNamespaceAndPath(NS, "textures/gui/jei/clibano_combustion.png");
    private static final ResourceLocation SMITHING_GUI =
            ResourceLocation.withDefaultNamespace("textures/gui/container/smithing.png");
    /** The mod's Hephaestus Smithing JEI panel (256x256 sheet; the panel is the top-left 148x108). */
    private static final ResourceLocation RITUAL_GUI =
            ResourceLocation.fromNamespaceAndPath(NS, "textures/gui/jei/hephaestus_forge/smithing.png");
    /** The eight pedestal positions around the forge, from the mod's JEI category. */
    private static final int[][] PEDESTALS = {
            {63, 13}, {82, 16}, {85, 35}, {82, 54}, {63, 57}, {44, 54}, {41, 35}, {44, 16}};

    private ForbiddenArcanusLayouts() {
    }

    static List<EditorLayout> forInstalledTypes() {
        List<EditorLayout> layouts = new ArrayList<>();
        if (serializerExists(NS + ":clibano_combustion")) {
            layouts.add(clibanoCombustion());
        }
        if (serializerExists(NS + ":apply_modifier")) {
            layouts.add(applyModifier());
        }
        if (Platform.INSTANCE.isModLoaded(NS)) {
            layouts.add(hephaestusRitual());
        }
        return layouts;
    }

    private static boolean serializerExists(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        return location != null && BuiltInRegistries.RECIPE_SERIALIZER.containsKey(location);
    }

    /**
     * Clibano furnace. Slot positions are the mod's own JEI category ones: enhancer 12/24,
     * ingredients 37/24 + 55/24, result 97/35. The flame (slot-frame column at 48/43) and the
     * progress arrow (74/43) are drawn as decoration from the same sheet, the flame matching
     * whatever the fire type field says.
     */
    private static EditorLayout clibanoCombustion() {
        List<SlotDef> slots = List.of(
                new SlotDef(37, 24, "ingredients.first", SlotFormat.INGREDIENT, false, true),
                new SlotDef(55, 24, "ingredients.second", SlotFormat.INGREDIENT, false, false),
                // The enhancer is a datapack reference, but the mod's enhancer ids equal their relic
                // items' ids — so an item slot writing the bare id picks them naturally.
                new SlotDef(12, 24, "enhancer", SlotFormat.ITEM_ID, false, false),
                new SlotDef(97, 35, "result", SlotFormat.ITEM, true, true));
        List<FieldDef> fields = List.of(
                new FieldDef("category", "Book tab", FieldType.STRING, "misc", false,
                        List.of("misc", "food", "blocks")),
                new FieldDef("experience", "XP", FieldType.FLOAT, "0.1", false),
                new FieldDef("cooking_time", "Time (ticks)", FieldType.INT, "100", false),
                new FieldDef("fire_type", "Fire", FieldType.STRING, "fire", false,
                        List.of("fire", "soul_fire", "enchanted_fire")),
                new FieldDef("residue.type", "Residue", FieldType.STRING, "", false,
                        List.of(NS + ":iron", NS + ":copper", NS + ":gold", NS + ":rune")),
                new FieldDef("residue.chance", "Residue chance (0-1)", FieldType.FLOAT, "", false));
        EditorLayout layout = new EditorLayout(NS + ":clibano_combustion", "Clibano combustion",
                CLIBANO_GUI, 0, 0, 147, 97, Kind.SLOTS, slots, fields);
        layout.finishSave = ForbiddenArcanusLayouts::collapseIngredients;
        layout.prepareLoad = ForbiddenArcanusLayouts::expandIngredients;
        layout.decoration = (graphics, left, top, partialTick, view) -> {
            // Flame column on the sheet: fire 151/1, soul fire 170/1, enchanted fire 189/1 (12x15).
            int flameU = switch (view.field("fire_type").trim()) {
                case "soul_fire" -> 170;
                case "enchanted_fire" -> 189;
                default -> 151;
            };
            graphics.blit(CLIBANO_GUI, left + 48, top + 43, flameU, 1, 12, 15);
            graphics.blit(CLIBANO_GUI, left + 74, top + 43, 148, 32, 13, 12); // progress arrow
        };
        return layout;
    }

    /**
     * The clibano codec's {@code ingredients} is one ingredient OR a {@code {"first","second"}} pair —
     * the slots always write the pair form, so a save with the second slot empty unwraps to the plain
     * one-ingredient form.
     */
    private static JsonObject collapseIngredients(JsonObject recipe) {
        if (recipe.get("ingredients") instanceof JsonObject pair
                && pair.has("first") && !pair.has("second")) {
            recipe.add("ingredients", pair.get("first"));
        }
        return recipe;
    }

    /** Load-side inverse: a plain one-ingredient form is wrapped so the first slot's path finds it. */
    private static JsonObject expandIngredients(JsonObject recipe) {
        JsonElement ingredients = recipe.get("ingredients");
        boolean plain = ingredients instanceof JsonArray
                || (ingredients instanceof JsonObject obj && !obj.has("first"));
        if (plain) {
            JsonObject pair = new JsonObject();
            pair.add("first", ingredients);
            recipe.add("ingredients", pair);
        }
        return recipe;
    }

    /**
     * Hephaestus Forge rituals — NOT recipes: each ritual is an entry of the datapack registry
     * {@code forbidden_arcanus:hephaestus_forge/ritual}, so this layout sets
     * {@link EditorLayout#registryTarget} and saves go through the registry pipeline (config rule +
     * live apply) instead of the recipe one. Drawn on the mod's own JEI panel: the ring of eight
     * pedestal slots, the main ingredient on the forge (center), up to four enhancer relics on the
     * left, the result on the right. A ritual input with {@code amount: n} loads as n filled pedestals
     * and each filled pedestal saves as one single-amount input — same JSON meaning, editor-friendly
     * shape. Only {@code create_item} results are editable; loading a tier-upgrade or transmute ritual
     * clears the result slot.
     */
    private static EditorLayout hephaestusRitual() {
        List<SlotDef> slots = new ArrayList<>();
        // {"ingredient": {"item"|"tag": id}} — one pedestal each; amount is left implicit (1).
        EditorLayout.SlotValueBuilder pedestal = (ref, count, chance) -> {
            JsonObject ingredient = new JsonObject();
            if (ref.startsWith("#")) {
                ingredient.addProperty("tag", ref.substring(1));
            } else {
                ingredient.addProperty("item", ref);
            }
            JsonObject input = new JsonObject();
            input.add("ingredient", ingredient);
            return input;
        };
        boolean first = true;
        for (int[] position : PEDESTALS) {
            slots.add(new SlotDef(position[0], position[1], "inputs[]", SlotFormat.INGREDIENT,
                    false, first, pedestal));
            first = false;
        }
        slots.add(new SlotDef(63, 35, "main_ingredient", SlotFormat.INGREDIENT, false, true));
        for (int i = 0; i < 4; i++) {
            // Enhancer definition ids equal their relic items' ids, like in the clibano layout.
            slots.add(new SlotDef(10, 12 + i * 21, "enhancers[]", SlotFormat.ITEM_ID, false, false));
        }
        slots.add(new SlotDef(122, 35, "result.result_item", SlotFormat.ITEM, true, true));

        List<FieldDef> fields = List.of(
                new FieldDef("forge_tier", "Forge tier (1-5)", FieldType.INT, "1", false),
                new FieldDef("match_tier_exact", "Exact tier only", FieldType.BOOL, "", false),
                new FieldDef("essences.aureal", "Aureal", FieldType.INT, "", false),
                new FieldDef("essences.souls", "Souls", FieldType.INT, "", false),
                new FieldDef("essences.blood", "Blood", FieldType.INT, "", false),
                new FieldDef("essences.experience", "Experience", FieldType.INT, "", false),
                new FieldDef("magic_circle", "Magic circle", FieldType.STRING, NS + ":create_item", true,
                        List.of(NS + ":create_item", NS + ":upgrade_tier", NS + ":upgrade_final_tier")),
                new FieldDef("duration", "Duration (ticks)", FieldType.INT, "", false));

        EditorLayout layout = new EditorLayout(NS + ":hephaestus_forge/ritual", "Hephaestus forge ritual",
                RITUAL_GUI, 0, 0, 148, 108, Kind.SLOTS, slots, fields);
        layout.registryTarget = NS + ":hephaestus_forge/ritual";
        JsonObject template = new JsonObject();
        JsonObject result = new JsonObject();
        result.addProperty("type", NS + ":create_item");
        template.add("result", result);
        template.add("essences", new JsonObject()); // the codec requires the key even when all-default
        layout.template = template;
        layout.prepareLoad = ForbiddenArcanusLayouts::expandRitual;
        return layout;
    }

    /** Load-side reshaping: amounts become repeated pedestals; non-editable result types are cleared. */
    private static JsonObject expandRitual(JsonObject ritual) {
        if (ritual.get("inputs") instanceof JsonArray inputs) {
            JsonArray expanded = new JsonArray();
            for (JsonElement element : inputs) {
                int amount = element instanceof JsonObject input
                        && input.get("amount") instanceof com.google.gson.JsonPrimitive p && p.isNumber()
                        ? p.getAsInt() : 1;
                for (int i = 0; i < Math.min(amount, 8) && expanded.size() < 8; i++) {
                    JsonObject copy = element.getAsJsonObject().deepCopy();
                    copy.remove("amount");
                    expanded.add(copy);
                }
            }
            ritual.add("inputs", expanded);
        }
        if (!(ritual.get("result") instanceof JsonObject result)
                || !(result.get("type") instanceof com.google.gson.JsonPrimitive type)
                || !type.getAsString().equals(NS + ":create_item")) {
            ritual.remove("result"); // tier-upgrade / transmute rituals: pick a new create_item result
        }
        if (ritual.get("enhancers") instanceof com.google.gson.JsonPrimitive) {
            ritual.remove("enhancers"); // the "#tag" holder-set form has no slot representation
        }
        return ritual;
    }

    /** Modifier smithing: the vanilla smithing GUI, slot positions from SmithingMenu. */
    private static EditorLayout applyModifier() {
        List<SlotDef> slots = List.of(
                new SlotDef(8, 48, "template", SlotFormat.INGREDIENT, false, true),
                new SlotDef(44, 48, "addition", SlotFormat.INGREDIENT, false, true));
        List<FieldDef> fields = List.of(
                new FieldDef("modifier", "Modifier id", FieldType.STRING, "", true,
                        List.of(NS + ":aquatic", NS + ":demolishing", NS + ":eternal",
                                NS + ":fiery", NS + ":magnetized", NS + ":soulbound")));
        return new EditorLayout(NS + ":apply_modifier", "Apply modifier (smithing)",
                SMITHING_GUI, 0, 0, 176, 166, 84, Kind.SLOTS, slots, fields);
    }
}
