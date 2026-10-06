package net.ledok.datarewriter.mixin;

import net.ledok.datarewriter.DisabledItems;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Item entities saved before the item was disabled turn into the replacement (or vanish) as their chunk loads. */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
    @Shadow
    public abstract ItemStack getItem();

    @Shadow
    public abstract void setItem(ItemStack stack);

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void datarewriter$convertDisabled(CompoundTag tag, CallbackInfo ci) {
        if (!DisabledItems.active()) {
            return;
        }
        ItemStack stack = getItem();
        ItemStack converted = DisabledItems.convert(stack);
        if (converted == stack) {
            return;
        }
        if (converted.isEmpty()) {
            ((ItemEntity) (Object) this).discard();
        } else {
            setItem(converted);
        }
    }
}
