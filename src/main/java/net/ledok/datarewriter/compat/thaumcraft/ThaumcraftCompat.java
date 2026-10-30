package net.ledok.datarewriter.compat.thaumcraft;

import com.google.gson.JsonObject;
import net.ledok.datarewriter.Datarewriter;
import net.ledok.datarewriter.RewriteState;
import net.ledok.datarewriter.network.ThaumcraftRecipesPayload;
import net.ledok.datarewriter.platform.Network;
import net.ledok.datarewriter.platform.Platform;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * Entry points for the Thaumcraft 4: Reforged integration. Safe to call without the mod: every method checks
 * {@link #LOADED} first, and only then touches the classes that link against Thaumcraft.
 */
public final class ThaumcraftCompat {
    public static final boolean LOADED = Platform.INSTANCE.isModLoaded("thaumcraft");
    /** The editor's pseudo registry target: its Load/Save requests are Thaumcraft recipes by key. */
    public static final String EDITOR_TARGET = "datarewriter:thaumcraft_recipe";

    private static volatile ThaumcraftRecipesPayload lastSync = new ThaumcraftRecipesPayload(java.util.List.of(), java.util.List.of());

    private ThaumcraftCompat() {
    }

    /** Server start and /reload: rebuild the map from Thaumcraft's own recipes plus the config. */
    public static void apply(MinecraftServer server) {
        if (!LOADED) {
            return;
        }
        ThaumcraftRecipes.Stats stats = ThaumcraftRecipes.apply(RewriteState.config());
        ThaumcraftRecipes.Diff diff = ThaumcraftRecipes.diff();
        lastSync = new ThaumcraftRecipesPayload(diff.removed(), diff.changed());
        RewriteState.thaumcraftChanged = stats.removed() + stats.modified() + stats.added() + stats.changed();
        if (RewriteState.thaumcraftChanged > 0 || stats.failed() > 0) {
            Datarewriter.LOGGER.info("Thaumcraft recipes: removed {}, modified {}, added {}, ingredients/results "
                    + "changed in {}{}", stats.removed(), stats.modified(), stats.added(), stats.changed(),
                    stats.failed() > 0 ? ", " + stats.failed() + " rule(s) FAILED (see above)" : "");
        }
    }

    /** The current changes to these players (join, /reload, /datarewriter reload, an editor save). */
    public static void sync(Collection<ServerPlayer> players) {
        if (!LOADED) {
            return;
        }
        for (ServerPlayer player : players) {
            if (Network.INSTANCE.canSendToPlayer(player, ThaumcraftRecipesPayload.TYPE)) {
                Network.INSTANCE.sendToPlayer(player, lastSync);
            }
        }
    }

    /** The recipe as DataRewriter JSON for the editor's Load, or null when there is none (or it can't be edited). */
    public static @Nullable JsonObject encode(String key) {
        if (!LOADED) {
            return null;
        }
        var recipe = ThaumcraftRecipes.get(key);
        return recipe == null || !TcJson.editable(recipe) ? null : TcJson.encode(recipe);
    }

    /** Checks an editor save; null when it is a valid recipe, else why not. */
    public static @Nullable String validate(JsonObject json) {
        if (!LOADED) {
            return "Thaumcraft is not installed on this server";
        }
        try {
            ThaumcraftRecipes.validate(json);
            return null;
        } catch (TcJson.TcJsonException e) {
            return e.getMessage();
        }
    }

    /** Aspect tags for the editor's hints (empty without Thaumcraft). */
    public static java.util.List<String> aspectTags() {
        return LOADED ? TcJson.aspectTags() : java.util.List.of();
    }
}
