package net.ledok.datarewriter.client.gui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Sub-editor of a DD Loot Stage {@code loot_stage:level_table} entry: the level → table list (the highest
 * level at or below the loot level picks the table; below the lowest one the lowest one is used), plus the
 * entry's weight and quality, and a preview of which table a given level rolls.
 */
final class LevelTableScreen extends Screen {
    private static final int ROW_H = 22;

    private final Screen parent;
    private final JsonObject entry;
    private final Consumer<JsonObject> done;
    private final List<Row> rows = new ArrayList<>();
    private String weightText;
    private String qualityText;
    private String previewText = "1";
    private int scroll;
    private int visibleRows;
    private int top;
    private Component status = Component.empty();

    /** One level → table pair; the level stays text until Done so half-typed values survive re-inits. */
    private static final class Row {
        String level;
        String table;

        Row(String level, String table) {
            this.level = level;
            this.table = table;
        }
    }

    LevelTableScreen(Screen parent, JsonObject entry, Consumer<JsonObject> done) {
        super(Component.literal("Level table (DD Loot Stage)"));
        this.parent = parent;
        this.entry = entry;
        this.done = done;
        if (entry.get("tables") instanceof JsonObject tables) {
            for (Map.Entry<String, JsonElement> e : tables.entrySet()) {
                rows.add(new Row(e.getKey(), e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : ""));
            }
        }
        rows.sort(Comparator.comparingInt(row -> parseLevel(row.level, Integer.MAX_VALUE)));
        weightText = entry.get("weight") instanceof JsonPrimitive w && w.isNumber() ? String.valueOf(w.getAsInt()) : "1";
        qualityText = entry.get("quality") instanceof JsonPrimitive q && q.isNumber() ? String.valueOf(q.getAsInt()) : "0";
    }

    @Override
    protected void init() {
        int w = Math.min(360, width - 20);
        int left = (width - w) / 2;
        top = 24;
        int footer = 22 * 3 + 30;
        visibleRows = Math.max(1, (height - top - 16 - footer) / ROW_H);
        scroll = Math.max(0, Math.min(scroll, rows.size() - visibleRows));

        int y = top + 14;
        for (int i = scroll; i < Math.min(rows.size(), scroll + visibleRows); i++) {
            Row row = rows.get(i);
            EditBox level = new EditBox(font, left, y + 1, 40, 18, Component.literal("Level"));
            level.setValue(row.level);
            level.setResponder(text -> row.level = text);
            addRenderableWidget(level);
            int tableW = w - 40 - 4 - 40 - 4 - 20 - 4;
            addRenderableWidget(Button.builder(Component.literal(shorten(row.table, tableW)), b -> {
                        assert minecraft != null;
                        minecraft.setScreen(new LootTablePickerScreen(this, id -> row.table = id));
                    })
                    .bounds(left + 44, y, tableW, 20)
                    .tooltip(Tooltip.create(Component.literal(row.table + "\nClick to pick another table")))
                    .build());
            addRenderableWidget(Button.builder(Component.literal("View"), b -> view(row.table))
                    .bounds(left + 44 + tableW + 4, y, 40, 20)
                    .tooltip(Tooltip.create(Component.literal("Open this table in the loot table editor")))
                    .build());
            addRenderableWidget(Button.builder(Component.literal("✕"), b -> {
                        rows.remove(row);
                        rebuildWidgets();
                    })
                    .bounds(left + w - 20, y, 20, 20)
                    .tooltip(Tooltip.create(Component.literal("Remove this level")))
                    .build());
            y += ROW_H;
        }

        y = height - footer;
        addRenderableWidget(Button.builder(Component.literal("Add level"), b -> {
                    int next = rows.stream().mapToInt(row -> parseLevel(row.level, 0)).max().orElse(0) + 1;
                    String table = rows.isEmpty() ? "minecraft:empty" : rows.get(rows.size() - 1).table;
                    rows.add(new Row(String.valueOf(next), table));
                    scroll = Math.max(0, rows.size() - visibleRows);
                    rebuildWidgets();
                })
                .bounds(left, y, 90, 20)
                .tooltip(Tooltip.create(Component.literal("A new level, starting with the last row's table")))
                .build());
        EditBox weight = new EditBox(font, left + 150, y + 1, 50, 18, Component.literal("Weight"));
        weight.setValue(weightText);
        weight.setResponder(text -> weightText = text);
        addRenderableWidget(weight);
        EditBox quality = new EditBox(font, left + 260, y + 1, 50, 18, Component.literal("Quality"));
        quality.setValue(qualityText);
        quality.setResponder(text -> qualityText = text);
        addRenderableWidget(quality);

        y += 24;
        EditBox preview = new EditBox(font, left + 100, y + 1, 40, 18, Component.literal("Preview level"));
        preview.setValue(previewText);
        preview.setResponder(text -> previewText = text);
        addRenderableWidget(preview);
        addRenderableWidget(Button.builder(Component.literal("View"), b -> {
                    String table = tableFor(parseLevel(previewText, 1));
                    if (table != null) {
                        view(table);
                    }
                })
                .bounds(left + w - 40, y, 40, 20)
                .tooltip(Tooltip.create(Component.literal("Open the table this level rolls")))
                .build());

        y += 30;
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> finish())
                .bounds(left + w / 2 - 104, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(left + w / 2 + 4, y, 100, 20).build());
    }

    private void view(String table) {
        if (ResourceLocation.tryParse(table) == null) {
            return;
        }
        assert minecraft != null;
        minecraft.setScreen(new LootTableEditorScreen(this, table, true));
    }

    private String shorten(String text, int width) {
        return font.width(text) <= width - 8 ? text : font.plainSubstrByWidth(text, width - 14) + "…";
    }

    private static int parseLevel(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** What the add-on picks for a level: the highest level at or below it, else the lowest one. */
    private @Nullable String tableFor(int level) {
        Row best = null;
        Row lowest = null;
        for (Row row : rows) {
            int value = parseLevel(row.level, Integer.MIN_VALUE);
            if (value == Integer.MIN_VALUE) {
                continue;
            }
            if (lowest == null || value < parseLevel(lowest.level, 0)) {
                lowest = row;
            }
            if (value <= level && (best == null || value > parseLevel(best.level, 0))) {
                best = row;
            }
        }
        Row pick = best != null ? best : lowest;
        return pick == null ? null : pick.table;
    }

    private void finish() {
        String error = problem();
        if (error != null) {
            status = Component.literal(error).withStyle(ChatFormatting.RED);
            return;
        }
        JsonObject result = entry.deepCopy();
        JsonObject tables = new JsonObject();
        rows.stream()
                .sorted(Comparator.comparingInt(row -> parseLevel(row.level, 0)))
                .forEach(row -> tables.addProperty(String.valueOf(parseLevel(row.level, 0)), row.table));
        result.add("tables", tables);
        int weight = parseLevel(weightText, 1);
        int quality = parseLevel(qualityText, 0);
        if (weight == 1) {
            result.remove("weight");
        } else {
            result.addProperty("weight", weight);
        }
        if (quality == 0) {
            result.remove("quality");
        } else {
            result.addProperty("quality", quality);
        }
        done.accept(result);
        onClose();
    }

    private @Nullable String problem() {
        if (rows.isEmpty()) {
            return "Add at least one level.";
        }
        Set<Integer> seen = new HashSet<>();
        for (Row row : rows) {
            int level = parseLevel(row.level, Integer.MIN_VALUE);
            if (level == Integer.MIN_VALUE) {
                return "'" + row.level + "' is not a whole number.";
            }
            if (!seen.add(level)) {
                return "Level " + level + " is listed twice.";
            }
            if (ResourceLocation.tryParse(row.table) == null || row.table.isBlank()) {
                return "Level " + level + ": pick a loot table.";
            }
        }
        if (parseLevel(weightText, Integer.MIN_VALUE) < 1) {
            return "Weight must be a whole number, 1 or more.";
        }
        if (parseLevel(qualityText, Integer.MIN_VALUE) == Integer.MIN_VALUE) {
            return "Quality must be a whole number.";
        }
        return null;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (rows.size() > visibleRows) {
            scroll = Math.max(0, Math.min(rows.size() - visibleRows, scroll - (int) Math.signum(scrollY)));
            rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int w = Math.min(360, width - 20);
        int left = (width - w) / 2;
        graphics.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
        graphics.drawString(font, Component.literal("Level").withStyle(ChatFormatting.GRAY), left, top, 0xA0A0A0);
        graphics.drawString(font, Component.literal("Loot table (from this level up)").withStyle(ChatFormatting.GRAY),
                left + 44, top, 0xA0A0A0);
        if (rows.size() > visibleRows) {
            graphics.drawString(font, Component.literal("scroll for more (" + rows.size() + " levels)")
                    .withStyle(ChatFormatting.DARK_GRAY), left + w - 130, top, 0x707070);
        }
        int footer = 22 * 3 + 30;
        int y = height - footer;
        graphics.drawString(font, "Weight", left + 106, y + 6, 0xFFFFFF);
        graphics.drawString(font, "Quality", left + 212, y + 6, 0xFFFFFF);
        y += 24;
        graphics.drawString(font, "Level", left + 60, y + 6, 0xFFFFFF);
        String pick = tableFor(parseLevel(previewText, 1));
        String line = "rolls " + (pick == null ? "nothing" : pick);
        graphics.drawString(font, font.plainSubstrByWidth(line, w - 150 - 44), left + 146, y + 6, 0xA0E0A0);
        if (!status.getString().isEmpty()) {
            graphics.drawCenteredString(font, status, width / 2, height - 12, 0xFFFFFF);
        }
    }

    @Override
    public void onClose() {
        assert minecraft != null;
        minecraft.setScreen(parent);
    }
}
