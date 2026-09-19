package net.ledok.datarewriter.api;

/**
 * Entry point for addons (Fabric: entrypoint {@code "datarewriter"}; NeoForge: a {@link java.util.ServiceLoader}
 * provider of this interface), both sides. Called during mod initialization, before any config is
 * loaded — contribute rules through {@link RewriteRules#addProvider} and listen for changes through
 * {@link DataRewriterEvents} here:
 *
 * <pre>{@code
 * public class MyAddon implements DataRewriterAddon {
 *     public void register() {
 *         RewriteRules.addProvider("mymod", rules -> rules
 *                 .removeRecipes("minecraft:*_from_smelting")
 *                 .replaceIngredients("minecraft:iron_ingot", "#c:ingots/iron"));
 *     }
 * }
 * }</pre>
 *
 * See {@code API.md} in the repository for the complete surface.
 */
public interface DataRewriterAddon {
    void register();
}
