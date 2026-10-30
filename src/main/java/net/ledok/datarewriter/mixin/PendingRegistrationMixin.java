package net.ledok.datarewriter.mixin;

import com.google.gson.JsonElement;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Decoder;
import net.ledok.datarewriter.RegistryRewriter;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.StrictJsonParser;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.Reader;

/** One registry entry read from a resource file (datapacks, or the client's copy of a known pack). */
@Mixin(targets = "net.minecraft.resources.RegistryLoadTask$PendingRegistration")
public abstract class PendingRegistrationMixin {

    /** {@code registries.remove}: a matching entry's file is never parsed or registered. */
    @Inject(method = "loadFromResource", at = @At("HEAD"), cancellable = true)
    private static <T> void datarewriter$maybeRemove(Decoder<T> decoder, RegistryOps<JsonElement> ops,
                                                     ResourceKey<T> key, Resource resource,
                                                     CallbackInfoReturnable<Either<T, Exception>> cir) {
        if (RegistryRewriter.removed(key)) {
            // NeoForge's "conditions not met" marker: skipped quietly, not reported as a load error.
            cir.setReturnValue(Either.right(RegistryLoadTaskAccessor.datarewriter$skippedMarker()));
        }
    }

    /** {@code registries.add} (replacing) and {@code registries.modify}: the entry's JSON, rewritten before parsing. */
    @Redirect(
            method = "loadFromResource",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/StrictJsonParser;parse(Ljava/io/Reader;)Lcom/google/gson/JsonElement;")
    )
    private static JsonElement datarewriter$rewriteElement(Reader reader, Decoder<?> decoder,
                                                           RegistryOps<JsonElement> ops, ResourceKey<?> key,
                                                           Resource resource) {
        return RegistryRewriter.rewriteElement(StrictJsonParser.parse(reader), key, decoder, ops);
    }
}
