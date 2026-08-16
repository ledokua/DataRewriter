package net.ledok.datarewriter.client.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/** Searchable list of every recipe type the editor knows a layout for. */
public class TypePickerScreen extends Screen {
    private static final int ROW_HEIGHT = 13;
    private static final int LIST_WIDTH = 280;

    private final Screen parent;
    private final List<EditorLayout> layouts;
    private final Consumer<EditorLayout> onPick;

    private static String lastQuery = "";
    private static int lastScroll;

    private String query = lastQuery;
    private List<EditorLayout> filtered;
    private int scrollRow;
    private boolean restoreScroll = true;

    public TypePickerScreen(Screen parent, List<EditorLayout> layouts, Consumer<EditorLayout> onPick) {
        super(Component.literal("Choose a recipe type"));
        this.parent = parent;
        this.layouts = layouts;
        this.filtered = layouts;
        this.onPick = onPick;
    }

    private int listLeft() {
        return (width - LIST_WIDTH) / 2;
    }

    private int listTop() {
        return 56;
    }

    private int visibleRows() {
        return Math.max(1, (height - listTop() - 36) / ROW_HEIGHT);
    }

    @Override
    protected void init() {
        EditBox searchBox = new EditBox(font, listLeft(), 30, LIST_WIDTH, 18, Component.literal("Search"));
        searchBox.setMaxLength(128);
        searchBox.setHint(Component.literal("search by name or id").withStyle(ChatFormatting.DARK_GRAY));
        searchBox.setTooltip(Tooltip.create(Component.literal(
                "All space-separated words must match; @mod filters by namespace "
                        + "— e.g. '@create mixing'")));
        searchBox.setValue(query);
        searchBox.setResponder(text -> {
            query = text;
            refresh();
        });
        addRenderableWidget(searchBox);
        setInitialFocus(searchBox);

        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds((width - 80) / 2, height - 28, 80, 20)
                .build());
        refresh();
    }

    private void refresh() {
        String q = query.trim();
        filtered = q.isEmpty() ? layouts : layouts.stream()
                .filter(l -> SearchQuery.matches(q, l.typeId, l.displayName))
                .toList();
        scrollRow = restoreScroll ? Math.max(0, Math.min(lastScroll, filtered.size() - visibleRows())) : 0;
        restoreScroll = false;
    }

    @Override
    public void removed() {
        lastQuery = query;
        lastScroll = scrollRow;
        super.removed();
    }

    private void pick(EditorLayout layout) {
        onPick.accept(layout);
        assert minecraft != null;
        minecraft.setScreen(parent);
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
            EditorLayout layout = filtered.get(index);
            int y = top + row * ROW_HEIGHT;
            boolean hover = mouseX >= left && mouseX < left + LIST_WIDTH
                    && mouseY >= y && mouseY < y + ROW_HEIGHT;
            if (hover) {
                graphics.fill(left - 2, y, left + LIST_WIDTH + 2, y + ROW_HEIGHT, 0x66FFFFFF);
            }
            Component line = Component.literal(layout.displayName)
                    .append(Component.literal("  " + layout.typeId).withStyle(ChatFormatting.DARK_GRAY));
            graphics.drawString(font, line, left, y + 2, 0xFFFFFF);
        }

        int totalRows = filtered.size();
        String footer = totalRows == 0 ? "no matches"
                : totalRows + " types" + (totalRows > rows ? " — scroll for more" : "");
        graphics.drawCenteredString(font, Component.literal(footer).withStyle(ChatFormatting.GRAY),
                width / 2, top + rows * ROW_HEIGHT + 10, 0xA0A0A0);
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
                pick(filtered.get(index));
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == 257 || keyCode == 335) && !filtered.isEmpty()) { // enter
            pick(filtered.get(0));
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        assert minecraft != null;
        minecraft.setScreen(parent);
    }
}
