package net.ledok.datarewriter.network;

import io.netty.buffer.ByteBuf;
import net.ledok.datarewriter.Datarewriter;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Payloads for the in-game loot table editor. Loot tables are never synced to
 * clients by vanilla, so the editor asks the server for everything: the table
 * list (optionally filtered by dropped item), one table's JSON, and saves.
 */
public final class LootPayloads {
    /**
     * Serverbound custom payloads are capped at 32767 bytes, so table JSON
     * sent to the server travels in parts of at most this many chars
     * (a char is at most 3 UTF-8 bytes).
     */
    public static final int SAVE_CHUNK_CHARS = 10_000;

    private LootPayloads() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(Datarewriter.MOD_ID, path);
    }

    /**
     * C2S: request the loot table id list. Empty filter = every table;
     * otherwise an item id ('*' wildcards allowed) and only tables containing
     * a matching item entry are returned.
     */
    public record TableListRequest(String itemFilter) implements CustomPacketPayload {
        public static final Type<TableListRequest> TYPE = new Type<>(id("loot_list_request"));
        public static final StreamCodec<ByteBuf, TableListRequest> STREAM_CODEC =
                ByteBufCodecs.STRING_UTF8.map(TableListRequest::new, TableListRequest::itemFilter);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C: the (possibly filtered) loot table id list, sorted. */
    public record TableList(String itemFilter, List<String> ids) implements CustomPacketPayload {
        public static final Type<TableList> TYPE = new Type<>(id("loot_list"));
        public static final StreamCodec<ByteBuf, TableList> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, TableList::itemFilter,
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), TableList::ids,
                TableList::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: request one loot table's current JSON. */
    public record TableRequest(String tableId) implements CustomPacketPayload {
        public static final Type<TableRequest> TYPE = new Type<>(id("loot_table_request"));
        public static final StreamCodec<ByteBuf, TableRequest> STREAM_CODEC =
                ByteBufCodecs.STRING_UTF8.map(TableRequest::new, TableRequest::tableId);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * S2C: one loot table's current JSON ("" if the table does not exist).
     * injected carries pools other mods added at runtime (a JSON array,
     * "" = none) — shown read-only in the editor.
     */
    public record TableContent(String tableId, String json, String injected)
            implements CustomPacketPayload {
        public static final Type<TableContent> TYPE = new Type<>(id("loot_table"));
        public static final StreamCodec<ByteBuf, TableContent> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, TableContent::tableId,
                ByteBufCodecs.stringUtf8(1_000_000), TableContent::json,
                ByteBufCodecs.stringUtf8(1_000_000), TableContent::injected,
                TableContent::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * C2S: save a loot table edit. mode is "replace" (json = whole table),
     * "modify" (json = array of pools to append) or "remove" (json = "").
     * Large JSON is split into parts; the server reassembles per player.
     */
    public record SaveTable(String mode, String tableId, int part, int totalParts, String json)
            implements CustomPacketPayload {
        public static final Type<SaveTable> TYPE = new Type<>(id("save_loot_table"));
        public static final StreamCodec<ByteBuf, SaveTable> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, SaveTable::mode,
                ByteBufCodecs.STRING_UTF8, SaveTable::tableId,
                ByteBufCodecs.VAR_INT, SaveTable::part,
                ByteBufCodecs.VAR_INT, SaveTable::totalParts,
                ByteBufCodecs.stringUtf8(SAVE_CHUNK_CHARS), SaveTable::json,
                SaveTable::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * C2S: bulk item edit across loot tables. Empty toItem = delete the
     * matching item entries; otherwise replace them with toItem. fromItem
     * allows '*' wildcards. tables scopes which tables are touched — a
     * comma-separated pattern list where '!' excludes ("" = all tables).
     */
    public record BulkItemEdit(String fromItem, String toItem, String tables)
            implements CustomPacketPayload {
        public static final Type<BulkItemEdit> TYPE = new Type<>(id("bulk_loot_item_edit"));
        public static final StreamCodec<ByteBuf, BulkItemEdit> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, BulkItemEdit::fromItem,
                ByteBufCodecs.STRING_UTF8, BulkItemEdit::toItem,
                ByteBufCodecs.STRING_UTF8, BulkItemEdit::tables,
                BulkItemEdit::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * S2C: outcome of a SaveTable / BulkItemEdit — shown inside the editor
     * screens (the same text also goes to chat), so a failed save is never
     * missed and a successful one names the config file it went to.
     */
    public record SaveResult(boolean ok, String message) implements CustomPacketPayload {
        public static final Type<SaveResult> TYPE = new Type<>(id("loot_save_result"));
        public static final StreamCodec<ByteBuf, SaveResult> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, SaveResult::ok,
                ByteBufCodecs.STRING_UTF8, SaveResult::message,
                SaveResult::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
