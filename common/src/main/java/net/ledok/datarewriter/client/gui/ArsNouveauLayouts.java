package net.ledok.datarewriter.client.gui;

import com.google.gson.JsonArray;
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
 * Bundled layouts for Ars Nouveau (verified against its 1.21.x sources; the mod is NeoForge-only, so
 * these only ever activate on that loader). The mod's JEI categories are blank panels with the slots
 * arranged in a circle, so every layout here uses the editor's own panel; the pedestal ring positions
 * are the ones JEI computes for eight pedestals.
 * <ul>
 * <li>{@code enchanting_apparatus} — reagent in the middle, up to eight pedestal items around it.</li>
 * <li>{@code enchantment} — apparatus enchanting: pedestal items plus the enchantment id and level;
 * there is no result item, the enchantment is applied to whatever is on the apparatus.</li>
 * <li>{@code armor_upgrade} — pedestal items plus the armor tier the upgrade unlocks.</li>
 * <li>{@code imbuement} — input in the middle, up to eight pedestal items, a source cost.</li>
 * <li>{@code glyph} — the scribes table: a plain list of inputs (grid, order matters only for looks)
 * and the glyph item, priced in experience points.</li>
 * <li>{@code crush} — one input, up to four results with a drop chance each (Alt+scroll on a result
 * slot). The codec's per-result {@code maxRange} (rolls per drop) always saves as 1 — the mod's own
 * recipes never use more.</li>
 * <li>{@code budding_conversion} — amethyst golem conversion, block id to block id.</li>
 * <li>{@code scry_ritual} — two tags: the augment item tag and the block tag it highlights. Pick tags
 * in the slots; the highlight is a BLOCK tag, so type it by hand when no item tag mirrors it.</li>
 * <li>{@code dye} — a vanilla-shapeless recipe under its own type, drawn as the crafting table.</li>
 * </ul>
 * {@code caster_tome}, {@code summon_ritual}, {@code reactive_enchantment}, {@code spell_write} and
 * {@code dispel_entity} have no JEI category and carry spell/entity data the editor has no widgets
 * for; {@code book_upgrade} and {@code potion_flask} are dynamic shapeless recipes.
 */
final class ArsNouveauLayouts {
    private static final String NS = "ars_nouveau";
    private static final ResourceLocation CRAFTING_GUI =
            ResourceLocation.withDefaultNamespace("textures/gui/container/crafting_table.png");
    /**
     * The eight pedestal positions JEI's MultiInputCategory computes: a ring of radius 32 around
     * (48, 45), starting at the top and stepping 45 degrees.
     */
    private static final int[][] RING = {
            {48, 13}, {70, 22}, {80, 45}, {70, 67}, {48, 77}, {25, 67}, {16, 45}, {25, 22}};

    private ArsNouveauLayouts() {
    }

    static List<EditorLayout> forInstalledTypes() {
        List<EditorLayout> layouts = new ArrayList<>();
        if (serializerExists(NS + ":enchanting_apparatus")) {
            layouts.add(enchantingApparatus());
        }
        if (serializerExists(NS + ":enchantment")) {
            layouts.add(apparatusEnchanting());
        }
        if (serializerExists(NS + ":armor_upgrade")) {
            layouts.add(armorUpgrade());
        }
        if (serializerExists(NS + ":imbuement")) {
            layouts.add(imbuement());
        }
        if (serializerExists(NS + ":glyph")) {
            layouts.add(glyph());
        }
        if (serializerExists(NS + ":crush")) {
            layouts.add(crush());
        }
        if (serializerExists(NS + ":budding_conversion")) {
            layouts.add(buddingConversion());
        }
        if (serializerExists(NS + ":scry_ritual")) {
            layouts.add(scryRitual());
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

    /** Up to eight optional pedestal-item slots on the JEI ring, appending into {@code path}. */
    private static void addRing(List<SlotDef> slots, String path) {
        for (int[] position : RING) {
            slots.add(new SlotDef(position[0], position[1], path, SlotFormat.INGREDIENT, false, false));
        }
    }

    /** The codec requires {@code pedestalItems} even when no pedestal is used. */
    private static JsonObject emptyArrayTemplate(String key) {
        JsonObject template = new JsonObject();
        template.add(key, new JsonArray());
        return template;
    }

    private static EditorLayout enchantingApparatus() {
        List<SlotDef> slots = new ArrayList<>();
        addRing(slots, "pedestalItems[]");
        slots.add(new SlotDef(48, 45, "reagent", SlotFormat.INGREDIENT, false, true));
        slots.add(new SlotDef(86, 10, "result", SlotFormat.ITEM, true, true));
        List<FieldDef> fields = List.of(
                new FieldDef("sourceCost", "Source cost", FieldType.INT, "0", true),
                new FieldDef("keepNbtOfReagent", "Keep reagent's data", FieldType.BOOL, "false", true));
        EditorLayout layout = new EditorLayout(NS + ":enchanting_apparatus", "Enchanting apparatus",
                null, 0, 0, 114, 108, Kind.SLOTS, slots, fields);
        layout.template = emptyArrayTemplate("pedestalItems");
        return layout;
    }

    private static EditorLayout apparatusEnchanting() {
        List<SlotDef> slots = new ArrayList<>();
        addRing(slots, "pedestalItems[]");
        List<FieldDef> fields = List.of(
                new FieldDef("enchantment", "Enchantment", FieldType.STRING, "", true,
                        List.of("minecraft:sharpness", "minecraft:protection", "minecraft:mending",
                                "minecraft:fortune", "minecraft:looting", "minecraft:unbreaking")),
                new FieldDef("level", "Level", FieldType.INT, "1", true),
                new FieldDef("sourceCost", "Source cost", FieldType.INT, "2000", true));
        EditorLayout layout = new EditorLayout(NS + ":enchantment", "Apparatus enchanting",
                null, 0, 0, 114, 108, Kind.SLOTS, slots, fields);
        layout.template = emptyArrayTemplate("pedestalItems");
        return layout;
    }

    private static EditorLayout armorUpgrade() {
        List<SlotDef> slots = new ArrayList<>();
        addRing(slots, "pedestalItems[]");
        List<FieldDef> fields = List.of(
                new FieldDef("tier", "Armor tier (1-3)", FieldType.INT, "1", true,
                        List.of("1", "2", "3")),
                new FieldDef("sourceCost", "Source cost", FieldType.INT, "5000", true));
        EditorLayout layout = new EditorLayout(NS + ":armor_upgrade", "Armor upgrade (apparatus)",
                null, 0, 0, 114, 108, Kind.SLOTS, slots, fields);
        layout.template = emptyArrayTemplate("pedestalItems");
        return layout;
    }

    private static EditorLayout imbuement() {
        List<SlotDef> slots = new ArrayList<>();
        addRing(slots, "pedestalItems[]");
        slots.add(new SlotDef(48, 45, "input", SlotFormat.INGREDIENT, false, true));
        slots.add(new SlotDef(86, 10, "output", SlotFormat.ITEM, true, true));
        List<FieldDef> fields = List.of(
                new FieldDef("source", "Source cost", FieldType.INT, "100", true));
        EditorLayout layout = new EditorLayout(NS + ":imbuement", "Imbuement chamber",
                null, 0, 0, 114, 108, Kind.SLOTS, slots, fields);
        layout.template = emptyArrayTemplate("pedestalItems");
        return layout;
    }

    private static EditorLayout glyph() {
        List<SlotDef> slots = new ArrayList<>();
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                slots.add(new SlotDef(10 + col * 18, 13 + row * 18, "inputs[]",
                        SlotFormat.INGREDIENT, false, row == 0 && col == 0));
            }
        }
        slots.add(new SlotDef(86, 31, "output", SlotFormat.ITEM, true, true));
        List<FieldDef> fields = List.of(
                new FieldDef("exp", "Experience points", FieldType.INT, "27", true));
        return new EditorLayout(NS + ":glyph", "Glyph (scribes table)",
                null, 0, 0, 114, 76, Kind.SLOTS, slots, fields);
    }

    private static EditorLayout crush() {
        // {"stack": {"id", "count"}, "chance": c, "maxRange": 1} — every key is required by the codec.
        EditorLayout.SlotValueBuilder crushOutput = (ref, count, chance) -> {
            JsonObject stack = new JsonObject();
            stack.addProperty("id", ref);
            stack.addProperty("count", Math.max(1, count));
            JsonObject output = new JsonObject();
            output.add("stack", stack);
            output.addProperty("chance", chance);
            output.addProperty("maxRange", 1);
            return output;
        };
        List<SlotDef> slots = new ArrayList<>();
        slots.add(new SlotDef(8, 10, "input", SlotFormat.INGREDIENT, false, true));
        for (int i = 0; i < 4; i++) {
            slots.add(new SlotDef(54, 10 + i * 18, "output[]", SlotFormat.ITEM,
                    true, i == 0, true, crushOutput));
        }
        List<FieldDef> fields = List.of(
                new FieldDef("skip_block_place", "Skip block drops", FieldType.BOOL, "", false));
        return new EditorLayout(NS + ":crush", "Crush (spell)",
                null, 0, 0, 120, 88, Kind.SLOTS, slots, fields);
    }

    private static EditorLayout buddingConversion() {
        // Both values are BLOCK ids by name; every convertible block has an item with the same id.
        List<SlotDef> slots = List.of(
                new SlotDef(8, 10, "input", SlotFormat.ITEM_ID, false, true),
                new SlotDef(96, 10, "result", SlotFormat.ITEM_ID, true, true));
        return new EditorLayout(NS + ":budding_conversion", "Budding conversion",
                null, 0, 0, 120, 36, Kind.SLOTS, slots, List.of());
    }

    private static EditorLayout scryRitual() {
        // Both keys are bare tag ids without '#': augment is an ITEM tag, highlight a BLOCK tag.
        EditorLayout.SlotValueBuilder bareTag = (ref, count, chance) ->
                new JsonPrimitive(ref.startsWith("#") ? ref.substring(1) : ref);
        List<SlotDef> slots = List.of(
                new SlotDef(8, 10, "augment", SlotFormat.STRING, false, true, false, true, bareTag),
                new SlotDef(96, 10, "highlight", SlotFormat.STRING, true, true, false, true, bareTag));
        EditorLayout layout = new EditorLayout(NS + ":scry_ritual", "Scry ritual",
                null, 0, 0, 120, 36, Kind.SLOTS, slots, List.of());
        layout.prepareLoad = ArsNouveauLayouts::tagifyScryRitual;
        return layout;
    }

    /** The bare tag strings would read as item ids — prefix '#' so the slots show them as tags. */
    private static JsonObject tagifyScryRitual(JsonObject recipe) {
        for (String key : new String[]{"augment", "highlight"}) {
            if (recipe.get(key) instanceof JsonPrimitive p && p.isString()
                    && !p.getAsString().startsWith("#")) {
                recipe.addProperty(key, "#" + p.getAsString());
            }
        }
        return recipe;
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
