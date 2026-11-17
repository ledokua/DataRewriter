package net.ledok.datarewriter.suggest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import net.ledok.datarewriter.Datarewriter;
import net.ledok.datarewriter.EditorPermissions;
import net.ledok.datarewriter.RewriteState;
import net.ledok.datarewriter.config.ConfigLoader;
import net.ledok.datarewriter.config.GuiRecipeSaver;
import net.ledok.datarewriter.config.RewriteConfig;
import net.ledok.datarewriter.network.SuggestionPayloads;
import net.ledok.datarewriter.platform.Network;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Players' recipe suggestions, server side. A player proposes a new recipe, an edit or a removal from the recipe
 * editor; it is filed to Eden_LD's report queue (category {@value #CATEGORY}, reference = the recipe id, or
 * {@code new:<result item>}); staff open it from Eden's Moderation window into the recipe editor's review mode and
 * accept (written to {@value GuiRecipeSaver#FILE_NAME} for the next {@code /reload}), or reject with a reason.
 * <p>
 * The payload Eden stores for us is a JSON object: {@code kind} ("new" / "edit" / "remove"), {@code recipe_id}
 * (edit, remove), {@code base} (the recipe as the server had it when suggested), {@code proposed} (new, edit; the
 * game's own encoding of the parsed recipe), {@code type}, {@code output}.
 */
public final class RecipeSuggestions {
    public static final String CATEGORY = "datarewriter:recipe";
    /** Characters of the author's text Eden keeps. */
    private static final int MAX_TEXT = 500;

    /** Suggestions each reviewer opened (id → author's name): only those can be decided from their client. */
    private static final Map<UUID, Map<Long, String>> OPENED = new ConcurrentHashMap<>();

    private RecipeSuggestions() {
    }

    /** True when the server has a suggestion queue (Eden_LD 1.3.83+). */
    public static boolean available() {
        return EdenQueue.available();
    }

    /** Server start: hands Eden our review screen for {@value #CATEGORY}. */
    public static void register() {
        if (available()) {
            EdenQueue.registerEditor(CATEGORY, RecipeSuggestions::openReview);
        }
    }

    // ------------------------------------------------------------------ the author

    public static void handleSuggest(MinecraftServer server, ServerPlayer player, SuggestionPayloads.Suggest p) {
        if (!available()) {
            fail(player, "suggest.not_available");
            return;
        }
        if (!EditorPermissions.canSuggest(player)) {
            fail(player, "suggest.no_permission");
            return;
        }
        String text = p.text().strip();
        if (text.isEmpty()) {
            fail(player, "suggest.text_required");
            return;
        }
        if (text.length() > MAX_TEXT) {
            text = text.substring(0, MAX_TEXT);
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("kind", p.kind());
        String reference;
        String output;
        switch (p.kind()) {
            case "edit", "remove" -> {
                ResourceLocation id = ResourceLocation.tryParse(p.recipeId());
                RecipeHolder<?> holder = id == null ? null : server.getRecipeManager().byKey(id).orElse(null);
                if (holder == null) {
                    fail(player, "suggest.unknown_recipe", p.recipeId());
                    return;
                }
                JsonElement base = encode(server, holder.value());
                payload.addProperty("recipe_id", id.toString());
                if (base != null) {
                    payload.add("base", base);
                }
                output = output(server, holder.value(), p.output());
                reference = id.toString();
                if (p.kind().equals("edit")) {
                    GuiRecipeSaver.Checked checked = GuiRecipeSaver.check(server, p.recipeJson());
                    if (checked.error() != null) {
                        fail(player, "suggest.invalid", checked.error());
                        return;
                    }
                    JsonElement proposed = encode(server, checked.recipe());
                    if (proposed != null && proposed.equals(base)) {
                        fail(player, "suggest.unchanged");
                        return;
                    }
                    payload.add("proposed", proposed != null ? proposed : withoutId(checked.json()));
                    output = output(server, checked.recipe(), p.output());
                }
            }
            case "new" -> {
                GuiRecipeSaver.Checked checked = GuiRecipeSaver.check(server, p.recipeJson());
                if (checked.error() != null) {
                    fail(player, "suggest.invalid", checked.error());
                    return;
                }
                JsonElement proposed = encode(server, checked.recipe());
                payload.add("proposed", proposed != null ? proposed : withoutId(checked.json()));
                output = output(server, checked.recipe(), p.output());
                reference = "new:" + output;
            }
            default -> {
                fail(player, "suggest.invalid", p.kind());
                return;
            }
        }
        String type = typeOf(payload.get(payload.has("proposed") ? "proposed" : "base"));
        payload.addProperty("type", type);
        payload.addProperty("output", output);

        // What Eden's own list shows staff without opening the editor.
        List<String> log = new ArrayList<>();
        switch (p.kind()) {
            case "edit" -> log.add("Change recipe " + reference + " (" + type + ")");
            case "remove" -> log.add("Remove recipe " + reference + " (" + type + ")");
            default -> log.add("New recipe (" + type + ")");
        }
        log.add("Result: " + output);

        long id = EdenQueue.suggest(server, player.getUUID(), CATEGORY, reference, text, payload.toString(), log);
        if (id < 0) {
            fail(player, "suggest.refused");
            return;
        }
        Datarewriter.LOGGER.info("Recipe suggestion #{} from {}: {} {}", id, player.getGameProfile().getName(),
                p.kind(), reference);
        succeed(player, "suggest.sent", "#" + id);
    }

    // ------------------------------------------------------------------ the reviewer

    /** Eden's [Open in editor]: sends {@code staff} the suggestion and what the server knows about it now. */
    private static void openReview(ServerPlayer staff, long id, UUID author, String authorName, String reference,
                                   String text, String payloadJson) {
        if (!Network.INSTANCE.canSendToPlayer(staff, SuggestionPayloads.Review.TYPE)) {
            staff.sendSystemMessage(Component.translatableWithFallback("gui.datarewriter.review.needs_client",
                    "Recipe suggestions open in DataRewriter's recipe editor, which your game doesn't have.")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        JsonObject payload = parseObject(payloadJson);
        JsonObject context = new JsonObject();
        String recipeId = string(payload, "recipe_id");
        if (recipeId != null) {
            ResourceLocation rid = ResourceLocation.tryParse(recipeId);
            RecipeHolder<?> holder = rid == null ? null : staff.server.getRecipeManager().byKey(rid).orElse(null);
            JsonElement current = holder == null ? null : encode(staff.server, holder.value());
            if (current != null) {
                context.add("current", current);
            }
            String file = definedIn(recipeId);
            if (file != null) {
                context.addProperty("defined_in", file);
            }
        }
        if ("new".equals(string(payload, "kind"))) {
            context.addProperty("fixed_id", fixedId(staff.server, string(payload, "output")));
        }
        context.addProperty("can_accept", EditorPermissions.canSave(staff));
        OPENED.computeIfAbsent(staff.getUUID(), k -> new ConcurrentHashMap<>()).put(id, authorName);
        Network.INSTANCE.sendToPlayer(staff, new SuggestionPayloads.Review(id, authorName, text, payloadJson,
                context.toString()));
    }

    public static void handleDecision(MinecraftServer server, ServerPlayer player, SuggestionPayloads.Decision d) {
        if (!available()) {
            fail(player, "suggest.not_available");
            return;
        }
        Map<Long, String> opened = OPENED.get(player.getUUID());
        String author = opened == null ? null : opened.get(d.id());
        if (author == null) {
            fail(player, "review.not_opened", "#" + d.id());
            return;
        }
        String reason = d.reason().strip();
        if (reason.length() > MAX_TEXT) {
            reason = reason.substring(0, MAX_TEXT);
        }
        String by = player.getGameProfile().getName();

        if (!d.accept()) {
            if (reason.isEmpty()) {
                fail(player, "review.reason_required");
                return;
            }
            if (!EdenQueue.resolve(server, d.id(), false, reason, by)) {
                fail(player, "review.gone", "#" + d.id());
                return;
            }
            opened.remove(d.id());
            Datarewriter.LOGGER.info("Recipe suggestion #{} rejected by {}: {}", d.id(), by, reason);
            succeed(player, "review.rejected", "#" + d.id());
            return;
        }

        if (!EditorPermissions.canSave(player)) {
            fail(player, "review.no_save_permission");
            return;
        }
        String recipeId;
        if (d.recipeJson().isEmpty()) {
            ResourceLocation rid = ResourceLocation.tryParse(d.recipeId());
            if (rid == null || d.recipeId().isBlank()) {
                fail(player, "suggest.invalid", d.recipeId());
                return;
            }
            recipeId = rid.toString();
            String file = definedIn(recipeId);
            if (file != null && !file.equals(GuiRecipeSaver.FILE_NAME)) {
                // Removal rules never touch recipes a config file adds; that file's entry has to go.
                fail(player, "review.remove_in_file", recipeId, file);
                return;
            }
            String error = GuiRecipeSaver.removeEntry(recipeId);
            if (error == null) {
                error = GuiRecipeSaver.saveBulkRemoval("id", recipeId);
            }
            if (error != null) {
                fail(player, "suggest.invalid", error);
                return;
            }
        } else {
            JsonObject recipe = parseObject(d.recipeJson());
            recipeId = string(recipe, "id");
            if (recipeId == null || recipeId.isBlank()) {
                fail(player, "review.id_required");
                return;
            }
            recipe.addProperty(ConfigLoader.SUGGESTED_BY,
                    author + " (#" + d.id() + "), accepted by " + by + " " + LocalDate.now());
            String error = GuiRecipeSaver.saveForReload(server, recipe.toString());
            if (error != null) {
                fail(player, "suggest.invalid", error);
                return;
            }
        }
        boolean resolved = EdenQueue.resolve(server, d.id(), true, reason, by);
        opened.remove(d.id());
        Datarewriter.LOGGER.info("Recipe suggestion #{} from {} accepted by {}: {} written to {}", d.id(), author,
                by, recipeId, GuiRecipeSaver.FILE_NAME);
        succeed(player, resolved ? "review.accepted" : "review.accepted_gone", "#" + d.id(), recipeId,
                GuiRecipeSaver.FILE_NAME);
    }

    // ------------------------------------------------------------------ helpers

    /** The config file that adds {@code recipeId} (the last one, which wins), or null. */
    private static @Nullable String definedIn(String recipeId) {
        String file = null;
        for (RewriteConfig.AddedRecipe added : RewriteState.config().additions()) {
            if (added.id().toString().equals(recipeId) && added.source() != null) {
                file = added.source();
            }
        }
        return file;
    }

    /** {@code datarewriter:suggested/<item>}, numbered when taken by a recipe or an entry waiting for a reload. */
    private static String fixedId(MinecraftServer server, @Nullable String output) {
        ResourceLocation item = output == null ? null : ResourceLocation.tryParse(output);
        String base = Datarewriter.MOD_ID + ":suggested/"
                + (item == null ? "recipe" : ConfigLoader.sanitizePath(item.getPath()));
        Set<String> waiting = GuiRecipeSaver.savedIds();
        String id = base;
        for (int n = 2; waiting.contains(id) || server.getRecipeManager().byKey(ResourceLocation.parse(id)).isPresent(); n++) {
            id = base + "_" + n;
        }
        return id;
    }

    private static @Nullable JsonElement encode(MinecraftServer server, Recipe<?> recipe) {
        try {
            return Recipe.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, server.registryAccess()), recipe)
                    .result().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonObject withoutId(JsonObject json) {
        JsonObject copy = json.deepCopy();
        copy.remove("id");
        return copy;
    }

    /** The recipe's result item, else the editor's hint, else "?". */
    private static String output(MinecraftServer server, Recipe<?> recipe, String hint) {
        try {
            ItemStack result = recipe.getResultItem(server.registryAccess());
            if (!result.isEmpty()) {
                return BuiltInRegistries.ITEM.getKey(result.getItem()).toString();
            }
        } catch (Exception ignored) {
            // some modded recipes can't answer without a level
        }
        ResourceLocation id = ResourceLocation.tryParse(hint.toLowerCase(Locale.ROOT));
        return id == null || hint.isBlank() ? "?" : id.toString();
    }

    private static String typeOf(@Nullable JsonElement recipe) {
        return recipe instanceof JsonObject obj && obj.get("type") instanceof JsonPrimitive p ? p.getAsString() : "?";
    }

    private static JsonObject parseObject(String json) {
        try {
            return JsonParser.parseString(json) instanceof JsonObject obj ? obj : new JsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private static @Nullable String string(JsonObject obj, String key) {
        return obj.get(key) instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }

    private static void fail(ServerPlayer player, String key, String... args) {
        answer(player, false, key, args);
    }

    private static void succeed(ServerPlayer player, String key, String... args) {
        answer(player, true, key, args);
    }

    /** Only players with DataRewriter send these payloads, so the lang keys resolve on their side. */
    private static void answer(ServerPlayer player, boolean ok, String key, String... args) {
        player.sendSystemMessage(Component.translatable("gui.datarewriter.suggest.prefix")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.translatable("gui.datarewriter." + key, (Object[]) args)
                        .withStyle(ok ? ChatFormatting.GRAY : ChatFormatting.RED)));
        if (Network.INSTANCE.canSendToPlayer(player, SuggestionPayloads.Result.TYPE)) {
            Network.INSTANCE.sendToPlayer(player, new SuggestionPayloads.Result(ok, key, List.of(args)));
        }
    }
}
