package net.ledok.datarewriter.config;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A loot table scope for bulk item rules: a comma-separated list of
 * {@link IdPattern}s where a leading '!' excludes instead of includes.
 * "minecraft:chests/*"            — only chest tables
 * "somemod:*"                     — only one mod's tables
 * "!*:blocks/*, !*:entities/*"    — everything except block and entity drops
 * With no include patterns, every table not excluded matches.
 */
public final class TableScope {
    private final List<IdPattern> includes;
    private final List<IdPattern> excludes;
    private final String source;

    private TableScope(List<IdPattern> includes, List<IdPattern> excludes, String source) {
        this.includes = includes;
        this.excludes = excludes;
        this.source = source;
    }

    /** Returns null when any part is not a valid id or pattern (or the value is blank). */
    public static @Nullable TableScope parse(String value) {
        List<String> parts = new ArrayList<>();
        for (String part : value.split(",")) {
            if (!part.trim().isEmpty()) {
                parts.add(part.trim());
            }
        }
        return parse(parts, value.trim());
    }

    /** Parses pre-split patterns (from a config list). Null when any is invalid or the list is empty. */
    public static @Nullable TableScope parse(List<String> parts, String source) {
        if (parts.isEmpty()) {
            return null;
        }
        List<IdPattern> includes = new ArrayList<>();
        List<IdPattern> excludes = new ArrayList<>();
        for (String part : parts) {
            boolean exclude = part.startsWith("!");
            IdPattern pattern = IdPattern.parse(exclude ? part.substring(1).trim() : part);
            if (pattern == null) {
                return null;
            }
            (exclude ? excludes : includes).add(pattern);
        }
        return new TableScope(includes, excludes, source);
    }

    public boolean matches(ResourceLocation id) {
        for (IdPattern exclude : excludes) {
            if (exclude.matches(id)) {
                return false;
            }
        }
        if (includes.isEmpty()) {
            return true;
        }
        for (IdPattern include : includes) {
            if (include.matches(id)) {
                return true;
            }
        }
        return false;
    }

    /** The scope as the user wrote it — for saving back into config rules. */
    @Override
    public String toString() {
        return source;
    }
}
