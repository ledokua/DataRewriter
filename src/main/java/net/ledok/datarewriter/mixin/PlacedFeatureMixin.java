package net.ledok.datarewriter.mixin;

import net.ledok.datarewriter.RemovedFeatures;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Biome decoration places each of a biome's features through this method (and only biome decoration
 * does): a removed feature simply places nothing. Features nested inside other features are untouched.
 */
@Mixin(PlacedFeature.class)
public abstract class PlacedFeatureMixin {
    @Inject(method = "placeWithBiomeCheck", at = @At("HEAD"), cancellable = true)
    private void datarewriter$skipRemoved(WorldGenLevel level, ChunkGenerator generator, RandomSource random,
                                          BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (RemovedFeatures.isRemoved((PlacedFeature) (Object) this, level.registryAccess())) {
            cir.setReturnValue(false);
        }
    }
}
