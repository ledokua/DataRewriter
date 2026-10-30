package net.ledok.datarewriter.client.gui;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.client.event.RegisterPictureInPictureRenderersEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Draws block models (and plain fluid boxes) inside GUI screens with the
 * transform conventions of Create's recipe-viewer animations, so bundled
 * layouts can mirror what EMI shows for a mod without compiling against it:
 * blocks are looked up by id, and anything missing is simply skipped.
 *
 * <p>Since 1.21.6 the GUI pose is 2D and drawing is deferred, so 3D content goes
 * through a {@link Scene}: a picture-in-picture layer anchored at a GUI point,
 * with its own 3D pose stack (GUI pixels, y down, like the old GUI pose).
 *
 * <p>Transform order per element (matches Catnip's GuiGameElement): scale,
 * local block offset, y-flip for GUI space, then rotation around the block
 * centre.
 */
public final class GuiBlocks {
    /** Half the side of a scene's square, in GUI pixels: room for a machine three blocks tall at scale 24. */
    private static final int SCENE_RADIUS = 80;
    private static final Map<String, BlockState> STATE_CACHE = new HashMap<>();

    private GuiBlocks() {
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
            Identifier id = Identifier.tryParse(blockId);
            if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
                return null;
            }
            Block block = BuiltInRegistries.BLOCK.getValue(id);
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

    /**
     * A 3D scene anchored at (x, y) of the current GUI pose. Build it with {@link #pose()} and the elements'
     * {@code render(scene)}, then {@link #submit()} it once.
     */
    static Scene scene(GuiGraphicsExtractor graphics, float x, float y) {
        return new Scene(graphics, x, y);
    }

    static final class Scene {
        private final GuiGraphicsExtractor graphics;
        private final Matrix3x2f guiPose;
        private final PoseStack pose = new PoseStack();
        private final List<Draw> draws = new ArrayList<>();

        private Scene(GuiGraphicsExtractor graphics, float x, float y) {
            this.graphics = graphics;
            // The anchor becomes the scene's origin: the PiP region is centred on it.
            this.guiPose = new Matrix3x2f(graphics.pose()).translate(x, y);
        }

        /** The scene's 3D pose: GUI pixels relative to the anchor, y down, z towards the viewer. */
        PoseStack pose() {
            return pose;
        }

        /** Hands the scene to the GUI renderer; nothing is drawn when every element was missing. */
        void submit() {
            if (!draws.isEmpty()) {
                graphics.submitPictureInPictureRenderState(new SceneRenderState(List.copyOf(draws),
                        new Matrix3x2f(guiPose), graphics.peekScissorStack()));
            }
        }
    }

    /** One element of a scene, with the scene pose it was added under. */
    private sealed interface Draw permits BlockDraw, FluidDraw {
        PoseStack.Pose pose();
    }

    private record BlockDraw(PoseStack.Pose pose, List<BlockStateModelPart> parts) implements Draw {
    }

    private record FluidDraw(PoseStack.Pose pose, TextureAtlasSprite sprite, int color,
                             float x0, float y0, float z0, float x1, float y1, float z1) implements Draw {
    }

    /** One drawable element with Catnip-style fluent transforms. */
    static final class Element {
        private final @Nullable List<BlockStateModelPart> parts;
        private double xLocal;
        private double yLocal;
        private double zLocal;
        private double xRot;
        private double yRot;
        private double zRot;
        private boolean aroundCenter;
        private double scale = 1;

        private Element(@Nullable List<BlockStateModelPart> parts) {
            this.parts = parts;
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

        void render(Scene scene) {
            if (parts == null || parts.isEmpty()) {
                return;
            }
            PoseStack pose = scene.pose;
            pose.pushPose();
            pose.scale((float) scale, (float) scale, (float) scale);
            pose.translate(xLocal, yLocal, zLocal);
            flip(pose);
            double off = aroundCenter ? 0.5 : 0;
            pose.translate(off, off, off);
            pose.mulPose(Axis.ZP.rotationDegrees((float) zRot));
            pose.mulPose(Axis.XP.rotationDegrees((float) xRot));
            pose.mulPose(Axis.YP.rotationDegrees((float) yRot));
            pose.translate(-off, -off, -off);
            scene.draws.add(new BlockDraw(pose.last().copy(), parts));
            pose.popPose();
        }
    }

    /** Element for a block state; draws nothing when the state is null. */
    static Element block(BlockState state) {
        if (state == null) {
            return new Element(null);
        }
        List<BlockStateModelPart> parts = new ArrayList<>();
        Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state)
                .collectParts(RandomSource.create(42L), parts);
        return new Element(parts);
    }

    /**
     * Element for a standalone model path (e.g. Flywheel partials, "create:block/mechanical_mixer/head").
     * NeoForge 26.x files standalone models under key objects owned by the mod that registered them, so
     * another mod's parts can't be looked up by path: they are skipped, like missing ones always were.
     */
    static Element part(String modelId) {
        return new Element(null);
    }

    /** Multiplies the pose by a y-flip so block-space "up" is screen-up. */
    static void flip(PoseStack pose) {
        pose.mulPose(new Matrix4f().scaling(1, -1, 1));
    }

    /**
     * Fluid rendered as a textured, tinted box in the current (already
     * flipped/scaled) scene space; used for basins, spouts and drains.
     * Falls back to water when the fluid id is unknown.
     */
    static void fluidBox(Scene scene, String fluidId, float x0, float y0, float z0, float x1, float y1, float z1) {
        Fluid fluid = Fluids.WATER;
        Identifier id = fluidId == null ? null : Identifier.tryParse(fluidId);
        if (id != null && BuiltInRegistries.FLUID.containsKey(id)) {
            fluid = BuiltInRegistries.FLUID.getValue(id);
        }
        FluidModel model = Minecraft.getInstance().getModelManager().getFluidStateModelSet()
                .get(fluid.defaultFluidState());
        if (model == null) {
            return;
        }
        int color = -1;
        if (model.tintSource() != null) {
            try {
                color = model.tintSource().color(fluid.defaultFluidState().createLegacyBlock());
            } catch (Exception e) {
                // tints that need a level position: untinted
            }
        }
        scene.draws.add(new FluidDraw(scene.pose.last().copy(), model.stillMaterial().sprite(), color,
                x0, y0, z0, x1, y1, z1));
    }

    /** Registers the scene renderer with NeoForge's PiP event (DatarewriterNeoForgeClient). */
    public static void registerRenderer(RegisterPictureInPictureRenderersEvent event) {
        event.register(SceneRenderState.class, SceneRenderer::new);
    }

    /** What a scene hands the GUI renderer: its draws, square region and the GUI pose it sits in. */
    record SceneRenderState(List<Draw> draws, Matrix3x2f pose, int x0, int y0, int x1, int y1,
                            @Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds)
            implements PictureInPictureRenderState {
        SceneRenderState(List<Draw> draws, Matrix3x2f pose, @Nullable ScreenRectangle scissorArea) {
            this(draws, pose, -SCENE_RADIUS, -SCENE_RADIUS, SCENE_RADIUS, SCENE_RADIUS, scissorArea,
                    screenBounds(pose, scissorArea));
        }

        @Override
        public float scale() {
            return 1;
        }

        /** The region in screen space (through the GUI pose), clipped by the scissor like vanilla's states. */
        private static ScreenRectangle screenBounds(Matrix3x2f pose, @Nullable ScreenRectangle scissorArea) {
            ScreenRectangle region = new ScreenRectangle(-SCENE_RADIUS, -SCENE_RADIUS, 2 * SCENE_RADIUS,
                    2 * SCENE_RADIUS).transformMaxBounds(pose);
            return scissorArea != null ? scissorArea.intersection(region) : region;
        }
    }

    static final class SceneRenderer extends PictureInPictureRenderer<SceneRenderState> {
        SceneRenderer(MultiBufferSource.BufferSource bufferSource) {
            super(bufferSource);
        }

        @Override
        public Class<SceneRenderState> getRenderStateClass() {
            return SceneRenderState.class;
        }

        @Override
        protected float getTranslateY(int height, int guiScale) {
            return height / 2f; // the anchor is the texture centre
        }

        @Override
        protected void renderToTexture(SceneRenderState state, PoseStack poseStack) {
            Minecraft.getInstance().gameRenderer.getLighting().setupFor(Lighting.Entry.ITEMS_3D);
            // The PiP space is y up; scene poses are GUI space (y down, z towards the viewer).
            poseStack.mulPose(new Matrix4f().scaling(1, -1, -1));
            QuadInstance instance = new QuadInstance();
            VertexConsumer blocks = bufferSource.getBuffer(Sheets.cutoutBlockSheet());
            for (Draw draw : state.draws()) {
                poseStack.pushPose();
                poseStack.last().pose().mul(draw.pose().pose());
                poseStack.last().normal().mul(draw.pose().normal());
                if (draw instanceof BlockDraw block) {
                    for (BlockStateModelPart part : block.parts()) {
                        for (Direction direction : DIRECTIONS) {
                            for (BakedQuad quad : part.getQuads(direction)) {
                                blocks.putBakedQuad(poseStack.last(), quad, instance);
                            }
                        }
                    }
                } else if (draw instanceof FluidDraw fluid) {
                    fluidBox(bufferSource.getBuffer(Sheets.translucentBlockSheet()), poseStack.last(), fluid);
                    blocks = bufferSource.getBuffer(Sheets.cutoutBlockSheet());
                }
                poseStack.popPose();
            }
        }

        @Override
        protected String getTextureLabel() {
            return "datarewriter block scene";
        }
    }

    /** null first: the quads facing no particular direction. */
    private static final Direction[] DIRECTIONS = {null, Direction.DOWN, Direction.UP, Direction.NORTH,
            Direction.SOUTH, Direction.WEST, Direction.EAST};

    private static void fluidBox(VertexConsumer buffer, PoseStack.Pose pose, FluidDraw f) {
        TextureAtlasSprite sprite = f.sprite();
        float r = (f.color() >> 16 & 0xFF) / 255f;
        float g = (f.color() >> 8 & 0xFF) / 255f;
        float b = (f.color() & 0xFF) / 255f;
        float x0 = f.x0(), y0 = f.y0(), z0 = f.z0(), x1 = f.x1(), y1 = f.y1(), z1 = f.z1();
        // Six faces, uv spans proportional to the face size (sprite is 1x1 block).
        quad(buffer, pose, sprite, r, g, b, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, 0, 1, 0, x0, z0, x1, z1); // top
        quad(buffer, pose, sprite, r, g, b, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, 0, -1, 0, x0, z0, x1, z1); // bottom
        quad(buffer, pose, sprite, r, g, b, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, 0, 0, -1, x0, y0, x1, y1); // north
        quad(buffer, pose, sprite, r, g, b, x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1, 0, 0, 1, x0, y0, x1, y1); // south
        quad(buffer, pose, sprite, r, g, b, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1, -1, 0, 0, z0, y0, z1, y1); // west
        quad(buffer, pose, sprite, r, g, b, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, 1, 0, 0, z0, y0, z1, y1); // east
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
                .setLight(LightCoordsUtil.FULL_BRIGHT)
                .setNormal(pose, nx, ny, nz);
    }
}
