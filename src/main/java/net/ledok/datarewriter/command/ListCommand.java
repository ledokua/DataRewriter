package net.ledok.datarewriter.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
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

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * /datarewriter list recipes|loot_tables [mod] [page] — discover ids for
 * config rules in game. Ids are click-to-copy; everything is server-side, so
 * it works from vanilla clients too.
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
                                .then(kindNode(Kind.LOOT_TABLES)))));
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
