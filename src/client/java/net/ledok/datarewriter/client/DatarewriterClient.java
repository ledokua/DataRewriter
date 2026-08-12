package net.ledok.datarewriter.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.ledok.datarewriter.client.gui.RecipeEditorScreen;

public class DatarewriterClient implements ClientModInitializer {
    private static boolean openEditorNextTick;

    @Override
    public void onInitializeClient() {
        // A command can't open a screen directly — the chat screen closes after
        // the command runs and would override it, so opening is deferred a tick.
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("recipeeditor")
                        .executes(context -> {
                            openEditorNextTick = true;
                            return 1;
                        })));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level == null) {
                openEditorNextTick = false;
            } else if (openEditorNextTick && client.screen == null) {
                openEditorNextTick = false;
                client.setScreen(new RecipeEditorScreen());
            }
        });
    }
}
