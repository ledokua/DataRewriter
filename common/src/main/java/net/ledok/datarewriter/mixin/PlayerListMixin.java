package net.ledok.datarewriter.mixin;

import net.ledok.datarewriter.DisabledItems;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Sends the disabled-item list right before the recipes (join) or the tags (/reload): recipe viewers like
 * EMI reload once they have both, so they already know what to hide — no second reload.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Shadow
    @Final
    private List<ServerPlayer> players;

    @Inject(method = "placeNewPlayer", at = @At(value = "NEW",
            target = "net/minecraft/network/protocol/game/ClientboundUpdateRecipesPacket"))
    private void datarewriter$syncDisabledItemsOnJoin(Connection connection, ServerPlayer player,
                                                      CommonListenerCookie cookie, CallbackInfo ci) {
        DisabledItems.sync(player);
    }

    @Inject(method = "reloadResources", at = @At("HEAD"))
    private void datarewriter$syncDisabledItemsOnReload(CallbackInfo ci) {
        DisabledItems.syncAll(players);
    }
}
