package net.ledok.datarewriter.client.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * The recipe editor's «Suggest…» form: what kind of proposal (a new recipe; for a loaded recipe, a change or its
 * removal) and why. Staff see the text with the proposal; the answer comes in Eden's «Звернення» tab and by mail.
 */
final class SuggestScreen extends Screen {
    /** Characters Eden keeps of the text. */
    private static final int MAX_TEXT = 500;

    private final Screen parent;
    private final @Nullable String recipeId;
    private final List<String> kinds = new ArrayList<>();
    private final BiConsumer<String, String> send;
    private final List<Component> summary;
    private final List<Component> warnings;
    /** Warnings were shown once: the next Send goes through. */
    private boolean warned;
    private String kind;
    private String text = "";
    private Component error = Component.empty();

    /**
     * {@code recipeId}: the loaded recipe (null: a new one); {@code buildError}: why the editor's recipe can't be
     * built — then only a removal can be suggested.
     */
    SuggestScreen(Screen parent, @Nullable String recipeId, @Nullable Component buildError, List<Component> summary,
                  List<Component> warnings, BiConsumer<String, String> send) {
        super(EditorText.tr("suggest.title"));
        this.parent = parent;
        this.recipeId = recipeId;
        this.send = send;
        this.summary = summary;
        this.warnings = warnings;
        if (recipeId == null) {
            kinds.add("new");
        } else {
            if (buildError == null) {
                kinds.add("edit");
            } else {
                error = EditorText.tr("suggest.only_removal", buildError).withStyle(ChatFormatting.YELLOW);
            }
            kinds.add("remove");
        }
        kind = kinds.getFirst();
    }

    @Override
    protected void init() {
        int w = Math.min(320, width - 40);
        int left = (width - w) / 2;
        int y = height / 2 - 70;
        addRenderableWidget(CycleButton.<String>builder(k -> EditorText.tr("suggest.kind." + k,
                        recipeId == null ? "" : recipeId))
                .withValues(kinds)
                .withInitialValue(kind)
                .displayOnlyValue()
                .create(left, y, w, 20, EditorText.tr("suggest.kind"), (button, value) -> kind = value));
        y += 26;
        MultiLineEditBox box = new MultiLineEditBox(font, left, y, w, 50,
                EditorText.tr("suggest.text.hint"), EditorText.tr("suggest.text"));
        box.setCharacterLimit(MAX_TEXT);
        box.setValue(text);
        box.setValueListener(value -> text = value);
        addRenderableWidget(box);
        setInitialFocus(box);
        y += 64; // the box draws its 0/500 counter under itself
        addRenderableWidget(Button.builder(EditorText.tr("suggest.send"), b -> confirm())
                .bounds(left, y, w / 2 - 2, 20).build());
        addRenderableWidget(Button.builder(EditorText.tr("cancel"), b -> onClose())
                .bounds(left + w / 2 + 2, y, w / 2 - 2, 20).build());
    }

    private void confirm() {
        if (text.isBlank()) {
            error = EditorText.tr("suggest.text_required").withStyle(ChatFormatting.RED);
            return;
        }
        if (!kind.equals("remove") && !warnings.isEmpty() && !warned) {
            warned = true;
            error = EditorText.tr("suggest.warn.confirm").withStyle(ChatFormatting.YELLOW);
            return;
        }
        onClose();
        send.accept(kind, text.strip());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int w = Math.min(320, width - 40);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 92, 0xFFFFFF);
        int y = height / 2 + 46; // under the Send/Cancel row
        List<Component> lines = new ArrayList<>();
        if (!kind.equals("remove")) {
            lines.addAll(summary);
            warnings.forEach(warning -> lines.add(warning.copy().withStyle(ChatFormatting.GOLD)));
        }
        lines.add(EditorText.tr("suggest.info").withStyle(ChatFormatting.GRAY));
        for (var line : lines.stream().flatMap(l -> font.split(l, w).stream()).toList()) {
            graphics.drawCenteredString(font, line, width / 2, y, 0xFFFFFF);
            y += 10;
        }
        for (var line : font.split(error, w)) {
            graphics.drawCenteredString(font, line, width / 2, y + 2, 0xFFFFFF);
            y += 10;
        }
    }

    @Override
    public void onClose() {
        assert minecraft != null;
        minecraft.setScreen(parent);
    }
}
