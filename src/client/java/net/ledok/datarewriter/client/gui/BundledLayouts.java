package net.ledok.datarewriter.client.gui;

import com.google.gson.JsonObject;
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
 * Hand-tuned layouts for known mods, bundled with DataRewriter. Each is
 * offered only when its recipe type is actually registered in the running
 * game; a user layout file for the same type takes precedence.
 *
 * Schemas verified against the mods' 1.21.1 sources:
 * - Farmer's Delight Refabricated (github.com/MehVahdJukaar/FarmersDelightRefabricated)
 * - Brewin' and Chewin' (github.com/ChefsDelights/BrewinAndChewin)
 */
final class BundledLayouts {
    private BundledLayouts() {
    }

    static List<EditorLayout> forInstalledTypes() {
        List<EditorLayout> layouts = new ArrayList<>();
        if (typeExists("farmersdelight:cooking")) {
            layouts.add(fdCooking());
        }
        if (typeExists("farmersdelight:cutting")) {
            layouts.add(fdCutting());
        }
        if (typeExists("brewinandchewin:fermenting")) {
            layouts.add(bncFermenting());
        }
        if (typeExists("brewinandchewin:keg_pouring")) {
            layouts.add(bncKegPouring());
        }
        if (typeExists("brewinandchewin:distilling")) {
            layouts.add(bncDistilling());
        }
        if (typeExists("vinery:wine_fermentation")) {
            layouts.add(vineryWineFermentation());
        }
        if (typeExists("vinery:apple_mashing")) {
            layouts.add(vineryAppleMashing());
        }
        if (typeExists("vinery:apple_fermenting")) {
            layouts.add(vineryAppleFermenting());
        }
        return layouts;
    }

    private static boolean typeExists(String typeId) {
        ResourceLocation id = ResourceLocation.tryParse(typeId);
        return id != null && BuiltInRegistries.RECIPE_SERIALIZER.containsKey(id);
    }

    /** Cooking pot: 2x3 ingredient grid, container, single result. */
    private static EditorLayout fdCooking() {
        List<SlotDef> slots = new ArrayList<>();
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 3; col++) {
                slots.add(new SlotDef(30 + col * 18, 17 + row * 18, "ingredients[]",
                        SlotFormat.INGREDIENT, false, row == 0 && col == 0));
            }
        }
        slots.add(new SlotDef(92, 55, "container", SlotFormat.ITEM, false, false));
        slots.add(new SlotDef(124, 55, "result", SlotFormat.ITEM, true, true));
        return new EditorLayout("farmersdelight:cooking", "Cooking Pot (Farmer's Delight)",
                ResourceLocation.fromNamespaceAndPath("farmersdelight", "textures/gui/cooking_pot.png"),
                0, 0, 176, 166, 84, Kind.SLOTS, slots,
                List.of(new FieldDef("recipe_book_tab", "Tab", FieldType.STRING, "",
                                false, List.of("meals", "drinks", "misc")),
                        new FieldDef("experience", "XP", FieldType.FLOAT, "0.0", false),
                        new FieldDef("cookingtime", "Time (ticks)", FieldType.INT, "200", false)));
    }

    /**
     * Cutting board (no GUI in the mod), laid out like EMI's recipe view:
     * tool above the ingredient, then up to four results in a 2x2 grid, each
     * encoded as {"item": {"id", "count"}, "chance": c} — Alt+scroll over a
     * result slot sets its drop chance.
     */
    private static EditorLayout fdCutting() {
        List<SlotDef> slots = new ArrayList<>();
        slots.add(new SlotDef(35, 12, "tool", SlotFormat.INGREDIENT, false, true));
        slots.add(new SlotDef(35, 38, "ingredients[]", SlotFormat.INGREDIENT, false, true));
        for (int i = 0; i < 4; i++) {
            slots.add(new SlotDef(122 + (i % 2) * 18, 16 + (i / 2) * 18, "result[]",
                    SlotFormat.ITEM, true, i == 0, true,
                    (ref, count, chance) -> {
                        JsonObject item = new JsonObject();
                        item.addProperty("id", ref);
                        if (count > 1) {
                            item.addProperty("count", count);
                        }
                        JsonObject entry = new JsonObject();
                        entry.add("item", item);
                        if (chance < 1f) {
                            entry.addProperty("chance", chance);
                        }
                        return entry;
                    }));
        }
        return new EditorLayout("farmersdelight:cutting", "Cutting Board (Farmer's Delight)",
                null, 0, 0, 176, 166, 84, Kind.SLOTS, slots, List.of());
    }

    /**
     * Keg fermenting, laid out like EMI's recipe view: base fluid on the
     * left, the 2x2 ingredient grid, then a result that is EITHER a fluid
     * (upper right slot) or an item (lower right slot) — fill exactly one.
     * Units are always written as millibuckets; omitting the unit would mean
     * droplets on Fabric.
     */
    private static EditorLayout bncFermenting() {
        List<SlotDef> slots = new ArrayList<>();
        slots.add(new SlotDef(14, 25, "base_fluid", SlotFormat.FLUID_AMOUNT, false, false, false, true,
                (ref, amount, chance) -> {
                    JsonObject ingredient = new JsonObject();
                    if (ref.startsWith("#")) {
                        ingredient.addProperty("tag", ref); // BnC keeps the '#' prefix in fluid tags
                    } else {
                        ingredient.addProperty("fluid", ref);
                    }
                    JsonObject fluid = new JsonObject();
                    fluid.add("ingredient", ingredient);
                    fluid.addProperty("amount", amount);
                    fluid.addProperty("unit", "millibuckets");
                    return fluid;
                }));
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 2; col++) {
                slots.add(new SlotDef(44 + col * 18, 16 + row * 18, "ingredients[]",
                        SlotFormat.INGREDIENT, false, row == 0 && col == 0));
            }
        }
        slots.add(new SlotDef(140, 16, "result", SlotFormat.FLUID_AMOUNT, true, false));
        slots.add(new SlotDef(140, 34, "result", SlotFormat.ITEM, true, false));
        return new EditorLayout("brewinandchewin:fermenting", "Keg Fermenting (Brewin' and Chewin')",
                null, 0, 0, 176, 166, 84, Kind.SLOTS, slots,
                List.of(new FieldDef("unit", "Unit", FieldType.STRING, "millibuckets",
                                true, List.of("liters", "millibuckets", "droplets")),
                        new FieldDef("category", "Category", FieldType.STRING, "drinks",
                                false, List.of("meals", "drinks", "misc")),
                        new FieldDef("experience", "XP", FieldType.FLOAT, "0.0", false),
                        new FieldDef("fermenting_time", "Time (ticks)", FieldType.INT, "9600", false),
                        new FieldDef("temperature", "Temperature", FieldType.INT, "3", false)));
    }

    /** Keg pouring: tank fluid + optional container item -> output item. */
    private static EditorLayout bncKegPouring() {
        List<SlotDef> slots = List.of(
                new SlotDef(14, 25, "fluid", SlotFormat.FLUID_AMOUNT, false, true),
                new SlotDef(53, 25, "container", SlotFormat.ITEM, false, false),
                new SlotDef(140, 25, "output", SlotFormat.ITEM, true, true));
        return new EditorLayout("brewinandchewin:keg_pouring", "Keg Pouring (Brewin' and Chewin')",
                null, 0, 0, 176, 166, 84, Kind.SLOTS, slots,
                List.of(new FieldDef("unit", "Unit", FieldType.STRING, "millibuckets",
                                true, List.of("liters", "millibuckets", "droplets")),
                        new FieldDef("strict", "Strict", FieldType.BOOL, "false", false),
                        new FieldDef("can_fill", "Can fill", FieldType.BOOL, "true", false)));
    }

    private static final List<String> JUICE_TYPES = List.of(
            "white_general", "red_general", "white_savanna", "red_savanna", "white_taiga",
            "red_taiga", "white_jungle", "red_jungle", "apple", "red_crimson", "white_warped");

    /**
     * Fermentation barrel: up to 3 ingredients + juice (a {type, amount}
     * data object, not a fluid — set via the two juice fields) -> result.
     * The barrel holds at most 100 juice by default.
     */
    private static EditorLayout vineryWineFermentation() {
        List<SlotDef> slots = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            slots.add(new SlotDef(67 + i * 18, 58, "ingredients[]", SlotFormat.INGREDIENT, false, i == 0));
        }
        slots.add(new SlotDef(103, 17, "result", SlotFormat.ITEM, true, true));
        return new EditorLayout("vinery:wine_fermentation", "Fermentation Barrel (Vinery)",
                ResourceLocation.fromNamespaceAndPath("vinery", "textures/gui/fermentation_barrel_gui.png"),
                0, 0, 176, 166, 84, Kind.SLOTS, slots,
                List.of(new FieldDef("juice.type", "Juice", FieldType.STRING, "apple", true, JUICE_TYPES),
                        new FieldDef("juice.amount", "Amount", FieldType.INT, "20", true),
                        new FieldDef("wine_bottle.required", "Needs bottle", FieldType.BOOL, "true", true)));
    }

    /** Apple press, mashing step: single input -> output. */
    private static EditorLayout vineryAppleMashing() {
        return new EditorLayout("vinery:apple_mashing", "Apple Press — Mashing (Vinery)",
                ResourceLocation.fromNamespaceAndPath("vinery", "textures/gui/apple_press_gui.png"),
                0, 0, 176, 166, 84, Kind.SLOTS,
                List.of(new SlotDef(44, 34, "input", SlotFormat.INGREDIENT, false, true),
                        new SlotDef(119, 18, "output", SlotFormat.ITEM, true, true)),
                List.of());
    }

    /** Apple press, fermenting step: single input (+ bottle flag) -> output. */
    private static EditorLayout vineryAppleFermenting() {
        return new EditorLayout("vinery:apple_fermenting", "Apple Press — Fermenting (Vinery)",
                ResourceLocation.fromNamespaceAndPath("vinery", "textures/gui/apple_press_gui.png"),
                0, 0, 176, 166, 84, Kind.SLOTS,
                List.of(new SlotDef(101, 50, "input", SlotFormat.INGREDIENT, false, true),
                        new SlotDef(119, 18, "output", SlotFormat.ITEM, true, true)),
                List.of(new FieldDef("wine_bottle.required", "Needs bottle", FieldType.BOOL, "true", true)));
    }

    /** Distillery: single ingredient -> item result. */
    private static EditorLayout bncDistilling() {
        List<SlotDef> slots = List.of(
                new SlotDef(53, 25, "ingredient", SlotFormat.INGREDIENT, false, true),
                new SlotDef(140, 25, "result", SlotFormat.ITEM, true, true));
        return new EditorLayout("brewinandchewin:distilling", "Distilling (Brewin' and Chewin')",
                null, 0, 0, 176, 166, 84, Kind.SLOTS, slots,
                List.of(new FieldDef("distilling_time", "Time (ticks)", FieldType.INT, "400", false),
                        new FieldDef("water_cost", "Water", FieldType.INT, "1", false),
                        new FieldDef("experience", "XP", FieldType.FLOAT, "0.0", false)));
    }
}
