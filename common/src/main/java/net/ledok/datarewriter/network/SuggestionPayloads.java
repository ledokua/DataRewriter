package net.ledok.datarewriter.network;

import io.netty.buffer.ByteBuf;
import net.ledok.datarewriter.Datarewriter;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Payloads for the recipe editor's rights and for players' recipe suggestions (filed to the server's suggestion
 * queue, reviewed by staff in the editor). Server side: {@link net.ledok.datarewriter.suggest.RecipeSuggestions}.
 */
public final class SuggestionPayloads {
    /** Recipe JSON from a client; serverbound payloads are capped at 32767 bytes. */
    public static final int MAX_RECIPE_CHARS = 10_000;
    /** The author's text, a staff reason. */
    public static final int MAX_TEXT_CHARS = 1_000;

    private SuggestionPayloads() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(Datarewriter.MOD_ID, path);
    }

    /** S2C, on join and when LuckPerms changes it: may this player save, may they suggest. */
    public record Access(boolean save, boolean suggest) implements CustomPacketPayload {
        public static final Type<Access> TYPE = new Type<>(id("editor_access"));
        public static final StreamCodec<ByteBuf, Access> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, Access::save,
                ByteBufCodecs.BOOL, Access::suggest,
                Access::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * C2S: a player's proposal. {@code kind} is "new", "edit" or "remove"; {@code recipeId} the recipe an edit or
     * removal is about ("" for new); {@code recipeJson} the editor's recipe ("" for a removal); {@code output} the
     * result item the editor shows (a hint for recipes whose result the server can't read); {@code text} why.
     */
    public record Suggest(String kind, String recipeId, String recipeJson, String output, String text)
            implements CustomPacketPayload {
        public static final Type<Suggest> TYPE = new Type<>(id("suggest_recipe"));
        public static final StreamCodec<ByteBuf, Suggest> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(16), Suggest::kind,
                ByteBufCodecs.stringUtf8(256), Suggest::recipeId,
                ByteBufCodecs.stringUtf8(MAX_RECIPE_CHARS), Suggest::recipeJson,
                ByteBufCodecs.stringUtf8(256), Suggest::output,
                ByteBufCodecs.stringUtf8(MAX_TEXT_CHARS), Suggest::text,
                Suggest::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * S2C: open suggestion {@code id} for review. {@code payload} is what the author's editor sent (see
     * {@code RecipeSuggestions} for its keys); {@code context} what the server knows now: {@code current} (the
     * recipe's JSON today), {@code defined_in} (the config file that adds that id), {@code fixed_id} (the id a new
     * recipe gets), {@code can_accept}.
     */
    public record Review(long id, String author, String text, String payload, String context)
            implements CustomPacketPayload {
        public static final Type<Review> TYPE = new Type<>(SuggestionPayloads.id("review_suggestion"));
        public static final StreamCodec<ByteBuf, Review> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, Review::id,
                ByteBufCodecs.STRING_UTF8, Review::author,
                ByteBufCodecs.stringUtf8(MAX_TEXT_CHARS), Review::text,
                ByteBufCodecs.stringUtf8(1_000_000), Review::payload,
                ByteBufCodecs.stringUtf8(1_000_000), Review::context,
                Review::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * C2S: a reviewer's verdict on suggestion {@code id}. Accepting a new recipe or an edit carries the (possibly
     * edited) recipe with its id in {@code recipeJson}; accepting a removal carries the id in {@code recipeId}.
     * {@code reason} goes to the author (required to reject).
     */
    public record Decision(long id, boolean accept, String recipeId, String recipeJson, String reason)
            implements CustomPacketPayload {
        public static final Type<Decision> TYPE = new Type<>(SuggestionPayloads.id("suggestion_decision"));
        public static final StreamCodec<ByteBuf, Decision> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, Decision::id,
                ByteBufCodecs.BOOL, Decision::accept,
                ByteBufCodecs.stringUtf8(256), Decision::recipeId,
                ByteBufCodecs.stringUtf8(MAX_RECIPE_CHARS), Decision::recipeJson,
                ByteBufCodecs.stringUtf8(MAX_TEXT_CHARS), Decision::reason,
                Decision::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C: the answer to {@link Suggest} or {@link Decision}, as a lang key ({@code gui.datarewriter.}…) and its arguments. */
    public record Result(boolean ok, String key, List<String> args) implements CustomPacketPayload {
        public static final Type<Result> TYPE = new Type<>(id("suggestion_result"));
        public static final StreamCodec<ByteBuf, Result> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, Result::ok,
                ByteBufCodecs.STRING_UTF8, Result::key,
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), Result::args,
                Result::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
