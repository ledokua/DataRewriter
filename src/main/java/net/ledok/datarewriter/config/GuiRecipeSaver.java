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

        Path dir = FabricLoader.getInstance().getConfigDir().resolve(Datarewriter.MOD_ID);
        Path file = dir.resolve(FILE_NAME);
        JsonObject root = new JsonObject();
        try {
            Files.createDirectories(dir);
            if (Files.exists(file)) {
                String content = ConfigLoader.stripCommentsAndTrailingCommas(Files.readString(file));
                JsonReader reader = new JsonReader(new StringReader(content));
                reader.setLenient(true);
                if (JsonParser.parseReader(reader) instanceof JsonObject existing) {
                    root = existing;
                } else {
                    return SaveResult.failure(FILE_NAME + " exists but is not a config object — fix or delete it first");
                }
            }
        } catch (Exception e) {
            return SaveResult.failure("could not read " + FILE_NAME + ": " + e.getMessage());
        }

        JsonObject recipes = root.get("recipes") instanceof JsonObject r ? r : new JsonObject();
        root.add("recipes", recipes);
        JsonArray add = recipes.get("add") instanceof JsonArray a ? a : new JsonArray();
        recipes.add("add", add);
        add.add(recipe);

        try {
            Files.writeString(file, BANNER + GSON.toJson(root) + "\n");
        } catch (IOException e) {
            return SaveResult.failure("could not write " + FILE_NAME + ": " + e.getMessage());
        }

        // Without an explicit id, the id must match what ConfigLoader will
        // generate for this entry's position on the next reload.
        ResourceLocation id = explicitId != null ? explicitId
                : ResourceLocation.fromNamespaceAndPath(Datarewriter.MOD_ID,
                        ConfigLoader.sanitizePath(FILE_NAME.replaceFirst("\\.json5?$", "")) + "/" + add.size());
        return new SaveResult(null, new RecipeHolder<>(id, result.result().orElseThrow()));
    }
}
