package net.ledok.datarewriter.client.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * A reviewer's verdict on a recipe suggestion: the answer for its author (shown in their «Звернення» tab and
 * mailed) — optional when accepting, required when rejecting.
 */
final class DecisionScreen extends Screen {
    private static final int MAX_TEXT = 500;

    private final Screen parent;
    private final boolean accept;
    private final Consumer<String> confirm;
    private String reason = "";
    private Component error = Component.empty();

    DecisionScreen(Screen parent, boolean accept, long id, Consumer<String> confirm) {
        super(EditorText.tr(accept ? "decision.accept.title" : "decision.reject.title", "#" + id));
        this.parent = parent;
        this.accept = accept;
        this.confirm = confirm;
    }

    @Override
    protected void init() {
        int w = Math.min(320, width - 40);
        int left = (width - w) / 2;
        int y = height / 2 - 50;
        MultiLineEditBox box = new MultiLineEditBox(font, left, y, w, 74,
                EditorText.tr(accept ? "decision.accept.hint" : "decision.reject.hint"),
                EditorText.tr("decision.reason"));
        box.setCharacterLimit(MAX_TEXT);
        box.setValue(reason);
        box.setValueListener(value -> reason = value);
        addRenderableWidget(box);
        setInitialFocus(box);
        y += 88; // the box draws its 0/500 counter under itself
        addRenderableWidget(Button.builder(EditorText.tr(accept ? "review.accept" : "review.reject"), b -> {
                    if (!accept && reason.isBlank()) {
                        error = EditorText.tr("review.reason_required").withStyle(ChatFormatting.RED);
                        return;
                    }
                    onClose();
                    confirm.accept(reason.strip());
                })
                .bounds(left, y, w / 2 - 2, 20).build());
        addRenderableWidget(Button.builder(EditorText.tr("cancel"), b -> onClose())
                .bounds(left + w / 2 + 2, y, w / 2 - 2, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int w = Math.min(320, width - 40);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 72, 0xFFFFFF);
        int y = height / 2 + 64;
        Component info = accept ? EditorText.tr("decision.accept.info") : EditorText.tr("decision.reject.info");
        for (var line : font.split(info.copy().withStyle(ChatFormatting.GRAY), w)) {
            graphics.drawCenteredString(font, line, width / 2, y, 0xFFFFFF);
            y += 10;
        }
        graphics.drawCenteredString(font, error, width / 2, y + 2, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        assert minecraft != null;
        minecraft.setScreen(parent);
    }
}
