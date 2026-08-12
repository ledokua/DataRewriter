# DataRewriter

A lightweight, **server-side only** Fabric mod for Minecraft **1.21.1** that removes, replaces, and adds recipes and loot tables through simple config files — inspired by KubeJS, but without a scripting engine.

- Works on dedicated servers and in singleplayer. **Vanilla clients can join** — rewritten recipes reach them through normal recipe sync.
- Changes apply on world/server start and on plain `/reload` — no restart needed while tuning.
- Config files allow `// comments`, trailing commas, and unquoted keys.

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
  result: [{ item: "minecraft:oak_planks", count: 6 }],
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

## Finding ids in game

You don't have to dig through mod jars to find recipe or loot table ids — as an operator, use:

- `/datarewriter list recipes` — every mod that has recipes, with counts; click a mod to drill in
- `/datarewriter list recipes <mod> [page]` — its recipe ids, paginated with clickable prev/next; **click any id to copy it** to your clipboard, ready to paste into a config file
- `/datarewriter list loot_tables [mod] [page]` — the same for loot tables

The command is registered server-side, so it works from a vanilla client. Listings reflect the current state (after your rules), so recipes you added under the `datarewriter:` namespace show up too.

## Troubleshooting

- **A recipe didn't appear?** Check the server log. A broken recipe produces `Parsing error loading recipe <id>` (from vanilla) or an error naming your config file (from DataRewriter). After `/reload`, ops also see the error count in chat.
- **Recipe book:** added recipes have no unlock advancement, so the green recipe book won't advertise them — but they craft fine, and REI/JEI/EMI list them.
- **Item in a removed recipe still craftable?** Some mods register several recipes for one item (or a datapack adds one). Remove by `output:` instead of `id:` to catch them all.

## Notes for developers

- Recipes are rewritten in two phases: `id`/`mod` rules and additions are applied to the raw recipe JSON map (mixin at the head of `RecipeManager.apply`), while `output`/`input`/`type` rules run after startup / `/reload` completes — item tags are not bound during recipe loading, so tag matching earlier would silently fail.
- Loot tables are rewritten in the raw JSON map too (mixin in `SimpleJsonResourceReloadListener.scanDirectory`, filtered to the `loot_table` directory). That hook runs before recipes in vanilla's reload pipeline, so it's also where the config is (re)loaded.
- Targets 1.21.1 specifically. 1.21.2+ replaced `RecipeManager`'s internals (`RecipeMap`) and would need a different implementation.

## Ideas / future

- More data sections in the same format (advancements? item tags?)
- Loot `modify` operations beyond pool injection (e.g. filtering single items out of tables)
