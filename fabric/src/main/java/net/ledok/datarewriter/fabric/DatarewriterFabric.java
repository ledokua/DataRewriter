package net.ledok.datarewriter.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.ledok.datarewriter.Datarewriter;
import net.ledok.datarewriter.api.DataRewriterAddon;
import net.ledok.datarewriter.command.ListCommand;
import net.ledok.datarewriter.menu.EditorMenu;
import net.ledok.datarewriter.menu.LootEditorMenu;
import net.ledok.datarewriter.network.BulkRecipeEditPayload;
import net.ledok.datarewriter.network.LootEditNetworking;
import net.ledok.datarewriter.network.LootPayloads;
import net.ledok.datarewriter.network.RecipeSaveNetworking;
import net.ledok.datarewriter.network.RegistryEditNetworking;
import net.ledok.datarewriter.network.RegistryPayloads;
import net.ledok.datarewriter.network.SaveRecipePayload;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;

public final class DatarewriterFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        // MenuType's constructor is opened by fabric-api's transitive access widener (common is vanilla-only).
        EditorMenu.register(new MenuType<>(EditorMenu::new, FeatureFlags.DEFAULT_FLAGS));
        LootEditorMenu.register(new MenuType<>(LootEditorMenu::new, FeatureFlags.DEFAULT_FLAGS));
        // Addons contribute rules and listeners via the "datarewriter" entrypoint.
        FabricLoader.getInstance().getEntrypoints("datarewriter", DataRewriterAddon.class)
                .forEach(DataRewriterAddon::register);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                ListCommand.register(dispatcher));

        // Payloads (both directions must be registered on both sides).
        PayloadTypeRegistry.playC2S().register(SaveRecipePayload.TYPE, SaveRecipePayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(BulkRecipeEditPayload.TYPE, BulkRecipeEditPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(LootPayloads.TableListRequest.TYPE, LootPayloads.TableListRequest.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(LootPayloads.TableRequest.TYPE, LootPayloads.TableRequest.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(LootPayloads.SaveTable.TYPE, LootPayloads.SaveTable.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(LootPayloads.BulkItemEdit.TYPE, LootPayloads.BulkItemEdit.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(LootPayloads.TableList.TYPE, LootPayloads.TableList.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(LootPayloads.TableContent.TYPE, LootPayloads.TableContent.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(LootPayloads.SaveResult.TYPE, LootPayloads.SaveResult.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(LootPayloads.Clipboard.TYPE, LootPayloads.Clipboard.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(RegistryPayloads.EntryRequest.TYPE, RegistryPayloads.EntryRequest.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(RegistryPayloads.SaveEntry.TYPE, RegistryPayloads.SaveEntry.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(RegistryPayloads.EntryContent.TYPE, RegistryPayloads.EntryContent.STREAM_CODEC);

        ServerPlayNetworking.registerGlobalReceiver(SaveRecipePayload.TYPE,
                (payload, context) -> RecipeSaveNetworking.handleSave(context.server(), context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(BulkRecipeEditPayload.TYPE,
                (payload, context) -> RecipeSaveNetworking.handleBulk(context.server(), context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(LootPayloads.TableListRequest.TYPE,
                (payload, context) -> LootEditNetworking.handleTableListRequest(context.server(), context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(LootPayloads.TableRequest.TYPE,
                (payload, context) -> LootEditNetworking.handleTableRequest(context.server(), context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(LootPayloads.SaveTable.TYPE,
                (payload, context) -> LootEditNetworking.handleSaveTable(context.server(), context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(LootPayloads.BulkItemEdit.TYPE,
                (payload, context) -> LootEditNetworking.handleBulkItemEdit(context.server(), context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(RegistryPayloads.EntryRequest.TYPE,
                (payload, context) -> RegistryEditNetworking.handleEntryRequest(context.server(), context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(RegistryPayloads.SaveEntry.TYPE,
                (payload, context) -> RegistryEditNetworking.handleSave(context.server(), context.player(), payload));

        // Rules matching by output/input/type need parsed recipes and bound tags, which only exist once the
        // data reload has fully finished; the post-injection loot pass runs there too.
        ServerLifecycleEvents.SERVER_STARTED.register(Datarewriter::onServerStarted);
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
            if (success) Datarewriter.onDataPackReloaded(server);
        });
    }
}
