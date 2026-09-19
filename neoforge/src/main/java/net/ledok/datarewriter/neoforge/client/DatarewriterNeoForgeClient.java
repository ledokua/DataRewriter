package net.ledok.datarewriter.neoforge.client;

import net.ledok.datarewriter.Datarewriter;
import net.ledok.datarewriter.api.DataRewriterClientAddon;
import net.ledok.datarewriter.client.DatarewriterClient;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.ServiceLoader;

/** Client-only mod instance; never constructed on a dedicated server. */
@Mod(value = Datarewriter.MOD_ID, dist = Dist.CLIENT)
public final class DatarewriterNeoForgeClient {
    public DatarewriterNeoForgeClient(IEventBus modBus) {
        DatarewriterClient.init();
        // Addons register editor layouts as ServiceLoader providers of DataRewriterClientAddon.
        ServiceLoader.load(DataRewriterClientAddon.class).forEach(DataRewriterClientAddon::registerClient);

        NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent e) -> {
            e.getDispatcher().register(Commands.literal("recipeeditor").executes(context -> {
                DatarewriterClient.openRecipeEditor();
                return 1;
            }));
            e.getDispatcher().register(Commands.literal("loottableeditor").executes(context -> {
                DatarewriterClient.openLootTableEditor();
                return 1;
            }));
            e.getDispatcher().register(Commands.literal("recipetweaker").executes(context -> {
                DatarewriterClient.openRecipeTweaker();
                return 1;
            }));
        });
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> DatarewriterClient.tick(Minecraft.getInstance()));
    }
}
