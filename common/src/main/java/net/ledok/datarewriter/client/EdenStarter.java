package net.ledok.datarewriter.client;

import net.ledok.datarewriter.Datarewriter;
import net.ledok.datarewriter.client.gui.RecipeEditorScreen;
import net.ledok.datarewriter.platform.Platform;
import net.ledok.datarewriter.suggest.RecipeSuggestions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * A «Рецепт» choice in Eden_LD's «Звернення» (reports) form that opens the recipe editor in suggest mode
 * ({@code EdenReportsClientApi}, Eden 1.3.84+), registered by reflection — DataRewriter never compiles against Eden.
 * Shown only while the server lets this player suggest.
 */
final class EdenStarter {
    private static final String API = "net.ledok.eden_ld.api.hub.EdenReportsClientApi";

    private EdenStarter() {
    }

    static void register() {
        if (!Platform.INSTANCE.isModLoaded("eden_ld")) {
            return;
        }
        BooleanSupplier available = DatarewriterClient::maySuggest;
        Consumer<Screen> open = parent -> Minecraft.getInstance().setScreen(RecipeEditorScreen.forSuggestion(parent));
        try {
            Class.forName(API)
                    .getMethod("registerSuggestionStarter", String.class, String.class, BooleanSupplier.class,
                            Consumer.class)
                    .invoke(null, RecipeSuggestions.CATEGORY, "gui.datarewriter.suggest.eden_kind", available, open);
        } catch (ReflectiveOperationException e) {
            Datarewriter.LOGGER.info("Eden_LD has no suggestion starters (1.3.84+); recipe suggestions open "
                    + "with /recipeeditor only");
        }
    }
}
