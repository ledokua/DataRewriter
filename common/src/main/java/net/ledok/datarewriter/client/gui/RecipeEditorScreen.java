package net.ledok.datarewriter.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import net.ledok.datarewriter.client.DatarewriterClient;
import net.ledok.datarewriter.config.GuiRecipeSaver;
import net.ledok.datarewriter.platform.Network;
import net.ledok.datarewriter.network.RegistryPayloads;
import net.ledok.datarewriter.network.SaveRecipePayload;
import net.ledok.datarewriter.network.SuggestionPayloads;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
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
    /**
     * EDIT saves (the {@code datarewriter.editor.save} right); SUGGEST files a proposal to the server's suggestion
     * queue instead; VIEW only shows recipes; REVIEW is a staff member deciding on someone's suggestion.
     */
    private enum Mode { EDIT, SUGGEST, VIEW, REVIEW }

    private final Mode mode;
    /** Where Close returns to; null = back to the game. */
    private final Screen parent;
    private final List<EditorLayout> layouts;
    private EditorLayout layout;

    /** Slot and field values per recipe type id. */
    private static final class State {
        final Map<String, String[]> refs = new HashMap<>();
        final Map<String, int[]> counts = new HashMap<>();
        final Map<String, int[]> chances = new HashMap<>();
        /** Raw components JSON per result slot (null = none). */
        final Map<String, String[]> components = new HashMap<>();
        final Map<String, Map<String, String>> fields = new HashMap<>();
    }

    // The editor's own state is static, so closing and reopening the editor
    // restores the last type, slots, amounts and field values (kept until the
    // game quits). A review works on states of its own.
    private static final State SHARED = new State();
    private static String lastTypeId = "";
    private State state = SHARED;

    private String idText = "";
    private Component status = Component.empty();
    /** Set by a suggestion's review; null otherwise. */
    private SuggestionReview review;

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
        final Component text;

        EditorError(Component text) {
            super(text.getString());
            this.text = text;
        }
    }

    /** {@code /recipeeditor}: saves with the save right, else suggests (or only shows recipes). */
    public RecipeEditorScreen() {
        this(null, DatarewriterClient.maySave() ? Mode.EDIT : DatarewriterClient.maySuggest() ? Mode.SUGGEST : Mode.VIEW);
    }

    private RecipeEditorScreen(Screen parent, Mode mode) {
        // EMI (and REI/JEI) only attach their overlays to container screens,
        // so the editor is one — with a slot-less, client-only menu that never
        // talks to the server (all container packet paths are neutralized
        // below).
        super(new EditorMenu(0, Minecraft.getInstance().player.getInventory()),
                Minecraft.getInstance().player.getInventory(),
                EditorText.tr(switch (mode) {
                    case EDIT -> "editor.title";
                    case SUGGEST -> "editor.title.suggest";
                    case VIEW -> "editor.title.view";
                    case REVIEW -> "editor.title.review";
                }));
        this.mode = mode;
        this.parent = parent;
        // Registry entries (rituals) are for staff only: suggestions are about recipes.
        List<EditorLayout> all = EditorLayouts.loadAll();
        this.layouts = mode == Mode.EDIT ? all : all.stream().filter(l -> l.registryTarget == null).toList();
        EditorLayout remembered = null;
        for (EditorLayout candidate : layouts) {
            if (candidate.typeId.equals(lastTypeId)) {
                remembered = candidate;
                break;
            }
        }
        this.layout = remembered != null ? remembered : layouts.getFirst();
        if (mode != Mode.REVIEW) {
            lastTypeId = this.layout.typeId;
        }
    }

    /**
     * The editor in suggest mode, returning to {@code parent} on Close — Eden's «Звернення» form, a recipe
     * viewer. Players with the save right get the normal editor.
     */
    public static RecipeEditorScreen forSuggestion(Screen parent) {
        return new RecipeEditorScreen(parent, DatarewriterClient.maySave() ? Mode.EDIT
                : DatarewriterClient.maySuggest() ? Mode.SUGGEST : Mode.VIEW);
    }

    /** {@link #forSuggestion} with {@code recipe} loaded, ready to propose a change to it or its removal. */
    public static RecipeEditorScreen suggestChange(Screen parent, RecipeHolder<?> recipe) {
        RecipeEditorScreen screen = forSuggestion(parent);
        screen.loadRecipe(recipe);
        return screen;
    }

    /** A staff member reviewing a suggestion (opened from Eden's Moderation window). */
    public static RecipeEditorScreen review(Screen parent, SuggestionPayloads.Review payload) {
        RecipeEditorScreen screen = new RecipeEditorScreen(parent, Mode.REVIEW);
        screen.startReview(payload);
        return screen;
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
        String[] refs = state.refs.get(layout.typeId);
        if (refs == null || refs.length != layout.slots.size()) {
            refs = new String[layout.slots.size()];
            state.refs.put(layout.typeId, refs);
        }
        return refs;
    }

    private int[] counts() {
        int[] counts = state.counts.get(layout.typeId);
        if (counts == null || counts.length != layout.slots.size()) {
            counts = new int[layout.slots.size()];
            for (int i = 0; i < counts.length; i++) {
                counts[i] = layout.slots.get(i).format().fluid() ? 1000 : 1;
            }
            state.counts.put(layout.typeId, counts);
        }
        return counts;
    }

    /** Drop chances in percent (1-100), for chanceable slots. */
    private int[] chances() {
        int[] chances = state.chances.get(layout.typeId);
        if (chances == null || chances.length != layout.slots.size()) {
            chances = new int[layout.slots.size()];
            java.util.Arrays.fill(chances, 100);
            state.chances.put(layout.typeId, chances);
        }
        return chances;
    }

    /** Components JSON text per slot, for slots where {@link #supportsComponents} is true. */
    private String[] components() {
        String[] components = state.components.get(layout.typeId);
        if (components == null || components.length != layout.slots.size()) {
            components = new String[layout.slots.size()];
            state.components.put(layout.typeId, components);
        }
        return components;
    }

    /**
     * Result slots writing a plain item stack can carry a {@code components} object; ingredient
     * slots can't (1.21.1 ingredients are item/tag only), and custom builders shape their own JSON.
     */
    private static boolean supportsComponents(SlotDef slot) {
        return slot.result() && slot.custom() == null
                && (slot.format() == SlotFormat.ITEM || slot.format() == SlotFormat.SHORTHAND_RESULT);
    }

    private Map<String, String> fieldValues() {
        return state.fields.computeIfAbsent(layout.typeId, k -> new HashMap<>());
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
        if (review != null) {
            initReviewPanel();
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
        int totalH = 24 + layout.height + 6 + 22 + fieldRows.size() * 22 + 24 + 14 + (review != null ? 22 : 0);
        int top = Math.max(6, (height - totalH) / 2);

        addRenderableWidget(typeButton(left, top, contentW));
        panelTop = top + 24;
        invLeft = Math.max(4, left - INV_COLS * 18 - 16);
        invTop = Math.max(top, (height - INV_ROWS * 18) / 2);

        int y = panelTop + layout.height + 6;
        if (layout.registryTarget != null) {
            addRenderableWidget(idBox(left, y, contentW - 44));
            addRenderableWidget(loadEntryButton(left + contentW - 40, y, 40));
        } else {
            addRenderableWidget(idBox(left, y, contentW));
        }
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
        if (review != null) {
            addRenderableWidget(acceptButton(left, y, buttonW));
            addRenderableWidget(rejectButton(left + buttonW + 4, y, buttonW));
            addRenderableWidget(closeButton(left + (buttonW + 4) * 2, y, buttonW));
            y += 22;
            int halfW = (contentW - 4) / 2;
            addRenderableWidget(compareButton(left, y, halfW));
            addRenderableWidget(detailsButton(left + halfW + 4, y, halfW));
        } else {
            addRenderableWidget(saveButton(left, y, buttonW));
            addRenderableWidget(clearButton(left + buttonW + 4, y, buttonW));
            addRenderableWidget(closeButton(left + (buttonW + 4) * 2, y, buttonW));
        }

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
        if (layout.registryTarget != null) {
            addRenderableWidget(idBox(colX, y, colW - 44));
            addRenderableWidget(loadEntryButton(colX + colW - 40, y, 40));
        } else {
            addRenderableWidget(idBox(colX, y, colW));
        }
        y += 22;

        for (FieldDef field : layout.fields) {
            Component label = fieldLabel(field);
            fieldLabels.add(new FieldLabel(label, colX, y + 5));
            int labelW = font.width(label) + 5;
            addRenderableWidget(fieldBox(field, colX + labelW, y, Math.max(40, colW - labelW)));
            y += 22;
        }

        y += 2;
        int halfW = colW / 2 - 2;
        if (review != null) {
            addRenderableWidget(acceptButton(colX, y, halfW));
            addRenderableWidget(rejectButton(colX + colW / 2 + 2, y, halfW));
            y += 22;
            addRenderableWidget(compareButton(colX, y, halfW));
            addRenderableWidget(detailsButton(colX + colW / 2 + 2, y, halfW));
            y += 22;
            addRenderableWidget(closeButton(colX, y, colW));
        } else {
            addRenderableWidget(saveButton(colX, y, colW));
            y += 22;
            addRenderableWidget(clearButton(colX, y, halfW));
            addRenderableWidget(closeButton(colX + colW / 2 + 2, y, halfW));
        }
        y += 20;

        statusY = Math.max(panelTop + layout.height, y) + 6;

        uiLeft = panelLeft - 4;
        uiTop = top - 4;
        uiRight = colX + colW + 4;
        uiBottom = statusY + 14;
    }

    private Button typeButton(int x, int y, int buttonWidth) {
        Button button = Button.builder(EditorText.layoutName(layout), b -> {
                    assert minecraft != null;
                    minecraft.setScreen(new TypePickerScreen(this, layouts, chosen -> {
                        layout = chosen;
                        lastTypeId = chosen.typeId;
                        status = Component.empty();
                    }));
                })
                .bounds(x, y, buttonWidth, 20)
                .tooltip(Tooltip.create(EditorText.tr("editor.type.tooltip")))
                .build();
        // A suggestion is reviewed as sent; its type isn't up for change.
        button.active = review == null;
        return button;
    }

    private EditBox idBox(int x, int y, int boxW) {
        EditBox box = new EditBox(font, x, y, boxW, 18, EditorText.tr("editor.id"));
        box.setMaxLength(256);
        box.setHint(EditorText.tr(switch (mode) {
            case SUGGEST -> "editor.id.hint.suggest";
            case REVIEW -> "editor.id.hint.review";
            default -> "editor.id.hint";
        }).withStyle(ChatFormatting.DARK_GRAY));
        box.setValue(idText);
        box.setResponder(text -> idText = text);
        if (review != null && review.kind.equals("remove")) {
            box.setEditable(false);
        }
        return box;
    }

    private Component fieldLabel(FieldDef field) {
        return EditorText.fieldLabel(field.label()).append(field.required() ? "*" : "")
                .withStyle(ChatFormatting.GRAY);
    }

    private EditBox fieldBox(FieldDef field, int x, int y, int boxW) {
        EditBox box = new EditBox(font, x, y, boxW, 18, EditorText.fieldLabel(field.label()));
        box.setMaxLength(256);
        box.setValue(fieldValue(field));
        box.setResponder(text -> fieldValues().put(field.path(), text));
        if (!field.suggestions().isEmpty()) {
            box.setTooltip(Tooltip.create(EditorText.tr("editor.field.known_values",
                    String.join(", ", field.suggestions()))));
        }
        return box;
    }

    private Button clearButton(int x, int y, int buttonWidth) {
        return Button.builder(EditorText.tr("editor.clear"), b -> clearSlots())
                .tooltip(Tooltip.create(EditorText.tr("editor.clear.tooltip")))
                .bounds(x, y, buttonWidth, 20)
                .build();
    }

    private Button closeButton(int x, int y, int buttonWidth) {
        return Button.builder(EditorText.tr("editor.close"), b -> onClose()).bounds(x, y, buttonWidth, 20).build();
    }

    /** Save in EDIT, «Suggest…» in SUGGEST, a disabled Save in VIEW. */
    private Button saveButton(int x, int y, int buttonWidth) {
        if (mode == Mode.SUGGEST) {
            return Button.builder(EditorText.tr("editor.suggest"), b -> suggest())
                    .tooltip(Tooltip.create(EditorText.tr("editor.suggest.tooltip")))
                    .bounds(x, y, buttonWidth, 20).build();
        }
        Button button = Button.builder(EditorText.tr("editor.save"), b -> save()).bounds(x, y, buttonWidth, 20).build();
        if (mode == Mode.VIEW) {
            button.active = false;
            button.setTooltip(Tooltip.create(EditorText.tr("editor.view_only")));
        }
        return button;
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
        minecraft.setScreen(parent);
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
            int w = font.width(fieldLabel(field)) + 5 + boxWidth(field) + 12;
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
                // Empty slots by role: results green, inputs blue; a required one gets an orange frame.
                graphics.fill(x, y, x + 16, y + 16, slot.result() ? 0x4455FF55 : 0x445599FF);
                if (requiredMissing(i) && review == null) {
                    graphics.renderOutline(x - 1, y - 1, 18, 18, 0xFFFF9900);
                }
            }
            boolean hover = mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16;
            if (hover) {
                hoveredSlot = i;
                graphics.fill(x, y, x + 16, y + 16, 0x66FFFFFF);
            }
            if (refs[i] != null) {
                ItemStack icon = iconFor(refs[i], slot.format().fluid());
                if (components()[i] != null && supportsComponents(slot)) {
                    icon = componentIcon(i, icon);
                }
                graphics.renderItem(icon, x, y);
                String decoration = decorationFor(slot, refs[i], counts[i], chances()[i]);
                if (decoration != null) {
                    graphics.renderItemDecorations(font, icon, x, y, decoration);
                }
            }
        }

        renderInventory(graphics, mouseX, mouseY);
        renderReviewPanel(graphics);

        for (FieldLabel label : fieldLabels) {
            graphics.drawString(font, label.text(), label.x(), label.y(), 0xA0A0A0);
        }
        Component shown = status.getString().isEmpty() && review == null ? requiredHint() : status;
        if (!shown.getString().isEmpty()) {
            List<net.minecraft.util.FormattedCharSequence> lines = font.split(shown,
                    Math.min(width - 40, Math.max(200, uiRight - uiLeft)));
            for (int i = 0; i < Math.min(2, lines.size()); i++) {
                graphics.drawCenteredString(font, lines.get(i), width / 2, statusY + i * 10, 0xFFFFFF);
            }
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
            graphics.drawString(font, EditorText.tr("editor.inventory"),
                    panelLeft + 8 - layout.u, panelTop + layout.inventoryY - layout.v - 12, 0x404040, false);
        } else {
            graphics.drawString(font, EditorText.tr("editor.inventory").withStyle(ChatFormatting.GRAY),
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
                    EditorText.tr("editor.inventory.pick").withStyle(ChatFormatting.GRAY),
                    EditorText.tr("editor.inventory.tags").withStyle(ChatFormatting.GRAY)),
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

    /** A slot's label in the player's language (see {@link #slotLabelEnglish}). */
    private static Component slotLabel(SlotDef slot) {
        return EditorText.slotLabel(slotLabelEnglish(slot));
    }

    /** Human label for a slot, derived from its JSON field ("base_fluid" -> "Base fluid"). */
    private static String slotLabelEnglish(SlotDef slot) {
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
        // camelCase paths too: "pedestalItems" -> "Pedestal items"
        String spaced = path.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private void renderSlotTooltip(GuiGraphics graphics, int index, int mouseX, int mouseY) {
        SlotDef slot = layout.slots.get(index);
        String ref = refs()[index];
        List<Component> lines = new ArrayList<>();
        Component role = EditorText.tr(slot.result() ? "editor.slot.role.result" : "editor.slot.role.input")
                .withStyle(slot.result() ? ChatFormatting.GREEN : ChatFormatting.AQUA);
        if (ref == null) {
            lines.add(slotLabel(slot).copy()
                    .append(slot.required() ? EditorText.tr("editor.slot.required") : Component.empty())
                    .withStyle(ChatFormatting.WHITE));
            lines.add(role);
            lines.add(EditorText.tr("editor.slot.choose").withStyle(ChatFormatting.GRAY));
            if (slot.acceptsTags() && !slot.format().fluid()) {
                lines.add(EditorText.tr("editor.slot.tags").withStyle(ChatFormatting.GRAY));
            }
        } else {
            lines.add(slotLabel(slot).copy().append(": ").withStyle(ChatFormatting.GRAY)
                    .append(iconFor(ref, slot.format().fluid()).getHoverName()));
            lines.add(Component.literal(ref).withStyle(ChatFormatting.DARK_GRAY));
            lines.add(role);
            if (slot.format().fluid()) {
                lines.add(EditorText.tr("editor.slot.mb", counts()[index]).withStyle(ChatFormatting.AQUA));
            }
            if (slot.chanceable()) {
                int chance = chances()[index];
                lines.add(EditorText.tr(chance == 0 && !slot.result() ? "editor.slot.chance.never_consumed"
                        : "editor.slot.chance", chance).withStyle(ChatFormatting.AQUA));
            }
            if (slot.acceptsTags() && !slot.format().fluid() && !ref.startsWith("#")) {
                lines.add(EditorText.tr("editor.slot.to_tag").withStyle(ChatFormatting.GRAY));
            }
            if (supportsComponents(slot)) {
                lines.add(EditorText.tr(components()[index] != null
                        ? "editor.slot.components.edit" : "editor.slot.components.add")
                        .withStyle(components()[index] != null
                                ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.GRAY));
            }
            lines.add(EditorText.tr("editor.slot.clear").withStyle(ChatFormatting.GRAY));
        }
        if (slot.format().counted && layout.kind == Kind.SLOTS) {
            lines.add(EditorText.tr(slot.format().fluid() ? "editor.slot.scroll.fluid" : "editor.slot.scroll.count")
                    .withStyle(ChatFormatting.GRAY));
        }
        if (slot.chanceable()) {
            lines.add(EditorText.tr("editor.slot.scroll.chance").withStyle(ChatFormatting.GRAY));
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
            Runnable editComponents = supportsComponents(slot)
                    ? () -> minecraft.setScreen(new ComponentsEditScreen(this, refs()[slotIndex],
                            components()[slotIndex], obj -> {
                        components()[slotIndex] = obj == null ? null : obj.toString();
                        componentIconCache.remove(slotIndex);
                    }))
                    : null;
            if (countEditable) {
                // Middle-click: type an exact amount instead of scrolling (plus the
                // slot's other actions — tag conversion, components).
                boolean fluid = slot.format().fluid();
                Component label = slotLabel(slot).copy().append(EditorText.tr(fluid ? "editor.amount.mb" : "editor.amount.count"));
                int max = fluid ? 1_000_000 : 99;
                minecraft.setScreen(new AmountInputScreen(this, label, counts()[index], 1, max,
                        amount -> counts()[slotIndex] = amount,
                        toTag == null ? null : EditorText.tr("editor.amount.to_tag"), toTag,
                        editComponents == null ? null : EditorText.tr("editor.amount.components"),
                        editComponents));
                return true;
            }
            if (toTag != null) {
                toTag.run();
                return true;
            }
            if (editComponents != null) {
                editComponents.run();
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

    /**
     * EMI stack lookups (R/U). The editor menu has no real slots, so EMI finds
     * nothing on its own: recipe slots answer with their ref, the inventory
     * panel with the actual stack under the cursor.
     */
    public Object emiStackAt(int x, int y) {
        int index = slotAt(x, y);
        if (index >= 0) {
            String ref = refs()[index];
            return layout.slots.get(index).format().fluid()
                    ? EmiHover.fluid(ref) : EmiHover.itemOrTag(ref);
        }
        int slot = invSlotAt(x, y);
        if (slot >= 0 && minecraft != null && minecraft.player != null) {
            ItemStack stack = minecraft.player.getInventory().items.get(slot);
            return stack.isEmpty() ? null : stack;
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
                chances[index] = Math.max(layout.minChance, Math.min(100,
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
            status = e.text.copy().withStyle(ChatFormatting.RED);
            return;
        }
        if (layout.registryTarget != null) {
            String entryId = idText.trim();
            if (ResourceLocation.tryParse(entryId) == null || entryId.isEmpty()) {
                status = EditorText.tr("editor.entry.id_needed").withStyle(ChatFormatting.RED);
                return;
            }
            if (!Network.INSTANCE.canSendToServer(RegistryPayloads.SaveEntry.TYPE)) {
                status = EditorText.tr("editor.no_server").withStyle(ChatFormatting.RED);
                return;
            }
            Network.INSTANCE.sendToServer(new RegistryPayloads.SaveEntry(
                    layout.registryTarget, entryId, recipe.toString()));
            status = EditorText.tr("editor.sent").withStyle(ChatFormatting.YELLOW);
            return;
        }
        if (!Network.INSTANCE.canSendToServer(SaveRecipePayload.TYPE)) {
            status = EditorText.tr("editor.no_server").withStyle(ChatFormatting.RED);
            return;
        }
        Network.INSTANCE.sendToServer(new SaveRecipePayload(recipe.toString()));
        // The editor stays open for the next recipe; the server's confirmation
        // (or rejection) arrives in chat.
        status = EditorText.tr("editor.sent").withStyle(ChatFormatting.YELLOW);
    }

    /** Server's answer to the last save (also in chat). */
    public void onSaveResult(boolean ok, String message) {
        status = Component.literal((ok ? "✔ " : "✘ ") + message)
                .withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    // ------------------------------------------------------------------ suggest mode

    /** «Suggest…»: checks the recipe, then asks what kind of proposal it is and why. */
    private void suggest() {
        status = Component.empty();
        if (!Network.INSTANCE.canSendToServer(SuggestionPayloads.Suggest.TYPE)) {
            status = EditorText.tr("editor.no_server").withStyle(ChatFormatting.RED);
            return;
        }
        String id = idText.trim();
        ResourceLocation existing = id.isEmpty() ? null : ResourceLocation.tryParse(id);
        assert minecraft != null && minecraft.level != null;
        if (existing != null && minecraft.level.getRecipeManager().byKey(existing).isEmpty()) {
            existing = null; // an id nothing has yet: a new recipe
        }
        Component buildError = null;
        try {
            buildRecipe();
        } catch (EditorError e) {
            buildError = e.text;
        }
        if (buildError != null && existing == null) {
            status = buildError.copy().withStyle(ChatFormatting.RED);
            return;
        }
        String recipeId = existing == null ? null : existing.toString();
        minecraft.setScreen(new SuggestScreen(this, recipeId, buildError, proposalSummary(), proposalWarnings(),
                this::sendSuggestion));
    }

    /**
     * Under the editor while nothing else is said: which parts this recipe type needs (orange-framed slots, starred
     * fields) and that blue slots are inputs, green ones results.
     */
    private Component requiredHint() {
        List<String> fields = new ArrayList<>();
        for (FieldDef field : layout.fields) {
            if (field.required() && field.saved()) {
                fields.add(EditorText.fieldLabel(field.label()).getString());
            }
        }
        boolean requiredSlots = layout.slots.stream().anyMatch(SlotDef::required);
        String key = requiredSlots ? (fields.isEmpty() ? "editor.required.hint" : "editor.required.hint.fields")
                : (fields.isEmpty() ? "editor.required.hint.none" : "editor.required.hint.fields_only");
        return EditorText.tr(key, String.join(", ", fields)).withStyle(ChatFormatting.GRAY);
    }

    /**
     * A required slot left empty. A required slot of a list ("ingredients[]") stands for "at least one entry": any
     * filled slot of the same list and role satisfies it, so inputs can go in any of the list's slots.
     */
    private boolean requiredMissing(int index) {
        SlotDef slot = layout.slots.get(index);
        String[] refs = refs();
        if (!slot.required() || refs[index] != null) {
            return false;
        }
        if (slot.path().endsWith("[]")) {
            for (int i = 0; i < layout.slots.size(); i++) {
                SlotDef other = layout.slots.get(i);
                if (refs[i] != null && other.path().equals(slot.path()) && other.result() == slot.result()) {
                    return false;
                }
            }
        }
        return true;
    }

    /** What the suggestion would send, for the form: the filled inputs, then the results. */
    private List<Component> proposalSummary() {
        List<String> inputs = new ArrayList<>();
        List<String> results = new ArrayList<>();
        for (int i = 0; i < layout.slots.size(); i++) {
            String text = slotText(state, layout, i);
            if (!text.equals("—")) {
                (layout.slots.get(i).result() ? results : inputs).add(text);
            }
        }
        return List.of(
                EditorText.tr("suggest.summary.inputs", inputs.isEmpty() ? "—" : String.join(", ", inputs))
                        .withStyle(ChatFormatting.AQUA),
                EditorText.tr("suggest.summary.results", results.isEmpty() ? "—" : String.join(", ", results))
                        .withStyle(ChatFormatting.GREEN));
    }

    /**
     * Shapes that are probably a misunderstanding of the layout: an item that is both an input and a result, several
     * results, or every input slot filled. The form asks for a second click before sending these.
     */
    private List<Component> proposalWarnings() {
        String[] refs = refs();
        Set<String> inputs = new HashSet<>();
        int inputSlots = 0;
        int inputsFilled = 0;
        int resultsFilled = 0;
        for (int i = 0; i < layout.slots.size(); i++) {
            if (!layout.slots.get(i).result()) {
                inputSlots++;
                if (refs[i] != null) {
                    inputsFilled++;
                    inputs.add(refs[i]);
                }
            } else if (refs[i] != null) {
                resultsFilled++;
            }
        }
        List<Component> warnings = new ArrayList<>();
        for (int i = 0; i < layout.slots.size(); i++) {
            if (layout.slots.get(i).result() && refs[i] != null && inputs.contains(refs[i])) {
                warnings.add(EditorText.tr("suggest.warn.same_item",
                        iconFor(refs[i], layout.slots.get(i).format().fluid()).getHoverName()));
                break;
            }
        }
        if (resultsFilled > 1) {
            warnings.add(EditorText.tr("suggest.warn.many_results", resultsFilled));
        }
        if (inputSlots > 3 && inputsFilled == inputSlots) {
            warnings.add(EditorText.tr("suggest.warn.all_inputs"));
        }
        return warnings;
    }

    /** {@link SuggestScreen}'s Send: {@code kind} is "new", "edit" or "remove". */
    private void sendSuggestion(String kind, String text) {
        String json = "";
        if (!kind.equals("remove")) {
            try {
                JsonObject recipe = buildRecipe();
                recipe.remove("id");
                json = recipe.toString();
            } catch (EditorError e) {
                status = e.text.copy().withStyle(ChatFormatting.RED);
                return;
            }
        }
        if (json.length() > SuggestionPayloads.MAX_RECIPE_CHARS) {
            status = EditorText.tr("editor.suggest.too_big").withStyle(ChatFormatting.RED);
            return;
        }
        Network.INSTANCE.sendToServer(new SuggestionPayloads.Suggest(kind, kind.equals("new") ? "" : idText.trim(),
                json, firstResult(), text));
        status = EditorText.tr("editor.sent").withStyle(ChatFormatting.YELLOW);
    }

    /** The first result slot's item, the server's hint for recipes whose result it can't read. */
    private String firstResult() {
        String[] refs = refs();
        for (int i = 0; i < layout.slots.size(); i++) {
            if (layout.slots.get(i).result() && refs[i] != null && !refs[i].startsWith("#")) {
                return refs[i];
            }
        }
        return "";
    }

    /** The server's answer to a suggestion or a review decision (also in chat). */
    public void onSuggestionResult(boolean ok, String key, List<String> args) {
        status = Component.literal(ok ? "✔ " : "✘ ")
                .append(EditorText.tr(key, args.toArray()))
                .withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.RED);
        if (ok && review != null && key.startsWith("review.")) {
            review.decided = true;
            rebuildWidgets();
        }
    }

    // ------------------------------------------------------------------ review mode

    /** A suggestion under review: what was proposed, what the recipe is now, and what changed. */
    private static final class SuggestionReview {
        long id;
        String author;
        String text;
        /** "new", "edit" or "remove". */
        String kind;
        String recipeId;
        String output;
        /** The proposal (null for a removal), the recipe when it was suggested, and now. */
        JsonObject proposed;
        JsonObject base;
        JsonObject current;
        String definedIn;
        String fixedId;
        boolean canAccept;
        /** Accepting can't work (a removal of a recipe a config file adds). */
        boolean blocked;
        boolean decided;
        EditorLayout proposalLayout;
        State proposalState;
        EditorLayout currentLayout;
        State currentState;
        boolean showingCurrent;
        final List<Component> lines = new ArrayList<>();
    }

    private int infoX;
    private int infoY;
    private int infoW;
    private int infoH;

    private void startReview(SuggestionPayloads.Review payload) {
        SuggestionReview r = new SuggestionReview();
        JsonObject data = parseObject(payload.payload());
        JsonObject context = parseObject(payload.context());
        r.id = payload.id();
        r.author = payload.author();
        r.text = payload.text();
        r.kind = stringOr(data, "kind", "new");
        r.recipeId = stringOr(data, "recipe_id", null);
        r.output = stringOr(data, "output", "?");
        r.proposed = data.get("proposed") instanceof JsonObject o ? o : null;
        r.base = data.get("base") instanceof JsonObject o ? o : null;
        r.current = context.get("current") instanceof JsonObject o ? o : null;
        r.definedIn = stringOr(context, "defined_in", null);
        r.fixedId = stringOr(context, "fixed_id", "");
        r.canAccept = context.get("can_accept") instanceof JsonPrimitive p && p.isBoolean() && p.getAsBoolean();
        review = r;

        JsonObject before = r.current != null ? r.current : r.base;
        JsonObject shown = r.kind.equals("remove") ? before : r.proposed;
        r.proposalLayout = shown == null ? null : layoutFor(shown);
        r.proposalState = r.proposalLayout == null ? new State() : loadInto(r.proposalLayout, shown);
        if (r.proposalLayout != null) {
            layout = r.proposalLayout;
        }
        state = r.proposalState;
        if (!r.kind.equals("remove") && before != null) {
            r.currentLayout = layoutFor(before);
            if (r.currentLayout != null) {
                r.currentState = loadInto(r.currentLayout, before);
            }
        }
        idText = r.kind.equals("new") ? r.fixedId : r.recipeId == null ? "" : r.recipeId;
        describe(r, before, shown);
    }

    /** The panel's text: who and what, the author's words, what changed, and what to watch out for. */
    private void describe(SuggestionReview r, JsonObject before, JsonObject shown) {
        List<Component> lines = r.lines;
        lines.add(EditorText.tr("review.header", "#" + r.id, r.author).withStyle(ChatFormatting.GOLD));
        lines.add(EditorText.tr("review.kind." + r.kind, r.recipeId == null ? r.output : r.recipeId)
                .withStyle(ChatFormatting.YELLOW));
        lines.add(Component.literal("“" + r.text + "”").withStyle(ChatFormatting.WHITE));
        lines.add(Component.empty());
        switch (r.kind) {
            case "remove" -> lines.add(EditorText.tr("review.removal").withStyle(ChatFormatting.RED));
            case "new" -> lines.add(EditorText.tr("review.new_recipe").withStyle(ChatFormatting.AQUA));
            default -> {
                lines.add(EditorText.tr("review.changes").withStyle(ChatFormatting.GRAY));
                int before0 = lines.size();
                diff(r, lines);
                if (lines.size() == before0) {
                    lines.add(EditorText.tr("review.no_visible_changes").withStyle(ChatFormatting.DARK_GRAY));
                }
            }
        }
        lines.add(Component.empty());
        if (!r.kind.equals("new") && r.current == null) {
            lines.add(EditorText.tr("review.warn.gone").withStyle(ChatFormatting.RED));
        } else if (r.base != null && r.current != null && !r.base.equals(r.current)) {
            lines.add(EditorText.tr("review.warn.changed_since").withStyle(ChatFormatting.YELLOW));
        }
        if (r.definedIn != null && !r.definedIn.equals(GuiRecipeSaver.FILE_NAME)) {
            if (r.kind.equals("remove")) {
                r.blocked = true;
                lines.add(EditorText.tr("review.warn.remove_in_file", r.definedIn).withStyle(ChatFormatting.RED));
            } else {
                lines.add(EditorText.tr("review.warn.defined_in", r.definedIn).withStyle(ChatFormatting.YELLOW));
            }
        }
        if (shown != null && r.proposalLayout == null) {
            lines.add(EditorText.tr("review.warn.no_layout", stringOr(shown, "type", "?"))
                    .withStyle(ChatFormatting.YELLOW));
        }
        if (!r.canAccept) {
            lines.add(EditorText.tr("review.warn.no_save_right").withStyle(ChatFormatting.YELLOW));
        }
        lines.add(EditorText.tr("review.writes_to", GuiRecipeSaver.FILE_NAME).withStyle(ChatFormatting.DARK_GRAY));
    }

    /** Slot by slot and field by field, the recipe now against the proposal (same layout) — or the type change. */
    private void diff(SuggestionReview r, List<Component> lines) {
        if (r.currentLayout == null || r.proposalLayout == null) {
            return;
        }
        if (r.currentLayout != r.proposalLayout) {
            lines.add(EditorText.tr("review.type_changed", EditorText.layoutName(r.currentLayout),
                    EditorText.layoutName(r.proposalLayout)).withStyle(ChatFormatting.AQUA));
            return;
        }
        EditorLayout l = r.proposalLayout;
        int grid = l.kind == Kind.SLOTS ? 0 : l.gridSize * l.gridSize;
        for (int i = 0; i < l.slots.size(); i++) {
            SlotDef slot = l.slots.get(i);
            String was = slotText(r.currentState, l, i);
            String now = slotText(r.proposalState, l, i);
            if (!was.equals(now)) {
                Component name = i < grid && !slot.result()
                        ? EditorText.tr("review.grid", i / l.gridSize + 1, i % l.gridSize + 1)
                        : slotLabel(slot);
                lines.add(name.copy().append(": " + was + " → " + now).withStyle(ChatFormatting.AQUA));
            }
        }
        for (FieldDef field : l.fields) {
            String was = fieldText(r.currentState, l, field);
            String now = fieldText(r.proposalState, l, field);
            if (!was.equals(now)) {
                lines.add(EditorText.fieldLabel(field.label()).append(": " + was + " → " + now)
                        .withStyle(ChatFormatting.AQUA));
            }
        }
    }

    /** A slot's content for the diff: name, amount, chance; "—" when empty. */
    private static String slotText(State st, EditorLayout l, int index) {
        String[] refs = st.refs.get(l.typeId);
        String ref = refs == null || index >= refs.length ? null : refs[index];
        if (ref == null) {
            return "—";
        }
        boolean fluid = l.slots.get(index).format().fluid();
        StringBuilder text = new StringBuilder(ref.startsWith("#") ? ref : iconFor(ref, fluid).getHoverName().getString());
        int[] counts = st.counts.get(l.typeId);
        int count = counts == null || index >= counts.length ? 1 : counts[index];
        if (fluid) {
            text.append(' ').append(count).append(" mB");
        } else if (count > 1) {
            text.append(" ×").append(count);
        }
        int[] chances = st.chances.get(l.typeId);
        if (chances != null && index < chances.length && chances[index] < 100) {
            text.append(" (").append(chances[index]).append("%)");
        }
        String[] components = st.components.get(l.typeId);
        if (components != null && index < components.length && components[index] != null) {
            text.append(" +").append(components[index]);
        }
        return text.toString();
    }

    private static String fieldText(State st, EditorLayout l, FieldDef field) {
        Map<String, String> values = st.fields.get(l.typeId);
        String value = values == null ? null : values.get(field.path());
        value = value == null || value.isBlank() ? field.defaultValue() : value.trim();
        return value.isEmpty() ? "—" : value;
    }

    /** The first layout for the recipe JSON's type, or null. */
    private EditorLayout layoutFor(JsonObject recipe) {
        String type = stringOr(recipe, "type", "");
        for (EditorLayout candidate : layouts) {
            if (candidate.typeId.equals(type)) {
                return candidate;
            }
        }
        return null;
    }

    /** Reads {@code recipe} into a fresh state for {@code target}, leaving the screen's own untouched. */
    private State loadInto(EditorLayout target, JsonObject recipe) {
        State savedState = state;
        EditorLayout savedLayout = layout;
        State scratch = new State();
        state = scratch;
        layout = target;
        try {
            JsonObject copy = recipe.deepCopy();
            fillFromJson(target.prepareLoad != null ? target.prepareLoad.apply(copy) : copy);
        } finally {
            state = savedState;
            layout = savedLayout;
        }
        return scratch;
    }

    /** The info panel goes right of the editor when there is room; Details shows it in full anyway. */
    private void initReviewPanel() {
        infoX = uiRight + 6;
        infoW = Math.min(220, width - infoX - 6);
        if (infoW < 120) {
            infoW = 0;
            return;
        }
        infoY = uiTop + 2;
        infoH = Math.max(80, uiBottom - uiTop - 4);
        uiRight = infoX + infoW + 3;
    }

    private void renderReviewPanel(GuiGraphics graphics) {
        if (review == null || infoW == 0) {
            return;
        }
        graphics.fill(infoX - 3, infoY - 3, infoX + infoW + 3, infoY + infoH + 3, 0xC0101010);
        int y = infoY;
        int bottom = infoY + infoH - 9;
        for (Component line : review.lines) {
            for (net.minecraft.util.FormattedCharSequence part : font.split(line, infoW)) {
                if (y > bottom - 10) {
                    graphics.drawString(font, EditorText.tr("review.more").withStyle(ChatFormatting.DARK_GRAY),
                            infoX, y, 0xFFFFFF);
                    return;
                }
                graphics.drawString(font, part, infoX, y, 0xFFFFFF);
                y += 10;
            }
        }
    }

    private Button acceptButton(int x, int y, int buttonWidth) {
        Button button = Button.builder(EditorText.tr("review.accept"), b -> {
                    assert minecraft != null;
                    minecraft.setScreen(new DecisionScreen(this, true, review.id, reason -> decide(true, reason)));
                })
                .bounds(x, y, buttonWidth, 20)
                .tooltip(Tooltip.create(EditorText.tr("review.accept.tooltip", GuiRecipeSaver.FILE_NAME)))
                .build();
        button.active = !review.decided && review.canAccept && !review.blocked && !review.showingCurrent;
        return button;
    }

    private Button rejectButton(int x, int y, int buttonWidth) {
        Button button = Button.builder(EditorText.tr("review.reject"), b -> {
                    assert minecraft != null;
                    minecraft.setScreen(new DecisionScreen(this, false, review.id, reason -> decide(false, reason)));
                })
                .bounds(x, y, buttonWidth, 20)
                .build();
        button.active = !review.decided && !review.showingCurrent;
        return button;
    }

    /** Swaps the panel between the proposal and the recipe as it is now. */
    private Button compareButton(int x, int y, int buttonWidth) {
        Button button = Button.builder(EditorText.tr(review.showingCurrent ? "review.show_proposal"
                        : "review.show_current"), b -> {
                    review.showingCurrent = !review.showingCurrent;
                    layout = review.showingCurrent ? review.currentLayout : review.proposalLayout;
                    state = review.showingCurrent ? review.currentState : review.proposalState;
                    status = review.showingCurrent
                            ? EditorText.tr("review.showing_current").withStyle(ChatFormatting.YELLOW)
                            : Component.empty();
                    rebuildWidgets();
                })
                .bounds(x, y, buttonWidth, 20)
                .build();
        button.active = review.currentState != null && review.proposalLayout != null;
        return button;
    }

    private Button detailsButton(int x, int y, int buttonWidth) {
        return Button.builder(EditorText.tr("review.details"), b -> {
                    assert minecraft != null;
                    minecraft.setScreen(new TextLinesScreen(this, EditorText.tr("review.details.title", "#" + review.id),
                            review.lines));
                })
                .bounds(x, y, buttonWidth, 20)
                .build();
    }

    /** {@link DecisionScreen}'s confirm: sends the verdict with the (possibly edited) recipe. */
    private void decide(boolean accept, String reason) {
        SuggestionReview r = review;
        String recipeId = "";
        String json = "";
        if (accept) {
            if (r.kind.equals("remove")) {
                recipeId = r.recipeId == null ? "" : r.recipeId;
            } else {
                JsonObject recipe;
                if (r.proposalLayout == null) {
                    recipe = r.proposed.deepCopy(); // no layout to edit it with: as proposed
                } else {
                    try {
                        recipe = buildRecipe();
                    } catch (EditorError e) {
                        status = e.text.copy().withStyle(ChatFormatting.RED);
                        return;
                    }
                }
                String id = idText.trim();
                if (id.isEmpty() || ResourceLocation.tryParse(id) == null) {
                    status = EditorText.tr("review.id_needed").withStyle(ChatFormatting.RED);
                    return;
                }
                recipe.addProperty("id", id);
                json = recipe.toString();
                if (json.length() > SuggestionPayloads.MAX_RECIPE_CHARS) {
                    status = EditorText.tr("editor.suggest.too_big").withStyle(ChatFormatting.RED);
                    return;
                }
            }
        }
        if (!Network.INSTANCE.canSendToServer(SuggestionPayloads.Decision.TYPE)) {
            status = EditorText.tr("editor.no_server").withStyle(ChatFormatting.RED);
            return;
        }
        Network.INSTANCE.sendToServer(new SuggestionPayloads.Decision(r.id, accept, recipeId, json, reason));
        status = EditorText.tr("editor.sent").withStyle(ChatFormatting.YELLOW);
    }

    private static JsonObject parseObject(String json) {
        try {
            return JsonParser.parseString(json) instanceof JsonObject obj ? obj : new JsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private static String stringOr(JsonObject obj, String key, String fallback) {
        return obj.get(key) instanceof JsonPrimitive p && p.isString() ? p.getAsString() : fallback;
    }

    /** Per-slot icons with the slot's components applied (key: ref + components text). */
    private final Map<Integer, ItemStack> componentIconCache = new HashMap<>();
    private final Map<Integer, String> componentIconKey = new HashMap<>();

    /** The slot's icon with its data components applied, cached until ref or text changes. */
    private ItemStack componentIcon(int index, ItemStack base) {
        String key = refs()[index] + "|" + components()[index];
        if (key.equals(componentIconKey.get(index))) {
            ItemStack cached = componentIconCache.get(index);
            if (cached != null) {
                return cached;
            }
        }
        ItemStack applied = base.copy();
        assert minecraft != null;
        if (minecraft.level != null) {
            try {
                var ops = net.minecraft.resources.RegistryOps.create(
                        com.mojang.serialization.JsonOps.INSTANCE, minecraft.level.registryAccess());
                net.minecraft.core.component.DataComponentPatch.CODEC
                        .parse(ops, JsonParser.parseString(components()[index]))
                        .result().ifPresent(applied::applyComponents);
            } catch (Exception ignored) {
            }
        }
        componentIconKey.put(index, key);
        componentIconCache.put(index, applied);
        return applied;
    }

    private void clearSlots() {
        state.refs.remove(layout.typeId);
        state.counts.remove(layout.typeId);
        state.chances.remove(layout.typeId);
        state.components.remove(layout.typeId);
        cursorItem = null;
        cursorTag = null;
        status = Component.empty();
    }

    private JsonObject buildRecipe() {
        JsonObject recipe = layout.template != null ? layout.template.deepCopy() : new JsonObject();
        if (layout.registryTarget == null) {
            recipe.addProperty("type", layout.typeId);
            String id = idText.trim();
            if (!id.isEmpty()) {
                if (ResourceLocation.tryParse(id) == null) {
                    throw new EditorError(EditorText.tr("editor.error.bad_id", id));
                }
                recipe.addProperty("id", id);
            }
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
            if (layout.kind != Kind.SLOTS && !slot.result() && i < layout.gridSize * layout.gridSize) {
                continue; // grid inputs were turned into pattern/key or ingredients above
            }
            String ref = refs[i];
            if (ref == null) {
                if (requiredMissing(i)) {
                    throw new EditorError(EditorText.tr(slot.result() ? "editor.error.no_result"
                            : "editor.error.required_slot", slotLabel(slot)));
                }
                continue;
            }
            if (ref.startsWith("#") && !slot.acceptsTags()) {
                throw new EditorError(EditorText.tr("editor.error.no_tags", ref));
            }
            setAtPath(recipe, slot.path(), slotValue(slot, ref, counts[i], chances()[i] / 100f,
                    supportsComponents(slot) ? components()[i] : null));
        }

        for (FieldDef field : layout.fields) {
            if (!field.saved()) {
                continue; // editor-only: read by the layout's finisher, never written
            }
            String text = fieldValue(field).trim();
            if (text.isEmpty()) {
                if (field.required()) {
                    throw new EditorError(EditorText.tr("editor.error.field_required", EditorText.fieldLabel(field.label())));
                }
                continue;
            }
            JsonPrimitive value = switch (field.type()) {
                case INT -> {
                    try {
                        yield new JsonPrimitive(Long.parseLong(text));
                    } catch (NumberFormatException e) {
                        throw new EditorError(EditorText.tr("editor.error.field_int", EditorText.fieldLabel(field.label())));
                    }
                }
                case FLOAT -> {
                    try {
                        yield new JsonPrimitive(Double.parseDouble(text));
                    } catch (NumberFormatException e) {
                        throw new EditorError(EditorText.tr("editor.error.field_number", EditorText.fieldLabel(field.label())));
                    }
                }
                case STRING -> new JsonPrimitive(text);
                case BOOL -> {
                    if (!text.equalsIgnoreCase("true") && !text.equalsIgnoreCase("false")) {
                        throw new EditorError(EditorText.tr("editor.error.field_bool", EditorText.fieldLabel(field.label())));
                    }
                    yield new JsonPrimitive(Boolean.parseBoolean(text));
                }
            };
            setAtPath(recipe, field.path(), value);
        }
        if (layout.finisher != null) {
            try {
                layout.finisher.finish(recipe, slotView);
            } catch (EditorLayout.LayoutError e) {
                throw new EditorError(Component.translatableWithFallback(e.key, e.english, e.args));
            } catch (IllegalArgumentException e) {
                throw new EditorError(Component.literal(e.getMessage()));
            }
        }
        return layout.finishSave != null ? layout.finishSave.apply(recipe) : recipe;
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
            throw new EditorError(EditorText.tr("editor.error.empty_grid"));
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
        // Ingredient objects, not the bare-string shorthand: 1.21.1's Ingredient
        // codec only accepts {"item"}/{"tag"} (or a list of them), and the config
        // loader's shorthand expansion is a convenience for hand-written files.
        letters.forEach((ref, letter) -> key.add(String.valueOf(letter), ingredientObj(ref)));
        recipe.add("pattern", pattern);
        recipe.add("key", key);
    }

    private void buildShapeless(JsonObject recipe, String[] refs) {
        JsonArray ingredients = new JsonArray();
        for (int i = 0; i < layout.gridSize * layout.gridSize; i++) {
            if (refs[i] != null) {
                ingredients.add(ingredientObj(refs[i]));
            }
        }
        if (ingredients.isEmpty()) {
            throw new EditorError(EditorText.tr("editor.error.empty_grid"));
        }
        recipe.add("ingredients", ingredients);
    }

    private static JsonElement slotValue(SlotDef slot, String ref, int count, float chance,
                                         String components) {
        if (slot.custom() != null) {
            return slot.custom().build(ref, count, chance);
        }
        SlotFormat format = slot.format();
        JsonElement value = switch (format) {
            case STRING, ITEM_ID -> new JsonPrimitive(ref);
            case SHORTHAND_RESULT -> {
                if (count <= 1 && components == null) {
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
        // Data components on plain item-stack results, validated when they were entered.
        if (components != null && value instanceof JsonObject obj) {
            try {
                obj.add("components", JsonParser.parseString(components));
            } catch (Exception ignored) {
            }
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
        Minecraft mc = Minecraft.getInstance(); // also before init, from suggestChange
        if (mc.level == null) {
            return false;
        }
        JsonElement encoded;
        try {
            encoded = Recipe.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE,
                    mc.level.registryAccess()), holder.value()).result().orElse(null);
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
        state.fields.remove(typeId);
        if (layout.prepareLoad != null) {
            json = layout.prepareLoad.apply(json);
        }
        fillFromJson(json);

        idText = holder.id().toString();
        status = loadedStatus(holder.id().toString());
        if (minecraft != null) {
            rebuildWidgets();
        }
        return true;
    }

    private Component loadedStatus(String id) {
        return EditorText.tr(mode == Mode.SUGGEST ? "editor.loaded.suggest" : "editor.loaded", id)
                .withStyle(ChatFormatting.GREEN);
    }

    /** Fills slots and fields from a recipe/entry in its serialized JSON form (layout already selected). */
    private void fillFromJson(JsonObject json) {
        String[] refs = refs();
        int[] counts = counts();
        int[] chances = chances();
        switch (layout.kind) {
            case SHAPED -> loadShaped(json, refs);
            case SHAPELESS -> loadShapeless(json, refs);
            case SLOTS -> {
            }
        }

        // Array paths whose slots are all items or all fluids: the layout already
        // says what their entries are, so they are not sniffed ({"tag", "amount"}
        // is an item in Modern Industrialization's item_inputs, not a fluid).
        Map<String, Boolean> arrayKinds = new HashMap<>();
        Set<String> mixedArrays = new HashSet<>();
        for (SlotDef slot : layout.slots) {
            if (slot.path().endsWith("[]")) {
                Boolean previous = arrayKinds.putIfAbsent(slot.path(), slot.format().fluid());
                if (previous != null && previous != slot.format().fluid()) {
                    mixedArrays.add(slot.path());
                }
            }
        }

        Map<String, Integer> cursors = new HashMap<>();
        Set<String> consumed = new HashSet<>();
        for (int i = 0; i < layout.slots.size(); i++) {
            SlotDef slot = layout.slots.get(i);
            if (layout.kind != Kind.SLOTS && !slot.result() && i < layout.gridSize * layout.gridSize) {
                continue; // grid slots were read by loadShaped/loadShapeless
            }
            boolean array = slot.path().endsWith("[]");
            if (!array && consumed.contains(slot.path())) {
                continue; // an either/or path already claimed by another slot
            }
            boolean sniffKind = !array || mixedArrays.contains(slot.path());
            Parsed parsed;
            if (array && sniffKind) {
                // Arrays may mix items and fluids (Create's ingredients/results):
                // each slot takes the next entry of its own kind, so an item slot
                // never swallows a fluid entry meant for a fluid slot.
                parsed = nextArrayEntry(json, slot.path(), slot.format().fluid(), cursors);
            } else {
                JsonElement value = getAtPath(json, slot.path(), cursors);
                parsed = value == null ? null : parseAny(value);
            }
            if (parsed == null || (sniffKind && parsed.fluidLike() != slot.format().fluid())) {
                continue;
            }
            refs[i] = parsed.ref();
            if (parsed.count() > 0) {
                counts[i] = Math.max(1, Math.min(slot.format().fluid() ? 1_000_000 : 99, parsed.count()));
            }
            if (slot.chanceable() && (parsed.chance() > 0 || (parsed.chance() == 0 && layout.minChance == 0))) {
                chances[i] = Math.max(layout.minChance, Math.min(100, Math.round(parsed.chance() * 100)));
            }
            if (supportsComponents(slot)) {
                components()[i] = parsed.components();
                componentIconCache.remove(i);
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
    }

    /** The Load button of registry layouts: asks the server for the entry named in the id box. */
    private Button loadEntryButton(int x, int y, int width) {
        return Button.builder(EditorText.tr("editor.entry.load"), b -> {
            String entryId = idText.trim();
            if (ResourceLocation.tryParse(entryId) == null || entryId.isEmpty()) {
                status = EditorText.tr("editor.entry.load_id_needed").withStyle(ChatFormatting.RED);
                return;
            }
            if (!Network.INSTANCE.canSendToServer(RegistryPayloads.EntryRequest.TYPE)) {
                status = EditorText.tr("editor.no_server").withStyle(ChatFormatting.RED);
                return;
            }
            Network.INSTANCE.sendToServer(new RegistryPayloads.EntryRequest(layout.registryTarget, entryId));
            status = EditorText.tr("editor.entry.loading", entryId).withStyle(ChatFormatting.YELLOW);
        }).bounds(x, y, width, 20).build();
    }

    /** The server's answer to {@link #loadEntryButton}: the entry's JSON, or "" when it doesn't exist. */
    public void onRegistryEntry(String registry, String entryId, String json) {
        if (layout.registryTarget == null || !layout.registryTarget.equals(registry)) {
            return;
        }
        if (json.isEmpty()) {
            status = EditorText.tr("editor.entry.missing", entryId, registry).withStyle(ChatFormatting.RED);
            return;
        }
        JsonObject parsed;
        try {
            parsed = JsonParser.parseString(json) instanceof JsonObject obj ? obj : null;
        } catch (Exception e) {
            parsed = null;
        }
        if (parsed == null) {
            status = EditorText.tr("editor.entry.unreadable").withStyle(ChatFormatting.RED);
            return;
        }
        clearSlots();
        if (layout.prepareLoad != null) {
            parsed = layout.prepareLoad.apply(parsed);
        }
        fillFromJson(parsed);
        idText = entryId;
        status = loadedStatus(entryId);
        rebuildWidgets();
    }

    /** Fills the crafting grid from an already-parsed shaped recipe (no JSON round-trip). */
    private boolean loadShapedParsed(RecipeHolder<?> holder, ShapedRecipe shaped, String typeId) {
        Minecraft mc = Minecraft.getInstance();
        assert mc.level != null;
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
        ItemStack result = shaped.getResultItem(mc.level.registryAccess());
        for (int i = 0; i < layout.slots.size(); i++) {
            if (layout.slots.get(i).result() && !result.isEmpty()) {
                refs[i] = BuiltInRegistries.ITEM.getKey(result.getItem()).toString();
                counts[i] = Math.max(1, Math.min(99, result.getCount()));
            }
        }

        idText = holder.id().toString();
        status = loadedStatus(holder.id().toString());
        if (minecraft != null) {
            rebuildWidgets();
        }
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

    /** chance is 0-1, or -1 when the entry gives none. */
    private record Parsed(String ref, int count, float chance, boolean fluidLike,
                          String components) {
        Parsed(String ref, int count, float chance, boolean fluidLike) {
            this(ref, count, chance, fluidLike, null);
        }

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
            return new Parsed(p.getAsString(), 0, -1, false);
        }
        if (value instanceof JsonArray array) {
            return array.isEmpty() ? null : parseAny(array.get(0));
        }
        if (!(value instanceof JsonObject obj)) {
            return null;
        }
        // "amount" alone isn't proof of a fluid: {"item": {...}, "amount": n}
        // is an item result with a quantity (Eternal Starlight's alloy).
        boolean fluidLike = obj.has("fluid") || obj.has("amount_mb")
                || (obj.has("amount") && !obj.has("item"));
        int count = intOf(obj, "count", intOf(obj, "amount", intOf(obj, "amount_mb", 0)));
        // -1 = no chance given; Modern Industrialization calls it "probability".
        float chance = obj.get("chance") instanceof JsonPrimitive c && c.isNumber() ? c.getAsFloat()
                : obj.get("probability") instanceof JsonPrimitive p && p.isNumber() ? p.getAsFloat() : -1;
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
        } else if (obj.get("stack") instanceof JsonObject stack) {
            // Ars Nouveau's crush output: {"stack": {"id", "count"}, "chance", "maxRange"}.
            Parsed nested = parseAny(stack);
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
        String components = obj.get("components") instanceof JsonObject c ? c.toString() : null;
        return ref == null ? null : new Parsed(ref, count, chance, fluidLike, components);
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
