package net.ledok.datarewriter.fabric.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.ledok.datarewriter.api.DataRewriterClientAddon;
import net.ledok.datarewriter.client.DatarewriterClient;
import net.ledok.datarewriter.network.LootPayloads;
import net.ledok.datarewriter.network.RegistryPayloads;

public final class DatarewriterFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        DatarewriterClient.init();
        // Addons register editor layouts via the "datarewriter_client" entrypoint.
        FabricLoader.getInstance().getEntrypoints("datarewriter_client", DataRewriterClientAddon.class)
                .forEach(DataRewriterClientAddon::registerClient);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("recipeeditor").executes(context -> {
                DatarewriterClient.openRecipeEditor();
                return 1;
            }));
            dispatcher.register(ClientCommandManager.literal("loottableeditor").executes(context -> {
                DatarewriterClient.openLootTableEditor();
                return 1;
            }));
            dispatcher.register(ClientCommandManager.literal("recipetweaker").executes(context -> {
                DatarewriterClient.openRecipeTweaker();
                return 1;
            }));
        });
        ClientTickEvents.END_CLIENT_TICK.register(DatarewriterClient::tick);

        ClientPlayNetworking.registerGlobalReceiver(LootPayloads.TableList.TYPE,
                (payload, context) -> DatarewriterClient.onTableList(context.client(), payload));
        ClientPlayNetworking.registerGlobalReceiver(LootPayloads.Clipboard.TYPE,
                (payload, context) -> DatarewriterClient.onClipboard(context.client(), payload));
        ClientPlayNetworking.registerGlobalReceiver(LootPayloads.SaveResult.TYPE,
                (payload, context) -> DatarewriterClient.onSaveResult(context.client(), payload));
        ClientPlayNetworking.registerGlobalReceiver(LootPayloads.TableContent.TYPE,
                (payload, context) -> DatarewriterClient.onTableContent(context.client(), payload));
        ClientPlayNetworking.registerGlobalReceiver(RegistryPayloads.EntryContent.TYPE,
                (payload, context) -> DatarewriterClient.onRegistryEntry(context.client(), payload));
    }
}
