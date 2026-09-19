package net.ledok.datarewriter.api;

import net.ledok.datarewriter.Datarewriter;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Server-side hooks other mods can listen to. Loader-agnostic: plain listener lists, fired on the server
 * thread. Register from your {@code datarewriter} entrypoint. A listener that throws is logged and
 * skipped, never allowed to break a save or a reload.
 */
public final class DataRewriterEvents {
    private DataRewriterEvents() {
    }

    /**
     * The live recipe set changed: after the post-load passes on server start and {@code /reload}, and
     * after every recipe editor / recipe tweaker save. Clients are not pushed the new list until
     * {@code /datarewriter reload} (see the README); server-side lookups see it immediately.
     */
    @FunctionalInterface
    public interface RecipesChanged {
        void onRecipesChanged(MinecraftServer server);
    }

    /**
     * Live loot tables changed: after the post-load passes on server start and {@code /reload}, and after
     * every loot table editor save (single table or bulk item rule).
     */
    @FunctionalInterface
    public interface LootTablesChanged {
        void onLootTablesChanged(MinecraftServer server);
    }

    public static final List<RecipesChanged> RECIPES_CHANGED = new CopyOnWriteArrayList<>();
    public static final List<LootTablesChanged> LOOT_TABLES_CHANGED = new CopyOnWriteArrayList<>();

    public static void fireRecipesChanged(MinecraftServer server) {
        for (RecipesChanged listener : RECIPES_CHANGED) {
            try {
                listener.onRecipesChanged(server);
            } catch (Throwable t) {
                Datarewriter.LOGGER.error("A RECIPES_CHANGED listener failed", t);
            }
        }
    }

    public static void fireLootTablesChanged(MinecraftServer server) {
        for (LootTablesChanged listener : LOOT_TABLES_CHANGED) {
            try {
                listener.onLootTablesChanged(server);
            } catch (Throwable t) {
                Datarewriter.LOGGER.error("A LOOT_TABLES_CHANGED listener failed", t);
            }
        }
    }
}
