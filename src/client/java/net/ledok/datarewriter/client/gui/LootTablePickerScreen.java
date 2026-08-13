package net.ledok.datarewriter.client.gui;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.datarewriter.network.LootPayloads;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Searchable list of every loot table on the server, opened by
 * /loottableeditor. The list comes from the server (loot tables are never
 * synced to clients); it can be narrowed to tables that drop a chosen item.
 * Also hosts the bulk actions: replace/remove an item across all tables.
 */
public class LootTablePickerScreen extends Screen {
    private static final int ROW_HEIGHT = 13;
    private static final int LIST_WIDTH = 300;

    private String query = "";
    /** Item id the server-side list was filtered by ("" = all tables). */
    private String itemFilter = "";
    private List<String> ids = List.of();
    private List<String> filtered = List.of();
    private boolean loading = true;
    private int scrollRow;

    /** Non-null = selection mode: clicking a table hands its id back instead of editing. */
    private final Consumer<String> onSelect;
    private final Screen returnTo;

    public LootTablePickerScreen() {
        super(Component.literal("Loot Table Editor"));
        this.onSelect = null;
        this.returnTo = null;
    }

    /** Selection mode: pick a table id (with search/filter) and return to {@code returnTo}. */
    public LootTablePickerScreen(Screen returnTo, Consumer<String> onSelect) {
        super(Component.literal("Pick a loot table"));
        this.onSelect = onSelect;
        this.returnTo = returnTo;
    }

    /** Called by the client networking receiver when the server's list arrives. */
    public void onList(String forItemFilter, List<String> tableIds) {
        if (!forItemFilter.equals(itemFilter)) {
            return; // stale answer for a filter that has changed since
        }
        this.ids = tableIds;
        this.loading = false;
        refresh();
    }

    private void requestList() {
        loading = true;
        ids = List.of();
        refresh();
        ClientPlayNetworking.send(new LootPayloads.TableListRequest(itemFilter));
    }

    private int listLeft() {
        return (width - LIST_WIDTH) / 2;
    }

    private int listTop() {
        return 56;
    }

    private int visibleRows() {
        return Math.max(1, (height - listTop() - 66) / ROW_HEIGHT);
    }

    @Override
    protected void init() {
        EditBox searchBox = new EditBox(font, listLeft(), 30, LIST_WIDTH, 18, Component.literal("Search"));
        searchBox.setMaxLength(256);
        searchBox.setHint(Component.literal("search — Enter on a full id creates a new table")
                .withStyle(ChatFormatting.DARK_GRAY));
        searchBox.setValue(query);
        searchBox.setResponder(text -> {
            query = text;
            refresh();
        });
        addRenderableWidget(searchBox);
        setInitialFocus(searchBox);

        int buttonY = height - 52;
        int third = (LIST_WIDTH - 8) / 3;
        addRenderableWidget(Button.builder(itemFilterLabel(), b -> pickItemFilter())
                .bounds(listLeft(), buttonY, itemFilter.isEmpty() ? LIST_WIDTH / 3 : LIST_WIDTH / 3 * 2, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "Show only loot tables that drop a chosen item — or, with a #tag "
                                + "(e.g. #c:fishes), any item from that tag")))
                .build());
        if (!itemFilter.isEmpty()) {
            addRenderableWidget(Button.builder(Component.literal("All tables"), b -> {
                        itemFilter = "";
                        requestList();
                        rebuildWidgets();
                    })
                    .bounds(listLeft() + LIST_WIDTH / 3 * 2 + 4, buttonY, LIST_WIDTH / 3 - 4, 20)
                    .build());
        } else if (onSelect == null) {
            addRenderableWidget(Button.builder(Component.literal("Replace item…"), b -> bulkReplace())
                    .bounds(listLeft() + third + 4, buttonY, third, 20)
                    .tooltip(Tooltip.create(Component.literal(
                            "Swap one item for another across loot tables — you choose "
                                    + "which tables (all, one mod, no block drops, …)")))
                    .build());
            addRenderableWidget(Button.builder(Component.literal("Remove item…"), b -> bulkRemove())
                    .bounds(listLeft() + (third + 4) * 2, buttonY, third, 20)
                    .tooltip(Tooltip.create(Component.literal(
                            "Delete an item from loot tables you choose (the rest of each table stays)")))
                    .build());
        }

        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds((width - 80) / 2, height - 28, 80, 20)
                .build());

        if (loading && ids.isEmpty()) {
            requestList();
        } else {
            refresh();
        }
    }

    private Component itemFilterLabel() {
        if (itemFilter.isEmpty()) {
            return Component.literal("Filter by item…");
        }
        Component name = itemFilter.contains("*") || itemFilter.startsWith("#")
                ? Component.literal(itemFilter)
                : RecipeEditorScreen.iconFor(itemFilter, false).getHoverName();
        return Component.literal("Dropping: ").withStyle(ChatFormatting.GRAY).append(name);
    }

    private void pickItemFilter() {
        assert minecraft != null;
        minecraft.setScreen(new ItemSelectScreen(this,
                Component.literal("Show tables dropping which item?"), true, true, ref -> {
            itemFilter = ref;
            requestList();
            rebuildWidgets();
        }));
    }

    private void bulkReplace() {
        assert minecraft != null;
        minecraft.setScreen(new ItemSelectScreen(this,
                Component.literal("Replace which item (or #tag)?"), true, true, from ->
                minecraft.setScreen(new ItemSelectScreen(this,
                        Component.literal("Replace with which item?"), false, to ->
                        minecraft.setScreen(new TableScopeScreen(this, tables ->
                                confirm("Replace " + from + " with " + to + scopeText(tables) + "?",
                                        new LootPayloads.BulkItemEdit(from, to, tables))))))));
    }

    private void bulkRemove() {
        assert minecraft != null;
        minecraft.setScreen(new ItemSelectScreen(this,
                Component.literal("Remove which item (or #tag) from loot tables?"), true, true, item ->
                minecraft.setScreen(new TableScopeScreen(this, tables ->
                        confirm("Remove " + item + scopeText(tables) + "?",
                                new LootPayloads.BulkItemEdit(item, "", tables))))));
    }

    private static String scopeText(String tables) {
        return tables.equals("*") ? " in every loot table" : " in tables matching '" + tables + "'";
    }

    private void confirm(String question, LootPayloads.BulkItemEdit payload) {
        assert minecraft != null;
        minecraft.setScreen(new ConfirmScreen(yes -> {
            if (yes) {
                ClientPlayNetworking.send(payload);
                itemFilter = "";
                requestList();
            }
            minecraft.setScreen(this);
        },
                Component.literal("Bulk loot table edit"),
                Component.literal(question + "\n\nThis is saved as a rule in "
                        + "config/datarewriter/gui-loot-tables.json5 and applies immediately. "
                        + "Delete the rule there to undo it.")));
    }

    private void refresh() {
        String q = query.trim().toLowerCase(Locale.ROOT);
        filtered = q.isEmpty() ? ids : ids.stream()
                .filter(id -> id.toLowerCase(Locale.ROOT).contains(q))
                .toList();
        scrollRow = 0;
    }

    private void open(String tableId) {
        assert minecraft != null;
        if (onSelect != null) {
            Screen before = minecraft.screen;
            onSelect.accept(tableId);
            if (minecraft.screen == before) {
                minecraft.setScreen(returnTo);
            }
            return;
        }
        minecraft.setScreen(new LootTableEditorScreen(this, tableId));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);

        int left = listLeft();
        int top = listTop();
        int rows = visibleRows();
        graphics.fill(left - 4, top - 4, left + LIST_WIDTH + 4, top + rows * ROW_HEIGHT + 4, 0x88000000);

        for (int row = 0; row < rows; row++) {
            int index = scrollRow + row;
            if (index >= filtered.size()) {
                break;
            }
            int y = top + row * ROW_HEIGHT;
            boolean hover = mouseX >= left && mouseX < left + LIST_WIDTH
                    && mouseY >= y && mouseY < y + ROW_HEIGHT;
            if (hover) {
                graphics.fill(left - 2, y, left + LIST_WIDTH + 2, y + ROW_HEIGHT, 0x66FFFFFF);
            }
            graphics.drawString(font, filtered.get(index), left, y + 2, 0xFFFFFF);
        }

        String footer;
        if (loading) {
            footer = "asking the server for the table list…";
        } else if (filtered.isEmpty()) {
            footer = validNewId() != null
                    ? "no matches — press Enter to create '" + validNewId() + "'"
                    : itemFilter.isEmpty() ? "no matches" : "no tables drop this item";
        } else {
            footer = filtered.size() + " tables"
                    + (filtered.size() > rows ? " — scroll for more" : "")
                    + (onSelect != null ? " — click one to use it" : " — click one to edit it");
        }
        graphics.drawCenteredString(font, Component.literal(footer).withStyle(ChatFormatting.GRAY),
                width / 2, top + rows * ROW_HEIGHT + 10, 0xA0A0A0);
    }

    /** The search text as a new-table id, if it is a valid unknown id. */
    private String validNewId() {
        String text = query.trim();
        if (text.isEmpty() || !text.contains(":")) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(text);
        return id != null && !ids.contains(id.toString()) ? id.toString() : null;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int maxScroll = Math.max(0, filtered.size() - visibleRows());
        scrollRow = Math.max(0, Math.min(maxScroll, scrollRow - (int) Math.signum(scrollY)));
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        int left = listLeft();
        int top = listTop();
        if (button == 0 && mouseX >= left && mouseX < left + LIST_WIDTH
                && mouseY >= top && mouseY < top + visibleRows() * ROW_HEIGHT) {
            int index = scrollRow + (int) ((mouseY - top) / ROW_HEIGHT);
            if (index < filtered.size()) {
                open(filtered.get(index));
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) { // enter
            if (!filtered.isEmpty()) {
                open(filtered.getFirst());
                return true;
            }
            String newId = validNewId();
            if (newId != null) {
                open(newId);
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        assert minecraft != null;
        minecraft.setScreen(onSelect != null ? returnTo : null);
    }
}
