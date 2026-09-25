# DataRewriter

Recipe and loot table rewriting for Minecraft **1.21.1** (Fabric and NeoForge). Remove, replace and add
recipes and loot tables through plain config files — KubeJS-style control without a scripting engine —
or skip the JSON entirely and do it in game: a recipe editor drawn on each station's own GUI, a bulk
recipe tweaker, and a loot table editor that writes straight into the running server.

> Minecraft 1.21.1 · Java 21 · Fabric (needs [Fabric API](https://modrinth.com/mod/fabric-api)) or
> NeoForge, one jar per loader. **Server-side.** Vanilla clients can join and get every rewritten recipe
> through normal recipe sync; installing the mod on your own client is what adds the editors. All in-game
> editing requires operator level 2.

## Contents

[Getting started](#getting-started) · [Config files](#config-files) · [Removing recipes](#removing-recipes) ·
[Replacing ingredients](#replacing-ingredients) · [Adding & replacing recipes](#adding--replacing-recipes) ·
[Loot tables](#loot-tables) · [Item rules across tables](#item-rules-across-tables) ·
[Recipe editor](#recipe-editor) · [Recipe tweaks](#recipe-tweaks) · [Loot table editor](#loot-table-editor) ·
[Supported mods](#natively-supported-mods) · [Editor layouts](#editor-layouts--manual-override) ·
[Datapack registries](#datapack-registries) · [Commands](#commands) · [File locations](#file-locations) ·
[Troubleshooting](#troubleshooting) ·
[Addon API](#addon-api) · [Compatibility](#compatibility) · [Notes for developers](#notes-for-developers)

## Getting started

1. Drop the jar for your loader into `mods/` on the server (on Fabric, Fabric API is required; on
   NeoForge nothing else). Install it on your client too if you want the [editors](#in-game-editors) —
   other players don't need it.
2. On first launch the mod writes `config/datarewriter/example.json5`, with every rule type documented.
3. Edit it (or add your own file) and run `/reload`. Ops get a chat summary — how many recipes were
   removed and added, plus a warning if any config file has errors.
4. Or don't write JSON at all: `/recipeeditor`, `/recipetweaker` and `/loottableeditor` do the same
   things visually and apply them live.

Changes apply on world/server start and on plain `/reload` — no restart while tuning. Dedicated servers
and singleplayer worlds work the same way.

## Config files

**Every** `.json` / `.json5` file in `config/datarewriter/` is loaded — **subfolders included**
(`config/datarewriter/nerfs/mobs.json5`, as deep as you like) — so split and organize your tweaks into as
many files and folders as you like (`waystones.json5`, `vanilla-nerfs.json5`, …). Only `editor-layouts/`
is special (client-side [editor layouts](#editor-layouts--manual-override), not rules). Files allow
`// comments`, trailing commas and unquoted keys, and each looks like this:

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

Eight operations exist. Recipes and loot tables are rewritten independently, but within `loot_tables`
the order below is the order they run in — `modify` also sees tables `add` created, and the two item
rules see everything:

| Section | Operation | What it does |
|---|---|---|
| `recipes` | [`remove`](#removing-recipes) | Deletes every recipe matching a rule |
| `recipes` | [`add`](#adding--replacing-recipes) | Adds a recipe — or replaces one, when it carries an existing `id` |
| `recipes` | [`replace_ingredients`](#replacing-ingredients) | Swaps an item or `#tag` for another in **every** recipe's ingredients |
| `loot_tables` | [`remove`](#loot-tables) | Empties matching tables — the block, mob or chest drops nothing |
| `loot_tables` | [`add`](#loot-tables) | Adds a table, or replaces an existing one by `id` |
| `loot_tables` | [`modify`](#loot-tables) | Appends pools to matching tables, keeping their own loot |
| `loot_tables` | [`replace_items`](#item-rules-across-tables) | Swaps what item entries drop, across many tables |
| `loot_tables` | [`remove_items`](#item-rules-across-tables) | Deletes item entries, across many tables |

Rules that match on a `#tag` (and every item rule) are applied once startup or `/reload` has finished
rather than during loading, because item tags aren't bound yet at that point — the effect is the same.

## Removing recipes

Each rule is an object; a recipe is removed when it matches **all** conditions in the rule (rules
themselves are independent — any rule can remove a recipe).

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

Removal never touches recipes *you* added, so "remove everything from `minecraft:`, then re-add a few"
works as expected. Removing modded recipes needs no special handling: `mod`, `id` and `type` always work,
and `output`/`input` work for any recipe that reports its result and ingredients normally.

## Replacing ingredients

Instead of being removed, recipes can have an ingredient **swapped everywhere**:

```json5
replace_ingredients: [
  { from: "minecraft:diamond", to: "minecraft:emerald" },
  { from: "#minecraft:logs_that_burn", to: "#minecraft:stone_bricks" },
]
```

`from` is an item id (with `*` wildcards) or a `#tag` — a tag matches every item belonging to it plus
ingredient refs to the identical tag; `to` is an item id or a `#tag`. Only **ingredients** are rewritten —
results stay as they are — and every recipe is covered, whatever mod it comes from. The rewritten recipes
are re-validated with the real recipe parser; a recipe that would break is left unchanged (with a log
warning). Applied after startup/`/reload`, and instantly when saved from the [recipe tweaks
GUI](#recipe-tweaks).

## Adding & replacing recipes

Entries in `add` use the vanilla recipe JSON format (the same one datapacks use), with two conveniences:

1. **Shorthand strings** — for vanilla recipe types you can write ingredients as `"minecraft:oak_log"` or
   `"#minecraft:planks"` and results as `"minecraft:diamond"`; DataRewriter expands them to the strict
   1.21.1 format for you.
2. **Replacing** — give an entry an `id` matching an existing recipe and it replaces that recipe. Without
   an `id`, one is generated (`datarewriter:<filename>/<n>`).

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

Shorthand is expanded for: `crafting_shaped`, `crafting_shapeless`, `smelting`, `blasting`, `smoking`,
`campfire_cooking`, `stonecutting`, `smithing_transform`, `smithing_trim`.

### Modded recipe types

Adding recipes of **modded types** works out of the box — the `type` field selects any recipe serializer
registered in the game, and the JSON is handed to that mod's own parser. The only difference: shorthand
strings are not expanded for unknown types, so write the exact format the mod expects, e.g.
`{ "item": "..." }` / `{ "tag": "..." }` for ingredients.

The easiest way to learn a mod's format: open its jar (it's a zip) and copy a real recipe from
`data/<modid>/recipe/` (or `recipes/` in older mods) as a template. For example, a Farmer's Delight
cutting recipe:

```json5
{
  type: "farmersdelight:cutting",
  ingredients: [{ item: "minecraft:oak_log" }],
  tool: { tag: "c:tools/axes" },
  result: [{ item: { id: "minecraft:oak_planks", count: 6 } }],
}
```

## Loot tables

Loot table ids encode what they're for in their path: `minecraft:blocks/stone`,
`minecraft:entities/zombie`, `minecraft:chests/simple_dungeon`, `minecraft:gameplay/fishing`, … Combined
with `*` wildcards this gives precise targeting — removing a mod's chest loot doesn't touch its block
drops.

"Removing" a loot table **empties** it: the block, mob or chest simply drops nothing. (The table itself
stays registered, so mods that look it up don't break.)

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

Rules AND-combine `id` (wildcards allowed) and `mod`, like recipe rules. Additions use the vanilla loot
table JSON format (same as datapacks — the [Minecraft wiki](https://minecraft.wiki/w/Loot_table)
documents it fully) and **require an `id`**: use an existing id to replace that table. Replaced/added
tables are never touched by your own removal rules.

`modify` appends your pools to every matching table without touching its existing loot — the way RPG-style
mods inject their drops everywhere. It targets like `remove` (`id` with wildcards, `mod`) and runs
**after** remove/add, so it also applies to tables you added or emptied. To make an injected drop rare,
weight it against a `minecraft:empty` entry like in the example, or use a `random_chance` condition on the
pool.

### Item rules across tables

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

`from`/`item` allow `*` wildcards **or a `#tag`** (e.g. `#c:fishes`): a tag matches every item that
belongs to it, plus loot entries referencing the identical tag (replacing converts such a tag entry into
the target item). `table` (optional) restricts which tables are touched: one or more patterns
(comma-separated in a string, or a list), each with `*` wildcards, and a leading `!` **excludes** instead
of includes — with only exclusions given, everything not excluded matches.

These run **last**, so they also cover pools you added or injected, and
[loot other mods inject at runtime](#loot-injected-by-other-mods). They find item entries anywhere in a
table — including inside `alternatives`/`group` composites — and keep each entry's weight, count
functions and conditions when replacing. (Tag entries and items referenced inside functions are not
touched.)

## Datapack registries

Some crafting systems are not recipes at all: Forbidden Arcanus stores its Hephaestus Forge **rituals**
(and its enhancers, item modifiers, ...) in datapack registries, the same mechanism vanilla uses for
enchantments. The `registries` config section rewrites those entries the way the sections above rewrite
recipes — remove by id (`*` wildcards), merge changes into matching entries, or add whole entries:

```json5
{
  registries: {
    remove: [ { registry: "forbidden_arcanus:hephaestus_forge/ritual", id: "forbidden_arcanus:slimec" } ],
    modify: [
      // "merge" merges into the entry's JSON: objects merge deeper, everything else is
      // replaced, null deletes a key. An entry that no longer parses loads unchanged (see the log).
      {
        registry: "forbidden_arcanus:hephaestus_forge/ritual",
        id: "forbidden_arcanus:*",
        merge: { essences: { souls: 20 } },
      },
    ],
    add: [
      // "entry" is the registry's own JSON format; an existing id is replaced.
      { registry: "minecraft:enchantment", id: "minecraft:sharpness", entry: { /* ... */ } },
    ],
  },
}
```

Two things set these rules apart from everything else in this file:

- They apply while the **world loads** — restart or re-enter the world after changing them; `/reload`
  does not touch datapack registries (that's vanilla behavior, not a DataRewriter choice).
- **Removal can break references.** An entry that other data files name by id (an enhancer a ritual
  requires, a magic circle) must not be removed — the world then fails to load, exactly as if a
  datapack had deleted the file. Removing "leaf" entries like rituals themselves is safe.

The generated `example.json5` documents the shapes; ritual editing also has
[its own GUI](#hephaestus-forge-rituals-forbidden-arcanus).

## In-game editors

These screens do everything the config files do, without writing JSON. They are **client-side**: only
your own client needs the mod, never the other players'. Every save goes through the server, which
validates it, appends it to a normal config file and applies it to the running game immediately.

| Command | Screen | What it's for |
|---|---|---|
| `/recipeeditor` | [Recipe editor](#recipe-editor) | Build or replace one recipe, on its station's own GUI |
| `/recipetweaker` | [Recipe tweaks](#recipe-tweaks) | Remove or swap an item across **all** recipes at once |
| `/loottableeditor` | [Loot table editor](#loot-table-editor) | Browse and edit any loot table on the server |
| `/recipeeditor` → Hephaestus forge ritual | [Ritual editor](#hephaestus-forge-rituals-forbidden-arcanus) | Edit Forbidden Arcanus forge rituals (with the mod installed) |

Saving requires **operator level 2** on the server you're connected to. The loot editor needs it to open
at all: loot tables are never synced to clients, so everything it shows is fetched live from the server.

### Picking items and tags

The same controls work in every DataRewriter screen, so choosing an item never means typing an id:

- **Click a slot** to choose an item from a searchable list — type `#` to search **tags** (item tags, or
  fluid tags on slots that accept them). Right-click clears a slot.
- **Your inventory** is shown in the screen: click an item to pick it up, then click it into as many slots
  as you like (right-click drops it), or drag it onto a slot. **Right-click an inventory item to pick one
  of its `#tags`** instead, from a searchable list. Buckets dropped on fluid slots become their fluid.
- **Middle-click a placed item to convert it to one of its `#tags`** ("any planks instead of oak planks").
- Search is EMI-style: all space-separated words must match, and `@mod` filters by namespace —
  `@minecraft end` shows Minecraft's entries containing "end". Pickers remember their last search and
  scroll position until the game quits.
- With **EMI** installed, its panels sit next to the screen and you can drag any item or fluid straight
  into a slot, like an AE2 pattern terminal — compatible slots light up green while dragging. EMI's lookup
  keys work everywhere too: hover any slot — a recipe slot, a loot entry, an inventory item — and press
  **R** for its recipes or **U** for its uses. (Clicks stay with the editor, so picking items up still
  works as before.)

### Recipe editor

`/recipeeditor` builds one recipe at a time, drawn on the station it belongs to.

- Click the type button at the top for a searchable list of every recipe type the editor knows; picking
  one draws that station's own GUI (crafting table, furnace, smithing table, …) or a generic panel for
  auto-detected modded types. Full station GUIs show your inventory in its normal place, like the real
  screen.
- **Native support** ships for known mods — see [natively supported mods](#natively-supported-mods). Each
  appears automatically when that mod is installed; every other mod's types are covered by
  [automatic detection](#modded-recipe-types--automatic).
- For slot types that support amounts, **scroll** over the slot to change the count; middle-click types an
  exact amount (count or mB), with the slot's other actions offered as buttons in the same popup: **Convert
  to #tag…** and, on plain item-stack result slots, **Edit components…**. Where a recipe supports
  per-result chances, **Alt+scroll** sets them.
- **Edit components…** takes the vanilla `components` JSON object (`{"minecraft:custom_name": "...",
  "minecraft:enchantments": {"minecraft:sharpness": 3}}`), validates it with the game's own component
  codec against the loaded registries before accepting it, and previews the result item live in the
  dialog. The result slot then renders the item with its components applied. Ingredients can't carry
  components in 1.21.1 — only results.
- Hover a text field to see the values existing recipes actually use for it (e.g. which `unit` strings a
  mod accepts) — collected automatically from all loaded recipes of that type.
- Open any recipe in **EMI** and click its **fill (+) button** to load that recipe into the editor —
  slots, amounts, chances, fields and the id, ready to tweak and save (which replaces the original; clear
  the id to save a copy instead). It works on any recipe card backed by a real recipe, including mods
  whose cards carry synthetic ids such as Create's, and only appears while the **recipe editor itself** is
  open (in any other screen EMI says "current workstation does not support recipe", which is expected).
- Optionally give the recipe an id (an existing id **replaces** that recipe), fill in any extra fields
  (XP, cooking time, …) and hit **Save recipe**. The editor stays open so you can keep making recipes;
  **Clear** empties the current pattern.
- Closing and reopening brings back your last recipe type, slot contents, amounts and field values (kept
  until the game quits).

Saving sends the recipe to the server, where it is validated by the real recipe parser, appended to
`config/datarewriter/gui-recipes.json5` — a normal config file you can edit later — and **applied
immediately**: no `/reload`, the recipe can be crafted right away by everyone. Saving under an id that is
already in that file replaces the entry instead of adding a second one.

What is *not* immediate is the client-side **recipe list** — what EMI/JEI/REI and the recipe book show.
Pushing it makes those viewers drop everything and reload from scratch, which takes seconds on a big pack
and would interrupt you after every single save, so DataRewriter leaves it to you: keep editing as long as
you like, then run **`/datarewriter reload`** (the save confirmation has a clickable button for it) and
everything you made appears at once, in one reload. `/reload` and rejoining refresh it too. Until then the
editor tells you how many edits are waiting, and `/datarewriter status` repeats the count.

### Recipe tweaks

`/recipetweaker` is the bulk counterpart: pick an item or `#tag` and choose what happens to it across
**all** recipes at once —

- **Remove its recipes** — every recipe that *produces* the item disappears (recipes you added with
  DataRewriter are kept).
- **Remove recipes using it** — every recipe with the item as an *ingredient* disappears.
- **Replace it in recipes** — the item (or `#tag`) is swapped for a second item or `#tag` in every
  recipe's ingredients; results are untouched.

Applying saves the matching [`remove`](#removing-recipes) /
[`replace_ingredients`](#replacing-ingredients) rule to `config/datarewriter/gui-recipes.json5` (delete it
there to undo), applies it live and reports the count in chat — run `/datarewriter reload` when you're
done to refresh EMI/JEI/REI. The screen remembers its slots and mode until the game quits.

### Loot table editor

`/loottableeditor` browses and edits every loot table on the server.

- The picker lists **every loot table** with a search box and a draggable scrollbar. Click one to edit it;
  typing an id that doesn't exist and pressing Enter creates a **new table** under that id. The picker
  remembers its search, filter and scroll position until the game quits.
- **Filter by item** shows only the tables that actually drop a chosen item — the quick way to answer
  "where can diamonds come from?" before changing that. It also takes a `#tag` (e.g. `#c:fishes`): tables
  dropping **any item from the tag** (or the tag itself) are shown.
- **Replace item…** / **Remove item…** work across many loot tables at once: pick the item **or one of its
  tags** (a `#tag` covers every item in it), a replacement item if replacing, then choose **which tables**
  — every table, one mod (`somemod:*`), only chests, or anything *except* block/entity drops
  (`!*:blocks/*`); quick preset buttons cover the common cases. Entries keep their weights, counts and
  conditions when replaced. The operation is saved as a
  [`replace_items`/`remove_items`](#item-rules-across-tables) rule in
  `config/datarewriter/gui-loot-tables.json5` — delete the rule there to undo it. (`*` wildcard patterns
  typed by hand remain a config-file feature.)
- The editor shows the table's **pools** with entries as item slots. Scroll on an entry to change its
  **weight** (yellow `w` badge; the tooltip shows its share of the pool), Shift+scroll for the **count**,
  Ctrl+scroll to grow a count **range** (1–3, …), Alt+scroll for a **drop chance** (the blue `%` badge — a
  `random_chance` condition: even when a roll picks the entry, it only drops that often); middle-click
  types an exact weight — the same popup offers **Convert to #tag…** for item entries (listing every tag
  the item is in) and **Edit components…** for item and tag entries. Scroll over the `rolls:` text to
  change how many entries a pool hands out (Shift makes it a range).
- Drops with **data components** are drawn as they'll actually look in a chest: `set_components`,
  `set_name` and `set_potion` functions are applied to the icon (custom names, potion colors, enchanted
  gear, …), and randomly-enchanted drops get the enchant glint. **Edit components…** (middle-click)
  writes a plain `set_components` function on the entry — the same validated JSON dialog as the recipe
  editor — so a drop can carry a custom name, enchantments or any other component data. Everything else
  (other functions, a conditional `set_components`) is untouched by the editor and preserved exactly
  as-is when saving.
- Click a slot to set its item (`#tags` work too) — or drop an item from the inventory panel or EMI
  **anywhere on a pool's card** to add it to that pool, or exactly on a slot to replace that entry.
  Right-click the `+` slot to add an **empty entry** (a weighted chance to drop nothing); right-click an
  entry deletes it, `✕` deletes a pool.
- **Complex entries are editable too**: an `alternatives`/`group`/`sequence` entry shows as a bundle with
  its entry count — click it to open a sub-editor for its children (same controls, nesting included), and
  a nested `loot_table` reference shows as a chest — click it to pick a different target table.
  Middle-click a pool's `+` slot to **create** one of these. Anything beyond that (inline nested tables,
  `dynamic` entries, custom conditions) still shows as a locked slot and is **preserved exactly as-is**,
  same for fancy count/chance formulas.
- **Save table** stores the whole table as a replacement (also the only way to keep edits or deletions of
  the table's *own* entries); with every pool deleted it saves an empty-table rule instead. **Save added
  pools** appends just your new pools, leaving the table's own loot to its mod — survives mod updates,
  like config [`modify`](#loot-tables). **Empty table** removes all pools *locally* — nothing is saved
  until you press Save table — and **Reload** discards unsaved edits and refetches the table.
- **Unsaved edits survive closing the editor**: each table keeps its draft (per table id, until the game
  quits) and reopening the table restores it, clearly marked. Save makes a draft permanent, Reload throws
  it away.

Saves are validated with the vanilla loot table parser, written to
`config/datarewriter/gui-loot-tables.json5` (a normal config file) and **applied to the running server
immediately** — kill the mob or open the chest and the new drops are live, no `/reload`.

### Loot injected by other mods

Mods that inject loot at runtime through the loader's loot events (Fabric's `LootTableEvents`, NeoForge's
`LootTableLoadEvent`; RPG-series equipment injectors and the like) are fully accounted for:

- Injected pools **show up in the editor**, marked purple as "Injected — by \<mod\>?" (the mod is guessed
  from the ids inside the pool). They are read-only there: the injection is generated by that mod's own
  config, which is the place to restructure it. Mods that don't append a pool but **merge their entries
  into one of the table's own pools** (loot-weight tweakers like Brocraft Cobblemon Additions) are
  detected too: those entries get a purple frame inside the pool, are read-only, and are never written
  into your save — the mod re-merges them on every load anyway.
- If an injecting mod's config uses a `#tag`, the injected pool usually shows **individual items instead
  of the tag** — that is accurate, not a display bug: such mods expand the tag into one plain item entry
  per member when they build the pool, so no tag entry exists in the live table. Bulk Replace/Remove still
  catches them either way: by item id, and also by `#tag` — tag rules match every item *belonging* to the
  tag, not just literal tag entries.
- [`replace_items`/`remove_items`](#item-rules-across-tables) rules — from the config **and** from the
  GUI's bulk Replace/Remove item — **do apply to injected loot**: after the other mods' injections run,
  DataRewriter re-applies its item rules on top (at startup, after `/reload`, and immediately on a GUI
  bulk edit). So "replace all diamonds with emeralds" catches injected diamonds too.
- Saving a table in the editor re-runs the mods' loot events on it, so injected loot survives live edits
  instead of disappearing until the next `/reload`.

### Natively supported mods

Hand-tuned layouts — drawn on the mod's own GUI where one exists — ship for the mods below. Each appears
automatically when that mod is installed; nothing is required at runtime.

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
| Eternal Starlight | `alloy` (alloy furnace GUI), `drying`, `geyser_smoking`, `tool_modification`, `mana_crystal` | alloy: 3×3 ingredients, up to 3 results with amounts (result *ranges* stay JSON-only), burn time; drying rack and geyser draw the station block (the rack gets a campfire when "needs fire below" is set); geyser's input is a bare item id with its own count field; tool modification and mana crystal are the mod's crafting-table special recipes (crafting GUI, item-id slots + mana type / book tab fields). The dynamic `accessory_combination` type has no data and is hidden. |
| Forbidden Arcanus | `clibano_combustion` (the mod's own JEI panel), `apply_modifier` | clibano: one or two ingredients (the second slot may stay empty), optional enhancer relic slot, `{"id","count"}` result, XP / cooking time / fire type (the drawn flame follows it) / residue type + chance fields; apply modifier: smithing GUI with template + addition and the modifier's id — the base slot stays empty because the recipe applies to every item the modifier accepts. Hephaestus Forge rituals get [their own editor](#hephaestus-forge-rituals-forbidden-arcanus). NeoForge-only, like the mod itself. |
| Ars Nouveau | `enchanting_apparatus`, `enchantment`, `armor_upgrade`, `imbuement`, `glyph`, `crush`, `budding_conversion`, `scry_ritual`, `dye` | the three apparatus types and the imbuement chamber use the same pedestal ring as the mod's JEI pages (up to 8 pedestal items, `#tags` allowed); apparatus enchanting has no result item — just the enchantment id, level and source cost; glyph is a plain list of up to 9 ingredients priced in XP; crush takes up to 4 results with drop chances (Alt+scroll; the per-result `maxRange` always saves as 1, like every recipe the mod ships); budding conversion and scry ritual are two-slot recipes — scry's slots hold bare *tags* (the highlight is a BLOCK tag, so type it by hand when no item tag mirrors it); dye is a shapeless crafting recipe under its own type. `caster_tome`, `summon_ritual` and the other spell-data types stay JSON-only. NeoForge-only, like the mod itself. |
| Mystical Agriculture | `infusion`, `awakening`, `enchanter`, `reprocessor`, `soul_extraction`, `soulium_spawner` | drawn on the mod's own JEI panels: the two altars keep their ring (infusion: seed + up to 8 pedestal items; awakening: the four corner slots are the essence vessel *stacks* with counts, the edges the pedestal items); enchanter takes two counted ingredients + the enchantment id; soul extraction pairs an input with the mob soul type id and a souls amount; soulium spawner takes a counted input and up to four weighted entity ids as fields. The mod's own seed recipes use its dynamic `crop_component` ingredients — those load with empty slots (recipes you make here use plain items/`#tags`, which work just the same). `farmland_till` and `soul_jar_empty` are special crafting behaviors with dynamic ingredients, hidden from the type picker. NeoForge-only, like the mod itself. |
| Eidolon Repraised | `worktable`, `crucible`, the five `ritual_brazier*` types, `athame_foraging`, `chant_conversion`, `dye` | drawn on the mod's codex pages: worktable is a 3×3 shaped grid *plus* the four corner reagents (saved as extra key letters + the `reagents` row); crucible takes up to 4 steps of up to 4 items with a stirs count per step (two of the mod's own recipes exceed 4 items in a step and load truncated); brazier rituals share the reagent + pedestal arc + focus slot, with the variant's own extras (crafted result, summoned entity, commands, ritual id + invariant item, or structure). The mod's worktable recipes can't be re-encoded by the game, so the Load/EMI fill starts that one from scratch. `chant`/`command_chant` (sign sequences) are hidden — JSON-only. NeoForge-only, like the mod itself. |
| Malum | `spirit_infusion`, `soul_binding`, `runeworking`, `spirit_focusing`, `spirit_repair`, `unchained_transmutation`, `void_favor`, `node_smelting`, `node_blasting` | drawn on the mod's JEI codex pages. Spirit slots take the spirit *items* and save them as spirit type ids with counts; sized ingredients take counts via scroll and `#tags`. Infusion/soul binding: spirits down one side, extra inputs down the other (binding's result is a geas effect id field); runeworking includes the rune sound id; focusing has time + durability cost; repair takes the repair material, up to six repairable items (the mod's longest lists reach twelve — those load truncated) and the repair percentage, with no result (it's derived); the node types are furnace recipes whose output is an ingredient (tags allowed) plus an output count. `conjuncture_crystallarium` and `ore_derealization` (block-state/world-rule data) are hidden — JSON-only. NeoForge-only, like the mod itself. |
| Create (6.x, Fabric port) | `mixing`, `compacting`, `crushing`, `milling`, `pressing`, `cutting`, `deploying`, `item_application`, `filling`, `emptying`, `splashing`, `haunting`, `sandpaper_polishing`, `mechanical_crafting` | drawn like Create's own EMI recipe views: the same slot positions, arrows and slot frames, and the animated machines (mixer, press, crushing wheels, saw, deployer, spout with its fluid stream, drain, fan, millstone, crafter, blaze burner when heat is set). Fluid ingredients/results are proper fluid slots (`#fluid tags` allowed for inputs; amounts in mB, scroll or middle-click), results take drop chances (Alt+scroll; chance slots get Create's dotted frame), basins have `Heat` (`none`/`heated`/`superheated`) and `Time (ticks)` fields, deploying/item application `Keep held item`, mechanical crafting a 9×9 grid — Create raises vanilla's 3×3 pattern cap that far, and empty rows/columns are trimmed on save, so a 2×2 recipe drawn anywhere in the grid stays 2×2 — with `Accept mirrored`. Loading a Create recipe from EMI fills item and fluid slots correctly even though Create keeps both in one list. Not covered: `sequenced_assembly` (nested step recipes — write those in JSON). |

### Hephaestus forge rituals (Forbidden Arcanus)

With Forbidden Arcanus installed, the recipe editor's type picker gains **Hephaestus forge ritual**,
drawn on the mod's own JEI panel: the ring of eight pedestal slots, the main ingredient on the forge in
the center, up to four enhancer relics on the left, the result on the right, and fields for the four
essence costs, the forge tier (with "exact tier only"), the magic circle and the duration. A ritual
input with `amount: 3` shows as three filled pedestals, exactly like in JEI.

Rituals are **not recipes** — they live in a [datapack registry](#datapack-registries) — so this screen
saves differently: type the ritual's id, **Load** fetches it from the server, and Save writes it as a
`registries.add` rule to `config/datarewriter/gui-registries.json5` and applies it to the running
server immediately. The forge picks the change up at once; other players' JEI shows it after they
rejoin the world. Only `create_item` rituals (the ones that craft an item) are editable — loading a
tier-upgrade ritual clears the result slot.

### Modded recipe types — automatic

Every other modded recipe type works **automatically**: the server syncs every loaded recipe to the
client, so when the editor opens it takes one existing recipe of each modded type, encodes it back to JSON
with the mod's own codec, and infers the editing layout from that sample — ingredient-shaped values become
slots (counted ones get scroll-to-set amounts, fluids get a fluid picker), numbers/strings/booleans become
fields pre-filled with the sample's values. These auto layouts draw on a generic panel and appear in the
type selector as e.g. "Cutting (farmersdelight)".

Two limits: a type only appears if at least one recipe of it is currently loaded (that's where the sample
comes from), and the inference is heuristic — an exotic JSON shape may come out wrong or miss a field.
When one does, write the layout yourself.

### Editor layouts — manual override

For full control — the mod's real GUI texture, exact slot positions, required flags, corrected field
mappings — write a layout in `config/datarewriter/editor-layouts/*.json5` **on the client**; it replaces
the auto layout for that type. In its minimal form (generic panel, auto-placed slots) a layout is just the
JSON mapping:

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

(To learn a mod's recipe JSON field names, copy a real recipe from its jar — `data/<modid>/recipe/` — as
the reference.)

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

- Slot `x`/`y` are the item positions from the mod's Menu/ScreenHandler class (the numbers passed to
  `new Slot(...)`); the crop offset is subtracted automatically. Without a texture, `crop` (if given) just
  sets the panel size.
- `field` paths support nesting (`result.item`) and arrays (`ingredients[]` appends).
- `format` controls the JSON written for a picked item: `string` (`"mod:item"` / `"#mod:tag"`),
  `ingredient` (`{item}`/`{tag}`), `counted_ingredient` (`{ingredient, count}`), `item` (`{id, count}`),
  `item_named` (`{item, count}`), `fluid` (`{id, amount_mb}`) or `fluid_amount` (`{id, amount}`) — the
  fluid formats make the picker list fluids.
- Slots also take `chance: true` (Alt+scroll sets a drop chance, written as a `chance` key) and
  `tags: true` (allow `#tags` beyond what the format permits — fluid formats then write `{tag}` instead of
  `{id}`; only use where the recipe type accepts it).
- Add `inventory_y: 84` to show the player inventory inside the panel like a real container screen — with
  a full 176×166 GUI texture, or without any texture (the editor draws a clean recipe-card panel).
- `fields` become text boxes: `type` is `int`, `float`, `string` or `bool`; `required: true` blocks saving
  while empty; an optional `suggestions: ["a", "b"]` list is shown as a tooltip (auto layouts fill this
  from existing recipes).

An entry of just `{ type: "somemod:sometype", hidden: true }` removes that recipe type from the editor
entirely — for dynamic or dummy recipe types that can't sensibly be created.

A commented example is generated at `config/datarewriter/editor-layouts/example.json5` on first use, and
the files are re-read every time the editor opens, so you can tweak a layout and just reopen the screen.

Saved recipes always write ingredients in the object form recipes actually use (`{"item": …}` /
`{"tag": …}`); in hand-written config files you may also use the plain-string shorthand
(`"minecraft:oak_log"`, `"#minecraft:planks"`) for the vanilla types and for anything built on the vanilla
`pattern`/`key` structure, such as Create's mechanical crafting.

## Commands

You don't have to dig through mod jars to find recipe or loot table ids. The whole **`/datarewriter` tree
requires operator level 2**; it is registered server-side, so it works from a vanilla client, and listings
reflect the current state (after your rules) — recipes you added under the `datarewriter:` namespace show
up too.

```
/datarewriter list recipes                   every mod that has recipes, with counts — click a mod to drill in
/datarewriter list recipes <mod> [page]      its recipe ids, paginated with prev/next — click an id to copy it
/datarewriter list loot_tables [mod] [page]  the same for loot tables
/datarewriter hand                           the id of the held item (main hand, else off hand)
/datarewriter errors                         every error and warning from the last config load
/datarewriter status                         config files read, and whether each loot table add is in effect
/datarewriter reload                         push the recipe list to everyone (refreshes EMI/JEI/REI)
/recipeeditor                                open the recipe editor             (client, needs the mod)
/recipetweaker                               open the bulk recipe tweaks screen (client, needs the mod)
/loottableeditor                             open the loot table editor         (client, needs the mod)
```

`/datarewriter hand` prints a bare `mod:item` line (also written to the server log). It lands in your
clipboard immediately if your client runs DataRewriter; on a vanilla client, click the line to copy — the
same goes for ids in the `list` output. `/datarewriter errors` repeats the last load's problems in chat
and in the log; `/reload` re-checks them.

The two reloads do different things:

| | Re-reads config files | Applies rules | Refreshes EMI/JEI/REI |
|---|---|---|---|
| `/reload` (vanilla) | yes | yes | yes |
| `/datarewriter reload` | no | no | yes |
| An editor save | no | immediately | no — run `/datarewriter reload` when you're done |

## File locations

Config lives on the **server** (in singleplayer, your own instance) — editor saves are written there, never
on a connecting client. Layouts are the one client-side exception.

| What | Where |
|---|---|
| Your rules — any depth, any name | `config/datarewriter/**/*.json5`, `.json` |
| Generated, fully commented example | `config/datarewriter/example.json5` |
| [Recipe editor](#recipe-editor) and [tweaks](#recipe-tweaks) saves | `config/datarewriter/gui-recipes.json5` |
| [Loot table editor](#loot-table-editor) saves | `config/datarewriter/gui-loot-tables.json5` |
| [Ritual editor](#hephaestus-forge-rituals-forbidden-arcanus) saves | `config/datarewriter/gui-registries.json5` |
| [Editor layouts](#editor-layouts--manual-override) (**client**) | `config/datarewriter/editor-layouts/*.json5` |
| Generated layout example (**client**) | `config/datarewriter/editor-layouts/example.json5` |

The two `gui-*.json5` files are ordinary config files: edit them by hand, delete a rule to undo it, or
move it into your own file.

## Troubleshooting

- **A config loot table drops nothing?** If the game rejects a table's JSON (typically an item from a mod
  not installed on this server), the whole table is dropped: look in the log for
  `Couldn't parse element minecraft:loot_table/<id>`; DataRewriter warns after every (re)load which config
  tables were rejected.
- **A recipe didn't appear?** Check the server log. A broken recipe produces
  `Parsing error loading recipe <id>` (from vanilla) or an error naming your config file (from
  DataRewriter). After `/reload`, ops also see the error count in chat — `/datarewriter errors` prints
  them.
- **Item in a removed recipe still craftable?** Some mods register several recipes for one item (or a
  datapack adds one). Remove by `output:` instead of `id:` to catch them all.
- **Recipe book:** added recipes have no unlock advancement, so the green recipe book won't advertise them
  — but they craft fine, and EMI/JEI/REI list them.
- **A recipe you just saved isn't in EMI?** That is on purpose: the recipe is already live (craft it and
  see), only the viewer's list is stale. Run `/datarewriter reload` — or click the button in the save
  confirmation — and every pending edit shows up in one refresh. See [Recipe editor](#recipe-editor).
- **In-game edits vanish after a restart / "the config doesn't have my changes"?** Editor saves are
  written by the **server you are connected to**, into *its* `config/datarewriter/` (in singleplayer that
  is your own config folder; on a dedicated server it is the server's, never your client's). If the file
  is missing there after you pressed Save, the save did not happen — the editor shows the server's answer
  right in the screen (✔ green = written and applied; ✘ red = not saved, and why). The usual reasons: you
  are not an **operator** (permission level 2) on that server, the server could not write its config
  folder, or the edit was made on a different server/world than the one you checked. The server log has
  `Loot editor: wrote /full/path/gui-loot-tables.json5` for every successful save (and a `WARN` for every
  refused one). Hosting panels that reset the `config` folder from a template on restart will also wipe
  these files — keep a copy in your modpack config if that is your setup.
- **`/datarewriter status`** is the first thing to run when an edit seems lost: it lists the config
  directory the server actually read, every config file with its rule counts, and for each loot table
  `add` where it came from and whether it is *in effect right now* (loaded into DataRewriter's cache
  **and** present in the live registry with at least that many pools) — or why not ("NOT LOADED",
  "rejected by the game", "another mod replaced it"). The same is logged at startup
  (`Config: <dir> — files…`, `Loot tables added: id (file)…`).
- **The same table added in two config files** — e.g. a modpack config *and* an in-game save
  (`gui-loot-tables.json5`) — is a classic: files load in name order and the later one wins, so which
  version you get depends on file names. The loader warns about it (`/datarewriter errors`); keep only
  one.
- **Don't keep `gui-loot-tables.json5` / `gui-recipes.json5` open in a server panel's file editor while
  saving in game.** Panel editors show the content from when you opened the file (so in-game saves don't
  appear there), and saving that tab — or its autosave — writes the stale copy back over the in-game
  edits; they then survive only until the next `/reload` or restart. DataRewriter notices this: the next
  in-game save answers with a note that the file was changed outside the game, and after a reload ops get
  a red warning in chat (plus a `WARN` in the log) that the file was overwritten since the last in-game
  save.
- **EMI stuck on "EMI Reloading..." after `/reload` or when joining the server?** Fixed — update
  DataRewriter. Older versions re-synced recipes to clients without the accompanying tag sync; EMI (and
  JEI/REI) only reload after receiving a matched tags+recipes pair, so the unpaired packet left EMI
  waiting, and the *next* join could freeze its reload entirely (log: `[EMI] World is null`). One extra
  `/reload` always unstuck it.

## Addon API

Other mods can contribute rules from code, listen for recipe and loot table changes, and ship recipe
editor layouts for their own recipe types through the `datarewriter` and `datarewriter_client`
entrypoints — see [`API.md`](API.md).

## Compatibility

| Works with | How |
|---|---|
| **Vanilla clients** | Fully supported: rewritten recipes reach them through normal recipe sync, and the `/datarewriter` commands are server-side, so ids stay clickable-to-copy in chat. Only the editors need the mod on the client. |
| **[EMI](https://modrinth.com/mod/emi)** | Optional, and the best companion: item and fluid side panels with drag & drop into any slot, the **fill (+)** button that loads an existing recipe into the editor, and **R**/**U** lookups over every DataRewriter screen. Nothing is required at runtime. |
| **JEI / REI** | No integration needed — they read the synced recipe list, so added and removed recipes show up after `/datarewriter reload` (or `/reload`, or rejoining). |
| **Mods that add recipe types** | Removal and JSON `add` always work. In the editor, vanilla and fourteen mods get [hand-drawn layouts](#natively-supported-mods) and everything else is [detected automatically](#modded-recipe-types--automatic); a [manual layout](#editor-layouts--manual-override) overrides either. |
| **Mods that inject loot at runtime** | RPG-series equipment injectors, loot-weight tweakers and anything else using the loader's loot events (Fabric `LootTableEvents`, NeoForge `LootTableLoadEvent`): their pools are shown, preserved across live edits, and covered by [item rules](#item-rules-across-tables). See [loot injected by other mods](#loot-injected-by-other-mods). NeoForge's global loot modifiers are a different mechanism — they rewrite the *rolled* drops, not the table — so the editor doesn't show them and item rules don't touch them; they still apply on top of whatever DataRewriter produced. |

DataRewriter targets **1.21.1** specifically, on Fabric and NeoForge: 1.21.2+ replaced `RecipeManager`'s
internals (`RecipeMap`) and would need a different implementation.

## Notes for developers

- MultiLoader layout, same as NPCs_LD: `common/` holds all the code (Mojmap, vanilla-only — no Fabric API
  — including the mixins, which Loom remaps into the Fabric jar); `fabric/` and `neoforge/` are thin glue
  (`DatarewriterFabric` / `DatarewriterNeoForge` plus their client classes) that construct the menus and
  register commands, payloads and lifecycle events. Loader specifics reach common through three
  `ServiceLoader` interfaces: `Platform` (config dir, loaded mods, the loot load hooks), `Network` (send /
  can-send per side) and `ClientPlatform` (fluid sprites, standalone models). `./gradlew build` builds all
  three modules; `:fabric:runClient` / `:neoforge:runClient` start the dev clients (`fabric/run`,
  `neoforge/run`).
- Recipes are rewritten in two phases: `id`/`mod` rules and additions are applied to the raw recipe JSON
  map (mixin at the head of `RecipeManager.apply`), while `output`/`input`/`type` rules run after startup
  / `/reload` completes — item tags are not bound during recipe loading, so tag matching earlier would
  silently fail.
- Loot tables are rewritten in the raw JSON map too (mixin in
  `SimpleJsonResourceReloadListener.scanDirectory`, filtered to the `loot_table` directory). That hook
  runs before recipes in vanilla's reload pipeline, so it's also where the config is (re)loaded. The final
  JSON map is kept in memory — it's what the loot editor reads and searches.
- Loot editor saves apply without a reload by rebinding the table's `Holder.Reference` inside the frozen
  reloadable registry (what vanilla itself does during load); brand-new ids briefly unfreeze the registry
  to register. Before binding, the loader's loot load hooks (Fabric's `LootTableEvents` REPLACE + MODIFY,
  NeoForge's `LootTableLoadEvent` — `Platform.applyLootHooks`) are re-invoked on the freshly parsed table
  and the config's item rules are applied over the result, so runtime loot injections from other mods are
  preserved *and* covered by item rules; the JSON cache stays at the datapack level, so injections never
  stack. A post-injection pass (`LootInjectionRewriter`, from `Datarewriter.onServerStarted` /
  `onDataPackReloaded`, which each loader wires to its server-started and end-of-`/reload` events) does
  the same on reload: it encodes each live table back to
  JSON with the vanilla codec, re-applies the item rules and rebinds tables where injected entries
  matched. The editor shows injected pools by diffing the live table's encoded pools against the
  datapack-level cache (the extra tail pools are the injected ones).
- Bundled layouts can carry a `Decoration` (`EditorLayout.decoration`) that draws over the panel and under
  the slot contents; `GuiBlocks` renders block states and standalone models (Flywheel partials, fetched
  through `ClientPlatform.standaloneModel` — Fabric's injected `ModelManager.getModel(id)`, NeoForge's
  `ModelResourceLocation.standalone`) with Catnip-`GuiGameElement` transform semantics, so a mod's
  recipe-viewer animations can be ported by id without a compile dependency — see `CreateLayouts`.
- Screenshot harness: run the client with
  `./gradlew :fabric:runClient -PscreenshotTypes=create:mixing,create:pressing` (also `loot:<table id>`
  for the loot editor; `:neoforge:runClient` takes the same properties) and it opens the recipe editor on
  each type once in a world (loading the first existing recipe of that type, or sample values with
  `-PscreenshotSample=true`), saves `screenshots/<type>.png` and quits. Works headless:
  `xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew :fabric:runClient --args="--quickPlaySingleplayer <world> --width 1280 --height 720"`
  with `LIBGL_ALWAYS_SOFTWARE=1` and `onboardAccessibility:false` in `fabric/run/options.txt`. The
  NeoForge run ignores `--args`; pass the world as `-PquickPlay=<world>` instead.
- Addon API ([`API.md`](API.md)): rules from code (`RewriteRules`), change events (`DataRewriterEvents`)
  and editor layouts from code (`EditorLayouts.register`), through the `datarewriter` /
  `datarewriter_client` entrypoints (ServiceLoader providers on NeoForge).

## Ideas / future

- More data sections in the same format (advancements? item tags?)

## License / credits

[MIT](LICENSE.txt). Create's recipe layouts are drawn after
[Create](https://modrinth.com/mod/create)'s own EMI views; no Create code is compiled in.
