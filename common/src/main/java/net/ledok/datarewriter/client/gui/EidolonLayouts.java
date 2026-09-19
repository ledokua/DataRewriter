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
 * Bundled layouts for Eidolon Repraised (verified against its 1.21.1 sources; the mod is
 * NeoForge-only, so these only ever activate on that loader). Drawn on the mod's codex page art,
 * like its JEI pages:
 * <ul>
 * <li>{@code worktable} — a 3x3 shaped grid plus the four corner reagents. The reagents share the
 * recipe's letter key: the save writes them as extra (lowercase) letters and a {@code reagents}
 * pattern row, in the order top, right, bottom, left.</li>
 * <li>{@code crucible} — up to four steps of up to four items each, with a stirs count per step;
 * the save packs the filled rows into the codec's {@code steps} list. A step needs items OR stirs.
 * (Two of the mod's own recipes put more than four items in one step — those load truncated;
 * edit them in JSON.)</li>
 * <li>the five {@code ritual_brazier*} types — reagent on the brazier, up to eight pedestal items
 * on the arc below it, an optional focus item, plus what distinguishes the variant: a crafted
 * result, a summoned entity id, commands, the ritual id (with an optional invariant item), or a
 * structure to locate.</li>
 * <li>{@code athame_foraging} — the block broken (as its item) and what it drops.</li>
 * <li>{@code chant_conversion} — input, output and the devotion fields.</li>
 * <li>{@code dye} — a vanilla-shapeless recipe under its own type, drawn as the crafting table.</li>
 * </ul>
 * {@code chant} recipes (sign sequences) stay JSON-only.
 */
final class EidolonLayouts {
    private static final String NS = "eidolon_repraised";
    /** Codex page art: 128x160 at the sheet's top left (JEI offsets it by 5/4; we crop instead). */
    private static final ResourceLocation WORKTABLE_GUI =
            ResourceLocation.fromNamespaceAndPath(NS, "textures/gui/codex_worktable_page.png");
    private static final ResourceLocation CRUCIBLE_GUI =
            ResourceLocation.fromNamespaceAndPath(NS, "textures/gui/codex_crucible_page.png");
    private static final ResourceLocation RITUAL_GUI =
            ResourceLocation.fromNamespaceAndPath(NS, "textures/gui/codex_ritual_page.png");
    private static final ResourceLocation CRAFTING_GUI =
            ResourceLocation.withDefaultNamespace("textures/gui/container/crafting_table.png");
    /**
     * Eight pedestal positions on the arc below the brazier — the mod's JEI ring for eight items
     * (center 69/91, radius 48, spread around straight down), shifted from page to art space.
     */
    private static final int[][] ARC = {
            {103, 88}, {95, 105}, {82, 118}, {65, 126}, {46, 126}, {29, 118}, {16, 105}, {8, 88}};
    private static final int CRUCIBLE_STEPS = 4;
    private static final int CRUCIBLE_ITEMS_PER_STEP = 4;

    private EidolonLayouts() {
    }

    static List<EditorLayout> forInstalledTypes() {
        List<EditorLayout> layouts = new ArrayList<>();
        if (serializerExists(NS + ":worktable")) {
            layouts.add(worktable());
        }
        if (serializerExists(NS + ":crucible")) {
            layouts.add(crucible());
        }
        if (serializerExists(NS + ":ritual_brazier_crafting")) {
            layouts.add(craftingRitual());
        }
        if (serializerExists(NS + ":ritual_brazier_summoning")) {
            layouts.add(summoningRitual());
        }
        if (serializerExists(NS + ":ritual_brazier_command")) {
            layouts.add(commandRitual());
        }
        if (serializerExists(NS + ":ritual_brazier")) {
            layouts.add(genericRitual());
        }
        if (serializerExists(NS + ":ritual_brazier_location")) {
            layouts.add(locationRitual());
        }
        if (serializerExists(NS + ":athame_foraging")) {
            layouts.add(foraging());
        }
        if (serializerExists(NS + ":chant_conversion")) {
            layouts.add(chantConversion());
        }
        if (serializerExists(NS + ":dye")) {
            layouts.add(dye());
        }
        return layouts;
    }

    private static boolean serializerExists(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        return location != null && BuiltInRegistries.RECIPE_SERIALIZER.containsKey(location);
    }

    /**
     * The worktable's shaped grid plus its four corner reagents. The grid is normal shaped
     * pattern/key; the reagent slots write into a scratch array the save turns into extra
     * lowercase key letters plus the {@code reagents} pattern (one row of four).
     */
    private static EditorLayout worktable() {
        List<SlotDef> slots = new ArrayList<>();
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                slots.add(new SlotDef(39 + col * 17, 33 + row * 17, "", SlotFormat.STRING, false, false));
            }
        }
        // Top, right, bottom, left — the order the mod's JEI page and screen use.
        int[][] corners = {{56, 11}, {95, 50}, {56, 89}, {17, 50}};
        for (int[] corner : corners) {
            slots.add(new SlotDef(corner[0], corner[1], "reagent_items[]", SlotFormat.INGREDIENT,
                    false, true));
        }
        slots.add(new SlotDef(56, 129, "result", SlotFormat.ITEM, true, true));
        EditorLayout layout = new EditorLayout(NS + ":worktable", "Worktable",
                WORKTABLE_GUI, 0, 0, 128, 160, Kind.SHAPED, slots, List.of());
        layout.finishSave = EidolonLayouts::packReagents;
        layout.prepareLoad = EidolonLayouts::unpackReagents;
        return layout;
    }

    /** Scratch {@code reagent_items} -> lowercase letters in {@code key} + a {@code reagents} row. */
    private static JsonObject packReagents(JsonObject recipe) {
        if (!(recipe.remove("reagent_items") instanceof JsonArray reagents)
                || !(recipe.get("key") instanceof JsonObject key)) {
            return recipe;
        }
        StringBuilder row = new StringBuilder();
        List<JsonElement> assigned = new ArrayList<>();
        for (JsonElement reagent : reagents) {
            int existing = -1;
            for (int i = 0; i < assigned.size(); i++) {
                if (assigned.get(i).equals(reagent)) {
                    existing = i;
                    break;
                }
            }
            if (existing < 0) {
                existing = assigned.size();
                assigned.add(reagent);
                key.add(String.valueOf((char) ('a' + existing)), reagent);
            }
            row.append((char) ('a' + existing));
        }
        JsonArray pattern = new JsonArray();
        pattern.add(row.toString());
        recipe.add("reagents", pattern);
        return recipe;
    }

    /** The inverse: resolves the {@code reagents} letters through {@code key} into the scratch array. */
    private static JsonObject unpackReagents(JsonObject recipe) {
        if (!(recipe.get("reagents") instanceof JsonArray pattern)
                || !(recipe.get("key") instanceof JsonObject key)) {
            return recipe;
        }
        JsonArray items = new JsonArray();
        for (JsonElement rowElement : pattern) {
            if (!(rowElement instanceof JsonPrimitive row)) {
                continue;
            }
            for (char letter : row.getAsString().toCharArray()) {
                JsonElement ingredient = key.get(String.valueOf(letter));
                if (ingredient != null) {
                    items.add(ingredient.deepCopy());
                }
            }
        }
        recipe.add("reagent_items", items);
        return recipe;
    }

    /**
     * The crucible: four step rows of four item slots each (the mod's JEI rows), a stirs field per
     * step, packed into the codec's {@code steps} list on save.
     */
    private static EditorLayout crucible() {
        List<SlotDef> slots = new ArrayList<>();
        for (int step = 1; step <= CRUCIBLE_STEPS; step++) {
            for (int item = 0; item < CRUCIBLE_ITEMS_PER_STEP; item++) {
                slots.add(new SlotDef(23 + item * 17, 23 + (step - 1) * 20, "step" + step + "_items[]",
                        SlotFormat.INGREDIENT, false, step == 1 && item == 0));
            }
        }
        slots.add(new SlotDef(55, 114, "result", SlotFormat.ITEM, true, true));
        List<FieldDef> fields = new ArrayList<>();
        for (int step = 1; step <= CRUCIBLE_STEPS; step++) {
            fields.add(new FieldDef("stirs_" + step, "Stirs (step " + step + ")", FieldType.INT,
                    "", false));
        }
        EditorLayout layout = new EditorLayout(NS + ":crucible", "Crucible",
                CRUCIBLE_GUI, 0, 0, 128, 160, Kind.SLOTS, slots, fields);
        layout.finishSave = EidolonLayouts::packSteps;
        layout.prepareLoad = EidolonLayouts::unpackSteps;
        layout.decoration = (graphics, left, top, partialTick, view) -> {
            for (int step = 0; step < CRUCIBLE_STEPS; step++) {
                graphics.blit(CRUCIBLE_GUI, left, top + 23 + step * 20, 128, 0, 128, 20);
            }
        };
        return layout;
    }

    /** Scratch {@code stepN_items}/{@code stirs_N} -> the codec's {@code steps} list, in order. */
    private static JsonObject packSteps(JsonObject recipe) {
        JsonArray steps = new JsonArray();
        for (int i = 1; i <= CRUCIBLE_STEPS; i++) {
            JsonElement items = recipe.remove("step" + i + "_items");
            JsonElement stirs = recipe.remove("stirs_" + i);
            JsonObject step = new JsonObject();
            if (stirs instanceof JsonPrimitive s && s.getAsInt() > 0) {
                step.add("stirs", stirs);
            }
            if (items instanceof JsonArray array && !array.isEmpty()) {
                step.add("items", items);
            }
            if (!step.keySet().isEmpty()) {
                steps.add(step);
            }
        }
        recipe.add("steps", steps);
        return recipe;
    }

    /** The inverse: the first four steps spread into the scratch arrays and stir fields. */
    private static JsonObject unpackSteps(JsonObject recipe) {
        if (recipe.get("steps") instanceof JsonArray steps) {
            int index = 1;
            for (JsonElement element : steps) {
                if (index > CRUCIBLE_STEPS || !(element instanceof JsonObject step)) {
                    break;
                }
                if (step.get("items") instanceof JsonArray items) {
                    recipe.add("step" + index + "_items", items);
                }
                if (step.get("stirs") != null) {
                    recipe.add("stirs_" + index, step.get("stirs"));
                }
                index++;
            }
        }
        return recipe;
    }

    /** The slots every brazier ritual shares: reagent, pedestal arc, one focus item. */
    private static List<SlotDef> ritualSlots() {
        List<SlotDef> slots = new ArrayList<>();
        for (int[] position : ARC) {
            slots.add(new SlotDef(position[0], position[1], "pedestal_items[]", SlotFormat.INGREDIENT,
                    false, false));
        }
        slots.add(new SlotDef(55, 81, "reagent", SlotFormat.INGREDIENT, false, true));
        slots.add(new SlotDef(86, 78, "focus_items[]", SlotFormat.INGREDIENT, false, false));
        return slots;
    }

    /** Both list keys are required by every ritual codec, even when empty. */
    private static JsonObject ritualTemplate() {
        JsonObject template = new JsonObject();
        template.add("pedestal_items", new JsonArray());
        template.add("focus_items", new JsonArray());
        return template;
    }

    private static EditorLayout ritualLayout(String type, String name, List<SlotDef> slots,
                                             List<FieldDef> fields) {
        EditorLayout layout = new EditorLayout(NS + ":" + type, name,
                RITUAL_GUI, 0, 0, 128, 160, Kind.SLOTS, slots, fields);
        layout.template = ritualTemplate();
        return layout;
    }

    private static FieldDef healthField() {
        return new FieldDef("health_requirement", "Health cost", FieldType.FLOAT, "", false);
    }

    private static EditorLayout craftingRitual() {
        List<SlotDef> slots = ritualSlots();
        slots.add(new SlotDef(57, 41, "result", SlotFormat.ITEM, true, true));
        List<FieldDef> fields = List.of(
                new FieldDef("keep_nbt_of_reagent", "Keep reagent's data", FieldType.BOOL, "", false),
                healthField());
        return ritualLayout("ritual_brazier_crafting", "Brazier ritual (crafting)", slots, fields);
    }

    private static EditorLayout summoningRitual() {
        List<FieldDef> fields = List.of(
                new FieldDef("output", "Entity", FieldType.STRING, "", true,
                        List.of("minecraft:zombie", "minecraft:skeleton", "minecraft:slime")));
        return ritualLayout("ritual_brazier_summoning", "Brazier ritual (summoning)",
                ritualSlots(), fields);
    }

    private static EditorLayout commandRitual() {
        List<FieldDef> fields = List.of(
                new FieldDef("command_1", "Command 1", FieldType.STRING, "", true),
                new FieldDef("command_2", "Command 2", FieldType.STRING, "", false),
                healthField());
        EditorLayout layout = ritualLayout("ritual_brazier_command", "Brazier ritual (command)",
                ritualSlots(), fields);
        layout.finishSave = EidolonLayouts::packCommands;
        layout.prepareLoad = EidolonLayouts::unpackCommands;
        return layout;
    }

    private static JsonObject packCommands(JsonObject recipe) {
        JsonArray commands = new JsonArray();
        for (int i = 1; i <= 2; i++) {
            if (recipe.remove("command_" + i) instanceof JsonPrimitive command
                    && !command.getAsString().isBlank()) {
                commands.add(command);
            }
        }
        recipe.add("commands", commands);
        return recipe;
    }

    private static JsonObject unpackCommands(JsonObject recipe) {
        if (recipe.get("commands") instanceof JsonArray commands) {
            for (int i = 0; i < Math.min(2, commands.size()); i++) {
                recipe.add("command_" + (i + 1), commands.get(i));
            }
        }
        return recipe;
    }

    private static EditorLayout genericRitual() {
        List<SlotDef> slots = ritualSlots();
        // The one invariant item some rituals keep on a pedestal (never consumed).
        slots.add(new SlotDef(86, 55, "invariant_items[]", SlotFormat.INGREDIENT, false, false));
        List<FieldDef> fields = List.of(
                new FieldDef("ritual", "Ritual id", FieldType.STRING, "", true,
                        List.of(NS + ":daylight", NS + ":moonlight", NS + ":purify",
                                NS + ":crystal", NS + ":allure", NS + ":repelling")),
                healthField());
        EditorLayout layout = ritualLayout("ritual_brazier", "Brazier ritual (generic)", slots, fields);
        layout.template.add("invariant_items", new JsonArray());
        return layout;
    }

    private static EditorLayout locationRitual() {
        List<FieldDef> fields = List.of(
                new FieldDef("structure", "Structure", FieldType.STRING, "", true,
                        List.of("minecraft:stronghold", "minecraft:mineshaft")),
                healthField());
        return ritualLayout("ritual_brazier_location", "Brazier ritual (location)",
                ritualSlots(), fields);
    }

    private static EditorLayout foraging() {
        List<SlotDef> slots = List.of(
                new SlotDef(8, 10, "block", SlotFormat.INGREDIENT, false, true),
                new SlotDef(96, 10, "output", SlotFormat.ITEM, true, true));
        return new EditorLayout(NS + ":athame_foraging", "Athame foraging",
                null, 0, 0, 120, 36, Kind.SLOTS, slots, List.of());
    }

    private static EditorLayout chantConversion() {
        List<SlotDef> slots = List.of(
                new SlotDef(8, 10, "input", SlotFormat.INGREDIENT, false, true),
                new SlotDef(96, 10, "output", SlotFormat.ITEM, true, true));
        List<FieldDef> fields = List.of(
                new FieldDef("min_devotion", "Min devotion", FieldType.FLOAT, "0", true),
                new FieldDef("conversion_cost", "Conversion cost", FieldType.FLOAT, "", false),
                new FieldDef("deity", "Deity", FieldType.STRING, "", false,
                        List.of(NS + ":dark_deity", NS + ":light_deity")));
        return new EditorLayout(NS + ":chant_conversion", "Chant conversion",
                null, 0, 0, 120, 36, Kind.SLOTS, slots, fields);
    }

    private static EditorLayout dye() {
        List<SlotDef> slots = new ArrayList<>();
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                slots.add(new SlotDef(30 + col * 18, 17 + row * 18, "", SlotFormat.STRING, false, false));
            }
        }
        slots.add(new SlotDef(124, 35, "result", SlotFormat.SHORTHAND_RESULT, true, true));
        return new EditorLayout(NS + ":dye", "Dye (crafting)",
                CRAFTING_GUI, 0, 0, 176, 166, 84, Kind.SHAPELESS, slots, List.of());
    }
}
