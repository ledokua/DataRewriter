package net.ledok.datarewriter.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/** Wrapped, scrollable lines of text (a suggestion's details), with a Back button. */
final class TextLinesScreen extends Screen {
    private final Screen parent;
    private final List<Component> lines;
    private final List<FormattedCharSequence> wrapped = new ArrayList<>();
    private int scroll;

    TextLinesScreen(Screen parent, Component title, List<Component> lines) {
        super(title);
        this.parent = parent;
        this.lines = List.copyOf(lines);
    }

    private int textWidth() {
        return Math.min(360, width - 40);
    }

    private int visibleLines() {
        return Math.max(1, (height - 32 - 40) / 10);
    }

    @Override
    protected void init() {
        wrapped.clear();
        for (Component line : lines) {
            wrapped.addAll(line.getString().isEmpty() ? List.of(FormattedCharSequence.EMPTY) : font.split(line, textWidth()));
        }
        scroll = Math.max(0, Math.min(scroll, wrapped.size() - visibleLines()));
        addRenderableWidget(Button.builder(EditorText.tr("back"), b -> onClose())
                .bounds((width - 100) / 2, height - 28, 100, 20).build());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Math.max(0, Math.min(wrapped.size() - visibleLines(), scroll - (int) Math.signum(scrollY) * 3));
        return true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        int left = (width - textWidth()) / 2;
        int y = 32;
        for (int i = scroll; i < Math.min(wrapped.size(), scroll + visibleLines()); i++) {
            graphics.drawString(font, wrapped.get(i), left, y, 0xFFFFFF);
            y += 10;
        }
    }

    @Override
    public void onClose() {
        assert minecraft != null;
        minecraft.setScreen(parent);
    }
}
