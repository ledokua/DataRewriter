package net.ledok.datarewriter.client.gui;

import net.ledok.datarewriter.config.TableScope;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * "Which loot tables?" step of a bulk item edit: a pattern list with quick
 * presets. '*' wildcards, comma-separated patterns, and a leading '!' to
 * exclude — "somemod:*" (one mod only), "!*:blocks/*" (everything except
 * block drops). Confirms with "*" for every table.
 */
public class TableScopeScreen extends Screen {
    private static final int BOX_W = 260;

    private final Screen parent;
    private final Consumer<String> onConfirm;

    private EditBox scopeBox;
    private Component status = Component.empty();
    private int top;
    private int statusY;

    public TableScopeScreen(Screen parent, Consumer<String> onConfirm) {
        super(Component.literal("Which loot tables?"));
        this.parent = parent;
        this.onConfirm = onConfirm;
    }

    @Override
    protected void init() {
        int left = (width - BOX_W) / 2;
        top = Math.max(10, (height - 190) / 2);

        scopeBox = new EditBox(font, left, top + 34, BOX_W, 18, Component.literal("Tables"));
        scopeBox.setMaxLength(512);
        scopeBox.setValue("*");
        scopeBox.setHint(Component.literal("* = every table").withStyle(ChatFormatting.DARK_GRAY));
        scopeBox.setResponder(text -> status = Component.empty());
        addRenderableWidget(scopeBox);
        setInitialFocus(scopeBox);

        int half = (BOX_W - 4) / 2;
        int y = top + 70;
        addPreset(left, y, half, "All tables", "*", true,
                "Apply to every loot table");
        addPreset(left + half + 4, y, half, "Chests only", "*:chests/*", true,
                "Only chest loot (from every mod)");
        y += 24;
        addPreset(left, y, half, "Except block drops", "!*:blocks/*", false,
                "Adds an exclusion — block drop tables are left alone");
        addPreset(left + half + 4, y, half, "Except entity drops", "!*:entities/*", false,
                "Adds an exclusion — mob drop tables are left alone");
        y += 30;
        addRenderableWidget(Button.builder(Component.literal("OK"), b -> confirm())
                .bounds(left, y, half, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(left + half + 4, y, half, 20)
                .build());
        statusY = y + 26;
    }

    /** Presets either replace the pattern list or append to it (exclusions). */
    private void addPreset(int x, int y, int w, String label, String pattern, boolean replace,
                           String tooltip) {
        addRenderableWidget(Button.builder(Component.literal(label), b -> {
                    String current = scopeBox.getValue().trim();
                    if (replace || current.isEmpty() || current.equals("*")) {
                        scopeBox.setValue(pattern);
                    } else if (!current.contains(pattern)) {
                        scopeBox.setValue(current + ", " + pattern);
                    }
                })
                .bounds(x, y, w, 20)
                .tooltip(Tooltip.create(Component.literal(tooltip)))
                .build());
    }

    private void confirm() {
        String text = scopeBox.getValue().trim();
        if (text.isEmpty()) {
            text = "*";
        }
        if (!text.equals("*") && TableScope.parse(text) == null) {
            status = Component.literal("Not a valid pattern list — check for typos.")
                    .withStyle(ChatFormatting.RED);
            return;
        }
        assert minecraft != null;
        Screen before = minecraft.screen;
        onConfirm.accept(text);
        if (minecraft.screen == before) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, top, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.literal(
                        "Patterns with '*', comma-separated — '!' excludes")
                .withStyle(ChatFormatting.GRAY), width / 2, top + 16, 0xA0A0A0);
        graphics.drawCenteredString(font, Component.literal(
                        "e.g.  somemod:*   or   *:chests/*, !minecraft:chests/*")
                .withStyle(ChatFormatting.DARK_GRAY), width / 2, top + 56, 0x808080);
        if (!status.getString().isEmpty()) {
            graphics.drawCenteredString(font, status, width / 2, statusY, 0xFFFFFF);
        }
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
