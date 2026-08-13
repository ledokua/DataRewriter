package net.ledok.datarewriter.client.gui;

import net.ledok.datarewriter.menu.LootEditorMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * "Pick one item" step used by the loot table picker's filter and bulk
 * actions: click an item in your inventory (right-click picks one of its
 * #tags instead), drag one in from EMI, or fall back to the full searchable
 * item list. A container screen so EMI shows its panel next to it (the menu
 * is client-only, nothing is moved or consumed).
 */
public class ItemSelectScreen extends AbstractContainerScreen<LootEditorMenu> {
    private static final int COLS = 9;
    private static final int ROWS = 4;

    private final Screen parent;
    private final boolean allowTags;
    private final Consumer<String> onPick;

    private int gridLeft;
    private int gridTop;
    private int uiTop;
    private int uiBottom;

    public ItemSelectScreen(Screen parent, Component prompt, Consumer<String> onPick) {
        this(parent, prompt, false, onPick);
    }

    public ItemSelectScreen(Screen parent, Component prompt, boolean allowTags,
                            Consumer<String> onPick) {
        super(new LootEditorMenu(0, Minecraft.getInstance().player.getInventory()),
                Minecraft.getInstance().player.getInventory(), prompt);
        this.parent = parent;
        this.allowTags = allowTags;
        this.onPick = onPick;
    }

    @Override
    protected void init() {
        super.init();
        int gridW = COLS * 18;
        gridLeft = (width - gridW) / 2;
        uiTop = Math.max(6, (height - 170) / 2);
        gridTop = uiTop + 40;

        int y = gridTop + ROWS * 18 + 18;
        addRenderableWidget(Button.builder(Component.literal("Search all items…"), b -> {
                    assert minecraft != null;
                    minecraft.setScreen(new ItemPickerScreen(parent, false, allowTags, title, this::deliver));
                })
                .bounds(gridLeft, y, gridW / 2 - 2, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "Browse every item in the game, with search")))
                .build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(gridLeft + gridW / 2 + 2, y, gridW / 2 - 2, 20)
                .build());
        uiBottom = y + 26;

        leftPos = gridLeft - 8;
        topPos = uiTop;
        imageWidth = gridW + 16;
        imageHeight = uiBottom - uiTop;
    }

    private void deliver(String ref) {
        assert minecraft != null;
        Screen before = minecraft.screen;
        onPick.accept(ref);
        // Return to the parent unless the callback already opened the next
        // screen of a chained flow.
        if (minecraft.screen == before) {
            minecraft.setScreen(parent);
        }
    }

    // --- rendering --------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, uiTop, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.literal(allowTags
                        ? "Click an item (right-click: its #tags) — or drag one in from EMI"
                        : "Click an item — or drag one in from EMI")
                .withStyle(ChatFormatting.GRAY), width / 2, uiTop + 14, 0xA0A0A0);

        assert minecraft != null && minecraft.player != null;
        List<ItemStack> items = minecraft.player.getInventory().items;
        ItemStack hovered = ItemStack.EMPTY;
        for (int i = 0; i < items.size() && i < COLS * ROWS; i++) {
            int x = gridLeft + (i % COLS) * 18 + 1;
            int y = gridTop + (i / COLS) * 18 + 1;
            graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFFFFFFFF);
            graphics.fill(x - 1, y - 1, x + 16, y + 16, 0xFF373737);
            graphics.fill(x, y, x + 16, y + 16, 0xFF8B8B8B);
            ItemStack stack = items.get(i);
            if (!stack.isEmpty()) {
                graphics.renderItem(stack, x, y);
                graphics.renderItemDecorations(font, stack, x, y);
            }
            if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
                graphics.fill(x, y, x + 16, y + 16, 0x66FFFFFF);
                hovered = stack;
            }
        }

        if (!hovered.isEmpty()) {
            List<Component> lines = new ArrayList<>();
            lines.add(hovered.getHoverName());
            lines.add(Component.literal(BuiltInRegistries.ITEM.getKey(hovered.getItem()).toString())
                    .withStyle(ChatFormatting.DARK_GRAY));
            lines.add(Component.literal("Click to choose this item").withStyle(ChatFormatting.GRAY));
            if (allowTags) {
                lines.add(Component.literal("Right-click: choose one of its #tags instead")
                        .withStyle(ChatFormatting.GRAY));
            }
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
        }
    }

    // --- input ------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if ((button == 0 || button == 1) && mouseX >= gridLeft && mouseX < gridLeft + COLS * 18
                && mouseY >= gridTop && mouseY < gridTop + ROWS * 18) {
            int col = (int) ((mouseX - gridLeft) / 18);
            int row = (int) ((mouseY - gridTop) / 18);
            int index = row * COLS + col;
            assert minecraft != null && minecraft.player != null;
            List<ItemStack> items = minecraft.player.getInventory().items;
            if (index < items.size() && !items.get(index).isEmpty()) {
                if (button == 0) {
                    deliver(BuiltInRegistries.ITEM.getKey(items.get(index).getItem()).toString());
                } else if (allowTags) {
                    // Right-click: pick one of this item's tags instead —
                    // no typing tag ids by hand.
                    minecraft.setScreen(new ItemTagListScreen(this,
                            items.get(index).getItem(), this::deliver));
                }
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    // --- EMI hooks --------------------------------------------------------

    /** EMI drag & drop: releasing an item anywhere over the window picks it. */
    public boolean emiDrop(int x, int y, Object key) {
        if (!(key instanceof Item item)) {
            return false;
        }
        deliver(BuiltInRegistries.ITEM.getKey(item).toString());
        return true;
    }

    /** EMI drag & drop: the whole window is one drop target. */
    public void renderEmiDropTargets(GuiGraphics graphics, Object key) {
        if (key instanceof Item) {
            graphics.fill(leftPos, uiTop + 26, leftPos + imageWidth, uiBottom, 0x4433BB33);
        }
    }

    /** The UI as {x, y, width, height} — EMI keeps its panels outside this. */
    public int[] emiScreenBounds() {
        return new int[]{leftPos, uiTop, imageWidth, uiBottom - uiTop};
    }

    // --- container-screen plumbing neutralized (menu is client-only) --------

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    }

    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType type) {
    }

    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int guiLeft, int guiTop, int mouseButton) {
        return false;
    }

    @Override
    public void onClose() {
        assert minecraft != null;
        minecraft.setScreen(parent);
    }
}
