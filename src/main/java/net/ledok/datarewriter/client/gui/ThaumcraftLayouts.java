package net.ledok.datarewriter.client.gui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.ledok.datarewriter.client.gui.EditorLayout.FieldDef;
import net.ledok.datarewriter.client.gui.EditorLayout.FieldType;
import net.ledok.datarewriter.client.gui.EditorLayout.Kind;
import net.ledok.datarewriter.client.gui.EditorLayout.SlotDef;
import net.ledok.datarewriter.client.gui.EditorLayout.SlotFormat;
import net.ledok.datarewriter.compat.thaumcraft.ThaumcraftCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Thaumcraft 4: Reforged's own recipe kinds. They are not game recipes but entries of Thaumcraft's recipe
 * map under string keys, so these layouts go through the registry-entry path with
 * {@link ThaumcraftCompat#EDITOR_TARGET}: the id box takes the recipe key ("ElementalSword"), Load fetches
 * that recipe from the server (switching to its layout), Save writes it to {@code thaumcraft.add}.
 * <ul>
 * <li>Arcane (shaped / shapeless) — the arcane worktable's 3×3 grid and the vis cost.</li>
 * <li>Crucible — the catalyst thrown into the crucible and the essentia it needs.</li>
 * <li>Infusion — the central item on the matrix, up to twelve pedestal components, instability.</li>
 * <li>Infusion enchantment — components and the enchantment it applies to the central item.</li>
 * </ul>
 * Aspects are typed as "aer:5, ignis:3". Items whose aspects or wand parts matter to a recipe (crystals,
 * wands) load as the plain item — retype those in the config file.
 */
final class ThaumcraftLayouts {
    private static final int RING_X = 54;
    private static final int RING_Y = 50;
    private static final int RING_RADIUS = 40;

    private ThaumcraftLayouts() {
    }

    static List<EditorLayout> forInstalledTypes() {
        if (!ThaumcraftCompat.LOADED) {
            return List.of();
        }
        return List.of(arcane(true), arcane(false), crucible(), infusion(), infusionEnchantment());
    }

    private static EditorLayout arcane(boolean shaped) {
        List<SlotDef> slots = new ArrayList<>();
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                slots.add(new SlotDef(12 + col * 18, 12 + row * 18, "", SlotFormat.STRING, false, false));
            }
        }
        slots.add(new SlotDef(112, 30, "result", SlotFormat.SHORTHAND_RESULT, true, true));
        String type = shaped ? "arcane_shaped" : "arcane_shapeless";
        EditorLayout layout = new EditorLayout("thaumcraft:" + type,
                shaped ? "Arcane worktable (Thaumcraft)" : "Arcane worktable, shapeless (Thaumcraft)",
                null, 0, 0, 148, 78, shaped ? Kind.SHAPED : Kind.SHAPELESS, slots,
                List.of(research(), aspects("Vis (aer:5, ignis:3)")));
        return configure(layout, type);
    }

    private static EditorLayout crucible() {
        List<SlotDef> slots = List.of(
                new SlotDef(30, 30, "catalyst", SlotFormat.INGREDIENT, false, true),
                new SlotDef(112, 30, "result", SlotFormat.SHORTHAND_RESULT, true, true));
        EditorLayout layout = new EditorLayout("thaumcraft:crucible", "Crucible (Thaumcraft)", null, 0, 0,
                148, 78, Kind.SLOTS, slots, List.of(research(), aspects("Essentia (aqua:4)")));
        return configure(layout, "crucible");
    }

    private static EditorLayout infusion() {
        List<SlotDef> slots = new ArrayList<>();
        slots.add(new SlotDef(RING_X, RING_Y, "central", SlotFormat.INGREDIENT, false, true));
        slots.addAll(ring(true));
        slots.add(new SlotDef(150, RING_Y, "result", SlotFormat.SHORTHAND_RESULT, true, true));
        EditorLayout layout = new EditorLayout("thaumcraft:infusion", "Infusion (Thaumcraft)", null, 0, 0,
                176, 116, Kind.SLOTS, slots, List.of(research(), instability(), aspects("Essentia (aqua:4)")));
        return configure(layout, "infusion");
    }

    private static EditorLayout infusionEnchantment() {
        List<SlotDef> slots = new ArrayList<>(ring(true));
        EditorLayout layout = new EditorLayout("thaumcraft:infusion_enchantment",
                "Infusion enchantment (Thaumcraft)", null, 0, 0, 176, 116, Kind.SLOTS, slots,
                List.of(new FieldDef("enchantment", "Enchantment", FieldType.STRING, "minecraft:sharpness", true),
                        research(), instability(), aspects("Essentia (aqua:4)")));
        return configure(layout, "infusion_enchantment");
    }

    /** Twelve pedestal slots around the matrix; the first one is required. */
    private static List<SlotDef> ring(boolean firstRequired) {
        List<SlotDef> slots = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            double angle = Math.toRadians(i * 30 - 90);
            int x = RING_X + (int) Math.round(Math.cos(angle) * RING_RADIUS);
            int y = RING_Y + (int) Math.round(Math.sin(angle) * RING_RADIUS);
            slots.add(new SlotDef(x, y, "components[]", SlotFormat.INGREDIENT, false, firstRequired && i == 0));
        }
        return slots;
    }

    private static FieldDef research() {
        return new FieldDef("research", "Research key", FieldType.STRING, "", false);
    }

    private static FieldDef instability() {
        return new FieldDef("instability", "Instability", FieldType.INT, "0", false);
    }

    private static FieldDef aspects(String label) {
        return new FieldDef("aspects", label, FieldType.STRING, "", false, ThaumcraftCompat.aspectTags());
    }

    private static EditorLayout configure(EditorLayout layout, String type) {
        layout.registryTarget = ThaumcraftCompat.EDITOR_TARGET;
        layout.keyIds = true;
        JsonObject template = new JsonObject();
        template.addProperty("type", type);
        layout.template = template;
        layout.prepareLoad = ThaumcraftLayouts::aspectsAsText;
        return layout;
    }

    /** {aer: 5, ignis: 3} → "aer:5, ignis:3", the text field's form. */
    private static JsonObject aspectsAsText(JsonObject json) {
        if (json.get("aspects") instanceof JsonObject aspects) {
            StringBuilder text = new StringBuilder();
            for (Map.Entry<String, JsonElement> entry : aspects.entrySet()) {
                if (!text.isEmpty()) {
                    text.append(", ");
                }
                text.append(entry.getKey()).append(':').append(entry.getValue().getAsString());
            }
            json.add("aspects", new JsonPrimitive(text.toString()));
        }
        return json;
    }
}
