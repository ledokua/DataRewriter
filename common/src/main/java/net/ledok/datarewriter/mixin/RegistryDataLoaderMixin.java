package net.ledok.datarewriter.mixin;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Decoder;
import net.ledok.datarewriter.RegistryRewriter;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.WritableRegistry;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.Reader;
import java.util.List;
import java.util.Map;

/**
 * Applies the config's {@code registries} rules while the world's datapack registries load — the
 * registry twin of the recipe/loot hooks. Only the resource path is touched: a client joining a
 * remote server receives that server's (already rewritten) entries through the network path.
 */
@Mixin(RegistryDataLoader.class)
public abstract class RegistryDataLoaderMixin {

    /** A world start's registry load begins — (re)read the config and reset the rewrite stats. */
    @Inject(
            method = "load(Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/core/RegistryAccess;Ljava/util/List;)Lnet/minecraft/core/RegistryAccess$Frozen;",
            at = @At("HEAD")
    )
    private static void datarewriter$beginLoad(ResourceManager resourceManager, RegistryAccess registryAccess,
                                               List<RegistryDataLoader.RegistryData<?>> registries,
                                               CallbackInfoReturnable<RegistryAccess.Frozen> cir) {
        RegistryRewriter.beginLoad(registries);
    }

    /** Remembers each registry's element codec, for validation and the ritual editor's encode/decode. */
    @Inject(method = "loadContentsFromManager", at = @At("HEAD"))
    private static <E> void datarewriter$stashDecoder(ResourceManager resourceManager,
                                                      RegistryOps.RegistryInfoLookup lookup,
                                                      WritableRegistry<E> registry, Decoder<E> decoder,
                                                      Map<ResourceKey<?>, Exception> errors, CallbackInfo ci) {
        RegistryRewriter.stashDecoder(registry.key().location(), decoder);
    }

    /** {@code registries.remove}: a matching entry's file is never parsed or registered. */
    @Inject(method = "loadElementFromResource", at = @At("HEAD"), cancellable = true)
    private static <E> void datarewriter$maybeRemove(WritableRegistry<E> registry, Decoder<E> decoder,
                                                     RegistryOps<JsonElement> ops, ResourceKey<E> key,
                                                     Resource resource, RegistrationInfo info, CallbackInfo ci) {
        if (RegistryRewriter.removed(key)) {
            ci.cancel();
        }
    }

    /** {@code registries.add} (replacing) and {@code registries.modify}: the entry's JSON, rewritten before parsing. */
    @Redirect(
            method = "loadElementFromResource",
            at = @At(value = "INVOKE", target = "Lcom/google/gson/JsonParser;parseReader(Ljava/io/Reader;)Lcom/google/gson/JsonElement;")
    )
    private static JsonElement datarewriter$rewriteElement(Reader reader, WritableRegistry<?> registry,
                                                           Decoder<?> decoder, RegistryOps<JsonElement> ops,
                                                           ResourceKey<?> key, Resource resource,
                                                           RegistrationInfo info) {
        return RegistryRewriter.rewriteElement(JsonParser.parseReader(reader), key, decoder, ops);
    }

    /** {@code registries.add} (new ids): registered after the registry's datapack files. */
    @Inject(method = "loadContentsFromManager", at = @At("TAIL"))
    private static <E> void datarewriter$registerAdditions(ResourceManager resourceManager,
                                                           RegistryOps.RegistryInfoLookup lookup,
                                                           WritableRegistry<E> registry, Decoder<E> decoder,
                                                           Map<ResourceKey<?>, Exception> errors, CallbackInfo ci) {
        RegistryRewriter.registerAdditions(registry, decoder, lookup);
    }
}
