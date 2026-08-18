package net.ledok.datarewriter.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.datarewriter.menu.LootEditorMenu;
import net.ledok.datarewriter.network.LootPayloads;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-game loot table editor. Shows a table's pools with their entries as item
 * slots; edits are sent to the server, which validates them, writes them to a
 * normal DataRewriter config file and applies them live (op only).
 *
 * Only the common knobs are edited visually — item, weight, count range, drop
 * chance, rolls. Everything else an entry or pool carries (conditions,
 * functions, nested entries) is preserved untouched, and entry types the
 * editor can't represent are shown as locked slots.
 */
public class LootTableEditorScreen extends AbstractContainerScreen<LootEditorMenu> {
    private static final int ENTRY_COLS = 9;
    private static final int PANEL_W = ENTRY_COLS * 18 + 12;

    private final Screen parent;
    public final String tableId;

    /**
     * Unsaved edits, kept per table id until the game quits (like the recipe
     * editor's slots): reopening a table restores its draft; Save and Reload
     * clear it.
     */
    private record Draft(String tableJson, List<Boolean> newFlags, boolean dirtyExisting) {
    }

    private static final Map<String, Draft> DRAFTS = new HashMap<>();

    /** Table-level JSON with all keys the editor doesn't manage preserved. */
    private JsonObject tableSource = new JsonObject();
    private final List<PoolState> pools = new ArrayList<>();
    private boolean loading = true;
    /** An existing (non-new) pool was edited — only "Save table" keeps that. */
    private boolean dirtyExisting;
    /** The table exists on the server (saving an empty pool list can empty it). */
    private boolean existsOnServer;

    private Component status = Component.empty();

    // Player inventory panel (left side), same as the recipe editor.
    private static final int INV_COLS = 4;
    private static final int INV_ROWS = 9;
    private int invLeft;
    private int invTop;
    private Item cursorItem;

    /** The mouse-press already placed the cursor item — its release must not place again. */
    private boolean placedOnPress;

    private final ScrollBar scrollBar = new ScrollBar();

    private int contentLeft;
    private int panelLeft;
    private int panelTop;
    private int panelHeight;
    private int scrollOffset;
    private int statusY;

    private int uiLeft;
    private int uiTop;
    private int uiRight;
    private int uiBottom;

    enum Kind {
        ITEM, TAG, EMPTY, COMPOSITE, TABLE_REF, COMPLEX
    }

    static final class EntryState {
        JsonObject source;
        Kind kind;
        String ref;                     // "mod:item"/"#mod:tag" for ITEM/TAG, table id for TABLE_REF
        final List<EntryState> children = new ArrayList<>(); // COMPOSITE only
        int weight = 1;
        int countMin = 1;
        int countMax = 1;
        boolean countLocked;            // set_count exists but is too fancy to edit
        JsonObject countFn;             // the set_count function we manage
        int chance = 100;               // percent
        boolean chanceLocked;
        JsonObject chanceCond;          // the random_chance condition we manage
        ItemStack iconCache;            // icon with visual loot functions applied
        String iconCacheRef;
        boolean fancy;                  // functions change how the drop looks
        /** Merged into this pool at runtime by another mod — shown read-only, never saved. */
        boolean injected;
        String injectedBy = "";
    }

    /** A fresh 'empty' entry — a weighted chance to drop nothing. */
    static EntryState newEmptyEntry() {
        EntryState entry = new EntryState();
        entry.source = new JsonObject();
        entry.source.addProperty("type", "minecraft:empty");
        entry.kind = Kind.EMPTY;
        return entry;
    }

    /** A fresh composite entry (alternatives / group / sequence) with no children yet. */
    static EntryState newComposite(String typeId) {
        EntryState entry = new EntryState();
        entry.source = new JsonObject();
        entry.source.addProperty("type", typeId);
        entry.kind = Kind.COMPOSITE;
        return entry;
    }

    /** A fresh nested-loot-table entry pointing at the given table id. */
    static EntryState newTableRef(String tableId) {
        EntryState entry = new EntryState();
        entry.source = new JsonObject();
        entry.source.addProperty("type", "minecraft:loot_table");
        entry.source.addProperty("value", tableId);
        entry.kind = Kind.TABLE_REF;
        entry.ref = tableId;
        return entry;
    }

    private static final class PoolState {
        JsonObject source;
        int rollsMin = 1;
        int rollsMax = 1;
        boolean rollsLocked;            // rolls uses a provider the editor can't show
        final List<EntryState> entries = new ArrayList<>();
        boolean isNew;
        /** Added at runtime by another mod's loot injection — shown read-only. */
        boolean injected;
        /** Namespace guess of the injecting mod ("" = unknown). */
        String injectedBy = "";
    }

    public LootTableEditorScreen(Screen parent, String tableId) {
        super(new LootEditorMenu(0, Minecraft.getInstance().player.getInventory()),
                Minecraft.getInstance().player.getInventory(),
                Component.literal("Loot Table Editor"));
        this.parent = parent;
        this.tableId = tableId;
        requestContent();
    }

    private void requestContent() {
        loading = true;
        if (ClientPlayNetworking.canSend(LootPayloads.TableRequest.TYPE)) {
            ClientPlayNetworking.send(new LootPayloads.TableRequest(tableId));
        } else {
            loading = false;
            status = Component.literal("This server doesn't run DataRewriter — nothing to edit.")
                    .withStyle(ChatFormatting.RED);
        }
    }

    /** Called by the client networking receiver with the table's server-side JSON. */
    public void onContent(String json, String injected) {
        loading = false;
        pools.clear();
        dirtyExisting = false;
        cursorItem = null;
        tableSource = new JsonObject();
        existsOnServer = !json.isEmpty();
        if (restoreDraft(injected)) {
            return;
        }
        if (json.isEmpty()) {
            status = Component.literal("New table — it doesn't exist yet. Add a pool.")
                    .withStyle(ChatFormatting.YELLOW);
        } else {
            try {
                if (JsonParser.parseString(json) instanceof JsonObject table) {
                    parseTable(table);
                    status = Component.empty();
                } else {
                    status = Component.literal("Unexpected table JSON from the server.")
                            .withStyle(ChatFormatting.RED);
                }
            } catch (Exception e) {
                status = Component.literal("Could not parse the table: " + e.getMessage())
                        .withStyle(ChatFormatting.RED);
            }
        }
        parseInjected(injected);
        rebuildWidgets();
    }

    /** Restores this table's unsaved draft, if one exists. */
    private boolean restoreDraft(String injected) {
        Draft draft = DRAFTS.get(tableId);
        if (draft == null) {
            return false;
        }
        try {
            if (!(JsonParser.parseString(draft.tableJson()) instanceof JsonObject table)) {
                return false;
            }
            parseTable(table);
            for (int i = 0; i < pools.size() && i < draft.newFlags().size(); i++) {
                pools.get(i).isNew = draft.newFlags().get(i);
            }
            dirtyExisting = draft.dirtyExisting();
            status = Component.literal("Restored unsaved edits from this session — Reload discards them.")
                    .withStyle(ChatFormatting.YELLOW);
            parseInjected(injected);
            rebuildWidgets();
            return true;
        } catch (Exception e) {
            pools.clear();
            tableSource = new JsonObject();
            DRAFTS.remove(tableId);
            return false;
        }
    }

    /** Pools other mods added at runtime, appended to the view as read-only. */
    private void parseInjected(String injected) {
        if (injected.isEmpty()) {
            return;
        }
        try {
            JsonElement parsed = JsonParser.parseString(injected);
            JsonArray tailPools = parsed instanceof JsonArray array ? array
                    : parsed instanceof JsonObject obj && obj.get("pools") instanceof JsonArray a ? a : new JsonArray();
            // Entries other mods merged into the table's own pools (by pool index).
            if (parsed instanceof JsonObject obj && obj.get("entries") instanceof JsonObject perPool) {
                for (String key : perPool.keySet()) {
                    int poolIndex;
                    try {
                        poolIndex = Integer.parseInt(key);
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    if (poolIndex < 0 || poolIndex >= pools.size()
                            || !(perPool.get(key) instanceof JsonArray extra)) {
                        continue;
                    }
                    for (JsonElement element : extra) {
                        if (element instanceof JsonObject entryObj) {
                            EntryState entry = parseEntry(entryObj);
                            entry.injected = true;
                            entry.injectedBy = guessNamespace(entryObj);
                            pools.get(poolIndex).entries.add(entry);
                        }
                    }
                }
            }
            for (JsonElement element : tailPools) {
                if (element instanceof JsonObject poolObj) {
                    PoolState pool = parsePool(poolObj, false);
                    pool.injected = true;
                    pool.injectedBy = guessNamespace(poolObj);
                    pools.add(pool);
                }
            }
        } catch (Exception ignored) {
            // Display-only extras; a parse failure just hides them.
        }
    }

    /**
     * Guesses which mod injected a pool from the non-vanilla namespaces of
     * the ids inside it (items, tags, entry/function types).
     */
    private static String guessNamespace(JsonObject pool) {
        Map<String, Integer> counts = new HashMap<>();
        countNamespaces(pool, counts);
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("");
    }

    private static void countNamespaces(JsonElement element, Map<String, Integer> counts) {
        if (element instanceof JsonObject obj) {
            for (String key : obj.keySet()) {
                countNamespaces(obj.get(key), counts);
            }
        } else if (element instanceof JsonArray array) {
            for (JsonElement child : array) {
                countNamespaces(child, counts);
            }
        } else if (element instanceof JsonPrimitive p && p.isString()) {
            String value = p.getAsString();
            int colon = value.indexOf(':');
            if (colon > 0 && ResourceLocation.tryParse(
                    value.startsWith("#") ? value.substring(1) : value) != null) {
                String namespace = (value.startsWith("#") ? value.substring(1) : value)
                        .split(":", 2)[0];
                if (!namespace.equals("minecraft")) {
                    counts.merge(namespace, 1, Integer::sum);
                }
            }
        }
    }

    // --- JSON <-> state -------------------------------------------------

    private void parseTable(JsonObject table) {
        tableSource = table;
        if (table.get("pools") instanceof JsonArray array) {
            for (JsonElement element : array) {
                if (element instanceof JsonObject poolObj) {
                    pools.add(parsePool(poolObj, false));
                }
            }
        }
    }

    private PoolState parsePool(JsonObject source, boolean isNew) {
        PoolState pool = new PoolState();
        pool.source = source;
        pool.isNew = isNew;
        if (source.has("rolls")) {
            int[] rolls = parseProvider(source.get("rolls"));
            if (rolls != null) {
                pool.rollsMin = rolls[0];
                pool.rollsMax = rolls[1];
            } else {
                pool.rollsLocked = true;
            }
        }
        if (source.get("entries") instanceof JsonArray entries) {
            for (JsonElement element : entries) {
                if (element instanceof JsonObject entryObj) {
                    pool.entries.add(parseEntry(entryObj));
                }
            }
        }
        return pool;
    }

    private EntryState parseEntry(JsonObject source) {
        EntryState entry = new EntryState();
        entry.source = source;
        String type = source.get("type") instanceof JsonPrimitive p && p.isString()
                ? normalizeId(p.getAsString()) : "";
        String name = source.get("name") instanceof JsonPrimitive p && p.isString()
                ? p.getAsString() : null;
        switch (type) {
            case "minecraft:item" -> {
                entry.kind = name != null ? Kind.ITEM : Kind.COMPLEX;
                entry.ref = name;
            }
            case "minecraft:tag" -> {
                entry.kind = name != null ? Kind.TAG : Kind.COMPLEX;
                entry.ref = name != null ? "#" + name : null;
            }
            case "minecraft:empty" -> entry.kind = Kind.EMPTY;
            case "minecraft:alternatives", "minecraft:group", "minecraft:sequence" -> {
                entry.kind = Kind.COMPOSITE;
                if (source.get("children") instanceof JsonArray children) {
                    for (JsonElement element : children) {
                        if (element instanceof JsonObject childObj) {
                            entry.children.add(parseEntry(childObj));
                        }
                    }
                }
            }
            case "minecraft:loot_table" -> {
                // "value" is either a table id (editable) or an inline table
                // (preserved as-is).
                if (source.get("value") instanceof JsonPrimitive v && v.isString()) {
                    entry.kind = Kind.TABLE_REF;
                    entry.ref = v.getAsString();
                } else {
                    entry.kind = Kind.COMPLEX;
                }
            }
            default -> entry.kind = Kind.COMPLEX;
        }
        if (source.get("weight") instanceof JsonPrimitive w && w.isNumber()) {
            entry.weight = Math.max(1, w.getAsInt());
        }
        if (source.get("functions") instanceof JsonArray functions) {
            for (JsonElement element : functions) {
                if (element instanceof JsonObject fn
                        && fn.get("function") instanceof JsonPrimitive id && id.isString()
                        && normalizeId(id.getAsString()).equals("minecraft:set_count")) {
                    // A conditional or additive set_count changes meaning if we
                    // rewrite it — show the count but don't let it be edited.
                    boolean simple = !fn.has("conditions")
                            && !(fn.get("add") instanceof JsonPrimitive add && add.getAsBoolean());
                    int[] count = parseProvider(fn.get("count"));
                    if (simple && count != null) {
                        entry.countFn = fn;
                        entry.countMin = count[0];
                        entry.countMax = count[1];
                    } else {
                        entry.countLocked = true;
                    }
                    break;
                }
            }
        }
        if (source.get("conditions") instanceof JsonArray conditions) {
            for (JsonElement element : conditions) {
                if (element instanceof JsonObject cond
                        && cond.get("condition") instanceof JsonPrimitive id && id.isString()
                        && normalizeId(id.getAsString()).equals("minecraft:random_chance")) {
                    if (cond.get("chance") instanceof JsonPrimitive c && c.isNumber()) {
                        entry.chanceCond = cond;
                        entry.chance = Math.max(1, Math.min(100, Math.round(c.getAsFloat() * 100)));
                    } else {
                        entry.chanceLocked = true;
                    }
                    break;
                }
            }
        }
        return entry;
    }

    private static String normalizeId(String id) {
        return id.contains(":") ? id : "minecraft:" + id;
    }

    /** {min, max} from a number provider, or null when it's not a plain int/uniform. */
    private static int[] parseProvider(JsonElement element) {
        if (element instanceof JsonPrimitive p && p.isNumber()) {
            float f = p.getAsFloat();
            return f == Math.floor(f) ? new int[]{(int) f, (int) f} : null;
        }
        if (!(element instanceof JsonObject obj)) {
            return null;
        }
        String type = obj.get("type") instanceof JsonPrimitive t && t.isString()
                ? normalizeId(t.getAsString()) : null;
        if (type == null || type.equals("minecraft:uniform")) {
            int[] min = obj.has("min") ? parseProvider(obj.get("min")) : null;
            int[] max = obj.has("max") ? parseProvider(obj.get("max")) : null;
            if (min != null && max != null && min[0] == min[1] && max[0] == max[1]) {
                return new int[]{min[0], max[0]};
            }
            return null;
        }
        if (type.equals("minecraft:constant")) {
            return obj.has("value") ? parseProvider(obj.get("value")) : null;
        }
        return null;
    }

    private static JsonElement providerJson(int min, int max) {
        if (min == max) {
            return new JsonPrimitive(min);
        }
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "minecraft:uniform");
        obj.addProperty("min", min);
        obj.addProperty("max", max);
        return obj;
    }

    /** Writes the editable knobs back into the entry's preserved JSON. */
    static void syncEntry(EntryState entry) {
        JsonObject source = entry.source;
        if (entry.kind == Kind.COMPOSITE) {
            JsonArray children = new JsonArray();
            for (EntryState child : entry.children) {
                syncEntry(child);
                children.add(child.source);
            }
            source.add("children", children);
        }
        if (entry.kind == Kind.TABLE_REF) {
            source.addProperty("value", entry.ref);
        }
        if (entry.kind == Kind.ITEM || entry.kind == Kind.TAG) {
            source.addProperty("type", entry.kind == Kind.TAG ? "minecraft:tag" : "minecraft:item");
            source.addProperty("name", entry.kind == Kind.TAG ? entry.ref.substring(1) : entry.ref);
            if (entry.kind == Kind.TAG) {
                // Tag entries require "expand"; true = each item in the tag is
                // its own weighted option (kept as-is if the entry had one).
                if (!source.has("expand")) {
                    source.addProperty("expand", true);
                }
            } else {
                source.remove("expand");
            }
        }
        if (entry.weight != 1) {
            source.addProperty("weight", entry.weight);
        } else {
            source.remove("weight");
        }
        if (!entry.countLocked) {
            if (entry.countFn == null && (entry.countMin != 1 || entry.countMax != 1)) {
                entry.countFn = new JsonObject();
                entry.countFn.addProperty("function", "minecraft:set_count");
                JsonArray functions = source.get("functions") instanceof JsonArray a ? a : new JsonArray();
                source.add("functions", functions);
                functions.add(entry.countFn);
            }
            if (entry.countFn != null) {
                entry.countFn.add("count", providerJson(entry.countMin, entry.countMax));
            }
        }
        if (!entry.chanceLocked) {
            if (entry.chance >= 100) {
                if (entry.chanceCond != null && source.get("conditions") instanceof JsonArray conditions) {
                    conditions.remove(entry.chanceCond);
                    entry.chanceCond = null;
                    if (conditions.isEmpty()) {
                        source.remove("conditions");
                    }
                }
            } else {
                if (entry.chanceCond == null) {
                    entry.chanceCond = new JsonObject();
                    entry.chanceCond.addProperty("condition", "minecraft:random_chance");
                    JsonArray conditions = source.get("conditions") instanceof JsonArray a ? a : new JsonArray();
                    source.add("conditions", conditions);
                    conditions.add(entry.chanceCond);
                }
                entry.chanceCond.addProperty("chance", entry.chance / 100f);
            }
        }
    }

    private static JsonObject syncPool(PoolState pool) {
        if (!pool.rollsLocked) {
            pool.source.add("rolls", providerJson(pool.rollsMin, pool.rollsMax));
        }
        JsonArray entries = new JsonArray();
        for (EntryState entry : pool.entries) {
            if (entry.injected) {
                continue; // belongs to the other mod, re-merged by it on every load
            }
            syncEntry(entry);
            entries.add(entry.source);
        }
        pool.source.add("entries", entries);
        return pool.source;
    }

    private JsonObject buildTableJson() {
        JsonArray array = new JsonArray();
        for (PoolState pool : pools) {
            if (!pool.injected) { // injected pools belong to the other mod
                array.add(syncPool(pool));
            }
        }
        tableSource.add("pools", array);
        return tableSource;
    }

    /** The table's own pools — everything except runtime-injected ones. */
    private boolean hasOwnPools() {
        return pools.stream().anyMatch(pool -> !pool.injected);
    }

    // --- saving ---------------------------------------------------------

    /** Server's answer to the last save (also in chat); clears draft flags only on success. */
    public void onSaveResult(boolean ok, String message) {
        if (ok) {
            if (pendingSuccess != null) {
                pendingSuccess.run();
            }
            status = Component.literal("✔ " + message).withStyle(ChatFormatting.GREEN);
        } else {
            status = Component.literal("✘ " + message).withStyle(ChatFormatting.RED);
        }
        pendingSuccess = null;
        rebuildWidgets();
    }

    private Runnable pendingSuccess;

    private void saveReplace() {
        if (!hasOwnPools()) {
            // No pools left: for an existing table this saves an "empty this
            // table" rule (it will drop nothing at all).
            if (!existsOnServer) {
                status = Component.literal("Nothing to save — the table doesn't exist yet. Add a pool.")
                        .withStyle(ChatFormatting.RED);
                return;
            }
            if (sendChunked("remove", "")) {
                pendingSuccess = () -> {
                    tableSource = new JsonObject();
                    dirtyExisting = false;
                };
                status = Component.literal("Empty-table rule sent — waiting for the server…")
                        .withStyle(ChatFormatting.YELLOW);
            }
            return;
        }
        if (sendChunked("replace", buildTableJson().toString())) {
            pendingSuccess = () -> {
                for (PoolState pool : pools) {
                    pool.isNew = false;
                }
                dirtyExisting = false;
            };
            status = Component.literal("Table sent — waiting for the server…")
                    .withStyle(ChatFormatting.YELLOW);
        }
    }

    private void saveModify() {
        JsonArray newPools = new JsonArray();
        for (PoolState pool : pools) {
            if (pool.isNew) {
                newPools.add(syncPool(pool));
            }
        }
        if (newPools.isEmpty()) {
            status = Component.literal("No added pools — 'Save table' saves edits to existing ones.")
                    .withStyle(ChatFormatting.RED);
            return;
        }
        if (sendChunked("modify", newPools.toString())) {
            String warning = dirtyExisting
                    ? " Edits to the table's own pools were NOT included — use Save table for those." : "";
            pendingSuccess = () -> {
                for (PoolState pool : pools) {
                    pool.isNew = false;
                }
            };
            status = Component.literal("Added pools sent — waiting for the server…" + warning)
                    .withStyle(ChatFormatting.YELLOW);
        }
    }

    /** Removes all pools locally — nothing reaches the server until "Save table". */
    private void emptyTable() {
        if (!hasOwnPools()) {
            status = Component.literal("The table is already empty.").withStyle(ChatFormatting.GRAY);
            return;
        }
        boolean removedExisting = pools.stream().anyMatch(pool -> !pool.isNew && !pool.injected);
        pools.removeIf(pool -> !pool.injected);
        if (removedExisting) {
            dirtyExisting = true;
        }
        status = Component.literal("All pools removed — 'Save table' makes it permanent, "
                + "Reload brings them back.").withStyle(ChatFormatting.YELLOW);
        rebuildWidgets();
    }

    private boolean sendChunked(String mode, String json) {
        if (!ClientPlayNetworking.canSend(LootPayloads.SaveTable.TYPE)) {
            status = Component.literal("This server doesn't run DataRewriter — nothing was saved.")
                    .withStyle(ChatFormatting.RED);
            return false;
        }
        int total = Math.max(1, (json.length() + LootPayloads.SAVE_CHUNK_CHARS - 1)
                / LootPayloads.SAVE_CHUNK_CHARS);
        for (int part = 0; part < total; part++) {
            int from = part * LootPayloads.SAVE_CHUNK_CHARS;
            int to = Math.min(json.length(), from + LootPayloads.SAVE_CHUNK_CHARS);
            ClientPlayNetworking.send(new LootPayloads.SaveTable(
                    mode, tableId, part, total, json.substring(from, to)));
        }
        return true;
    }

    // --- layout ----------------------------------------------------------

    @Override
    protected void init() {
        super.init();
        int contentW = Math.max(250, PANEL_W);
        contentLeft = (width - contentW) / 2;
        panelLeft = (width - PANEL_W) / 2;
        int top = Math.max(6, (height - (24 + 160 + 6 + 48 + 16)) / 2);

        addRenderableWidget(Button.builder(
                        Component.literal(tableId).withStyle(ChatFormatting.WHITE), b -> {
                            assert minecraft != null;
                            minecraft.setScreen(parent instanceof LootTablePickerScreen picker
                                    ? picker : new LootTablePickerScreen());
                        })
                .bounds(contentLeft, top, contentW, 20)
                .tooltip(Tooltip.create(Component.literal("Click to pick another loot table")))
                .build());

        panelTop = top + 24;
        invLeft = Math.max(4, contentLeft - INV_COLS * 18 - 16);
        invTop = Math.max(top, (height - INV_ROWS * 18) / 2);

        int chromeBelow = 6 + 48 + 16; // buttons (2 rows) + status
        int available = Math.max(60, height - panelTop - chromeBelow - 6);
        panelHeight = Math.min(Math.max(60, poolContentHeight()), available);
        scrollOffset = Math.max(0, Math.min(scrollOffset, poolContentHeight() - panelHeight));

        int y = panelTop + panelHeight + 6;
        int buttonW = (contentW - 8) / 3;
        boolean hasNewPools = pools.stream().anyMatch(pool -> pool.isNew);
        Button saveTable = Button.builder(Component.literal("Save table"), b -> saveReplace())
                .bounds(contentLeft, y, buttonW, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "Save the WHOLE table as a replacement.\nAlso keeps edits/deletions of the "
                                + "table's own pools — but future changes to this table by its mod "
                                + "or datapack are overridden.\nWith every pool deleted, this saves "
                                + "an empty-table rule instead.")))
                .build();
        addRenderableWidget(saveTable);
        Button savePools = Button.builder(Component.literal("Save added pools"), b -> saveModify())
                .bounds(contentLeft + buttonW + 4, y, buttonW, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "Save only the pools you added here, appended to the table.\nThe table's own "
                                + "loot stays as its mod ships it (survives mod updates).")))
                .build();
        savePools.active = hasNewPools;
        addRenderableWidget(savePools);
        addRenderableWidget(Button.builder(Component.literal("Add pool"), b -> {
                    PoolState pool = new PoolState();
                    pool.source = new JsonObject();
                    pool.isNew = true;
                    pools.add(pool);
                    status = Component.empty();
                    rebuildWidgets();
                })
                .bounds(contentLeft + (buttonW + 4) * 2, y, buttonW, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "A pool rolls its item list 'rolls' times; each roll picks ONE entry by weight")))
                .build());
        y += 24;
        addRenderableWidget(Button.builder(Component.literal("Empty table"), b -> emptyTable())
                .bounds(contentLeft, y, buttonW, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "Remove all pools, so the table drops nothing.\nOnly a local edit — "
                                + "'Save table' makes it permanent, Reload undoes it.")))
                .build());
        addRenderableWidget(Button.builder(Component.literal("Reload"), b -> {
                    scrollOffset = 0;
                    status = Component.empty();
                    DRAFTS.remove(tableId); // Reload really discards the draft
                    requestContent();
                })
                .bounds(contentLeft + buttonW + 4, y, buttonW, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "Discard unsaved changes and refetch the table from the server")))
                .build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(contentLeft + (buttonW + 4) * 2, y, buttonW, 20).build());

        statusY = y + 26;

        uiLeft = Math.min(invLeft - 4, contentLeft);
        uiTop = Math.min(top, invTop - 14);
        uiRight = Math.max(contentLeft + contentW, panelLeft + PANEL_W) + 4;
        uiBottom = Math.max(statusY + 12, invTop + INV_ROWS * 18) + 4;

        leftPos = uiLeft;
        topPos = uiTop;
        imageWidth = uiRight - uiLeft;
        imageHeight = uiBottom - uiTop;
    }

    /** Slot count of a pool's grid; injected pools have no add-slot. */
    private static int slotCount(PoolState pool) {
        return pool.entries.size() + (pool.injected ? 0 : 1);
    }

    private int poolHeight(PoolState pool) {
        int rows = (Math.max(1, slotCount(pool)) + ENTRY_COLS - 1) / ENTRY_COLS;
        return 14 + rows * 18 + 6;
    }

    private int poolContentHeight() {
        int total = 8;
        for (PoolState pool : pools) {
            total += poolHeight(pool);
        }
        return total;
    }

    /** Y of pool {@code index}'s header line, in screen coordinates. */
    private int poolY(int index) {
        int y = panelTop + 4 - scrollOffset;
        for (int i = 0; i < index; i++) {
            y += poolHeight(pools.get(i));
        }
        return y;
    }

    private int entryX(int entryIndex) {
        return panelLeft + 6 + (entryIndex % ENTRY_COLS) * 18;
    }

    private int entryY(int poolIndex, int entryIndex) {
        return poolY(poolIndex) + 13 + (entryIndex / ENTRY_COLS) * 18;
    }

    /** {poolIndex, entryIndex} under the mouse; entryIndex == size() is the add-slot. */
    private int[] entryAt(double mouseX, double mouseY) {
        if (!inPanel(mouseX, mouseY)) {
            return null;
        }
        for (int p = 0; p < pools.size(); p++) {
            PoolState pool = pools.get(p);
            for (int e = 0; e < slotCount(pool); e++) {
                int x = entryX(e);
                int y = entryY(p, e);
                if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
                    return new int[]{p, e};
                }
            }
        }
        return null;
    }

    /** Pool index whose rolls text is under the mouse, or -1. */
    private int rollsAt(double mouseX, double mouseY) {
        if (!inPanel(mouseX, mouseY)) {
            return -1;
        }
        for (int p = 0; p < pools.size(); p++) {
            int y = poolY(p);
            int x = panelLeft + 6 + font.width("Pool " + (p + 1)) + 6;
            if (mouseX >= x && mouseX < x + font.width(rollsText(pools.get(p))) + 4
                    && mouseY >= y && mouseY < y + 11) {
                return p;
            }
        }
        return -1;
    }

    /** Editable pool index whose card area contains the point, or -1 (drop target). */
    private int poolAt(double mouseX, double mouseY) {
        if (!inPanel(mouseX, mouseY)) {
            return -1;
        }
        for (int p = 0; p < pools.size(); p++) {
            int y = poolY(p);
            if (mouseY >= y && mouseY < y + poolHeight(pools.get(p))) {
                return pools.get(p).injected ? -1 : p;
            }
        }
        return -1;
    }

    /** Pool index whose delete cross is under the mouse, or -1. */
    private int poolDeleteAt(double mouseX, double mouseY) {
        if (!inPanel(mouseX, mouseY)) {
            return -1;
        }
        for (int p = 0; p < pools.size(); p++) {
            int y = poolY(p);
            int x = panelLeft + PANEL_W - 15;
            if (mouseX >= x && mouseX < x + 10 && mouseY >= y && mouseY < y + 11) {
                return pools.get(p).injected ? -1 : p; // injected pools have no cross
            }
        }
        return -1;
    }

    private boolean inPanel(double mouseX, double mouseY) {
        return mouseX >= panelLeft && mouseX < panelLeft + PANEL_W
                && mouseY >= panelTop && mouseY < panelTop + panelHeight;
    }

    private String rollsText(PoolState pool) {
        if (pool.rollsLocked) {
            return "rolls: custom";
        }
        return "rolls: " + (pool.rollsMin == pool.rollsMax ? String.valueOf(pool.rollsMin)
                : pool.rollsMin + "-" + pool.rollsMax);
    }

    // --- rendering --------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, Math.max(2, uiTop - 12), 0xFFFFFF);

        // Card panel in the same style as the recipe editor's generic panels.
        int right = panelLeft + PANEL_W;
        int bottom = panelTop + panelHeight;
        graphics.fill(panelLeft - 1, panelTop - 1, right + 1, bottom + 1, 0xFF000000);
        graphics.fill(panelLeft, panelTop, right, bottom, 0xFFC6C6C6);
        graphics.fill(panelLeft, panelTop, right, panelTop + 1, 0xFFFFFFFF);
        graphics.fill(panelLeft, panelTop, panelLeft + 1, bottom, 0xFFFFFFFF);
        graphics.fill(panelLeft, bottom - 1, right, bottom, 0xFF555555);
        graphics.fill(right - 1, panelTop, right, bottom, 0xFF555555);

        List<Component> tooltip = null;
        graphics.enableScissor(panelLeft, panelTop, right, bottom);
        if (loading) {
            graphics.drawCenteredString(font, Component.literal("Loading table from the server…"),
                    panelLeft + PANEL_W / 2, panelTop + panelHeight / 2 - 4, 0x404040);
        } else if (pools.isEmpty()) {
            graphics.drawCenteredString(font,
                    Component.literal("No pools — the table drops nothing. Use Add pool."),
                    panelLeft + PANEL_W / 2, panelTop + panelHeight / 2 - 4, 0x404040);
        }
        for (int p = 0; p < pools.size(); p++) {
            PoolState pool = pools.get(p);
            int headerY = poolY(p);
            String label = pool.injected ? "Injected" : "Pool " + (p + 1);
            graphics.drawString(font, label, panelLeft + 6, headerY + 1,
                    pool.injected ? 0x8040A0 : 0x404040, false);
            int rollsX = panelLeft + 6 + font.width(label) + 6;
            boolean rollsHover = rollsAt(mouseX, mouseY) == p;
            graphics.drawString(font, rollsText(pool), rollsX, headerY + 1,
                    pool.injected ? 0x808080
                            : rollsHover ? 0x1F5FBF : pool.rollsLocked ? 0x808080 : 0x2F6F2F, false);
            if (pool.injected) {
                if (!pool.injectedBy.isEmpty()) {
                    graphics.drawString(font, "by " + pool.injectedBy + "?",
                            rollsX + font.width(rollsText(pool)) + 6, headerY + 1, 0x808080, false);
                }
            } else if (pool.isNew) {
                int newX = rollsX + font.width(rollsText(pool)) + 6;
                graphics.drawString(font, "(added)", newX, headerY + 1, 0xB08020, false);
            }
            if (!pool.injected) {
                boolean deleteHover = poolDeleteAt(mouseX, mouseY) == p;
                graphics.drawString(font, "✕", panelLeft + PANEL_W - 13, headerY + 1,
                        deleteHover ? 0xFF3333 : 0x803333, false);
            }
            if (pool.injected && rollsHover) {
                tooltip = List.of(
                        Component.literal("This pool was injected at runtime by another mod"
                                + (pool.injectedBy.isEmpty() ? ""
                                : " — looks like '" + pool.injectedBy + "'")),
                        Component.literal("Read-only here — change it in that mod's own config. "
                                + "Bulk Replace/Remove item DOES cover it.")
                                .withStyle(ChatFormatting.GRAY),
                        Component.literal("#tags from the mod's config are expanded into the "
                                + "individual items shown here.")
                                .withStyle(ChatFormatting.GRAY));
            } else if (rollsHover && !pool.rollsLocked && !pool.injected) {
                tooltip = List.of(
                        Component.literal("How many entries this pool hands out"),
                        Component.literal("Scroll: rolls ±1 (Shift: max only — makes a range)")
                                .withStyle(ChatFormatting.GRAY),
                        Component.literal("Middle-click to type exact values")
                                .withStyle(ChatFormatting.GRAY));
            } else if (rollsHover) {
                tooltip = List.of(Component.literal("This pool uses a rolls formula the editor "
                        + "can't show — preserved as-is (edit in the config)"));
            } else if (!pool.injected && poolDeleteAt(mouseX, mouseY) == p) {
                tooltip = List.of(Component.literal("Delete this pool")
                        .withStyle(ChatFormatting.RED));
            }

            for (int e = 0; e < slotCount(pool); e++) {
                int x = entryX(e);
                int y = entryY(p, e);
                graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFFFFFFFF);
                graphics.fill(x - 1, y - 1, x + 16, y + 16, 0xFF373737);
                graphics.fill(x, y, x + 16, y + 16, 0xFF8B8B8B);
                boolean hover = mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16
                        && inPanel(mouseX, mouseY);
                if (e == pool.entries.size()) {
                    graphics.drawCenteredString(font, "+", x + 8, y + 4, hover ? 0xFFFFFF : 0xD0FFD0);
                    if (hover) {
                        tooltip = List.of(
                                Component.literal("Add an item to this pool"),
                                Component.literal("Click to choose — or drop one anywhere on the "
                                        + "pool from the inventory/EMI").withStyle(ChatFormatting.GRAY),
                                Component.literal("Right-click: add an 'empty' entry — a chance "
                                        + "to drop nothing").withStyle(ChatFormatting.GRAY),
                                Component.literal("Middle-click: add a special entry — "
                                        + "alternatives/group or a nested loot table")
                                        .withStyle(ChatFormatting.GRAY));
                    }
                    continue;
                }
                EntryState entry = pool.entries.get(e);
                renderEntry(graphics, font, entry, x, y);
                if (hover) {
                    graphics.fill(x, y, x + 16, y + 16, 0x66FFFFFF);
                    tooltip = entryTooltip(pool, entry);
                }
            }
        }
        graphics.disableScissor();

        scrollBar.render(graphics, right - 6, panelTop + 2, panelHeight - 4,
                poolContentHeight(), panelHeight, scrollOffset);

        renderInventory(graphics, mouseX, mouseY);

        if (!status.getString().isEmpty()) {
            // Long server answers wrap onto a second line instead of running off-screen.
            List<net.minecraft.util.FormattedCharSequence> lines = font.split(status, Math.max(200, width - 40));
            for (int i = 0; i < Math.min(2, lines.size()); i++) {
                graphics.drawCenteredString(font, lines.get(i), width / 2, statusY + i * 10, 0xFFFFFF);
            }
            if (lines.size() > 2) {
                // keep the rest reachable in chat; the screen only has room for two lines
            }
        }
        if (tooltip != null) {
            graphics.renderComponentTooltip(font, tooltip, mouseX, mouseY);
        }
        if (cursorItem != null) {
            graphics.renderItem(new ItemStack(cursorItem), mouseX - 8, mouseY - 8);
        }
    }

    /** Shared with the composite sub-editor, which draws the same slots. */
    /**
     * The stack shown for an ITEM entry — like the drop will look in a chest:
     * set_components / set_name / set_potion from the entry's functions are
     * applied to the icon (display-only; saving keeps the JSON untouched).
     */
    static ItemStack entryIcon(EntryState entry) {
        if (entry.kind != Kind.ITEM || entry.ref == null) {
            return RecipeEditorScreen.iconFor(entry.ref, false);
        }
        if (entry.iconCache != null && entry.ref.equals(entry.iconCacheRef)) {
            return entry.iconCache;
        }
        ItemStack stack = RecipeEditorScreen.iconFor(entry.ref, false).copy();
        entry.fancy = applyVisualFunctions(stack, entry.source);
        entry.iconCache = stack;
        entry.iconCacheRef = entry.ref;
        return stack;
    }

    /** Applies look-changing loot functions to the stack; true if any did. */
    private static boolean applyVisualFunctions(ItemStack stack, JsonObject source) {
        if (!(source.get("functions") instanceof JsonArray functions)) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return false;
        }
        RegistryOps<JsonElement> ops =
                RegistryOps.create(JsonOps.INSTANCE, minecraft.level.registryAccess());
        boolean fancy = false;
        for (JsonElement element : functions) {
            if (!(element instanceof JsonObject fn)
                    || !(fn.get("function") instanceof JsonPrimitive id && id.isString())) {
                continue;
            }
            try {
                switch (normalizeId(id.getAsString())) {
                    case "minecraft:set_components" -> {
                        if (fn.get("components") instanceof JsonObject components) {
                            var patch = DataComponentPatch.CODEC.parse(ops, components).result();
                            if (patch.isPresent()) {
                                stack.applyComponents(patch.get());
                                fancy = true;
                            }
                        }
                    }
                    case "minecraft:set_name" -> {
                        if (fn.has("name")) {
                            var name = ComponentSerialization.CODEC.parse(ops, fn.get("name")).result();
                            if (name.isPresent()) {
                                boolean itemName = fn.get("target") instanceof JsonPrimitive t
                                        && t.isString() && t.getAsString().equals("item_name");
                                stack.set(itemName ? DataComponents.ITEM_NAME
                                        : DataComponents.CUSTOM_NAME, name.get());
                                fancy = true;
                            }
                        }
                    }
                    case "minecraft:set_potion" -> {
                        if (fn.get("id") instanceof JsonPrimitive potion && potion.isString()) {
                            var holder = BuiltInRegistries.POTION.getHolder(
                                    ResourceLocation.parse(normalizeId(potion.getAsString())));
                            if (holder.isPresent()) {
                                stack.set(DataComponents.POTION_CONTENTS,
                                        new PotionContents(holder.get()));
                                fancy = true;
                            }
                        }
                    }
                    case "minecraft:enchant_randomly", "minecraft:enchant_with_levels",
                         "minecraft:set_enchantments" -> {
                        // Which enchantment is random/complex — at least show
                        // that the drop will be enchanted.
                        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
                        fancy = true;
                    }
                    default -> {
                    }
                }
            } catch (Throwable ignored) {
                // Display-only best effort — a bad component just keeps the plain icon.
            }
        }
        return fancy;
    }

    static void renderEntry(GuiGraphics graphics, Font font, EntryState entry, int x, int y) {
        ItemStack icon = switch (entry.kind) {
            case ITEM -> entryIcon(entry);
            case TAG -> RecipeEditorScreen.iconFor(entry.ref, false);
            case EMPTY -> new ItemStack(Items.STRUCTURE_VOID);
            case COMPOSITE -> new ItemStack(Items.BUNDLE);
            case TABLE_REF -> new ItemStack(Items.CHEST);
            case COMPLEX -> new ItemStack(complexIcon(entry));
        };
        graphics.renderItem(icon, x, y);
        String decoration = null;
        if ((entry.kind == Kind.ITEM || entry.kind == Kind.TAG) && entry.countMax > 1) {
            decoration = entry.countMin == entry.countMax ? String.valueOf(entry.countMax)
                    : entry.countMin + "-" + entry.countMax;
        } else if (entry.kind == Kind.COMPOSITE) {
            decoration = String.valueOf(entry.children.size());
        }
        graphics.renderItemDecorations(font, icon, x, y, decoration == null ? "" : decoration);
        if (entry.weight != 1) {
            graphics.pose().pushPose();
            graphics.pose().translate(x, y, 200);
            graphics.pose().scale(0.5f, 0.5f, 1);
            graphics.drawString(font, "w" + entry.weight, 1, 1, 0xFFFF55, true);
            graphics.pose().popPose();
        }
        if (entry.chance < 100 || entry.chanceLocked) {
            graphics.pose().pushPose();
            graphics.pose().translate(x, y, 200);
            graphics.pose().scale(0.5f, 0.5f, 1);
            String text = entry.chanceLocked ? "?" : entry.chance + "%";
            graphics.drawString(font, text, 32 - font.width(text), 1, 0x55FFFF, true);
            graphics.pose().popPose();
        }
        if (entry.kind == Kind.TAG) {
            // Tag entries cycle their icon through the tag's items — the '#'
            // badge marks that this is a tag, not one specific item.
            graphics.pose().pushPose();
            graphics.pose().translate(x, y, 200);
            graphics.pose().scale(0.5f, 0.5f, 1);
            graphics.drawString(font, "#", 1, 23, 0xFFAA00, true);
            graphics.pose().popPose();
        }
        if (entry.injected) {
            // Purple tint + frame: merged in by another mod, like injected pools.
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 250);
            graphics.fill(x, y, x + 16, y + 16, 0x50A040C0);
            graphics.fill(x - 1, y - 1, x + 17, y, 0xFF8040A0);
            graphics.fill(x - 1, y + 16, x + 17, y + 17, 0xFF8040A0);
            graphics.fill(x - 1, y, x, y + 16, 0xFF8040A0);
            graphics.fill(x + 16, y, x + 17, y + 16, 0xFF8040A0);
            graphics.pose().popPose();
        }
    }

    private static Item complexIcon(EntryState entry) {
        String type = entry.source.get("type") instanceof JsonPrimitive p && p.isString()
                ? normalizeId(p.getAsString()) : "";
        return switch (type) {
            case "minecraft:loot_table" -> Items.CHEST;
            case "minecraft:dynamic" -> Items.ENDER_CHEST;
            default -> Items.COMMAND_BLOCK;
        };
    }

    /** The composite type's short label ("Alternatives" etc.). */
    static String compositeLabel(EntryState entry) {
        String type = entry.source.get("type") instanceof JsonPrimitive p && p.isString()
                ? normalizeId(p.getAsString()) : "";
        return switch (type) {
            case "minecraft:alternatives" -> "Alternatives";
            case "minecraft:sequence" -> "Sequence";
            default -> "Group";
        };
    }

    /** What a composite type does, for tooltips and the sub-editor. */
    static String compositeHelp(EntryState entry) {
        return switch (compositeLabel(entry)) {
            case "Alternatives" -> "the first entry whose conditions match drops";
            case "Sequence" -> "entries drop in order until one's conditions fail";
            default -> "all entries drop together";
        };
    }

    private List<Component> entryTooltip(PoolState pool, EntryState entry) {
        List<Component> lines = new ArrayList<>();
        if (entry.injected && !pool.injected) {
            lines.add(Component.literal("Merged into this pool at runtime by another mod"
                    + (entry.injectedBy.isEmpty() ? "" : " — looks like '" + entry.injectedBy + "'"))
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
            lines.add(Component.literal("Not part of the table's own JSON, so it is read-only here and "
                    + "never saved — change it in that mod's config. Bulk Replace/Remove item DOES cover it.")
                    .withStyle(ChatFormatting.GRAY));
        }
        if (pool.injected) {
            lines.add(Component.literal("Injected at runtime by another mod"
                    + (pool.injectedBy.isEmpty() ? "" : " — looks like '" + pool.injectedBy + "'"))
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
            lines.add(Component.literal("Read-only here — change it in that mod's own config. "
                    + "Bulk Replace/Remove item DOES cover it.").withStyle(ChatFormatting.GRAY));
            if (entry.kind == Kind.ITEM) {
                lines.add(Component.literal("Injecting mods usually expand a config #tag into "
                        + "single items like this one — removing/replacing by item id or by "
                        + "a #tag containing it both catch it.")
                        .withStyle(ChatFormatting.GRAY));
            }
        }
        switch (entry.kind) {
            case ITEM -> {
                lines.add(entryIcon(entry).getHoverName());
                lines.add(Component.literal(entry.ref).withStyle(ChatFormatting.DARK_GRAY));
                if (entry.fancy) {
                    lines.add(Component.literal("Drops with extra data (components/enchantments) "
                            + "— shown like in game, kept exactly as-is on save.")
                            .withStyle(ChatFormatting.GRAY));
                }
            }
            case TAG -> {
                lines.add(Component.literal(entry.ref).withStyle(ChatFormatting.GOLD));
                lines.add(Component.literal("An item TAG — each roll drops one matching item "
                        + "(the icon cycles through them).").withStyle(ChatFormatting.GRAY));
                lines.add(Component.literal("Bulk Replace/Remove must target the #tag itself, "
                        + "not an item inside it.").withStyle(ChatFormatting.GRAY));
            }
            case EMPTY -> lines.add(Component.literal("Nothing (an 'empty' entry — a chance to "
                    + "get no drop). Click to turn it into an item."));
            case COMPOSITE -> {
                lines.add(Component.literal(compositeLabel(entry) + " — "
                        + entry.children.size() + " entr" + (entry.children.size() == 1 ? "y" : "ies")));
                lines.add(Component.literal("Here " + compositeHelp(entry) + ".")
                        .withStyle(ChatFormatting.GRAY));
                lines.add(Component.literal("Click to edit its entries")
                        .withStyle(ChatFormatting.GRAY));
            }
            case TABLE_REF -> {
                lines.add(Component.literal("Drops from another loot table:"));
                lines.add(Component.literal(entry.ref).withStyle(ChatFormatting.DARK_GRAY));
                lines.add(Component.literal("Click to pick a different table")
                        .withStyle(ChatFormatting.GRAY));
            }
            case COMPLEX -> {
                String type = entry.source.get("type") instanceof JsonPrimitive p && p.isString()
                        ? p.getAsString() : "?";
                lines.add(Component.literal("Complex entry: " + type));
                lines.add(Component.literal("The editor can't show this one — it is preserved "
                        + "exactly as-is when saving.").withStyle(ChatFormatting.GRAY));
            }
        }
        int totalWeight = 0;
        for (EntryState other : pool.entries) {
            totalWeight += Math.max(1, other.weight);
        }
        int share = totalWeight == 0 ? 100 : Math.round(entry.weight * 100f / totalWeight);
        lines.add(Component.literal("Weight: " + entry.weight + " (" + share + "% of this pool)")
                .withStyle(ChatFormatting.AQUA));
        if (entry.kind == Kind.ITEM || entry.kind == Kind.TAG) {
            if (entry.countLocked) {
                lines.add(Component.literal("Count: custom formula (preserved)")
                        .withStyle(ChatFormatting.AQUA));
            } else if (entry.countMax > 1) {
                lines.add(Component.literal("Count: " + (entry.countMin == entry.countMax
                        ? String.valueOf(entry.countMin) : entry.countMin + "-" + entry.countMax))
                        .withStyle(ChatFormatting.AQUA));
            }
            if (entry.chanceLocked) {
                lines.add(Component.literal("Chance: custom formula (preserved)")
                        .withStyle(ChatFormatting.AQUA));
            } else if (entry.chance < 100) {
                lines.add(Component.literal("Chance: " + entry.chance + "% (the blue badge) — even "
                        + "when a roll picks this entry, it only drops this often "
                        + "(a random_chance condition)").withStyle(ChatFormatting.AQUA));
            }
        }
        if (!pool.injected) {
            lines.add(Component.literal("Scroll: weight — Shift: count — Ctrl: count max — Alt: chance")
                    .withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal("Middle-click: exact weight"
                    + (entry.kind == Kind.ITEM ? " / convert to a #tag" : "")
                    + " — right-click: delete entry").withStyle(ChatFormatting.GRAY));
        }
        return lines;
    }

    // --- player inventory panel (same as the recipe editor) ---------------

    private int[] invPos(int index) {
        return new int[]{invLeft + (index % INV_COLS) * 18 + 1, invTop + (index / INV_COLS) * 18 + 1};
    }

    private void renderInventory(GuiGraphics graphics, int mouseX, int mouseY) {
        assert minecraft != null && minecraft.player != null;
        List<ItemStack> items = minecraft.player.getInventory().items;
        graphics.drawString(font, Component.literal("Inventory").withStyle(ChatFormatting.GRAY),
                invLeft, invTop - 11, 0xA0A0A0);
        ItemStack hovered = ItemStack.EMPTY;
        for (int i = 0; i < items.size() && i < 36; i++) {
            int[] pos = invPos(i);
            int x = pos[0];
            int y = pos[1];
            graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFFFFFFFF);
            graphics.fill(x - 1, y - 1, x + 16, y + 16, 0xFF373737);
            graphics.fill(x, y, x + 16, y + 16, 0xFF8B8B8B);
            ItemStack stack = items.get(i);
            if (!stack.isEmpty()) {
                graphics.renderItem(stack, x, y);
                graphics.renderItemDecorations(font, stack, x, y);
            }
            if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
                graphics.fill(x, y, x + 16, y + 16, 0x66FFFFFF);
                hovered = stack;
            }
        }
        if (!hovered.isEmpty()) {
            graphics.renderComponentTooltip(font, List.of(
                    hovered.getHoverName(),
                    Component.literal(BuiltInRegistries.ITEM.getKey(hovered.getItem()).toString())
                            .withStyle(ChatFormatting.DARK_GRAY),
                    Component.literal("Click to pick up, then click any slots — right-click drops")
                            .withStyle(ChatFormatting.GRAY)),
                    mouseX, mouseY);
        }
    }

    private int invSlotAt(double mouseX, double mouseY) {
        for (int i = 0; i < 36; i++) {
            int[] pos = invPos(i);
            if (mouseX >= pos[0] && mouseX < pos[0] + 16 && mouseY >= pos[1] && mouseY < pos[1] + 16) {
                return i;
            }
        }
        return -1;
    }

    // --- input -------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 1 && cursorItem != null) {
            cursorItem = null;
            return true;
        }
        if (button == 0) {
            int barValue = scrollBar.mouseClicked(mouseX, mouseY);
            if (barValue >= 0) {
                scrollOffset = barValue;
                return true;
            }
        }
        int invIndex = invSlotAt(mouseX, mouseY);
        if (invIndex >= 0 && button == 0) {
            assert minecraft != null && minecraft.player != null;
            List<ItemStack> items = minecraft.player.getInventory().items;
            if (invIndex < items.size() && !items.get(invIndex).isEmpty()) {
                cursorItem = items.get(invIndex).getItem();
            }
            return true;
        }
        int deletePool = poolDeleteAt(mouseX, mouseY);
        if (deletePool >= 0 && button == 0) {
            boolean wasNew = pools.get(deletePool).isNew;
            pools.remove(deletePool);
            if (!wasNew) {
                dirtyExisting = true;
                status = Component.literal("Pool removed — 'Save table' makes it permanent.")
                        .withStyle(ChatFormatting.YELLOW);
            }
            rebuildWidgets();
            return true;
        }
        int rollsPool = rollsAt(mouseX, mouseY);
        if (rollsPool >= 0 && button == 2 && !pools.get(rollsPool).rollsLocked
                && !pools.get(rollsPool).injected) {
            assert minecraft != null;
            PoolState pool = pools.get(rollsPool);
            // The min dialog closes into the max dialog (AmountInputScreen
            // returns to its parent after confirming).
            AmountInputScreen maxDialog = new AmountInputScreen(this,
                    Component.literal("Rolls (maximum)"), pool.rollsMax, 0, 999, max -> {
                pool.rollsMax = Math.max(pool.rollsMin, max);
                markEdited(pool);
            });
            minecraft.setScreen(new AmountInputScreen(maxDialog,
                    Component.literal("Rolls (minimum)"), pool.rollsMin, 0, 999, min -> {
                pool.rollsMin = min;
                pool.rollsMax = Math.max(pool.rollsMax, min);
                markEdited(pool);
            }));
            return true;
        }
        int[] hit = entryAt(mouseX, mouseY);
        if (hit == null) {
            // With an item on the cursor, anywhere on a pool's card adds it
            // to that pool.
            if (button == 0 && cursorItem != null) {
                int poolIndex = poolAt(mouseX, mouseY);
                if (poolIndex >= 0) {
                    PoolState pool = pools.get(poolIndex);
                    placeItem(pool, pool.entries.size(),
                            BuiltInRegistries.ITEM.getKey(cursorItem).toString());
                    placedOnPress = true;
                    return true;
                }
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }
        PoolState pool = pools.get(hit[0]);
        if (pool.injected) {
            status = Component.literal("This pool is injected by another mod at runtime — "
                    + "read-only here (bulk Replace/Remove item does cover it).")
                    .withStyle(ChatFormatting.LIGHT_PURPLE);
            return true;
        }
        boolean addSlot = hit[1] == pool.entries.size();
        if (!addSlot && pool.entries.get(hit[1]).injected) {
            EntryState injectedEntry = pool.entries.get(hit[1]);
            status = Component.literal("This entry was merged into the pool at runtime by another mod"
                    + (injectedEntry.injectedBy.isEmpty() ? "" : " (looks like '" + injectedEntry.injectedBy + "')")
                    + " — read-only here (bulk Replace/Remove item does cover it).")
                    .withStyle(ChatFormatting.LIGHT_PURPLE);
            return true;
        }
        if (button == 1 && addSlot) {
            // An 'empty' entry: one weighted option of the pool that drops
            // nothing when the roll picks it.
            pool.entries.add(newEmptyEntry());
            markEdited(pool);
            rebuildWidgets();
            return true;
        }
        if (button == 2 && addSlot) {
            assert minecraft != null;
            minecraft.setScreen(new SpecialEntryScreen(this, entry -> {
                pool.entries.add(entry);
                markEdited(pool);
                rebuildWidgets();
                if (entry.kind == Kind.COMPOSITE) {
                    assert minecraft != null;
                    minecraft.setScreen(new CompositeEntryScreen(this, entry, () -> markEdited(pool)));
                }
            }));
            return true;
        }
        if (button == 1 && !addSlot) {
            pool.entries.remove(hit[1]);
            markEdited(pool);
            rebuildWidgets();
            return true;
        }
        if (button == 2 && !addSlot) {
            assert minecraft != null;
            EntryState entry = pool.entries.get(hit[1]);
            if (entry.kind == Kind.ITEM) {
                // Item entries also offer conversion to one of the item's
                // tags ("any planks instead of oak planks").
                minecraft.setScreen(new AmountInputScreen(this, Component.literal("Weight"),
                        entry.weight, 1, 1000, weight -> {
                    entry.weight = weight;
                    markEdited(pool);
                }, Component.literal("Convert to #tag…"), () ->
                        minecraft.setScreen(new ItemTagListScreen(this,
                                RecipeEditorScreen.iconFor(entry.ref, false).getItem(), tag -> {
                            entry.kind = Kind.TAG;
                            entry.ref = tag;
                            markEdited(pool);
                        }))));
            } else {
                minecraft.setScreen(new AmountInputScreen(this, Component.literal("Weight"),
                        entry.weight, 1, 1000, weight -> {
                    entry.weight = weight;
                    markEdited(pool);
                }));
            }
            return true;
        }
        if (button == 0) {
            if (cursorItem != null) {
                placeItem(pool, hit[1], BuiltInRegistries.ITEM.getKey(cursorItem).toString());
                placedOnPress = true;
                return true;
            }
            assert minecraft != null;
            if (addSlot || isItemPlaceable(pool.entries.get(hit[1]))) {
                final int entryIndex = hit[1];
                minecraft.setScreen(new ItemPickerScreen(this, false, true,
                        ref -> placeItem(pool, entryIndex, ref)));
            } else if (pool.entries.get(hit[1]).kind == Kind.COMPOSITE) {
                minecraft.setScreen(new CompositeEntryScreen(this,
                        pool.entries.get(hit[1]), () -> markEdited(pool)));
            } else if (pool.entries.get(hit[1]).kind == Kind.TABLE_REF) {
                EntryState entry = pool.entries.get(hit[1]);
                minecraft.setScreen(new LootTablePickerScreen(this, tableRef -> {
                    entry.ref = tableRef;
                    markEdited(pool);
                }));
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** Entries an item/tag can be placed into; composites and refs are edited by clicking. */
    static boolean isItemPlaceable(EntryState entry) {
        return !entry.injected && (entry.kind == Kind.ITEM || entry.kind == Kind.TAG || entry.kind == Kind.EMPTY);
    }

    /** Sets an entry's item (or adds a new entry when index == size). */
    private void placeItem(PoolState pool, int entryIndex, String ref) {
        if (ref == null || ref.isEmpty()) {
            return;
        }
        if (pool.injected) {
            return;
        }
        EntryState entry;
        if (entryIndex >= pool.entries.size()) {
            entry = new EntryState();
            entry.source = new JsonObject();
            pool.entries.add(entry);
            rebuildWidgets(); // pool may have grown a row
        } else {
            entry = pool.entries.get(entryIndex);
            if (!isItemPlaceable(entry)) {
                return;
            }
        }
        entry.kind = ref.startsWith("#") ? Kind.TAG : Kind.ITEM;
        entry.ref = ref;
        markEdited(pool);
    }

    private void markEdited(PoolState pool) {
        if (!pool.isNew) {
            dirtyExisting = true;
        }
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        scrollBar.mouseReleased();
        if (button == 0 && cursorItem != null) {
            if (placedOnPress) {
                placedOnPress = false;
                return true;
            }
            String ref = BuiltInRegistries.ITEM.getKey(cursorItem).toString();
            int[] hit = entryAt(mouseX, mouseY);
            if (hit != null) {
                placeItem(pools.get(hit[0]), hit[1], ref);
                return true;
            }
            int poolIndex = poolAt(mouseX, mouseY);
            if (poolIndex >= 0) {
                PoolState pool = pools.get(poolIndex);
                placeItem(pool, pool.entries.size(), ref);
                return true;
            }
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        int barValue = scrollBar.mouseDragged(mouseY);
        if (barValue >= 0) {
            scrollOffset = barValue;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int direction = (int) Math.signum(scrollY);
        int rollsPool = rollsAt(mouseX, mouseY);
        if (rollsPool >= 0) {
            PoolState pool = pools.get(rollsPool);
            if (!pool.rollsLocked && !pool.injected) {
                if (hasShiftDown()) {
                    pool.rollsMax = Math.max(pool.rollsMin, Math.min(999, pool.rollsMax + direction));
                } else {
                    pool.rollsMin = Math.max(0, Math.min(999, pool.rollsMin + direction));
                    pool.rollsMax = Math.max(pool.rollsMin, Math.min(999, pool.rollsMax + direction));
                }
                markEdited(pool);
            }
            return true;
        }
        int[] hit = entryAt(mouseX, mouseY);
        if (hit != null && hit[1] < pools.get(hit[0]).entries.size()) {
            PoolState pool = pools.get(hit[0]);
            if (pool.injected) {
                return true; // read-only: swallow the scroll, no edits
            }
            EntryState entry = pool.entries.get(hit[1]);
            if (entry.injected) {
                return true; // read-only: swallow the scroll, no edits
            }
            boolean itemLike = entry.kind == Kind.ITEM || entry.kind == Kind.TAG;
            if (hasAltDown()) {
                if (itemLike && !entry.chanceLocked) {
                    entry.chance = Math.max(1, Math.min(100, entry.chance + direction * 5));
                    markEdited(pool);
                }
            } else if (hasControlDown()) {
                if (itemLike && !entry.countLocked) {
                    entry.countMax = Math.max(entry.countMin, Math.min(99, entry.countMax + direction));
                    markEdited(pool);
                }
            } else if (hasShiftDown()) {
                if (itemLike && !entry.countLocked) {
                    int count = Math.max(1, Math.min(99, entry.countMin + direction));
                    entry.countMin = count;
                    entry.countMax = count;
                    markEdited(pool);
                }
            } else {
                entry.weight = Math.max(1, Math.min(1000, entry.weight + direction));
                markEdited(pool);
            }
            return true;
        }
        if (inPanel(mouseX, mouseY)) {
            int maxScroll = Math.max(0, poolContentHeight() - panelHeight);
            scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - direction * 18));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // --- EMI hooks ----------------------------------------------------------

    /** EMI drag & drop: set an entry, or add to the pool dropped on. */
    public boolean emiDrop(int x, int y, Object key) {
        if (!(key instanceof Item item)) {
            return false;
        }
        String ref = BuiltInRegistries.ITEM.getKey(item).toString();
        int[] hit = entryAt(x, y);
        if (hit != null) {
            PoolState pool = pools.get(hit[0]);
            if (pool.injected
                    || (hit[1] < pool.entries.size() && !isItemPlaceable(pool.entries.get(hit[1])))) {
                return false;
            }
            placeItem(pool, hit[1], ref);
            return true;
        }
        // Anywhere else on a pool's card appends the item to that pool.
        int poolIndex = poolAt(x, y);
        if (poolIndex < 0) {
            return false;
        }
        PoolState pool = pools.get(poolIndex);
        placeItem(pool, pool.entries.size(), ref);
        return true;
    }

    /** EMI drag & drop: highlight the pools and every slot the item could land in. */
    public void renderEmiDropTargets(GuiGraphics graphics, Object key) {
        if (!(key instanceof Item)) {
            return;
        }
        graphics.enableScissor(panelLeft, panelTop, panelLeft + PANEL_W, panelTop + panelHeight);
        for (int p = 0; p < pools.size(); p++) {
            PoolState pool = pools.get(p);
            if (pool.injected) {
                continue; // read-only — not a drop target
            }
            // A thin frame around the whole pool card — dropping anywhere
            // inside adds to this pool.
            int top = poolY(p) - 2;
            int bottom = poolY(p) + poolHeight(pool) - 4;
            int left = panelLeft + 2;
            int right = panelLeft + PANEL_W - 6;
            graphics.fill(left, top, right, top + 1, 0x8833BB33);
            graphics.fill(left, bottom - 1, right, bottom, 0x8833BB33);
            graphics.fill(left, top, left + 1, bottom, 0x8833BB33);
            graphics.fill(right - 1, top, right, bottom, 0x8833BB33);
            for (int e = 0; e <= pool.entries.size(); e++) {
                if (e < pool.entries.size() && !isItemPlaceable(pool.entries.get(e))) {
                    continue;
                }
                graphics.fill(entryX(e), entryY(p, e), entryX(e) + 16, entryY(p, e) + 16, 0x8833BB33);
            }
        }
        graphics.disableScissor();
    }

    /** The whole editor UI as {x, y, width, height} — EMI keeps its panels outside this. */
    public int[] emiScreenBounds() {
        return new int[]{uiLeft, uiTop, uiRight - uiLeft, uiBottom - uiTop};
    }

    // --- container-screen plumbing neutralized (menu is client-only) --------

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    }

    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType type) {
    }

    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int guiLeft, int guiTop, int mouseButton) {
        return false;
    }

    @Override
    public void removed() {
        // Fires on every screen change (sub-screens included) — keep the
        // draft in sync so unsaved edits survive close/reopen. A clean state
        // clears any stale draft (Save went through, or nothing changed).
        if (!loading) {
            boolean dirty = dirtyExisting
                    || pools.stream().anyMatch(pool -> pool.isNew && !pool.injected);
            if (dirty) {
                List<Boolean> newFlags = pools.stream()
                        .filter(pool -> !pool.injected)
                        .map(pool -> pool.isNew)
                        .toList();
                DRAFTS.put(tableId, new Draft(buildTableJson().toString(), newFlags, dirtyExisting));
            } else {
                DRAFTS.remove(tableId);
            }
        }
        super.removed();
    }

    @Override
    public void onClose() {
        assert minecraft != null;
        minecraft.setScreen(null);
    }
}
