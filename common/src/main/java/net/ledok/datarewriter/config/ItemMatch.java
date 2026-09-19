package net.ledok.datarewriter.config;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * The item side of a bulk loot rule: either an {@link IdPattern} ('*'
 * wildcards) or a "#namespace:tag". A tag matches every item that belongs to
 * it, plus loot entries referencing the identical tag — but tags only bind
 * late in a (re)load, so tag-based rules are applied by the post-load pass
 * rather than the raw-JSON rewrite.
 */
public final class ItemMatch {
    private final @Nullable IdPattern pattern;
    private final @Nullable ResourceLocation tag;
    private final String source;

    private ItemMatch(@Nullable IdPattern pattern, @Nullable ResourceLocation tag, String source) {
        this.pattern = pattern;
        this.tag = tag;
        this.source = source;
    }

    /** Returns null if the value is not a valid id, pattern or #tag. */
    public static @Nullable ItemMatch parse(String value) {
        if (value.startsWith("#")) {
            ResourceLocation tag = ResourceLocation.tryParse(value.substring(1));
            return tag == null ? null : new ItemMatch(null, tag, "#" + tag);
        }
        IdPattern pattern = IdPattern.parse(value);
        return pattern == null ? null : new ItemMatch(pattern, null, value);
    }

    /** Non-null unless {@link #tag()} is set. */
    public @Nullable IdPattern pattern() {
        return pattern;
    }

    public @Nullable ResourceLocation tag() {
        return tag;
    }

    /** The match as the user wrote it (tags keep their '#'). */
    @Override
    public String toString() {
        return source;
    }
}
