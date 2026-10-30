package net.ledok.datarewriter.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeSerializer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static net.ledok.datarewriter.client.gui.EditorLayout.FieldDef;
import static net.ledok.datarewriter.client.gui.EditorLayout.FieldType;
import static net.ledok.datarewriter.client.gui.EditorLayout.Kind;
import static net.ledok.datarewriter.client.gui.EditorLayout.SlotDef;
import static net.ledok.datarewriter.client.gui.EditorLayout.SlotFormat;

/**
 * Bundled layouts for Modern Industrialization (verified against its 1.21.1
 * sources). Every machine recipe type shares one schema
 * ({@code MachineRecipe.codec}):
 * <ul>
 * <li>{@code eu} — EU/t, which also decides the machine tier that can run it;
 * {@code duration} — ticks.</li>
 * <li>{@code item_inputs[]} — {"item"|"tag", "amount", "probability"};
 * probability 0 = required but never consumed.</li>
 * <li>{@code fluid_inputs[]} — {"fluid"|"tag", "amount" (mB), "probability"}.</li>
 * <li>{@code item_outputs[]} — {"item", "amount", "probability"};
 * {@code fluid_outputs[]} — {"fluid", "amount", "probability"}.</li>
 * </ul>
 * Slot positions are the machines' own (SingleBlockCraftingMachines and the
 * multiblocks' recipe-viewer slots in MultiblockMachines), drawn on a card
 * panel with MI's progress bar. The editor-only "Tier" field picks the
 * machine tier the recipe is meant for (bronze/steel steam, LV-superconductor
 * casings, EBF coils): an empty EU/t takes that tier's maximum, and a higher
 * EU/t is refused. {@code process_conditions} and output {@code components}
 * stay JSON-only. Machine types added through MI's KubeJS integration get a
 * generic layout. {@code forge_hammer} is a separate, simpler recipe.
 */
final class ModernIndustrializationLayouts {
    private static final String NS = "modern_industrialization";
    private static final String TIER = "tier";

    /** Highest EU/t each tier runs: steam machine tiers, casing/hatch voltages, EBF coils. */
    private static final Map<String, Long> TIER_MAX_EU = new LinkedHashMap<>();

    static {
        TIER_MAX_EU.put("bronze", 2L);
        TIER_MAX_EU.put("steel", 4L);
        TIER_MAX_EU.put("lv", 32L);
        TIER_MAX_EU.put("mv", 128L);
        TIER_MAX_EU.put("hv", 1024L);
        TIER_MAX_EU.put("ev", 8192L);
        TIER_MAX_EU.put("superconductor", (long) Integer.MAX_VALUE);
        TIER_MAX_EU.put("cupronickel", 32L);
        TIER_MAX_EU.put("kanthal", 128L);
    }

    private static final List<String> MACHINE_TIERS = List.of("bronze", "steel", "lv", "mv", "hv", "ev", "superconductor");
    private static final List<String> MULTIBLOCK_TIERS = List.of("lv", "mv", "hv", "ev", "superconductor");
    private static final List<String> BLAST_FURNACE_TIERS = List.of("bronze", "steel", "cupronickel", "kanthal");
    private static final List<String> STEAM_TIERS = List.of("bronze", "steel");

    /** Which machines can run a type, for the tier readout. */
    private enum Power {
        /** Single block with bronze, steel and electric versions. */
        BRONZE_UP,
        /** Single block with steel and electric versions. */
        STEEL_UP,
        /** Electric single block only (32 EU/t + upgrades). */
        ELECTRIC,
        /** Electric multiblock (128 EU/t + upgrades). */
        MULTIBLOCK,
        /** Steam multiblock: 2 EU/t with bronze hatches, 4 with steel. */
        STEAM_MULTIBLOCK,
        /** Steam blast furnace up to 4 EU/t, then the EBF by coil tier. */
        BLAST_FURNACE,
        /** Steam quarry up to 4 EU/t, then the electric quarry. */
        QUARRY,
        /** Fusion reactor: no EU/t limit. */
        UNLIMITED,
        /** Machine types from MI's KubeJS integration: casing voltage only. */
        CUSTOM
    }

    /** One machine recipe type: its tier family and slot positions. */
    private static final class Machine {
        final String path;
        final String name;
        final Power power;
        final int defaultEu;
        String bar = "arrow";
        int barX = 77;
        int barY = 33;
        final List<int[]> itemIn = new ArrayList<>();
        final List<int[]> fluidIn = new ArrayList<>();
        final List<int[]> itemOut = new ArrayList<>();
        final List<int[]> fluidOut = new ArrayList<>();

        Machine(String path, String name, Power power, int defaultEu) {
            this.path = path;
            this.name = name;
            this.power = power;
            this.defaultEu = defaultEu;
        }

        Machine bar(String type, int x, int y) {
            bar = type;
            barX = x;
            barY = y;
            return this;
        }

        Machine itemIn(int x, int y, int columns, int rows) {
            grid(itemIn, x, y, columns, rows);
            return this;
        }

        Machine fluidIn(int x, int y, int columns, int rows) {
            grid(fluidIn, x, y, columns, rows);
            return this;
        }

        Machine itemOut(int x, int y, int columns, int rows) {
            grid(itemOut, x, y, columns, rows);
            return this;
        }

        Machine fluidOut(int x, int y, int columns, int rows) {
            grid(fluidOut, x, y, columns, rows);
            return this;
        }

        /** MI's SlotPositions.addSlots: rows of {@code columns} slots, 18 px apart. */
        private static void grid(List<int[]> into, int x, int y, int columns, int rows) {
            for (int row = 0; row < rows; row++) {
                for (int col = 0; col < columns; col++) {
                    into.add(new int[]{x + col * 18, y + row * 18});
                }
            }
        }
    }

    private static List<Machine> machines() {
        return List.of(
                // Single block machines: SingleBlockCraftingMachines.init()
                new Machine("assembler", "Assembler", Power.ELECTRIC, 8).bar("circuit", 105, 45)
                        .itemIn(42, 27, 3, 3).fluidIn(98, 27, 2, 1).itemOut(139, 27, 1, 3),
                new Machine("centrifuge", "Centrifuge", Power.ELECTRIC, 8).bar("centrifuge", 65, 33)
                        .itemIn(42, 27, 1, 1).fluidIn(42, 45, 1, 1).itemOut(93, 27, 2, 2).fluidOut(131, 27, 2, 2),
                new Machine("chemical_reactor", "Chemical Reactor", Power.ELECTRIC, 8).bar("triple_arrow", 88, 35)
                        .itemIn(30, 27, 3, 1).fluidIn(30, 47, 3, 1).itemOut(116, 27, 3, 1).fluidOut(116, 47, 3, 1),
                new Machine("compressor", "Compressor", Power.BRONZE_UP, 2).bar("compress", 77, 34)
                        .itemIn(56, 35, 1, 1).itemOut(102, 35, 1, 1),
                new Machine("cutting_machine", "Cutting Machine", Power.BRONZE_UP, 2).bar("slice", 88, 31)
                        .itemIn(60, 35, 1, 1).fluidIn(40, 35, 1, 1).itemOut(120, 35, 1, 1),
                new Machine("distillery", "Distillery", Power.ELECTRIC, 8)
                        .fluidIn(56, 35, 1, 1).fluidOut(102, 35, 1, 1),
                new Machine("electrolyzer", "Electrolyzer", Power.ELECTRIC, 8).bar("arrow", 66, 35)
                        .itemIn(42, 27, 1, 1).fluidIn(42, 47, 1, 1).itemOut(93, 27, 2, 2).fluidOut(131, 27, 2, 2),
                new Machine("furnace", "Furnace", Power.BRONZE_UP, 2)
                        .itemIn(56, 35, 1, 1).itemOut(102, 35, 1, 1),
                new Machine("macerator", "Macerator", Power.BRONZE_UP, 2).bar("macerate", 77, 33)
                        .itemIn(56, 35, 1, 1).itemOut(102, 27, 2, 2),
                new Machine("mixer", "Mixer", Power.BRONZE_UP, 2).bar("arrow", 103, 33)
                        .itemIn(62, 27, 2, 2).fluidIn(42, 27, 1, 2).itemOut(129, 27, 1, 2).fluidOut(149, 27, 1, 2),
                new Machine("packer", "Packer", Power.STEEL_UP, 2)
                        .itemIn(56, 18, 1, 3).itemOut(102, 36, 1, 1),
                new Machine("polarizer", "Polarizer", Power.ELECTRIC, 8).bar("magnet", 77, 30)
                        .itemIn(56, 23, 1, 2).itemOut(102, 32, 1, 1),
                new Machine("unpacker", "Unpacker", Power.STEEL_UP, 2)
                        .itemIn(56, 36, 1, 1).itemOut(102, 27, 1, 2),
                new Machine("wiremill", "Wiremill", Power.STEEL_UP, 2).bar("wiremill", 77, 34)
                        .itemIn(56, 35, 1, 1).itemOut(102, 35, 1, 1),
                // Multiblocks: recipe-viewer slots from MultiblockMachines.clientInit()
                new Machine("blast_furnace", "Blast Furnace", Power.BLAST_FURNACE, 32)
                        .itemIn(56, 35, 1, 2).fluidIn(36, 35, 1, 1).itemOut(102, 35, 1, 1).fluidOut(122, 35, 1, 1),
                new Machine("coke_oven", "Coke Oven", Power.STEAM_MULTIBLOCK, 2)
                        .itemIn(56, 35, 1, 1).itemOut(102, 35, 1, 1).fluidOut(102, 53, 1, 1),
                new Machine("distillation_tower", "Distillation Tower", Power.MULTIBLOCK, 32)
                        // MI shows 8 outputs in one row; wrapped to 4x2 to fit the panel.
                        .fluidIn(56, 35, 1, 1).fluidOut(98, 26, 4, 2),
                new Machine("fusion_reactor", "Fusion Reactor", Power.UNLIMITED, 8192).bar("arrow", 66, 33)
                        .fluidIn(26, 35, 2, 1).fluidOut(92, 35, 3, 1),
                new Machine("heat_exchanger", "Heat Exchanger", Power.MULTIBLOCK, 2).bar("arrow", 77, 42)
                        .itemIn(36, 35, 1, 1).fluidIn(56, 35, 1, 2).itemOut(122, 35, 1, 1).fluidOut(102, 35, 1, 2),
                new Machine("implosion_compressor", "Implosion Compressor", Power.MULTIBLOCK, 32).bar("compress", 77, 42)
                        .itemIn(36, 35, 2, 2).itemOut(102, 42, 1, 1),
                new Machine("oil_drilling_rig", "Oil Drilling Rig", Power.MULTIBLOCK, 32)
                        .itemIn(36, 35, 1, 1).fluidOut(122, 35, 1, 1),
                new Machine("pressurizer", "Pressurizer", Power.MULTIBLOCK, 32)
                        .itemIn(38, 35, 1, 1).fluidIn(56, 35, 1, 1).fluidOut(102, 35, 1, 1),
                new Machine("quarry", "Quarry", Power.QUARRY, 4)
                        // MI's 4x4 output grid, nudged left to fit the panel.
                        .itemIn(56, 35, 1, 1).itemOut(98, 26, 4, 4),
                new Machine("vacuum_freezer", "Vacuum Freezer", Power.MULTIBLOCK, 32)
                        .itemIn(56, 35, 1, 2).fluidIn(36, 35, 1, 2).itemOut(102, 35, 1, 1).fluidOut(122, 35, 1, 1));
    }

    /** Machine types from MI's KubeJS integration: 4 items + 2 fluids each way. */
    private static Machine custom(String path) {
        return new Machine(path, prettify(path), Power.CUSTOM, 8)
                .bar("arrow", 77, 35)
                .fluidIn(18, 27, 1, 2).itemIn(38, 27, 2, 2).itemOut(102, 27, 2, 2).fluidOut(142, 27, 1, 2);
    }

    private static final Map<ResourceLocation, Boolean> TEXTURE_PRESENT = new HashMap<>();

    private ModernIndustrializationLayouts() {
    }

    static List<EditorLayout> forInstalledTypes() {
        List<EditorLayout> layouts = new ArrayList<>();
        Set<String> known = new HashSet<>();
        for (Machine machine : machines()) {
            known.add(machine.path);
            if (typeExists(machine.path)) {
                layouts.add(machineLayout(machine));
            }
        }
        known.add("forge_hammer");
        if (typeExists("forge_hammer")) {
            layouts.add(forgeHammer());
        }
        // MI's KubeJS integration registers extra machine types under MI's own
        // namespace; recognized by their serializer class (no compile dependency).
        for (Map.Entry<net.minecraft.resources.ResourceKey<RecipeSerializer<?>>, RecipeSerializer<?>> entry
                : BuiltInRegistries.RECIPE_SERIALIZER.entrySet()) {
            ResourceLocation id = entry.getKey().location();
            if (id.getNamespace().equals(NS) && !known.contains(id.getPath())
                    && entry.getValue().getClass().getSimpleName().endsWith("MachineRecipeType")) {
                layouts.add(machineLayout(custom(id.getPath())));
            }
        }
        return layouts;
    }

    private static boolean typeExists(String path) {
        return BuiltInRegistries.RECIPE_SERIALIZER.containsKey(ResourceLocation.fromNamespaceAndPath(NS, path));
    }

    /** {"item"|"tag", "amount", "probability"} — also the fluid shapes, with {@code idKey} "fluid". */
    private static EditorLayout.SlotValueBuilder entry(String idKey) {
        return (ref, amount, chance) -> {
            JsonObject obj = new JsonObject();
            if (ref.startsWith("#")) {
                obj.addProperty("tag", ref.substring(1));
            } else {
                obj.addProperty(idKey, ref);
            }
            obj.addProperty("amount", Math.max(1, amount));
            if (chance < 1f) {
                obj.addProperty("probability", chance);
            }
            return obj;
        };
    }

    private static EditorLayout machineLayout(Machine machine) {
        List<SlotDef> slots = new ArrayList<>();
        for (int[] pos : machine.itemIn) {
            slots.add(new SlotDef(pos[0], pos[1], "item_inputs[]", SlotFormat.COUNTED_INGREDIENT,
                    false, false, true, entry("item")));
        }
        for (int[] pos : machine.fluidIn) {
            slots.add(new SlotDef(pos[0], pos[1], "fluid_inputs[]", SlotFormat.FLUID_AMOUNT,
                    false, false, true, true, entry("fluid")));
        }
        for (int[] pos : machine.itemOut) {
            slots.add(new SlotDef(pos[0], pos[1], "item_outputs[]", SlotFormat.ITEM,
                    true, false, true, entry("item")));
        }
        for (int[] pos : machine.fluidOut) {
            slots.add(new SlotDef(pos[0], pos[1], "fluid_outputs[]", SlotFormat.FLUID_AMOUNT,
                    true, false, true, entry("fluid")));
        }

        // Card panel: slots, two info lines under them, then the inventory.
        int slotsBottom = 0;
        for (SlotDef slot : slots) {
            slotsBottom = Math.max(slotsBottom, slot.y() + 17);
        }
        int infoY = slotsBottom + 5;
        int inventoryY = Math.max(84, infoY + 24);

        List<String> tiers = switch (machine.power) {
            case BRONZE_UP, STEEL_UP, ELECTRIC, CUSTOM -> MACHINE_TIERS;
            case MULTIBLOCK, UNLIMITED -> MULTIBLOCK_TIERS;
            case STEAM_MULTIBLOCK -> STEAM_TIERS;
            case BLAST_FURNACE -> BLAST_FURNACE_TIERS;
            case QUARRY -> List.of("bronze", "steel", "mv", "hv", "ev");
        };
        List<FieldDef> fields = List.of(
                new FieldDef("eu", "EU/t", FieldType.INT, String.valueOf(machine.defaultEu), false,
                        List.of("2", "4", "8", "16", "32", "128", "1024", "8192")),
                new FieldDef("duration", "Time (ticks)", FieldType.INT, "200", true),
                new FieldDef(TIER, "Tier", FieldType.STRING, "", false, tiers, false));

        EditorLayout layout = new EditorLayout(NS + ":" + machine.path, machine.name + " (Modern Industrialization)",
                null, 0, 0, 176, inventoryY + 82, inventoryY, Kind.SLOTS, slots, fields);
        layout.minChance = 0;
        layout.ownSlotArt = true;
        ResourceLocation bar = ResourceLocation.fromNamespaceAndPath(NS, "textures/gui/progress_bar/" + machine.bar + ".png");
        layout.decoration = (graphics, left, top, partialTick, view) -> {
            for (SlotDef slot : slots) {
                slotFrame(graphics, left + slot.x(), top + slot.y(), slot.format().fluid());
            }
            progressBar(graphics, bar, left + machine.barX, top + machine.barY);
            renderInfo(graphics, left + 8, top + infoY, machine.power, view);
        };
        layout.finisher = ModernIndustrializationLayouts::finish;
        return layout;
    }

    /** Vanilla-style slot socket; fluid slots get a bluish well. */
    private static void slotFrame(GuiGraphics graphics, int x, int y, boolean fluid) {
        graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFFFFFFFF);
        graphics.fill(x - 1, y - 1, x + 16, y + 16, 0xFF373737);
        graphics.fill(x, y, x + 16, y + 16, fluid ? 0xFF7A8796 : 0xFF8B8B8B);
    }

    /** MI's 20x20 progress bar (empty half, then a looping fill), or a plain arrow if the texture is missing. */
    private static void progressBar(GuiGraphics graphics, ResourceLocation texture, int x, int y) {
        boolean present = TEXTURE_PRESENT.computeIfAbsent(texture,
                id -> Minecraft.getInstance().getResourceManager().getResource(id).isPresent());
        if (!present) {
            graphics.drawString(Minecraft.getInstance().font, "->", x + 4, y + 6, 0x404040, false);
            return;
        }
        graphics.blit(texture, x, y, 0, 0, 20, 20, 20, 40);
        int filled = (int) (Util.getMillis() % 2000L * 20 / 2000L);
        if (filled > 0) {
            graphics.blit(texture, x, y, 0, 20, filled, 20, 20, 40);
        }
    }

    /** Two lines under the slots: EU/t, time and total EU; then which machine tier runs it. */
    private static void renderInfo(GuiGraphics graphics, int x, int y, Power power, EditorLayout.SlotView view) {
        Font font = Minecraft.getInstance().font;
        String tier = view.field(TIER).trim().toLowerCase(Locale.ROOT);
        Long tierMax = TIER_MAX_EU.get(tier);
        long eu = parseLong(view.field("eu"));
        if (eu <= 0 && tierMax != null) {
            eu = Math.min(tierMax, Integer.MAX_VALUE);
        }
        long ticks = parseLong(view.field("duration"));
        StringBuilder first = new StringBuilder();
        first.append(eu > 0 ? eu + " EU/t" : "? EU/t");
        if (ticks > 0) {
            first.append(" · ").append(seconds(ticks));
            if (eu > 0) {
                first.append(" · ").append(eu * ticks).append(" EU");
            }
        }
        graphics.drawString(font, first.toString(), x, y, 0x404040, false);

        String second;
        int color = 0x404040;
        if (!tier.isEmpty() && tierMax == null) {
            second = "Unknown tier '" + tier + "'";
            color = 0xAA0000;
        } else if (tierMax != null && eu > tierMax) {
            second = "Above " + tierName(tier) + " (max " + tierMax + " EU/t)";
            color = 0xAA0000;
        } else if (eu > 0) {
            second = runsOn(power, eu);
            if (second.startsWith("!")) {
                second = second.substring(1);
                color = 0xAA0000;
            }
        } else {
            second = "Set EU/t or pick a Tier";
        }
        graphics.drawString(font, second, x, y + 10, color, false);
    }

    /** The weakest machine setup that runs a recipe of {@code eu} EU/t ("!" prefix = none can). */
    private static String runsOn(Power power, long eu) {
        return switch (power) {
            case BRONZE_UP, STEEL_UP, ELECTRIC, CUSTOM -> {
                if (eu <= 2 && (power == Power.BRONZE_UP || power == Power.CUSTOM)) {
                    yield "Runs on: bronze steam and up";
                }
                if (eu <= 4 && power != Power.ELECTRIC) {
                    yield "Runs on: steel steam and up";
                }
                yield electric("", 32, eu);
            }
            case MULTIBLOCK -> electric("hatches", 128, eu);
            case STEAM_MULTIBLOCK -> eu <= 2 ? "Runs on: bronze hatches"
                    : eu <= 4 ? "Runs on: steel hatches" : "!Steam multiblocks stop at 4 EU/t";
            case BLAST_FURNACE -> eu <= 4 ? "Runs on: steam blast furnace, EBF"
                    : eu <= 32 ? "Runs on: EBF, cupronickel coils"
                    : eu <= 128 ? "Runs on: EBF, kanthal coils"
                    : "!No built-in EBF coil (kanthal: 128)";
            case QUARRY -> eu <= 4 ? "Runs on: steam or electric quarry" : electric("quarry", 128, eu);
            case UNLIMITED -> "Runs on: fusion reactor (no limit)";
        };
    }

    /** Electric machines: base max EU/t, beyond that a casing/hatch voltage plus upgrades. */
    private static String electric(String what, long base, long eu) {
        String voltage = eu <= 32 ? "LV" : eu <= 128 ? "MV" : eu <= 1024 ? "HV" : eu <= 8192 ? "EV" : "SC";
        String suffix = what.isEmpty() ? (eu <= 32 ? " electric" : " casing") : " " + what;
        return "Runs on: " + voltage + suffix + (eu > base ? ", +" + (eu - base) + " EU/t upgr." : "");
    }

    /** Fills an empty EU/t from the Tier, and refuses an EU/t the chosen tier can't run. */
    private static void finish(JsonObject recipe, EditorLayout.SlotView view) {
        String tier = view.field(TIER).trim().toLowerCase(Locale.ROOT);
        Long tierMax = null;
        if (!tier.isEmpty()) {
            tierMax = TIER_MAX_EU.get(tier);
            if (tierMax == null) {
                throw new IllegalArgumentException("unknown tier '" + tier + "' — use one of "
                        + String.join(", ", TIER_MAX_EU.keySet()) + " (or leave it empty)");
            }
        }
        if (!recipe.has("eu")) {
            if (tierMax == null) {
                throw new IllegalArgumentException("set 'EU/t' (or pick a Tier to use its maximum)");
            }
            recipe.addProperty("eu", (int) Math.min(tierMax, Integer.MAX_VALUE));
        }
        long eu = recipe.get("eu").getAsLong();
        if (eu < 1 || eu > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("'EU/t' must be at least 1");
        }
        if (tierMax != null && eu > tierMax) {
            throw new IllegalArgumentException("EU/t " + eu + " is above " + tierName(tier)
                    + " (max " + tierMax + ") — lower EU/t or pick a higher Tier");
        }
        if (recipe.get("duration").getAsLong() < 1) {
            throw new IllegalArgumentException("'Time (ticks)' must be at least 1");
        }
        if (!nonEmpty(recipe, "item_inputs") && !nonEmpty(recipe, "fluid_inputs")) {
            throw new IllegalArgumentException("add at least one item or fluid input");
        }
        if (!nonEmpty(recipe, "item_outputs") && !nonEmpty(recipe, "fluid_outputs")) {
            throw new IllegalArgumentException("add at least one item or fluid output");
        }
    }

    /**
     * Forge hammer on its own GUI: ingredient (with a separate count field)
     * -> result stack; damage = hammer durability used (0 = works by hand).
     * The tool slot is machine gear, not recipe data.
     */
    private static EditorLayout forgeHammer() {
        List<SlotDef> slots = List.of(
                new SlotDef(34, 33, "ingredient", SlotFormat.INGREDIENT, false, true),
                new SlotDef(143, 33, "result", SlotFormat.ITEM, true, true));
        return new EditorLayout(NS + ":forge_hammer", "Forge Hammer (Modern Industrialization)",
                ResourceLocation.fromNamespaceAndPath(NS, "textures/gui/container/forge_hammer.png"),
                0, 0, 176, 166, 84, Kind.SLOTS, slots,
                List.of(new FieldDef("count", "Input count", FieldType.INT, "1", false),
                        new FieldDef("damage", "Hammer damage", FieldType.INT, "0", false)));
    }

    private static boolean nonEmpty(JsonObject recipe, String key) {
        return recipe.get(key) instanceof JsonArray array && !array.isEmpty();
    }

    private static long parseLong(String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String seconds(long ticks) {
        return ticks % 20 == 0 ? ticks / 20 + " s" : String.format(Locale.ROOT, "%.2f s", ticks / 20.0);
    }

    private static String tierName(String tier) {
        return tier.length() <= 2 ? tier.toUpperCase(Locale.ROOT)
                : Character.toUpperCase(tier.charAt(0)) + tier.substring(1);
    }

    private static String prettify(String path) {
        String spaced = path.replace('_', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
