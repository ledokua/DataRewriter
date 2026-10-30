# DataRewriter (26.1 branch) — notes for Claude Code sessions

Recipe / loot / tag / registry rewriting for Minecraft 26.1.2, NeoForge only, standalone Gradle build (Java 25,
ModDevGradle 2.0.148, NeoForge 26.1.2.114). The `multiloader` branch (local checkout `../DataRewriter`, this is
its git worktree) holds the 1.21.1 Fabric + NeoForge build with the same packages and class names: carry every
change over to the other branch when it applies to both.

## Read first
- `README.md` — user docs, the full config reference, "26.1 notes" (what differs from 1.21.1) and the
  developer source map. Update it with every config key or behavior change.
- `API.md` — addon API (`net.ledok.datarewriter.api`).

## 26.x vs 1.21.1 (the traps)
- `Identifier` (was ResourceLocation), `ResourceKey.identifier()`, `TagKey.location()`, registry `getValue`/`get`
  (Optional holder) / `listElements`, `RegistryAccess.lookupOrThrow`.
- Recipes: `RecipeHolder.id()` is a `ResourceKey<Recipe<?>>`; no `getResultItem`/`getIngredients` — use
  `RecipeView`. Ingredients in JSON are bare `"id"`/`"#tag"` strings; `RecipeJsonNormalizer` converts 1.21.1
  objects. Live list swaps go through `RecipeRewriter.replaceRecipes` (accessor + `finalizeRecipeLoading`).
- Clients get no recipes from vanilla: operators get all of them (`RecipeRewriter.sendToEditor`), the client
  reads them through `client/ClientRecipes` (integrated server in singleplayer).
- Reload order: tags → loot → recipes (tags first since 1.21.2): the config reloads in `TagLoaderMixin`.
- Registries load as parallel tasks: rewriting lives in `PendingRegistrationMixin`, additions in
  `RegistryLoadTaskMixin`; stats are atomic.
- GUI: `GuiGraphicsExtractor`, `extract*` methods, ARGB colors need alpha (`0xFF......`), 2D `Matrix3x2fStack`
  pose, input events are records (`MouseButtonEvent`, `KeyEvent`). 3D content goes through `GuiBlocks.Scene`
  (picture-in-picture). Container `imageWidth/Height` are final: `AbstractContainerScreenAccessor`.
- Reference sources: `../../../emi/neoforge/build/moddev/artifacts/minecraft-patched-26.1.2.114-sources.jar`
  and the NeoForge `-sources.jar` in the Gradle cache.

## Build & test
- `./gradlew build` → `build/libs/DataRewriter-neoforge-<version>+26.1.2.jar`. EMI Continued is compile-only
  from the local jar named by `emi_jar` in `gradle.properties`.
- `runServer` (`run-server/`, port 25617, a test config in `run-server/config/datarewriter/`) and `runClient`
  (`run/`, world `smoke`). Gradle does not pass stdin to the server console.
- Headless client: own Xvfb display (check `/tmp/.X11-unix/` — `pgrep -f` matches your own shell), then
  `env -u WAYLAND_DISPLAY DISPLAY=:<n> ./gradlew runClient -PquickPlay=smoke -PscreenshotTypes=<types>`.
  Kill only java processes whose command line contains `DataRewriter-26.1/`, by PID.
