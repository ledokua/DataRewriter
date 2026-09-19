package net.ledok.datarewriter.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Describes how the recipe editor renders and serializes one recipe type:
 * which GUI texture to show, where the (ghost) slots sit, and how each slot
 * and extra field maps into the recipe JSON. Vanilla types are built in;
 * modded types load from config/datarewriter/editor-layouts/*.json5.
 */
public final class EditorLayout {
    /** How a slot's picked item/fluid is written into the recipe JSON. */
    public enum SlotFormat {
        /** Bare string, "mod:item" or "#mod:tag" (vanilla shorthand). */
        STRING(true, false),
        /** Vanilla result shorthand: string, or {"id","count"} when count > 1. */
        SHORTHAND_RESULT(false, true),
        /** {"item": id} / {"tag": id} — the vanilla Ingredient object. */
        INGREDIENT(true, false),
        /** {"ingredient": {"item"/"tag": id}, "count": n}. */
        COUNTED_INGREDIENT(true, true),
        /** {"id": id, "count": n} — the vanilla ItemStack object. */
        ITEM(false, true),
        /** {"item": id, "count": n} — ItemStack with an "item" key. */
        ITEM_NAMED(false, true),
        /** {"id": fluid id, "amount_mb": n} — the picker lists fluids. */
        FLUID(false, true),
        /** {"id": fluid id, "amount": n} — fluid with a plain "amount" key. */
        FLUID_AMOUNT(false, true),
        /** Bare "mod:item" string, no tags, no count (registry-by-name item fields). */
        ITEM_ID(false, false);

        public final boolean allowsTags;
        public final boolean counted;

        SlotFormat(boolean allowsTags, boolean counted) {
            this.allowsTags = allowsTags;
            this.counted = counted;
        }

        /** Slots whose picker lists fluids and whose count is an mB amount. */
        public boolean fluid() {
            return this == FLUID || this == FLUID_AMOUNT;
        }
    }

    public enum Kind { SHAPED, SHAPELESS, SLOTS }

    public enum FieldType { INT, FLOAT, STRING, BOOL }

    /** How a slot's picked value becomes JSON when the standard formats don't fit (bundled layouts only). */
    public interface SlotValueBuilder {
        JsonElement build(String ref, int count, float chance);
    }

    /** Read access to the editor's current values, for decorations that react to them. */
    public interface SlotView {
        /** Ref in slot {@code index} ("mod:item" / "#mod:tag"), or null when empty. */
        String ref(int index);

        /** Amount in slot {@code index} (item count or mB). */
        int count(int index);

        /** Chance in percent (1-100) for chanceable slots. */
        int chance(int index);

        /** Current text of the field at {@code path} ("" when unset). */
        String field(String path);
    }

    /**
     * Extra art drawn over the panel background and under the slot contents
     * (bundled layouts only): slot frames, arrows, animated machines. Coordinates
     * are the same texture-space ones slots use — {@code left}/{@code top} is
     * where the layout's (0,0) lands on screen.
     */
    public interface Decoration {
        void render(net.minecraft.client.gui.GuiGraphics graphics, int left, int top, float partialTick, SlotView view);
    }

    /**
     * x/y are the slot's item position in the original GUI's coordinates
     * (copy them straight from the mod's Menu class); the crop offset is
     * subtracted when rendering. path supports dots and a trailing "[]"
     * (append to array), e.g. "ingredients[]". chanceable slots take a drop
     * chance via Alt+scroll; tags additionally allows #tags beyond what the
     * format permits. custom overrides how the value is written into the
     * JSON; the format still drives the picker, counts and highlights.
     */
    public record SlotDef(int x, int y, String path, SlotFormat format, boolean result, boolean required,
                          boolean chanceable, boolean tags, SlotValueBuilder custom) {
        public SlotDef(int x, int y, String path, SlotFormat format, boolean result, boolean required) {
            this(x, y, path, format, result, required, false, false, null);
        }

        public SlotDef(int x, int y, String path, SlotFormat format, boolean result, boolean required,
                       SlotValueBuilder custom) {
            this(x, y, path, format, result, required, false, false, custom);
        }

        public SlotDef(int x, int y, String path, SlotFormat format, boolean result, boolean required,
                       boolean chanceable, SlotValueBuilder custom) {
            this(x, y, path, format, result, required, chanceable, false, custom);
        }

        public boolean acceptsTags() {
            return format.allowsTags || tags;
        }
    }

    /** suggestions = values seen in existing recipes (or listed in a layout file), shown as a tooltip. */
    public record FieldDef(String path, String label, FieldType type, String defaultValue,
                           boolean required, List<String> suggestions) {
        public FieldDef(String path, String label, FieldType type, String defaultValue, boolean required) {
            this(path, label, type, defaultValue, required, List.of());
        }
    }

    public final String typeId;
    public final String displayName;
    /** null = no station texture; the editor draws a generic panel with slot boxes. */
    public final ResourceLocation texture;
    public final int u;
    public final int v;
    public final int width;
    public final int height;
    /**
     * y of the player-inventory section inside the texture (84 on standard
     * 176x166 container GUIs, hotbar at +58), or -1 when the texture has no
     * inventory section — the editor then shows its own side panel.
     */
    public final int inventoryY;
    public final Kind kind;
    public final List<SlotDef> slots;
    public final List<FieldDef> fields;
    /**
     * Constant JSON merged into every saved recipe before slots/fields are
     * written — for keys a codec requires even when empty (e.g. a mandatory
     * empty array that filled slots then append into). Null = none.
     */
    public JsonObject template;
    /** Extra art (see {@link Decoration}); null = none. */
    public Decoration decoration;
    /**
     * Bundled layouts only: rewrites the built recipe JSON as the last step of
     * a save — for shapes slots can't express directly, e.g. collapsing an
     * either-or key that holds one ingredient OR a {first, second} pair. Null = none.
     */
    public java.util.function.UnaryOperator<JsonObject> finishSave;
    /**
     * Bundled layouts only: the inverse of {@link #finishSave} — rewrites an
     * encoded recipe before its values are read into the editor's slots and
     * fields (EMI's fill button, the screenshot harness). Null = none.
     */
    public java.util.function.UnaryOperator<JsonObject> prepareLoad;
    /**
     * Bundled layouts only: when set, the layout edits entries of this datapack registry (a Hephaestus
     * Forge ritual, say) instead of recipes — saves go through the registry save payload, the built
     * JSON carries no "type"/"id" keys, and an id is mandatory (it names the entry). {@link #typeId}
     * is then just the layout's unique identifier. Null = a normal recipe layout.
     */
    public String registryTarget;
    /**
     * True when the decoration draws its own slot frames, so the editor
     * skips its generic grey slot boxes and result arrow (texture-less
     * layouts only).
     */
    public boolean ownSlotArt;
    /**
     * Side length of the crafting grid for SHAPED/SHAPELESS layouts: the
     * first gridSize² slots are the grid, row-major. 3 for vanilla crafting.
     */
    public int gridSize = 3;

    public EditorLayout(String typeId, String displayName, ResourceLocation texture,
                        int u, int v, int width, int height, int inventoryY,
                        Kind kind, List<SlotDef> slots, List<FieldDef> fields) {
        this.typeId = typeId;
        this.displayName = displayName;
        this.texture = texture;
        this.u = u;
        this.v = v;
        this.width = width;
        this.height = height;
        this.inventoryY = inventoryY;
        this.kind = kind;
        this.slots = List.copyOf(slots);
        this.fields = List.copyOf(fields);
    }

    public EditorLayout(String typeId, String displayName, ResourceLocation texture,
                        int u, int v, int width, int height,
                        Kind kind, List<SlotDef> slots, List<FieldDef> fields) {
        this(typeId, displayName, texture, u, v, width, height, -1, kind, slots, fields);
    }

    /**
     * True when the layout shows the player inventory inside the panel (like
     * a real container screen). Works with a full 176x166 GUI texture or with
     * the editor's own panel style when there is no texture.
     */
    public boolean fullGui() {
        return inventoryY >= 0;
    }

    /** Parses one layout from a config JSON object. Throws IllegalArgumentException with a readable message. */
    public static EditorLayout fromJson(JsonObject obj) {
        String typeId = requireString(obj, "type");
        if (ResourceLocation.tryParse(typeId) == null) {
            throw new IllegalArgumentException("'type' is not a valid recipe type id: " + typeId);
        }
        String name = obj.get("name") instanceof JsonPrimitive p ? p.getAsString() : typeId;
        // Texture is optional: without one the editor draws a generic panel,
        // so a layout for another mod's type needs no assets at all.
        ResourceLocation texture = null;
        if (obj.get("texture") instanceof JsonPrimitive p && p.isString()) {
            texture = ResourceLocation.tryParse(p.getAsString());
            if (texture == null) {
                throw new IllegalArgumentException("'texture' is not a valid resource location: " + p.getAsString());
            }
        }

        int[] crop = {0, 0, 176, 80};
        if (obj.get("crop") instanceof JsonArray arr) {
            if (arr.size() != 4) {
                throw new IllegalArgumentException("'crop' must be [u, v, width, height]");
            }
            for (int i = 0; i < 4; i++) {
                crop[i] = arr.get(i).getAsInt();
            }
        }
        int inventoryY = obj.get("inventory_y") instanceof JsonPrimitive p && p.isNumber()
                ? p.getAsInt() : -1;

        List<SlotDef> slots = new ArrayList<>();
        if (!(obj.get("slots") instanceof JsonArray slotArr) || slotArr.isEmpty()) {
            throw new IllegalArgumentException("a layout needs a non-empty 'slots' list");
        }
        for (JsonElement el : slotArr) {
            if (!(el instanceof JsonObject slot)) {
                throw new IllegalArgumentException("every entry in 'slots' must be an object");
            }
            SlotFormat format = parseFormat(requireString(slot, "format"));
            // x/y are optional; missing coordinates are auto-placed below.
            int x = slot.get("x") instanceof JsonPrimitive p && p.isNumber() ? p.getAsInt() : -1;
            int y = slot.get("y") instanceof JsonPrimitive p && p.isNumber() ? p.getAsInt() : -1;
            slots.add(new SlotDef(x, y,
                    requireString(slot, "field"), format,
                    readBool(slot, "result"), readBool(slot, "required"),
                    readBool(slot, "chance"), readBool(slot, "tags"), null));
        }
        autoPlaceSlots(slots, crop);

        List<FieldDef> fields = new ArrayList<>();
        if (obj.get("fields") instanceof JsonArray fieldArr) {
            for (JsonElement el : fieldArr) {
                if (!(el instanceof JsonObject field)) {
                    throw new IllegalArgumentException("every entry in 'fields' must be an object");
                }
                String path = requireString(field, "field");
                String label = field.get("label") instanceof JsonPrimitive p ? p.getAsString() : path;
                FieldType type = switch (field.get("type") instanceof JsonPrimitive p
                        ? p.getAsString().toLowerCase(Locale.ROOT) : "string") {
                    case "int" -> FieldType.INT;
                    case "float" -> FieldType.FLOAT;
                    case "string" -> FieldType.STRING;
                    case "bool", "boolean" -> FieldType.BOOL;
                    default -> throw new IllegalArgumentException(
                            "field '" + path + "': unknown type (expected int, float, string or bool)");
                };
                String defaultValue = field.get("default") instanceof JsonPrimitive p ? p.getAsString() : "";
                List<String> suggestions = new ArrayList<>();
                if (field.get("suggestions") instanceof JsonArray suggestionArr) {
                    for (JsonElement s : suggestionArr) {
                        if (s instanceof JsonPrimitive sp) {
                            suggestions.add(sp.getAsString());
                        }
                    }
                }
                fields.add(new FieldDef(path, label, type, defaultValue,
                        readBool(field, "required"), List.copyOf(suggestions)));
            }
        }

        EditorLayout layout = new EditorLayout(typeId, name, texture, crop[0], crop[1], crop[2], crop[3],
                inventoryY, Kind.SLOTS, slots, fields);
        if (obj.get("template") instanceof JsonObject template) {
            layout.template = template.deepCopy();
        }
        return layout;
    }

    /**
     * Slots without coordinates get editor-chosen positions: inputs in a
     * 3-wide grid on the left, results in a column on the right — so a layout
     * can skip texture and coordinates entirely. Also used by {@link AutoLayouts}.
     */
    static void autoPlaceSlots(List<SlotDef> slots, int[] crop) {
        int inputIndex = 0;
        int resultIndex = 0;
        for (int i = 0; i < slots.size(); i++) {
            SlotDef slot = slots.get(i);
            if (slot.x() >= 0 && slot.y() >= 0) {
                continue;
            }
            int x;
            int y;
            if (slot.result()) {
                x = crop[0] + crop[2] - 44;
                y = crop[1] + 16 + resultIndex * 20;
                resultIndex++;
            } else {
                x = crop[0] + 26 + (inputIndex % 3) * 20;
                y = crop[1] + 16 + (inputIndex / 3) * 20;
                inputIndex++;
            }
            slots.set(i, new SlotDef(x, y, slot.path(), slot.format(), slot.result(), slot.required(),
                    slot.chanceable(), slot.tags(), slot.custom()));
        }
    }

    private static SlotFormat parseFormat(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "string" -> SlotFormat.STRING;
            case "ingredient" -> SlotFormat.INGREDIENT;
            case "counted_ingredient" -> SlotFormat.COUNTED_INGREDIENT;
            case "item" -> SlotFormat.ITEM;
            case "item_named" -> SlotFormat.ITEM_NAMED;
            case "fluid" -> SlotFormat.FLUID;
            case "fluid_amount" -> SlotFormat.FLUID_AMOUNT;
            case "shorthand_result" -> SlotFormat.SHORTHAND_RESULT;
            case "item_id" -> SlotFormat.ITEM_ID;
            default -> throw new IllegalArgumentException("unknown slot format '" + s
                    + "' (expected string, item_id, ingredient, counted_ingredient, item, item_named, fluid, fluid_amount)");
        };
    }

    private static String requireString(JsonObject obj, String key) {
        if (obj.get(key) instanceof JsonPrimitive p && p.isString()) {
            return p.getAsString();
        }
        throw new IllegalArgumentException("missing or non-string '" + key + "'");
    }

    private static int requireInt(JsonObject obj, String key) {
        if (obj.get(key) instanceof JsonPrimitive p && p.isNumber()) {
            return p.getAsInt();
        }
        throw new IllegalArgumentException("missing or non-numeric '" + key + "'");
    }

    private static boolean readBool(JsonObject obj, String key) {
        return obj.get(key) instanceof JsonPrimitive p && p.isBoolean() && p.getAsBoolean();
    }
}
