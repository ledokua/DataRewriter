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
    /** The pattern as written, for messages. */
    private final String source;

    private IdPattern(@Nullable String literal, @Nullable Pattern regex, String source) {
        this.literal = literal;
        this.regex = regex;
        this.source = source;
    }

    @Override
    public String toString() {
        return literal != null ? literal : source;
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
            return new IdPattern(null, Pattern.compile(regex.toString()), withNamespace);
        }
        ResourceLocation id = ResourceLocation.tryParse(value);
        return id == null ? null : new IdPattern(id.toString(), null, id.toString());
    }

    public boolean matches(ResourceLocation id) {
        return literal != null ? literal.equals(id.toString()) : regex.matcher(id.toString()).matches();
    }
}
