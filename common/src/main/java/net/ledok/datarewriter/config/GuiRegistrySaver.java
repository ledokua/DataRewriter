package net.ledok.datarewriter.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import net.ledok.datarewriter.Datarewriter;
import net.ledok.datarewriter.platform.Platform;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persists datapack registry entries coming from the in-game ritual editor into a normal
 * DataRewriter config file ({@code registries.add} entries), so they survive restarts. The caller
 * (RegistryEditNetworking) validates the JSON with the registry's own codec first and applies the
 * change live.
 */
public final class GuiRegistrySaver {
    public static final String FILE_NAME = "gui-registries.json5";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String BANNER = """
            // Datapack registry entries (Hephaestus Forge rituals, ...) saved with the in-game editor.
            // This is a normal DataRewriter config file — edit or move entries freely.
            // Registry rules apply while the world loads, not on /reload.
            """;

    /** What the last in-game save wrote, to notice external overwrites (panel editors, pack syncs). */
    private static String lastWritten;
    private static String pendingNotice;

    private GuiRegistrySaver() {
    }

    /**
     * Saves one entry as a {@code registries.add} rule (one per registry + id — saving again replaces
     * the previous one). Returns an error message or null.
     */
    public static synchronized String saveEntry(String registry, String id, JsonObject entry) {
        Path dir = Platform.INSTANCE.configDir().resolve(Datarewriter.MOD_ID);
        Path file = dir.resolve(FILE_NAME);
        JsonObject root = new JsonObject();
        try {
            Files.createDirectories(dir);
            if (Files.exists(file)) {
                String raw = Files.readString(file);
                if (lastWritten != null && !raw.equals(lastWritten)) {
                    pendingNotice = "note: " + FILE_NAME + " was changed on disk by something else since "
                            + "the last in-game save — this edit was merged onto the current file.";
                    Datarewriter.LOGGER.warn("Ritual editor: {} was modified outside the game since the last save",
                            file.toAbsolutePath());
                }
                String content = ConfigLoader.stripCommentsAndTrailingCommas(raw);
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

        JsonObject registries = root.get("registries") instanceof JsonObject existing
                ? existing : new JsonObject();
        root.add("registries", registries);
        JsonArray add = registries.get("add") instanceof JsonArray existing ? existing : new JsonArray();
        registries.add("add", add);
        for (int i = add.size() - 1; i >= 0; i--) {
            if (add.get(i) instanceof JsonObject old
                    && old.get("registry") instanceof JsonPrimitive r && r.getAsString().equals(registry)
                    && old.get("id") instanceof JsonPrimitive p && p.getAsString().equals(id)) {
                add.remove(i);
            }
        }
        JsonObject rule = new JsonObject();
        rule.addProperty("registry", registry);
        rule.addProperty("id", id);
        rule.add("entry", entry.deepCopy());
        add.add(rule);

        try {
            String out = BANNER + GSON.toJson(root) + "\n";
            Files.writeString(file, out);
            lastWritten = out;
            Datarewriter.LOGGER.info("Ritual editor: wrote {}", file.toAbsolutePath());
            return null;
        } catch (IOException e) {
            return "could not write " + FILE_NAME + ": " + e.getMessage();
        }
    }

    /** See the recipe/loot savers: true once when the file changed outside the game since the last save. */
    public static synchronized boolean changedSinceLastSave() {
        if (lastWritten == null) {
            return false;
        }
        Path file = Platform.INSTANCE.configDir().resolve(Datarewriter.MOD_ID).resolve(FILE_NAME);
        try {
            String raw = Files.exists(file) ? Files.readString(file) : "";
            if (raw.equals(lastWritten)) {
                return false;
            }
            Datarewriter.LOGGER.warn("Ritual editor: {} differs from what the in-game editor last wrote — "
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

    /** Unused JsonElement import guard (kept for parity with the other savers' shapes). */
    @SuppressWarnings("unused")
    private static JsonElement unused() {
        return null;
    }
}
