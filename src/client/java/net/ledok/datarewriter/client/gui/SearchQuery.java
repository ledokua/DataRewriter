package net.ledok.datarewriter.client.gui;

import java.util.Locale;

/**
 * EMI-style search matching: the query is split on spaces and every term
 * must match. A term starting with '@' matches the id's namespace (the mod),
 * any other term matches anywhere in the id or the display name — so
 * "@minecraft end" finds minecraft's ids containing "end".
 */
final class SearchQuery {
    private SearchQuery() {
    }

    /** True when every term of the query matches the id. */
    static boolean matches(String query, String id) {
        return matches(query, id, "");
    }

    /** True when every term of the query matches the id or the name. */
    static boolean matches(String query, String id, String name) {
        String lowerId = id.toLowerCase(Locale.ROOT);
        int colon = lowerId.indexOf(':');
        String namespace = colon < 0 ? "" : lowerId.substring(0, colon);
        String lowerName = name.toLowerCase(Locale.ROOT);
        for (String term : query.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (term.isEmpty() || term.equals("@")) {
                continue;
            }
            if (term.startsWith("@")) {
                if (!namespace.contains(term.substring(1))) {
                    return false;
                }
            } else if (!lowerId.contains(term) && !lowerName.contains(term)) {
                return false;
            }
        }
        return true;
    }
}
