package net.ledok.datarewriter.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The recipes this client can look up by id. Since 1.21.2 servers send recipe contents only on request:
 * DataRewriter's server sends all of them to operators (RecipeRewriter#sendToEditor), and other mods may
 * request some types; every batch arrives through NeoForge's RecipesReceivedEvent. In singleplayer the
 * integrated server's own list is read directly.
 */
public final class ClientRecipes {
    private static volatile Map<ResourceKey<Recipe<?>>, RecipeHolder<?>> received = Map.of();

    private ClientRecipes() {
    }

    /** A batch for these types: it replaces what was known for exactly those types. */
    public static synchronized void receive(Set<RecipeType<?>> types, RecipeMap recipes) {
        Map<ResourceKey<Recipe<?>>, RecipeHolder<?>> next = new LinkedHashMap<>();
        for (RecipeHolder<?> holder : received.values()) {
            if (!types.contains(holder.value().getType())) {
                next.put(holder.id(), holder);
            }
        }
        for (RecipeHolder<?> holder : recipes.values()) {
            next.put(holder.id(), holder);
        }
        received = next;
    }

    /** Disconnected: the next server sends its own. */
    public static synchronized void clear() {
        received = Map.of();
    }

    public static Collection<RecipeHolder<?>> all() {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        return server != null ? server.getRecipeManager().getRecipes() : received.values();
    }

    public static Optional<RecipeHolder<?>> byId(Identifier id) {
        ResourceKey<Recipe<?>> key = ResourceKey.create(Registries.RECIPE, id);
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        return server != null ? server.getRecipeManager().byKey(key) : Optional.ofNullable(received.get(key));
    }
}
