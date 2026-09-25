package net.ledok.datarewriter.client.gui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * A small dialog for an item's data components, entered as the vanilla JSON object the
 * {@code components} key takes everywhere (recipe results, loot's {@code set_components}):
 * {@code {"minecraft:custom_name": "...", "minecraft:enchantments": {...}}}. The input is
 * validated with the real component codec against the world's registries before it is accepted,
 * and a live preview shows the item with the components applied. An empty box removes them.
 */
public class ComponentsEditScreen extends Screen {
    private final Screen parent;
    /** Item id the preview renders; null = no preview (a #tag loot entry, say). */
    private final @Nullable String previewRef;
    /** Receives the parsed components object, or null when they were removed. */
    private final Consumer<@Nullable JsonObject> onSet;

    private String text;
    private MultiLineEditBox box;
    private Component error = Component.empty();
    private ItemStack preview = ItemStack.EMPTY;

    public ComponentsEditScreen(Screen parent, @Nullable String previewRef, @Nullable String initialJson,
                         Consumer<@Nullable JsonObject> onSet) {
        super(Component.literal("Item components"));
        this.parent = parent;
        this.previewRef = previewRef;
        this.onSet = onSet;
        this.text = initialJson == null ? "" : initialJson;
    }

    @Override
    protected void init() {
        int boxWidth = Math.min(340, width - 40);
        int boxLeft = width / 2 - boxWidth / 2;
        int boxTop = height / 2 - 60;
        box = new MultiLineEditBox(font, boxLeft, boxTop, boxWidth, 96,
                Component.literal("{\"minecraft:custom_name\": \"...\"}"),
                Component.literal("Components"));
        box.setCharacterLimit(8000);
        box.setValue(text);
        box.setValueListener(value -> {
            text = value;
            refreshPreview();
        });
        addRenderableWidget(box);
        setInitialFocus(box);

        int y = boxTop + 102;
        addRenderableWidget(Button.builder(Component.literal("OK"), b -> confirm())
                .bounds(width / 2 - 110, y, 68, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Remove"), b -> {
            onSet.accept(null);
            close();
        }).bounds(width / 2 - 36, y, 68, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(width / 2 + 38, y, 68, 20).build());
        refreshPreview();
    }

    private void confirm() {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            onSet.accept(null);
            close();
            return;
        }
        JsonObject parsed = validate(trimmed);
        if (parsed != null) {
            onSet.accept(parsed);
            close();
        }
    }

    /** Parses and codec-checks the text; sets {@link #error} and returns null when it's bad. */
    private @Nullable JsonObject validate(String value) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(value);
        } catch (Exception e) {
            error = Component.literal("Not valid JSON: " + shorten(e.getMessage()))
                    .withStyle(ChatFormatting.RED);
            return null;
        }
        if (!(parsed instanceof JsonObject obj)) {
            error = Component.literal("Must be a JSON object: {\"mod:component\": value, ...}")
                    .withStyle(ChatFormatting.RED);
            return null;
        }
        assert minecraft != null && minecraft.level != null;
        RegistryOps<JsonElement> ops =
                RegistryOps.create(JsonOps.INSTANCE, minecraft.level.registryAccess());
        var result = DataComponentPatch.CODEC.parse(ops, obj);
        if (result.error().isPresent()) {
            error = Component.literal(shorten(result.error().get().message()))
                    .withStyle(ChatFormatting.RED);
            return null;
        }
        error = Component.empty();
        return obj;
    }

    private void refreshPreview() {
        error = Component.empty();
        preview = previewRef == null ? ItemStack.EMPTY
                : RecipeEditorScreen.iconFor(previewRef, false).copy();
        String trimmed = text.trim();
        if (trimmed.isEmpty() || minecraft == null || minecraft.level == null) {
            return;
        }
        JsonObject parsed = validate(trimmed);
        if (parsed != null && !preview.isEmpty()) {
            RegistryOps<JsonElement> ops =
                    RegistryOps.create(JsonOps.INSTANCE, minecraft.level.registryAccess());
            DataComponentPatch.CODEC.parse(ops, parsed).result()
                    .ifPresent(patch -> preview.applyComponents(patch));
        }
    }

    private static String shorten(String message) {
        if (message == null) {
            return "unknown error";
        }
        return message.length() > 120 ? message.substring(0, 117) + "..." : message;
    }

    private void close() {
        assert minecraft != null;
        if (minecraft.screen == this) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public void onClose() {
        assert minecraft != null;
        minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int boxTop = height / 2 - 60;
        graphics.drawCenteredString(font, title, width / 2, boxTop - 34, 0xFFFFFF);
        if (!preview.isEmpty()) {
            int x = width / 2 - 8;
            graphics.renderItem(preview, x, boxTop - 22);
            graphics.renderItemDecorations(font, preview, x, boxTop - 22);
            if (mouseX >= x && mouseX < x + 16 && mouseY >= boxTop - 22 && mouseY < boxTop - 6) {
                graphics.renderTooltip(font, preview, mouseX, mouseY);
            }
        }
        if (error.getString().isEmpty()) {
            graphics.drawCenteredString(font,
                    Component.literal("The item's \"components\" JSON object — validated on OK")
                            .withStyle(ChatFormatting.GRAY),
                    width / 2, height / 2 + 66, 0xA0A0A0);
        } else {
            graphics.drawCenteredString(font, error, width / 2, height / 2 + 66, 0xFF6060);
        }
    }
}
