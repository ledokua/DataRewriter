package net.ledok.datarewriter.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import net.fabricmc.loader.api.FabricLoader;
import net.ledok.datarewriter.Datarewriter;
import net.ledok.datarewriter.config.ConfigLoader;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static net.ledok.datarewriter.client.gui.EditorLayout.FieldDef;
import static net.ledok.datarewriter.client.gui.EditorLayout.FieldType;
import static net.ledok.datarewriter.client.gui.EditorLayout.Kind;
import static net.ledok.datarewriter.client.gui.EditorLayout.SlotDef;
import static net.ledok.datarewriter.client.gui.EditorLayout.SlotFormat;

/** Built-in vanilla layouts plus user layouts from config/datarewriter/editor-layouts/. */
public final class EditorLayouts {
    /**
     * Recipe types hidden from the editor: not real data-driven recipes
     * (dynamic matchers / dummy data carriers), so creating them would only
     * confuse. Users can extend this with { type: "...", hidden: true }
     * entries in their layout files.
     */
    private static final Set<String> HIDDEN_TYPES = Set.of(
            "herbalbrews:cauldron_brewing" // matches() is always false; one dummy recipe ships
    );

    private EditorLayouts() {
    }

    /**
     * Reloaded on every editor open, so layout files are live-editable.
     * Precedence: built-ins, then layout files, then automatically inferred
     * layouts for any remaining modded recipe type (see {@link AutoLayouts}).
     */
    public static List<EditorLayout> loadAll() {
        List<EditorLayout> layouts = new ArrayList<>(builtIns());
        Set<String> hidden = new HashSet<>(HIDDEN_TYPES);

        Path dir = FabricLoader.getInstance().getConfigDir()
                .resolve(Datarewriter.MOD_ID).resolve("editor-layouts");
        try {
            if (!Files.isDirectory(dir)) {
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("example.json5"), EXAMPLE_FILE);
            }
            try (Stream<Path> stream = Files.walk(dir)) {
                List<Path> files = stream.filter(Files::isRegularFile)
                        .filter(p -> {
                            String name = p.getFileName().toString();
                            return name.endsWith(".json") || name.endsWith(".json5");
                        })
                        .sorted()
                        .toList();
                for (Path file : files) {
                    loadFile(file, layouts, hidden);
                }
            }
        } catch (IOException e) {
            Datarewriter.LOGGER.error("Could not read editor layouts from {}", dir, e);
        }

        layouts.removeIf(layout -> hidden.contains(layout.typeId));
        Set<String> knownTypes = new HashSet<>(hidden);
        for (EditorLayout layout : layouts) {
            knownTypes.add(layout.typeId);
        }
        // Bundled layouts for known mods; a user layout file for the same
        // type (already in the list) wins.
        for (EditorLayout bundled : BundledLayouts.forInstalledTypes()) {
            if (knownTypes.add(bundled.typeId)) {
                layouts.add(bundled);
            }
        }
        layouts.addAll(AutoLayouts.infer(knownTypes));
        return layouts;
    }

    private static void loadFile(Path file, List<EditorLayout> layouts, Set<String> hidden) {
        String fileName = file.getFileName().toString();
        try {
            String content = ConfigLoader.stripCommentsAndTrailingCommas(Files.readString(file));
            JsonReader reader = new JsonReader(new StringReader(content));
            reader.setLenient(true);
            JsonElement parsed = JsonParser.parseReader(reader);
            // A file may hold one layout object or a list of them.
            List<JsonElement> entries = parsed instanceof JsonArray arr
                    ? arr.asList() : List.of(parsed);
            for (JsonElement entry : entries) {
                if (!(entry instanceof JsonObject obj)) {
                    Datarewriter.LOGGER.error("[{}] editor layout entries must be objects", fileName);
                    continue;
                }
                if (obj.get("hidden") instanceof JsonPrimitive p && p.isBoolean() && p.getAsBoolean()) {
                    if (obj.get("type") instanceof JsonPrimitive type && type.isString()) {
                        hidden.add(type.getAsString());
                    } else {
                        Datarewriter.LOGGER.error("[{}] a 'hidden' entry needs a 'type'", fileName);
                    }
                    continue;
                }
                layouts.add(EditorLayout.fromJson(obj));
            }
        } catch (Exception e) {
            Datarewriter.LOGGER.error("[{}] Failed to load editor layout: {}", fileName, e.getMessage());
        }
    }

    private static List<EditorLayout> builtIns() {
        List<EditorLayout> list = new ArrayList<>();

        // 3x3 grid + result, positions from CraftingMenu.
        List<SlotDef> craftingSlots = new ArrayList<>();
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                craftingSlots.add(new SlotDef(30 + col * 18, 17 + row * 18, "", SlotFormat.STRING, false, false));
            }
        }
        craftingSlots.add(new SlotDef(124, 35, "result", SlotFormat.SHORTHAND_RESULT, true, true));
        list.add(new EditorLayout("minecraft:crafting_shaped", "Crafting (shaped)",
                container("crafting_table"), 0, 0, 176, 166, 84, Kind.SHAPED, craftingSlots, List.of()));
        list.add(new EditorLayout("minecraft:crafting_shapeless", "Crafting (shapeless)",
                container("crafting_table"), 0, 0, 176, 166, 84, Kind.SHAPELESS, craftingSlots, List.of()));

        list.add(cooking("smelting", "Smelting (furnace)", "furnace", 200));
        list.add(cooking("blasting", "Blasting (blast furnace)", "blast_furnace", 100));
        list.add(cooking("smoking", "Smoking (smoker)", "smoker", 100));
        list.add(cooking("campfire_cooking", "Campfire cooking", "furnace", 600));

        list.add(new EditorLayout("minecraft:stonecutting", "Stonecutting",
                container("stonecutter"), 0, 0, 176, 166, 84, Kind.SLOTS,
                List.of(new SlotDef(20, 33, "ingredient", SlotFormat.STRING, false, true),
                        new SlotDef(143, 33, "result", SlotFormat.SHORTHAND_RESULT, true, true)),
                List.of()));

        List<SlotDef> smithingInputs = List.of(
                new SlotDef(8, 48, "template", SlotFormat.STRING, false, true),
                new SlotDef(26, 48, "base", SlotFormat.STRING, false, true),
                new SlotDef(44, 48, "addition", SlotFormat.STRING, false, true));
        List<SlotDef> smithingTransform = new ArrayList<>(smithingInputs);
        smithingTransform.add(new SlotDef(98, 48, "result", SlotFormat.SHORTHAND_RESULT, true, true));
        list.add(new EditorLayout("minecraft:smithing_transform", "Smithing (upgrade)",
                container("smithing"), 0, 0, 176, 166, 84, Kind.SLOTS, smithingTransform, List.of()));
        list.add(new EditorLayout("minecraft:smithing_trim", "Smithing (trim)",
                container("smithing"), 0, 0, 176, 166, 84, Kind.SLOTS, smithingInputs, List.of()));

        return list;
    }

    private static EditorLayout cooking(String type, String name, String texture, int defaultTime) {
        return new EditorLayout("minecraft:" + type, name, container(texture), 0, 0, 176, 166, 84, Kind.SLOTS,
                List.of(new SlotDef(56, 17, "ingredient", SlotFormat.STRING, false, true),
                        new SlotDef(116, 35, "result", SlotFormat.SHORTHAND_RESULT, true, true)),
                List.of(new FieldDef("experience", "XP", FieldType.FLOAT, "0.1", false),
                        new FieldDef("cookingtime", "Time (ticks)", FieldType.INT,
                                String.valueOf(defaultTime), false)));
    }

    private static ResourceLocation container(String name) {
        return ResourceLocation.withDefaultNamespace("textures/gui/container/" + name + ".png");
    }

    private static final String EXAMPLE_FILE = """
            // DataRewriter — recipe editor layouts (CLIENT-side config).
            // Each layout teaches the in-game recipe editor one modded recipe type:
            // how its slots and fields map into the recipe JSON, and (optionally)
            // which GUI texture to draw. A file can hold one layout object or a list.
            //
            // "texture" and slot "x"/"y" are OPTIONAL. Leave them out and the editor
            // draws a generic panel and places the slots for you — the quickest way
            // to support another mod's recipe type. For the authentic look, use the
            // mod's GUI texture and the slot positions from its Menu/ScreenHandler
            // class (the same numbers passed to `new Slot(...)`). "crop" = [u, v,
            // width, height] picks the region of the texture to show (or the panel
            // size if there is no texture); slot coordinates are relative to the
            // FULL texture, the crop offset is subtracted for you.
            //
            // Slot "format" — how the picked item is written into the JSON:
            //   "string"             -> "mod:item" or "#mod:tag"
            //   "ingredient"         -> {"item": id} / {"tag": id}
            //   "counted_ingredient" -> {"ingredient": {"item"/"tag": id}, "count": n}
            //   "item"               -> {"id": id, "count": n}
            //   "item_named"         -> {"item": id, "count": n}
            //   "fluid"              -> {"id": fluid id, "amount_mb": n} (picker lists fluids)
            //   "fluid_amount"       -> {"id": fluid id, "amount": n} (picker lists fluids)
            // Counted formats: scroll the mouse wheel over the slot to set the amount.
            // Add "chance": true to a slot to set a drop chance with Alt+Scroll
            // (written as a "chance" key on the slot's JSON object).
            // Add "tags": true to also allow #tags where the format normally
            // doesn't (fluid formats then write {"tag": id} instead of {"id": id})
            // — only do this if the recipe type actually accepts a tag there.
            //
            // An entry of just { type: "somemod:sometype", hidden: true } removes
            // that recipe type from the editor entirely (useful for dynamic or
            // dummy recipe types that can't sensibly be created).
            //
            // An optional "template": { ... } object is merged into every saved
            // recipe before slots/fields — for keys a codec requires even when
            // empty (e.g. a mandatory empty list that slots then append into).
            // "field" paths support nesting ("result.item") and arrays ("ingredients[]").
            //
            // If the texture is a full 176x166 container GUI, add "inventory_y": 84
            // — the editor then draws the whole GUI with your inventory in its
            // normal place (hotbar at inventory_y + 58) instead of a side panel.
            //
            // Minimal (no texture, auto-placed slots):
            // {
            //   type: "somemod:cutting",
            //   name: "Cutting Board (Some Mod)",
            //   slots: [
            //     { field: "ingredients[]", format: "ingredient", required: true },
            //     { field: "result", format: "item", result: true, required: true },
            //   ],
            //   fields: [
            //     { field: "processing_time", label: "Time", type: "int", default: "100" },
            //   ],
            // }
            //
            // Full (the mod's own GUI):
            // {
            //   type: "somemod:cutting",
            //   name: "Cutting Board (Some Mod)",
            //   texture: "somemod:textures/gui/cutting_board.png",
            //   crop: [0, 0, 176, 80],
            //   slots: [
            //     { x: 56, y: 17, field: "ingredients[]", format: "ingredient", required: true },
            //     { x: 116, y: 35, field: "result", format: "item", result: true, required: true },
            //   ],
            //   fields: [
            //     { field: "processing_time", label: "Time", type: "int", default: "100" },
            //   ],
            // }
            """;
}
