package net.ledok.datarewriter.mixin;

import net.ledok.datarewriter.RegistryRewriter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Applies the config's {@code registries} rules while the world's datapack registries load — the
 * registry twin of the recipe/loot hooks. Since 1.21.9 each registry loads as its own parallel task:
 * this marks the start of a load, RegistryLoadTaskMixin and PendingRegistrationMixin do the rewriting.
 */
@Mixin(RegistryDataLoader.class)
public abstract class RegistryDataLoaderMixin {

    /** A world start's registry load from datapacks begins — reset the rewrite stats, remember the codecs. */
    @Inject(
            method = "load(Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/util/List;Ljava/util/List;Ljava/util/concurrent/Executor;Ljava/util/List;)Ljava/util/concurrent/CompletableFuture;",
            at = @At("HEAD")
    )
    private static void datarewriter$beginLoad(ResourceManager resourceManager,
                                               List<HolderLookup.RegistryLookup<?>> contextRegistries,
                                               List<RegistryDataLoader.RegistryData<?>> registries, Executor executor,
                                               List<Registry.PendingTags<?>> pendingTags,
                                               CallbackInfoReturnable<CompletableFuture<RegistryAccess.Frozen>> cir) {
        RegistryRewriter.beginLoad(registries);
    }
}
