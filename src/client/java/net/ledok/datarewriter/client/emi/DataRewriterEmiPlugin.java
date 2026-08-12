package net.ledok.datarewriter.client.emi;

import dev.emi.emi.api.EmiDragDropHandler;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.Bounds;
import net.ledok.datarewriter.client.gui.RecipeEditorScreen;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * Optional EMI integration: drag items/fluids from EMI's panel straight into
 * the recipe editor's ghost slots (AE2 pattern-terminal style). Only loaded
 * by EMI itself via the "emi" entrypoint, so the mod works without EMI.
 */
@EmiEntrypoint
public class DataRewriterEmiPlugin implements EmiPlugin {
    @Override
    public void register(EmiRegistry registry) {
        registry.addDragDropHandler(RecipeEditorScreen.class, new EmiDragDropHandler<>() {
            @Override
            public boolean dropStack(RecipeEditorScreen screen, EmiIngredient ingredient, int x, int y) {
                Object key = firstKey(ingredient);
                return key != null && screen.emiDrop(x, y, key);
            }

            @Override
            public void render(RecipeEditorScreen screen, EmiIngredient dragged,
                               GuiGraphics graphics, int mouseX, int mouseY, float delta) {
                Object key = firstKey(dragged);
                if (key != null) {
                    screen.renderEmiDropTargets(graphics, key);
                }
            }
        });

        // EMI only shows its panels on container screens by default; a bounds
        // provider opts a plain Screen in and tells EMI where the UI sits so
        // the panels appear beside it.
        registry.addScreenBoundsProvider(RecipeEditorScreen.class, screen -> {
            int[] bounds = screen.emiScreenBounds();
            return new Bounds(bounds[0], bounds[1], bounds[2], bounds[3]);
        });
    }

    private static Object firstKey(EmiIngredient ingredient) {
        List<EmiStack> stacks = ingredient.getEmiStacks();
        return stacks.isEmpty() ? null : stacks.getFirst().getKey();
    }
}
