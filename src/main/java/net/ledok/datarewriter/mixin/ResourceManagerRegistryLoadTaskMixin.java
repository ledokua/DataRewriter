package net.ledok.datarewriter.mixin;

import net.ledok.datarewriter.RegistryLoadContext;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceManagerRegistryLoadTask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** A registry loading from datapack resources: keeps its parse lookup for {@code registries.add}. */
@Mixin(ResourceManagerRegistryLoadTask.class)
public abstract class ResourceManagerRegistryLoadTaskMixin {
    @Inject(method = "load", at = @At("HEAD"))
    private void datarewriter$keepContext(RegistryOps.RegistryInfoLookup context, Executor executor,
                                          CallbackInfoReturnable<CompletableFuture<?>> cir) {
        ((RegistryLoadContext) this).datarewriter$setContext(context);
    }
}
