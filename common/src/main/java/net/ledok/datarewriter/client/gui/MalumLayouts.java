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
 * Bundled layouts for Malum (verified against its 1.21.1 sources; the mod is NeoForge-only, so
 * these only ever activate on that loader). Drawn on the mod's own JEI codex panels.
 * <p>
 * Two Lodestone shapes appear everywhere: sized ingredients ({@code {"item"|"tag", "count"}} — the
 * slot's scroll count writes the count) and spirit ingredients ({@code {"type": spirit id,
 * "count"}}). Spirit slots take the spirit <b>items</b> (Earthen Spirit etc.) and write their id
 * with the {@code _spirit} suffix stripped, which is exactly the spirit type id; loading maps the
 * type back to the item.
 * <ul>
 * <li>{@code spirit_infusion} — the spirit altar: spirits down the left, extra inputs down the
 * right, input above the altar, result below.</li>
 * <li>{@code soul_binding} — same shape as infusion (no JEI page of its own), but the result is a
 * geas effect id field instead of an item.</li>
 * <li>{@code runeworking} — the runic workbench column: spirit stack, rock, rune, plus the
 * required rune sound id.</li>
 * <li>{@code spirit_focusing} — the spirit crucible: spirits on top, input, result, plus time and
 * durability cost.</li>
 * <li>{@code spirit_repair} — the repair pylon: spirits on top, repair material, up to six
 * repairable items (the mod's biggest lists go to twelve — those load truncated), and the repair
 * percentage. The repaired item is derived, so there is no result.</li>
 * <li>{@code unchained_transmutation} and {@code void_favor} — simple input to result pages.</li>
 * <li>{@code node_smelting} / {@code node_blasting} — furnace recipes whose output is an
 * ingredient (tags allowed) with a separate output count.</li>
 * </ul>
 * {@code conjuncture_crystallarium} and {@code ore_derealization} carry block-state and world-rule
 * data the editor can't express — hidden, JSON-only.
 */
final class MalumLayouts {
    private static final String NS = "malum";
    private static final ResourceLocation INFUSION_GUI = gui("spirit_infusion_jei");
    private static final ResourceLocation RUNEWORKING_GUI = gui("runeworking_jei");
    private static final ResourceLocation FOCUSING_GUI = gui("spirit_focusing_jei");
    private static final ResourceLocation REPAIR_GUI = gui("spirit_repair_jei");
    private static final ResourceLocation WELL_GUI = gui("weeping_well_jei");
    private static final ResourceLocation TRANSMUTATION_GUI = gui("spirit_transmutation_jei");
    private static final List<String> SPIRIT_ITEMS = List.of(
            NS + ":earthen_spirit", NS + ":infernal_spirit", NS + ":arcane_spirit",
            NS + ":aqueous_spirit", NS + ":aerial_spirit", NS + ":sacred_spirit",
            NS + ":wicked_spirit", NS + ":eldritch_spirit");

    private MalumLayouts() {
    }

    private static ResourceLocation gui(String name) {
        return ResourceLocation.fromNamespaceAndPath(NS, "textures/gui/" + name + ".png");
    }

    static List<EditorLayout> forInstalledTypes() {
        List<EditorLayout> layouts = new ArrayList<>();
        if (serializerExists(NS + ":spirit_infusion")) {
            layouts.add(spiritInfusion());
        }
        if (serializerExists(NS + ":soul_binding")) {
            layouts.add(soulBinding());
        }
        if (serializerExists(NS + ":runeworking")) {
            layouts.add(runeworking());
        }
        if (serializerExists(NS + ":spirit_focusing")) {
            layouts.add(spiritFocusing());
        }
        if (serializerExists(NS + ":spirit_repair")) {
            layouts.add(spiritRepair());
        }
        if (serializerExists(NS + ":unchained_transmutation")) {
            layouts.add(transmutation());
        }
        if (serializerExists(NS + ":void_favor")) {
            layouts.add(voidFavor());
        }
        if (serializerExists(NS + ":node_smelting")) {
            layouts.add(nodeCooking("node_smelting", "Node smelting (furnace)", "furnace"));
        }
        if (serializerExists(NS + ":node_blasting")) {
            layouts.add(nodeCooking("node_blasting", "Node blasting (blast furnace)", "blast_furnace"));
        }
        return layouts;
    }

    private static boolean serializerExists(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        return location != null && BuiltInRegistries.RECIPE_SERIALIZER.containsKey(location);
    }

    /** Lodestone's flat SizedIngredient: {@code {"item"|"tag": id, "count": n}}. */
    private static final EditorLayout.SlotValueBuilder SIZED = (ref, count, chance) -> {
        JsonObject value = new JsonObject();
        if (ref.startsWith("#")) {
            value.addProperty("tag", ref.substring(1));
        } else {
            value.addProperty("item", ref);
        }
        value.addProperty("count", Math.max(1, count));
        return value;
    };

    /** A spirit ingredient: the picked spirit item's id minus {@code _spirit} is the type id. */
    private static final EditorLayout.SlotValueBuilder SPIRIT = (ref, count, chance) -> {
        JsonObject value = new JsonObject();
        value.addProperty("type", ref.endsWith("_spirit")
                ? ref.substring(0, ref.length() - "_spirit".length()) : ref);
        value.addProperty("count", Math.max(1, count));
        return value;
    };

    /** A sized-ingredient slot (counted, tags allowed). */
    private static SlotDef sized(int x, int y, String path, boolean required) {
        return new SlotDef(x, y, path, SlotFormat.ITEM_NAMED, false, required, false, true, SIZED);
    }

    /** A spirit slot on {@code spirits[]} (counted; the picker's spirit items map to type ids). */
    private static SlotDef spirit(int x, int y, boolean required) {
        return new SlotDef(x, y, "spirits[]", SlotFormat.ITEM_NAMED, false, required, false, false, SPIRIT);
    }

    /** {@code spirits} is required by every codec that has it; loading maps types back to items. */
    private static JsonObject spiritsTemplate() {
        JsonObject template = new JsonObject();
        template.add("spirits", new JsonArray());
        return template;
    }

    /** Rewrites {@code spirits[].type} to the spirit item id so the slots can show and load them. */
    private static JsonObject spiritsToItems(JsonObject recipe) {
        if (recipe.get("spirits") instanceof JsonArray spirits) {
            for (JsonElement element : spirits) {
                if (element instanceof JsonObject spirit
                        && spirit.get("type") instanceof JsonPrimitive type) {
                    spirit.remove("type");
                    spirit.addProperty("item", type.getAsString() + "_spirit");
                }
            }
        }
        return recipe;
    }

    private static EditorLayout spiritInfusion() {
        List<SlotDef> slots = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            slots.add(spirit(20, 25 + i * 20, i == 0));
        }
        for (int i = 0; i < 4; i++) {
            slots.add(sized(104, 49 + i * 20, "extraInputs[]", false));
        }
        slots.add(sized(63, 57, "input", true));
        slots.add(new SlotDef(63, 124, "result", SlotFormat.ITEM, true, true));
        List<FieldDef> fields = List.of(
                new FieldDef("carryOverComponentData", "Keep input's data", FieldType.BOOL, "", false));
        EditorLayout layout = new EditorLayout(NS + ":spirit_infusion", "Spirit infusion (altar)",
                INFUSION_GUI, 0, 0, 142, 185, Kind.SLOTS, slots, fields);
        layout.template = spiritsTemplate();
        layout.prepareLoad = MalumLayouts::spiritsToItems;
        return layout;
    }

    private static EditorLayout soulBinding() {
        List<SlotDef> slots = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            slots.add(spirit(12, 13 + i * 20, i == 0));
        }
        for (int i = 0; i < 4; i++) {
            slots.add(sized(104, 13 + i * 20, "extraInputs[]", false));
        }
        slots.add(sized(58, 53, "input", true));
        List<FieldDef> fields = List.of(
                new FieldDef("result", "Geas effect", FieldType.STRING, "", true,
                        List.of(NS + ":pact_of_reciprocation", NS + ":pact_of_the_prospector",
                                NS + ":oath_of_the_overeager_fist", NS + ":oath_of_the_overkeen_eye")));
        EditorLayout layout = new EditorLayout(NS + ":soul_binding", "Soul binding",
                null, 0, 0, 142, 173, Kind.SLOTS, slots, fields);
        layout.template = spiritsTemplate();
        layout.prepareLoad = MalumLayouts::spiritsToItems;
        return layout;
    }

    private static EditorLayout runeworking() {
        List<SlotDef> slots = List.of(
                sized(63, 14, "secondaryInput", true),
                sized(63, 57, "input", true),
                new SlotDef(63, 124, "result", SlotFormat.ITEM, true, true));
        List<FieldDef> fields = List.of(
                new FieldDef("soundType", "Rune sound", FieldType.STRING,
                        NS + ":runic_workbench_shapes_tainted_rune", true,
                        List.of(NS + ":runic_workbench_shapes_tainted_rune",
                                NS + ":runic_workbench_shapes_void_rune",
                                NS + ":runic_workbench_shapes_wooden_rune")));
        return new EditorLayout(NS + ":runeworking", "Runeworking",
                RUNEWORKING_GUI, 0, 0, 142, 185, Kind.SLOTS, slots, fields);
    }

    private static EditorLayout spiritFocusing() {
        List<SlotDef> slots = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            slots.add(spirit(31 + i * 20, 12, i == 0));
        }
        slots.add(new SlotDef(63, 57, "input", SlotFormat.INGREDIENT, false, true));
        slots.add(new SlotDef(63, 124, "result", SlotFormat.ITEM, true, true));
        List<FieldDef> fields = List.of(
                new FieldDef("time", "Time (ticks)", FieldType.INT, "300", true),
                new FieldDef("durabilityCost", "Durability cost", FieldType.INT, "2", true));
        EditorLayout layout = new EditorLayout(NS + ":spirit_focusing", "Spirit focusing (crucible)",
                FOCUSING_GUI, 0, 0, 142, 185, Kind.SLOTS, slots, fields);
        layout.template = spiritsTemplate();
        layout.prepareLoad = MalumLayouts::spiritsToItems;
        return layout;
    }

    private static EditorLayout spiritRepair() {
        List<SlotDef> slots = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            slots.add(spirit(31 + i * 20, 12, i == 0));
        }
        slots.add(sized(44, 57, "repairMaterial", true));
        // The repairable items; the repaired result is derived from them, so there is none to place.
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 3; col++) {
                slots.add(new SlotDef(82 + col * 18, 48 + row * 18, "validItems[]",
                        SlotFormat.ITEM_ID, false, row == 0 && col == 0));
            }
        }
        List<FieldDef> fields = List.of(
                new FieldDef("durabilityPercentage", "Repair (0-1)", FieldType.FLOAT, "0.5", false));
        EditorLayout layout = new EditorLayout(NS + ":spirit_repair", "Spirit repair (pylon)",
                REPAIR_GUI, 0, 0, 142, 185, Kind.SLOTS, slots, fields);
        layout.template = spiritsTemplate();
        layout.prepareLoad = MalumLayouts::spiritsToItems;
        return layout;
    }

    private static EditorLayout transmutation() {
        List<SlotDef> slots = List.of(
                new SlotDef(28, 27, "input", SlotFormat.INGREDIENT, false, true),
                new SlotDef(93, 27, "result", SlotFormat.ITEM, true, true));
        List<FieldDef> fields = List.of(
                new FieldDef("group", "Group", FieldType.STRING, "", false));
        return new EditorLayout(NS + ":unchained_transmutation", "Spirit transmutation",
                TRANSMUTATION_GUI, 0, 0, 142, 83, Kind.SLOTS, slots, fields);
    }

    private static EditorLayout voidFavor() {
        List<SlotDef> slots = List.of(
                new SlotDef(63, 57, "input", SlotFormat.INGREDIENT, false, true),
                new SlotDef(63, 124, "result", SlotFormat.ITEM, true, true));
        return new EditorLayout(NS + ":void_favor", "Void favor (weeping well)",
                WELL_GUI, 0, 0, 142, 185, Kind.SLOTS, slots, List.of());
    }

    /** The metal node furnace recipes: the output is an ingredient (tags allowed) plus a count. */
    private static EditorLayout nodeCooking(String type, String name, String station) {
        List<SlotDef> slots = List.of(
                new SlotDef(56, 17, "ingredient", SlotFormat.INGREDIENT, false, true),
                new SlotDef(116, 35, "output", SlotFormat.INGREDIENT, true, true));
        List<FieldDef> fields = List.of(
                new FieldDef("outputCount", "Output count", FieldType.INT, "1", true),
                new FieldDef("experience", "XP", FieldType.FLOAT, "0.5", false),
                new FieldDef("cookingTime", "Time (ticks)", FieldType.INT,
                        type.equals("node_blasting") ? "100" : "200", false));
        return new EditorLayout(NS + ":" + type, name,
                ResourceLocation.withDefaultNamespace("textures/gui/container/" + station + ".png"),
                0, 0, 176, 166, 84, Kind.SLOTS, slots, fields);
    }
}
