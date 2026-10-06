package net.ledok.datarewriter.mixin;

import net.ledok.datarewriter.DisabledItems;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Fresh item entities (block, crop and mob drops, thrown items) spawn as the replacement, or not at all. */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
    private void datarewriter$convertDisabledDrop(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (!(entity instanceof ItemEntity itemEntity) || !DisabledItems.active()) {
            return;
        }
        ItemStack stack = itemEntity.getItem();
        ItemStack converted = DisabledItems.convert(stack);
        if (converted == stack) {
            return;
        }
        if (converted.isEmpty()) {
            cir.setReturnValue(false);
        } else {
            itemEntity.setItem(converted);
        }
    }
}
