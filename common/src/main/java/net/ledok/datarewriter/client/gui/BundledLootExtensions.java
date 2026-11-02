package net.ledok.datarewriter.client.gui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.ledok.datarewriter.platform.Platform;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Loot editor support shipped with DataRewriter for other mods' loot elements, registered when the mod is
 * installed (so those mods need no dependency on DataRewriter):
 * <ul>
 * <li>DD Loot Stage's {@code loot_stage:level_table} entry — a chest slot with its level range, edited in
 * {@link LevelTableScreen}.</li>
 * <li>Dungeon Difficulty's {@code dungeon_difficulty:local_scaling} table function — a labelled chip.</li>
 * </ul>
 */
public final class BundledLootExtensions {
    private BundledLootExtensions() {
    }

    public static void register() {
        if (Platform.INSTANCE.isModLoaded("loot_stage")) {
            LootEditorExtensions.registerEntryType("loot_stage:level_table", new LevelTable());
        }
        if (Platform.INSTANCE.isModLoaded("dungeon_difficulty")) {
            LootEditorExtensions.registerTableFunction("dungeon_difficulty:local_scaling", new LocalScaling());
        }
    }

    private static final class LevelTable implements LootEditorExtensions.EntryType {
        @Override
        public String displayName() {
            return "Level table";
        }

        @Override
        public ItemStack icon(JsonObject entry) {
            return new ItemStack(Items.CHEST);
        }

        @Override
        public @Nullable String badge(JsonObject entry) {
            List<Integer> levels = levels(entry);
            if (levels.isEmpty()) {
                return "?";
            }
            int min = levels.get(0);
            int max = levels.get(levels.size() - 1);
            return min == max ? String.valueOf(min) : min + "-" + max;
        }

        @Override
        public List<Component> tooltip(JsonObject entry) {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal("Level table (DD Loot Stage)").withStyle(ChatFormatting.GOLD));
            lines.add(Component.literal("Rolls the table for the loot level here (dungeon level, capped by the "
                    + "player's loot stage):").withStyle(ChatFormatting.GRAY));
            List<Map.Entry<Integer, String>> rows = rows(entry);
            for (int i = 0; i < Math.min(8, rows.size()); i++) {
                lines.add(Component.literal("  level " + rows.get(i).getKey() + "+ → " + rows.get(i).getValue()));
            }
            if (rows.size() > 8) {
                lines.add(Component.literal("  …and " + (rows.size() - 8) + " more").withStyle(ChatFormatting.GRAY));
            }
            if (entry.get("quality") instanceof JsonPrimitive q && q.isNumber() && q.getAsInt() != 0) {
                lines.add(Component.literal("Quality: " + q.getAsInt()).withStyle(ChatFormatting.AQUA));
            }
            return lines;
        }

        @Override
        public Screen edit(Screen parent, JsonObject entry, Consumer<JsonObject> done) {
            return new LevelTableScreen(parent, entry, done);
        }

        @Override
        public @Nullable String validate(JsonObject entry) {
            if (!(entry.get("tables") instanceof JsonObject tables) || tables.isEmpty()) {
                return "needs at least one level";
            }
            for (Map.Entry<String, JsonElement> e : tables.entrySet()) {
                try {
                    Integer.parseInt(e.getKey().trim());
                } catch (NumberFormatException ex) {
                    return "'" + e.getKey() + "' is not a level";
                }
                if (!(e.getValue() instanceof JsonPrimitive p) || ResourceLocation.tryParse(p.getAsString()) == null) {
                    return "level " + e.getKey() + " has no valid loot table";
                }
            }
            return null;
        }

        @Override
        public JsonObject create() {
            JsonObject entry = new JsonObject();
            entry.addProperty("type", "loot_stage:level_table");
            JsonObject tables = new JsonObject();
            tables.addProperty("1", "minecraft:empty");
            entry.add("tables", tables);
            return entry;
        }

        private static List<Map.Entry<Integer, String>> rows(JsonObject entry) {
            List<Map.Entry<Integer, String>> rows = new ArrayList<>();
            if (entry.get("tables") instanceof JsonObject tables) {
                for (Map.Entry<String, JsonElement> e : tables.entrySet()) {
                    try {
                        rows.add(Map.entry(Integer.parseInt(e.getKey().trim()),
                                e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : "?"));
                    } catch (NumberFormatException ignored) {
                        // shown by validate
                    }
                }
            }
            rows.sort(Map.Entry.comparingByKey());
            return rows;
        }

        private static List<Integer> levels(JsonObject entry) {
            List<Integer> levels = new ArrayList<>(rows(entry).stream().map(Map.Entry::getKey).toList());
            levels.sort(Comparator.naturalOrder());
            return levels;
        }
    }

    private static final class LocalScaling implements LootEditorExtensions.TableFunction {
        @Override
        public Component label(JsonObject function) {
            String scope = scope(function);
            return Component.literal("DD scaling" + (scope.isEmpty() ? "" : " (" + scope + ")"));
        }

        @Override
        public List<Component> tooltip(JsonObject function) {
            String scope = scope(function);
            return List.of(Component.literal("Dungeon Difficulty scales the items this table drops by the "
                            + "difficulty where it is opened.").withStyle(ChatFormatting.GRAY),
                    Component.literal(scope.isEmpty() ? "No table namespace/path given"
                            : "Matched by Dungeon Difficulty's config as " + scope).withStyle(ChatFormatting.GRAY));
        }

        private static String scope(JsonObject function) {
            String namespace = function.get("loot_table_namespace") instanceof JsonPrimitive p ? p.getAsString() : "";
            String path = function.get("loot_table_path") instanceof JsonPrimitive p ? p.getAsString() : "";
            return namespace.isEmpty() && path.isEmpty() ? "" : namespace + "/" + path;
        }
    }
}
