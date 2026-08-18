package net.ledok.datarewriter.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.fabricmc.loader.api.FabricLoader;
import net.ledok.datarewriter.Datarewriter;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persists recipes coming from the in-game recipe editor into a normal
 * DataRewriter config file, validating them with the vanilla recipe codec
 * first so broken recipes never reach the config.
 */
public final class GuiRecipeSaver {
    public static final String FILE_NAME = "gui-recipes.json5";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String BANNER = """
            // Recipes created with the in-game recipe editor.
            // This is a normal DataRewriter config file — edit or move entries freely.
            """;

    private GuiRecipeSaver() {
    }

    /** Either an error message or the parsed recipe, ready to be applied live. */
    public record SaveResult(String error, RecipeHolder<?> recipe) {
        static SaveResult failure(String error) {
            return new SaveResult(error, null);
        }
    }

    /** Validates and appends the recipe to the config file. */
    public static SaveResult save(MinecraftServer server, String recipeJson) {
        JsonObject recipe;
        try {
            JsonElement parsed = JsonParser.parseString(recipeJson);
            if (!(parsed instanceof JsonObject obj)) {
                return SaveResult.failure("the recipe must be a JSON object");
            }
            recipe = obj;
        } catch (Exception e) {
            return SaveResult.failure("invalid JSON: " + e.getMessage());
        }
        if (!(recipe.get("type") instanceof JsonPrimitive)) {
            return SaveResult.failure("the recipe is missing 'type'");
        }
        ResourceLocation explicitId = null;
        if (recipe.get("id") instanceof JsonPrimitive idValue) {
            explicitId = ResourceLocation.tryParse(idValue.getAsString());
            if (explicitId == null) {
                return SaveResult.failure("'" + idValue.getAsString() + "' is not a valid recipe id");
            }
        }

        // Validate exactly what the config loader will feed the game later:
        // id stripped, shorthand expanded for vanilla types.
        JsonObject validation = recipe.deepCopy();
        validation.remove("id");
        RecipeJsonNormalizer.normalize(validation);
        DataResult<Recipe<?>> result = Recipe.CODEC.parse(
                RegistryOps.create(JsonOps.INSTANCE, server.registryAccess()), validation);
        if (result.error().isPresent()) {
            Datarewriter.LOGGER.warn("Recipe editor: parser rejected this recipe JSON: {}", validation);
            return SaveResult.failure("rejected by the recipe parser: " + result.error().get().message());
        }

        JsonObject root;
        try {
            root = readRoot();
        } catch (IOException e) {
            return SaveResult.failure(e.getMessage());
        }

        JsonObject recipes = recipesSection(root);
        JsonArray add = recipes.get("add") instanceof JsonArray a ? a : new JsonArray();
        recipes.add("add", add);
        boolean replacedEntry = false;
        if (explicitId != null) {
            // Saving the same id again replaces the earlier entry instead of
            // stacking a second one (the loader would warn and take the last).
            String idText = explicitId.toString();
            replacedEntry = add.asList().removeIf(e -> e instanceof JsonObject obj
                    && obj.get("id") instanceof JsonPrimitive p && p.isString()
                    && ResourceLocation.tryParse(p.getAsString()) != null
                    && ResourceLocation.tryParse(p.getAsString()).toString().equals(idText));
        }
        add.add(recipe);

        try {
            writeRoot(root);
        } catch (IOException e) {
            return SaveResult.failure(e.getMessage());
        }

        // Without an explicit id, the id must match what ConfigLoader will
        // generate for this entry's position on the next reload.
        ResourceLocation id = explicitId != null ? explicitId
                : ResourceLocation.fromNamespaceAndPath(Datarewriter.MOD_ID,
                        ConfigLoader.sanitizePath(FILE_NAME.replaceFirst("\\.json5?$", "")) + "/" + add.size());
        return new SaveResult(null, new RecipeHolder<>(id, result.result().orElseThrow()));
    }

    /**
     * Appends a bulk removal rule — {output: ref} or {input: ref} — to the
     * recipes section (append-unique). Returns an error message or null.
     */
    public static String saveBulkRemoval(String condition, String ref) {
        JsonObject rule = new JsonObject();
        rule.addProperty(condition, ref);
        return appendUnique("remove", rule);
    }

    /**
     * Appends a {from, to} rule to recipes.replace_ingredients
     * (append-unique). Returns an error message or null.
     */
    public static String saveReplaceIngredients(String from, String to) {
        JsonObject rule = new JsonObject();
        rule.addProperty("from", from);
        rule.addProperty("to", to);
        return appendUnique("replace_ingredients", rule);
    }

    private static String appendUnique(String listKey, JsonObject rule) {
        JsonObject root;
        try {
            root = readRoot();
        } catch (IOException e) {
            return e.getMessage();
        }
        JsonObject recipes = recipesSection(root);
        JsonArray list = recipes.get(listKey) instanceof JsonArray a ? a : new JsonArray();
        recipes.add(listKey, list);
        if (!list.contains(rule)) {
            list.add(rule);
        }
        try {
            writeRoot(root);
        } catch (IOException e) {
            return e.getMessage();
        }
        return null;
    }

    private static JsonObject recipesSection(JsonObject root) {
        JsonObject recipes = root.get("recipes") instanceof JsonObject r ? r : new JsonObject();
        root.add("recipes", recipes);
        return recipes;
    }

    /** Content of our last write; a different file on the next read means someone else edited it. */
    private static String lastWritten;
    private static String pendingNotice;

    /**
     * On reload: true when the file no longer matches what the in-game editor
     * last wrote this session — i.e. something else (a panel file editor
     * saving a stale copy, a modpack sync) overwrote in-game edits.
     */
    public static synchronized boolean changedSinceLastSave() {
        if (lastWritten == null) {
            return false;
        }
        Path file = FabricLoader.getInstance().getConfigDir().resolve(Datarewriter.MOD_ID).resolve(FILE_NAME);
        try {
            String raw = Files.exists(file) ? Files.readString(file) : "";
            if (raw.equals(lastWritten)) {
                return false;
            }
            Datarewriter.LOGGER.warn("Recipe editor: {} differs from what the in-game editor last wrote — "
                    + "in-game edits may have been overwritten (panel file editor / modpack sync?)",
                    file.toAbsolutePath());
            lastWritten = null; // report once
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** One-shot warning collected by the last save (external edit detected), or null. */
    public static synchronized String takeNotice() {
        String notice = pendingNotice;
        pendingNotice = null;
        return notice;
    }

    private static JsonObject readRoot() throws IOException {
        Path file = FabricLoader.getInstance().getConfigDir()
                .resolve(Datarewriter.MOD_ID).resolve(FILE_NAME);
        if (!Files.exists(file)) {
            return new JsonObject();
        }
        try {
            String raw = Files.readString(file);
            if (lastWritten != null && !raw.equals(lastWritten)) {
                pendingNotice = "note: " + FILE_NAME + " was changed on disk by something else since the last in-game save "
                + "(a server panel file editor still open on it?) — this edit was merged onto the current file, "
                + "but earlier in-game edits may have been overwritten if that editor saved an old copy. "
                + "Close/reload panel editors before saving in game.";
                Datarewriter.LOGGER.warn("Recipe editor: {} was modified outside the game since the last save", file.toAbsolutePath());
            }
            String content = ConfigLoader.stripCommentsAndTrailingCommas(raw);
            JsonReader reader = new JsonReader(new StringReader(content));
            reader.setLenient(true);
            if (JsonParser.parseReader(reader) instanceof JsonObject existing) {
                return existing;
            }
            throw new IOException(FILE_NAME + " exists but is not a config object — fix or delete it first");
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("could not read " + FILE_NAME + ": " + e.getMessage());
        }
    }

    private static void writeRoot(JsonObject root) throws IOException {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve(Datarewriter.MOD_ID);
        try {
            Files.createDirectories(dir);
            String out = BANNER + GSON.toJson(root) + "\n";
            Files.writeString(dir.resolve(FILE_NAME), out);
            lastWritten = out;
            Datarewriter.LOGGER.info("Recipe editor: wrote {}", dir.resolve(FILE_NAME).toAbsolutePath());
        } catch (IOException e) {
            throw new IOException("could not write " + FILE_NAME + ": " + e.getMessage());
        }
    }
}
