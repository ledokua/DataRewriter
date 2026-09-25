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
    private final Component extraLabel;
    private final Runnable extraAction;
    private final Component extraLabel2;
    private final Runnable extraAction2;

    private String text;

    public AmountInputScreen(Screen parent, Component label, int initial, int min, int max, IntConsumer onSet) {
        this(parent, label, initial, min, max, onSet, null, null);
    }

    /** With an extra action button below OK/Cancel (e.g. "Convert to tag…"). */
    public AmountInputScreen(Screen parent, Component label, int initial, int min, int max,
                             IntConsumer onSet, Component extraLabel, Runnable extraAction) {
        this(parent, label, initial, min, max, onSet, extraLabel, extraAction, null, null);
    }

    /** With up to two extra action buttons below OK/Cancel. */
    public AmountInputScreen(Screen parent, Component label, int initial, int min, int max,
                             IntConsumer onSet, Component extraLabel, Runnable extraAction,
                             Component extraLabel2, Runnable extraAction2) {
        super(Component.literal("Set amount"));
        this.parent = parent;
        this.label = label;
        this.min = min;
        this.max = max;
        this.onSet = onSet;
        this.extraLabel = extraLabel;
        this.extraAction = extraAction;
        this.extraLabel2 = extraLabel2;
        this.extraAction2 = extraAction2;
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
        if (extraLabel != null && extraAction != null) {
            addRenderableWidget(Button.builder(extraLabel, b -> extraAction.run())
                    .bounds(centerX - 60, y + 48, 120, 20).build());
        }
        if (extraLabel2 != null && extraAction2 != null) {
            addRenderableWidget(Button.builder(extraLabel2, b -> extraAction2.run())
                    .bounds(centerX - 60, y + 72, 120, 20).build());
        }
    }

    private void confirm() {
        assert minecraft != null;
        Screen before = minecraft.screen;
        try {
            onSet.accept(Math.max(min, Math.min(max, Integer.parseInt(text.trim()))));
        } catch (NumberFormatException ignored) {
            // empty/invalid input keeps the old amount
        }
        // Return to the parent unless the callback already opened another screen.
        if (minecraft.screen == before) {
            onClose();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, label, width / 2, height / 2 - 34, 0xFFFFFF);
        graphics.drawCenteredString(font,
                Component.literal(min + " – " + max).withStyle(ChatFormatting.DARK_GRAY),
                width / 2, height / 2 + (extraLabel != null ? 54 : 30), 0x707070);
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
