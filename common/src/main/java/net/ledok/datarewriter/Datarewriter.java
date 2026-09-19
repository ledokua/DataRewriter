package net.ledok.datarewriter;

import net.ledok.datarewriter.api.DataRewriterEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Loader-agnostic entry points; each loader module wires its own lifecycle events to these. */
public final class Datarewriter {
    public static final String MOD_ID = "datarewriter";
    public static final Logger LOGGER = LoggerFactory.getLogger("DataRewriter");

    private Datarewriter() {
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    /**
     * Server started. Rules matching by output/input/type need parsed recipes and bound tags, which are
     * only available once the data reload has fully finished. The loot post-injection pass also runs here —
     * after other mods' runtime loot injections (the loader's loot events) are in the registry.
     */
    public static void onServerStarted(MinecraftServer server) {
        RecipeRewriter.applyParsedRules(server);
        LootInjectionRewriter.apply(server);
        RewriteState.missingConfigTables(server);
        DataRewriterEvents.fireRecipesChanged(server);
        DataRewriterEvents.fireLootTablesChanged(server);
    }

    /** A successful {@code /reload}: the same passes, then a chat summary for online operators. */
    public static void onDataPackReloaded(MinecraftServer server) {
        RecipeRewriter.applyParsedRules(server);
        LootInjectionRewriter.apply(server);
        RewriteState.announce(server);
        DataRewriterEvents.fireRecipesChanged(server);
        DataRewriterEvents.fireLootTablesChanged(server);
    }
}
