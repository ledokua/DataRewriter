package net.ledok.datarewriter.menu;

import net.ledok.datarewriter.Datarewriter;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/**
 * Slot-less menu backing the client-side recipe editor screen. It has its own
 * registered type so overlay mods can target the editor precisely (e.g. EMI's
 * recipe-fill button); the server never opens this menu, so vanilla clients
 * are unaffected.
 */
public class EditorMenu extends AbstractContainerMenu {
    public static final MenuType<EditorMenu> TYPE =
            new MenuType<>(EditorMenu::new, FeatureFlags.DEFAULT_FLAGS);

    public static void register() {
        Registry.register(BuiltInRegistries.MENU,
                ResourceLocation.fromNamespaceAndPath(Datarewriter.MOD_ID, "recipe_editor"), TYPE);
    }

    public EditorMenu(int containerId, Inventory inventory) {
        super(TYPE, containerId);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
