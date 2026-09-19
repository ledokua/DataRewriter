package net.ledok.datarewriter.client.gui;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static net.ledok.datarewriter.client.gui.EditorLayout.Decoration;
import static net.ledok.datarewriter.client.gui.EditorLayout.FieldDef;
import static net.ledok.datarewriter.client.gui.EditorLayout.FieldType;
import static net.ledok.datarewriter.client.gui.EditorLayout.Kind;
import static net.ledok.datarewriter.client.gui.EditorLayout.SlotDef;
import static net.ledok.datarewriter.client.gui.EditorLayout.SlotFormat;
import static net.ledok.datarewriter.client.gui.EditorLayout.SlotView;

/**
 * Bundled layouts for Create's processing recipes, drawn like Create's own
 * EMI recipe views: the same slot positions, arrows and slot frames from
 * Create's {@code jei/widgets.png}, and the same animated machines (mixer,
 * press, crushing wheels, saw, ...) rendered from Create's block models —
 * all referenced by id, so nothing here needs Create at compile time and
 * each layout only appears when its recipe type is registered.
 *
 * <p>JSON schema (Create 6 processing recipes): {@code ingredients} is a
 * list of item ingredients ({@code {"item"|"tag"}}) and fluid ingredients
 * ({@code {"type": "neoforge:single"|"neoforge:tag", "fluid"|"tag", "amount"}}),
 * {@code results} a list of item outputs ({@code {"id", "count", "chance"}})
 * and fluid outputs ({@code {"id", "amount"}}); plus {@code processing_time},
 * {@code heat_requirement} (basins), {@code keep_held_item} (deployer /
 * item application) and {@code accept_mirrored} (mechanical crafting).
 */
final class CreateLayouts {
    private static final ResourceLocation WIDGETS =
            ResourceLocation.fromNamespaceAndPath("create", "textures/gui/jei/widgets.png");
    private static final float ROT_X = -15.5f;
    private static final float ROT_Y = 22.5f;

    private static final FieldDef TIME = new FieldDef("processing_time", "Time (ticks)", FieldType.INT, "", false);
    private static final FieldDef HEAT = new FieldDef("heat_requirement", "Heat", FieldType.STRING, "", false,
            List.of("none", "heated", "superheated"));
    private static final FieldDef KEEP_HELD = new FieldDef("keep_held_item", "Keep held item", FieldType.BOOL, "false", false);
    private static final FieldDef MIRRORED = new FieldDef("accept_mirrored", "Accept mirrored", FieldType.BOOL, "false", true);

    /** Fluid ingredient in Create's (NeoForge-style) typed form; the port also accepts "create:" type ids. */
    private static final EditorLayout.SlotValueBuilder FLUID_INGREDIENT = (ref, amount, chance) -> {
        JsonObject obj = new JsonObject();
        if (ref.startsWith("#")) {
            obj.addProperty("type", "neoforge:tag");
            obj.addProperty("tag", ref.substring(1));
        } else {
            obj.addProperty("type", "neoforge:single");
            obj.addProperty("fluid", ref);
        }
        obj.addProperty("amount", amount);
        return obj;
    };

    private CreateLayouts() {
    }

    static List<EditorLayout> forInstalledTypes() {
        List<EditorLayout> layouts = new ArrayList<>();
        add(layouts, "create:mixing", CreateLayouts::mixing);
        add(layouts, "create:compacting", CreateLayouts::compacting);
        add(layouts, "create:crushing", CreateLayouts::crushing);
        add(layouts, "create:milling", CreateLayouts::milling);
        add(layouts, "create:pressing", CreateLayouts::pressing);
        add(layouts, "create:cutting", CreateLayouts::cutting);
        add(layouts, "create:deploying", CreateLayouts::deploying);
        add(layouts, "create:item_application", CreateLayouts::itemApplication);
        add(layouts, "create:filling", CreateLayouts::filling);
        add(layouts, "create:emptying", CreateLayouts::emptying);
        add(layouts, "create:splashing", CreateLayouts::splashing);
        add(layouts, "create:haunting", CreateLayouts::haunting);
        add(layouts, "create:sandpaper_polishing", CreateLayouts::polishing);
        add(layouts, "create:mechanical_crafting", CreateLayouts::mechanicalCrafting);
        return layouts;
    }

    private static void add(List<EditorLayout> layouts, String typeId, java.util.function.Supplier<EditorLayout> factory) {
        ResourceLocation id = ResourceLocation.tryParse(typeId);
        if (id != null && BuiltInRegistries.RECIPE_SERIALIZER.containsKey(id)) {
            layouts.add(factory.get());
        }
    }

    /** Inset of the first slot in a panel drawn by us (the frame art starts one pixel earlier). */
    private static final int PAD = 6;

    // ---- slot helpers -------------------------------------------------------

    private static SlotDef in(int x, int y, boolean required) {
        return new SlotDef(x, y, "ingredients[]", SlotFormat.INGREDIENT, false, required);
    }

    private static SlotDef out(int x, int y, boolean required) {
        return new SlotDef(x, y, "results[]", SlotFormat.ITEM, true, required, true, false, null);
    }

    private static SlotDef fluidIn(int x, int y) {
        return new SlotDef(x, y, "ingredients[]", SlotFormat.FLUID_AMOUNT, false, false, false, true, FLUID_INGREDIENT);
    }

    private static SlotDef fluidOut(int x, int y) {
        return new SlotDef(x, y, "results[]", SlotFormat.FLUID_AMOUNT, true, false);
    }

    private static EditorLayout layout(String type, String name, int width, int height,
                                       List<SlotDef> slots, List<FieldDef> fields, Decoration decoration) {
        EditorLayout layout = new EditorLayout(type, name, null, 0, 0, width, height, Kind.SLOTS, slots, fields);
        layout.ownSlotArt = true;
        layout.decoration = decoration;
        return layout;
    }

    // ---- layouts ------------------------------------------------------------

    /** Basin layouts (mixing/compacting): 3x3 items + 2 fluids in, 4 items + 2 fluids out, heat bar. */
    private static List<SlotDef> basinSlots() {
        List<SlotDef> slots = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            slots.add(in(17 + (i % 3) * 19, 51 - (i / 3) * 19, i == 0));
        }
        slots.add(fluidIn(17, 70));
        slots.add(fluidIn(36, 70));
        for (int i = 0; i < 4; i++) {
            slots.add(out(132 + (i % 2) * 19, 51 - (i / 2) * 19, i == 0));
        }
        slots.add(fluidOut(132, 70));
        slots.add(fluidOut(151, 70));
        return slots;
    }

    private static Decoration basinDecoration(List<SlotDef> slots, boolean press) {
        return (graphics, left, top, partialTick, view) -> {
            PoseStack pose = graphics.pose();
            pose.pushPose();
            pose.translate(left, top, 0);
            String heat = view.field("heat_requirement").trim().toLowerCase(Locale.ROOT);
            boolean heated = heat.equals("heated") || heat.equals("superheated");
            slotFrames(graphics, slots, view);
            downArrow(graphics, 136, 13);
            if (heated) {
                widget(graphics, 81, 88, 0, 42, 52, 11); // light
            } else {
                widget(graphics, 81, 68, 0, 56, 52, 11); // shadow
            }
            widget(graphics, 4, 92, 0, heated ? 201 : 221, 169, 19); // heat bar
            String key = heat.isEmpty() ? "none" : heat;
            int color = switch (key) {
                case "heated" -> 0xE88300;
                case "superheated" -> 0x5C93E8;
                default -> 0xFFFFFF;
            };
            graphics.drawString(Minecraft.getInstance().font,
                    Component.translatable("create.recipe.heat_requirement." + key), 9, 98, color, false);
            if (heated) {
                renderItemIcon(graphics, "create:blaze_burner", 134, 93);
                if (heat.equals("superheated")) {
                    renderItemIcon(graphics, "create:blaze_cake", 153, 93);
                }
                blazeBurner(graphics, 177 / 2 + 3, 55, heat.equals("superheated"));
            }
            if (press) {
                press(graphics, 177 / 2 + 3, 34, true);
            } else {
                mixer(graphics, 177 / 2 + 3, 34);
            }
            pose.popPose();
        };
    }

    private static EditorLayout mixing() {
        List<SlotDef> slots = basinSlots();
        return layout("create:mixing", "Mixing (Create)", 177, 116, slots, List.of(HEAT, TIME),
                basinDecoration(slots, false));
    }

    private static EditorLayout compacting() {
        List<SlotDef> slots = basinSlots();
        return layout("create:compacting", "Compacting (Create)", 177, 116, slots, List.of(HEAT, TIME),
                basinDecoration(slots, true));
    }

    private static EditorLayout crushing() {
        List<SlotDef> slots = new ArrayList<>();
        slots.add(in(51, 3, true));
        for (int i = 0; i < 4; i++) {
            slots.add(out(52 + i * 19, 78, i == 0));
        }
        return layout("create:crushing", "Crushing (Create)", 177, 100, slots, List.of(TIME),
                (graphics, left, top, partialTick, view) -> {
                    PoseStack pose = graphics.pose();
                    pose.pushPose();
                    pose.translate(left, top, 0);
                    slotFrames(graphics, slots, view);
                    downArrow(graphics, 72, 7);
                    crushingWheels(graphics, 62, 59);
                    pose.popPose();
                });
    }

    private static EditorLayout milling() {
        List<SlotDef> slots = new ArrayList<>();
        slots.add(in(15, 9, true));
        for (int i = 0; i < 4; i++) {
            slots.add(out(133 + (i % 2) * 19, 27 - (i / 2) * 19, i == 0));
        }
        return layout("create:milling", "Milling (Create)", 177, 53, slots, List.of(TIME),
                (graphics, left, top, partialTick, view) -> {
                    PoseStack pose = graphics.pose();
                    pose.pushPose();
                    pose.translate(left, top, 0);
                    slotFrames(graphics, slots, view);
                    arrow(graphics, 85, 32);
                    downArrow(graphics, 43, 4);
                    millstone(graphics, 48, 27);
                    pose.popPose();
                });
    }

    private static EditorLayout pressing() {
        List<SlotDef> slots = new ArrayList<>();
        slots.add(in(27, 51, true));
        slots.add(out(131, 50, true));
        slots.add(out(150, 50, false));
        return layout("create:pressing", "Pressing (Create)", 177, 70, slots, List.of(TIME),
                (graphics, left, top, partialTick, view) -> {
                    PoseStack pose = graphics.pose();
                    pose.pushPose();
                    pose.translate(left, top, 0);
                    slotFrames(graphics, slots, view);
                    shadow(graphics, 61, 41);
                    longArrow(graphics, 52, 54);
                    press(graphics, 177 / 2 - 17, 22, false);
                    pose.popPose();
                });
    }

    private static EditorLayout cutting() {
        List<SlotDef> slots = new ArrayList<>();
        slots.add(in(44, 5, true));
        for (int i = 0; i < 4; i++) {
            slots.add(out(118 + (i % 2) * 19, 48 - (i / 2) * 19, i == 0));
        }
        return layout("create:cutting", "Sawing (Create)", 177, 70, slots, List.of(TIME),
                (graphics, left, top, partialTick, view) -> {
                    PoseStack pose = graphics.pose();
                    pose.pushPose();
                    pose.translate(left, top, 0);
                    slotFrames(graphics, slots, view);
                    downArrow(graphics, 70, 6);
                    shadow(graphics, 72 - 17, 42 + 13);
                    saw(graphics, 72, 42);
                    pose.popPose();
                });
    }

    private static EditorLayout deploying() {
        List<SlotDef> slots = new ArrayList<>();
        slots.add(in(27, 51, true));  // processed item
        slots.add(in(51, 5, true));   // held item
        slots.add(out(132, 51, true));
        slots.add(out(151, 51, false));
        return layout("create:deploying", "Deploying (Create)", 177, 70, slots, List.of(KEEP_HELD, TIME),
                (graphics, left, top, partialTick, view) -> {
                    PoseStack pose = graphics.pose();
                    pose.pushPose();
                    pose.translate(left, top, 0);
                    slotFrames(graphics, slots, view);
                    shadow(graphics, 62, 57);
                    downArrow(graphics, 126, 29);
                    deployer(graphics, 177 / 2 - 13, 22);
                    pose.popPose();
                });
    }

    private static EditorLayout itemApplication() {
        List<SlotDef> slots = new ArrayList<>();
        slots.add(in(27, 38, true));  // block
        slots.add(in(51, 5, true));   // applied item
        slots.add(out(132, 38, true));
        slots.add(out(151, 38, false));
        return layout("create:item_application", "Item application — manual (Create)", 177, 60, slots,
                List.of(KEEP_HELD),
                (graphics, left, top, partialTick, view) -> {
                    PoseStack pose = graphics.pose();
                    pose.pushPose();
                    pose.translate(left, top, 0);
                    slotFrames(graphics, slots, view);
                    shadow(graphics, 62, 47);
                    downArrow(graphics, 74, 10);
                    BlockState state = blockOf(view.ref(0));
                    if (state != null) {
                        pose.pushPose();
                        pose.translate(74, 51, 100);
                        pose.mulPose(Axis.XP.rotationDegrees(ROT_X));
                        pose.mulPose(Axis.YP.rotationDegrees(ROT_Y));
                        GuiBlocks.block(state).scale(20).render(graphics);
                        pose.popPose();
                    }
                    pose.popPose();
                });
    }

    private static EditorLayout filling() {
        List<SlotDef> slots = new ArrayList<>();
        slots.add(in(27, 51, true));
        slots.add(fluidIn(27, 32));
        slots.add(out(132, 51, true));
        return layout("create:filling", "Spout filling (Create)", 177, 70, slots, List.of(TIME),
                (graphics, left, top, partialTick, view) -> {
                    PoseStack pose = graphics.pose();
                    pose.pushPose();
                    pose.translate(left, top, 0);
                    slotFrames(graphics, slots, view);
                    shadow(graphics, 62, 57);
                    downArrow(graphics, 126, 29);
                    spout(graphics, 177 / 2 - 13, 22, view.ref(1));
                    pose.popPose();
                });
    }

    private static EditorLayout emptying() {
        List<SlotDef> slots = new ArrayList<>();
        slots.add(in(27, 8, true));
        slots.add(fluidOut(132, 8));
        slots.add(out(132, 27, false));
        return layout("create:emptying", "Item draining (Create)", 177, 50, slots, List.of(TIME),
                (graphics, left, top, partialTick, view) -> {
                    PoseStack pose = graphics.pose();
                    pose.pushPose();
                    pose.translate(left, top, 0);
                    slotFrames(graphics, slots, view);
                    shadow(graphics, 62, 37);
                    downArrow(graphics, 73, 4);
                    drain(graphics, 177 / 2 - 13, 40, view.ref(1));
                    pose.popPose();
                });
    }

    private static List<SlotDef> fanSlots() {
        List<SlotDef> slots = new ArrayList<>();
        slots.add(in(11, 48, true));
        for (int i = 0; i < 6; i++) {
            slots.add(out(123 + (i % 3) * 19, 48 - (i / 3) * 19, i == 0));
        }
        return slots;
    }

    private static Decoration fanDecoration(List<SlotDef> slots, boolean soulFire) {
        return (graphics, left, top, partialTick, view) -> {
            PoseStack pose = graphics.pose();
            pose.pushPose();
            pose.translate(left, top, 0);
            slotFrames(graphics, slots, view);
            shadow(graphics, 46, 29);
            if (soulFire) {
                widget(graphics, 65, 39, 0, 42, 52, 11); // light
            } else {
                shadow(graphics, 65, 39);
            }
            longArrow(graphics, 40, 51);
            fan(graphics, 56, 33, soulFire);
            pose.popPose();
        };
    }

    private static EditorLayout splashing() {
        List<SlotDef> slots = fanSlots();
        return layout("create:splashing", "Bulk washing (Create)", 178, 72, slots, List.of(TIME),
                fanDecoration(slots, false));
    }

    private static EditorLayout haunting() {
        List<SlotDef> slots = fanSlots();
        return layout("create:haunting", "Bulk haunting (Create)", 178, 72, slots, List.of(TIME),
                fanDecoration(slots, true));
    }

    private static EditorLayout polishing() {
        List<SlotDef> slots = new ArrayList<>();
        slots.add(in(27, 29, true));
        slots.add(out(132, 29, true));
        return layout("create:sandpaper_polishing", "Sandpaper polishing (Create)", 177, 55, slots, List.of(),
                (graphics, left, top, partialTick, view) -> {
                    PoseStack pose = graphics.pose();
                    pose.pushPose();
                    pose.translate(left, top, 0);
                    slotFrames(graphics, slots, view);
                    shadow(graphics, 61, 21);
                    longArrow(graphics, 52, 32);
                    ItemStack paper = itemStack("create:sand_paper");
                    if (!paper.isEmpty()) {
                        pose.pushPose();
                        pose.translate(177 / 2 - 16, 0, 0);
                        pose.scale(2, 2, 1);
                        graphics.renderItem(paper, 0, 0);
                        pose.popPose();
                    }
                    pose.popPose();
                });
    }

    /**
     * Mechanical crafter grid, with the crafter/result column beside it. The
     * grid is 9x9: Create raises vanilla's 3x3 pattern cap that far (its own
     * recipes stop at 5x5). Empty rows and columns are trimmed when saving,
     * so a 2x2 recipe drawn anywhere in the grid saves as a 2x2 pattern.
     */
    private static EditorLayout mechanicalCrafting() {
        int grid = 9;
        int pitch = 19;
        int gridRight = PAD + (grid - 1) * pitch + 16;
        // Right column: crafter art on top, arrow, result slot. Kept clear of
        // the grid by the width of the crafter's shadow, and centred on it.
        int columnX = gridRight + 26;
        int columnY = (PAD - 1 + gridRight) / 2 - 36;
        List<SlotDef> slots = new ArrayList<>();
        for (int row = 0; row < grid; row++) {
            for (int col = 0; col < grid; col++) {
                slots.add(new SlotDef(PAD + col * pitch, PAD + row * pitch, "",
                        SlotFormat.STRING, false, false));
            }
        }
        slots.add(new SlotDef(columnX + 5, columnY + 56, "result", SlotFormat.ITEM, true, true));
        EditorLayout layout = new EditorLayout("create:mechanical_crafting", "Mechanical crafting (Create)",
                null, 0, 0, columnX + 40, gridRight + PAD, Kind.SHAPED, slots, List.of(MIRRORED));
        layout.gridSize = grid;
        layout.ownSlotArt = true;
        layout.decoration = (graphics, left, top, partialTick, view) -> {
            PoseStack pose = graphics.pose();
            pose.pushPose();
            pose.translate(left, top, 0);
            for (int i = 0; i < grid * grid; i++) {
                SlotDef slot = slots.get(i);
                slotFrame(graphics, slot.x(), slot.y(), false);
            }
            slotFrame(graphics, columnX + 5, columnY + 56, false);
            downArrow(graphics, columnX - 1, columnY + 34);
            crafter(graphics, columnX, columnY);
            int amount = 0;
            for (int i = 0; i < grid * grid; i++) {
                if (view.ref(i) != null) {
                    amount++;
                }
            }
            pose.pushPose();
            pose.translate(0, 0, 300);
            graphics.drawString(Minecraft.getInstance().font, String.valueOf(amount),
                    columnX + 13, columnY + 14, 0xFFFFFF);
            pose.popPose();
            pose.popPose();
        };
        return layout;
    }

    // ---- 2D widgets from create:textures/gui/jei/widgets.png ---------------

    private static void widget(GuiGraphics graphics, int x, int y, int u, int v, int w, int h) {
        graphics.blit(WIDGETS, x, y, u, v, w, h);
    }

    private static void slotFrame(GuiGraphics graphics, int x, int y, boolean chance) {
        widget(graphics, x - 1, y - 1, chance ? 20 : 0, chance ? 156 : 0, 18, 18);
    }

    private static void slotFrames(GuiGraphics graphics, List<SlotDef> slots, SlotView view) {
        for (int i = 0; i < slots.size(); i++) {
            SlotDef slot = slots.get(i);
            slotFrame(graphics, slot.x(), slot.y(), slot.chanceable() && view.chance(i) < 100);
        }
    }

    private static void arrow(GuiGraphics graphics, int x, int y) {
        widget(graphics, x, y, 19, 10, 42, 10);
    }

    private static void longArrow(GuiGraphics graphics, int x, int y) {
        widget(graphics, x, y, 19, 0, 71, 10);
    }

    private static void downArrow(GuiGraphics graphics, int x, int y) {
        widget(graphics, x, y, 0, 21, 18, 14);
    }

    private static void shadow(GuiGraphics graphics, int x, int y) {
        widget(graphics, x, y, 0, 56, 52, 11);
    }

    private static ItemStack itemStack(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl != null && BuiltInRegistries.ITEM.containsKey(rl)
                ? new ItemStack(BuiltInRegistries.ITEM.get(rl)) : ItemStack.EMPTY;
    }

    private static void renderItemIcon(GuiGraphics graphics, String id, int x, int y) {
        ItemStack stack = itemStack(id);
        if (!stack.isEmpty()) {
            graphics.renderItem(stack, x, y);
        }
    }

    private static BlockState blockOf(String ref) {
        if (ref == null || ref.startsWith("#")) {
            return null;
        }
        ItemStack stack = itemStack(ref);
        return stack.getItem() instanceof BlockItem blockItem ? blockItem.getBlock().defaultBlockState() : null;
    }

    // ---- animated machines (ports of Create's AnimatedKinetics classes) -----

    private static BlockState shaft(String axis) {
        return GuiBlocks.state("create:shaft", "axis=" + axis);
    }

    private static void mixer(GuiGraphics graphics, int x, int y) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 200);
        pose.mulPose(Axis.XP.rotationDegrees(ROT_X));
        pose.mulPose(Axis.YP.rotationDegrees(ROT_Y));
        int scale = 23;
        GuiBlocks.part("create:block/cogwheel_shaftless")
                .rotateBlock(0, GuiBlocks.angle() * 2, 0).scale(scale).render(graphics);
        GuiBlocks.block(GuiBlocks.state("create:mechanical_mixer")).scale(scale).render(graphics);
        float animation = ((Mth.sin(GuiBlocks.time() / 32f) + 1) / 5) + .5f;
        GuiBlocks.part("create:block/mechanical_mixer/pole")
                .atLocal(0, animation, 0).scale(scale).render(graphics);
        GuiBlocks.part("create:block/mechanical_mixer/head")
                .rotateBlock(0, GuiBlocks.angle() * 4, 0).atLocal(0, animation, 0).scale(scale).render(graphics);
        GuiBlocks.block(GuiBlocks.state("create:basin")).atLocal(0, 1.65, 0).scale(scale).render(graphics);
        pose.popPose();
    }

    private static void press(GuiGraphics graphics, int x, int y, boolean basin) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 200);
        pose.mulPose(Axis.XP.rotationDegrees(ROT_X));
        pose.mulPose(Axis.YP.rotationDegrees(ROT_Y));
        int scale = basin ? 23 : 24;
        GuiBlocks.block(shaft("z")).rotateBlock(0, 0, GuiBlocks.angle()).scale(scale).render(graphics);
        GuiBlocks.block(GuiBlocks.state("create:mechanical_press")).scale(scale).render(graphics);
        GuiBlocks.part("create:block/mechanical_press/head")
                .atLocal(0, -pressHeadOffset(), 0).scale(scale).render(graphics);
        if (basin) {
            GuiBlocks.block(GuiBlocks.state("create:basin")).atLocal(0, 1.65, 0).scale(scale).render(graphics);
        }
        pose.popPose();
    }

    private static float pressHeadOffset() {
        float cycle = GuiBlocks.time() % 30;
        if (cycle < 10) {
            float progress = cycle / 10;
            return -(progress * progress * progress);
        }
        if (cycle < 15) {
            return -1;
        }
        if (cycle < 20) {
            return -1 + (1 - ((20 - cycle) / 5));
        }
        return 0;
    }

    private static void crushingWheels(GuiGraphics graphics, int x, int y) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 100);
        pose.mulPose(Axis.YP.rotationDegrees(-22.5f));
        int scale = 22;
        BlockState wheel = GuiBlocks.state("create:crushing_wheel", "axis=x");
        GuiBlocks.block(wheel).rotateBlock(0, 90, -GuiBlocks.angle()).scale(scale).render(graphics);
        GuiBlocks.block(wheel).rotateBlock(0, 90, GuiBlocks.angle()).atLocal(2, 0, 0).scale(scale).render(graphics);
        pose.popPose();
    }

    private static void millstone(GuiGraphics graphics, int x, int y) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 100);
        shadow(graphics, -16, 13);
        pose.translate(-2, 18, 0);
        int scale = 22;
        GuiBlocks.part("create:block/millstone/inner")
                .rotateBlock(22.5, GuiBlocks.angle() * 2, 0).scale(scale).render(graphics);
        GuiBlocks.block(GuiBlocks.state("create:millstone")).rotateBlock(22.5, 22.5, 0).scale(scale).render(graphics);
        pose.popPose();
    }

    private static void saw(GuiGraphics graphics, int x, int y) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 200);
        pose.translate(2, 22, 0);
        pose.mulPose(Axis.XP.rotationDegrees(ROT_X));
        pose.mulPose(Axis.YP.rotationDegrees(ROT_Y + 90));
        int scale = 25;
        GuiBlocks.block(shaft("x")).rotateBlock(-GuiBlocks.angle(), 0, 0).scale(scale).render(graphics);
        GuiBlocks.block(GuiBlocks.state("create:mechanical_saw", "facing=up")).scale(scale).render(graphics);
        GuiBlocks.part("create:block/mechanical_saw/blade_vertical_active")
                .rotateBlock(0, -90, -90).scale(scale).render(graphics);
        pose.popPose();
    }

    private static void deployer(GuiGraphics graphics, int x, int y) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 100);
        pose.mulPose(Axis.XP.rotationDegrees(ROT_X));
        pose.mulPose(Axis.YP.rotationDegrees(ROT_Y));
        int scale = 20;
        GuiBlocks.block(shaft("z")).rotateBlock(0, 0, GuiBlocks.angle()).scale(scale).render(graphics);
        GuiBlocks.block(GuiBlocks.state("create:deployer", "facing=down", "axis_along_first=false"))
                .scale(scale).render(graphics);
        float cycle = GuiBlocks.time() % 30;
        float offset = cycle < 10 ? cycle / 10f : cycle < 20 ? (20 - cycle) / 10f : 0;
        pose.pushPose();
        pose.translate(0, offset * 17, 0);
        GuiBlocks.part("create:block/deployer/pole").rotateBlock(90, 0, 0).scale(scale).render(graphics);
        GuiBlocks.part("create:block/deployer/hand_holding").rotateBlock(90, 0, 0).scale(scale).render(graphics);
        pose.popPose();
        GuiBlocks.block(GuiBlocks.state("create:depot")).atLocal(0, 2, 0).scale(scale).render(graphics);
        pose.popPose();
    }

    private static void spout(GuiGraphics graphics, int x, int y, String fluidRef) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 100);
        pose.mulPose(Axis.XP.rotationDegrees(ROT_X));
        pose.mulPose(Axis.YP.rotationDegrees(ROT_Y));
        int scale = 20;
        GuiBlocks.block(GuiBlocks.state("create:spout")).scale(scale).render(graphics);
        float cycle = GuiBlocks.time() % 30;
        float squeeze = cycle < 20 ? Mth.sin((float) (cycle / 20f * Math.PI)) : 0;
        squeeze *= 20;
        pose.pushPose();
        GuiBlocks.part("create:block/spout/top").scale(scale).render(graphics);
        pose.translate(0, -3 * squeeze / 32f, 0);
        GuiBlocks.part("create:block/spout/middle").scale(scale).render(graphics);
        pose.translate(0, -3 * squeeze / 32f, 0);
        GuiBlocks.part("create:block/spout/bottom").scale(scale).render(graphics);
        pose.popPose();
        GuiBlocks.block(GuiBlocks.state("create:depot")).atLocal(0, 2, 0).scale(scale).render(graphics);

        String fluid = fluidId(fluidRef);
        if (fluid != null) {
            // Fluid inside the spout's tank...
            pose.pushPose();
            GuiBlocks.flip(pose);
            pose.scale(16, 16, 16);
            float from = 3f / 16f;
            float to = 17f / 16f;
            GuiBlocks.fluidBox(graphics, fluid, from, from, from, to, to, to);
            pose.popPose();
            // ...and the stream pouring onto the depot.
            float width = 1 / 128f * squeeze;
            pose.pushPose();
            pose.translate(scale / 2f, scale * 1.5f, scale / 2f);
            GuiBlocks.flip(pose);
            pose.scale(16, 16, 16);
            pose.translate(-0.5f, 0, -0.5f);
            from = -width / 2 + 0.5f;
            to = width / 2 + 0.5f;
            if (width > 0.001f) {
                GuiBlocks.fluidBox(graphics, fluid, from, 0, from, to, 2, to);
            }
            pose.popPose();
        }
        pose.popPose();
    }

    private static void drain(GuiGraphics graphics, int x, int y, String fluidRef) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 100);
        pose.mulPose(Axis.XP.rotationDegrees(ROT_X));
        pose.mulPose(Axis.YP.rotationDegrees(ROT_Y));
        int scale = 20;
        GuiBlocks.block(GuiBlocks.state("create:item_drain")).scale(scale).render(graphics);
        String fluid = fluidId(fluidRef);
        if (fluid != null) {
            GuiBlocks.flip(pose);
            pose.scale(scale, scale, scale);
            float from = 2 / 16f;
            float to = 1f - from;
            GuiBlocks.fluidBox(graphics, fluid, from, from, from, to, 3 / 4f, to);
        }
        pose.popPose();
    }

    private static void blazeBurner(GuiGraphics graphics, int x, int y, boolean seething) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 200);
        pose.mulPose(Axis.XP.rotationDegrees(ROT_X));
        pose.mulPose(Axis.YP.rotationDegrees(ROT_Y));
        int scale = 23;
        float offset = (Mth.sin(GuiBlocks.time() / 16f) + 0.5f) / 16f;
        GuiBlocks.block(GuiBlocks.state("create:blaze_burner")).atLocal(0, 1.65, 0).scale(scale).render(graphics);
        String blaze = seething ? "create:block/blaze_burner/blaze/super" : "create:block/blaze_burner/blaze/active";
        String rods = seething ? "create:block/blaze_burner/superheated_rods_large" : "create:block/blaze_burner/rods_large";
        GuiBlocks.part(blaze).atLocal(1, 1.8, 1).rotate(0, 180, 0).scale(scale).render(graphics);
        GuiBlocks.part(rods).atLocal(1, 1.7 + offset, 1).rotate(0, 180, 0).scale(scale).render(graphics);
        GuiBlocks.part("create:block/blaze_burner/flame").atLocal(0, 1.8, 0).scale(scale).render(graphics);
        pose.popPose();
    }

    private static void fan(GuiGraphics graphics, int x, int y, boolean soulFire) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 100);
        pose.mulPose(Axis.XP.rotationDegrees(-12.5f));
        pose.mulPose(Axis.YP.rotationDegrees(22.5f));
        int scale = 24;
        GuiBlocks.part("create:block/encased_fan/propeller")
                .rotateBlock(180, 0, GuiBlocks.angle() * 16).scale(scale).render(graphics);
        GuiBlocks.block(GuiBlocks.state("create:encased_fan")).rotateBlock(0, 180, 0).scale(scale).render(graphics);
        if (soulFire) {
            GuiBlocks.block(Blocks.SOUL_FIRE.defaultBlockState()).atLocal(0, 0, 2).scale(scale).render(graphics);
        } else {
            pose.pushPose();
            pose.scale(scale, scale, scale);
            pose.translate(0, 0, 2);
            GuiBlocks.flip(pose);
            GuiBlocks.fluidBox(graphics, "minecraft:water", 0, 0, 0, 1, 1, 1);
            pose.popPose();
        }
        pose.popPose();
    }

    private static void crafter(GuiGraphics graphics, int x, int y) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 100);
        shadow(graphics, -16, 13);
        pose.translate(3, 16, 0);
        pose.mulPose(Axis.XP.rotationDegrees(-12.5f));
        pose.mulPose(Axis.YP.rotationDegrees(-22.5f));
        int scale = 22;
        GuiBlocks.part("create:block/cogwheel_shaftless")
                .rotateBlock(90, 0, GuiBlocks.angle()).scale(scale).render(graphics);
        GuiBlocks.block(GuiBlocks.state("create:mechanical_crafter")).rotateBlock(0, 180, 0).scale(scale).render(graphics);
        pose.popPose();
    }

    /** Fluid id for a slot ref: plain ids as-is, "#tag" resolved to the tag's first fluid, else null. */
    private static String fluidId(String ref) {
        if (ref == null) {
            return null;
        }
        if (!ref.startsWith("#")) {
            return ref;
        }
        ResourceLocation tagId = ResourceLocation.tryParse(ref.substring(1));
        if (tagId == null) {
            return null;
        }
        return BuiltInRegistries.FLUID.getTag(TagKey.create(Registries.FLUID, tagId))
                .flatMap(set -> set.stream().findFirst())
                .map(holder -> BuiltInRegistries.FLUID.getKey(holder.value()).toString())
                .orElse(null);
    }
}
