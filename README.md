# DataRewriter

A lightweight, **server-side** Fabric mod for Minecraft **1.21.1** that removes, replaces, and adds recipes and loot tables through simple config files — inspired by KubeJS, but without a scripting engine.

- Works on dedicated servers and in singleplayer. **Vanilla clients can join** — rewritten recipes reach them through normal recipe sync.
- Changes apply on world/server start and on plain `/reload` — no restart needed while tuning.
- Config files allow `// comments`, trailing commas, and unquoted keys.
- Optional client side: installing the mod on your own client adds a visual [recipe editor](#recipe-editor-gui) — other players don't need it.

## Getting started

Drop the jar into `mods/` on the server (Fabric API required). On first launch the mod creates `config/datarewriter/example.json5` with everything documented.

**Every** `.json` / `.json5` file in `config/datarewriter/` is loaded, so split your tweaks into as many files as you like (`waystones.json5`, `vanilla-nerfs.json5`, …). Each file looks like this:

```json5
// comments and trailing commas are fine
{
  recipes: {
    remove: [ /* removal rules */ ],
    add:    [ /* new recipes   */ ],
  },
  loot_tables: {
    remove: [ /* removal rules      */ ],
    add:    [ /* new loot tables    */ ],
    modify: [ /* loot injections    */ ],
  },
}
```

After a `/reload`, online operators get a chat summary: how many recipes were removed/added, plus a warning if any config file has errors.

## Removing recipes

Each rule is an object; a recipe is removed when it matches **all** conditions in the rule (rules themselves are independent — any rule can remove a recipe).

```json5
remove: [
  { mod: "uselessmod" },                 // every recipe from one mod
  { id: "minecraft:golden_apple" },      // one exact recipe id
  { id: "minecraft:*_boat" },            // '*' wildcards work in ids
  { output: "minecraft:elytra" },        // everything that crafts this item
  { input: "minecraft:diamond" },        // everything using this ingredient
  { input: "#minecraft:planks" },        // item tags work for output/input
  { type: "minecraft:blasting", mod: "minecraft" },  // AND-combined conditions
]
```

| Condition | Matches |
|-----------|---------|
| `id` | the recipe id; `*` wildcards allowed |
| `mod` | the recipe id's namespace (`"minecraft"`, `"create"`, …) |
| `type` | the recipe type (`minecraft:crafting_shaped`, `minecraft:smelting`, modded types too) |
| `output` | the item the recipe produces; `#tag` allowed |
| `input` | any ingredient of the recipe; `#tag` allowed |

Removal never touches recipes *you* added, so "remove everything from `minecraft:`, then re-add a few" works as expected.

## Adding & replacing recipes

Entries in `add` use the vanilla recipe JSON format (the same one datapacks use), with two conveniences:

1. **Shorthand strings** — for vanilla recipe types you can write ingredients as `"minecraft:oak_log"` or `"#minecraft:planks"` and results as `"minecraft:diamond"`; DataRewriter expands them to the strict 1.21.1 format for you.
2. **Replacing** — give an entry an `id` matching an existing recipe and it replaces that recipe. Without an `id`, one is generated (`datarewriter:<filename>/<n>`).

```json5
add: [
  {
    id: "minecraft:enchanting_table",   // replaces the vanilla recipe
    type: "minecraft:crafting_shaped",
    pattern: ["XX",
              "XX"],
    key: { X: "minecraft:oak_log" },
    result: "minecraft:enchanting_table",
  },
  {
    type: "minecraft:smelting",         // no id -> generated automatically
    ingredient: "minecraft:rotten_flesh",
    result: "minecraft:leather",
    experience: 0.1,
    cookingtime: 200,
  },
  {
    type: "minecraft:crafting_shapeless",
    ingredients: ["minecraft:blaze_rod", "#minecraft:planks"],
    result: { id: "minecraft:blaze_powder", count: 4 },   // object form for a count
  },
]
```

Shorthand is expanded for: `crafting_shaped`, `crafting_shapeless`, `smelting`, `blasting`, `smoking`, `campfire_cooking`, `stonecutting`, `smithing_transform`, `smithing_trim`.

### Modded recipe types

Adding recipes of **modded types** works out of the box — the `type` field selects any recipe serializer registered in the game, and the JSON is handed to that mod's own parser. The only difference: shorthand strings are not expanded for unknown types, so write the exact format the mod expects, e.g. `{ "item": "..." }` / `{ "tag": "..." }` for ingredients.

The easiest way to learn a mod's format: open its jar (it's a zip) and copy a real recipe from `data/<modid>/recipe/` (or `recipes/` in older mods) as a template. For example, a Farmer's Delight cutting recipe:

```json5
{
  type: "farmersdelight:cutting",
  ingredients: [{ item: "minecraft:oak_log" }],
  tool: { tag: "c:tools/axes" },
  result: [{ item: { id: "minecraft:oak_planks", count: 6 } }],
}
```

Removing modded recipes needs no special handling at all — `mod`, `id`, `type` always work, and `output`/`input` work for any recipe that reports its result/ingredients normally.

## Loot tables

Loot table ids encode what they're for in their path: `minecraft:blocks/stone`, `minecraft:entities/zombie`, `minecraft:chests/simple_dungeon`, `minecraft:gameplay/fishing`, … Combined with `*` wildcards this gives precise targeting — removing a mod's chest loot doesn't touch its block drops.

"Removing" a loot table **empties** it: the block, mob, or chest simply drops nothing. (The table itself stays registered, so mods that look it up don't break.)

```json5
loot_tables: {
  remove: [
    { id: "minecraft:entities/creeper" },  // creepers drop nothing
    { id: "minecraft:chests/village/*" },  // all village chest loot
    { id: "*:entities/*" },                // entity drops from every mod
    { mod: "uselessmod" },                 // every table from one mod
  ],
  add: [
    {
      id: "minecraft:entities/zombie",     // replaces zombie drops entirely
      pools: [{
        rolls: 1,
        entries: [{ type: "minecraft:item", name: "minecraft:diamond" }],
      }],
    },
  ],
  modify: [
    {
      id: "minecraft:chests/village/*",    // inject into every matching table,
      pools: [{                             // keeping the table's own loot
        rolls: 1,
        entries: [
          { type: "minecraft:item", name: "minecraft:emerald" },
          { type: "minecraft:empty", weight: 3 },   // 3:1 odds -> emerald 25% of the time
        ],
      }],
    },
  ],
}
```

Rules AND-combine `id` (wildcards allowed) and `mod`, like recipe rules. Additions use the vanilla loot table JSON format (same as datapacks — the [Minecraft wiki](https://minecraft.wiki/w/Loot_table) documents it fully) and **require an `id`**: use an existing id to replace that table. Replaced/added tables are never touched by your own removal rules.

`modify` appends your pools to every matching table without touching its existing loot — the way RPG-style mods inject their drops everywhere. It targets like `remove` (`id` with wildcards, `mod`) and runs **after** remove/add, so it also applies to tables you added or emptied. To make an injected drop rare, weight it against a `minecraft:empty` entry like in the example, or use a `random_chance` condition on the pool.

Two more operations work on **items across tables** instead of whole tables:

```json5
loot_tables: {
  // Swap what item entries drop — everywhere, or only in matching tables.
  replace_items: [
    { from: "minecraft:diamond", to: "minecraft:emerald" },
    { from: "uselessmod:*", to: "minecraft:stick", table: "minecraft:chests/*" },
    // '!' excludes: everywhere except block and entity drops
    { from: "minecraft:diamond", to: "minecraft:coal", table: "!*:blocks/*, !*:entities/*" },
  ],
  // Delete item entries; the rest of each table stays intact.
  remove_items: [
    "minecraft:ender_pearl",                                    // shorthand for { item: ... }
    { item: "minecraft:string", table: "minecraft:entities/*" },
    { item: "minecraft:gold_ingot", table: ["somemod:*", "!somemod:blocks/*"] },
  ],
}
```

`from`/`item` allow `*` wildcards **or a `#tag`** (e.g. `#c:fishes`): a tag matches every item that belongs to it, plus loot entries referencing the identical tag (replacing converts such a tag entry into the target item). Tag-based rules are applied right after startup/`/reload` finishes rather than during loading (item tags aren't bound yet at that point) — the effect is the same. `table` (optional) restricts which tables are touched: one or more patterns (comma-separated in a string, or a list), each with `*` wildcards, and a leading `!` **excludes** instead of includes — with only exclusions given, everything not excluded matches. These run **last**, so they also cover pools you added or injected. They find item entries anywhere in a table — including inside `alternatives`/`group` composites — and keep each entry's weight, count functions and conditions when replacing. (Tag entries and items referenced inside functions are not touched.)

## Recipe editor GUI

If the mod is installed on your **client** too, you can create recipes visually instead of writing JSON: run `/recipeeditor` while in a world.

- Click the type button at the top to open a searchable list of every recipe type the editor knows; picking one draws that station's own GUI (crafting table, furnace, smithing table, …) or a generic panel for auto-detected modded types. Full station GUIs show your inventory in its normal place, like the real screen.
- **Native support** ships for known mods — see the [supported mods list](#natively-supported-mods) below. Each appears automatically when that mod is installed; every other mod's types are covered by [automatic detection](#modded-recipe-types--automatic).
- Hover a text field to see the values existing recipes actually use for it (e.g. which `unit` strings a mod accepts) — collected automatically from all loaded recipes of that type.
- Click a highlighted slot to choose an item from a searchable list (type `#` to search **tags** — item tags, or fluid tags on slots that accept them); right-click clears a slot. For slot types that support amounts, scroll the mouse wheel over the slot to change the count; where a recipe supports per-result chances, Alt+scroll sets them.
- Your **inventory** is shown in the editor — click an item to pick it up, then click it into as many slots as you like (right-click drops it), or just drag it onto a slot. Buckets dropped on fluid slots become their fluid.
- With **EMI** installed, its panels show up next to the editor and you can drag any item or fluid from them straight into a slot, like an AE2 pattern terminal — compatible slots light up green while dragging. Even better: open any recipe in EMI and click its **fill (+) button** to load that recipe into the editor — slots, amounts, chances, fields, and the id, ready to tweak and save (which replaces the original; clear the id to save a copy instead). (Optional; nothing is required at runtime.)
- Middle-click a filled slot to **type an exact amount** (count or mB) instead of scrolling.
- Optionally give the recipe an id (an existing id **replaces** that recipe), fill in any extra fields (XP, cooking time, …), and hit **Save recipe**. The editor stays open, so you can keep making recipes; **Clear** empties the current pattern.
- Closing and reopening the editor brings back your last recipe type, slot contents, amounts, and field values (kept until the game quits).

Saving sends the recipe to the server (op only), where it is validated by the real recipe parser, appended to `config/datarewriter/gui-recipes.json5` — a normal config file you can edit later — and **applied immediately** to the running game (no `/reload`; the recipe is live and synced to all players the moment you save). Nothing extra is needed on other players' clients or on the server beyond the mod itself.

### Natively supported mods

Hand-tuned layouts (drawn on the mod's own GUI where one exists) ship for:

| Mod | Recipe types | Notes |
|-----|--------------|-------|
| Minecraft | crafting (shaped & shapeless), smelting, blasting, smoking, campfire cooking, stonecutting, smithing (upgrade & trim) | always available, real station GUIs |
| Farmer's Delight Refabricated | `cooking` (cooking pot GUI), `cutting` | cutting board: tool slot + up to 4 results with drop chances |
| Brewin' and Chewin' | `fermenting`, `keg_pouring` (keg GUI), `distilling` | fluid slots with tag support; units always saved as millibuckets; distilling covers the mod's upcoming release |
| [Let's Do] Vinery | `wine_fermentation` (fermentation barrel GUI), `apple_mashing`, `apple_fermenting` (apple press GUI) | juice is set via type/amount fields (all valid juice types suggested) |
| [Let's Do] Herbal Brews | `kettle_brewing` (tea kettle GUI) | the dummy `cauldron_brewing` type is hidden |
| Ube's Delight | `baking_mat` | tool, 3×3 ingredients, chance results, optional processing stages |
| Runes | `crafting` (altar GUI) | smithing-style base + addition; OR-alternatives via `#tags` |
| Potions LD | `potion_brewing` (alchemy table GUI) | 2×2 counted ingredients (scroll to set amounts) + result; upgrade slots are machine gear, not recipe data |

### Modded recipe types — automatic

Modded recipe types work **automatically**: the server syncs every loaded recipe to the client, so when the editor opens it takes one existing recipe of each modded type, encodes it back to JSON with the mod's own codec, and infers the editing layout from that sample — ingredient-shaped values become slots (counted ones get scroll-to-set amounts, fluids get a fluid picker), numbers/strings/booleans become fields pre-filled with the sample's values. These auto layouts draw on a generic panel and appear in the type selector as e.g. "Cutting (farmersdelight)".

Two limits: a type only appears if at least one recipe of it is currently loaded (that's where the sample comes from), and the inference is heuristic — an exotic JSON shape may come out wrong or miss a field.

### Editor layouts — manual override

For full control — the mod's real GUI texture, exact slot positions, required flags, corrected field mappings — write a layout in `config/datarewriter/editor-layouts/*.json5` **on the client**; it replaces the auto layout for that type. In its minimal form (generic panel, auto-placed slots) a layout is just the JSON mapping:

```json5
{
  type: "somemod:cutting",
  name: "Cutting Board (Some Mod)",
  slots: [
    { field: "ingredients[]", format: "ingredient", required: true },
    { field: "result", format: "item", result: true, required: true },
  ],
  fields: [
    { field: "processing_time", label: "Time", type: "int", default: "100" },
  ],
}
```

(To learn a mod's recipe JSON field names, copy a real recipe from its jar — `data/<modid>/recipe/` — as the reference.)

Optionally, add the mod's own GUI for the authentic look:

```json5
{
  // ...same as above, plus:
  texture: "somemod:textures/gui/cutting_board.png",
  crop: [0, 0, 176, 80],            // [u, v, width, height] region of the texture
  slots: [
    { x: 56, y: 17, field: "ingredients[]", format: "ingredient", required: true },
    { x: 116, y: 35, field: "result", format: "item", result: true, required: true },
  ],
}
```

- Slot `x`/`y` are the item positions from the mod's Menu/ScreenHandler class (the numbers passed to `new Slot(...)`); the crop offset is subtracted automatically. Without a texture, `crop` (if given) just sets the panel size.
- `field` paths support nesting (`result.item`) and arrays (`ingredients[]` appends).
- `format` controls the JSON written for a picked item: `string` (`"mod:item"` / `"#mod:tag"`), `ingredient` (`{item}`/`{tag}`), `counted_ingredient` (`{ingredient, count}`), `item` (`{id, count}`), `item_named` (`{item, count}`), `fluid` (`{id, amount_mb}`) or `fluid_amount` (`{id, amount}`) — the fluid formats make the picker list fluids.
- Slots also take `chance: true` (Alt+scroll sets a drop chance, written as a `chance` key) and `tags: true` (allow `#tags` beyond what the format permits — fluid formats then write `{tag}` instead of `{id}`; only use where the recipe type accepts it).
- Add `inventory_y: 84` to show the player inventory inside the panel like a real container screen — with a full 176×166 GUI texture, or without any texture (the editor draws a clean recipe-card panel).
- `fields` become text boxes: `type` is `int`, `float`, `string` or `bool`; `required: true` blocks saving while empty; an optional `suggestions: ["a", "b"]` list is shown as a tooltip (auto layouts fill this from existing recipes).

An entry of just `{ type: "somemod:sometype", hidden: true }` removes that recipe type from the editor entirely — for dynamic or dummy recipe types that can't sensibly be created.

A commented example is generated at `config/datarewriter/editor-layouts/example.json5` on first use, and the files are re-read every time the editor opens, so you can tweak a layout and just reopen the screen.

## Loot table editor GUI

Loot tables get the same treatment as recipes: run `/loottableeditor` (client-side, op only) to browse and edit them visually. Loot tables are never synced to clients, so everything you see comes fresh from the server.

- The picker lists **every loot table on the server** with a search box. Click one to edit it; typing an id that doesn't exist and pressing Enter creates a **new table** under that id.
- **Filter by item** shows only the tables that actually drop a chosen item — the quick way to answer "where can diamonds come from?" before changing that. It also takes a `#tag` (e.g. `#c:fishes`): tables drop-matching **any item from the tag** (or the tag itself) are shown.
- Choosing an item never means scrolling a giant list: click one in your **inventory**, drag one in from **EMI**, type an id (with `*` wildcards where the action supports patterns), or fall back to the searchable all-items list. Where tags make sense, **right-click an inventory item to pick one of its `#tags`** instead — no typing tag ids by hand.
- **Replace item…** / **Remove item…** work across many loot tables at once: pick the item **or one of its tags** (right-click; a `#tag` covers every item in it), a replacement item if replacing, then choose **which tables** — every table, one mod (`somemod:*`), only chests, or anything *except* block/entity drops (`!*:blocks/*` — quick preset buttons cover the common cases). Entries keep their weights, counts and conditions when replaced. The operation is saved as a `replace_items`/`remove_items` rule in `config/datarewriter/gui-loot-tables.json5` — delete the rule there to undo it.
- The editor shows the table's **pools** with entries as item slots. Scroll on an entry to change its **weight** (the tooltip shows its share of the pool), Shift+scroll for the **count**, Ctrl+scroll to grow a count **range** (1–3, …), Alt+scroll for a **drop chance**; middle-click types an exact weight — for item entries the same popup offers **Convert to #tag…**, listing every tag the item is in ("any planks instead of oak planks"). Scroll over the `rolls:` text to change how many entries a pool hands out (Shift makes it a range).
- Click a slot to set its item (`#tags` work too) — or drop an item from the inventory panel or EMI **anywhere on a pool's card** to add it to that pool, or exactly on a slot to replace that entry. Right-click the `+` slot to add an **empty entry** (a weighted chance to drop nothing); right-click an entry deletes it, `✕` deletes a pool.
- **Complex entries are editable too**: an `alternatives`/`group`/`sequence` entry shows as a bundle with its entry count — click it to open a sub-editor for its children (same controls, nesting included), and a nested `loot_table` reference shows as a chest — click it to pick a different target table. Middle-click a pool's `+` slot to **create** one of these. Anything beyond that (inline nested tables, `dynamic` entries, custom conditions) still shows as a locked slot and is **preserved exactly as-is**, same for fancy count/chance formulas.
- **Save table** stores the whole table as a replacement (also the only way to keep edits/deletions of the table's *own* entries); with every pool deleted it saves an empty-table rule instead. **Save added pools** appends just your new pools, leaving the table's own loot to its mod — survives mod updates, like config `modify`. **Empty table** removes all pools *locally* — nothing is saved until you press Save table, and **Reload** discards unsaved edits and refetches the table.

Saves are validated with the vanilla loot table parser, written to `config/datarewriter/gui-loot-tables.json5` (a normal config file) and **applied to the running server immediately** — kill the mob or open the chest and the new drops are live, no `/reload`.

**Other mods that inject loot at runtime** (RPG-series equipment injectors and the like, via Fabric's loot events) are fully accounted for:

- Injected pools **show up in the editor**, marked purple as "Injected — by \<mod\>?" (the mod is guessed from the ids inside the pool). They are read-only there: the injection is generated by that mod's own config, which is the place to restructure it.
- `replace_items`/`remove_items` rules — from the config **and** from the GUI's bulk Replace/Remove item — **do apply to injected loot**: after the other mods' injections run, DataRewriter re-applies its item rules on top (at startup, after `/reload`, and immediately on a GUI bulk edit). So "replace all diamonds with emeralds" catches injected diamonds too.
- Saving a table in the editor re-runs the mods' loot events on it, so injected loot survives live edits instead of disappearing until the next `/reload`.

## Finding ids in game

You don't have to dig through mod jars to find recipe or loot table ids — as an operator, use:

- `/datarewriter list recipes` — every mod that has recipes, with counts; click a mod to drill in
- `/datarewriter list recipes <mod> [page]` — its recipe ids, paginated with clickable prev/next; **click any id to copy it** to your clipboard, ready to paste into a config file
- `/datarewriter list loot_tables [mod] [page]` — the same for loot tables
- `/datarewriter errors` — every error and warning from the last config load, in chat (and printed to the server log again); `/reload` re-checks

The commands are registered server-side, so they work from a vanilla client. Listings reflect the current state (after your rules), so recipes you added under the `datarewriter:` namespace show up too.

## Troubleshooting

- **A recipe didn't appear?** Check the server log. A broken recipe produces `Parsing error loading recipe <id>` (from vanilla) or an error naming your config file (from DataRewriter). After `/reload`, ops also see the error count in chat.
- **Recipe book:** added recipes have no unlock advancement, so the green recipe book won't advertise them — but they craft fine, and REI/JEI/EMI list them.
- **Item in a removed recipe still craftable?** Some mods register several recipes for one item (or a datapack adds one). Remove by `output:` instead of `id:` to catch them all.

## Notes for developers

- Recipes are rewritten in two phases: `id`/`mod` rules and additions are applied to the raw recipe JSON map (mixin at the head of `RecipeManager.apply`), while `output`/`input`/`type` rules run after startup / `/reload` completes — item tags are not bound during recipe loading, so tag matching earlier would silently fail.
- Loot tables are rewritten in the raw JSON map too (mixin in `SimpleJsonResourceReloadListener.scanDirectory`, filtered to the `loot_table` directory). That hook runs before recipes in vanilla's reload pipeline, so it's also where the config is (re)loaded. The final JSON map is kept in memory — it's what the loot editor reads and searches.
- Loot editor saves apply without a reload by rebinding the table's `Holder.Reference` inside the frozen reloadable registry (what vanilla itself does during load); brand-new ids briefly unfreeze the registry to register. Before binding, Fabric's `LootTableEvents` (REPLACE + MODIFY) are re-invoked on the freshly parsed table and the config's item rules are applied over the result, so runtime loot injections from other mods are preserved *and* covered by item rules; the JSON cache stays at the datapack level, so injections never stack. A post-injection pass (`LootInjectionRewriter`, from `SERVER_STARTED`/`END_DATA_PACK_RELOAD`) does the same on reload: it encodes each live table back to JSON with the vanilla codec, re-applies the item rules and rebinds tables where injected entries matched. The editor shows injected pools by diffing the live table's encoded pools against the datapack-level cache (the extra tail pools are the injected ones).
- Targets 1.21.1 specifically. 1.21.2+ replaced `RecipeManager`'s internals (`RecipeMap`) and would need a different implementation.

## Ideas / future

- More data sections in the same format (advancements? item tags?)
