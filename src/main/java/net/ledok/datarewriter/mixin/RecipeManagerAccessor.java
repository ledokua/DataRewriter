package net.ledok.datarewriter.mixin;

import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Swaps the live recipe map; callers then rerun {@code finalizeRecipeLoading} to rebuild the derived sets. */
@Mixin(RecipeManager.class)
public interface RecipeManagerAccessor {
    @Accessor("recipes")
    void datarewriter$setRecipes(RecipeMap recipes);
}
