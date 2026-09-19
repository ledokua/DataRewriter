package net.ledok.datarewriter.client;

import net.ledok.datarewriter.client.gui.LootTableEditorScreen;
import net.ledok.datarewriter.client.gui.LootTablePickerScreen;
import net.ledok.datarewriter.client.gui.RecipeEditorScreen;
import net.ledok.datarewriter.client.gui.RecipeTweaksScreen;
import net.ledok.datarewriter.network.LootPayloads;
import net.minecraft.client.Minecraft;

/**
 * Loader-agnostic client side: the editor commands, the deferred screen opening and the loot editor's
 * payload handlers. Each loader module registers its commands, tick event and receivers against these.
 */
public final class DatarewriterClient {
    private static boolean openEditorNextTick;
    private static boolean openLootEditorNextTick;
    private static boolean openTweaksNextTick;

    private DatarewriterClient() {
    }

    public static void init() {
        ScreenshotHarness.init();
    }

    // A command can't open a screen directly — the chat screen closes after the command runs and would
    // override it, so opening is deferred to the next tick.

    /** {@code /recipeeditor} */
    public static void openRecipeEditor() {
        openEditorNextTick = true;
    }

    /** {@code /loottableeditor} */
    public static void openLootTableEditor() {
        openLootEditorNextTick = true;
    }

    /** {@code /recipetweaker} */
    public static void openRecipeTweaker() {
        openTweaksNextTick = true;
    }

    /** End of client tick. */
    public static void tick(Minecraft client) {
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
        ScreenshotHarness.tick(client);
    }

    // Loot editor answers from the server. Every handler only touches the screen that asked, so stray
    // packets are simply dropped.

    public static void onTableList(Minecraft client, LootPayloads.TableList payload) {
        if (client.screen instanceof LootTablePickerScreen picker) {
            picker.onList(payload.itemFilter(), payload.ids());
        }
    }

    public static void onClipboard(Minecraft client, LootPayloads.Clipboard payload) {
        client.keyboardHandler.setClipboard(payload.text());
    }

    public static void onSaveResult(Minecraft client, LootPayloads.SaveResult payload) {
        if (client.screen instanceof LootTableEditorScreen editor) {
            editor.onSaveResult(payload.ok(), payload.message());
        } else if (client.screen instanceof LootTablePickerScreen picker) {
            picker.onSaveResult(payload.ok(), payload.message());
        } else if (client.screen instanceof RecipeEditorScreen editor) {
            editor.onSaveResult(payload.ok(), payload.message());
        } else if (client.screen instanceof RecipeTweaksScreen tweaks) {
            tweaks.onSaveResult(payload.ok(), payload.message());
        }
    }

    public static void onTableContent(Minecraft client, LootPayloads.TableContent payload) {
        if (client.screen instanceof LootTableEditorScreen editor && editor.tableId.equals(payload.tableId())) {
            editor.onContent(payload.json(), payload.injected());
        }
    }
}
