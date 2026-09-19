package net.ledok.datarewriter.client.gui;

import net.ledok.datarewriter.client.gui.LootTableEditorScreen.EntryState;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * Chooser for the loot editor's special entry types: a composite
 * (alternatives / group / sequence) or a nested loot table reference.
 */
public class SpecialEntryScreen extends Screen {
    private final Screen parent;
    private final Consumer<EntryState> onCreate;
    private int top;

    public SpecialEntryScreen(Screen parent, Consumer<EntryState> onCreate) {
        super(Component.literal("Add a special entry"));
        this.parent = parent;
        this.onCreate = onCreate;
    }

    @Override
    protected void init() {
        int w = 220;
        int left = (width - w) / 2;
        top = Math.max(10, (height - 170) / 2);
        int y = top + 24;
        addChoice(left, y, w, "Alternatives", "minecraft:alternatives",
                "The first entry whose conditions match drops (an either/or)");
        y += 24;
        addChoice(left, y, w, "Group", "minecraft:group",
                "All of its entries drop together");
        y += 24;
        addChoice(left, y, w, "Sequence", "minecraft:sequence",
                "Entries drop in order until one's conditions fail");
        y += 24;
        addRenderableWidget(Button.builder(Component.literal("Nested loot table…"), b -> {
                    assert minecraft != null;
                    minecraft.setScreen(new LootTablePickerScreen(parent, tableId ->
                            deliver(LootTableEditorScreen.newTableRef(tableId))));
                })
                .bounds(left, y, w, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "One roll of another loot table, dropped here")))
                .build());
        y += 30;
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(left + (w - 80) / 2, y, 80, 20)
                .build());
    }

    private void addChoice(int x, int y, int w, String label, String typeId, String tooltip) {
        addRenderableWidget(Button.builder(Component.literal(label),
                        b -> deliver(LootTableEditorScreen.newComposite(typeId)))
                .bounds(x, y, w, 20)
                .tooltip(Tooltip.create(Component.literal(tooltip)))
                .build());
    }

    private void deliver(EntryState entry) {
        assert minecraft != null;
        Screen before = minecraft.screen;
        onCreate.accept(entry);
        if (minecraft.screen == before) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, top, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.literal(
                        "One slot in the pool holding several entries")
                .withStyle(ChatFormatting.GRAY), width / 2, top + 12, 0xA0A0A0);
    }

    @Override
    public void onClose() {
        assert minecraft != null;
        minecraft.setScreen(parent);
    }
}
