package net.ledok.datarewriter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Decoder;
import com.mojang.serialization.Encoder;
import com.mojang.serialization.JsonOps;
import net.ledok.datarewriter.mixin.HolderReferenceInvoker;
import net.ledok.datarewriter.mixin.MappedRegistryAccessor;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Applies a datapack registry edit (a Hephaestus Forge ritual, say) to the running server without a
 * restart — the registry twin of {@link LootLiveApplier}, using the same holder-rebinding trick and
 * the element codecs {@link RegistryRewriter} stashed while the world loaded. Synced registries reach
 * OTHER clients only when they next join; the server itself (and so the game logic) sees the change
 * immediately.
 */
public final class RegistryLiveApplier {
    private RegistryLiveApplier() {
    }

    /** Either an error message or the parsed entry plus its registry. */
    public record Parsed(@Nullable String error, @Nullable Registry<Object> registry, @Nullable Object value) {
        static Parsed fail(String error) {
            return new Parsed(error, null, null);
        }
    }

    /** Validates entry JSON with the registry's own codec, against the server's loaded registries. */
    @SuppressWarnings("unchecked")
    public static Parsed parse(MinecraftServer server, ResourceLocation registryId, JsonObject entryJson) {
        Decoder<?> decoder = RegistryRewriter.decoder(registryId);
        if (decoder == null) {
            return Parsed.fail("'" + registryId + "' is not a datapack registry this world loaded");
        }
        ResourceKey<Registry<Object>> key = ResourceKey.createRegistryKey(registryId);
        Optional<Registry<Object>> registry = server.registryAccess().registry(key);
        if (registry.isEmpty()) {
            return Parsed.fail("registry '" + registryId + "' is not present on this server");
        }
        try {
            DataResult<Object> result = ((Decoder<Object>) decoder)
                    .parse(RegistryOps.create(JsonOps.INSTANCE, server.registryAccess()), entryJson);
            if (result.error().isPresent()) {
                return Parsed.fail("rejected by the registry's parser: " + result.error().get().message());
            }
            return new Parsed(null, registry.get(), result.result().orElseThrow());
        } catch (Throwable t) {
            return Parsed.fail("rejected by the registry's parser: " + t);
        }
    }

    /** Swaps (or registers) the parsed entry in the live registry. Returns an error or null. */
    @SuppressWarnings("unchecked")
    public static @Nullable String bind(Parsed parsed, ResourceLocation entryId) {
        Registry<Object> registry = parsed.registry();
        if (!(registry instanceof MappedRegistry<Object> mapped)) {
            return "cannot apply live: the registry is a " + registry.getClass().getName()
                    + " (the change still applies at the next world load)";
        }
        MappedRegistryAccessor<Object> accessor = (MappedRegistryAccessor<Object>) (Object) mapped;
        ResourceKey<Object> key = ResourceKey.create(
                (ResourceKey<? extends Registry<Object>>) registry.key(), entryId);
        var existing = mapped.getHolder(entryId);
        if (existing.isPresent()) {
            Holder.Reference<Object> holder = existing.get();
            Object old = holder.value();
            HolderReferenceInvoker<Object> invoker = (HolderReferenceInvoker<Object>) (Object) holder;
            invoker.datarewriter$bindValue(parsed.value());
            // Keep the value-keyed maps consistent with the new instance.
            if (accessor.datarewriter$getByValue().remove(old) != null) {
                accessor.datarewriter$getByValue().put(parsed.value(), holder);
            }
            var toId = accessor.datarewriter$getToId();
            if (toId.containsKey(old)) {
                toId.put(parsed.value(), toId.removeInt(old));
            }
        } else {
            accessor.datarewriter$setFrozen(false);
            try {
                mapped.register(key, parsed.value(), RegistrationInfo.BUILT_IN);
            } finally {
                accessor.datarewriter$setFrozen(true);
            }
        }
        return null;
    }

    /**
     * One entry of a datapack registry encoded back to JSON with the registry's own codec, or null
     * when the id is unknown or the codec cannot encode (a plain decoder, an entry that won't
     * round-trip). Feeds the ritual editor's load-by-id.
     */
    @SuppressWarnings("unchecked")
    public static @Nullable JsonObject encode(MinecraftServer server, ResourceLocation registryId,
                                              ResourceLocation entryId) {
        Decoder<?> decoder = RegistryRewriter.decoder(registryId);
        if (!(decoder instanceof Encoder<?>)) {
            return null;
        }
        Optional<Registry<Object>> registry = server.registryAccess()
                .registry(ResourceKey.<Registry<Object>>createRegistryKey(registryId));
        if (registry.isEmpty()) {
            return null;
        }
        Object value = registry.get().get(entryId);
        if (value == null) {
            return null;
        }
        try {
            DataResult<JsonElement> result = ((Encoder<Object>) decoder).encodeStart(
                    RegistryOps.create(JsonOps.INSTANCE, server.registryAccess()), value);
            return result.result().orElse(null) instanceof JsonObject obj ? obj : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
