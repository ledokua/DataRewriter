package net.ledok.datarewriter.client.gui;

import com.google.gson.JsonArray;
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
        if (typeExists("herbalbrews:kettle_brewing")) {
            layouts.add(hbKettleBrewing());
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
        if (typeExists("ubesdelight:baking_mat")) {
            layouts.add(ubesBakingMat());
        }
        if (typeExists("runes:crafting")) {
            layouts.add(runesCrafting());
        }
        if (typeExists("potions_ld:potion_brewing")) {
            layouts.add(potionsLdBrewing());
        }
        layouts.addAll(CreateLayouts.forInstalledTypes());
        layouts.addAll(EternalStarlightLayouts.forInstalledTypes());
        layouts.addAll(ForbiddenArcanusLayouts.forInstalledTypes());
        layouts.addAll(ArsNouveauLayouts.forInstalledTypes());
        return layouts;
    }

    /** FD-Refabricated-style chance result: {"item": {"id", "count"}, "chance": c}. */
    private static EditorLayout.SlotValueBuilder chanceResult() {
        return (ref, count, chance) -> {
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
        };
    }

    /**
     * Baking mat (no GUI — card panel with the inventory below): tool on top,
     * up to 9 ingredients, up to 4 chance results, and up to 5 optional
     * render-only processing stages. The 1.21.1 refab port's codec expects
     * "processing_stages" to always exist, so the layout template pre-seeds
     * an empty array that filled stage slots append into.
     */
    private static EditorLayout ubesBakingMat() {
        List<SlotDef> slots = new ArrayList<>();
        slots.add(new SlotDef(79, 10, "tool", SlotFormat.INGREDIENT, false, true));
        for (int i = 0; i < 9; i++) {
            slots.add(new SlotDef(13 + (i % 3) * 18, 32 + (i / 3) * 18, "ingredients[]",
                    SlotFormat.INGREDIENT, false, i == 0));
        }
        for (int i = 0; i < 4; i++) {
            slots.add(new SlotDef(122 + (i % 2) * 18, 37 + (i / 2) * 18, "result[]",
                    SlotFormat.ITEM, true, i == 0, true, chanceResult()));
        }
        for (int i = 0; i < 5; i++) {
            slots.add(new SlotDef(40 + i * 18, 88, "processing_stages[]",
                    SlotFormat.INGREDIENT, false, false));
        }
        EditorLayout layout = new EditorLayout("ubesdelight:baking_mat", "Baking Mat (Ube's Delight)",
                null, 0, 0, 176, 190, 110, Kind.SLOTS, slots, List.of());
        JsonObject template = new JsonObject();
        template.add("processing_stages", new JsonArray());
        layout.template = template;
        return layout;
    }

    /** Rune crafting altar: smithing-style base + addition -> result. */
    private static EditorLayout runesCrafting() {
        List<SlotDef> slots = List.of(
                new SlotDef(27, 47, "base", SlotFormat.INGREDIENT, false, true),
                new SlotDef(76, 47, "addition", SlotFormat.INGREDIENT, false, true),
                new SlotDef(134, 47, "result", SlotFormat.ITEM, true, true));
        return new EditorLayout("runes:crafting", "Crafting Altar (Runes)",
                ResourceLocation.fromNamespaceAndPath("runes", "textures/gui/crafting_altar.png"),
                0, 0, 176, 166, 84, Kind.SLOTS, slots, List.of());
    }

    /**
     * Alchemy table on its own GUI: 2x2 counted ingredients -> result. The
     * three right-hand upgrade slots are machine gear, not recipe data. The
     * mod's result codec uses "item" instead of vanilla's "id".
     */
    private static EditorLayout potionsLdBrewing() {
        List<SlotDef> slots = List.of(
                new SlotDef(29, 21, "ingredients[]", SlotFormat.COUNTED_INGREDIENT, false, true),
                new SlotDef(47, 21, "ingredients[]", SlotFormat.COUNTED_INGREDIENT, false, false),
                new SlotDef(29, 40, "ingredients[]", SlotFormat.COUNTED_INGREDIENT, false, false),
                new SlotDef(47, 40, "ingredients[]", SlotFormat.COUNTED_INGREDIENT, false, false),
                new SlotDef(116, 31, "result", SlotFormat.ITEM_NAMED, true, true));
        return new EditorLayout("potions_ld:potion_brewing", "Alchemy Table (Potions LD)",
                ResourceLocation.fromNamespaceAndPath("potions_ld", "textures/gui/alchemy_table.png"),
                0, 0, 176, 166, 84, Kind.SLOTS, slots,
                List.of(new FieldDef("cookingTime", "Time (ticks)", FieldType.INT, "100", false)));
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
                    SlotFormat.ITEM, true, i == 0, true, chanceResult()));
        }
        return new EditorLayout("farmersdelight:cutting", "Cutting Board (Farmer's Delight)",
                null, 0, 0, 176, 166, 84, Kind.SLOTS, slots, List.of());
    }

    private static ResourceLocation bncTexture(String name) {
        return ResourceLocation.fromNamespaceAndPath("brewinandchewin", "textures/gui/" + name + ".png");
    }

    /**
     * Keg fermenting on the keg GUI: base fluid in the free band left of the
     * 2x2 ingredient grid, then a result that is EITHER a fluid (in the tank)
     * or an item (the tankard output slot) — fill exactly one. Units are
     * always written as millibuckets; omitting the unit would mean droplets
     * on Fabric.
     */
    private static EditorLayout bncFermenting() {
        List<SlotDef> slots = new ArrayList<>();
        slots.add(new SlotDef(13, 26, "base_fluid", SlotFormat.FLUID_AMOUNT, false, false, false, true,
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
                slots.add(new SlotDef(39 + col * 18, 17 + row * 18, "ingredients[]",
                        SlotFormat.INGREDIENT, false, row == 0 && col == 0));
            }
        }
        slots.add(new SlotDef(124, 25, "result", SlotFormat.FLUID_AMOUNT, true, false));
        slots.add(new SlotDef(124, 55, "result", SlotFormat.ITEM, true, false));
        return new EditorLayout("brewinandchewin:fermenting", "Keg Fermenting (Brewin' and Chewin')",
                bncTexture("keg"), 0, 0, 176, 166, 84, Kind.SLOTS, slots,
                List.of(new FieldDef("unit", "Unit", FieldType.STRING, "millibuckets",
                                true, List.of("liters", "millibuckets", "droplets")),
                        new FieldDef("category", "Category", FieldType.STRING, "drinks",
                                false, List.of("meals", "drinks", "misc")),
                        new FieldDef("experience", "XP", FieldType.FLOAT, "0.0", false),
                        new FieldDef("fermenting_time", "Time (ticks)", FieldType.INT, "9600", false),
                        new FieldDef("temperature", "Temperature", FieldType.INT, "3", false)));
    }

    /**
     * Keg pouring on the keg GUI, matching the machine 1:1: tank fluid +
     * optional container in the tankard slot -> output item.
     */
    private static EditorLayout bncKegPouring() {
        List<SlotDef> slots = List.of(
                new SlotDef(124, 25, "fluid", SlotFormat.FLUID_AMOUNT, false, true),
                new SlotDef(91, 55, "container", SlotFormat.ITEM, false, false),
                new SlotDef(124, 55, "output", SlotFormat.ITEM, true, true));
        return new EditorLayout("brewinandchewin:keg_pouring", "Keg Pouring (Brewin' and Chewin')",
                bncTexture("keg"), 0, 0, 176, 166, 84, Kind.SLOTS, slots,
                List.of(new FieldDef("unit", "Unit", FieldType.STRING, "millibuckets",
                                true, List.of("liters", "millibuckets", "droplets")),
                        new FieldDef("strict", "Strict", FieldType.BOOL, "false", false),
                        new FieldDef("can_fill", "Can fill", FieldType.BOOL, "true", false)));
    }

    /**
     * Tea kettle: 2x2 ingredients + the container (bottle) slot — the mod
     * matches the container as a fifth ingredient, so it maps to
     * "ingredients[]" too. Water/heat slots are machine fuel, not recipe
     * data; the recipe's fluid/heat requirements are plain int fields.
     */
    private static EditorLayout hbKettleBrewing() {
        List<SlotDef> slots = new ArrayList<>();
        slots.add(new SlotDef(13, 12, "ingredients[]", SlotFormat.INGREDIENT, false, true));
        slots.add(new SlotDef(31, 12, "ingredients[]", SlotFormat.INGREDIENT, false, false));
        slots.add(new SlotDef(13, 30, "ingredients[]", SlotFormat.INGREDIENT, false, false));
        slots.add(new SlotDef(31, 30, "ingredients[]", SlotFormat.INGREDIENT, false, false));
        slots.add(new SlotDef(31, 52, "ingredients[]", SlotFormat.INGREDIENT, false, false));
        slots.add(new SlotDef(91, 22, "result", SlotFormat.ITEM, true, true));
        return new EditorLayout("herbalbrews:kettle_brewing", "Tea Kettle (Herbal Brews)",
                ResourceLocation.fromNamespaceAndPath("herbalbrews", "textures/gui/tea_kettle.png"),
                0, 0, 176, 166, 84, Kind.SLOTS, slots,
                List.of(new FieldDef("effect", "Effect", FieldType.STRING, "herbalbrews:balanced",
                                true, List.of("herbalbrews:balanced", "herbalbrews:deeprush")),
                        new FieldDef("effect_duration", "Duration", FieldType.INT, "1800", true),
                        new FieldDef("fluid_amount", "Water", FieldType.INT, "10", true),
                        new FieldDef("heat_amount", "Heat", FieldType.INT, "40", true),
                        new FieldDef("crafting_duration", "Time (ticks)", FieldType.INT, "20", true),
                        new FieldDef("experience", "XP", FieldType.FLOAT, "0.8", true)));
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

    /**
     * Apple press, mashing step: single input -> output. The mash lands in
     * the first bottom-right slot (which then feeds the fermenting step), not
     * the top result slot.
     */
    private static EditorLayout vineryAppleMashing() {
        return new EditorLayout("vinery:apple_mashing", "Apple Press — Mashing (Vinery)",
                ResourceLocation.fromNamespaceAndPath("vinery", "textures/gui/apple_press_gui.png"),
                0, 0, 176, 166, 84, Kind.SLOTS,
                List.of(new SlotDef(44, 34, "input", SlotFormat.INGREDIENT, false, true),
                        new SlotDef(101, 50, "output", SlotFormat.ITEM, true, true)),
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

    /**
     * Distillery GUI: input top-left, output right (fuel and water slots are
     * machine supplies, not recipe data). Falls back to the card panel if the
     * texture is absent (it is referenced but missing in some BnC builds).
     */
    private static EditorLayout bncDistilling() {
        List<SlotDef> slots = List.of(
                new SlotDef(44, 17, "ingredient", SlotFormat.INGREDIENT, false, true),
                new SlotDef(116, 35, "result", SlotFormat.ITEM, true, true));
        return new EditorLayout("brewinandchewin:distilling", "Distilling (Brewin' and Chewin')",
                bncTexture("distillery"), 0, 0, 176, 166, 84, Kind.SLOTS, slots,
                List.of(new FieldDef("distilling_time", "Time (ticks)", FieldType.INT, "400", false),
                        new FieldDef("water_cost", "Water", FieldType.INT, "1", false),
                        new FieldDef("experience", "XP", FieldType.FLOAT, "0.0", false)));
    }
}
