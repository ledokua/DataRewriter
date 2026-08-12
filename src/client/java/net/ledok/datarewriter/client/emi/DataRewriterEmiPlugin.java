package net.ledok.datarewriter.client.emi;

import dev.emi.emi.api.EmiDragDropHandler;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.recipe.EmiPlayerInventory;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.handler.EmiCraftContext;
import dev.emi.emi.api.recipe.handler.EmiRecipeHandler;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.Bounds;
import net.ledok.datarewriter.client.gui.RecipeEditorScreen;
import net.ledok.datarewriter.menu.EditorMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.crafting.RecipeHolder;

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

        // The AE2-style fill button on EMI recipe cards: loads the viewed
        // recipe into the editor (nothing is crafted or consumed).
        registry.addRecipeHandler(EditorMenu.TYPE, new EmiRecipeHandler<>() {
            @Override
            public EmiPlayerInventory getInventory(AbstractContainerScreen<EditorMenu> screen) {
                // NOT EmiPlayerInventory.of(...) — that routes back through the
                // screen's recipe handler (this class) and recurses infinitely.
                return new EmiPlayerInventory(Minecraft.getInstance().player);
            }

            @Override
            public boolean supportsRecipe(EmiRecipe recipe) {
                return recipe.getId() != null;
            }

            @Override
            public boolean alwaysDisplaySupport(EmiRecipe recipe) {
                return true; // no ingredients are consumed, so the fill always "can craft"
            }

            @Override
            public boolean canCraft(EmiRecipe recipe, EmiCraftContext<EditorMenu> context) {
                Minecraft minecraft = Minecraft.getInstance();
                return recipe.getId() != null && minecraft.level != null
                        && minecraft.level.getRecipeManager().byKey(recipe.getId()).isPresent();
            }

            @Override
            public boolean craft(EmiRecipe recipe, EmiCraftContext<EditorMenu> context) {
                Minecraft minecraft = Minecraft.getInstance();
                if (recipe.getId() == null || minecraft.level == null
                        || !(context.getScreen() instanceof RecipeEditorScreen screen)) {
                    return false;
                }
                RecipeHolder<?> holder = minecraft.level.getRecipeManager()
                        .byKey(recipe.getId()).orElse(null);
                if (holder == null || !screen.loadRecipe(holder)) {
                    return false;
                }
                minecraft.setScreen(screen);
                return true;
            }
        });
    }

    private static Object firstKey(EmiIngredient ingredient) {
        List<EmiStack> stacks = ingredient.getEmiStacks();
        return stacks.isEmpty() ? null : stacks.getFirst().getKey();
    }
}
