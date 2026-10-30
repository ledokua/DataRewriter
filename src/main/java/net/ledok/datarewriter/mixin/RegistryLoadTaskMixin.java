package net.ledok.datarewriter.mixin;

import net.ledok.datarewriter.RegistryLoadContext;
import net.ledok.datarewriter.RegistryRewriter;
import net.minecraft.core.WritableRegistry;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.RegistryLoadTask;
import net.minecraft.resources.RegistryOps;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.stream.Stream;

@Mixin(RegistryLoadTask.class)
public abstract class RegistryLoadTaskMixin<T> implements RegistryLoadContext {
    @Shadow
    @Final
    private Object registryWriteLock;

    @Shadow
    @Final
    private WritableRegistry<T> registry;

    @Shadow
    @Final
    protected RegistryDataLoader.RegistryData<T> data;

    @Unique
    private volatile RegistryOps.RegistryInfoLookup datarewriter$context;

    @Override
    public void datarewriter$setContext(RegistryOps.RegistryInfoLookup context) {
        datarewriter$context = context;
    }

    /** {@code registries.add} (new ids): registered after the registry's datapack files. */
    @Inject(method = "registerElements", at = @At("TAIL"))
    private void datarewriter$registerAdditions(Stream<?> elements, CallbackInfo ci) {
        RegistryOps.RegistryInfoLookup context = datarewriter$context;
        if (context != null) {
            synchronized (registryWriteLock) {
                RegistryRewriter.registerAdditions(registry, data.elementCodec(), context);
            }
        }
    }
}
