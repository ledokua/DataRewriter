package net.ledok.datarewriter.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.ledok.datarewriter.Datarewriter;
import net.ledok.datarewriter.RegistryLiveApplier;
import net.ledok.datarewriter.RewriteState;
import net.ledok.datarewriter.config.GuiRegistrySaver;
import net.ledok.datarewriter.config.RewriteConfig;
import net.ledok.datarewriter.platform.Network;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server side of the ritual editor (datapack registry entries). Each loader module registers the
 * payloads and routes them here. Reading an entry is open to everyone — the data is synced to
 * clients anyway; saving requires permission level 2, like every other editor.
 */
public final class RegistryEditNetworking {
    private RegistryEditNetworking() {
    }

    /** {@code EntryRequest}: one entry's JSON for the editor's Load button. */
    public static void handleEntryRequest(MinecraftServer server, ServerPlayer player,
                                          RegistryPayloads.EntryRequest payload) {
        ResourceLocation registry = ResourceLocation.tryParse(payload.registry());
        ResourceLocation id = ResourceLocation.tryParse(payload.entryId());
        JsonObject json = registry == null || id == null ? null
                : RegistryLiveApplier.encode(server, registry, id);
        Network.INSTANCE.sendToPlayer(player, new RegistryPayloads.EntryContent(
                payload.registry(), payload.entryId(), json == null ? "" : json.toString()));
    }

    /** {@code SaveEntry}: validate, persist as a {@code registries.add} rule, apply live. */
    public static void handleSave(MinecraftServer server, ServerPlayer player,
                                  RegistryPayloads.SaveEntry payload) {
        if (!player.hasPermissions(2)) {
            fail(player, "you need to be an operator (permission level 2) on this server");
            return;
        }
        ResourceLocation registry = ResourceLocation.tryParse(payload.registry());
        ResourceLocation id = ResourceLocation.tryParse(payload.entryId());
        if (registry == null || id == null) {
            fail(player, "'" + (registry == null ? payload.registry() : payload.entryId())
                    + "' is not a valid id");
            return;
        }
        JsonObject entry;
        try {
            entry = JsonParser.parseString(payload.json()) instanceof JsonObject obj ? obj : null;
        } catch (Exception e) {
            entry = null;
        }
        if (entry == null) {
            fail(player, "the entry must be a JSON object");
            return;
        }
        RegistryLiveApplier.Parsed parsed = RegistryLiveApplier.parse(server, registry, entry);
        if (parsed.error() != null) {
            fail(player, parsed.error());
            return;
        }
        // Persist first: if the config file can't be written, nothing changes in memory either.
        String error = GuiRegistrySaver.saveEntry(registry.toString(), id.toString(), entry);
        if (error == null) {
            RewriteState.appendRegistryAddition(
                    new RewriteConfig.RegistryAddition(registry, id, entry.deepCopy(), GuiRegistrySaver.FILE_NAME));
            error = RegistryLiveApplier.bind(parsed, id);
        }
        if (error != null) {
            fail(player, error);
            return;
        }
        Datarewriter.LOGGER.info("Ritual editor: {} in {} — saved and applied (by {})",
                id, registry, player.getGameProfile().getName());
        succeed(player, "Entry " + id + " saved to config/datarewriter/" + GuiRegistrySaver.FILE_NAME
                + " on the server and applied live. Other players' recipe viewers (JEI) show it after"
                + " they rejoin the world.");
    }

    private static void fail(ServerPlayer player, String message) {
        String text = "Not saved — " + message;
        Datarewriter.LOGGER.warn("Ritual editor ({}): {}", player.getGameProfile().getName(), text);
        player.sendSystemMessage(prefix().append(Component.literal(text).withStyle(ChatFormatting.RED)));
        if (Network.INSTANCE.canSendToPlayer(player, LootPayloads.SaveResult.TYPE)) {
            Network.INSTANCE.sendToPlayer(player, new LootPayloads.SaveResult(false, text));
        }
    }

    private static void succeed(ServerPlayer player, String message) {
        String notice = GuiRegistrySaver.takeNotice();
        if (notice != null) {
            message += " (" + notice + ")";
        }
        player.sendSystemMessage(prefix().append(Component.literal(message).withStyle(ChatFormatting.GRAY)));
        if (Network.INSTANCE.canSendToPlayer(player, LootPayloads.SaveResult.TYPE)) {
            Network.INSTANCE.sendToPlayer(player, new LootPayloads.SaveResult(true, message));
        }
    }

    private static MutableComponent prefix() {
        return Component.literal("[DataRewriter] ").withStyle(ChatFormatting.GOLD);
    }
}
