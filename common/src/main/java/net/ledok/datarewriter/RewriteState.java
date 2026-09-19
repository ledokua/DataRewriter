package net.ledok.datarewriter;

import com.google.gson.JsonElement;
import net.ledok.datarewriter.config.ConfigLoader;
import net.ledok.datarewriter.config.GuiLootSaver;
import net.ledok.datarewriter.config.GuiRecipeSaver;
import net.ledok.datarewriter.config.GuiRegistrySaver;
import net.ledok.datarewriter.config.RewriteConfig;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Config and per-reload stats shared between the loot phase (runs first,
 * loads the config) and the recipe phases.
 */
public final class RewriteState {
    private static volatile RewriteConfig config = RewriteConfig.EMPTY;
    private static volatile boolean loadedOnce;

    public static volatile int recipesRemovedJson;
    public static volatile int recipesRemovedParsed;
    public static volatile int recipesAdded;
    public static volatile int recipesReplaced;
    public static volatile int lootRemoved;
    public static volatile int lootAdded;
    public static volatile int lootReplaced;
    public static volatile int lootModified;
    public static volatile int lootItemsReplaced;
    public static volatile int lootItemsRemoved;

    /**
     * The final loot table JSON of the last (re)load, after all rewrites —
     * what vanilla parsed. The loot editor reads tables from here and its
     * saves update it, so it always mirrors the live tables.
     */
    public static volatile Map<ResourceLocation, JsonElement> lootTableJsons = new HashMap<>();

    /**
     * Lazy cache of LIVE tables encoded back to JSON — the datapack level
     * plus everything other mods injected at runtime. Filled on demand by the
     * editor's searches, cleared on reload, invalidated per table on rebinds.
     */
    public static volatile Map<ResourceLocation, JsonElement> runtimeTableJsons = new ConcurrentHashMap<>();

    /**
     * The final recipe JSON of the last (re)load, after removals/additions —
     * what vanilla parsed. The ingredient-replacement pass rewrites entries
     * here and swaps the re-parsed recipes into the live RecipeManager.
     */
    public static volatile Map<ResourceLocation, JsonElement> recipeJsons = new ConcurrentHashMap<>();

    private RewriteState() {
    }

    /** Called by the earliest hook of a (re)load — the loot table scan. */
    public static RewriteConfig reloadConfig() {
        externallyChangedFiles = new ArrayList<>();
        if (GuiLootSaver.changedSinceLastSave()) {
            externallyChangedFiles.add(GuiLootSaver.FILE_NAME);
        }
        if (GuiRecipeSaver.changedSinceLastSave()) {
            externallyChangedFiles.add(GuiRecipeSaver.FILE_NAME);
        }
        if (GuiRegistrySaver.changedSinceLastSave()) {
            externallyChangedFiles.add(GuiRegistrySaver.FILE_NAME);
        }
        config = ConfigLoader.load();
        loadedOnce = true;
        return config;
    }

    /**
     * Config 'add' loot tables that are missing from the live registry after a
     * (re)load — the game rejected their JSON (typically an item from a mod
     * that isn't installed here), so they now drop nothing. Logged as WARN.
     */
    public static List<String> missingConfigTables(MinecraftServer server) {
        List<String> missing = new ArrayList<>();
        var registry = server.reloadableRegistries().get()
                .registryOrThrow(net.minecraft.core.registries.Registries.LOOT_TABLE);
        for (RewriteConfig.AddedLootTable addition : config().lootAdditions()) {
            if (!registry.containsKey(addition.id())) {
                missing.add(addition.id().toString());
            }
        }
        if (!missing.isEmpty()) {
            Datarewriter.LOGGER.warn("{} loot table(s) from the config did not load — the game rejected the JSON "
                    + "(look for 'Couldn't parse element' above; usually an item from a mod that isn't installed "
                    + "on this server): {}", missing.size(), String.join(", ", missing));
        }
        return missing;
    }

    /** GUI config files found overwritten by something else at the last reload (see announce). */
    private static volatile List<String> externallyChangedFiles = new ArrayList<>();

    /**
     * Current config. The loot scan runs before recipes in vanilla's reload
     * pipeline, so this is fresh by the time recipes load; the fallback only
     * guards against that hook never having fired at all.
     */
    public static RewriteConfig config() {
        if (!loadedOnce) {
            return reloadConfig();
        }
        return config;
    }

    /**
     * Mirrors a bulk rule the GUI just wrote to gui-loot-tables.json5 into
     * the in-memory config, so live applies see it before the next reload.
     */
    public static synchronized void appendLootItemReplacement(RewriteConfig.LootItemReplacement rule) {
        RewriteConfig old = config();
        List<RewriteConfig.LootItemReplacement> rules = new ArrayList<>(old.lootItemReplacements());
        rules.add(rule);
        config = new RewriteConfig(old.removals(), old.additions(), old.ingredientReplacements(),
                old.lootRemovals(), old.lootAdditions(), old.lootModifications(), List.copyOf(rules),
                old.lootItemRemovals(), old.registryRemovals(), old.registryAdditions(),
                old.registryModifications(), old.errorCount());
    }

    /** Same as {@link #appendLootItemReplacement} for remove_items rules. */
    public static synchronized void appendLootItemRemoval(RewriteConfig.LootItemRemoval rule) {
        RewriteConfig old = config();
        List<RewriteConfig.LootItemRemoval> rules = new ArrayList<>(old.lootItemRemovals());
        rules.add(rule);
        config = new RewriteConfig(old.removals(), old.additions(), old.ingredientReplacements(),
                old.lootRemovals(), old.lootAdditions(), old.lootModifications(),
                old.lootItemReplacements(), List.copyOf(rules), old.registryRemovals(),
                old.registryAdditions(), old.registryModifications(), old.errorCount());
    }

    /**
     * Mirrors a registry entry the GUI just wrote to gui-registries.json5 into the in-memory config,
     * so it survives an in-session world reload without re-reading the file. Replaces an earlier
     * mirror for the same registry + id.
     */
    public static synchronized void appendRegistryAddition(RewriteConfig.RegistryAddition rule) {
        RewriteConfig old = config();
        List<RewriteConfig.RegistryAddition> rules = new ArrayList<>(old.registryAdditions());
        rules.removeIf(existing -> existing.registry().equals(rule.registry()) && existing.id().equals(rule.id()));
        rules.add(rule);
        config = new RewriteConfig(old.removals(), old.additions(), old.ingredientReplacements(),
                old.lootRemovals(), old.lootAdditions(), old.lootModifications(),
                old.lootItemReplacements(), old.lootItemRemovals(), old.registryRemovals(),
                List.copyOf(rules), old.registryModifications(), old.errorCount());
    }

    /** Same mirroring for a recipe removal rule the GUI just saved. */
    public static synchronized void appendRemovalRule(RewriteConfig.RemovalRule rule) {
        RewriteConfig old = config();
        List<RewriteConfig.RemovalRule> rules = new ArrayList<>(old.removals());
        rules.add(rule);
        config = new RewriteConfig(List.copyOf(rules), old.additions(), old.ingredientReplacements(),
                old.lootRemovals(), old.lootAdditions(), old.lootModifications(),
                old.lootItemReplacements(), old.lootItemRemovals(), old.registryRemovals(), old.registryAdditions(),
                old.registryModifications(), old.errorCount());
    }

    /** Same mirroring for a replace_ingredients rule the GUI just saved. */
    public static synchronized void appendIngredientReplacement(RewriteConfig.IngredientReplacement rule) {
        RewriteConfig old = config();
        List<RewriteConfig.IngredientReplacement> rules = new ArrayList<>(old.ingredientReplacements());
        rules.add(rule);
        config = new RewriteConfig(old.removals(), old.additions(), List.copyOf(rules),
                old.lootRemovals(), old.lootAdditions(), old.lootModifications(),
                old.lootItemReplacements(), old.lootItemRemovals(), old.registryRemovals(), old.registryAdditions(),
                old.registryModifications(), old.errorCount());
    }

    /**
     * Sends a summary of what changed to online operators. Called after
     * /reload; on server start there is nobody to tell, the log covers it.
     */
    public static void announce(MinecraftServer server) {
        RewriteConfig loaded = config;
        boolean recipesInUse = !loaded.removals().isEmpty() || !loaded.additions().isEmpty()
                || !loaded.ingredientReplacements().isEmpty();
        boolean lootInUse = !loaded.lootRemovals().isEmpty() || !loaded.lootAdditions().isEmpty()
                || !loaded.lootModifications().isEmpty()
                || !loaded.lootItemReplacements().isEmpty() || !loaded.lootItemRemovals().isEmpty();
        boolean registriesInUse = !loaded.registryRemovals().isEmpty()
                || !loaded.registryAdditions().isEmpty() || !loaded.registryModifications().isEmpty();
        if (!recipesInUse && !lootInUse && !registriesInUse && loaded.errorCount() == 0
                && externallyChangedFiles.isEmpty()) {
            return; // mod not in use, stay quiet
        }

        StringBuilder text = new StringBuilder();
        if (recipesInUse) {
            text.append(String.format("recipes: %d removed, %d added%s",
                    recipesRemovedJson + recipesRemovedParsed, recipesAdded,
                    recipesReplaced > 0 ? " (" + recipesReplaced + " replacing)" : ""));
        }
        if (lootInUse) {
            if (!text.isEmpty()) {
                text.append("; ");
            }
            List<String> parts = new ArrayList<>();
            if (!loaded.lootRemovals().isEmpty()) {
                parts.add(lootRemoved + " removed");
            }
            if (!loaded.lootAdditions().isEmpty()) {
                parts.add(lootAdded + " added" + (lootReplaced > 0 ? " (" + lootReplaced + " replacing)" : ""));
            }
            if (!loaded.lootModifications().isEmpty()) {
                parts.add(lootModified + " modified");
            }
            if (!loaded.lootItemReplacements().isEmpty()) {
                parts.add(lootItemsReplaced + " item entries replaced");
            }
            if (!loaded.lootItemRemovals().isEmpty()) {
                parts.add(lootItemsRemoved + " item entries removed");
            }
            text.append("loot tables: ").append(String.join(", ", parts));
        }
        if (registriesInUse) {
            if (!text.isEmpty()) {
                text.append("; ");
            }
            text.append("registry entries (applied at world load, not /reload): ")
                    .append(RegistryRewriter.statsSummary());
        }
        if (text.isEmpty()) {
            text.append("no changes");
        }

        MutableComponent message = Component.literal("[DataRewriter] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(text.toString()).withStyle(ChatFormatting.GRAY));
        if (loaded.errorCount() > 0) {
            message.append(Component.literal(" — " + loaded.errorCount() + " config error(s), see log")
                    .withStyle(ChatFormatting.RED));
        }
        List<String> missing = missingConfigTables(server);
        if (!missing.isEmpty()) {
            message.append(Component.literal(" — WARNING: " + missing.size() + " config loot table(s) were rejected "
                    + "by the game and drop nothing now: " + String.join(", ", missing)
                    + " (see 'Couldn't parse element' in the log)").withStyle(ChatFormatting.RED));
        }
        if (!externallyChangedFiles.isEmpty()) {
            message.append(Component.literal(" — WARNING: " + String.join(", ", externallyChangedFiles)
                    + " was overwritten outside the game since the last in-game save (panel file editor / "
                    + "modpack sync?) — in-game edits made before that may be lost").withStyle(ChatFormatting.RED));
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.hasPermissions(2)) {
                player.sendSystemMessage(message);
            }
        }
    }
}
