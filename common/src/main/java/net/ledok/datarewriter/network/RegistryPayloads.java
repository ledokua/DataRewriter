package net.ledok.datarewriter.network;

import io.netty.buffer.ByteBuf;
import net.ledok.datarewriter.Datarewriter;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Payloads for the in-game ritual editor (datapack registry entries). Like the loot editor, the
 * client asks the server for everything: one entry's JSON, and saves. Entries are small (a ritual is
 * well under a kilobyte), so nothing is chunked; the server refuses entries that would not fit a
 * single payload.
 */
public final class RegistryPayloads {
    private RegistryPayloads() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(Datarewriter.MOD_ID, path);
    }

    /** C2S: request one registry entry's JSON (the editor's Load button). */
    public record EntryRequest(String registry, String entryId) implements CustomPacketPayload {
        public static final Type<EntryRequest> TYPE = new Type<>(id("registry_entry_request"));
        public static final StreamCodec<ByteBuf, EntryRequest> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, EntryRequest::registry,
                ByteBufCodecs.STRING_UTF8, EntryRequest::entryId,
                EntryRequest::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C: one entry's JSON, "" when the id does not exist (or cannot be encoded). */
    public record EntryContent(String registry, String entryId, String json) implements CustomPacketPayload {
        public static final Type<EntryContent> TYPE = new Type<>(id("registry_entry"));
        public static final StreamCodec<ByteBuf, EntryContent> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, EntryContent::registry,
                ByteBufCodecs.STRING_UTF8, EntryContent::entryId,
                ByteBufCodecs.STRING_UTF8, EntryContent::json,
                EntryContent::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: the editor asks the server to save one registry entry to the config and apply it live. */
    public record SaveEntry(String registry, String entryId, String json) implements CustomPacketPayload {
        public static final Type<SaveEntry> TYPE = new Type<>(id("save_registry_entry"));
        public static final StreamCodec<ByteBuf, SaveEntry> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, SaveEntry::registry,
                ByteBufCodecs.STRING_UTF8, SaveEntry::entryId,
                ByteBufCodecs.STRING_UTF8, SaveEntry::json,
                SaveEntry::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
