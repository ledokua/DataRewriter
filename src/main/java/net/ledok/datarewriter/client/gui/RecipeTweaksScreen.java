package net.ledok.datarewriter.client.gui;

import net.ledok.datarewriter.platform.Network;
import net.ledok.datarewriter.menu.LootEditorMenu;
import net.ledok.datarewriter.network.BulkRecipeEditPayload;
import net.ledok.datarewriter.mixin.AbstractContainerScreenAccessor;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;

import java.util.List;

/**
 * Bulk recipe edits: pick an item (or #tag), pick what to do with it —
 * remove the recipes that make it, remove the recipes that use it, or
 * replace it as an ingredient everywhere. Saved as normal config rules on
 * the server, applied live.
 */
public class RecipeTweaksScreen extends AbstractContainerScreen<LootEditorMenu> {
    private static final String[] MODES = {"remove_output", "remove_input", "replace"};
    private static final String[] MODE_LABELS = {
            "Remove its recipes", "Remove recipes using it", "Replace it in recipes"};
    private static final String[] MODE_HELP = {
            "Every recipe that PRODUCES the item is removed (recipes you added with DataRewriter are kept).",
            "Every recipe with the item as an INGREDIENT is removed.",
            "The item is swapped for the second item (or #tag) in every recipe's ingredients — results are not touched."};

    // Remembered until the game quits, like the recipe editor's slots.
    private static int lastMode;
    private static String lastFrom = "";
    private static String lastTo = "";

    private final List<Button> modeButtons = new java.util.ArrayList<>();
    private Component status = Component.empty();

    /** Server's answer to the last apply (also in chat). */
    public void onSaveResult(boolean ok, String message) {
        status = Component.literal((ok ? "✔ " : "✘ ") + message)
                .withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    private int panelLeft;
    private int panelTop;
    private int slotRowY;
    private int uiBottom;

    public RecipeTweaksScreen() {
        super(new LootEditorMenu(0, Minecraft.getInstance().player.getInventory()),
                Minecraft.getInstance().player.getInventory(),
                Component.literal("Recipe Tweaks"));
    }

    private static boolean replaceMode() {
        return MODES[lastMode].equals("replace");
    }

    @Override
    protected void init() {
        super.init();
        int panelW = 220;
        panelLeft = (width - panelW) / 2;
        panelTop = Math.max(10, (height - 190) / 2);

        modeButtons.clear();
        int y = panelTop + 16;
        for (int i = 0; i < MODES.length; i++) {
            int mode = i;
            Button button = Button.builder(Component.literal(MODE_LABELS[i]), b -> {
                        lastMode = mode;
                        status = Component.empty();
                        updateModeButtons();
                    })
                    .bounds(panelLeft, y, panelW, 20)
                    .tooltip(Tooltip.create(Component.literal(MODE_HELP[i])))
                    .build();
            modeButtons.add(button);
            addRenderableWidget(button);
            y += 22;
        }
        updateModeButtons();

        slotRowY = y + 26;
        y = slotRowY + 34;
        addRenderableWidget(Button.builder(Component.literal("Apply…"), b -> confirm())
                .bounds(panelLeft, y, panelW / 2 - 2, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(panelLeft + panelW / 2 + 2, y, panelW / 2 - 2, 20)
                .build());
        uiBottom = y + 40;

        leftPos = panelLeft - 8;
        topPos = panelTop - 4;
        ((AbstractContainerScreenAccessor) this).datarewriter$setImageWidth(panelW + 16);
        ((AbstractContainerScreenAccessor) this).datarewriter$setImageHeight(uiBottom - panelTop + 8);
    }

    private void updateModeButtons() {
        for (int i = 0; i < modeButtons.size(); i++) {
            modeButtons.get(i).active = i != lastMode;
        }
    }

    // --- slots --------------------------------------------------------------

    /** {x, y} of a slot: 0 = from, 1 = to (replace mode only). */
    private int[] slotPos(int slot) {
        int centre = panelLeft + 110;
        if (!replaceMode()) {
            return slot == 0 ? new int[]{centre - 9, slotRowY} : null;
        }
        return new int[]{slot == 0 ? centre - 40 : centre + 22, slotRowY};
    }

    private int slotAt(double mouseX, double mouseY) {
        for (int slot = 0; slot <= 1; slot++) {
            int[] pos = slotPos(slot);
            if (pos != null && mouseX >= pos[0] && mouseX < pos[0] + 18
                    && mouseY >= pos[1] && mouseY < pos[1] + 18) {
                return slot;
            }
        }
        return -1;
    }

    private void setRef(int slot, String ref) {
        if (slot == 0) {
            lastFrom = ref;
        } else {
            lastTo = ref;
        }
        status = Component.empty();
    }

    private void pickFor(int slot) {
        assert minecraft != null;
        minecraft.setScreen(new ItemSelectScreen(this,
                Component.literal(slot == 0 ? "Which item (or #tag)?" : "Replace with which item (or #tag)?"),
                true, ref -> setRef(slot, ref)));
    }

    // --- actions ------------------------------------------------------------

    private void confirm() {
        assert minecraft != null;
        if (lastFrom.isEmpty() || (replaceMode() && lastTo.isEmpty())) {
            status = Component.literal("Pick the item slot" + (replaceMode() ? "s" : "") + " first.")
                    .withStyle(ChatFormatting.RED);
            return;
        }
        if (!Network.INSTANCE.canSendToServer(BulkRecipeEditPayload.TYPE)) {
            status = Component.literal("This server doesn't run DataRewriter — nothing to apply to.")
                    .withStyle(ChatFormatting.RED);
            return;
        }
        String question = switch (MODES[lastMode]) {
            case "remove_output" -> "Remove every recipe producing " + lastFrom + "?";
            case "remove_input" -> "Remove every recipe using " + lastFrom + " as an ingredient?";
            default -> "Replace ingredient " + lastFrom + " with " + lastTo + " in every recipe?";
        };
        RecipeTweaksScreen self = this;
        minecraft.setScreen(new ConfirmScreen(yes -> {
            if (yes) {
                Network.INSTANCE.sendToServer(new BulkRecipeEditPayload(MODES[lastMode], lastFrom, lastTo));
                self.status = Component.literal("Sent — the result lands in chat.")
                        .withStyle(ChatFormatting.GRAY);
            }
            minecraft.setScreen(self);
        },
                Component.literal("Bulk recipe edit"),
                Component.literal(question + "\n\nThis is saved as a rule in "
                        + "config/datarewriter/gui-recipes.json5 and applies immediately. "
                        + "Delete the rule there to undo it.")));
    }

    // --- rendering ----------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, panelTop, 0xFFFFFFFF);
        graphics.centeredText(font,
                Component.literal(MODE_HELP[lastMode]).withStyle(ChatFormatting.GRAY),
                width / 2, slotRowY - 18, 0xFFA0A0A0);

        int hoveredSlot = slotAt(mouseX, mouseY);
        for (int slot = 0; slot <= 1; slot++) {
            int[] pos = slotPos(slot);
            if (pos == null) {
                continue;
            }
            graphics.fill(pos[0] - 1, pos[1] - 1, pos[0] + 18, pos[1] + 18, 0xFFFFFFFF);
            graphics.fill(pos[0] - 1, pos[1] - 1, pos[0] + 17, pos[1] + 17, 0xFF373737);
            graphics.fill(pos[0], pos[1], pos[0] + 16, pos[1] + 16, 0xFF8B8B8B);
            String ref = slot == 0 ? lastFrom : lastTo;
            if (!ref.isEmpty()) {
                graphics.item(RecipeEditorScreen.iconFor(ref, false), pos[0], pos[1]);
                if (ref.startsWith("#")) {
                    graphics.pose().pushMatrix();
                    graphics.pose().translate(pos[0], pos[1]);
                    graphics.pose().scale(0.5f, 0.5f);
                    graphics.text(font, "#", 1, 23, 0xFFFFAA00, true);
                    graphics.pose().popMatrix();
                }
            }
            if (hoveredSlot == slot) {
                graphics.fill(pos[0], pos[1], pos[0] + 16, pos[1] + 16, 0x66FFFFFF);
            }
            String label = ref.isEmpty() ? (slot == 0 ? "item / #tag" : "item / #tag") : ref;
            graphics.pose().pushMatrix();
            graphics.pose().translate(pos[0] + 9 - font.width(label) / 4f, pos[1] + 20);
            graphics.pose().scale(0.5f, 0.5f);
            graphics.text(font, label, 0, 0, ref.isEmpty() ? 0xFF707070 : 0xFFC0C0C0, false);
            graphics.pose().popMatrix();
        }
        if (replaceMode()) {
            graphics.centeredText(font, "→", panelLeft + 110, slotRowY + 5, 0xFFFFFFFF);
        }

        if (!status.getString().isEmpty()) {
            List<net.minecraft.util.FormattedCharSequence> lines = font.split(status, Math.max(200, width - 40));
            for (int i = 0; i < Math.min(2, lines.size()); i++) {
                graphics.centeredText(font, lines.get(i), width / 2, uiBottom - 14 + i * 10, 0xFFFFFFFF);
            }
        }
        if (hoveredSlot >= 0) {
            graphics.setComponentTooltipForNextFrame(font, List.of(
                    Component.literal("Click: choose an item or #tag — or drop one from EMI"),
                    Component.literal("Right-click: clear").withStyle(ChatFormatting.GRAY)),
                    mouseX, mouseY);
        }
    }

    // --- input --------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
        int slot = slotAt(mouseX, mouseY);
        if (slot >= 0) {
            if (button == 1) {
                setRef(slot, "");
            } else {
                pickFor(slot);
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    // --- EMI hooks ----------------------------------------------------------

    /** EMI stack lookups (R/U): the from/to slot under the cursor. */
    public Object emiStackAt(int x, int y) {
        int slot = slotAt(x, y);
        if (slot < 0) {
            return null;
        }
        return EmiHover.itemOrTag(slot == 0 ? lastFrom : lastTo);
    }

    /** EMI drag & drop: a slot sets that slot, anywhere else sets 'from'. */
    public boolean emiDrop(int x, int y, Object key) {
        if (!(key instanceof Item item)) {
            return false;
        }
        int slot = slotAt(x, y);
        setRef(slot == 1 ? 1 : 0, BuiltInRegistries.ITEM.getKey(item).toString());
        return true;
    }

    public void renderEmiDropTargets(GuiGraphicsExtractor graphics, Object key) {
        if (!(key instanceof Item)) {
            return;
        }
        for (int slot = 0; slot <= 1; slot++) {
            int[] pos = slotPos(slot);
            if (pos != null) {
                graphics.fill(pos[0] - 1, pos[1] - 1, pos[0] + 17, pos[1] + 17, 0x4433BB33);
            }
        }
    }

    /** The UI as {x, y, width, height} — EMI keeps its panels outside this. */
    public int[] emiScreenBounds() {
        return new int[]{panelLeft - 8, panelTop - 4, 236, uiBottom - panelTop + 8};
    }

    // --- container-screen plumbing neutralized (menu is client-only) --------

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
    }

    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, ContainerInput type) {
    }

    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int guiLeft, int guiTop) {
        return false;
    }

    @Override
    public void onClose() {
        assert minecraft != null;
        minecraft.setScreen(null);
    }
}
