package net.ledok.datarewriter.client.gui;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Teaches the loot table editor about loot elements of other mods. Register from a client addon
 * ({@code DataRewriterClientAddon.registerClient}) or at client init. Without a registration, an unknown entry
 * type shows as a locked slot and an unknown table function as a plain chip — both still preserved on save.
 */
public final class LootEditorExtensions {
    private static final Map<String, EntryType> ENTRY_TYPES = new LinkedHashMap<>();
    private static final Map<String, TableFunction> TABLE_FUNCTIONS = new LinkedHashMap<>();

    private LootEditorExtensions() {
    }

    /** How the editor shows and edits one custom loot pool entry type. */
    public interface EntryType {
        /** Name for menus ("Level table"). */
        String displayName();

        /** The slot's icon for this entry. */
        ItemStack icon(JsonObject entry);

        /** Short text on the slot's lower right (like a stack count), or null. */
        default @Nullable String badge(JsonObject entry) {
            return null;
        }

        /** Tooltip lines describing the entry (weight and chance lines are added by the editor). */
        List<Component> tooltip(JsonObject entry);

        /**
         * The sub-editor for the entry, opened on click. {@code entry} is a copy; call {@code done} with the
         * edited entry JSON to apply it, then return to {@code parent} (or just return there to cancel).
         */
        Screen edit(Screen parent, JsonObject entry, Consumer<JsonObject> done);

        /** What is wrong with the entry, or null; checked before the table is saved. */
        default @Nullable String validate(JsonObject entry) {
            return null;
        }

        /** A new entry for the "special entry" menu of a pool's add-slot, or null to not offer one there. */
        default @Nullable JsonObject create() {
            return null;
        }
    }

    /** How the editor labels one table-level loot function (a chip over the pools). */
    public interface TableFunction {
        Component label(JsonObject function);

        default List<Component> tooltip(JsonObject function) {
            return List.of();
        }
    }

    /** {@code typeId} like "mymod:my_entry" (the entry's "type"). A later registration replaces an earlier one. */
    public static void registerEntryType(String typeId, EntryType type) {
        ENTRY_TYPES.put(typeId, type);
    }

    /** {@code functionId} like "mymod:my_function" (the function's "function"). */
    public static void registerTableFunction(String functionId, TableFunction function) {
        TABLE_FUNCTIONS.put(functionId, function);
    }

    static @Nullable EntryType entryType(String typeId) {
        return ENTRY_TYPES.get(typeId);
    }

    static @Nullable TableFunction tableFunction(String functionId) {
        return TABLE_FUNCTIONS.get(functionId);
    }

    /** Registered types that offer a fresh entry, for the special-entry menu. */
    static Map<String, EntryType> creatableEntryTypes() {
        Map<String, EntryType> creatable = new LinkedHashMap<>();
        ENTRY_TYPES.forEach((id, type) -> {
            if (type.create() != null) {
                creatable.put(id, type);
            }
        });
        return creatable;
    }
}
