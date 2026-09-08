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
import dev.emi.emi.api.stack.EmiStackInteraction;
import dev.emi.emi.api.widget.Bounds;
import net.ledok.datarewriter.client.gui.CompositeEntryScreen;
import net.ledok.datarewriter.client.gui.ItemSelectScreen;
import net.ledok.datarewriter.client.gui.LootTableEditorScreen;
import net.ledok.datarewriter.client.gui.RecipeEditorScreen;
import net.ledok.datarewriter.client.gui.RecipeTweaksScreen;
import net.ledok.datarewriter.menu.EditorMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.material.Fluid;

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

        // Same drag & drop for the loot table editor's entry slots.
        registry.addDragDropHandler(LootTableEditorScreen.class, new EmiDragDropHandler<>() {
            @Override
            public boolean dropStack(LootTableEditorScreen screen, EmiIngredient ingredient, int x, int y) {
                Object key = firstKey(ingredient);
                return key != null && screen.emiDrop(x, y, key);
            }

            @Override
            public void render(LootTableEditorScreen screen, EmiIngredient dragged,
                               GuiGraphics graphics, int mouseX, int mouseY, float delta) {
                Object key = firstKey(dragged);
                if (key != null) {
                    screen.renderEmiDropTargets(graphics, key);
                }
            }
        });
        registry.addScreenBoundsProvider(LootTableEditorScreen.class, screen -> {
            int[] bounds = screen.emiScreenBounds();
            return new Bounds(bounds[0], bounds[1], bounds[2], bounds[3]);
        });

        // The "pick one item" step of the loot picker's filter/bulk actions:
        // dropping an EMI item anywhere on the window selects it.
        registry.addDragDropHandler(ItemSelectScreen.class, new EmiDragDropHandler<>() {
            @Override
            public boolean dropStack(ItemSelectScreen screen, EmiIngredient ingredient, int x, int y) {
                Object key = firstKey(ingredient);
                return key != null && screen.emiDrop(x, y, key);
            }

            @Override
            public void render(ItemSelectScreen screen, EmiIngredient dragged,
                               GuiGraphics graphics, int mouseX, int mouseY, float delta) {
                Object key = firstKey(dragged);
                if (key != null) {
                    screen.renderEmiDropTargets(graphics, key);
                }
            }
        });
        registry.addScreenBoundsProvider(ItemSelectScreen.class, screen -> {
            int[] bounds = screen.emiScreenBounds();
            return new Bounds(bounds[0], bounds[1], bounds[2], bounds[3]);
        });

        // Bulk recipe edits screen: dropping onto its from/to slots.
        registry.addDragDropHandler(RecipeTweaksScreen.class, new EmiDragDropHandler<>() {
            @Override
            public boolean dropStack(RecipeTweaksScreen screen, EmiIngredient ingredient, int x, int y) {
                Object key = firstKey(ingredient);
                return key != null && screen.emiDrop(x, y, key);
            }

            @Override
            public void render(RecipeTweaksScreen screen, EmiIngredient dragged,
                               GuiGraphics graphics, int mouseX, int mouseY, float delta) {
                Object key = firstKey(dragged);
                if (key != null) {
                    screen.renderEmiDropTargets(graphics, key);
                }
            }
        });
        registry.addScreenBoundsProvider(RecipeTweaksScreen.class, screen -> {
            int[] bounds = screen.emiScreenBounds();
            return new Bounds(bounds[0], bounds[1], bounds[2], bounds[3]);
        });

        // Sub-editor for composite loot entries (alternatives/group/sequence).
        registry.addDragDropHandler(CompositeEntryScreen.class, new EmiDragDropHandler<>() {
            @Override
            public boolean dropStack(CompositeEntryScreen screen, EmiIngredient ingredient, int x, int y) {
                Object key = firstKey(ingredient);
                return key != null && screen.emiDrop(x, y, key);
            }

            @Override
            public void render(CompositeEntryScreen screen, EmiIngredient dragged,
                               GuiGraphics graphics, int mouseX, int mouseY, float delta) {
                Object key = firstKey(dragged);
                if (key != null) {
                    screen.renderEmiDropTargets(graphics, key);
                }
            }
        });
        registry.addScreenBoundsProvider(CompositeEntryScreen.class, screen -> {
            int[] bounds = screen.emiScreenBounds();
            return new Bounds(bounds[0], bounds[1], bounds[2], bounds[3]);
        });

        // R/U (recipes/uses) over our screens. EMI reads the hovered stack from
        // the screen's Slot list, and our menus are slot-less — without these
        // providers both the editor's own slots and the inventory panel are
        // invisible to it. Never clickable: clicks stay ours (picking items up).
        registry.addStackProvider(RecipeEditorScreen.class,
                (screen, x, y) -> interaction(screen.emiStackAt(x, y)));
        registry.addStackProvider(LootTableEditorScreen.class,
                (screen, x, y) -> interaction(screen.emiStackAt(x, y)));
        registry.addStackProvider(CompositeEntryScreen.class,
                (screen, x, y) -> interaction(screen.emiStackAt(x, y)));
        registry.addStackProvider(RecipeTweaksScreen.class,
                (screen, x, y) -> interaction(screen.emiStackAt(x, y)));
        registry.addStackProvider(ItemSelectScreen.class,
                (screen, x, y) -> interaction(screen.emiStackAt(x, y)));

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
                return backingRecipe(recipe) != null;
            }

            @Override
            public boolean alwaysDisplaySupport(EmiRecipe recipe) {
                return true; // no ingredients are consumed, so the fill always "can craft"
            }

            @Override
            public boolean canCraft(EmiRecipe recipe, EmiCraftContext<EditorMenu> context) {
                return backingRecipe(recipe) != null;
            }

            @Override
            public boolean craft(EmiRecipe recipe, EmiCraftContext<EditorMenu> context) {
                Minecraft minecraft = Minecraft.getInstance();
                RecipeHolder<?> holder = backingRecipe(recipe);
                if (holder == null || !(context.getScreen() instanceof RecipeEditorScreen screen)) {
                    return false;
                }
                if (!screen.loadRecipe(holder)) {
                    return false;
                }
                minecraft.setScreen(screen);
                return true;
            }
        });
    }

    /**
     * The real recipe behind an EMI recipe card. Plugins often give their
     * cards synthetic ids (Create: "create:/mixing/&lt;ns&gt;/&lt;path&gt;"),
     * so try the backing recipe first, then the id itself, then the id with
     * a "/category/" prefix stripped off.
     */
    private static RecipeHolder<?> backingRecipe(EmiRecipe recipe) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return null;
        }
        try {
            RecipeHolder<?> backing = recipe.getBackingRecipe();
            if (backing != null) {
                return backing;
            }
        } catch (Exception ignored) {
            // some plugins throw from getBackingRecipe; fall through to the id
        }
        ResourceLocation id = recipe.getId();
        if (id == null) {
            return null;
        }
        var manager = minecraft.level.getRecipeManager();
        RecipeHolder<?> direct = manager.byKey(id).orElse(null);
        if (direct != null) {
            return direct;
        }
        String path = id.getPath();
        if (path.startsWith("/")) {
            String[] parts = path.substring(1).split("/");
            // "/<category>/<namespace>/<rest...>" — the rest is the recipe path.
            for (int i = 1; i + 1 < parts.length; i++) {
                ResourceLocation candidate = ResourceLocation.tryBuild(parts[i],
                        String.join("/", java.util.Arrays.copyOfRange(parts, i + 1, parts.length)));
                if (candidate != null) {
                    RecipeHolder<?> holder = manager.byKey(candidate).orElse(null);
                    if (holder != null) {
                        return holder;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Wraps what a screen reports under the cursor (an ItemStack, an Item, a
     * Fluid or a "#tag" string) as an EMI interaction. Marked not-clickable:
     * hover lookups like R/U still work, while mouse clicks keep going to the
     * editor instead of being swallowed by EMI.
     */
    private static EmiStackInteraction interaction(Object hovered) {
        EmiIngredient ingredient = ingredient(hovered);
        return ingredient == null ? EmiStackInteraction.EMPTY
                : new EmiStackInteraction(ingredient, null, false);
    }

    private static EmiIngredient ingredient(Object hovered) {
        if (hovered instanceof ItemStack stack) {
            return EmiStack.of(stack);
        }
        if (hovered instanceof Item item) {
            return EmiStack.of(item);
        }
        if (hovered instanceof Fluid fluid) {
            return EmiStack.of(fluid);
        }
        if (hovered instanceof String ref && ref.startsWith("#")) {
            ResourceLocation id = ResourceLocation.tryParse(ref.substring(1));
            return id == null ? null : EmiIngredient.of(TagKey.create(Registries.ITEM, id));
        }
        return null;
    }

    private static Object firstKey(EmiIngredient ingredient) {
        List<EmiStack> stacks = ingredient.getEmiStacks();
        return stacks.isEmpty() ? null : stacks.getFirst().getKey();
    }
}
