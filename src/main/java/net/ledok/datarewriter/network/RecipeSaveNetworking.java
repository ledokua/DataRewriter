package net.ledok.datarewriter.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.ledok.datarewriter.Datarewriter;
import net.ledok.datarewriter.RecipeRewriter;
import net.ledok.datarewriter.RewriteState;
import net.ledok.datarewriter.config.ConfigLoader;
import net.ledok.datarewriter.config.GuiRecipeSaver;
import net.ledok.datarewriter.config.ItemMatch;
import net.ledok.datarewriter.config.RewriteConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;

/**
 * Server side of the recipe editor. The editor itself lives in the client
 * source set; vanilla clients never send this payload, and servers without
 * the mod simply have no receiver (the editor checks for that before sending).
 */
public final class RecipeSaveNetworking {
    private RecipeSaveNetworking() {
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(SaveRecipePayload.TYPE, SaveRecipePayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(BulkRecipeEditPayload.TYPE, BulkRecipeEditPayload.STREAM_CODEC);
        registerBulkReceiver();
        ServerPlayNetworking.registerGlobalReceiver(SaveRecipePayload.TYPE, (payload, context) -> {
            var player = context.player();
            if (!player.hasPermissions(2)) {
                player.sendSystemMessage(prefix().append(Component.literal(
                        "You need to be an operator to save recipes.").withStyle(ChatFormatting.RED)));
                return;
            }
            MinecraftServer server = context.server();
            GuiRecipeSaver.SaveResult result = GuiRecipeSaver.save(server, payload.recipeJson());
            if (result.error() != null) {
                player.sendSystemMessage(prefix().append(Component.literal(
                        "Recipe not saved — " + result.error()).withStyle(ChatFormatting.RED)));
                return;
            }
            // Applied directly to the running RecipeManager — no data reload.
            // The config entry written above keeps it across restarts/reloads.
            RecipeRewriter.applyLive(server, result.recipe());
            Datarewriter.LOGGER.info("Recipe editor: saved and applied {}", result.recipe().id());
            player.sendSystemMessage(prefix().append(Component.literal(
                    "Recipe " + result.recipe().id() + " saved to config/datarewriter/"
                            + GuiRecipeSaver.FILE_NAME + " and applied.")
                    .withStyle(ChatFormatting.GRAY)));
        });
    }

    private static void registerBulkReceiver() {
        ServerPlayNetworking.registerGlobalReceiver(BulkRecipeEditPayload.TYPE, (payload, context) -> {
            var player = context.player();
            if (!player.hasPermissions(2)) {
                player.sendSystemMessage(prefix().append(Component.literal(
                        "You need to be an operator to edit recipes.").withStyle(ChatFormatting.RED)));
                return;
            }
            MinecraftServer server = context.server();
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
            Datarewriter.LOGGER.info("Recipe tweaks: {} ({} -> {}): {}",
                    payload.mode(), payload.from(), payload.to(), result);
            player.sendSystemMessage(prefix().append(Component.literal(result
                    + ". Saved as a rule in config/datarewriter/" + GuiRecipeSaver.FILE_NAME
                    + " — delete it there to undo.").withStyle(ChatFormatting.GRAY)));
        });
    }

    private static void fail(net.minecraft.server.level.ServerPlayer player, String message) {
        player.sendSystemMessage(prefix().append(Component.literal(
                "Recipe edit failed — " + message).withStyle(ChatFormatting.RED)));
    }

    private static MutableComponent prefix() {
        return Component.literal("[DataRewriter] ").withStyle(ChatFormatting.GOLD);
    }
}
