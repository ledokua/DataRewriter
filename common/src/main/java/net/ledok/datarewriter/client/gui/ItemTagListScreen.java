package net.ledok.datarewriter.client.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Searchable list of every tag one item belongs to; clicking a row hands
 * "#namespace:path" to the callback. Used by the loot editor's
 * "Convert to tag…" action.
 */
public class ItemTagListScreen extends Screen {
    private static final int ROW_HEIGHT = 13;
    private static final int LIST_WIDTH = 260;

    private final Screen parent;
    private final Consumer<String> onPick;
    private final List<String> tags;

    private String query = "";
    private List<String> filtered;
    private int scrollRow;

    public ItemTagListScreen(Screen parent, Item item, Consumer<String> onPick) {
        super(Component.empty().append(new ItemStack(item).getHoverName())
                .append(Component.literal(" is in these tags:")));
        this.parent = parent;
        this.onPick = onPick;
        this.tags = BuiltInRegistries.ITEM.wrapAsHolder(item).tags()
                .map(tag -> "#" + tag.location())
                .sorted()
                .toList();
        this.filtered = tags;
    }

    private int listLeft() {
        return (width - LIST_WIDTH) / 2;
    }

    private int listTop() {
        return 56;
    }

    private int visibleRows() {
        return Math.max(1, (height - listTop() - 60) / ROW_HEIGHT);
    }

    @Override
    protected void init() {
        EditBox searchBox = new EditBox(font, listLeft(), 30, LIST_WIDTH, 18, Component.literal("Search"));
        searchBox.setMaxLength(128);
        searchBox.setHint(Component.literal("search").withStyle(ChatFormatting.DARK_GRAY));
        searchBox.setValue(query);
        searchBox.setResponder(text -> {
            query = text;
            String q = query.trim().toLowerCase(Locale.ROOT);
            filtered = q.isEmpty() ? tags : tags.stream()
                    .filter(tag -> tag.toLowerCase(Locale.ROOT).contains(q))
                    .toList();
            scrollRow = 0;
        });
        addRenderableWidget(searchBox);
        setInitialFocus(searchBox);

        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds((width - 80) / 2, height - 28, 80, 20)
                .build());
    }

    private void pick(String tag) {
        assert minecraft != null;
        Screen before = minecraft.screen;
        onPick.accept(tag);
        if (minecraft.screen == before) {
            minecraft.setScreen(parent);
        }
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

        String footer = tags.isEmpty() ? "this item is in no tags"
                : filtered.isEmpty() ? "no matches"
                : filtered.size() + " tags" + (filtered.size() > rows ? " — scroll for more" : "")
                        + " — click one to use it";
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
    public void onClose() {
        assert minecraft != null;
        minecraft.setScreen(parent);
    }
}
