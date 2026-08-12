package net.ledok.datarewriter.client.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;

/** Tiny popup to type an exact amount (count or mB) for a slot. */
public class AmountInputScreen extends Screen {
    private final Screen parent;
    private final Component label;
    private final int min;
    private final int max;
    private final IntConsumer onSet;

    private String text;

    public AmountInputScreen(Screen parent, Component label, int initial, int min, int max, IntConsumer onSet) {
        super(Component.literal("Set amount"));
        this.parent = parent;
        this.label = label;
        this.min = min;
        this.max = max;
        this.onSet = onSet;
        this.text = String.valueOf(initial);
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int y = height / 2 - 20;
        EditBox box = new EditBox(font, centerX - 50, y, 100, 18, Component.literal("Amount"));
        box.setMaxLength(7);
        box.setFilter(s -> s.isEmpty() || s.matches("\\d{1,7}"));
        box.setValue(text);
        box.setResponder(value -> text = value);
        addRenderableWidget(box);
        setInitialFocus(box);

        addRenderableWidget(Button.builder(Component.literal("OK"), b -> confirm())
                .bounds(centerX - 50, y + 24, 48, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(centerX + 2, y + 24, 48, 20).build());
    }

    private void confirm() {
        try {
            onSet.accept(Math.max(min, Math.min(max, Integer.parseInt(text.trim()))));
        } catch (NumberFormatException ignored) {
            // empty/invalid input keeps the old amount
        }
        onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, label, width / 2, height / 2 - 34, 0xFFFFFF);
        graphics.drawCenteredString(font,
                Component.literal(min + " – " + max).withStyle(ChatFormatting.DARK_GRAY),
                width / 2, height / 2 + 30, 0x707070);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) { // enter
            confirm();
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
