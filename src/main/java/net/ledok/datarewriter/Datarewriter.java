package net.ledok.datarewriter;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.ledok.datarewriter.command.ListCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Datarewriter implements ModInitializer {
    public static final String MOD_ID = "datarewriter";
    public static final Logger LOGGER = LoggerFactory.getLogger("DataRewriter");

    @Override
    public void onInitialize() {
        ListCommand.register();
        // Rules matching by output/input/type need parsed recipes and bound tags,
        // which are only available once the data reload has fully finished.
        ServerLifecycleEvents.SERVER_STARTED.register(RecipeRewriter::applyParsedRules);
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
            if (success) {
                RecipeRewriter.applyParsedRules(server);
                RewriteState.announce(server);
            }
        });
    }
}