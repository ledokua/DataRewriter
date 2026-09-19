package net.ledok.datarewriter.neoforge;

import net.ledok.datarewriter.Datarewriter;
import net.ledok.datarewriter.api.DataRewriterAddon;
import net.ledok.datarewriter.command.ListCommand;
import net.ledok.datarewriter.menu.EditorMenu;
import net.ledok.datarewriter.menu.LootEditorMenu;
import net.ledok.datarewriter.neoforge.client.NeoForgeClientPayloads;
import net.ledok.datarewriter.network.BulkRecipeEditPayload;
import net.ledok.datarewriter.network.LootEditNetworking;
import net.ledok.datarewriter.network.LootPayloads;
import net.ledok.datarewriter.network.RecipeSaveNetworking;
import net.ledok.datarewriter.network.SaveRecipePayload;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.RegisterEvent;

import java.util.ServiceLoader;

@Mod(Datarewriter.MOD_ID)
public final class DatarewriterNeoForge {
    public DatarewriterNeoForge(IEventBus modBus) {
        // Addons contribute rules and listeners as ServiceLoader providers of DataRewriterAddon.
        ServiceLoader.load(DataRewriterAddon.class).forEach(DataRewriterAddon::register);

        modBus.addListener((RegisterEvent e) -> e.register(Registries.MENU, helper -> {
            // MenuType's constructor is opened by NeoForge's access transformer (common is vanilla-only).
            EditorMenu.register(new MenuType<>(EditorMenu::new, FeatureFlags.DEFAULT_FLAGS));
            LootEditorMenu.register(new MenuType<>(LootEditorMenu::new, FeatureFlags.DEFAULT_FLAGS));
        }));
        modBus.addListener(DatarewriterNeoForge::registerPayloads);

        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> ListCommand.register(e.getDispatcher()));
        // Rules matching by output/input/type need parsed recipes and bound tags, which only exist once the
        // data reload has fully finished; the post-injection loot pass runs there too. OnDatapackSyncEvent
        // with no player is posted at the end of /reload (after tags are bound); joins carry a player.
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> Datarewriter.onServerStarted(e.getServer()));
        NeoForge.EVENT_BUS.addListener((OnDatapackSyncEvent e) -> {
            if (e.getPlayer() == null) Datarewriter.onDataPackReloaded(e.getPlayerList().getServer());
        });
    }

    /**
     * Both directions on both sides. Optional, so vanilla clients (and clients without the mod) can still
     * join. Client-bound handlers only name {@link NeoForgeClientPayloads} inside lambda bodies, which a
     * dedicated server never runs, so the client classes behind it are never loaded there.
     */
    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar(Datarewriter.MOD_ID).optional();
        r.playToServer(SaveRecipePayload.TYPE, SaveRecipePayload.STREAM_CODEC,
                (p, ctx) -> RecipeSaveNetworking.handleSave(server(ctx), player(ctx), p));
        r.playToServer(BulkRecipeEditPayload.TYPE, BulkRecipeEditPayload.STREAM_CODEC,
                (p, ctx) -> RecipeSaveNetworking.handleBulk(server(ctx), player(ctx), p));
        r.playToServer(LootPayloads.TableListRequest.TYPE, LootPayloads.TableListRequest.STREAM_CODEC,
                (p, ctx) -> LootEditNetworking.handleTableListRequest(server(ctx), player(ctx), p));
        r.playToServer(LootPayloads.TableRequest.TYPE, LootPayloads.TableRequest.STREAM_CODEC,
                (p, ctx) -> LootEditNetworking.handleTableRequest(server(ctx), player(ctx), p));
        r.playToServer(LootPayloads.SaveTable.TYPE, LootPayloads.SaveTable.STREAM_CODEC,
                (p, ctx) -> LootEditNetworking.handleSaveTable(server(ctx), player(ctx), p));
        r.playToServer(LootPayloads.BulkItemEdit.TYPE, LootPayloads.BulkItemEdit.STREAM_CODEC,
                (p, ctx) -> LootEditNetworking.handleBulkItemEdit(server(ctx), player(ctx), p));

        r.playToClient(LootPayloads.TableList.TYPE, LootPayloads.TableList.STREAM_CODEC, (p, ctx) -> NeoForgeClientPayloads.tableList(p));
        r.playToClient(LootPayloads.TableContent.TYPE, LootPayloads.TableContent.STREAM_CODEC, (p, ctx) -> NeoForgeClientPayloads.tableContent(p));
        r.playToClient(LootPayloads.SaveResult.TYPE, LootPayloads.SaveResult.STREAM_CODEC, (p, ctx) -> NeoForgeClientPayloads.saveResult(p));
        r.playToClient(LootPayloads.Clipboard.TYPE, LootPayloads.Clipboard.STREAM_CODEC, (p, ctx) -> NeoForgeClientPayloads.clipboard(p));
    }

    private static ServerPlayer player(IPayloadContext ctx) {
        return (ServerPlayer) ctx.player();
    }

    private static MinecraftServer server(IPayloadContext ctx) {
        return player(ctx).server;
    }
}
