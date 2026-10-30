package net.ledok.datarewriter.neoforge.client;

import net.ledok.datarewriter.client.DatarewriterClient;
import net.ledok.datarewriter.network.LootPayloads;
import net.ledok.datarewriter.network.RegistryPayloads;
import net.ledok.datarewriter.network.ThaumcraftRecipesPayload;
import net.ledok.datarewriter.compat.thaumcraft.ThaumcraftClient;
import net.minecraft.client.Minecraft;

/** Client-bound payload handlers; only ever loaded on the physical client (see {@code DatarewriterNeoForge}). */
public final class NeoForgeClientPayloads {
    private NeoForgeClientPayloads() {
    }

    public static void tableList(LootPayloads.TableList p) { DatarewriterClient.onTableList(Minecraft.getInstance(), p); }
    public static void clipboard(LootPayloads.Clipboard p) { DatarewriterClient.onClipboard(Minecraft.getInstance(), p); }
    public static void saveResult(LootPayloads.SaveResult p) { DatarewriterClient.onSaveResult(Minecraft.getInstance(), p); }
    public static void tableContent(LootPayloads.TableContent p) { DatarewriterClient.onTableContent(Minecraft.getInstance(), p); }
    public static void thaumcraftRecipes(ThaumcraftRecipesPayload p) { ThaumcraftClient.receive(p); }
    public static void registryEntry(RegistryPayloads.EntryContent p) { DatarewriterClient.onRegistryEntry(Minecraft.getInstance(), p); }
}
