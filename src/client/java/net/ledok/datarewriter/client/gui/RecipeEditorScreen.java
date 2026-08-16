package net.ledok.datarewriter.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.datarewriter.network.SaveRecipePayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.ledok.datarewriter.menu.EditorMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.material.Fluid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static net.ledok.datarewriter.client.gui.EditorLayout.FieldDef;
import static net.ledok.datarewriter.client.gui.EditorLayout.FieldType;
import static net.ledok.datarewriter.client.gui.EditorLayout.Kind;
import static net.ledok.datarewriter.client.gui.EditorLayout.SlotDef;
import static net.ledok.datarewriter.client.gui.EditorLayout.SlotFormat;

/**
 * In-game recipe creator. Renders the target station's own GUI texture with
 * ghost slots; the finished recipe is sent to the server, which validates it
 * and appends it to a normal DataRewriter config file (op only).
 */
public class RecipeEditorScreen extends AbstractContainerScreen<EditorMenu> {
    private final List<EditorLayout> layouts;
    private EditorLayout layout;

    // Editor state is static and keyed by recipe type id, so closing and
    // reopening the editor restores the last type, slots, amounts and field
    // values (kept until the game quits).
    private static final Map<String, String[]> REFS_BY_TYPE = new HashMap<>();
    private static final Map<String, int[]> COUNTS_BY_TYPE = new HashMap<>();
    private static final Map<String, int[]> CHANCES_BY_TYPE = new HashMap<>();
    private static final Map<String, Map<String, String>> FIELDS_BY_TYPE = new HashMap<>();
    private static String lastTypeId = "";

    private String idText = "";
    private Component status = Component.empty();

    private int panelLeft;
    private int panelTop;
    private int statusY;
    private final List<FieldLabel> fieldLabels = new ArrayList<>();

    // Player inventory panel (left side): click or drag an item into a slot.
    private static final int INV_COLS = 4;
    private static final int INV_ROWS = 9;
    private int invLeft;
    private int invTop;
    /** Item picked up from the inventory panel, rendered on the cursor. */
    private Item cursorItem;
    /** "#tag" picked via right-click on an inventory item, on the cursor. */
    private String cursorTag;

    // Rectangle around the whole UI (panel + widgets + inventory), so EMI can
    // place its side panels next to the editor instead of over it.
    private int uiLeft;
    private int uiTop;
    private int uiRight;
    private int uiBottom;

    /** True when the layout's texture actually resolves — else the card panel is drawn. */
    private boolean texturePresent;

    private final EditorLayout.SlotView slotView = new EditorLayout.SlotView() {
        @Override
        public String ref(int index) {
            String[] refs = refs();
            return index >= 0 && index < refs.length ? refs[index] : null;
        }

        @Override
        public int count(int index) {
            int[] counts = counts();
            return index >= 0 && index < counts.length ? counts[index] : 0;
        }

        @Override
        public int chance(int index) {
            int[] chances = chances();
            return index >= 0 && index < chances.length ? chances[index] : 100;
        }

        @Override
        public String field(String path) {
            for (FieldDef field : layout.fields) {
                if (field.path().equals(path)) {
                    return fieldValue(field);
                }
            }
            return "";
        }
    };

    private record FieldLabel(Component text, int x, int y) {
    }

    private static class EditorError extends RuntimeException {
        EditorError(String message) {
            super(message);
        }
    }

    public RecipeEditorScreen() {
        // EMI (and REI/JEI) only attach their overlays to container screens,
        // so the editor is one — with a slot-less, client-only menu that never
        // talks to the server (all container packet paths are neutralized
        // below).
        super(new EditorMenu(0, Minecraft.getInstance().player.getInventory()),
                Minecraft.getInstance().player.getInventory(),
                Component.literal("Recipe Editor"));
        this.layouts = EditorLayouts.loadAll();
        EditorLayout remembered = null;
        for (EditorLayout candidate : layouts) {
            if (candidate.typeId.equals(lastTypeId)) {
                remembered = candidate;
                break;
            }
        }
        this.layout = remembered != null ? remembered : layouts.getFirst();
        lastTypeId = this.layout.typeId;
    }

    /** Switches to the layout for {@code typeId} (before the screen is shown); false when unknown. */
    public boolean selectType(String typeId) {
        for (EditorLayout candidate : layouts) {
            if (candidate.typeId.equals(typeId)) {
                layout = candidate;
                lastTypeId = typeId;
                status = Component.empty();
                return true;
            }
        }
        return false;
    }

    /** Fills every slot and field with sample values (dev screenshot harness). */
    public void fillSample() {
        String[] refs = refs();
        int[] counts = counts();
        int[] chances = chances();
        for (int i = 0; i < layout.slots.size(); i++) {
            SlotDef slot = layout.slots.get(i);
            if (slot.format().fluid()) {
                refs[i] = slot.result() ? "minecraft:lava" : "minecraft:water";
                counts[i] = 250;
            } else {
                refs[i] = slot.result() ? "minecraft:gold_ingot" : "minecraft:iron_ingot";
                counts[i] = slot.format().counted ? 2 : 1;
            }
            if (slot.chanceable() && i % 2 == 1) {
                chances[i] = 50;
            }
        }
        for (FieldDef field : layout.fields) {
            String value = field.suggestions().isEmpty() ? field.defaultValue() : field.suggestions().getLast();
            if (field.type() == FieldType.INT && value.isEmpty()) {
                value = "100";
            }
            fieldValues().put(field.path(), value);
        }
    }

    private String[] refs() {
        String[] refs = REFS_BY_TYPE.get(layout.typeId);
        if (refs == null || refs.length != layout.slots.size()) {
            refs = new String[layout.slots.size()];
            REFS_BY_TYPE.put(layout.typeId, refs);
        }
        return refs;
    }

    private int[] counts() {
        int[] counts = COUNTS_BY_TYPE.get(layout.typeId);
        if (counts == null || counts.length != layout.slots.size()) {
            counts = new int[layout.slots.size()];
            for (int i = 0; i < counts.length; i++) {
                counts[i] = layout.slots.get(i).format().fluid() ? 1000 : 1;
            }
            COUNTS_BY_TYPE.put(layout.typeId, counts);
        }
        return counts;
    }

    /** Drop chances in percent (1-100), for chanceable slots. */
    private int[] chances() {
        int[] chances = CHANCES_BY_TYPE.get(layout.typeId);
        if (chances == null || chances.length != layout.slots.size()) {
            chances = new int[layout.slots.size()];
            java.util.Arrays.fill(chances, 100);
            CHANCES_BY_TYPE.put(layout.typeId, chances);
        }
        return chances;
    }

    private Map<String, String> fieldValues() {
        return FIELDS_BY_TYPE.computeIfAbsent(layout.typeId, k -> new HashMap<>());
    }

    private String fieldValue(FieldDef field) {
        return fieldValues().getOrDefault(field.path(), field.defaultValue());
    }

    @Override
    protected void init() {
        super.init();
        fieldLabels.clear();
        assert minecraft != null;
        texturePresent = layout.texture != null
                && minecraft.getResourceManager().getResource(layout.texture).isPresent();
        if (layout.fullGui()) {
            initFullGui();
        } else {
            initSidePanel();
        }
        // Advertise the UI rectangle as the container's "background" so
        // overlay mods position their panels beside it.
        leftPos = uiLeft;
        topPos = uiTop;
        imageWidth = uiRight - uiLeft;
        imageHeight = uiBottom - uiTop;
    }

    /** Layouts without a full station GUI: widgets below, inventory panel on the left. */
    private void initSidePanel() {
        int contentW = Math.max(236, layout.width);
        int left = (width - contentW) / 2;
        panelLeft = (width - layout.width) / 2;

        // Vertical flow: type selector, panel, id box, field rows, buttons, status.
        List<List<FieldDef>> fieldRows = flowFields(contentW);
        int totalH = 24 + layout.height + 6 + 22 + fieldRows.size() * 22 + 24 + 14;
        int top = Math.max(6, (height - totalH) / 2);

        addRenderableWidget(typeButton(left, top, contentW));
        panelTop = top + 24;
        invLeft = Math.max(4, left - INV_COLS * 18 - 16);
        invTop = Math.max(top, (height - INV_ROWS * 18) / 2);

        int y = panelTop + layout.height + 6;
        addRenderableWidget(idBox(left, y, contentW));
        y += 22;

        for (List<FieldDef> row : fieldRows) {
            int x = left;
            for (FieldDef field : row) {
                Component label = fieldLabel(field);
                fieldLabels.add(new FieldLabel(label, x, y + 5));
                x += font.width(label) + 5;
                addRenderableWidget(fieldBox(field, x, y, boxWidth(field)));
                x += boxWidth(field) + 12;
            }
            y += 22;
        }

        int buttonW = (contentW - 8) / 3;
        addRenderableWidget(Button.builder(Component.literal("Save recipe"), b -> save())
                .bounds(left, y, buttonW, 20).build());
        addRenderableWidget(clearButton(left + buttonW + 4, y, buttonW));
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(left + (buttonW + 4) * 2, y, buttonW, 20).build());

        // The status/hint line sits inside the UI box, not at the screen
        // bottom — EMI's search bar lives down there.
        statusY = y + 26;

        uiLeft = Math.min(invLeft - 4, left);
        uiTop = Math.min(top, invTop - 14);
        uiRight = Math.max(left + contentW, panelLeft + layout.width) + 4;
        uiBottom = Math.max(statusY + 12, invTop + INV_ROWS * 18) + 4;
    }

    /**
     * Full station GUIs (176x166 with the player inventory in place): the
     * texture is drawn whole, the inventory sits where the real screen puts
     * it, and the id/fields/buttons move to a column on the right.
     */
    private void initFullGui() {
        int colW = 150;
        int totalW = layout.width + 8 + colW;
        panelLeft = (width - totalW) / 2;
        int top = Math.max(4, (height - (24 + layout.height + 18)) / 2);

        addRenderableWidget(typeButton(panelLeft, top, totalW));
        panelTop = top + 24;
        invLeft = 0;
        invTop = 0;

        int colX = panelLeft + layout.width + 8;
        int y = panelTop;
        addRenderableWidget(idBox(colX, y, colW));
        y += 22;

        for (FieldDef field : layout.fields) {
            Component label = fieldLabel(field);
            fieldLabels.add(new FieldLabel(label, colX, y + 5));
            int labelW = font.width(label) + 5;
            addRenderableWidget(fieldBox(field, colX + labelW, y, Math.max(40, colW - labelW)));
            y += 22;
        }

        y += 2;
        addRenderableWidget(Button.builder(Component.literal("Save recipe"), b -> save())
                .bounds(colX, y, colW, 20).build());
        y += 22;
        addRenderableWidget(clearButton(colX, y, colW / 2 - 2));
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(colX + colW / 2 + 2, y, colW / 2 - 2, 20).build());
        y += 20;

        statusY = Math.max(panelTop + layout.height, y) + 6;

        uiLeft = panelLeft - 4;
        uiTop = top - 4;
        uiRight = colX + colW + 4;
        uiBottom = statusY + 14;
    }

    private Button typeButton(int x, int y, int buttonWidth) {
        return Button.builder(Component.literal(layout.displayName), b -> {
                    assert minecraft != null;
                    minecraft.setScreen(new TypePickerScreen(this, layouts, chosen -> {
                        layout = chosen;
                        lastTypeId = chosen.typeId;
                        status = Component.empty();
                    }));
                })
                .bounds(x, y, buttonWidth, 20)
                .tooltip(Tooltip.create(Component.literal("Click to choose a recipe type")))
                .build();
    }

    private EditBox idBox(int x, int y, int boxW) {
        EditBox box = new EditBox(font, x, y, boxW, 18, Component.literal("Recipe id"));
        box.setMaxLength(256);
        box.setHint(Component.literal("recipe id (optional — use an existing id to replace it)")
                .withStyle(ChatFormatting.DARK_GRAY));
        box.setValue(idText);
        box.setResponder(text -> idText = text);
        return box;
    }

    private Component fieldLabel(FieldDef field) {
        return Component.literal(field.label() + (field.required() ? "*" : ""))
                .withStyle(ChatFormatting.GRAY);
    }

    private EditBox fieldBox(FieldDef field, int x, int y, int boxW) {
        EditBox box = new EditBox(font, x, y, boxW, 18, Component.literal(field.label()));
        box.setMaxLength(256);
        box.setValue(fieldValue(field));
        box.setResponder(text -> fieldValues().put(field.path(), text));
        if (!field.suggestions().isEmpty()) {
            box.setTooltip(Tooltip.create(Component.literal("Known values:\n"
                    + String.join(", ", field.suggestions()))));
        }
        return box;
    }

    private Button clearButton(int x, int y, int buttonWidth) {
        return Button.builder(Component.literal("Clear"), b -> clearSlots())
                .tooltip(Tooltip.create(Component.literal("Empty every slot of this recipe type")))
                .bounds(x, y, buttonWidth, 20)
                .build();
    }

    /** The whole editor UI as {x, y, width, height} — EMI keeps its panels outside this. */
    public int[] emiScreenBounds() {
        return new int[]{uiLeft, uiTop, uiRight - uiLeft, uiBottom - uiTop};
    }

    // --- Container-screen plumbing neutralized: the menu is client-only. ---

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
        // Skip AbstractContainerScreen's close-packet path — there is no
        // server-side container to close.
        assert minecraft != null;
        minecraft.setScreen(null);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // AbstractContainerScreen closes on the inventory key ('E'), which
        // must not happen while typing in a text box.
        if (keyCode != 256 && getFocused() instanceof EditBox box && box.canConsumeInput()) {
            box.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private static int boxWidth(FieldDef field) {
        return field.type() == EditorLayout.FieldType.STRING ? 100 : 50;
    }

    private List<List<FieldDef>> flowFields(int contentW) {
        List<List<FieldDef>> rows = new ArrayList<>();
        List<FieldDef> row = new ArrayList<>();
        int x = 0;
        for (FieldDef field : layout.fields) {
            int w = font.width(field.label() + (field.required() ? "*" : "")) + 5 + boxWidth(field) + 12;
            if (!row.isEmpty() && x + w > contentW) {
                rows.add(row);
                row = new ArrayList<>();
                x = 0;
            }
            row.add(field);
            x += w;
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        return rows;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, Math.max(2, uiTop - 12), 0xFFFFFF);
        if (texturePresent) {
            graphics.blit(layout.texture, panelLeft, panelTop, layout.u, layout.v, layout.width, layout.height);
        } else {
            renderGenericPanel(graphics);
        }
        if (layout.decoration != null) {
            layout.decoration.render(graphics, panelLeft - layout.u, panelTop - layout.v, partialTick, slotView);
        }

        String[] refs = refs();
        int[] counts = counts();
        int hoveredSlot = -1;
        for (int i = 0; i < layout.slots.size(); i++) {
            SlotDef slot = layout.slots.get(i);
            int x = slotX(slot);
            int y = slotY(slot);
            if (texturePresent && slot.format().fluid()) {
                // Fluid ghosts sit on tank graphics, not real item slots, so
                // give them a visible socket.
                graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFFFFFFFF);
                graphics.fill(x - 1, y - 1, x + 16, y + 16, 0xFF373737);
                graphics.fill(x, y, x + 16, y + 16, 0xFF8B8B8B);
            }
            if (refs[i] == null) {
                graphics.fill(x, y, x + 16, y + 16, slot.result() ? 0x3355FF55 : 0x33FFFFFF);
            }
            boolean hover = mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16;
            if (hover) {
                hoveredSlot = i;
                graphics.fill(x, y, x + 16, y + 16, 0x66FFFFFF);
            }
            if (refs[i] != null) {
                ItemStack icon = iconFor(refs[i], slot.format().fluid());
                graphics.renderItem(icon, x, y);
                String decoration = decorationFor(slot, refs[i], counts[i], chances()[i]);
                if (decoration != null) {
                    graphics.renderItemDecorations(font, icon, x, y, decoration);
                }
            }
        }

        renderInventory(graphics, mouseX, mouseY);

        for (FieldLabel label : fieldLabels) {
            graphics.drawString(font, label.text(), label.x(), label.y(), 0xA0A0A0);
        }
        if (!status.getString().isEmpty()) {
            graphics.drawCenteredString(font, status, width / 2, statusY, 0xFFFFFF);
        }

        if (hoveredSlot >= 0) {
            renderSlotTooltip(graphics, hoveredSlot, mouseX, mouseY);
        }

        if (cursorItem != null) {
            graphics.renderItem(new ItemStack(cursorItem), mouseX - 8, mouseY - 8);
        } else if (cursorTag != null) {
            graphics.renderItem(iconFor(cursorTag, false), mouseX - 8, mouseY - 8);
            graphics.pose().pushPose();
            graphics.pose().translate(mouseX - 8, mouseY - 8, 200);
            graphics.pose().scale(0.5f, 0.5f, 1);
            graphics.drawString(font, "#", 1, 23, 0xFFAA00, true);
            graphics.pose().popPose();
        }
    }

    /**
     * Screen position of inventory slot {@code index} (vanilla indices:
     * 0-8 hotbar, 9-35 main). Full-GUI layouts use the texture's own
     * inventory section; others use the editor's 4x9 side panel.
     */
    private int[] invPos(int index) {
        if (layout.fullGui()) {
            int baseX = panelLeft + 8 - layout.u;
            int baseY = panelTop + layout.inventoryY - layout.v;
            if (index < 9) {
                return new int[]{baseX + index * 18, baseY + 58}; // hotbar row
            }
            return new int[]{baseX + ((index - 9) % 9) * 18, baseY + ((index - 9) / 9) * 18};
        }
        return new int[]{invLeft + (index % INV_COLS) * 18 + 1, invTop + (index / INV_COLS) * 18 + 1};
    }

    private void renderInventory(GuiGraphics graphics, int mouseX, int mouseY) {
        assert minecraft != null && minecraft.player != null;
        List<ItemStack> items = minecraft.player.getInventory().items;
        if (layout.fullGui()) {
            // Both the GUI textures and the editor's own panel are light.
            graphics.drawString(font, Component.literal("Inventory"),
                    panelLeft + 8 - layout.u, panelTop + layout.inventoryY - layout.v - 12, 0x404040, false);
        } else {
            graphics.drawString(font, Component.literal("Inventory").withStyle(ChatFormatting.GRAY),
                    invLeft, invTop - 11, 0xA0A0A0);
        }
        ItemStack hovered = ItemStack.EMPTY;
        for (int i = 0; i < items.size() && i < 36; i++) {
            int[] pos = invPos(i);
            int x = pos[0];
            int y = pos[1];
            if (!texturePresent) { // textures already have slot graphics
                graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFFFFFFFF);
                graphics.fill(x - 1, y - 1, x + 16, y + 16, 0xFF373737);
                graphics.fill(x, y, x + 16, y + 16, 0xFF8B8B8B);
            }
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
            graphics.renderComponentTooltip(font, List.of(
                    hovered.getHoverName(),
                    Component.literal(BuiltInRegistries.ITEM.getKey(hovered.getItem()).toString())
                            .withStyle(ChatFormatting.DARK_GRAY),
                    Component.literal("Click to pick up, then click any slots — right-click drops")
                            .withStyle(ChatFormatting.GRAY),
                    Component.literal("Right/middle-click: pick up one of its #tags instead "
                            + "(for slots that take tags)")
                            .withStyle(ChatFormatting.GRAY)),
                    mouseX, mouseY);
        }
    }

    private int invSlotAt(double mouseX, double mouseY) {
        for (int i = 0; i < 36; i++) {
            int[] pos = invPos(i);
            if (mouseX >= pos[0] && mouseX < pos[0] + 16 && mouseY >= pos[1] && mouseY < pos[1] + 16) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Stand-in for layouts without a station texture: a vanilla-style light
     * panel with slot sockets and an arrow toward the results, in the look
     * recipe viewers like EMI use for their recipe cards.
     */
    private void renderGenericPanel(GuiGraphics graphics) {
        int right = panelLeft + layout.width;
        int bottom = panelTop + layout.height;
        graphics.fill(panelLeft - 1, panelTop - 1, right + 1, bottom + 1, 0xFF000000);
        graphics.fill(panelLeft, panelTop, right, bottom, 0xFFC6C6C6);
        graphics.fill(panelLeft, panelTop, right, panelTop + 1, 0xFFFFFFFF);
        graphics.fill(panelLeft, panelTop, panelLeft + 1, bottom, 0xFFFFFFFF);
        graphics.fill(panelLeft, bottom - 1, right, bottom, 0xFF555555);
        graphics.fill(right - 1, panelTop, right, bottom, 0xFF555555);

        if (layout.ownSlotArt) {
            return;
        }
        int arrowX = Integer.MAX_VALUE;
        int minResultY = Integer.MAX_VALUE;
        int maxResultY = Integer.MIN_VALUE;
        for (SlotDef slot : layout.slots) {
            int x = slotX(slot);
            int y = slotY(slot);
            graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFFFFFFFF);
            graphics.fill(x - 1, y - 1, x + 16, y + 16, 0xFF373737);
            graphics.fill(x, y, x + 16, y + 16, 0xFF8B8B8B);
            if (slot.result()) {
                arrowX = Math.min(arrowX, x);
                minResultY = Math.min(minResultY, y);
                maxResultY = Math.max(maxResultY, y);
            }
        }
        if (arrowX != Integer.MAX_VALUE) {
            String arrow = "->";
            graphics.drawString(font, arrow, arrowX - 12 - font.width(arrow) / 2,
                    (minResultY + maxResultY) / 2 + 4, 0x404040, false);
        }
    }

    private String decorationFor(SlotDef slot, String ref, int count, int chance) {
        if (slot.format().fluid()) {
            return count % 1000 == 0 ? (count / 1000) + "B" : String.valueOf(count);
        }
        if (slot.chanceable() && chance < 100) {
            return chance + "%";
        }
        String tag = ref.startsWith("#") ? "#" : "";
        return count > 1 ? tag + count : (tag.isEmpty() ? null : tag);
    }

    /** Human label for a slot, derived from its JSON field ("base_fluid" -> "Base fluid"). */
    private static String slotLabel(SlotDef slot) {
        String path = slot.path();
        if (path.endsWith("[]")) {
            path = path.substring(0, path.length() - 2);
        }
        int dot = path.lastIndexOf('.');
        if (dot >= 0) {
            path = path.substring(dot + 1);
        }
        if (path.isEmpty()) {
            return slot.result() ? "Result" : "Ingredient";
        }
        String spaced = path.replace('_', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private void renderSlotTooltip(GuiGraphics graphics, int index, int mouseX, int mouseY) {
        SlotDef slot = layout.slots.get(index);
        String ref = refs()[index];
        List<Component> lines = new ArrayList<>();
        if (ref == null) {
            lines.add(Component.literal(slotLabel(slot))
                    .append(slot.required() ? " (required)" : "").withStyle(ChatFormatting.WHITE));
            lines.add(Component.literal("Click to choose").withStyle(ChatFormatting.GRAY));
            if (slot.acceptsTags() && !slot.format().fluid()) {
                lines.add(Component.literal("#tags work here — type # in the item list, or "
                        + "right/middle-click an inventory item for its tags")
                        .withStyle(ChatFormatting.GRAY));
            }
        } else {
            lines.add(Component.literal(slotLabel(slot) + ": ").withStyle(ChatFormatting.GRAY)
                    .append(iconFor(ref, slot.format().fluid()).getHoverName()));
            lines.add(Component.literal(ref).withStyle(ChatFormatting.DARK_GRAY));
            if (slot.format().fluid()) {
                lines.add(Component.literal(counts()[index] + " mB").withStyle(ChatFormatting.AQUA));
            }
            if (slot.chanceable()) {
                lines.add(Component.literal("Chance: " + chances()[index] + "%")
                        .withStyle(ChatFormatting.AQUA));
            }
            if (slot.acceptsTags() && !slot.format().fluid() && !ref.startsWith("#")) {
                lines.add(Component.literal("Middle-click: convert to one of its #tags")
                        .withStyle(ChatFormatting.GRAY));
            }
            lines.add(Component.literal("Right-click to clear").withStyle(ChatFormatting.GRAY));
        }
        if (slot.format().counted && layout.kind == Kind.SLOTS) {
            lines.add(Component.literal(slot.format().fluid()
                    ? "Scroll: ±100 mB (Shift ±10, Ctrl ±1000) — middle-click to type"
                    : "Scroll or middle-click to set the count").withStyle(ChatFormatting.GRAY));
        }
        if (slot.chanceable()) {
            lines.add(Component.literal("Alt+Scroll: chance ±5% (Shift ±1%)")
                    .withStyle(ChatFormatting.GRAY));
        }
        graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
    }

    private int slotX(SlotDef slot) {
        return panelLeft + slot.x() - layout.u;
    }

    private int slotY(SlotDef slot) {
        return panelTop + slot.y() - layout.v;
    }

    private int slotAt(double mouseX, double mouseY) {
        for (int i = 0; i < layout.slots.size(); i++) {
            SlotDef slot = layout.slots.get(i);
            int x = slotX(slot);
            int y = slotY(slot);
            if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Our slots/inventory come first: AbstractContainerScreen.mouseClicked
        // consumes every click, so it must only see what we didn't handle.
        if (button == 1 && (cursorItem != null || cursorTag != null)) {
            cursorItem = null; // right-click drops the picked-up item/tag
            cursorTag = null;
            return true;
        }
        int invIndex = invSlotAt(mouseX, mouseY);
        if (invIndex >= 0) {
            assert minecraft != null && minecraft.player != null;
            List<ItemStack> items = minecraft.player.getInventory().items;
            if (invIndex < items.size() && !items.get(invIndex).isEmpty()) {
                if (button == 0) {
                    cursorItem = items.get(invIndex).getItem();
                } else {
                    // Right- or middle-click: put one of the item's #tags on
                    // the cursor instead, for slots that accept tags.
                    minecraft.setScreen(new ItemTagListScreen(this,
                            items.get(invIndex).getItem(), tag -> {
                        cursorTag = tag;
                        cursorItem = null;
                    }));
                }
            }
            return true;
        }
        int index = slotAt(mouseX, mouseY);
        if (index < 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        SlotDef slot = layout.slots.get(index);
        if (button == 1) {
            refs()[index] = null;
            return true;
        }
        if (button == 2 && refs()[index] != null) {
            assert minecraft != null;
            final int slotIndex = index;
            boolean countEditable = slot.format().counted
                    && !(layout.kind != Kind.SLOTS && !slot.result());
            // An item in a tag-capable slot can be converted to one of its
            // #tags, like a loot entry (MMB muscle memory).
            Runnable toTag = slot.acceptsTags() && !slot.format().fluid()
                    && !refs()[index].startsWith("#")
                    ? () -> minecraft.setScreen(new ItemTagListScreen(this,
                            iconFor(refs()[slotIndex], false).getItem(),
                            tag -> refs()[slotIndex] = tag))
                    : null;
            if (countEditable) {
                // Middle-click: type an exact amount instead of scrolling.
                boolean fluid = slot.format().fluid();
                Component label = Component.literal(slotLabel(slot) + (fluid ? " (mB)" : " (count)"));
                int max = fluid ? 1_000_000 : 99;
                minecraft.setScreen(toTag == null
                        ? new AmountInputScreen(this, label, counts()[index], 1, max,
                                amount -> counts()[slotIndex] = amount)
                        : new AmountInputScreen(this, label, counts()[index], 1, max,
                                amount -> counts()[slotIndex] = amount,
                                Component.literal("Convert to #tag…"), toTag));
                return true;
            }
            if (toTag != null) {
                toTag.run();
                return true;
            }
        }
        if (button == 0) {
            if (cursorTag != null) {
                if (slot.acceptsTags() && !slot.format().fluid()) {
                    refs()[index] = cursorTag; // stays on the cursor, AE2-style
                }
                return true;
            }
            if (cursorItem != null) {
                // AE2-style: the item stays on the cursor so it can be placed
                // into several slots; right-click drops it.
                placeItem(index, cursorItem);
                return true;
            }
            assert minecraft != null;
            final int slotIndex = index;
            minecraft.setScreen(new ItemPickerScreen(this,
                    slot.format().fluid(), slot.acceptsTags(),
                    ref -> refs()[slotIndex] = ref));
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        // Dragging from the inventory panel: place where the drag ends. The
        // item stays on the cursor for further placements (AE2-style).
        if (button == 0 && cursorItem != null) {
            int index = slotAt(mouseX, mouseY);
            if (index >= 0 && placeItem(index, cursorItem)) {
                return true;
            }
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** Puts an item into a slot, converting buckets to fluids for fluid slots. */
    private boolean placeItem(int index, Item item) {
        if (layout.slots.get(index).format().fluid()) {
            Fluid fluid = fluidForBucket(item);
            if (fluid == null) {
                return false;
            }
            refs()[index] = BuiltInRegistries.FLUID.getKey(fluid).toString();
            return true;
        }
        if (item == Items.AIR) {
            return false;
        }
        refs()[index] = BuiltInRegistries.ITEM.getKey(item).toString();
        return true;
    }

    private static Fluid fluidForBucket(Item item) {
        if (item == Items.AIR) {
            return null;
        }
        for (Fluid fluid : BuiltInRegistries.FLUID) {
            if (fluid.getBucket() == item && fluid.isSource(fluid.defaultFluidState())) {
                return fluid;
            }
        }
        return null;
    }

    /** EMI drag & drop: place a dragged item/fluid at screen coordinates. */
    public boolean emiDrop(int x, int y, Object key) {
        int index = slotAt(x, y);
        if (index < 0) {
            return false;
        }
        if (key instanceof Fluid fluid) {
            if (!layout.slots.get(index).format().fluid()) {
                return false;
            }
            refs()[index] = BuiltInRegistries.FLUID.getKey(fluid).toString();
            return true;
        }
        return key instanceof Item item && placeItem(index, item);
    }

    /** EMI drag & drop: highlight every slot the dragged item/fluid could land in. */
    public void renderEmiDropTargets(GuiGraphics graphics, Object key) {
        for (SlotDef slot : layout.slots) {
            boolean accepts = key instanceof Fluid ? slot.format().fluid()
                    : key instanceof Item item
                    && (!slot.format().fluid() || fluidForBucket(item) != null);
            if (accepts) {
                int x = slotX(slot);
                int y = slotY(slot);
                graphics.fill(x, y, x + 16, y + 16, 0x8833BB33);
            }
        }
    }


    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int index = slotAt(mouseX, mouseY);
        if (index >= 0) {
            SlotDef slot = layout.slots.get(index);
            if (slot.chanceable() && hasAltDown() && refs()[index] != null) {
                int step = hasShiftDown() ? 1 : 5;
                int[] chances = chances();
                chances[index] = Math.max(1, Math.min(100,
                        chances[index] + (int) Math.signum(scrollY) * step));
                return true;
            }
            if (slot.format().counted && refs()[index] != null
                    && !(layout.kind != Kind.SLOTS && !slot.result())) {
                int direction = (int) Math.signum(scrollY);
                int[] counts = counts();
                if (slot.format().fluid()) {
                    int step = hasControlDown() ? 1000 : hasShiftDown() ? 10 : 100;
                    counts[index] = Math.max(10, Math.min(1_000_000, counts[index] + direction * step));
                } else {
                    int step = hasShiftDown() ? 10 : 1;
                    counts[index] = Math.max(1, Math.min(99, counts[index] + direction * step));
                }
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void save() {
        status = Component.empty();
        JsonObject recipe;
        try {
            recipe = buildRecipe();
        } catch (EditorError e) {
            status = Component.literal(e.getMessage()).withStyle(ChatFormatting.RED);
            return;
        }
        if (!ClientPlayNetworking.canSend(SaveRecipePayload.TYPE)) {
            status = Component.literal("This server doesn't run DataRewriter — recipe not saved.")
                    .withStyle(ChatFormatting.RED);
            return;
        }
        ClientPlayNetworking.send(new SaveRecipePayload(recipe.toString()));
        // The editor stays open for the next recipe; the server's confirmation
        // (or rejection) arrives in chat.
        status = Component.literal("Recipe sent — the server's answer is in chat.")
                .withStyle(ChatFormatting.GREEN);
    }

    private void clearSlots() {
        REFS_BY_TYPE.remove(layout.typeId);
        COUNTS_BY_TYPE.remove(layout.typeId);
        CHANCES_BY_TYPE.remove(layout.typeId);
        cursorItem = null;
        cursorTag = null;
        status = Component.empty();
    }

    private JsonObject buildRecipe() {
        JsonObject recipe = layout.template != null ? layout.template.deepCopy() : new JsonObject();
        recipe.addProperty("type", layout.typeId);
        String id = idText.trim();
        if (!id.isEmpty()) {
            if (ResourceLocation.tryParse(id) == null) {
                throw new EditorError("'" + id + "' is not a valid recipe id");
            }
            recipe.addProperty("id", id);
        }

        String[] refs = refs();
        int[] counts = counts();
        switch (layout.kind) {
            case SHAPED -> buildShaped(recipe, refs);
            case SHAPELESS -> buildShapeless(recipe, refs);
            case SLOTS -> {
            }
        }
        for (int i = 0; i < layout.slots.size(); i++) {
            SlotDef slot = layout.slots.get(i);
            if (layout.kind != Kind.SLOTS && !slot.result()) {
                continue; // grid inputs were turned into pattern/key or ingredients above
            }
            String ref = refs[i];
            if (ref == null) {
                if (slot.required()) {
                    throw new EditorError(slot.result() ? "choose a result item"
                            : "a required ingredient slot is empty");
                }
                continue;
            }
            if (ref.startsWith("#") && !slot.acceptsTags()) {
                throw new EditorError("that slot can't take a tag (" + ref + ")");
            }
            setAtPath(recipe, slot.path(), slotValue(slot, ref, counts[i], chances()[i] / 100f));
        }

        for (FieldDef field : layout.fields) {
            String text = fieldValue(field).trim();
            if (text.isEmpty()) {
                if (field.required()) {
                    throw new EditorError("'" + field.label() + "' is required");
                }
                continue;
            }
            JsonPrimitive value = switch (field.type()) {
                case INT -> {
                    try {
                        yield new JsonPrimitive(Long.parseLong(text));
                    } catch (NumberFormatException e) {
                        throw new EditorError("'" + field.label() + "' must be a whole number");
                    }
                }
                case FLOAT -> {
                    try {
                        yield new JsonPrimitive(Double.parseDouble(text));
                    } catch (NumberFormatException e) {
                        throw new EditorError("'" + field.label() + "' must be a number");
                    }
                }
                case STRING -> new JsonPrimitive(text);
                case BOOL -> {
                    if (!text.equalsIgnoreCase("true") && !text.equalsIgnoreCase("false")) {
                        throw new EditorError("'" + field.label() + "' must be true or false");
                    }
                    yield new JsonPrimitive(Boolean.parseBoolean(text));
                }
            };
            setAtPath(recipe, field.path(), value);
        }
        return recipe;
    }

    private void buildShaped(JsonObject recipe, String[] refs) {
        int n = layout.gridSize;
        int minRow = n;
        int maxRow = -1;
        int minCol = n;
        int maxCol = -1;
        for (int row = 0; row < n; row++) {
            for (int col = 0; col < n; col++) {
                if (refs[row * n + col] != null) {
                    minRow = Math.min(minRow, row);
                    maxRow = Math.max(maxRow, row);
                    minCol = Math.min(minCol, col);
                    maxCol = Math.max(maxCol, col);
                }
            }
        }
        if (maxRow < 0) {
            throw new EditorError("place at least one ingredient in the grid");
        }
        LinkedHashMap<String, Character> letters = new LinkedHashMap<>();
        JsonArray pattern = new JsonArray();
        for (int row = minRow; row <= maxRow; row++) {
            StringBuilder line = new StringBuilder();
            for (int col = minCol; col <= maxCol; col++) {
                String ref = refs[row * n + col];
                if (ref == null) {
                    line.append(' ');
                } else {
                    line.append((char) letters.computeIfAbsent(ref, r -> (char) ('A' + letters.size())));
                }
            }
            pattern.add(line.toString());
        }
        JsonObject key = new JsonObject();
        letters.forEach((ref, letter) -> key.addProperty(String.valueOf(letter), ref));
        recipe.add("pattern", pattern);
        recipe.add("key", key);
    }

    private void buildShapeless(JsonObject recipe, String[] refs) {
        JsonArray ingredients = new JsonArray();
        for (int i = 0; i < layout.gridSize * layout.gridSize; i++) {
            if (refs[i] != null) {
                ingredients.add(refs[i]);
            }
        }
        if (ingredients.isEmpty()) {
            throw new EditorError("place at least one ingredient in the grid");
        }
        recipe.add("ingredients", ingredients);
    }

    private static JsonElement slotValue(SlotDef slot, String ref, int count, float chance) {
        if (slot.custom() != null) {
            return slot.custom().build(ref, count, chance);
        }
        SlotFormat format = slot.format();
        JsonElement value = switch (format) {
            case STRING -> new JsonPrimitive(ref);
            case SHORTHAND_RESULT -> {
                if (count <= 1) {
                    yield new JsonPrimitive(ref);
                }
                JsonObject obj = new JsonObject();
                obj.addProperty("id", ref);
                obj.addProperty("count", count);
                yield obj;
            }
            case INGREDIENT -> ingredientObj(ref);
            case COUNTED_INGREDIENT -> {
                JsonObject obj = new JsonObject();
                obj.add("ingredient", ingredientObj(ref));
                if (count > 1) {
                    obj.addProperty("count", count);
                }
                yield obj;
            }
            case ITEM, ITEM_NAMED -> {
                JsonObject obj = new JsonObject();
                obj.addProperty(format == SlotFormat.ITEM ? "id" : "item", ref);
                if (count > 1) {
                    obj.addProperty("count", count);
                }
                yield obj;
            }
            case FLUID, FLUID_AMOUNT -> {
                JsonObject obj = new JsonObject();
                if (ref.startsWith("#")) {
                    obj.addProperty("tag", ref.substring(1));
                } else {
                    obj.addProperty("id", ref);
                }
                obj.addProperty(format == SlotFormat.FLUID ? "amount_mb" : "amount", count);
                yield obj;
            }
        };
        // Generic chance support for layout files: added as a "chance" key.
        if (slot.chanceable() && chance < 1f && value instanceof JsonObject obj) {
            obj.addProperty("chance", chance);
        }
        return value;
    }

    private static JsonObject ingredientObj(String ref) {
        JsonObject obj = new JsonObject();
        if (ref.startsWith("#")) {
            obj.addProperty("tag", ref.substring(1));
        } else {
            obj.addProperty("item", ref);
        }
        return obj;
    }

    /**
     * Fills the editor from an existing recipe — the reverse of saving. Used
     * by EMI's recipe-fill button. Encodes the recipe with the real codec and
     * maps the JSON back through the layout's slot/field paths; the id box is
     * set to the recipe's id, so saving unchanged replaces it.
     */
    public boolean loadRecipe(RecipeHolder<?> holder) {
        assert minecraft != null;
        if (minecraft.level == null) {
            return false;
        }
        JsonElement encoded;
        try {
            encoded = Recipe.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE,
                    minecraft.level.registryAccess()), holder.value()).result().orElse(null);
        } catch (Exception e) {
            encoded = null;
        }
        if (!(encoded instanceof JsonObject json)
                || !(json.get("type") instanceof JsonPrimitive typeValue)) {
            // Network-synced shaped recipes lose their pattern/key data and
            // cannot be re-encoded ("Cannot encode unpacked recipe") — rebuild
            // the grid from the parsed recipe instead.
            ResourceLocation serializer = BuiltInRegistries.RECIPE_SERIALIZER
                    .getKey(holder.value().getSerializer());
            if (holder.value() instanceof ShapedRecipe shaped && serializer != null) {
                return loadShapedParsed(holder, shaped, serializer.toString());
            }
            return false;
        }
        String typeId = typeValue.getAsString();
        EditorLayout target = null;
        for (EditorLayout candidate : layouts) {
            if (candidate.typeId.equals(typeId)) {
                target = candidate;
                break;
            }
        }
        if (target == null) {
            return false;
        }
        layout = target;
        lastTypeId = typeId;
        clearSlots();
        FIELDS_BY_TYPE.remove(typeId);

        String[] refs = refs();
        int[] counts = counts();
        int[] chances = chances();
        switch (layout.kind) {
            case SHAPED -> loadShaped(json, refs);
            case SHAPELESS -> loadShapeless(json, refs);
            case SLOTS -> {
            }
        }

        Map<String, Integer> cursors = new HashMap<>();
        Set<String> consumed = new HashSet<>();
        for (int i = 0; i < layout.slots.size(); i++) {
            SlotDef slot = layout.slots.get(i);
            if (layout.kind != Kind.SLOTS && !slot.result()) {
                continue;
            }
            boolean array = slot.path().endsWith("[]");
            if (!array && consumed.contains(slot.path())) {
                continue; // an either/or path already claimed by another slot
            }
            Parsed parsed;
            if (array) {
                // Arrays may mix items and fluids (Create's ingredients/results):
                // each slot takes the next entry of its own kind, so an item slot
                // never swallows a fluid entry meant for a fluid slot.
                parsed = nextArrayEntry(json, slot.path(), slot.format().fluid(), cursors);
            } else {
                JsonElement value = getAtPath(json, slot.path(), cursors);
                parsed = value == null ? null : parseAny(value);
            }
            if (parsed == null || parsed.fluidLike() != slot.format().fluid()) {
                continue;
            }
            refs[i] = parsed.ref();
            if (parsed.count() > 0) {
                counts[i] = Math.max(1, Math.min(slot.format().fluid() ? 1_000_000 : 99, parsed.count()));
            }
            if (slot.chanceable() && parsed.chance() > 0) {
                chances[i] = Math.max(1, Math.min(100, Math.round(parsed.chance() * 100)));
            }
            if (!array) {
                consumed.add(slot.path());
            }
        }

        Map<String, String> values = fieldValues();
        Map<String, Integer> fieldCursors = new HashMap<>();
        for (FieldDef field : layout.fields) {
            if (getAtPath(json, field.path(), fieldCursors) instanceof JsonPrimitive p) {
                values.put(field.path(), p.getAsString());
            }
        }

        idText = holder.id().toString();
        status = Component.literal("Loaded " + holder.id() + " — save to replace it.")
                .withStyle(ChatFormatting.GREEN);
        rebuildWidgets();
        return true;
    }

    /** Fills the crafting grid from an already-parsed shaped recipe (no JSON round-trip). */
    private boolean loadShapedParsed(RecipeHolder<?> holder, ShapedRecipe shaped, String typeId) {
        assert minecraft != null && minecraft.level != null;
        // Vanilla shaped recipes and shaped-derived modded ones (e.g. Create's
        // mechanical crafting) share the serializer id with their layout's type.
        EditorLayout target = null;
        for (EditorLayout candidate : layouts) {
            if (candidate.typeId.equals(typeId) && candidate.kind == Kind.SHAPED) {
                target = candidate;
                break;
            }
        }
        if (target == null) {
            return false;
        }
        layout = target;
        lastTypeId = layout.typeId;
        clearSlots();

        String[] refs = refs();
        int[] counts = counts();
        int width = shaped.getWidth();
        int height = shaped.getHeight();
        List<Ingredient> ingredients = shaped.getIngredients();
        int n = layout.gridSize;
        for (int row = 0; row < Math.min(n, height); row++) {
            for (int col = 0; col < Math.min(n, width); col++) {
                // Tag ingredients arrive from the server pre-expanded, so the
                // first matching item stands in for the tag.
                ItemStack[] options = ingredients.get(row * width + col).getItems();
                if (options.length > 0) {
                    refs[row * n + col] = BuiltInRegistries.ITEM.getKey(options[0].getItem()).toString();
                }
            }
        }
        ItemStack result = shaped.getResultItem(minecraft.level.registryAccess());
        for (int i = 0; i < layout.slots.size(); i++) {
            if (layout.slots.get(i).result() && !result.isEmpty()) {
                refs[i] = BuiltInRegistries.ITEM.getKey(result.getItem()).toString();
                counts[i] = Math.max(1, Math.min(99, result.getCount()));
            }
        }

        idText = holder.id().toString();
        status = Component.literal("Loaded " + holder.id() + " — save to replace it.")
                .withStyle(ChatFormatting.GREEN);
        rebuildWidgets();
        return true;
    }

    private void loadShaped(JsonObject json, String[] refs) {
        if (!(json.get("key") instanceof JsonObject key)
                || !(json.get("pattern") instanceof JsonArray pattern)) {
            return;
        }
        Map<Character, String> byLetter = new HashMap<>();
        for (String letter : key.keySet()) {
            Parsed parsed = parseAny(key.get(letter));
            if (parsed != null && !letter.isEmpty()) {
                byLetter.put(letter.charAt(0), parsed.ref());
            }
        }
        int n = layout.gridSize;
        for (int row = 0; row < Math.min(n, pattern.size()); row++) {
            String line = pattern.get(row).getAsString();
            for (int col = 0; col < Math.min(n, line.length()); col++) {
                refs[row * n + col] = byLetter.get(line.charAt(col));
            }
        }
    }

    private void loadShapeless(JsonObject json, String[] refs) {
        if (!(json.get("ingredients") instanceof JsonArray ingredients)) {
            return;
        }
        for (int i = 0; i < Math.min(layout.gridSize * layout.gridSize, ingredients.size()); i++) {
            Parsed parsed = parseAny(ingredients.get(i));
            if (parsed != null) {
                refs[i] = parsed.ref();
            }
        }
    }

    /** Reads a value at a dotted path; "[]" paths consume array entries in order via {@code cursors}. */
    private static JsonElement getAtPath(JsonObject root, String path, Map<String, Integer> cursors) {
        String[] parts = path.split("\\.");
        JsonObject current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            if (current.get(parts[i]) instanceof JsonObject next) {
                current = next;
            } else {
                return null;
            }
        }
        String last = parts[parts.length - 1];
        if (last.endsWith("[]")) {
            String name = last.substring(0, last.length() - 2);
            if (!(current.get(name) instanceof JsonArray array)) {
                return null;
            }
            int index = cursors.merge(path, 1, Integer::sum) - 1;
            return index < array.size() ? array.get(index) : null;
        }
        return current.get(last);
    }

    private record Parsed(String ref, int count, float chance, boolean fluidLike) {
    }

    /**
     * Next entry of the array at {@code path} whose kind (fluid or item)
     * matches, skipping the other kind; the cursor is kept per path and kind.
     */
    private static Parsed nextArrayEntry(JsonObject root, String path, boolean fluid, Map<String, Integer> cursors) {
        String[] parts = path.split("\\.");
        JsonObject current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            if (current.get(parts[i]) instanceof JsonObject next) {
                current = next;
            } else {
                return null;
            }
        }
        String last = parts[parts.length - 1];
        String name = last.substring(0, last.length() - 2);
        if (!(current.get(name) instanceof JsonArray array)) {
            return null;
        }
        String cursorKey = path + (fluid ? "|fluid" : "|item");
        int wanted = cursors.merge(cursorKey, 1, Integer::sum) - 1;
        int seen = 0;
        for (JsonElement element : array) {
            Parsed parsed = parseAny(element);
            if (parsed == null || parsed.fluidLike() != fluid) {
                continue;
            }
            if (seen++ == wanted) {
                return parsed;
            }
        }
        return null;
    }

    /** Best-effort extraction of (ref, amount, chance) from the shapes recipes use. */
    private static Parsed parseAny(JsonElement value) {
        if (value instanceof JsonPrimitive p && p.isString()) {
            return new Parsed(p.getAsString(), 0, 0, false);
        }
        if (value instanceof JsonArray array) {
            return array.isEmpty() ? null : parseAny(array.get(0));
        }
        if (!(value instanceof JsonObject obj)) {
            return null;
        }
        boolean fluidLike = obj.has("amount") || obj.has("amount_mb") || obj.has("fluid");
        int count = intOf(obj, "count", intOf(obj, "amount", intOf(obj, "amount_mb", 0)));
        float chance = obj.get("chance") instanceof JsonPrimitive c && c.isNumber() ? c.getAsFloat() : 0;
        String ref = null;
        if (obj.get("id") instanceof JsonPrimitive p && p.isString()) {
            ref = p.getAsString();
        } else if (obj.get("fluid") instanceof JsonPrimitive p && p.isString()) {
            ref = p.getAsString();
        } else if (obj.get("item") instanceof JsonPrimitive p && p.isString()) {
            ref = p.getAsString();
        } else if (obj.get("tag") instanceof JsonPrimitive p && p.isString()) {
            String tag = p.getAsString();
            ref = tag.startsWith("#") ? tag : "#" + tag;
        } else if (obj.get("item") instanceof JsonObject inner) {
            Parsed nested = parseAny(inner);
            if (nested != null) {
                ref = nested.ref();
                if (count == 0) {
                    count = nested.count();
                }
            }
        } else if (obj.has("ingredient")) {
            Parsed nested = parseAny(obj.get("ingredient"));
            if (nested != null) {
                ref = nested.ref();
                fluidLike |= nested.fluidLike();
            }
        }
        return ref == null ? null : new Parsed(ref, count, chance, fluidLike);
    }

    private static int intOf(JsonObject obj, String key, int fallback) {
        return obj.get(key) instanceof JsonPrimitive p && p.isNumber() ? p.getAsInt() : fallback;
    }

    /** Sets a value at a dotted path; a trailing "[]" appends to an array. */
    private static void setAtPath(JsonObject root, String path, JsonElement value) {
        String[] parts = path.split("\\.");
        JsonObject current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            if (current.get(parts[i]) instanceof JsonObject next) {
                current = next;
            } else {
                JsonObject next = new JsonObject();
                current.add(parts[i], next);
                current = next;
            }
        }
        String last = parts[parts.length - 1];
        if (last.endsWith("[]")) {
            String name = last.substring(0, last.length() - 2);
            JsonArray array;
            if (current.get(name) instanceof JsonArray existing) {
                array = existing;
            } else {
                array = new JsonArray();
                current.add(name, array);
            }
            array.add(value);
        } else {
            current.add(last, value);
        }
    }

    /** Icon for an item id, #tag (cycles through the tag's entries), or fluid id. */
    static ItemStack iconFor(String ref, boolean fluid) {
        if (fluid) {
            if (ref.startsWith("#")) {
                ResourceLocation id = ResourceLocation.tryParse(ref.substring(1));
                if (id != null) {
                    var tag = BuiltInRegistries.FLUID.getTag(TagKey.create(Registries.FLUID, id));
                    if (tag.isPresent() && tag.get().size() > 0) {
                        int index = (int) ((System.currentTimeMillis() / 1000) % tag.get().size());
                        Item bucket = tag.get().get(index).value().getBucket();
                        if (bucket != Items.AIR) {
                            return new ItemStack(bucket);
                        }
                    }
                }
                return new ItemStack(Items.BUCKET);
            }
            ResourceLocation id = ResourceLocation.tryParse(ref);
            Fluid value = id == null ? null : BuiltInRegistries.FLUID.getOptional(id).orElse(null);
            Item bucket = value == null ? Items.AIR : value.getBucket();
            return new ItemStack(bucket == Items.AIR ? Items.BUCKET : bucket);
        }
        if (ref.startsWith("#")) {
            ResourceLocation id = ResourceLocation.tryParse(ref.substring(1));
            if (id != null) {
                var tag = BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, id));
                if (tag.isPresent() && tag.get().size() > 0) {
                    int index = (int) ((System.currentTimeMillis() / 1000) % tag.get().size());
                    return new ItemStack(tag.get().get(index).value());
                }
            }
            return new ItemStack(Items.NAME_TAG);
        }
        ResourceLocation id = ResourceLocation.tryParse(ref);
        return BuiltInRegistries.ITEM.getOptional(id == null ? ResourceLocation.withDefaultNamespace("barrier") : id)
                .map(ItemStack::new)
                .orElseGet(() -> new ItemStack(Items.BARRIER));
    }
}
