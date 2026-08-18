package net.ledok.datarewriter.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.ledok.datarewriter.Datarewriter;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.ledok.datarewriter.LootLiveApplier;
import net.ledok.datarewriter.RewriteState;
import net.ledok.datarewriter.config.ConfigLoader;
import net.ledok.datarewriter.config.RewriteConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * /datarewriter list recipes|loot_tables [mod] [page] — discover ids for
 * config rules in game. Ids are click-to-copy; everything is server-side, so
 * it works from vanilla clients too.
 * /datarewriter errors — the last config load's errors and warnings, in chat
 * and the server log.
 */
public final class ListCommand {
    private static final int PAGE_SIZE = 30;

    private enum Kind {
        RECIPES("recipes"),
        LOOT_TABLES("loot_tables");

        final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    private ListCommand() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("datarewriter")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("list")
                                .then(kindNode(Kind.RECIPES))
                                .then(kindNode(Kind.LOOT_TABLES)))
                        .then(Commands.literal("errors")
                                .executes(ctx -> showErrors(ctx.getSource())))
                        .then(Commands.literal("status")
                                .executes(ctx -> showStatus(ctx.getSource())))));
    }

    /**
     * /datarewriter status — which config files the last load read (and from
     * where), and whether each loot 'add'/'modify' entry is actually in effect
     * right now: loaded into DataRewriter's table cache and present in the
     * live registry. Meant for "my edit is gone after a restart" reports.
     */
    private static int showStatus(CommandSourceStack source) {
        RewriteConfig config = RewriteState.config();
        List<String> lines = new ArrayList<>();
        lines.add("config dir: " + ConfigLoader.directory());
        if (ConfigLoader.files().isEmpty()) {
            lines.add("no config files loaded");
        }
        for (ConfigLoader.FileSummary file : ConfigLoader.files()) {
            lines.add("file " + file);
        }
        MinecraftServer server = source.getServer();
        Registry<LootTable> registry = server.reloadableRegistries().get().registryOrThrow(Registries.LOOT_TABLE);
        for (RewriteConfig.AddedLootTable addition : config.lootAdditions()) {
            int configPools = poolCount(addition.json());
            JsonElement cached = RewriteState.lootTableJsons.get(addition.id());
            Optional<Holder.Reference<LootTable>> live = registry.getHolder(
                    ResourceKey.create(Registries.LOOT_TABLE, addition.id()));
            JsonObject liveJson = live.map(h -> LootLiveApplier.encode(server, h.value())).orElse(null);
            String state;
            if (cached == null) {
                state = "NOT LOADED into the table cache";
            } else if (poolCount(cached) < configPools) {
                state = "loaded but the cache has only " + poolCount(cached) + " pools";
            } else if (live.isEmpty()) {
                state = "loaded, but no live loot table with this id exists";
            } else if (liveJson == null) {
                state = "loaded; live table can't be encoded (mod entries)";
            } else if (poolCount(liveJson) < configPools) {
                state = "loaded, but the LIVE table has only " + poolCount(liveJson)
                        + " pools — another mod replaced it after DataRewriter";
            } else {
                state = "in effect (live: " + poolCount(liveJson) + " pools"
                        + (poolCount(liveJson) > configPools
                        ? ", " + (poolCount(liveJson) - configPools) + " injected by other mods)" : ")");
            }
            lines.add("loot add " + addition.id() + " (from " + addition.source() + ") — config: "
                    + configPools + " pools — " + state);
        }
        for (RewriteConfig.LootModification modification : config.lootModifications()) {
            RewriteConfig.LootRule target = modification.target();
            lines.add("loot modify " + (target.id() != null ? target.id().toString() : "mod " + target.mod())
                    + " — " + modification.pools().size() + " pools appended (from " + target.source() + ")");
        }
        lines.add("recipes: " + config.removals().size() + " removal rules, " + config.additions().size()
                + " added, " + config.ingredientReplacements().size() + " ingredient replacements; loot: "
                + config.lootRemovals().size() + " removals, " + config.lootItemReplacements().size()
                + " item replacements, " + config.lootItemRemovals().size() + " item removals; "
                + config.errorCount() + " config errors");
        Datarewriter.LOGGER.info("Status:");
        for (String line : lines) {
            Datarewriter.LOGGER.info("  {}", line);
        }
        source.sendSuccess(() -> Component.literal("[DataRewriter] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal("status (also in the server log):").withStyle(ChatFormatting.GRAY)), false);
        for (String line : lines) {
            ChatFormatting color = line.contains("NOT LOADED") || line.contains("but ") ? ChatFormatting.RED
                    : line.contains("in effect") ? ChatFormatting.GREEN : ChatFormatting.GRAY;
            source.sendSuccess(() -> Component.literal(line).withStyle(color), false);
        }
        return lines.size();
    }

    private static int poolCount(JsonElement table) {
        return table instanceof JsonObject obj && obj.get("pools") instanceof JsonArray pools ? pools.size() : 0;
    }

    /** How many issue lines go to chat before pointing at the log instead. */
    private static final int MAX_CHAT_ISSUES = 20;

    /**
     * /datarewriter errors — the errors and warnings of the last config load,
     * in chat and (again, together) in the server log.
     */
    private static int showErrors(CommandSourceStack source) {
        List<String> issues = ConfigLoader.issues();
        if (issues.isEmpty()) {
            source.sendSuccess(() -> Component.literal("[DataRewriter] ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal("No errors or warnings in the last config load.")
                            .withStyle(ChatFormatting.GREEN)), false);
            return 0;
        }

        Datarewriter.LOGGER.info("{} config issue(s) in the last load:", issues.size());
        for (String issue : issues) {
            Datarewriter.LOGGER.info("  {}", issue);
        }

        source.sendSuccess(() -> Component.literal("[DataRewriter] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(issues.size() + " config issue(s) in the last load"
                                + " (also printed to the server log; /reload re-checks):")
                        .withStyle(ChatFormatting.GRAY)), false);
        for (int i = 0; i < issues.size() && i < MAX_CHAT_ISSUES; i++) {
            String issue = issues.get(i);
            ChatFormatting color = issue.startsWith("ERROR") ? ChatFormatting.RED : ChatFormatting.YELLOW;
            source.sendSuccess(() -> Component.literal(issue).withStyle(color), false);
        }
        if (issues.size() > MAX_CHAT_ISSUES) {
            source.sendSuccess(() -> Component.literal("…and " + (issues.size() - MAX_CHAT_ISSUES)
                    + " more — see the server log.").withStyle(ChatFormatting.GRAY), false);
        }
        return issues.size();
    }

    private static LiteralArgumentBuilder<CommandSourceStack> kindNode(Kind kind) {
        return Commands.literal(kind.label)
                .executes(ctx -> listNamespaces(ctx.getSource(), kind))
                .then(Commands.argument("mod", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                ids(ctx.getSource().getServer(), kind)
                                        .map(ResourceLocation::getNamespace)
                                        .distinct().sorted().toList(),
                                builder))
                        .executes(ctx -> listIds(ctx.getSource(), kind,
                                StringArgumentType.getString(ctx, "mod"), 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> listIds(ctx.getSource(), kind,
                                        StringArgumentType.getString(ctx, "mod"),
                                        IntegerArgumentType.getInteger(ctx, "page")))));
    }

    private static Stream<ResourceLocation> ids(MinecraftServer server, Kind kind) {
        return switch (kind) {
            case RECIPES -> server.getRecipeManager().getRecipeIds();
            case LOOT_TABLES -> server.reloadableRegistries().getKeys(Registries.LOOT_TABLE).stream();
        };
    }

    private static int listNamespaces(CommandSourceStack source, Kind kind) {
        Map<String, Long> counts = ids(source.getServer(), kind)
                .collect(Collectors.groupingBy(ResourceLocation::getNamespace, TreeMap::new, Collectors.counting()));
        long total = counts.values().stream().mapToLong(Long::longValue).sum();

        MutableComponent body = Component.empty();
        boolean first = true;
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            if (!first) {
                body.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY));
            }
            first = false;
            String command = "/datarewriter list " + kind.label + " " + entry.getKey();
            body.append(Component.literal(entry.getKey() + " (" + entry.getValue() + ")")
                    .withStyle(style -> style.withColor(ChatFormatting.AQUA)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.literal("Click to list: " + command)))));
        }

        source.sendSuccess(() -> Component.literal("[DataRewriter] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(total + " " + kind.label + " in " + counts.size()
                        + " mods (click one to list its ids):").withStyle(ChatFormatting.GRAY)), false);
        source.sendSuccess(() -> body, false);
        return (int) total;
    }

    private static int listIds(CommandSourceStack source, Kind kind, String mod, int page) {
        List<ResourceLocation> all = ids(source.getServer(), kind)
                .filter(id -> id.getNamespace().equals(mod))
                .sorted()
                .toList();
        if (all.isEmpty()) {
            source.sendFailure(Component.literal("No " + kind.label + " found for mod '" + mod + "'"));
            return 0;
        }

        int pages = (all.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int current = Math.min(page, pages);
        List<ResourceLocation> shown = all.subList((current - 1) * PAGE_SIZE,
                Math.min(current * PAGE_SIZE, all.size()));

        MutableComponent header = Component.literal("[DataRewriter] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(kind.label + " in '" + mod + "' — " + all.size()
                        + ", page " + current + "/" + pages).withStyle(ChatFormatting.GRAY));
        if (current > 1) {
            header.append(nav("◀ prev", "/datarewriter list " + kind.label + " " + mod + " " + (current - 1)));
        }
        if (current < pages) {
            header.append(nav("next ▶", "/datarewriter list " + kind.label + " " + mod + " " + (current + 1)));
        }

        MutableComponent body = Component.empty();
        boolean first = true;
        for (ResourceLocation id : shown) {
            if (!first) {
                body.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY));
            }
            first = false;
            body.append(Component.literal(id.getPath())
                    .withStyle(style -> style.withColor(ChatFormatting.WHITE)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, id.toString()))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.literal(id + "\nClick to copy")))));
        }

        source.sendSuccess(() -> header, false);
        source.sendSuccess(() -> body, false);
        return shown.size();
    }

    private static Component nav(String label, String command) {
        return Component.literal(" [" + label + "]")
                .withStyle(style -> style.withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal(command))));
    }
}
