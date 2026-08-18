package net.ledok.datarewriter;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.ledok.datarewriter.command.ListCommand;
import net.ledok.datarewriter.menu.EditorMenu;
import net.ledok.datarewriter.menu.LootEditorMenu;
import net.ledok.datarewriter.network.LootEditNetworking;
import net.ledok.datarewriter.network.RecipeSaveNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Datarewriter implements ModInitializer {
    public static final String MOD_ID = "datarewriter";
    public static final Logger LOGGER = LoggerFactory.getLogger("DataRewriter");

    @Override
    public void onInitialize() {
        ListCommand.register();
        RecipeSaveNetworking.register();
        LootEditNetworking.register();
        EditorMenu.register();
        LootEditorMenu.register();
        // Rules matching by output/input/type need parsed recipes and bound tags,
        // which are only available once the data reload has fully finished.
        // The loot post-injection pass also runs here — after other mods'
        // runtime loot injections (Fabric loot events) are in the registry.
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            RecipeRewriter.applyParsedRules(server);
            LootInjectionRewriter.apply(server);
            RewriteState.missingConfigTables(server);
        });
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
            if (success) {
                RecipeRewriter.applyParsedRules(server);
                LootInjectionRewriter.apply(server);
                RewriteState.announce(server);
            }
        });
    }
}