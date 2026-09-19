package net.ledok.datarewriter.client.gui;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

import static net.ledok.datarewriter.client.gui.EditorLayout.FieldDef;
import static net.ledok.datarewriter.client.gui.EditorLayout.FieldType;
import static net.ledok.datarewriter.client.gui.EditorLayout.Kind;
import static net.ledok.datarewriter.client.gui.EditorLayout.SlotDef;
import static net.ledok.datarewriter.client.gui.EditorLayout.SlotFormat;

/**
 * Bundled layouts for Eternal Starlight (verified against its 1.21.1 sources):
 * <ul>
 * <li>{@code alloy} — alloy furnace, drawn on the mod's own furnace GUI: 3x3
 * ingredients ({@code ingredients[]}, {"item"|"tag"}), up to 3 results
 * ({@code results[]}, {"item": {"id"}, "amount": n}), {@code burn_time}.</li>
 * <li>{@code drying} — drying rack: {@code input} ingredient, {@code output}
 * item stack, {@code duration_ticks}, {@code fire_below}.</li>
 * <li>{@code geyser_smoking} — abyssal geyser: {@code input} bare item id +
 * {@code input_count}, {@code output} item stack.</li>
 * <li>{@code tool_modification} — crafting-table special recipe: {@code tool}
 * and {@code input} bare item ids, {@code output} item stack.</li>
 * <li>{@code mana_crystal} — crafting-table special recipe: {@code crystal}
 * item id and {@code mana_type}.</li>
 * </ul>
 * {@code accessory_combination} carries no data (dynamic recipe) and is hidden.
 */
final class EternalStarlightLayouts {
    private static final String NS = "eternal_starlight";
    private static final ResourceLocation ALLOY_FURNACE_GUI =
            ResourceLocation.fromNamespaceAndPath(NS, "textures/gui/screen/alloy_furnace/background.png");
    private static final ResourceLocation CRAFTING_GUI =
            ResourceLocation.withDefaultNamespace("textures/gui/container/crafting_table.png");
    private static final FieldDef CATEGORY = new FieldDef("category", "Book tab", FieldType.STRING, "misc", false,
            List.of("misc", "building", "redstone", "equipment"));

    /** Alloy result: {"item": {"id": ref}, "amount": n} (a constant amount; ranges are JSON-only). */
    private static final EditorLayout.SlotValueBuilder ALLOY_RESULT = (ref, count, chance) -> {
        JsonObject item = new JsonObject();
        item.addProperty("id", ref);
        JsonObject entry = new JsonObject();
        entry.add("item", item);
        entry.addProperty("amount", Math.max(1, count));
        return entry;
    };

    private EternalStarlightLayouts() {
    }

    static List<EditorLayout> forInstalledTypes() {
        List<EditorLayout> layouts = new ArrayList<>();
        add(layouts, NS + ":alloy", EternalStarlightLayouts::alloy);
        add(layouts, NS + ":drying", EternalStarlightLayouts::drying);
        add(layouts, NS + ":geyser_smoking", EternalStarlightLayouts::geyserSmoking);
        add(layouts, NS + ":tool_modification", EternalStarlightLayouts::toolModification);
        add(layouts, NS + ":mana_crystal", EternalStarlightLayouts::manaCrystal);
        return layouts;
    }

    private static void add(List<EditorLayout> layouts, String typeId, java.util.function.Supplier<EditorLayout> factory) {
        ResourceLocation id = ResourceLocation.tryParse(typeId);
        if (id != null && BuiltInRegistries.RECIPE_SERIALIZER.containsKey(id)) {
            layouts.add(factory.get());
        }
    }

    /** Alloy furnace: slot positions from AlloyFurnaceMenu; fuel/cooling slots are machine gear, not recipe data. */
    private static EditorLayout alloy() {
        List<SlotDef> slots = new ArrayList<>();
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 3; x++) {
                slots.add(new SlotDef(30 + x * 18, 17 + y * 18, "ingredients[]",
                        SlotFormat.INGREDIENT, false, x == 0 && y == 0));
            }
        }
        slots.add(new SlotDef(124, 18, "results[]", SlotFormat.ITEM, true, true, ALLOY_RESULT));
        slots.add(new SlotDef(115, 53, "results[]", SlotFormat.ITEM, true, false, ALLOY_RESULT));
        slots.add(new SlotDef(133, 53, "results[]", SlotFormat.ITEM, true, false, ALLOY_RESULT));
        return new EditorLayout(NS + ":alloy", "Alloy Furnace (Eternal Starlight)",
                ALLOY_FURNACE_GUI, 0, 0, 176, 166, 84, Kind.SLOTS, slots,
                List.of(new FieldDef("burn_time", "Burn time (ticks)", FieldType.INT, "200", true)));
    }

    /** Drying rack (no GUI): input left, output right, the rack in between — with a campfire under it when fire_below. */
    private static EditorLayout drying() {
        List<SlotDef> slots = List.of(
                new SlotDef(26, 30, "input", SlotFormat.INGREDIENT, false, true),
                new SlotDef(134, 30, "output", SlotFormat.ITEM, true, true));
        EditorLayout layout = new EditorLayout(NS + ":drying", "Drying Rack (Eternal Starlight)",
                null, 0, 0, 176, 78, Kind.SLOTS, slots,
                List.of(new FieldDef("duration_ticks", "Time (ticks)", FieldType.INT, "100", true),
                        new FieldDef("fire_below", "Needs fire below", FieldType.BOOL, "false", true)));
        layout.decoration = (graphics, left, top, partialTick, view) -> {
            boolean fire = view.field("fire_below").trim().equalsIgnoreCase("true");
            PoseStack pose = graphics.pose();
            pose.pushPose();
            pose.translate(left + 88, top + (fire ? 40 : 46), 100);
            pose.mulPose(Axis.XP.rotationDegrees(-15.5f));
            pose.mulPose(Axis.YP.rotationDegrees(22.5f));
            int scale = 20;
            pose.translate(-scale / 2f, 0, 0);
            GuiBlocks.block(GuiBlocks.state(NS + ":drying_rack")).scale(scale).render(graphics);
            if (fire) {
                GuiBlocks.block(Blocks.CAMPFIRE.defaultBlockState()).atLocal(0, 1, 0).scale(scale).render(graphics);
            }
            pose.popPose();
        };
        return layout;
    }

    /** Abyssal geyser (no GUI): the input is a bare item id with its own count field. */
    private static EditorLayout geyserSmoking() {
        List<SlotDef> slots = List.of(
                new SlotDef(26, 30, "input", SlotFormat.ITEM_ID, false, true),
                new SlotDef(134, 30, "output", SlotFormat.ITEM, true, true));
        EditorLayout layout = new EditorLayout(NS + ":geyser_smoking", "Geyser Smoking (Eternal Starlight)",
                null, 0, 0, 176, 78, Kind.SLOTS, slots,
                List.of(new FieldDef("input_count", "Input count", FieldType.INT, "1", true)));
        layout.decoration = (graphics, left, top, partialTick, view) -> {
            PoseStack pose = graphics.pose();
            pose.pushPose();
            pose.translate(left + 88, top + 46, 100);
            pose.mulPose(Axis.XP.rotationDegrees(-15.5f));
            pose.mulPose(Axis.YP.rotationDegrees(22.5f));
            int scale = 20;
            pose.translate(-scale / 2f, 0, 0);
            GuiBlocks.block(GuiBlocks.state(NS + ":abyssal_geyser")).scale(scale).render(graphics);
            pose.popPose();
        };
        return layout;
    }

    /** Tool modification: a crafting-table special recipe — tool + input -> output. */
    private static EditorLayout toolModification() {
        List<SlotDef> slots = List.of(
                new SlotDef(30, 35, "tool", SlotFormat.ITEM_ID, false, true),
                new SlotDef(66, 35, "input", SlotFormat.ITEM_ID, false, true),
                new SlotDef(124, 35, "output", SlotFormat.ITEM, true, true));
        return new EditorLayout(NS + ":tool_modification", "Tool Modification — crafting (Eternal Starlight)",
                CRAFTING_GUI, 0, 0, 176, 166, 84, Kind.SLOTS, slots, List.of(CATEGORY));
    }

    /** Mana crystal: a crafting-table special recipe keyed by crystal item and mana type. */
    private static EditorLayout manaCrystal() {
        List<SlotDef> slots = List.of(
                new SlotDef(48, 35, "crystal", SlotFormat.ITEM_ID, false, true));
        return new EditorLayout(NS + ":mana_crystal", "Mana Crystal — crafting (Eternal Starlight)",
                CRAFTING_GUI, 0, 0, 176, 166, 84, Kind.SLOTS, slots,
                List.of(new FieldDef("mana_type", "Mana type", FieldType.STRING, "", true,
                                List.of("empty", "terra", "wind", "water", "lunar", "blaze", "light")),
                        CATEGORY));
    }
}
