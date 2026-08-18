package net.ledok.datarewriter.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import net.fabricmc.loader.api.FabricLoader;
import net.ledok.datarewriter.Datarewriter;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.helpers.MessageFormatter;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Loads all .json / .json5 files from config/datarewriter/.
 * Files may contain comments and trailing commas. Each file holds one section
 * per data type, currently: recipes: { remove: [...], add: [...] }.
 */
public final class ConfigLoader {
    private static int errors;
    /** Errors and warnings of the load in progress. */
    private static final List<String> issues = new ArrayList<>();
    /** Snapshot of the last finished load, for /datarewriter errors. */
    private static volatile List<String> lastIssues = List.of();

    private ConfigLoader() {
    }

    public static RewriteConfig load() {
        errors = 0;
        issues.clear();
        Path dir = FabricLoader.getInstance().getConfigDir().resolve(Datarewriter.MOD_ID);
        try {
            if (!Files.isDirectory(dir)) {
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("example.json5"), EXAMPLE_FILE);
            }
        } catch (IOException e) {
            error("Could not create config directory {}: {}", dir, e.getMessage());
            lastIssues = List.copyOf(issues);
            return new RewriteConfig(List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), 1);
        }

        List<RewriteConfig.RemovalRule> removals = new ArrayList<>();
        List<RewriteConfig.AddedRecipe> additions = new ArrayList<>();
        List<RewriteConfig.IngredientReplacement> ingredientReplacements = new ArrayList<>();
        List<RewriteConfig.LootRule> lootRemovals = new ArrayList<>();
        List<RewriteConfig.AddedLootTable> lootAdditions = new ArrayList<>();
        List<RewriteConfig.LootModification> lootModifications = new ArrayList<>();
        List<RewriteConfig.LootItemReplacement> lootItemReplacements = new ArrayList<>();
        List<RewriteConfig.LootItemRemoval> lootItemRemovals = new ArrayList<>();

        // editor-layouts/ holds the recipe editor's CLIENT-side layout files,
        // which are not rewrite rules.
        Path editorLayouts = dir.resolve("editor-layouts");
        try (Stream<Path> stream = Files.walk(dir)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.startsWith(editorLayouts))
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith(".json") || name.endsWith(".json5");
                    })
                    .sorted()
                    .toList();
            List<FileSummary> summaries = new ArrayList<>();
            for (Path file : files) {
                int recipeBefore = removals.size() + additions.size() + ingredientReplacements.size();
                int lootBefore = lootRemovals.size() + lootAdditions.size() + lootModifications.size()
                        + lootItemReplacements.size() + lootItemRemovals.size();
                String name = dir.relativize(file).toString().replace('\\', '/');
                loadFile(name, file,
                        removals, additions, ingredientReplacements, lootRemovals,
                        lootAdditions, lootModifications, lootItemReplacements, lootItemRemovals);
                summaries.add(new FileSummary(name,
                        removals.size() + additions.size() + ingredientReplacements.size() - recipeBefore,
                        lootRemovals.size() + lootAdditions.size() + lootModifications.size()
                                + lootItemReplacements.size() + lootItemRemovals.size() - lootBefore));
            }
            lastFiles = List.copyOf(summaries);
            lastDir = dir.toAbsolutePath().toString();
            Datarewriter.LOGGER.info("Config: {} — {}", lastDir, summaries.isEmpty() ? "no files"
                    : summaries.stream().map(FileSummary::toString).collect(java.util.stream.Collectors.joining(", ")));
        } catch (IOException e) {
            error("Could not read config directory {}", dir, e);
        }

        lastIssues = List.copyOf(issues);
        return new RewriteConfig(List.copyOf(removals), List.copyOf(additions),
                List.copyOf(ingredientReplacements),
                List.copyOf(lootRemovals), List.copyOf(lootAdditions), List.copyOf(lootModifications),
                List.copyOf(lootItemReplacements), List.copyOf(lootItemRemovals),
                errors);
    }

    /** One config file of the last load and how many rules it contributed. */
    public record FileSummary(String name, int recipeRules, int lootRules) {
        @Override
        public String toString() {
            return name + " (" + recipeRules + " recipe, " + lootRules + " loot rules)";
        }
    }

    private static volatile List<FileSummary> lastFiles = List.of();
    private static volatile String lastDir = "";

    /** Files read by the last config load, in load order (later files win on the same id). */
    public static List<FileSummary> files() {
        return lastFiles;
    }

    /** Absolute config directory of the last load. */
    public static String directory() {
        return lastDir;
    }

    /** Errors and warnings of the last config load, for /datarewriter errors. */
    public static List<String> issues() {
        return lastIssues;
    }

    private static void error(String format, Object... args) {
        errors++;
        issues.add("ERROR: " + MessageFormatter.arrayFormat(format, args).getMessage());
        Datarewriter.LOGGER.error(format, args);
    }

    private static void warn(String format, Object... args) {
        issues.add("WARN: " + MessageFormatter.arrayFormat(format, args).getMessage());
        Datarewriter.LOGGER.warn(format, args);
    }

    private static void loadFile(String fileName, Path file,
                                 List<RewriteConfig.RemovalRule> removals,
                                 List<RewriteConfig.AddedRecipe> additions,
                                 List<RewriteConfig.IngredientReplacement> ingredientReplacements,
                                 List<RewriteConfig.LootRule> lootRemovals,
                                 List<RewriteConfig.AddedLootTable> lootAdditions,
                                 List<RewriteConfig.LootModification> lootModifications,
                                 List<RewriteConfig.LootItemReplacement> lootItemReplacements,
                                 List<RewriteConfig.LootItemRemoval> lootItemRemovals) {
        JsonObject root;
        try {
            String content = stripCommentsAndTrailingCommas(Files.readString(file));
            JsonReader reader = new JsonReader(new StringReader(content));
            reader.setLenient(true); // unquoted keys, single quotes
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                error("[{}] Top level must be an object like { recipes: { remove: [...], add: [...] } }", fileName);
                return;
            }
            root = parsed.getAsJsonObject();
        } catch (Exception e) {
            error("[{}] Failed to parse: {}", fileName, e.getMessage());
            return;
        }

        if (root.has("recipes")) {
            if (root.get("recipes") instanceof JsonObject recipes) {
                readRecipesSection(recipes, fileName, removals, additions, ingredientReplacements);
            } else {
                error("[{}] 'recipes' must be an object with 'remove' and/or 'add' lists", fileName);
            }
        }
        if (root.has("loot_tables")) {
            if (root.get("loot_tables") instanceof JsonObject lootTables) {
                readLootSection(lootTables, fileName, lootRemovals, lootAdditions, lootModifications,
                        lootItemReplacements, lootItemRemovals);
            } else {
                error("[{}] 'loot_tables' must be an object with 'remove' and/or 'add' lists", fileName);
            }
        }

        for (String key : root.keySet()) {
            if (key.equals("remove") || key.equals("add")) {
                error("[{}] '{}' must be inside a 'recipes: { ... }' section, ignoring it", fileName, key);
            } else if (!key.equals("recipes") && !key.equals("loot_tables")) {
                warn("[{}] Unknown section '{}' (expected 'recipes' or 'loot_tables')",
                        fileName, key);
            }
        }
    }

    private static void readLootSection(JsonObject lootTables, String fileName,
                                        List<RewriteConfig.LootRule> lootRemovals,
                                        List<RewriteConfig.AddedLootTable> lootAdditions,
                                        List<RewriteConfig.LootModification> lootModifications,
                                        List<RewriteConfig.LootItemReplacement> lootItemReplacements,
                                        List<RewriteConfig.LootItemRemoval> lootItemRemovals) {
        if (lootTables.has("remove")) {
            readLootRemovals(lootTables.get("remove"), fileName, lootRemovals);
        }
        if (lootTables.has("add")) {
            readLootAdditions(lootTables.get("add"), fileName, lootAdditions);
        }
        if (lootTables.has("modify")) {
            readLootModifications(lootTables.get("modify"), fileName, lootModifications);
        }
        if (lootTables.has("replace_items")) {
            readLootItemReplacements(lootTables.get("replace_items"), fileName, lootItemReplacements);
        }
        if (lootTables.has("remove_items")) {
            readLootItemRemovals(lootTables.get("remove_items"), fileName, lootItemRemovals);
        }
        for (String key : lootTables.keySet()) {
            if (!List.of("remove", "add", "modify", "replace_items", "remove_items").contains(key)) {
                warn("[{}] loot_tables: unknown key '{}' "
                        + "(expected 'remove', 'add', 'modify', 'replace_items' or 'remove_items')",
                        fileName, key);
            }
        }
    }

    private static void readLootItemReplacements(JsonElement element, String fileName,
                                                 List<RewriteConfig.LootItemReplacement> out) {
        if (!(element instanceof JsonArray array)) {
            error("[{}] loot_tables 'replace_items' must be a list", fileName);
            return;
        }
        int index = 0;
        for (JsonElement entry : array) {
            index++;
            if (entry.isJsonNull()) {
                continue;
            }
            if (!(entry instanceof JsonObject obj)) {
                error("[{}] loot_tables replace_items[{}] must be an object", fileName, index);
                continue;
            }
            ItemMatch from = readItemMatch(obj, "from", fileName, "replace_items", index);
            ResourceLocation to = readId(obj, "to", fileName, index);
            TableScope table = readTableScope(obj, fileName, "replace_items", index);
            for (String key : obj.keySet()) {
                if (!List.of("from", "to", "table").contains(key)) {
                    warn("[{}] loot_tables replace_items[{}]: unknown key '{}'",
                            fileName, index, key);
                }
            }
            if (from == null || to == null) {
                error("[{}] loot_tables replace_items[{}] needs 'from' (item id with '*' wildcards, "
                        + "or a '#tag') and 'to' (exact item id)", fileName, index);
                continue;
            }
            out.add(new RewriteConfig.LootItemReplacement(from, to, table, fileName));
        }
    }

    private static void readLootItemRemovals(JsonElement element, String fileName,
                                             List<RewriteConfig.LootItemRemoval> out) {
        if (!(element instanceof JsonArray array)) {
            error("[{}] loot_tables 'remove_items' must be a list", fileName);
            return;
        }
        int index = 0;
        for (JsonElement entry : array) {
            index++;
            if (entry.isJsonNull()) {
                continue;
            }
            // A bare string is shorthand for { item: "..." }.
            ItemMatch item;
            TableScope table = null;
            if (entry instanceof JsonPrimitive primitive && primitive.isString()) {
                item = ItemMatch.parse(primitive.getAsString());
                if (item == null) {
                    error("[{}] loot_tables remove_items[{}]: '{}' is not a valid item id, "
                            + "pattern or #tag", fileName, index, primitive.getAsString());
                    continue;
                }
            } else if (entry instanceof JsonObject obj) {
                item = readItemMatch(obj, "item", fileName, "remove_items", index);
                table = readTableScope(obj, fileName, "remove_items", index);
                for (String key : obj.keySet()) {
                    if (!List.of("item", "table").contains(key)) {
                        warn("[{}] loot_tables remove_items[{}]: unknown key '{}'",
                                fileName, index, key);
                    }
                }
                if (item == null) {
                    error("[{}] loot_tables remove_items[{}] needs 'item' (id with '*' wildcards, "
                            + "or a '#tag')", fileName, index);
                    continue;
                }
            } else {
                error("[{}] loot_tables remove_items[{}] must be an item id string or an object",
                        fileName, index);
                continue;
            }
            out.add(new RewriteConfig.LootItemRemoval(item, table, fileName));
        }
    }

    private static void readLootModifications(JsonElement element, String fileName,
                                              List<RewriteConfig.LootModification> lootModifications) {
        if (!(element instanceof JsonArray array)) {
            error("[{}] loot_tables 'modify' must be a list", fileName);
            return;
        }
        int index = 0;
        for (JsonElement entry : array) {
            index++;
            if (entry.isJsonNull()) {
                continue;
            }
            if (!(entry instanceof JsonObject obj)) {
                error("[{}] loot_tables modify[{}] must be an object", fileName, index);
                continue;
            }
            IdPattern id = readIdPattern(obj, "id", fileName, index);
            String mod = readString(obj, "mod");
            for (String key : obj.keySet()) {
                if (!List.of("id", "mod", "pools").contains(key)) {
                    warn("[{}] loot_tables modify[{}]: unknown key '{}'",
                            fileName, index, key);
                }
            }
            if (id == null && mod == null) {
                error("[{}] loot_tables modify[{}] has no valid target conditions ('id' and/or 'mod'), "
                        + "skipping (an empty target would modify every loot table)", fileName, index);
                continue;
            }
            if (!(obj.get("pools") instanceof JsonArray pools) || pools.isEmpty()) {
                error("[{}] loot_tables modify[{}] needs a non-empty 'pools' list to inject", fileName, index);
                continue;
            }
            lootModifications.add(new RewriteConfig.LootModification(
                    new RewriteConfig.LootRule(id, mod, fileName), pools.deepCopy()));
        }
    }

    private static void readLootRemovals(JsonElement element, String fileName,
                                         List<RewriteConfig.LootRule> lootRemovals) {
        if (!(element instanceof JsonArray array)) {
            error("[{}] loot_tables 'remove' must be a list", fileName);
            return;
        }
        int index = 0;
        for (JsonElement entry : array) {
            index++;
            if (entry.isJsonNull()) {
                continue;
            }
            if (!(entry instanceof JsonObject obj)) {
                error("[{}] loot_tables remove[{}] must be an object", fileName, index);
                continue;
            }
            IdPattern id = readIdPattern(obj, "id", fileName, index);
            String mod = readString(obj, "mod");
            for (String key : obj.keySet()) {
                if (!key.equals("id") && !key.equals("mod")) {
                    warn("[{}] loot_tables remove[{}]: unknown condition '{}'",
                            fileName, index, key);
                }
            }
            if (id == null && mod == null) {
                error("[{}] loot_tables remove[{}] has no valid conditions, skipping "
                        + "(an empty rule would empty every loot table)", fileName, index);
                continue;
            }
            lootRemovals.add(new RewriteConfig.LootRule(id, mod, fileName));
        }
    }

    private static void readLootAdditions(JsonElement element, String fileName,
                                          List<RewriteConfig.AddedLootTable> lootAdditions) {
        if (!(element instanceof JsonArray array)) {
            error("[{}] loot_tables 'add' must be a list", fileName);
            return;
        }
        int index = 0;
        for (JsonElement entry : array) {
            index++;
            if (entry.isJsonNull()) {
                continue;
            }
            if (!(entry instanceof JsonObject rawObj)) {
                error("[{}] loot_tables add[{}] must be a loot table object", fileName, index);
                continue;
            }
            if (!rawObj.has("id")) {
                error("[{}] loot_tables add[{}] needs an 'id' — a loot table only does something "
                        + "when the game references it by id (e.g. \"minecraft:entities/zombie\")",
                        fileName, index);
                continue;
            }
            JsonObject obj = rawObj.deepCopy();
            ResourceLocation id = readId(obj, "id", fileName, index);
            if (id == null) {
                continue;
            }
            obj.remove("id");
            for (RewriteConfig.AddedLootTable earlier : lootAdditions) {
                if (earlier.id().equals(id)) {
                    warn("[{}] loot_tables add: {} is also added by {} — files load in name order and the "
                            + "later one wins, so this entry replaces the one from {}. Keep only one "
                            + "(in-game editor saves go to gui-loot-tables.json5).",
                            fileName, id, earlier.source(), earlier.source());
                }
            }
            lootAdditions.add(new RewriteConfig.AddedLootTable(id, obj, fileName));
        }
    }

    private static void readRecipesSection(JsonObject recipes, String fileName,
                                           List<RewriteConfig.RemovalRule> removals,
                                           List<RewriteConfig.AddedRecipe> additions,
                                           List<RewriteConfig.IngredientReplacement> replacements) {
        if (recipes.has("remove")) {
            readRemovals(recipes.get("remove"), fileName, removals);
        }
        if (recipes.has("add")) {
            readAdditions(recipes.get("add"), fileName, additions);
        }
        if (recipes.has("replace_ingredients")) {
            readIngredientReplacements(recipes.get("replace_ingredients"), fileName, replacements);
        }
        for (String key : recipes.keySet()) {
            if (!List.of("remove", "add", "replace_ingredients").contains(key)) {
                warn("[{}] recipes: unknown key '{}' (expected 'remove', 'add' "
                        + "or 'replace_ingredients')", fileName, key);
            }
        }
    }

    private static void readIngredientReplacements(JsonElement element, String fileName,
                                                   List<RewriteConfig.IngredientReplacement> replacements) {
        if (!(element instanceof JsonArray array)) {
            error("[{}] recipes 'replace_ingredients' must be a list", fileName);
            return;
        }
        int index = 0;
        for (JsonElement entry : array) {
            index++;
            if (entry.isJsonNull()) {
                continue;
            }
            if (!(entry instanceof JsonObject obj)) {
                error("[{}] recipes replace_ingredients[{}] must be an object "
                        + "like { from: \"minecraft:stick\", to: \"#c:rods\" }", fileName, index);
                continue;
            }
            String fromText = readString(obj, "from");
            String toText = readString(obj, "to");
            ItemMatch from = fromText == null ? null : ItemMatch.parse(fromText);
            if (from == null) {
                error("[{}] recipes replace_ingredients[{}] needs 'from' — an item id "
                        + "(with '*' wildcards) or a '#tag'", fileName, index);
                continue;
            }
            String to = normalizeItemOrTag(toText);
            if (to == null) {
                error("[{}] recipes replace_ingredients[{}] needs 'to' — an item id or a '#tag'",
                        fileName, index);
                continue;
            }
            for (String key : obj.keySet()) {
                if (!key.equals("from") && !key.equals("to")) {
                    warn("[{}] recipes replace_ingredients[{}]: unknown key '{}'",
                            fileName, index, key);
                }
            }
            replacements.add(new RewriteConfig.IngredientReplacement(from, to, fileName));
        }
    }

    /** "id" or "#tag" with the namespace defaulted, or null when invalid. */
    public static String normalizeItemOrTag(String text) {
        if (text == null || text.isEmpty() || text.contains("*")) {
            return null;
        }
        boolean tag = text.startsWith("#");
        ResourceLocation id = ResourceLocation.tryParse(tag ? text.substring(1) : text);
        return id == null ? null : (tag ? "#" + id : id.toString());
    }

    private static void readRemovals(JsonElement element, String fileName,
                                     List<RewriteConfig.RemovalRule> removals) {
        if (!(element instanceof JsonArray array)) {
            error("[{}] 'remove' must be a list", fileName);
            return;
        }
        int index = 0;
        for (JsonElement entry : array) {
            index++;
            if (entry.isJsonNull()) {
                continue; // artifact of a stray comma
            }
            if (!(entry instanceof JsonObject obj)) {
                error("[{}] remove[{}] must be an object", fileName, index);
                continue;
            }
            IdPattern id = readIdPattern(obj, "id", fileName, index);
            ResourceLocation type = readId(obj, "type", fileName, index);
            String mod = readString(obj, "mod");
            String output = readItemRef(obj, "output", fileName, index);
            String input = readItemRef(obj, "input", fileName, index);
            for (String key : obj.keySet()) {
                if (!List.of("id", "type", "mod", "output", "input").contains(key)) {
                    warn("[{}] remove[{}]: unknown condition '{}'", fileName, index, key);
                }
            }
            if (id == null && type == null && mod == null && output == null && input == null) {
                error("[{}] remove[{}] has no valid conditions, skipping "
                        + "(an empty rule would remove every recipe)", fileName, index);
                continue;
            }
            removals.add(new RewriteConfig.RemovalRule(id, mod, type, output, input, fileName));
        }
    }

    private static void readAdditions(JsonElement element, String fileName,
                                      List<RewriteConfig.AddedRecipe> additions) {
        if (!(element instanceof JsonArray array)) {
            error("[{}] 'add' must be a list", fileName);
            return;
        }
        String fileStem = fileName.replaceFirst("\\.json5?$", "");
        int index = 0;
        for (JsonElement entry : array) {
            index++;
            if (entry.isJsonNull()) {
                continue;
            }
            if (!(entry instanceof JsonObject rawObj)) {
                error("[{}] add[{}] must be a recipe object", fileName, index);
                continue;
            }
            JsonObject obj = rawObj.deepCopy();
            ResourceLocation id;
            if (obj.has("id")) {
                // Explicit id: removed from the recipe JSON itself; matching an
                // existing recipe id replaces that recipe.
                id = readId(obj, "id", fileName, index);
                if (id == null) {
                    continue;
                }
                obj.remove("id");
            } else {
                id = ResourceLocation.fromNamespaceAndPath(Datarewriter.MOD_ID,
                        sanitizePath(fileStem) + "/" + index);
            }
            if (!obj.has("type")) {
                error("[{}] add[{}] is missing 'type' (e.g. \"minecraft:crafting_shaped\")",
                        fileName, index);
                continue;
            }
            RecipeJsonNormalizer.normalize(obj);
            for (RewriteConfig.AddedRecipe earlier : additions) {
                if (earlier.id().equals(id)) {
                    warn("[{}] recipes add: {} is added twice (files load in name order, the later entry wins)",
                            fileName, id);
                    break;
                }
            }
            additions.add(new RewriteConfig.AddedRecipe(id, obj));
        }
    }

    private static String readString(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : null;
    }

    /** An exact id or a pattern with '*' wildcards. */
    /** Reads an item id / '*' pattern / '#tag' for a bulk item rule. */
    private static ItemMatch readItemMatch(JsonObject obj, String key, String fileName,
                                           String listName, int index) {
        String value = readString(obj, key);
        if (value == null) {
            return null;
        }
        ItemMatch match = ItemMatch.parse(value);
        if (match == null) {
            error("[{}] loot_tables {}[{}]: '{}' is not a valid item id, pattern or #tag",
                    fileName, listName, index, value);
        }
        return match;
    }

    private static IdPattern readIdPattern(JsonObject obj, String key, String fileName, int index) {
        String value = readString(obj, key);
        if (value == null) {
            return null;
        }
        IdPattern pattern = IdPattern.parse(value);
        if (pattern == null) {
            error("[{}] entry {}: '{}' is not a valid id or pattern: {}", fileName, index, key, value);
        }
        return pattern;
    }

    /**
     * Reads the optional 'table' scope of a bulk item rule: a pattern string
     * (comma-separated, '!' excludes) or a list of such patterns.
     */
    private static TableScope readTableScope(JsonObject obj, String fileName, String listName, int index) {
        JsonElement value = obj.get("table");
        if (value == null || value.isJsonNull()) {
            return null;
        }
        TableScope scope = null;
        if (value instanceof JsonPrimitive primitive && primitive.isString()) {
            scope = TableScope.parse(primitive.getAsString());
        } else if (value instanceof JsonArray array) {
            List<String> parts = new ArrayList<>();
            StringBuilder source = new StringBuilder();
            for (JsonElement part : array) {
                if (!(part instanceof JsonPrimitive p && p.isString())) {
                    error("[{}] loot_tables {}[{}]: 'table' list entries must be strings",
                            fileName, listName, index);
                    return null;
                }
                parts.add(p.getAsString());
                source.append(source.isEmpty() ? "" : ", ").append(p.getAsString());
            }
            scope = TableScope.parse(parts, source.toString());
        }
        if (scope == null) {
            error("[{}] loot_tables {}[{}]: 'table' must be a loot table id/pattern "
                    + "(comma-separated, '!' excludes) or a list of them", fileName, listName, index);
        }
        return scope;
    }

    private static ResourceLocation readId(JsonObject obj, String key, String fileName, int index) {
        String value = readString(obj, key);
        if (value == null) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null) {
            error("[{}] entry {}: '{}' is not a valid id: {}", fileName, index, key, value);
        }
        return id;
    }

    /** An item id, or a tag reference starting with '#'. Returned normalized. */
    private static String readItemRef(JsonObject obj, String key, String fileName, int index) {
        String value = readString(obj, key);
        if (value == null) {
            return null;
        }
        boolean isTag = value.startsWith("#");
        ResourceLocation id = ResourceLocation.tryParse(isTag ? value.substring(1) : value);
        if (id == null) {
            error("[{}] entry {}: '{}' is not a valid item or #tag id: {}",
                    fileName, index, key, value);
            return null;
        }
        return isTag ? "#" + id : id.toString();
    }

    static String sanitizePath(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/._-]", "_");
    }

    /**
     * Removes // and /* *&#47; comments and trailing commas so the result is
     * plain JSON. String contents are left untouched.
     */
    public static String stripCommentsAndTrailingCommas(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean inString = false;
        char quote = 0;
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (inString) {
                out.append(c);
                if (c == '\\' && i + 1 < n) {
                    out.append(text.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (c == quote) {
                    inString = false;
                }
                i++;
                continue;
            }
            if (c == '"' || c == '\'') {
                inString = true;
                quote = c;
                out.append(c);
                i++;
                continue;
            }
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
                while (i < n && text.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(text.charAt(i) == '*' && text.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(i + 2, n);
                continue;
            }
            if (c == ',') {
                // Trailing comma if the next meaningful character closes a scope.
                int j = i + 1;
                while (j < n) {
                    char d = text.charAt(j);
                    if (Character.isWhitespace(d)) {
                        j++;
                    } else if (d == '/' && j + 1 < n && text.charAt(j + 1) == '/') {
                        while (j < n && text.charAt(j) != '\n') {
                            j++;
                        }
                    } else if (d == '/' && j + 1 < n && text.charAt(j + 1) == '*') {
                        j += 2;
                        while (j + 1 < n && !(text.charAt(j) == '*' && text.charAt(j + 1) == '/')) {
                            j++;
                        }
                        j = Math.min(j + 2, n);
                    } else {
                        break;
                    }
                }
                if (j < n && (text.charAt(j) == '}' || text.charAt(j) == ']')) {
                    i++; // drop the comma; comments/whitespace after it are handled normally
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static final String EXAMPLE_FILE = """
            // DataRewriter — data tweaks.
            // Every .json / .json5 file in this folder is loaded, subfolders
            // included; split rules across files and folders however you like.
            // Comments and trailing commas are allowed.
            // Changes apply on server restart or /reload (ops get a chat summary).
            {
              recipes: {
                // Each rule removes every recipe matching ALL of its conditions.
                remove: [
                  // { mod: "uselessmod" },                  // every recipe from one mod
                  // { id: "minecraft:golden_apple" },       // one exact recipe id
                  // { id: "minecraft:*_boat" },             // '*' wildcards work in ids
                  // { output: "minecraft:elytra" },         // everything that crafts this item
                  // { input: "minecraft:diamond" },         // everything using this ingredient
                  // { input: "#minecraft:planks" },         // tags work for output/input too
                  // { type: "minecraft:blasting", mod: "minecraft" }, // conditions combine (AND)
                ],

                // New recipes, in vanilla recipe JSON format (same as datapacks),
                // except ingredients/results can be plain strings for vanilla types.
                // Give an entry an "id" matching an existing recipe to REPLACE it.
                add: [
                  // {
                  //   id: "minecraft:crafting_table",       // replaces the vanilla recipe
                  //   type: "minecraft:crafting_shaped",
                  //   pattern: ["XX", "XX"],
                  //   key: { X: "minecraft:oak_log" },
                  //   result: { id: "minecraft:crafting_table" },
                  // },
                  // {
                  //   type: "minecraft:smelting",           // no id -> one is generated
                  //   ingredient: "minecraft:rotten_flesh",
                  //   result: "minecraft:leather",
                  //   experience: 0.1,
                  //   cookingtime: 200,
                  // },
                ],

                // Swap an ingredient in EVERY recipe (results are not touched).
                // 'from': item id ('*' wildcards ok) or '#tag' (items in the tag,
                // plus refs to the identical tag). 'to': item id or '#tag'.
                replace_ingredients: [
                  // { from: "minecraft:diamond", to: "minecraft:emerald" },
                  // { from: "#minecraft:logs_that_burn", to: "#minecraft:stone_bricks" },
                ],
              },

              loot_tables: {
                // "Removed" loot tables are emptied: the block/mob/chest drops nothing.
                remove: [
                  // { id: "minecraft:entities/zombie" },    // one exact table
                  // { id: "minecraft:chests/*" },           // all vanilla chest loot
                  // { id: "*:entities/*" },                 // entity drops from every mod
                  // { mod: "uselessmod" },                  // every table from one mod
                ],

                // Vanilla loot table JSON format (same as datapacks). The id is
                // required — use an existing id to replace that table.
                add: [
                  // {
                  //   id: "minecraft:entities/zombie",      // zombies drop diamonds now
                  //   pools: [{
                  //     rolls: 1,
                  //     entries: [{ type: "minecraft:item", name: "minecraft:diamond" }],
                  //   }],
                  // },
                ],

                // Inject extra pools into existing tables, keeping their own loot.
                // Targets like remove (id with '*' wildcards, mod); runs after remove/add.
                modify: [
                  // {
                  //   id: "minecraft:chests/village/*",     // every village chest can also...
                  //   pools: [{
                  //     rolls: 1,
                  //     entries: [
                  //       { type: "minecraft:item", name: "minecraft:emerald" },
                  //       { type: "minecraft:empty", weight: 3 },  // ...often nothing (25% chance)
                  //     ],
                  //   }],
                  // },
                ],

                // Swap what item entries drop, across every loot table (or only
                // tables matching "table"). 'from' allows '*' wildcards or a
                // "#tag" (matches every item in the tag, plus identical tag
                // entries); "table" is one or more patterns (comma-separated or
                // a list) and a leading '!' excludes instead of includes.
                replace_items: [
                  // { from: "minecraft:diamond", to: "minecraft:emerald" },
                  // { from: "uselessmod:*", to: "minecraft:stick", table: "minecraft:chests/*" },
                  // { from: "minecraft:diamond", to: "minecraft:coal", table: "!*:blocks/*, !*:entities/*" },
                  // { from: "#c:fishes", to: "minecraft:cod" },
                ],

                // Delete item entries from loot tables; the rest of each table
                // stays intact. A bare string means { item: "..." }; "table"
                // scopes like in replace_items.
                remove_items: [
                  // "minecraft:diamond",
                  // "#minecraft:music_discs",
                  // { item: "uselessmod:*" },
                  // { item: "minecraft:emerald", table: "minecraft:entities/*" },
                  // { item: "minecraft:gold_ingot", table: ["somemod:*", "!somemod:blocks/*"] },
                ],
              },
            }
            """;
}
