package net.ledok.datarewriter.client.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Searchable item (or fluid) grid; clicking an entry hands its id to the
 * editor. Typing a query starting with '#' searches item tags instead
 * (when the target slot accepts tags).
 */
public class ItemPickerScreen extends Screen {
    private static final int COLS = 9;
    private static final int ROWS = 6;
    private static final int CELL = 18;

    private final Screen parent;
    private final boolean fluids;
    private final boolean allowTags;
    private final Consumer<String> onPick;

    // Remembered per item/fluid mode so closing and reopening the picker
    // lands on the same search and scroll position (recipe editor pattern).
    private static final Map<Boolean, String> LAST_QUERY = new HashMap<>();
    private static final Map<Boolean, Integer> LAST_SCROLL = new HashMap<>();

    private EditBox searchBox;
    private String query;
    private List<Entry> entries = List.of();
    private int scrollRow;
    private boolean restoreScroll = true;

    private record Entry(String ref, Component name) {
    }

    public ItemPickerScreen(Screen parent, boolean fluids, boolean allowTags, Consumer<String> onPick) {
        this(parent, fluids, allowTags,
                Component.literal(fluids ? "Choose a fluid" : "Choose an item"), onPick);
    }

    public ItemPickerScreen(Screen parent, boolean fluids, boolean allowTags,
                            Component title, Consumer<String> onPick) {
        super(title);
        this.parent = parent;
        this.fluids = fluids;
        this.allowTags = allowTags;
        this.onPick = onPick;
        this.query = LAST_QUERY.getOrDefault(fluids, "");
    }

    private int gridLeft() {
        return (width - COLS * CELL) / 2;
    }

    private int gridTop() {
        return 58;
    }

    @Override
    protected void init() {
        searchBox = new EditBox(font, gridLeft(), 30, COLS * CELL, 18, Component.literal("Search"));
        searchBox.setMaxLength(128);
        searchBox.setHint(Component.literal(allowTags ? "search — #… for tags" : "search")
                .withStyle(ChatFormatting.DARK_GRAY));
        searchBox.setTooltip(Tooltip.create(Component.literal(
                "All space-separated words must match; @mod filters by namespace "
                        + "— e.g. '@minecraft sword'")));
        searchBox.setValue(query);
        searchBox.setResponder(text -> {
            query = text;
            refresh();
        });
        addRenderableWidget(searchBox);
        setInitialFocus(searchBox);

        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(gridLeft() + (COLS * CELL - 80) / 2, gridTop() + ROWS * CELL + 10, 80, 20)
                .build());
        refresh();
    }

    private void refresh() {
        String q = query.trim();
        List<Entry> found = new ArrayList<>();
        if (q.startsWith("#") && allowTags) {
            String tagQuery = q.substring(1);
            (fluids ? BuiltInRegistries.FLUID.getTagNames() : BuiltInRegistries.ITEM.getTagNames())
                    .map(tag -> tag.location().toString())
                    .filter(id -> SearchQuery.matches(tagQuery, id))
                    .sorted()
                    .forEach(id -> found.add(new Entry("#" + id, Component.literal("#" + id))));
        } else if (fluids) {
            BuiltInRegistries.FLUID.entrySet().stream()
                    .sorted(Comparator.comparing(e -> e.getKey().location()))
                    .forEach(e -> {
                        Fluid fluid = e.getValue();
                        if (!fluid.isSource(fluid.defaultFluidState())) {
                            return;
                        }
                        String id = e.getKey().location().toString();
                        Component name = fluid.getBucket() == Items.AIR
                                ? Component.literal(id) : fluid.getBucket().getDescription();
                        if (q.isEmpty() || SearchQuery.matches(q, id, name.getString())) {
                            found.add(new Entry(id, name));
                        }
                    });
        } else {
            BuiltInRegistries.ITEM.entrySet().stream()
                    .sorted(Comparator.comparing(e -> e.getKey().location()))
                    .forEach(e -> {
                        if (e.getValue() == Items.AIR) {
                            return;
                        }
                        String id = e.getKey().location().toString();
                        Component name = e.getValue().getDescription();
                        if (q.isEmpty() || SearchQuery.matches(q, id, name.getString())) {
                            found.add(new Entry(id, name));
                        }
                    });
        }
        entries = found;
        int totalRows = (entries.size() + COLS - 1) / COLS;
        scrollRow = restoreScroll
                ? Math.max(0, Math.min(LAST_SCROLL.getOrDefault(fluids, 0), totalRows - ROWS)) : 0;
        restoreScroll = false;
    }

    @Override
    public void removed() {
        LAST_QUERY.put(fluids, query);
        LAST_SCROLL.put(fluids, scrollRow);
        super.removed();
    }

    private void pick(String ref) {
        assert minecraft != null;
        Screen before = minecraft.screen;
        onPick.accept(ref);
        // Return to the parent unless the callback already opened another
        // screen (e.g. a second picker in a chained flow).
        if (minecraft.screen == before) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);

        int left = gridLeft();
        int top = gridTop();
        graphics.fill(left - 2, top - 2, left + COLS * CELL + 2, top + ROWS * CELL + 2, 0x88000000);

        Entry hovered = null;
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int index = (scrollRow + row) * COLS + col;
                if (index >= entries.size()) {
                    break;
                }
                Entry entry = entries.get(index);
                int x = left + col * CELL + 1;
                int y = top + row * CELL + 1;
                boolean hover = mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16;
                if (hover) {
                    hovered = entry;
                    graphics.fill(x - 1, y - 1, x + 17, y + 17, 0x66FFFFFF);
                }
                ItemStack icon = RecipeEditorScreen.iconFor(entry.ref(), fluids);
                graphics.renderItem(icon, x, y);
                if (entry.ref().startsWith("#")) {
                    graphics.renderItemDecorations(font, icon, x, y, "#");
                }
            }
        }

        int totalRows = (entries.size() + COLS - 1) / COLS;
        String footer = entries.isEmpty() ? "no matches"
                : entries.size() + " matches" + (totalRows > ROWS ? " — scroll for more" : "");
        graphics.drawCenteredString(font, Component.literal(footer).withStyle(ChatFormatting.GRAY),
                width / 2, top + ROWS * CELL + 36, 0xA0A0A0);

        if (hovered != null) {
            graphics.renderComponentTooltip(font, List.of(
                    hovered.name(),
                    Component.literal(hovered.ref()).withStyle(ChatFormatting.DARK_GRAY)),
                    mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int totalRows = (entries.size() + COLS - 1) / COLS;
        int maxScroll = Math.max(0, totalRows - ROWS);
        scrollRow = Math.max(0, Math.min(maxScroll, scrollRow - (int) Math.signum(scrollY)));
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        int left = gridLeft();
        int top = gridTop();
        if (button == 0 && mouseX >= left && mouseX < left + COLS * CELL
                && mouseY >= top && mouseY < top + ROWS * CELL) {
            int col = (int) ((mouseX - left) / CELL);
            int row = (int) ((mouseY - top) / CELL);
            int index = (scrollRow + row) * COLS + col;
            if (index < entries.size()) {
                pick(entries.get(index).ref());
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) { // enter / numpad enter
            String text = query.trim();
            if (text.startsWith("#") && allowTags
                    && ResourceLocation.tryParse(text.substring(1)) != null) {
                // Accept a tag id verbatim — useful for tags that only exist server-side.
                pick(text.startsWith("#") && !text.contains(":")
                        ? "#minecraft:" + text.substring(1) : text);
                return true;
            }
            if (!entries.isEmpty()) {
                pick(entries.get(0).ref());
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        assert minecraft != null;
        minecraft.setScreen(parent);
    }
}
