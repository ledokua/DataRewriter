package net.ledok.datarewriter.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.ledok.datarewriter.Datarewriter;
import net.ledok.datarewriter.RecipeRewriter;
import net.ledok.datarewriter.config.GuiRecipeSaver;
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

    private static MutableComponent prefix() {
        return Component.literal("[DataRewriter] ").withStyle(ChatFormatting.GOLD);
    }
}
