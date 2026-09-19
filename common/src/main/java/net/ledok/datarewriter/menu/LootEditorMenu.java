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
 * Slot-less menu backing the client-side loot table editor screen, separate
 * from {@link EditorMenu} so overlay integrations aimed at the recipe editor
 * (e.g. EMI's recipe-fill button) don't attach here. The server never opens
 * this menu, so vanilla clients are unaffected. Constructed by loader glue
 * like {@link EditorMenu}.
 */
public class LootEditorMenu extends AbstractContainerMenu {
    public static MenuType<LootEditorMenu> TYPE;

    /** Called once from loader glue with a freshly constructed type. */
    public static void register(MenuType<LootEditorMenu> type) {
        TYPE = Registry.register(BuiltInRegistries.MENU, Datarewriter.id("loot_editor"), type);
    }

    public LootEditorMenu(int containerId, Inventory inventory) {
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
