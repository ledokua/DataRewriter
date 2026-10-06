package net.ledok.datarewriter.mixin;

import net.ledok.datarewriter.DisabledItems;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.trading.MerchantOffers;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Villagers and wandering traders fix their offers when a player starts trading — the one moment every
 * offer list is looked at, whether vanilla, a datapack or a mod's trade listing made it, and whether the
 * trader is new or was saved before the item got disabled.
 */
@Mixin(AbstractVillager.class)
public abstract class AbstractVillagerMixin {
    @Shadow
    @Nullable
    protected MerchantOffers offers;

    @Inject(method = "setTradingPlayer", at = @At("HEAD"))
    private void datarewriter$sanitizeOffers(@Nullable Player player, CallbackInfo ci) {
        if (player != null && offers != null && !player.level().isClientSide && DisabledItems.active()) {
            DisabledItems.sanitize(offers);
        }
    }
}
