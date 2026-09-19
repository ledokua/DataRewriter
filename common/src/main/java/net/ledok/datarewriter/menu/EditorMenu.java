package net.ledok.datarewriter.menu;

import net.ledok.datarewriter.Datarewriter;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/**
 * Slot-less menu backing the client-side recipe editor screen. It has its own
 * registered type so overlay mods can target the editor precisely (e.g. EMI's
 * recipe-fill button); the server never opens this menu, so vanilla clients
 * are unaffected. The {@link MenuType} constructor is loader-access-widened,
 * so loader glue constructs it and registers here.
 */
public class EditorMenu extends AbstractContainerMenu {
    public static MenuType<EditorMenu> TYPE;

    /** Called once from loader glue with a freshly constructed type. */
    public static void register(MenuType<EditorMenu> type) {
        TYPE = Registry.register(BuiltInRegistries.MENU, Datarewriter.id("recipe_editor"), type);
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
