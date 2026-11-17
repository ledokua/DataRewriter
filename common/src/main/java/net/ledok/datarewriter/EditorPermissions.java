package net.ledok.datarewriter;

import net.ledok.datarewriter.network.SuggestionPayloads;
import net.ledok.datarewriter.platform.Network;
import net.ledok.datarewriter.platform.Platform;
import net.ledok.datarewriter.suggest.RecipeSuggestions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who may do what with the in-game editors. With LuckPerms, a node set on a player or group decides (true grants it
 * to a non-op, false takes it from an op); unset, or without LuckPerms, the old rules apply: saving needs operator
 * level 2, suggesting is open to everyone.
 * <p>
 * Clients learn their rights from {@link SuggestionPayloads.Access} on join, and again whenever LuckPerms changes
 * them (with a fresh command list, so {@code /datarewriter} appears or disappears at once).
 */
public final class EditorPermissions {
    /**
     * Saving in the recipe, loot table and registry editors and the recipe tweaker, accepting recipe suggestions,
     * and every {@code /datarewriter} command. Without it the recipe editor only shows recipes (and suggests).
     */
    public static final String SAVE = "datarewriter.editor.save";
    /** Proposing recipe changes from the recipe editor (filed to the server's suggestion queue). On by default. */
    public static final String SUGGEST = "datarewriter.suggest";

    private static final boolean LUCKPERMS = Platform.INSTANCE.isModLoaded("luckperms");

    /** What each online player was last told (see {@link #access}). */
    private static final Map<UUID, Integer> SENT = new ConcurrentHashMap<>();

    private EditorPermissions() {
    }

    public static boolean canSave(ServerPlayer player) {
        return check(player, SAVE, player.hasPermissions(2));
    }

    /** {@link #canSave(ServerPlayer)} for a command's {@code requires}; the console and command blocks need op level 2. */
    public static boolean canSave(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player != null ? canSave(player) : source.hasPermission(2);
    }

    public static boolean canSuggest(ServerPlayer player) {
        return check(player, SUGGEST, true);
    }

    private static boolean check(ServerPlayer player, String node, boolean fallback) {
        if (LUCKPERMS) {
            Boolean value = LuckPermsHook.permission(player.getUUID(), node);
            if (value != null) {
                return value;
            }
        }
        return fallback;
    }

    /** Bit 0: may save; bit 1: may suggest (and the server has a suggestion queue). */
    private static int access(ServerPlayer player) {
        return (canSave(player) ? 1 : 0) | (canSuggest(player) && RecipeSuggestions.available() ? 2 : 0);
    }

    /** Tells a joining player what the editor lets them do. Clients without DataRewriter are skipped. */
    public static void sync(ServerPlayer player) {
        int access = access(player);
        SENT.put(player.getUUID(), access);
        if (Network.INSTANCE.canSendToPlayer(player, SuggestionPayloads.Access.TYPE)) {
            Network.INSTANCE.sendToPlayer(player, new SuggestionPayloads.Access((access & 1) != 0, (access & 2) != 0));
        }
    }

    private static volatile MinecraftServer watched;

    /** Call at server start: LuckPerms edits then reach online players without a relog. */
    public static void watch(MinecraftServer server) {
        boolean first = watched == null;
        watched = server;
        if (LUCKPERMS && first) { // one subscription per game, even across singleplayer worlds
            LuckPermsHook.onUserDataChange(() -> {
                MinecraftServer current = watched;
                if (current != null && current.isRunning()) {
                    current.execute(() -> resendChanged(current));
                }
            });
        }
    }

    private static void resendChanged(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Integer before = SENT.get(player.getUUID());
            if (before != null && before != access(player)) {
                sync(player);
                server.getCommands().sendCommands(player);
            }
        }
        SENT.keySet().removeIf(uuid -> server.getPlayerList().getPlayer(uuid) == null);
    }

    /** The only class that names LuckPerms' API; loaded only when the mod is present. */
    private static final class LuckPermsHook {
        static Boolean permission(UUID player, String node) {
            try {
                net.luckperms.api.model.user.User user = net.luckperms.api.LuckPermsProvider.get()
                        .getUserManager().getUser(player);
                if (user == null) {
                    return null;
                }
                net.luckperms.api.util.Tristate result = user.getCachedData().getPermissionData().checkPermission(node);
                return result == net.luckperms.api.util.Tristate.UNDEFINED ? null : result.asBoolean();
            } catch (IllegalStateException e) {
                return null; // LuckPerms not enabled yet
            }
        }

        static void onUserDataChange(Runnable onChange) {
            try {
                net.luckperms.api.LuckPermsProvider.get().getEventBus().subscribe(
                        net.luckperms.api.event.user.UserDataRecalculateEvent.class, event -> onChange.run());
            } catch (IllegalStateException e) {
                Datarewriter.LOGGER.warn("LuckPerms is installed but not enabled; permission changes need a relog");
            }
        }
    }
}
