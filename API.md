# DataRewriter addon API

Other mods can feed DataRewriter **rules from code**, react when **recipes or loot tables change**, and ship
**recipe editor layouts** for their own recipe types. The API is loader-agnostic and small:
`net.ledok.datarewriter.api` plus `EditorLayouts` on the client. Rules from code go through exactly the
same pipeline as the config files, so everything the [README](README.md) says about a rule — wildcards,
`#tags`, table scopes, when tag rules apply, how the editors interact with rules — holds for its
programmatic twin.

## Setup

Depend on `datarewriter` (and compile against its jar), then tell DataRewriter about your classes:

**Fabric** — `fabric.mod.json` entrypoints:

```json
"entrypoints": {
  "datarewriter": ["com.example.myaddon.MyAddon"],
  "datarewriter_client": ["com.example.myaddon.MyAddonClient"]
}
```

**NeoForge** — `ServiceLoader` provider files in your jar:

```
META-INF/services/net.ledok.datarewriter.api.DataRewriterAddon         → com.example.myaddon.MyAddon
META-INF/services/net.ledok.datarewriter.api.DataRewriterClientAddon   → com.example.myaddon.MyAddonClient
```

The `datarewriter` entrypoint / `DataRewriterAddon` runs on both sides during mod initialization, before
any config is loaded; `datarewriter_client` / `DataRewriterClientAddon` runs on the physical client at the
same time. A multi-loader addon can ship both declarations in one class pair.

## Rules from code

`RewriteRules.addProvider(source, rules)` registers a provider. It is asked for its rules on **every**
config (re)load — server start and `/reload` — with a fresh `Builder`, so it can depend on state that
changes between loads (which mods are installed, a config option of your own). `source` is the label
shown in `/datarewriter status` (as `addon <source>`) and in error messages; your mod id is a good choice.

```java
public class MyAddon implements DataRewriterAddon {
    @Override
    public void register() {
        RewriteRules.addProvider("myaddon", rules -> {
            rules.removeRecipes("minecraft:*_from_smelting")      // id pattern, '*' wildcards
                 .removeRecipes(null, "somemod", null, "#c:ingots/copper", null) // id, mod, type, out, in
                 .replaceIngredients("minecraft:iron_ingot", "#c:ingots/iron")
                 .removeLootItems("minecraft:emerald", "minecraft:chests/*, !minecraft:chests/village/*");
            if (Platform.INSTANCE.isModLoaded("farmersdelight")) {
                rules.addRecipe(ResourceLocation.fromNamespaceAndPath("myaddon", "stew"), MY_STEW_JSON);
            }
        });
    }
}
```

One builder method per config operation:

| Builder | Config equivalent |
|---|---|
| `removeRecipes(idPattern)` · `removeRecipesByMod(mod)` · `removeRecipes(id, mod, type, output, input)` | `recipes.remove` |
| `addRecipe(id, json)` | `recipes.add` |
| `replaceIngredients(from, to)` | `recipes.replace_ingredients` |
| `removeLootTables(idPattern)` · `removeLootTablesByMod(mod)` | `loot_tables.remove` |
| `addLootTable(id, json)` | `loot_tables.add` |
| `modifyLootTables(idPattern, pools)` · `modifyLootTables(id, mod, pools)` | `loot_tables.modify` |
| `replaceLootItems(from, to, tables)` | `loot_tables.replace_items` |
| `removeLootItems(item, tables)` | `loot_tables.remove_items` |
| `removeRegistryEntries(registry, idPattern)` | `registries.remove` |
| `addRegistryEntry(registry, id, json)` | `registries.add` |
| `modifyRegistryEntries(registry, idPattern, merge)` | `registries.modify` |

Arguments use the config syntax: id patterns take `*` wildcards, item matches take wildcards or a
`#tag`, `output`/`input`/`to` take an item id or `#tag`, `tables` is a scope list (`"minecraft:chests/*,
!*:entities/*"`; `null` or `"*"` = every table). Every method validates like the config parser and throws
`IllegalArgumentException` on bad input — a provider that throws contributes **nothing** for that load and
counts as one config error (`/datarewriter errors` shows the message), exactly like a broken file.

The registry methods edit **datapack registries** (Forbidden Arcanus rituals, enchantments, ...);
they apply while the world loads, never on `/reload` — see the README's
[Datapack registries](README.md#datapack-registries) for the semantics and the removal caveat.

Providers run after the config files, so on the same id an `add` from a file wins over yours, the way a
later file wins over an earlier one. Rules that need parsed recipes or bound tags (`output`, `input`,
`type`, `#tag` item matches) apply after startup / `/reload` completes, like their file counterparts, and
item rules also cover the in-game editors: an op saving a loot table gets your `replaceLootItems` applied
over the result.

## Change events

`DataRewriterEvents.RECIPES_CHANGED` and `DataRewriterEvents.LOOT_TABLES_CHANGED` are plain listener lists,
fired on the server thread with the `MinecraftServer`:

```java
DataRewriterEvents.RECIPES_CHANGED.add(server -> MyRecipeCache.invalidate());
```

`RECIPES_CHANGED` fires after the post-load passes on server start and `/reload`, and after every recipe
editor / recipe tweaker save; `LOOT_TABLES_CHANGED` likewise for loot tables (the post-load pass, single
table saves, bulk item rules). Server-side lookups see the new data immediately; clients are only pushed
the recipe list on `/datarewriter reload` (see the README's [Commands](README.md#commands)). A listener
that throws is logged and skipped — it can't break a save or a reload.

## Recipe editor layouts (client)

A mod that adds a recipe type can ship the recipe editor's layout for it, so its users get the real GUI
instead of an [automatically inferred](README.md#modded-recipe-types--automatic) one and never write a
layout file. From `registerClient()`, build the layout from the same JSON shape the
[layout files](README.md#editor-layouts--manual-override) use and register it:

```java
public class MyAddonClient implements DataRewriterClientAddon {
    @Override
    public void registerClient() {
        JsonObject layout = JsonParser.parseString("""
            {
              "type": "myaddon:cutting",
              "name": "Cutting Board (My Addon)",
              "texture": "myaddon:textures/gui/cutting_board.png",
              "crop": [0, 0, 176, 80],
              "slots": [
                {"x": 56, "y": 17, "field": "ingredients[]", "format": "ingredient", "required": true},
                {"x": 116, "y": 35, "field": "result", "format": "item", "result": true, "required": true}
              ],
              "fields": [
                {"field": "processing_time", "label": "Time", "type": "int", "default": "100"}
              ]
            }
            """).getAsJsonObject();
        EditorLayouts.register(EditorLayout.fromJson(layout));
        // A dynamic or dummy recipe type that can't sensibly be created in an editor:
        EditorLayouts.hide("myaddon:accessory_combination");
    }
}
```

Precedence when the editor opens: a user's own layout file for the type, then addon layouts, then the
layouts bundled with DataRewriter, then automatic inference — so registering a layout for a type
DataRewriter already bundles replaces the bundled one, and a user can still override yours from
`config/datarewriter/editor-layouts/`. Layouts are collected on every editor open, so registration order
doesn't matter as long as it happens during client initialization.

For layouts that need more than JSON can say — custom drawing under the slots, a non-standard JSON shape
for a slot's value — the `EditorLayout` constructor, `EditorLayout.decoration` and `SlotValueBuilder`
are public; the bundled Create layouts (`CreateLayouts`) are the reference for both.

## Stability

The shapes above — entrypoints, `RewriteRules.Builder`, the two event lists, `EditorLayouts.register` /
`hide` with `EditorLayout.fromJson` — are the intended long-term surface. The `RewriteConfig` records the
builder produces are internal and may still change between minor versions.
