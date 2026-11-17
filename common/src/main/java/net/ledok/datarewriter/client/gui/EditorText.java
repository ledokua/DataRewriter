package net.ledok.datarewriter.client.gui;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Locale;

/**
 * The recipe editor's text in the player's language. Layouts keep their English names and labels in code (and in
 * layout files); lang files translate them by key, the English text being the fallback:
 * <ul>
 * <li>layout name: {@code gui.datarewriter.layout.<namespace>.<path>} (the recipe type, '/' as '.');</li>
 * <li>field label: {@code gui.datarewriter.field.<slug of the English label>};</li>
 * <li>slot label: {@code gui.datarewriter.slot.<slug of the English label>}.</li>
 * </ul>
 * A slug is the label in lower case with every run of other characters than a-z and 0-9 turned into one '_'
 * ("Time (ticks)" → {@code time_ticks}), so one translation serves every layout with the same label.
 */
public final class EditorText {
    private EditorText() {
    }

    /** {@code gui.datarewriter.<key>} */
    public static MutableComponent tr(String key, Object... args) {
        return Component.translatable("gui.datarewriter." + key, args);
    }

    public static MutableComponent layoutName(EditorLayout layout) {
        return Component.translatableWithFallback(
                "gui.datarewriter.layout." + layout.typeId.replace(':', '.').replace('/', '.'), layout.displayName);
    }

    public static MutableComponent fieldLabel(String english) {
        return Component.translatableWithFallback("gui.datarewriter.field." + slug(english), english);
    }

    public static MutableComponent slotLabel(String english) {
        return Component.translatableWithFallback("gui.datarewriter.slot." + slug(english), english);
    }

    static String slug(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }
}
