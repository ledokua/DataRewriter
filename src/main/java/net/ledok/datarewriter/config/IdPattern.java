package net.ledok.datarewriter.config;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;

/**
 * An id match: either an exact ResourceLocation or a pattern with '*'
 * wildcards ("minecraft:chests/*"). A missing namespace defaults to
 * "minecraft:" either way.
 */
public final class IdPattern {
    private final @Nullable String literal;
    private final @Nullable Pattern regex;

    private IdPattern(@Nullable String literal, @Nullable Pattern regex) {
        this.literal = literal;
        this.regex = regex;
    }

    /** Returns null if the value is not a valid id or pattern. */
    public static @Nullable IdPattern parse(String value) {
        if (value.contains("*")) {
            String withNamespace = value.contains(":") ? value : "minecraft:" + value;
            StringBuilder regex = new StringBuilder();
            String[] parts = withNamespace.split("\\*", -1);
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) {
                    regex.append(".*");
                }
                if (!parts[i].isEmpty()) {
                    regex.append(Pattern.quote(parts[i]));
                }
            }
            return new IdPattern(null, Pattern.compile(regex.toString()));
        }
        ResourceLocation id = ResourceLocation.tryParse(value);
        return id == null ? null : new IdPattern(id.toString(), null);
    }

    public boolean matches(ResourceLocation id) {
        return literal != null ? literal.equals(id.toString()) : regex.matcher(id.toString()).matches();
    }
}
