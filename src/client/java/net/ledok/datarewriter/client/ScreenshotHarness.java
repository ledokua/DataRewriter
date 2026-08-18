package net.ledok.datarewriter.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.ledok.datarewriter.Datarewriter;
import net.ledok.datarewriter.client.gui.LootTableEditorScreen;
import net.ledok.datarewriter.client.gui.RecipeEditorScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

/**
 * Dev-only: with {@code -Ddatarewriter.screenshotTypes=a:b,c:d} the client,
 * once in a world, opens the recipe editor on each listed type (loading the
 * first existing recipe of that type, or sample values when there is none),
 * saves a screenshot to {@code screenshots/<type>.png} and finally quits.
 * Used to eyeball bundled layouts without clicking through them by hand.
 */
final class ScreenshotHarness {
    private ScreenshotHarness() {
    }

    static void init() {
        String property = System.getProperty("datarewriter.screenshotTypes");
        if (property == null || property.isBlank()) {
            return;
        }
        Queue<String> pending = new ArrayDeque<>(List.of(property.split(",")));
        // Entries prefixed "loot:" open the loot table editor on that table instead.
        boolean sample = Boolean.getBoolean("datarewriter.screenshotSample");
        int[] timer = {0};
        String[] current = {null};
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.level == null) {
                return;
            }
            if (current[0] == null) {
                if (pending.isEmpty()) {
                    client.stop();
                    return;
                }
                current[0] = pending.poll().trim();
                timer[0] = 0;
                if (current[0].startsWith("loot:")) {
                    client.setScreen(new LootTableEditorScreen(null, current[0].substring(5)));
                    return;
                }
                RecipeEditorScreen screen = new RecipeEditorScreen();
                if (!screen.selectType(current[0])) {
                    Datarewriter.LOGGER.warn("[screenshots] no layout for {}", current[0]);
                    current[0] = null;
                    return;
                }
                client.setScreen(screen);
                RecipeHolder<?> existing = sample ? null : firstRecipeOf(client, current[0]);
                if (existing == null || !screen.loadRecipe(existing)) {
                    screen.fillSample();
                }
                return;
            }
            timer[0]++;
            if (timer[0] == 40) {
                Screenshot.grab(client.gameDirectory, current[0].replace(':', '_').replace('/', '_') + ".png",
                        client.getMainRenderTarget(), message -> Datarewriter.LOGGER.info("[screenshots] {}", message.getString()));
            } else if (timer[0] == 45) {
                client.setScreen(null);
                current[0] = null;
            }
        });
    }

    private static RecipeHolder<?> firstRecipeOf(Minecraft client, String typeId) {
        ResourceLocation id = ResourceLocation.tryParse(typeId);
        for (RecipeHolder<?> holder : client.level.getRecipeManager().getRecipes()) {
            ResourceLocation type = BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType());
            if (type != null && type.equals(id)) {
                return holder;
            }
        }
        return null;
    }
}
