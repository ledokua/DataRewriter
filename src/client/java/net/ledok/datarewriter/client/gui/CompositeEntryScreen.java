package net.ledok.datarewriter.client.gui;

import com.google.gson.JsonObject;
import net.ledok.datarewriter.client.gui.LootTableEditorScreen.EntryState;
import net.ledok.datarewriter.client.gui.LootTableEditorScreen.Kind;
import net.ledok.datarewriter.menu.LootEditorMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
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

/**
 * Sub-editor for one composite loot entry (alternatives / group / sequence):
 * its children rendered as the same interactive slots the pool editor uses —
 * including nested composites, which open another level of this screen. All
 * edits mutate the shared EntryState tree; the table editor serializes it on
 * save. A container screen so EMI can drag&drop into it too.
 */
public class CompositeEntryScreen extends AbstractContainerScreen<LootEditorMenu> {
    private static final int COLS = 9;
    private static final int PANEL_W = COLS * 18 + 12;

    private final Screen parent;
    private final EntryState composite;
    /** Bubbles "something changed" up to the table editor (marks the pool dirty). */
    private final Runnable onEdited;

    private int panelLeft;
    private int panelTop;
    private int panelHeight;
    private int uiTop;
    private int uiBottom;

    public CompositeEntryScreen(Screen parent, EntryState composite, Runnable onEdited) {
        super(new LootEditorMenu(0, Minecraft.getInstance().player.getInventory()),
                Minecraft.getInstance().player.getInventory(),
                Component.literal(LootTableEditorScreen.compositeLabel(composite) + " entry"));
        this.parent = parent;
        this.composite = composite;
        this.onEdited = onEdited;
    }

    @Override
    protected void init() {
        super.init();
        panelLeft = (width - PANEL_W) / 2;
        int rows = slotRows();
        panelHeight = rows * 18 + 12;
        uiTop = Math.max(6, (height - (40 + panelHeight + 34)) / 2);
        panelTop = uiTop + 40;

        int y = panelTop + panelHeight + 8;
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose())
                .bounds((width - 80) / 2, y, 80, 20)
                .build());
        uiBottom = y + 26;

        leftPos = panelLeft - 4;
        topPos = uiTop;
        imageWidth = PANEL_W + 8;
        imageHeight = uiBottom - uiTop;
    }

    private int slotRows() {
        return (composite.children.size() + 1 + COLS - 1) / COLS; // +1 add-slot
    }

    private int slotX(int index) {
        return panelLeft + 6 + (index % COLS) * 18;
    }

    private int slotY(int index) {
        return panelTop + 6 + (index / COLS) * 18;
    }

    /** Child index under the mouse; size() = the add-slot, -1 = none. */
    private int slotAt(double mouseX, double mouseY) {
        for (int i = 0; i <= composite.children.size(); i++) {
            int x = slotX(i);
            int y = slotY(i);
            if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
                return i;
            }
        }
        return -1;
    }

    private void edited() {
        onEdited.run();
    }

    // --- rendering --------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, uiTop, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.literal(
                        "Here " + LootTableEditorScreen.compositeHelp(composite) + ".")
                .withStyle(ChatFormatting.GRAY), width / 2, uiTop + 14, 0xA0A0A0);

        int right = panelLeft + PANEL_W;
        int bottom = panelTop + panelHeight;
        graphics.fill(panelLeft - 1, panelTop - 1, right + 1, bottom + 1, 0xFF000000);
        graphics.fill(panelLeft, panelTop, right, bottom, 0xFFC6C6C6);
        graphics.fill(panelLeft, panelTop, right, panelTop + 1, 0xFFFFFFFF);
        graphics.fill(panelLeft, panelTop, panelLeft + 1, bottom, 0xFFFFFFFF);
        graphics.fill(panelLeft, bottom - 1, right, bottom, 0xFF555555);
        graphics.fill(right - 1, panelTop, right, bottom, 0xFF555555);

        List<Component> tooltip = null;
        for (int i = 0; i <= composite.children.size(); i++) {
            int x = slotX(i);
            int y = slotY(i);
            graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFFFFFFFF);
            graphics.fill(x - 1, y - 1, x + 16, y + 16, 0xFF373737);
            graphics.fill(x, y, x + 16, y + 16, 0xFF8B8B8B);
            boolean hover = mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16;
            if (i == composite.children.size()) {
                graphics.drawCenteredString(font, "+", x + 8, y + 4, hover ? 0xFFFFFF : 0xD0FFD0);
                if (hover) {
                    tooltip = List.of(
                            Component.literal("Add an entry"),
                            Component.literal("Click to choose an item — or drop one from EMI")
                                    .withStyle(ChatFormatting.GRAY),
                            Component.literal("Right-click: an 'empty' entry — "
                                    + "middle-click: a special entry").withStyle(ChatFormatting.GRAY));
                }
                continue;
            }
            EntryState child = composite.children.get(i);
            LootTableEditorScreen.renderEntry(graphics, font, child, x, y);
            if (hover) {
                graphics.fill(x, y, x + 16, y + 16, 0x66FFFFFF);
                tooltip = childTooltip(child);
            }
        }

        if (tooltip != null) {
            graphics.renderComponentTooltip(font, tooltip, mouseX, mouseY);
        }
    }

    private List<Component> childTooltip(EntryState child) {
        List<Component> lines = new ArrayList<>();
        switch (child.kind) {
            case ITEM -> {
                lines.add(LootTableEditorScreen.entryIcon(child).getHoverName());
                lines.add(Component.literal(child.ref).withStyle(ChatFormatting.DARK_GRAY));
                if (child.fancy) {
                    lines.add(Component.literal("Drops with extra data (components/enchantments) "
                            + "— shown like in game, kept exactly as-is on save.")
                            .withStyle(ChatFormatting.GRAY));
                }
            }
            case TAG -> {
                lines.add(Component.literal(child.ref).withStyle(ChatFormatting.GOLD));
                lines.add(Component.literal("An item TAG — each roll drops one matching item "
                        + "(the icon cycles through them).").withStyle(ChatFormatting.GRAY));
            }
            case EMPTY -> lines.add(Component.literal("Nothing — a chance to get no drop. "
                    + "Click to turn it into an item."));
            case COMPOSITE -> {
                lines.add(Component.literal(LootTableEditorScreen.compositeLabel(child) + " — "
                        + child.children.size() + " entr" + (child.children.size() == 1 ? "y" : "ies")));
                lines.add(Component.literal("Click to edit its entries").withStyle(ChatFormatting.GRAY));
            }
            case TABLE_REF -> {
                lines.add(Component.literal("Drops from another loot table:"));
                lines.add(Component.literal(child.ref).withStyle(ChatFormatting.DARK_GRAY));
                lines.add(Component.literal("Click to pick a different table")
                        .withStyle(ChatFormatting.GRAY));
            }
            case COMPLEX -> lines.add(Component.literal(
                    "Complex entry — preserved as-is (edit in the config)"));
        }
        lines.add(Component.literal("Scroll: weight — Shift: count — Ctrl: count max — Alt: chance")
                .withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Middle-click: exact weight — right-click: delete")
                .withStyle(ChatFormatting.GRAY));
        return lines;
    }

    // --- input ------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int index = slotAt(mouseX, mouseY);
        if (index < 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        assert minecraft != null;
        boolean addSlot = index == composite.children.size();
        if (button == 1) {
            if (addSlot) {
                composite.children.add(LootTableEditorScreen.newEmptyEntry());
            } else {
                composite.children.remove(index);
            }
            edited();
            rebuildWidgets();
            return true;
        }
        if (button == 2) {
            if (addSlot) {
                minecraft.setScreen(new SpecialEntryScreen(this, entry -> {
                    composite.children.add(entry);
                    edited();
                    rebuildWidgets();
                    if (entry.kind == Kind.COMPOSITE) {
                        assert minecraft != null;
                        minecraft.setScreen(new CompositeEntryScreen(this, entry, onEdited));
                    }
                }));
                return true;
            }
            EntryState child = composite.children.get(index);
            if (child.kind == Kind.ITEM) {
                minecraft.setScreen(new AmountInputScreen(this, Component.literal("Weight"),
                        child.weight, 1, 1000, weight -> {
                    child.weight = weight;
                    edited();
                }, Component.literal("Convert to #tag…"), () ->
                        minecraft.setScreen(new ItemTagListScreen(this,
                                RecipeEditorScreen.iconFor(child.ref, false).getItem(), tag -> {
                            child.kind = Kind.TAG;
                            child.ref = tag;
                            edited();
                        }))));
            } else {
                minecraft.setScreen(new AmountInputScreen(this, Component.literal("Weight"),
                        child.weight, 1, 1000, weight -> {
                    child.weight = weight;
                    edited();
                }));
            }
            return true;
        }
        if (button == 0) {
            if (addSlot || LootTableEditorScreen.isItemPlaceable(composite.children.get(index))) {
                final int childIndex = index;
                minecraft.setScreen(new ItemPickerScreen(this, false, true,
                        ref -> place(childIndex, ref)));
            } else if (composite.children.get(index).kind == Kind.COMPOSITE) {
                minecraft.setScreen(new CompositeEntryScreen(this,
                        composite.children.get(index), onEdited));
            } else if (composite.children.get(index).kind == Kind.TABLE_REF) {
                EntryState child = composite.children.get(index);
                minecraft.setScreen(new LootTablePickerScreen(this, tableRef -> {
                    child.ref = tableRef;
                    edited();
                }));
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** Sets a child's item, or appends a new child when index == size. */
    private void place(int index, String ref) {
        if (ref == null || ref.isEmpty()) {
            return;
        }
        EntryState child;
        if (index >= composite.children.size()) {
            child = new EntryState();
            child.source = new JsonObject();
            composite.children.add(child);
            rebuildWidgets();
        } else {
            child = composite.children.get(index);
            if (!LootTableEditorScreen.isItemPlaceable(child)) {
                return;
            }
        }
        child.kind = ref.startsWith("#") ? Kind.TAG : Kind.ITEM;
        child.ref = ref;
        edited();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int index = slotAt(mouseX, mouseY);
        if (index < 0 || index >= composite.children.size()) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        int direction = (int) Math.signum(scrollY);
        EntryState child = composite.children.get(index);
        boolean itemLike = child.kind == Kind.ITEM || child.kind == Kind.TAG;
        if (hasAltDown()) {
            if (itemLike && !child.chanceLocked) {
                child.chance = Math.max(1, Math.min(100, child.chance + direction * 5));
                edited();
            }
        } else if (hasControlDown()) {
            if (itemLike && !child.countLocked) {
                child.countMax = Math.max(child.countMin, Math.min(99, child.countMax + direction));
                edited();
            }
        } else if (hasShiftDown()) {
            if (itemLike && !child.countLocked) {
                int count = Math.max(1, Math.min(99, child.countMin + direction));
                child.countMin = count;
                child.countMax = count;
                edited();
            }
        } else {
            child.weight = Math.max(1, Math.min(1000, child.weight + direction));
            edited();
        }
        return true;
    }

    // --- EMI hooks --------------------------------------------------------

    /** EMI drag & drop: set a child, or append when dropped on the add-slot. */
    public boolean emiDrop(int x, int y, Object key) {
        if (!(key instanceof Item item)) {
            return false;
        }
        int index = slotAt(x, y);
        if (index < 0) {
            return false;
        }
        if (index < composite.children.size()
                && !LootTableEditorScreen.isItemPlaceable(composite.children.get(index))) {
            return false;
        }
        place(index, BuiltInRegistries.ITEM.getKey(item).toString());
        return true;
    }

    /** EMI drag & drop: highlight every slot the item could land in. */
    public void renderEmiDropTargets(GuiGraphics graphics, Object key) {
        if (!(key instanceof Item)) {
            return;
        }
        for (int i = 0; i <= composite.children.size(); i++) {
            if (i < composite.children.size()
                    && !LootTableEditorScreen.isItemPlaceable(composite.children.get(i))) {
                continue;
            }
            graphics.fill(slotX(i), slotY(i), slotX(i) + 16, slotY(i) + 16, 0x8833BB33);
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
