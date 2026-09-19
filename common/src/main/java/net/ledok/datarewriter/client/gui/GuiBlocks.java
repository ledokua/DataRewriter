package net.ledok.datarewriter.client.gui;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.ledok.datarewriter.client.ClientPlatform;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Draws block models (and plain fluid boxes) inside GUI screens with the
 * transform conventions of Create's recipe-viewer animations, so bundled
 * layouts can mirror what EMI shows for a mod without compiling against it:
 * blocks are looked up by id, extra model parts by model path, and anything
 * missing is simply skipped.
 *
 * <p>Transform order per element (matches Catnip's GuiGameElement): scale,
 * local block offset, y-flip for GUI space, then rotation around the block
 * centre.
 */
final class GuiBlocks {
    private static final Vector3f LIGHT_1 = light(12.5f, -45f);
    private static final Vector3f LIGHT_2 = light(-20f, -50f);
    private static final Map<String, BlockState> STATE_CACHE = new HashMap<>();

    private GuiBlocks() {
    }

    private static Vector3f light(float yRot, float xRot) {
        Vector3f v = new Vector3f(0, 0, 1);
        v.rotate(Axis.YP.rotationDegrees(yRot));
        v.rotate(Axis.XN.rotationDegrees(xRot));
        return v;
    }

    /** Animation clock in ticks (fractional), independent of the game clock. */
    static float time() {
        return (Util.getMillis() % 7_200_000L) / 50f;
    }

    /** 0-360 rotation that advances 4 degrees per tick, like Create's kinetics previews. */
    static float angle() {
        return (time() * 4f) % 360;
    }

    /**
     * Block state by id with optional "property=value" overrides; null when
     * the block isn't registered (mod absent) — callers then skip drawing.
     */
    static BlockState state(String blockId, String... properties) {
        String key = blockId + String.join(",", properties);
        return STATE_CACHE.computeIfAbsent(key, k -> {
            ResourceLocation id = ResourceLocation.tryParse(blockId);
            if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
                return null;
            }
            Block block = BuiltInRegistries.BLOCK.get(id);
            BlockState state = block.defaultBlockState();
            for (String assignment : properties) {
                int eq = assignment.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                Property<?> property = block.getStateDefinition().getProperty(assignment.substring(0, eq));
                if (property != null) {
                    state = with(state, property, assignment.substring(eq + 1));
                }
            }
            return state;
        });
    }

    private static <T extends Comparable<T>> BlockState with(BlockState state, Property<T> property, String value) {
        return property.getValue(value.toLowerCase(Locale.ROOT)).map(v -> state.setValue(property, v)).orElse(state);
    }

    /** A standalone model (e.g. Flywheel partials, "create:block/mechanical_mixer/head"); null when missing. */
    static BakedModel model(String modelId) {
        ResourceLocation id = ResourceLocation.tryParse(modelId);
        if (id == null) {
            return null;
        }
        BakedModel model = ClientPlatform.INSTANCE.standaloneModel(id);
        return model == null || model == Minecraft.getInstance().getModelManager().getMissingModel() ? null : model;
    }

    /** One drawable element with Catnip-style fluent transforms. */
    static final class Element {
        private final BakedModel model;
        private final BlockState state;
        private double xLocal;
        private double yLocal;
        private double zLocal;
        private double xRot;
        private double yRot;
        private double zRot;
        private boolean aroundCenter;
        private double scale = 1;

        private Element(BakedModel model, BlockState state) {
            this.model = model;
            this.state = state;
        }

        Element atLocal(double x, double y, double z) {
            xLocal = x;
            yLocal = y;
            zLocal = z;
            return this;
        }

        /** Rotation around the element origin. */
        Element rotate(double x, double y, double z) {
            xRot = x;
            yRot = y;
            zRot = z;
            aroundCenter = false;
            return this;
        }

        /** Rotation around the block centre. */
        Element rotateBlock(double x, double y, double z) {
            rotate(x, y, z);
            aroundCenter = true;
            return this;
        }

        Element scale(double s) {
            scale = s;
            return this;
        }

        void render(GuiGraphics graphics) {
            if (model == null) {
                return;
            }
            PoseStack pose = graphics.pose();
            pose.pushPose();
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.enableDepthTest();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderLights(LIGHT_1, LIGHT_2);
            pose.scale((float) scale, (float) scale, (float) scale);
            pose.translate(xLocal, yLocal, zLocal);
            flip(pose);
            double off = aroundCenter ? 0.5 : 0;
            pose.translate(off, off, off);
            pose.mulPose(Axis.ZP.rotationDegrees((float) zRot));
            pose.mulPose(Axis.XP.rotationDegrees((float) xRot));
            pose.mulPose(Axis.YP.rotationDegrees((float) yRot));
            pose.translate(-off, -off, -off);
            Minecraft mc = Minecraft.getInstance();
            VertexConsumer buffer = graphics.bufferSource().getBuffer(Sheets.cutoutBlockSheet());
            mc.getBlockRenderer().getModelRenderer().renderModel(pose.last(), buffer,
                    state, model, 1, 1, 1, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            graphics.flush();
            pose.popPose();
            Lighting.setupFor3DItems();
        }
    }

    /** Element for a block state; draws nothing when the state is null. */
    static Element block(BlockState state) {
        if (state == null) {
            return new Element(null, null);
        }
        return new Element(Minecraft.getInstance().getBlockRenderer().getBlockModel(state), state);
    }

    /** Element for a standalone model path; draws nothing when it's missing. */
    static Element part(String modelId) {
        return new Element(model(modelId), Blocks.AIR.defaultBlockState());
    }

    /** Multiplies the pose by a y-flip so block-space "up" is screen-up. */
    static void flip(PoseStack pose) {
        pose.mulPose(new Matrix4f().scaling(1, -1, 1));
    }

    /**
     * Fluid rendered as a textured, tinted box in the current (already
     * flipped/scaled) block space; used for basins, spouts and drains.
     * Falls back to water when the fluid id is unknown.
     */
    static void fluidBox(GuiGraphics graphics, String fluidId, float x0, float y0, float z0, float x1, float y1, float z1) {
        Fluid fluid = Fluids.WATER;
        ResourceLocation id = fluidId == null ? null : ResourceLocation.tryParse(fluidId);
        if (id != null && BuiltInRegistries.FLUID.containsKey(id)) {
            fluid = BuiltInRegistries.FLUID.get(id);
        }
        ClientPlatform.FluidSprite fluidSprite = ClientPlatform.INSTANCE.fluidSprite(fluid);
        if (fluidSprite == null) {
            fluidSprite = ClientPlatform.INSTANCE.fluidSprite(Fluids.WATER);
        }
        if (fluidSprite == null) {
            return;
        }
        TextureAtlasSprite sprite = fluidSprite.sprite();
        int color = fluidSprite.color();
        float r = (color >> 16 & 0xFF) / 255f;
        float g = (color >> 8 & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;
        VertexConsumer buffer = graphics.bufferSource().getBuffer(Sheets.translucentCullBlockSheet());
        PoseStack.Pose pose = graphics.pose().last();
        // Six faces, uv spans proportional to the face size (sprite is 1x1 block).
        quad(buffer, pose, sprite, r, g, b, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, 0, 1, 0, x0, z0, x1, z1); // top
        quad(buffer, pose, sprite, r, g, b, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, 0, -1, 0, x0, z0, x1, z1); // bottom
        quad(buffer, pose, sprite, r, g, b, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, 0, 0, -1, x0, y0, x1, y1); // north
        quad(buffer, pose, sprite, r, g, b, x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1, 0, 0, 1, x0, y0, x1, y1); // south
        quad(buffer, pose, sprite, r, g, b, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1, -1, 0, 0, z0, y0, z1, y1); // west
        quad(buffer, pose, sprite, r, g, b, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, 1, 0, 0, z0, y0, z1, y1); // east
        graphics.flush();
    }

    private static void quad(VertexConsumer buffer, PoseStack.Pose pose, TextureAtlasSprite sprite,
                             float r, float g, float b,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz,
                             float nx, float ny, float nz,
                             float u0, float v0, float u1, float v1) {
        float su0 = sprite.getU(frac(u0));
        float su1 = sprite.getU(frac(u1));
        float sv0 = sprite.getV(frac(v0));
        float sv1 = sprite.getV(frac(v1));
        vertex(buffer, pose, ax, ay, az, r, g, b, su0, sv1, nx, ny, nz);
        vertex(buffer, pose, bx, by, bz, r, g, b, su1, sv1, nx, ny, nz);
        vertex(buffer, pose, cx, cy, cz, r, g, b, su1, sv0, nx, ny, nz);
        vertex(buffer, pose, dx, dy, dz, r, g, b, su0, sv0, nx, ny, nz);
        // Back face too, so the box reads correctly from every gui angle.
        vertex(buffer, pose, dx, dy, dz, r, g, b, su0, sv0, -nx, -ny, -nz);
        vertex(buffer, pose, cx, cy, cz, r, g, b, su1, sv0, -nx, -ny, -nz);
        vertex(buffer, pose, bx, by, bz, r, g, b, su1, sv1, -nx, -ny, -nz);
        vertex(buffer, pose, ax, ay, az, r, g, b, su0, sv1, -nx, -ny, -nz);
    }

    private static float frac(float v) {
        float f = v % 1f;
        if (f < 0) {
            f += 1f;
        }
        return f == 0 && v > 0 ? 1f : f;
    }

    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float z,
                               float r, float g, float b, float u, float v, float nx, float ny, float nz) {
        buffer.addVertex(pose, x, y, z)
                .setColor(r, g, b, 1f)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(pose, nx, ny, nz);
    }
}
