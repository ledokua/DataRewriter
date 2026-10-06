package net.ledok.datarewriter;

import net.ledok.datarewriter.config.RewriteConfig;
import net.ledok.datarewriter.mixin.CreativeModeTabsAccessor;
import net.ledok.datarewriter.network.DisabledItemsPayload;
import net.ledok.datarewriter.platform.Network;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The resolved {@code items.disable} rules: which items are out of the game and what replaces them. The
 * patterns are resolved once per config load into an identity map, so every runtime check (each slot
 * change, item entity spawn, trade screen) is a plain lookup.
 *
 * <p>Server side this covers what the derived recipe/loot rules can't: recipes MAKING the item (see
 * {@code RecipeRewriter}), item tags (TagLoaderMixin), trades (AbstractVillagerMixin) and existing stacks
 * (container menus, item entities). Client side, players with the mod receive the list and hide the items
 * from EMI and the creative tabs; vanilla clients simply never get one.
 */
public final class DisabledItems {
    /** Disabled item → replacement; {@link Items#AIR} = no replacement, the stack goes. */
    private static volatile Map<Item, Item> server = Map.of();
    /** Disabled items whose recipes are redirected to the replacement instead of removed. */
    private static volatile Set<Item> redirected = Set.of();
    /** What this client's current server disabled (by id, for EMI and the creative tabs). */
    private static volatile Set<Item> client = Set.of();

    private DisabledItems() {
    }

    /** Called right after every config load (server start and {@code /reload}). */
    static void resolve(RewriteConfig config) {
        if (config.disabledItems().isEmpty()) {
            server = Map.of();
            redirected = Set.of();
            return;
        }
        Map<Item, Item> resolved = new IdentityHashMap<>();
        Set<Item> redirect = new HashSet<>();
        for (Map.Entry<ResourceKey<Item>, Item> entry : BuiltInRegistries.ITEM.entrySet()) {
            ResourceLocation id = entry.getKey().location();
            if (entry.getValue() == Items.AIR) {
                continue;
            }
            // The first matching rule wins, like the derived bulk rules apply in order.
            for (RewriteConfig.DisabledItem rule : config.disabledItems()) {
                if (rule.matches(id)) {
                    Item with = rule.replaceWith() == null ? Items.AIR : BuiltInRegistries.ITEM.get(rule.replaceWith());
                    resolved.put(entry.getValue(), with);
                    if (rule.redirectRecipes()) {
                        redirect.add(entry.getValue());
                    }
                    break;
                }
            }
        }
        server = resolved;
        redirected = Set.copyOf(redirect);
        Datarewriter.LOGGER.info("Disabled {} items ({} with a replacement)", resolved.size(),
                resolved.values().stream().filter(item -> item != Items.AIR).count());
    }

    public static boolean active() {
        return !server.isEmpty();
    }

    public static boolean isDisabled(Item item) {
        return server.containsKey(item);
    }

    /** The replacement of a disabled item; null for an item that is not disabled, AIR for "none". */
    public static @Nullable Item replacement(Item item) {
        return server.get(item);
    }

    /** Recipes making this (disabled) item should make its replacement instead of going. */
    public static boolean redirectsRecipes(Item item) {
        return redirected.contains(item);
    }

    public static int count() {
        return server.size();
    }

    /**
     * The stack as it should be: the same instance when its item is not disabled, otherwise the
     * replacement with the same count and components, or {@link ItemStack#EMPTY}.
     */
    public static ItemStack convert(ItemStack stack) {
        if (stack.isEmpty()) {
            return stack;
        }
        Item with = server.get(stack.getItem());
        if (with == null) {
            return stack;
        }
        return with == Items.AIR ? ItemStack.EMPTY : stack.transmuteCopy(with, stack.getCount());
    }

    /**
     * Fixes a merchant's offer list in place: trades giving or asking for a disabled item get the
     * replacement, or are dropped when there is none. Returns how many offers changed.
     */
    public static int sanitize(MerchantOffers offers) {
        if (server.isEmpty()) {
            return 0;
        }
        int changed = 0;
        for (int i = offers.size() - 1; i >= 0; i--) {
            MerchantOffer offer = offers.get(i);
            ItemCost costA = convert(offer.getItemCostA());
            ItemCost oldB = offer.getItemCostB().orElse(null);
            ItemCost costB = oldB == null ? null : convert(oldB);
            ItemStack result = convert(offer.getResult());
            if (costA == offer.getItemCostA() && costB == oldB && result == offer.getResult()) {
                continue;
            }
            changed++;
            if (costA == null || (oldB != null && costB == null) || result.isEmpty()) {
                offers.remove(i);
                continue;
            }
            offers.set(i, new MerchantOffer(costA, Optional.ofNullable(costB), result, offer.getUses(),
                    offer.getMaxUses(), offer.getXp(), offer.getPriceMultiplier(), offer.getDemand()));
        }
        return changed;
    }

    /** Same item → the same instance; replaced → a new cost; no replacement → null. */
    private static @Nullable ItemCost convert(ItemCost cost) {
        Item with = server.get(cost.item().value());
        if (with == null) {
            return cost;
        }
        if (with == Items.AIR) {
            return null;
        }
        Holder<Item> holder = with.builtInRegistryHolder();
        return new ItemCost(holder, cost.count(), cost.components());
    }

    /** The current list for one player; skipped for clients without the mod. */
    public static void sync(ServerPlayer player) {
        if (Network.INSTANCE.canSendToPlayer(player, DisabledItemsPayload.TYPE)) {
            Network.INSTANCE.sendToPlayer(player, payload());
        }
    }

    public static void syncAll(Collection<ServerPlayer> players) {
        DisabledItemsPayload payload = payload();
        for (ServerPlayer player : players) {
            if (Network.INSTANCE.canSendToPlayer(player, DisabledItemsPayload.TYPE)) {
                Network.INSTANCE.sendToPlayer(player, payload);
            }
        }
    }

    private static DisabledItemsPayload payload() {
        List<ResourceLocation> ids = new ArrayList<>(server.size());
        for (Item item : server.keySet()) {
            ids.add(BuiltInRegistries.ITEM.getKey(item));
        }
        return new DisabledItemsPayload(ids);
    }

    // ---- client ----

    /**
     * The server's list arrived (it is sent ahead of the recipes on join and ahead of the tags on
     * {@code /reload}, so recipe viewers reload with it already in place). A changed list also drops the
     * creative tabs' cache, so they rebuild without the items the next time the inventory opens.
     */
    public static void receive(List<ResourceLocation> ids) {
        Set<Item> items = new HashSet<>();
        for (ResourceLocation id : ids) {
            BuiltInRegistries.ITEM.getOptional(id).ifPresent(items::add);
        }
        Set<Item> next = Set.copyOf(items);
        if (!next.equals(client)) {
            client = next;
            CreativeModeTabsAccessor.datarewriter$setCachedParameters(null);
        }
    }

    /** A new connection: forget the previous server's list (one without the mod never sends any). */
    public static void clearClient() {
        if (!client.isEmpty()) {
            client = Set.of();
            CreativeModeTabsAccessor.datarewriter$setCachedParameters(null);
        }
    }

    /** Whether this client should hide the item (EMI, creative tabs and search). */
    public static boolean hiddenOnClient(Item item) {
        return client.contains(item);
    }

    public static boolean clientActive() {
        return !client.isEmpty();
    }
}
