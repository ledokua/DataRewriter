package net.ledok.datarewriter.network;

import net.ledok.datarewriter.Datarewriter;
import net.ledok.datarewriter.RecipeRewriter;
import net.ledok.datarewriter.RewriteState;
import net.ledok.datarewriter.api.DataRewriterEvents;
import net.ledok.datarewriter.config.ConfigLoader;
import net.ledok.datarewriter.config.GuiRecipeSaver;
import net.ledok.datarewriter.config.ItemMatch;
import net.ledok.datarewriter.config.RewriteConfig;
import net.ledok.datarewriter.platform.Network;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server side of the recipe editor and the recipe tweaker. Vanilla clients
 * never send these payloads, and servers without the mod simply have no
 * receiver (the editor checks for that before sending). Each loader module
 * registers the payloads and routes them here; both handlers require
 * permission level 2.
 */
public final class RecipeSaveNetworking {
    private RecipeSaveNetworking() {
    }

    /** {@code SaveRecipePayload}: one recipe from the editor. */
    public static void handleSave(MinecraftServer server, ServerPlayer player, SaveRecipePayload payload) {
        if (!player.hasPermissions(2)) {
            fail(player, "you need to be an operator (permission level 2) on this server");
            return;
        }
        GuiRecipeSaver.SaveResult result = GuiRecipeSaver.save(server, payload.recipeJson());
        if (result.error() != null) {
            fail(player, result.error());
            return;
        }
        // Applied directly to the running RecipeManager — no data reload.
        // The config entry written above keeps it across restarts/reloads.
        RecipeRewriter.applyLive(server, result.recipe());
        DataRewriterEvents.fireRecipesChanged(server);
        Datarewriter.LOGGER.info("Recipe editor: saved and applied {}", result.recipe().id());
        succeed(player, "Recipe " + result.recipe().id() + " saved to config/datarewriter/"
                + GuiRecipeSaver.FILE_NAME + " on the server and applied live.");
    }

    /** {@code BulkRecipeEditPayload}: a remove-by-output/input or replace_ingredients rule from the tweaker. */
    public static void handleBulk(MinecraftServer server, ServerPlayer player, BulkRecipeEditPayload payload) {
        if (!player.hasPermissions(2)) {
            fail(player, "you need to be an operator (permission level 2) on this server");
            return;
        }
        String from = ConfigLoader.normalizeItemOrTag(payload.from());
        if (from == null) {
            fail(player, "'" + payload.from() + "' is not a valid item id or #tag");
            return;
        }
        String result;
        switch (payload.mode()) {
            case "remove_output", "remove_input" -> {
                boolean byOutput = payload.mode().equals("remove_output");
                String error = GuiRecipeSaver.saveBulkRemoval(byOutput ? "output" : "input", from);
                if (error != null) {
                    fail(player, error);
                    return;
                }
                RewriteState.appendRemovalRule(new RewriteConfig.RemovalRule(null, null, null,
                        byOutput ? from : null, byOutput ? null : from, GuiRecipeSaver.FILE_NAME));
                int removed = RecipeRewriter.applyRemovalRules(server);
                result = "Removed " + removed + " recipes "
                        + (byOutput ? "producing " : "using ") + from
                        + (byOutput ? " (recipes you added are kept)" : "");
            }
            case "replace" -> {
                String to = ConfigLoader.normalizeItemOrTag(payload.to());
                if (to == null) {
                    fail(player, "'" + payload.to() + "' is not a valid item id or #tag");
                    return;
                }
                String error = GuiRecipeSaver.saveReplaceIngredients(from, to);
                if (error != null) {
                    fail(player, error);
                    return;
                }
                RewriteState.appendIngredientReplacement(new RewriteConfig.IngredientReplacement(
                        ItemMatch.parse(from), to, GuiRecipeSaver.FILE_NAME));
                int changed = RecipeRewriter.applyIngredientReplacements(server);
                result = "Replaced ingredient " + from + " with " + to + " in " + changed + " recipes";
            }
            default -> {
                fail(player, "unknown bulk edit mode '" + payload.mode() + "'");
                return;
            }
        }
        DataRewriterEvents.fireRecipesChanged(server);
        Datarewriter.LOGGER.info("Recipe tweaks: {} ({} -> {}): {}",
                payload.mode(), payload.from(), payload.to(), result);
        succeed(player, result + ". Saved as a rule in config/datarewriter/" + GuiRecipeSaver.FILE_NAME
                + " on the server — delete it there to undo.");
    }

    private static void fail(ServerPlayer player, String message) {
        String text = "Recipe not saved — " + message;
        Datarewriter.LOGGER.warn("Recipe editor ({}): {}", player.getGameProfile().getName(), text);
        player.sendSystemMessage(prefix().append(Component.literal(text).withStyle(ChatFormatting.RED)));
        if (Network.INSTANCE.canSendToPlayer(player, LootPayloads.SaveResult.TYPE)) {
            Network.INSTANCE.sendToPlayer(player, new LootPayloads.SaveResult(false, text));
        }
    }

    /**
     * Reports a save that worked. The edit is already live on the server, but
     * the client-side recipe list is only pushed on demand — pushing it makes
     * EMI/JEI/REI reload from scratch, which would interrupt an editing session
     * after every save. So the answer carries the refresh reminder (the editor
     * screen shows it, where chat isn't visible) plus a chat button to do it.
     */
    private static void succeed(ServerPlayer player, String message) {
        String notice = GuiRecipeSaver.takeNotice();
        if (notice != null) {
            message += " (" + notice + ")";
        }
        int pending = RecipeRewriter.pendingClientEdits();
        if (pending > 0) {
            message += " Keep editing — " + pending + " edit" + (pending == 1 ? " is" : "s are")
                    + " waiting for /datarewriter reload to show up in EMI.";
        }
        player.sendSystemMessage(prefix().append(Component.literal(message).withStyle(ChatFormatting.GRAY)));
        if (pending > 0) {
            player.sendSystemMessage(prefix().append(Component.literal("[Refresh recipe list now]")
                    .withStyle(style -> style.withColor(ChatFormatting.GREEN)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                    "/datarewriter reload"))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.literal("/datarewriter reload"))))));
        }
        if (Network.INSTANCE.canSendToPlayer(player, LootPayloads.SaveResult.TYPE)) {
            Network.INSTANCE.sendToPlayer(player, new LootPayloads.SaveResult(true, message));
        }
    }

    private static MutableComponent prefix() {
        return Component.literal("[DataRewriter] ").withStyle(ChatFormatting.GOLD);
    }
}
