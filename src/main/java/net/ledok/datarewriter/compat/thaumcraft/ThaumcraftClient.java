package net.ledok.datarewriter.compat.thaumcraft;

import net.ledok.datarewriter.network.ThaumcraftRecipesPayload;
import net.minecraft.client.Minecraft;

/** Client half of the Thaumcraft integration; only ever called on the physical client. */
public final class ThaumcraftClient {
    private ThaumcraftClient() {
    }

    /** The server's changes arrived (on the main thread, ahead of the recipe sync that makes viewers reload). */
    public static void receive(ThaumcraftRecipesPayload payload) {
        if (!ThaumcraftCompat.LOADED || Minecraft.getInstance().getSingleplayerServer() != null) {
            return; // singleplayer: the integrated server already rewrote the shared map
        }
        ThaumcraftRecipes.applyDiff(new ThaumcraftRecipes.Diff(payload.removed(), payload.changed()));
    }

    /** A new connection: back to Thaumcraft's own recipes until the server says otherwise. */
    public static void reset() {
        if (ThaumcraftCompat.LOADED && Minecraft.getInstance().getSingleplayerServer() == null) {
            ThaumcraftRecipes.restore();
        }
    }
}
