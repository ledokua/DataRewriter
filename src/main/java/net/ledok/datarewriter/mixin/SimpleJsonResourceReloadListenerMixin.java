package net.ledok.datarewriter.mixin;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import net.ledok.datarewriter.LootRewriter;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(SimpleJsonResourceReloadListener.class)
public abstract class SimpleJsonResourceReloadListenerMixin {
    private static final String LOOT_TABLES = Registries.elementsDirPath(Registries.LOOT_TABLE);

    /**
     * ReloadableServerRegistries reads loot tables (data/&lt;ns&gt;/loot_table/) through this helper during
     * every (re)load, parsing each file as it is read. For that directory the scan goes through NeoForge's
     * bulk variant instead, which collects the raw JSON first, so the loot rules see and edit the whole map
     * before anything is parsed. Other directories are left alone.
     */
    @Inject(
            method = "scanDirectory(Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/resources/FileToIdConverter;Lcom/mojang/serialization/DynamicOps;Lcom/mojang/serialization/Codec;Ljava/util/Map;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private static <T> void datarewriter$rewriteLootTables(ResourceManager manager, FileToIdConverter lister,
                                                           DynamicOps<JsonElement> ops, Codec<T> codec,
                                                           Map<Identifier, T> result, CallbackInfo ci) {
        if (lister.prefix().equals(LOOT_TABLES)) {
            SimpleJsonResourceReloadListener.scanDirectoryWithModifier(manager, lister, ops, codec, result,
                    LootRewriter::rewrite);
            ci.cancel();
        }
    }
}
