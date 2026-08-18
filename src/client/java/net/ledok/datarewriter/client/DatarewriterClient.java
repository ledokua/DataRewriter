package net.ledok.datarewriter.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.datarewriter.client.gui.LootTableEditorScreen;
import net.ledok.datarewriter.client.gui.LootTablePickerScreen;
import net.ledok.datarewriter.client.gui.RecipeEditorScreen;
import net.ledok.datarewriter.client.gui.RecipeTweaksScreen;
import net.ledok.datarewriter.network.LootPayloads;

public class DatarewriterClient implements ClientModInitializer {
    private static boolean openEditorNextTick;
    private static boolean openLootEditorNextTick;
    private static boolean openTweaksNextTick;

    @Override
    public void onInitializeClient() {
        ScreenshotHarness.init();
        // A command can't open a screen directly — the chat screen closes after
        // the command runs and would override it, so opening is deferred a tick.
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("recipeeditor")
                    .executes(context -> {
                        openEditorNextTick = true;
                        return 1;
                    }));
            dispatcher.register(ClientCommandManager.literal("loottableeditor")
                    .executes(context -> {
                        openLootEditorNextTick = true;
                        return 1;
                    }));
            dispatcher.register(ClientCommandManager.literal("recipetweaker")
                    .executes(context -> {
                        openTweaksNextTick = true;
                        return 1;
                    }));
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level == null) {
                openEditorNextTick = false;
                openLootEditorNextTick = false;
                openTweaksNextTick = false;
            } else if (openEditorNextTick && client.screen == null) {
                openEditorNextTick = false;
                client.setScreen(new RecipeEditorScreen());
            } else if (openLootEditorNextTick && client.screen == null) {
                openLootEditorNextTick = false;
                client.setScreen(new LootTablePickerScreen());
            } else if (openTweaksNextTick && client.screen == null) {
                openTweaksNextTick = false;
                client.setScreen(new RecipeTweaksScreen());
            }
        });

        // Loot editor answers from the server. Both receivers only touch the
        // screen that asked, so stray packets are simply dropped.
        ClientPlayNetworking.registerGlobalReceiver(LootPayloads.TableList.TYPE, (payload, context) -> {
            if (context.client().screen instanceof LootTablePickerScreen picker) {
                picker.onList(payload.itemFilter(), payload.ids());
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(LootPayloads.SaveResult.TYPE, (payload, context) -> {
            if (context.client().screen instanceof LootTableEditorScreen editor) {
                editor.onSaveResult(payload.ok(), payload.message());
            } else if (context.client().screen instanceof LootTablePickerScreen picker) {
                picker.onSaveResult(payload.ok(), payload.message());
            } else if (context.client().screen instanceof RecipeEditorScreen editor) {
                editor.onSaveResult(payload.ok(), payload.message());
            } else if (context.client().screen instanceof RecipeTweaksScreen tweaks) {
                tweaks.onSaveResult(payload.ok(), payload.message());
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(LootPayloads.TableContent.TYPE, (payload, context) -> {
            if (context.client().screen instanceof LootTableEditorScreen editor
                    && editor.tableId.equals(payload.tableId())) {
                editor.onContent(payload.json(), payload.injected());
            }
        });
    }
}
