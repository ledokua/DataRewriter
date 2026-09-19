package net.ledok.datarewriter.client;

import net.ledok.datarewriter.Datarewriter;
import net.ledok.datarewriter.client.gui.LootTableEditorScreen;
import net.ledok.datarewriter.client.gui.RecipeEditorScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.lwjgl.glfw.GLFW;

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
    /** Null unless the property is set — then the harness is armed. */
    private static Queue<String> pending;
    /** Entries prefixed "loot:" open the loot table editor on that table instead. */
    private static boolean sample;
    private static int timer;
    private static String current;

    private ScreenshotHarness() {
    }

    static void init() {
        String property = System.getProperty("datarewriter.screenshotTypes");
        if (property == null || property.isBlank()) {
            return;
        }
        pending = new ArrayDeque<>(List.of(property.split(",")));
        sample = Boolean.getBoolean("datarewriter.screenshotSample");
    }

    /** End of client tick; a no-op unless {@link #init()} armed the harness. */
    static void tick(Minecraft client) {
        if (pending == null || client.player == null || client.level == null) {
            return;
        }
        if (current == null) {
            if (pending.isEmpty()) {
                client.stop();
                return;
            }
            current = pending.poll().trim();
            timer = 0;
            if (current.startsWith("loot:")) {
                client.setScreen(new LootTableEditorScreen(null, current.substring(5)));
                return;
            }
            RecipeEditorScreen screen = new RecipeEditorScreen();
            if (!screen.selectType(current)) {
                Datarewriter.LOGGER.warn("[screenshots] no layout for {}", current);
                current = null;
                return;
            }
            client.setScreen(screen);
            RecipeHolder<?> existing = sample ? null : firstRecipeOf(client, current);
            if (existing == null || !screen.loadRecipe(existing)) {
                screen.fillSample();
            }
            return;
        }
        timer++;
        if (timer == 5) {
            // Park the cursor in the corner: whatever it hovers draws a
            // tooltip over the layout and hides the very thing being checked.
            GLFW.glfwSetCursorPos(client.getWindow().getWindow(), 2, 2);
        }
        if (timer == 40) {
            Screenshot.grab(client.gameDirectory, current.replace(':', '_').replace('/', '_') + ".png",
                    client.getMainRenderTarget(), message -> Datarewriter.LOGGER.info("[screenshots] {}", message.getString()));
        } else if (timer == 45) {
            client.setScreen(null);
            current = null;
        }
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
