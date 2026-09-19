package net.ledok.datarewriter.mixin;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import net.ledok.datarewriter.LootRewriter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(SimpleJsonResourceReloadListener.class)
public abstract class SimpleJsonResourceReloadListenerMixin {

    /**
     * ReloadableServerRegistries uses this static helper to read loot tables
     * (data/&lt;ns&gt;/loot_table/) during every (re)load. Other callers pass
     * other directories and are left alone.
     */
    @Inject(
            method = "scanDirectory(Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/lang/String;Lcom/google/gson/Gson;Ljava/util/Map;)V",
            at = @At("TAIL")
    )
    private static void datarewriter$rewriteLootTables(ResourceManager resourceManager, String directory,
                                                       Gson gson, Map<ResourceLocation, JsonElement> output,
                                                       CallbackInfo ci) {
        if (directory.equals("loot_table")) {
            LootRewriter.rewrite(output);
        }
    }
}
