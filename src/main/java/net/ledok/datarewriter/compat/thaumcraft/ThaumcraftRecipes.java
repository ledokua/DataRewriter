package net.ledok.datarewriter.compat.thaumcraft;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.ledok.datarewriter.Datarewriter;
import net.ledok.datarewriter.DisabledItems;
import net.ledok.datarewriter.LootRewriter;
import net.ledok.datarewriter.config.ItemMatch;
import net.ledok.datarewriter.config.RewriteConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import thaumcraft.crafting.CompoundRecipe;
import thaumcraft.crafting.CraftingRecipeRef;
import thaumcraft.crafting.CrucibleRecipe;
import thaumcraft.crafting.InfusionEnchantmentRecipe;
import thaumcraft.crafting.InfusionRecipe;
import thaumcraft.crafting.ShapedArcaneRecipe;
import thaumcraft.crafting.ShapelessArcaneRecipe;
import thaumcraft.crafting.TcIngredient;
import thaumcraft.crafting.TcRecipe;
import thaumcraft.crafting.TcRecipes;
import thaumcraft.crafting.TcStack;
import thaumcraft.research.ResearchCategories;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Rewrites Thaumcraft's recipe map ({@code TcRecipes}): every apply restores the recipes Thaumcraft
 * registered itself, then runs the config — removals, modifications, additions, then the generic recipe
 * rules that reach these recipes too (output/input/type removals, replace_ingredients, items.disable).
 * The map is static and identical on both sides, so the server sends clients the difference
 * ({@link Diff}); in singleplayer client and server share it.
 */
final class ThaumcraftRecipes {
    private static @Nullable Map<String, TcRecipe> map;
    private static @Nullable Map<String, TcRecipe> originals;

    private ThaumcraftRecipes() {
    }

    /** What clients need to rebuild the server's map from their own originals. */
    record Diff(List<String> removed, List<String> changed) {
        static final Diff NONE = new Diff(List.of(), List.of());
    }

    record Stats(int removed, int modified, int added, int changed, int failed) {
    }

    /** Thaumcraft's own map, by reflection (it has no remove); the originals are copied on first use. */
    @SuppressWarnings("unchecked")
    private static synchronized Map<String, TcRecipe> map() {
        if (map == null) {
            try {
                Field field = TcRecipes.class.getDeclaredField("RECIPES");
                field.setAccessible(true);
                map = (Map<String, TcRecipe>) field.get(null);
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new IllegalStateException("Thaumcraft's recipe map (TcRecipes.RECIPES) is not accessible", e);
            }
            originals = new LinkedHashMap<>(map);
        }
        return map;
    }

    private static Map<String, TcRecipe> originals() {
        map();
        return originals;
    }

    /** Puts Thaumcraft's own recipes back (a client joining another server, or before re-applying). */
    static synchronized void restore() {
        install(originals());
    }

    /** The one place that writes Thaumcraft's map: replaced as a whole, under this class's lock. */
    private static void install(Map<String, TcRecipe> recipes) {
        Map<String, TcRecipe> live = map();
        live.clear();
        live.putAll(recipes);
    }

    static synchronized Stats apply(RewriteConfig config) {
        Map<String, TcRecipe> work = new LinkedHashMap<>(originals());
        int removed = 0;
        int modified = 0;
        int added = 0;
        int failed = 0;
        Set<String> addedKeys = new java.util.HashSet<>();
        for (RewriteConfig.ThaumcraftRule rule : config.thaumcraftRules()) {
            try {
                switch (rule) {
                    case RewriteConfig.ThaumcraftRemoval removal -> {
                        int before = work.size();
                        work.values().removeIf(recipe -> !addedKeys.contains(recipe.key()) && matches(removal, recipe));
                        removed += before - work.size();
                    }
                    case RewriteConfig.ThaumcraftModification modification -> {
                        Pattern key = glob(modification.id());
                        boolean any = false;
                        for (Map.Entry<String, TcRecipe> entry : work.entrySet()) {
                            if (!key.matcher(entry.getKey()).matches()) {
                                continue;
                            }
                            any = true;
                            if (!TcJson.editable(entry.getValue())) {
                                warn(rule, "can't modify " + entry.getKey() + " (a " + TcJson.type(entry.getValue())
                                        + " recipe — only remove those)");
                                continue;
                            }
                            JsonObject json = TcJson.encode(entry.getValue());
                            deepMerge(json, modification.merge());
                            json.addProperty("id", entry.getKey());
                            // Not strict: Thaumcraft's own recipes may name items the port doesn't have.
                            entry.setValue(TcJson.decode(json, false));
                            modified++;
                        }
                        if (!any) {
                            warn(rule, "modify '" + modification.id() + "' matches no recipe");
                        }
                    }
                    case RewriteConfig.ThaumcraftAddition addition -> {
                        TcRecipe recipe = TcJson.decode(addition.json(), true);
                        if (!recipe.key().isEmpty() && !researchExists(recipe)) {
                            warn(rule, recipe.key() + ": research '" + research(recipe)
                                    + "' doesn't exist, so nobody can craft it");
                        }
                        work.put(recipe.key(), recipe);
                        addedKeys.add(recipe.key());
                        added++;
                    }
                }
            } catch (TcJson.TcJsonException e) {
                failed++;
                Datarewriter.LOGGER.error("[{}] thaumcraft {}: {} — skipped", rule.source(), describe(rule), e.getMessage());
            }
        }

        // The generic recipe rules: recipes.remove by output/input/type (never the recipes added above).
        for (RewriteConfig.RemovalRule rule : config.removals()) {
            if (!rule.needsParsedRecipe() || rule.id() != null
                    || (rule.mod() != null && !rule.mod().equals("thaumcraft"))) {
                continue;
            }
            int before = work.size();
            work.values().removeIf(recipe -> !addedKeys.contains(recipe.key()) && matchesGeneric(rule, recipe));
            removed += before - work.size();
        }
        int changed = 0;
        for (RewriteConfig.IngredientReplacement rule : config.ingredientReplacements()) {
            changed += rewriteIngredients(work, ingredient -> replace(ingredient, rule));
        }
        if (DisabledItems.active()) {
            changed += applyDisabledItems(work);
        }

        install(work);
        return new Stats(removed, modified, added, changed, failed);
    }

    /** The difference between the live map and Thaumcraft's own recipes, for the client sync. */
    static synchronized Diff diff() {
        Map<String, TcRecipe> live = map();
        List<String> removed = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (String key : originals().keySet()) {
            if (!live.containsKey(key)) {
                removed.add(key);
            }
        }
        for (Map.Entry<String, TcRecipe> entry : live.entrySet()) {
            if (originals().get(entry.getKey()) != entry.getValue()) {
                changed.add(TcJson.encode(entry.getValue()).toString());
            }
        }
        return removed.isEmpty() && changed.isEmpty() ? Diff.NONE : new Diff(removed, changed);
    }

    /** Client: the server's map, rebuilt from this client's originals. */
    static synchronized void applyDiff(Diff diff) {
        Map<String, TcRecipe> work = new LinkedHashMap<>(originals());
        diff.removed().forEach(work::remove);
        for (String text : diff.changed()) {
            try {
                TcRecipe recipe = TcJson.decode(JsonParser.parseString(text).getAsJsonObject(), false);
                work.put(recipe.key(), recipe);
            } catch (RuntimeException e) {
                Datarewriter.LOGGER.warn("Thaumcraft recipe from the server couldn't be read: {}", e.getMessage());
            }
        }
        install(work);
    }

    static synchronized @Nullable TcRecipe get(String key) {
        return map().get(key);
    }

    /** Validates an editor save (decode with every check) without applying it; returns the recipe. */
    static TcRecipe validate(JsonObject json) {
        return TcJson.decode(json, true);
    }

    // ---- matching ----

    private static boolean matches(RewriteConfig.ThaumcraftRemoval rule, TcRecipe recipe) {
        if (rule.id() != null && !glob(rule.id()).matcher(recipe.key()).matches()) {
            return false;
        }
        if (rule.research() != null && !rule.research().equals(research(recipe))) {
            return false;
        }
        if (rule.type() != null && !TcJson.normalizeType(rule.type()).equals(TcJson.type(recipe))) {
            return false;
        }
        if (rule.output() != null && !producesRef(recipe, rule.output())) {
            return false;
        }
        return rule.input() == null || usesRef(recipe, rule.input());
    }

    private static boolean matchesGeneric(RewriteConfig.RemovalRule rule, TcRecipe recipe) {
        if (rule.type() != null && !(rule.type().getNamespace().equals("thaumcraft")
                && rule.type().getPath().equals(TcJson.type(recipe)))) {
            return false;
        }
        if (rule.output() != null && !producesRef(recipe, rule.output())) {
            return false;
        }
        return rule.input() == null || usesRef(recipe, rule.input());
    }

    /** "mod:item" or "#tag" against the recipe's result. */
    private static boolean producesRef(TcRecipe recipe, String ref) {
        TcStack result = result(recipe);
        Item item = result == null ? null : result.resolve();
        return item != null && itemMatches(item, ref);
    }

    /** "mod:item" or "#tag" against any ingredient (an identical tag ingredient counts for a #tag). */
    private static boolean usesRef(TcRecipe recipe, String ref) {
        for (TcIngredient ingredient : ingredients(recipe)) {
            if (ingredient instanceof TcIngredient.Tag tag && ref.startsWith("#")
                    && tag.tag().location().toString().equals(ref.substring(1))) {
                return true;
            }
            for (ItemStack stack : ingredient.displayStacks()) {
                if (itemMatches(stack.getItem(), ref)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean itemMatches(Item item, String ref) {
        if (ref.startsWith("#")) {
            Identifier tag = Identifier.tryParse(ref.substring(1));
            return tag != null && item.builtInRegistryHolder().is(TagKey.create(Registries.ITEM, tag));
        }
        return BuiltInRegistries.ITEM.getKey(item).toString().equals(ref);
    }

    // ---- ingredient rewriting (replace_ingredients, items.disable) ----

    /** What to do with one ingredient: the same instance = keep, null = the recipe goes. */
    private interface IngredientRewrite extends Function<TcIngredient, TcIngredient> {
    }

    private static final TcIngredient REMOVE = new TcIngredient.AnyOf(List.of());

    /** Rewrites every editable recipe's ingredients; returns how many recipes changed (or went). */
    private static int rewriteIngredients(Map<String, TcRecipe> work, IngredientRewrite rewrite) {
        int changed = 0;
        var it = work.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, TcRecipe> entry = it.next();
            TcRecipe next = rewriteRecipe(entry.getValue(), rewrite);
            if (next == entry.getValue()) {
                continue;
            }
            changed++;
            if (next == null) {
                it.remove();
            } else {
                entry.setValue(next);
            }
        }
        return changed;
    }

    private static @Nullable TcRecipe rewriteRecipe(TcRecipe recipe, IngredientRewrite rewrite) {
        return switch (recipe) {
            case ShapedArcaneRecipe r -> {
                List<TcIngredient> grid = rewriteList(r.grid(), rewrite, true);
                yield grid == r.grid() ? r : grid == null ? null
                        : new ShapedArcaneRecipe(r.key(), r.research(), r.result(), r.aspects(), r.width(), r.height(), grid);
            }
            case ShapelessArcaneRecipe r -> {
                List<TcIngredient> list = rewriteList(r.ingredients(), rewrite, false);
                yield list == r.ingredients() ? r : list == null ? null
                        : new ShapelessArcaneRecipe(r.key(), r.research(), r.result(), r.aspects(), list);
            }
            case CrucibleRecipe r -> {
                TcIngredient catalyst = rewrite.apply(r.catalyst());
                yield catalyst == r.catalyst() ? r : catalyst == REMOVE ? null
                        : new CrucibleRecipe(r.key(), r.research(), r.result(), catalyst, r.aspects());
            }
            case InfusionRecipe r -> {
                TcIngredient central = rewrite.apply(r.central());
                List<TcIngredient> list = rewriteList(r.components(), rewrite, false);
                if (central == REMOVE || list == null) {
                    yield null;
                }
                yield central == r.central() && list == r.components() ? r
                        : new InfusionRecipe(r.key(), r.research(), r.result(), r.instability(), r.aspects(), central, list);
            }
            case InfusionEnchantmentRecipe r -> {
                List<TcIngredient> list = rewriteList(r.components(), rewrite, false);
                yield list == r.components() ? r : list == null ? null
                        : new InfusionEnchantmentRecipe(r.key(), r.research(), r.enchantment(), r.instability(), r.aspects(), list);
            }
            case CompoundRecipe r -> r;
            case CraftingRecipeRef r -> r;
        };
    }

    /** Same list = unchanged, null = an ingredient can't be filled any more. */
    private static @Nullable List<TcIngredient> rewriteList(List<TcIngredient> list, IngredientRewrite rewrite,
                                                            boolean keepEmpty) {
        List<TcIngredient> next = null;
        for (int i = 0; i < list.size(); i++) {
            TcIngredient ingredient = list.get(i);
            if (ingredient instanceof TcIngredient.Empty && keepEmpty) {
                continue;
            }
            TcIngredient rewritten = rewrite.apply(ingredient);
            if (rewritten == REMOVE) {
                return null;
            }
            if (rewritten != ingredient) {
                if (next == null) {
                    next = new ArrayList<>(list);
                }
                next.set(i, rewritten);
            }
        }
        return next == null ? list : next;
    }

    /** replace_ingredients for one ingredient: item refs matching 'from' (or in the #tag), or the identical tag. */
    private static TcIngredient replace(TcIngredient ingredient, RewriteConfig.IngredientReplacement rule) {
        return switch (ingredient) {
            case TcIngredient.Stack s -> {
                Item item = s.stack().resolve();
                boolean matches = item != null && fromMatches(rule.from(), BuiltInRegistries.ITEM.getKey(item));
                yield matches ? toIngredient(rule.to(), s.stack()) : s;
            }
            case TcIngredient.Tag t -> rule.from().tag() != null && rule.from().tag().equals(t.tag().location())
                    ? toIngredient(rule.to(), null) : t;
            case TcIngredient.AnyOf a -> {
                List<TcIngredient> options = new ArrayList<>();
                boolean changed = false;
                for (TcIngredient option : a.options()) {
                    TcIngredient next = replace(option, rule);
                    changed |= next != option;
                    options.add(next);
                }
                yield changed ? new TcIngredient.AnyOf(List.copyOf(options)) : a;
            }
            case TcIngredient.Empty e -> e;
        };
    }

    private static boolean fromMatches(ItemMatch from, Identifier item) {
        if (from.tag() != null) {
            return LootRewriter.tagContents(from.tag()).contains(item);
        }
        return from.pattern().matches(item);
    }

    /** 'to' as an ingredient; an item keeps the old stack's count (Thaumcraft ingredients are single items). */
    private static TcIngredient toIngredient(String to, @Nullable TcStack old) {
        if (to.startsWith("#")) {
            return TcIngredient.tag(to.substring(1));
        }
        return TcIngredient.of(new TcStack(Identifier.parse(to), old == null ? 1 : old.count(), null));
    }

    /**
     * items.disable: recipes making a disabled item go (or make the replacement with make: "redirect"),
     * ingredients get the replacement, and a recipe some ingredient of which only a disabled item without
     * a replacement fits goes.
     */
    private static int applyDisabledItems(Map<String, TcRecipe> work) {
        int changed = 0;
        var it = work.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, TcRecipe> entry = it.next();
            TcStack result = result(entry.getValue());
            Item item = result == null ? null : result.resolve();
            if (item != null && DisabledItems.isDisabled(item)) {
                Item with = DisabledItems.replacement(item);
                if (DisabledItems.redirectsRecipes(item) && with != null && with != Items.AIR) {
                    entry.setValue(withResult(entry.getValue(), new TcStack(BuiltInRegistries.ITEM.getKey(with),
                            result.count(), null)));
                } else {
                    it.remove();
                }
                changed++;
            }
        }
        changed += rewriteIngredients(work, ThaumcraftRecipes::disabledIngredient);
        return changed;
    }

    private static TcIngredient disabledIngredient(TcIngredient ingredient) {
        return switch (ingredient) {
            case TcIngredient.Stack s -> {
                Item item = s.stack().resolve();
                if (item == null || !DisabledItems.isDisabled(item)) {
                    yield s;
                }
                Item with = DisabledItems.replacement(item);
                yield with == null || with == Items.AIR ? REMOVE
                        : TcIngredient.of(new TcStack(BuiltInRegistries.ITEM.getKey(with), s.stack().count(), null));
            }
            case TcIngredient.Tag t -> t.displayStacks().isEmpty() && DisabledItems.isEmptiedTag(t.tag().location())
                    ? REMOVE : t;
            case TcIngredient.AnyOf a -> {
                List<TcIngredient> options = new ArrayList<>();
                boolean changed = false;
                for (TcIngredient option : a.options()) {
                    TcIngredient next = disabledIngredient(option);
                    changed |= next != option;
                    if (next != REMOVE) {
                        options.add(next);
                    }
                }
                yield !changed ? a : options.isEmpty() ? REMOVE : new TcIngredient.AnyOf(List.copyOf(options));
            }
            case TcIngredient.Empty e -> e;
        };
    }

    // ---- record helpers ----

    private static @Nullable TcStack result(TcRecipe recipe) {
        return switch (recipe) {
            case ShapedArcaneRecipe r -> r.result();
            case ShapelessArcaneRecipe r -> r.result();
            case CrucibleRecipe r -> r.result();
            case InfusionRecipe r -> r.result();
            case CraftingRecipeRef r -> r.result();
            case InfusionEnchantmentRecipe r -> null;
            case CompoundRecipe r -> null;
        };
    }

    private static TcRecipe withResult(TcRecipe recipe, TcStack result) {
        return switch (recipe) {
            case ShapedArcaneRecipe r -> new ShapedArcaneRecipe(r.key(), r.research(), result, r.aspects(), r.width(), r.height(), r.grid());
            case ShapelessArcaneRecipe r -> new ShapelessArcaneRecipe(r.key(), r.research(), result, r.aspects(), r.ingredients());
            case CrucibleRecipe r -> new CrucibleRecipe(r.key(), r.research(), result, r.catalyst(), r.aspects());
            case InfusionRecipe r -> new InfusionRecipe(r.key(), r.research(), result, r.instability(), r.aspects(), r.central(), r.components());
            default -> recipe; // a crafting-table reference: the vanilla recipe pass handles that one
        };
    }

    private static List<TcIngredient> ingredients(TcRecipe recipe) {
        return switch (recipe) {
            case ShapedArcaneRecipe r -> r.grid();
            case ShapelessArcaneRecipe r -> r.ingredients();
            case CrucibleRecipe r -> List.of(r.catalyst());
            case InfusionRecipe r -> {
                List<TcIngredient> all = new ArrayList<>(r.components());
                all.add(r.central());
                yield all;
            }
            case InfusionEnchantmentRecipe r -> r.components();
            case CompoundRecipe r -> List.of();
            case CraftingRecipeRef r -> List.of();
        };
    }

    private static String research(TcRecipe recipe) {
        return recipe.research();
    }

    private static boolean researchExists(TcRecipe recipe) {
        return recipe.research().isEmpty() || ResearchCategories.getResearch(recipe.research()) != null;
    }

    /** '*' wildcards, case-sensitive like Thaumcraft's keys. */
    private static Pattern glob(String text) {
        StringBuilder regex = new StringBuilder();
        for (String part : text.split("\\*", -1)) {
            if (!regex.isEmpty()) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(part));
        }
        return Pattern.compile(regex.toString());
    }

    /** Objects merge recursively, everything else replaces, null deletes (like registries.modify). */
    private static void deepMerge(JsonObject target, JsonObject merge) {
        for (Map.Entry<String, JsonElement> entry : merge.entrySet()) {
            JsonElement value = entry.getValue();
            if (value instanceof JsonNull) {
                target.remove(entry.getKey());
            } else if (value instanceof JsonObject nested && target.get(entry.getKey()) instanceof JsonObject existing) {
                deepMerge(existing, nested);
            } else {
                target.add(entry.getKey(), value.deepCopy());
            }
        }
    }

    private static String describe(RewriteConfig.ThaumcraftRule rule) {
        return switch (rule) {
            case RewriteConfig.ThaumcraftRemoval r -> "remove";
            case RewriteConfig.ThaumcraftModification m -> "modify " + m.id();
            case RewriteConfig.ThaumcraftAddition a -> "add " + a.id();
        };
    }

    private static void warn(RewriteConfig.ThaumcraftRule rule, String message) {
        Datarewriter.LOGGER.warn("[{}] thaumcraft: {}", rule.source(), message);
    }
}
