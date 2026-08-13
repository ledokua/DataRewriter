package net.ledok.datarewriter.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import net.fabricmc.loader.api.FabricLoader;
import net.ledok.datarewriter.Datarewriter;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persists loot table edits coming from the in-game loot editor into a normal
 * DataRewriter config file, so they survive restarts and /reload. The caller
 * (LootEditNetworking) validates the JSON with the loot table codec first and
 * applies the change live.
 */
public final class GuiLootSaver {
    public static final String FILE_NAME = "gui-loot-tables.json5";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String BANNER = """
            // Loot table edits made with the in-game loot editor.
            // This is a normal DataRewriter config file — edit or move entries freely.
            """;

    private GuiLootSaver() {
    }

    /**
     * Saves a whole-table replacement as an 'add' entry (one per table id —
     * saving again replaces the previous entry). A conflicting exact-id
     * 'remove' rule from an earlier "empty table" save is dropped.
     */
    public static String saveReplace(String tableId, JsonObject table) {
        return update(root -> {
            JsonObject loot = lootSection(root);
            removeEntriesWithId(array(loot, "remove"), "id", tableId);
            JsonArray add = array(loot, "add");
            removeEntriesWithId(add, "id", tableId);
            JsonObject entry = new JsonObject();
            entry.addProperty("id", tableId);
            for (String key : table.keySet()) {
                entry.add(key, table.get(key));
            }
            add.add(entry);
        });
    }

    /**
     * Saves pools appended to a table as a 'modify' entry targeting exactly
     * that table (one per table id — saving again replaces it).
     */
    public static String saveModify(String tableId, JsonArray pools) {
        return update(root -> {
            JsonObject loot = lootSection(root);
            JsonArray modify = array(loot, "modify");
            removeEntriesWithId(modify, "id", tableId);
            JsonObject entry = new JsonObject();
            entry.addProperty("id", tableId);
            entry.add("pools", pools);
            modify.add(entry);
        });
    }

    /**
     * Saves an "empty this table" edit as a 'remove' rule. Earlier GUI
     * 'add'/'modify' entries for the table are dropped — added tables are
     * exempt from removal rules, so leaving them would undo the emptying.
     */
    public static String saveRemove(String tableId) {
        return update(root -> {
            JsonObject loot = lootSection(root);
            removeEntriesWithId(array(loot, "add"), "id", tableId);
            removeEntriesWithId(array(loot, "modify"), "id", tableId);
            JsonArray remove = array(loot, "remove");
            for (JsonElement e : remove) {
                if (e instanceof JsonObject obj && hasStringValue(obj, "id", tableId)) {
                    return; // already there
                }
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("id", tableId);
            remove.add(entry);
        });
    }

    /** Appends a bulk 'replace_items' rule (skipped if an identical one exists). */
    public static String saveReplaceItems(String from, String to, String tableScope) {
        JsonObject entry = new JsonObject();
        entry.addProperty("from", from);
        entry.addProperty("to", to);
        if (!tableScope.isEmpty()) {
            entry.addProperty("table", tableScope);
        }
        return appendUnique("replace_items", entry);
    }

    /** Appends a bulk 'remove_items' rule (skipped if an identical one exists). */
    public static String saveRemoveItems(String item, String tableScope) {
        JsonObject entry = new JsonObject();
        entry.addProperty("item", item);
        if (!tableScope.isEmpty()) {
            entry.addProperty("table", tableScope);
        }
        return appendUnique("remove_items", entry);
    }

    private static String appendUnique(String listKey, JsonObject entry) {
        return update(root -> {
            JsonArray list = array(lootSection(root), listKey);
            for (JsonElement existing : list) {
                if (existing.equals(entry)) {
                    return;
                }
            }
            list.add(entry);
        });
    }

    private interface RootEdit {
        void apply(JsonObject root);
    }

    /** Reads the config file, applies the edit, writes it back. Returns an error or null. */
    private static synchronized String update(RootEdit edit) {
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
                    return FILE_NAME + " exists but is not a config object — fix or delete it first";
                }
            }
        } catch (Exception e) {
            return "could not read " + FILE_NAME + ": " + e.getMessage();
        }

        edit.apply(root);

        try {
            Files.writeString(file, BANNER + GSON.toJson(root) + "\n");
        } catch (IOException e) {
            return "could not write " + FILE_NAME + ": " + e.getMessage();
        }
        return null;
    }

    private static JsonObject lootSection(JsonObject root) {
        JsonObject loot = root.get("loot_tables") instanceof JsonObject o ? o : new JsonObject();
        root.add("loot_tables", loot);
        return loot;
    }

    private static JsonArray array(JsonObject parent, String key) {
        JsonArray list = parent.get(key) instanceof JsonArray a ? a : new JsonArray();
        parent.add(key, list);
        return list;
    }

    private static void removeEntriesWithId(JsonArray list, String key, String value) {
        list.asList().removeIf(e -> e instanceof JsonObject obj && hasStringValue(obj, key, value));
    }

    private static boolean hasStringValue(JsonObject obj, String key, String value) {
        return obj.get(key) instanceof JsonPrimitive p && p.isString() && p.getAsString().equals(value);
    }
}
