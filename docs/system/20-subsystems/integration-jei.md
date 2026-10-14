---
id: integration-jei
owns: [integration/jei/]
entrypoints: [StellurgyJeiPlugin#registerCategories, StellurgyJeiPlugin#register, StellurgyJeiPlugin#onRuntimeAvailable, JeiClientTickHandler#onClientTick]
depends-on: [api-public, dimension-planets, util-core, inventory-containers, blocks, items, network-wire]
depended-by: []
contracts: [C4, C7]
confidence: high
---

## Purpose

Client-only Just Enough Items (JEI) integration. Exposes 18 recipe categories (tabs)
so players can browse Stellurgy machine recipes, gas-giant harvest gasses,
asteroid drops, orbital-laser-drill ores, fueling-station fuels and station-assembler
steps. Reads existing recipe/registry/config data and repackages it into JEI's display
API; it declares **no** persisted state, packets, mixins or registry entries of its own.

## Responsibility boundary

Owns: the `@JEIPlugin` entrypoint, the shared category/recipe base classes, and one
`Category / RecipeHandler / RecipeMaker / Wrapper` quartet per machine tab.
Does NOT own: the recipe data itself (`libVulpes` `RecipesMachine`, `FuelRegistry`,
`DimensionProperties`, the galaxy's `getAsteroidTypes()`), the blocks/items shown as
catalysts/ingredients, or the `PacketDimInfo` that triggers gas-giant refresh
(network-wire). JEI is a pure read-side projection; disabling JEI removes only the GUI.

## Shared pattern (read once, applies to every tab)

Each machine tab is four classes in a per-machine subpackage:

| class | JEI interface | role |
|-------|---------------|------|
| `<M>Category`     | `IRecipeCategory<W>` | tab: UID, title, icon, slot layout (`setRecipe`), background (`drawExtras`) |
| `<M>RecipeHandler`| `IRecipeHandler<W>`  | binds wrapper class → category UID; `isRecipeValid` |
| `<M>RecipeMaker`  | (static factory)     | pulls source data, returns `List<W>` for `registry.addRecipes` |
| `<M>Wrapper`      | `IRecipeWrapper`     | one recipe instance; supplies inputs/outputs via `getIngredients` |

`StellurgyJeiPlugin` (the single `@JEIPlugin`) wires all tabs in three vanilla-order phases:
`registerCategories` (adds 17 categories unconditionally + laser drill conditionally),
`register` (adds an `IAdvancedGuiHandler<GuiModular>` for covered-area tooltips, four
ingredient blacklist entries, all recipe handlers, all recipes, and all catalysts). UIDs
are `public static final String` constants on `StellurgyJeiPlugin` (gas-giant's lives on its own
category). **The base "standard machine" quartet** (`MachineCategoryTemplate` +
`MachineRecipe` + trivial `<M>Wrapper extends MachineRecipe` + boilerplate handler/maker)
covers the plain N-in/M-out machines; `<M>RecipeMaker` simply loops
`RecipesMachine.getInstance().getRecipes(clazz)` wrapping each in `<M>Wrapper`. The
special tabs replace `IRecipeCategory` directly and synthesise recipes from other sources.

`MachineCategoryTemplate` [MachineCategoryTemplate.java:19] draws the shared
`GenericNeiBackground.png` (163×55, 10 input + up-to-10 output 18px slots) and an animated
progress bar. `MachineRecipe` [MachineRecipe.java:18] extracts inputs/outputs/power/time
from an `IRecipe`; for `Recipe` chance-outputs it renames each stack with a normalised
`Chance: NN.N%` label [MachineRecipe.java:38-48] and draws `Power: N RF/t` / `Time: …`
[MachineRecipe.java:93-112].

## Standard machine tabs (10 — pure `MachineCategoryTemplate` quartets)

| tab (UID const) | recipe source class | catalyst block | notes |
|-----------------|---------------------|----------------|-------|
| rollingMachine  | `TileRollingMachine`   | blockRollingMachine  | plain |
| lathe           | `TileLathe`            | blockLathe           | plain |
| sawMill         | `TileCuttingMachine`   | blockCuttingMachine  | plain |
| crystallizer    | `TileCrystallizer`     | blockCrystallizer    | plain |
| electrolyzer    | `TileElectrolyser`     | blockElectrolyser    | plain |
| arcFurnace      | `TileElectricArcFurnace` | blockArcFurnace    | plain |
| centrifuge      | `TileCentrifuge`       | blockCentrifuge      | plain |
| precisionLaserEtcher | `TilePrecisionLaserEtcher` | blockPrecisionLaserEngraver | UID string `…precisionlaseretcher` |
| platePresser    | `BlockSmallPlatePress` | blockPlatePress      | recipes keyed by **block** class, not tile [PlatePressRecipeMaker.java:15] |
| precisionAssembler | `TilePrecisionAssembler` | blockPrecisionAssembler | custom `ProgressBarImage` ctor [PrecisionAssemblerCategory.java:14] |

## Special tabs (8 — bespoke `IRecipeCategory`/`RecipeMaker`)

| tab | source of "recipes" | layout / behaviour |
|-----|---------------------|--------------------|
| chemicalReactor | `TileChemicalReactor` recipes | overrides `setRecipe`; **armor recipe** special-case fans one input's damage values into a single output slot [ChemicalReactorCategory.java:60-91] |
| gasgiants | live `DimensionManager` scan of gas-giant dims → harvestable gasses | dynamic; runtime-refreshable; own UID `zmaster587.AR.gasGiants`; per-fluid tooltip shows planet + harvest cap [GasGiantCategory.java:46-113] |
| asteroids | `asteroidConfig.xml` (fallback: the connection galaxy's `getAsteroidTypes()`) | 6×2 output grid, **paged** (12/page); rebuilt on every `getRecipes` call, no cache [AsteroidRecipeMaker.java:16-37] |
| orbitalLaserDrill | config `standardLaserDrillOres` + current dim `laserDrillOres` | 6×6 grid, paged (36/page); only registered when VoidDrill mode active [OrbitalLaserDrillRecipeMaker.java:19-23] |
| satelliteBuilder | two hand-built `IRecipe` (assembly + chip-copy) | 12-slot bespoke layout; tooltip strips "unprogrammed"/"empty" lines (the strip set is built per tooltip, in the language the client shows now), adds preview labels [SatelliteBuilderWrapper.java:61-124] |
| fuelingStation | `FuelRegistry` × 4 fuel roles × all Forge fluids | fluid gauge + role tank + hidden station stack for discoverability [FuelingStationRecipeMaker.java:26-36] |
| co2scrubber | single synthetic entry (cartridge, WILDCARD meta) | 1 input → scrubber+vent+cartridge outputs [Co2ScrubberWrapper.java:15-28] |
| stationAssembler | single synthetic showcase entry | loader-hatch + chip → packed station (+chip); block added to both in/out for R+U discoverability [StationAssemblerWrapper.java:41-67] |

## Mechanics

- **MECH-JEI-01 register-all** — `registerCategories` adds 17 categories, `register`
  adds the GUI handler, blacklist, 17 handlers, 17 recipe sets and all catalysts; JEI
  calls both once at plugin-load. [StellurgyJeiPlugin.java:161-308]
- **MECH-JEI-02 standard-projection** — a plain machine's `RecipeMaker` loops
  `RecipesMachine.getInstance().getRecipes(tileClass)` and wraps each `IRecipe` in a
  `MachineRecipe`; `setRecipe` lays out ≤10 in / ≤10 out item+fluid slots. [LatheRecipeMaker.java:15-18, MachineCategoryTemplate.java:50-87]
- **MECH-JEI-03 chance-label** — chance-outputs get display names normalised to a
  percentage of the recipe's total chance weight. [MachineRecipe.java:33-48]
- **MECH-JEI-04 gas-giant-scan** — `GasGiantRecipeMaker` scans all registered dims,
  keeps `isGasGiant()` ones, dedupes harvestable gasses by fluid name, sorts by planet
  name then dim id. [GasGiantRecipeMaker.java:19-92]
- **MECH-JEI-05 gas-giant-runtime-refresh** — `PacketDimInfo` (client) marks the client's
  `ServerView` galaxy as changed; the FML-bus `JeiClientTickHandler`, on END phase, rebuilds while
  the view says its recipe views are stale: it removes every wrapper JEI's own registry holds in the
  gas-giant category and adds freshly scanned ones, and clears the mark only once JEI's runtime and
  the client world were up. No list of "what we added" is kept — JEI's registry is that list.
  [PacketDimInfo.java:147, JeiClientTickHandler.java:19-26, StellurgyJeiPlugin.java:128] `[V]`.
  The tick handler is registered by the plugin itself — once, in `onRuntimeAvailable`
  (`refreshTickRegistered`), holding a reference to that plugin — not by `ClientProxy`
  [StellurgyJeiPlugin.java:112-117, JeiClientTickHandler.java:10-17]
- **MECH-JEI-06 config-gated-drill** — Orbital Laser Drill category, recipes and
  catalyst are only registered when `enableLaserDrill && !laserDrillPlanet` (VoidDrill
  mode). [StellurgyJeiPlugin.java:159-162, 190-194, 306-313]
- **MECH-JEI-07 asteroid-xml-cache** — asteroid recipes are loaded from
  `config/<configFolder>/asteroidConfig.xml`, paged 12/entry; rebuilt on every call (the mtime cache
  and its static `cached`/`cachedMTime` fields are deleted — JEI asks once per plugin load, which is
  rare); falls back to the connection galaxy's `getAsteroidTypes()`, which THROWS while the client has
  no connection (caught — JEI loads at the title screen, so the answer there is the empty list).
  [AsteroidRecipeMaker.java:16-37]
- **MECH-JEI-08 voiddrill-ore-list** — drill outputs are built from OreDict/registry
  parsing of `standardLaserDrillOres` plus the current dimension's `laserDrillOres`,
  deduped by registry-name@meta×count. [OrbitalLaserDrillWrapper.java:60-138]
- **MECH-JEI-09 satellite-synthetic** — the satellite builder tab hand-builds two
  anonymous `IRecipe`s (assembly with 12 slot roles, chip-copy cycling variants) rather
  than reading `RecipesMachine`. [SatelliteBuilderRecipeMaker.java:21-167]
- **MECH-JEI-10 discoverability-injection** — fueling/station/co2 wrappers inject the
  machine block itself as both a hidden input and an output so JEI's R/U lookups on the
  block open the tab. [FuelingStationWrapper.java:57-67, StationAssemblerWrapper.java:48-66]
- **MECH-JEI-11 blacklist** — four blocks/items (forcefield, light source, airlock,
  space-station item) are hidden from the JEI ingredient list. [StellurgyJeiPlugin.java:222-228]

## State & persistence

None persisted. In-memory only: the `ServerView` stale mark (client, per connection),
`jeiRuntime`/`jeiHelpers` handles — INSTANCE fields of the plugin, replaced (never accumulated) when JEI
restarts and hands the callbacks again [StellurgyJeiPlugin.java:104-106]. No static state: there is no
asteroid mtime cache and no lazy static strip-strings set (the strings are built per tooltip, `SatelliteBuilderWrapper.java:92,118`). This subsystem contributes **zero** rows to seam-nbt,
seam-registry, seam-packets, seam-mixin.

## Invariants

- **INV-JEI-01 [V][BEH]** Each `RecipeHandler.getRecipeCategoryUid` returns the same
  `StellurgyJeiPlugin.*UUID` constant used to register that category's recipes; mismatch = empty
  tab. [LatheRecipeHandler.java:16, StellurgyJeiPlugin.java:251]
- **INV-JEI-02 [V][BEH]** Gas-giant UID is defined once (`GasGiantCategory.UID`) and reused
  via `StellurgyJeiPlugin.gasGiantsUUID`; runtime add/remove must use that same UID. [GasGiantCategory.java:22, StellurgyJeiPlugin.java:101,135,144]
- **INV-JEI-03 [V]** All wrappers copy incoming `ItemStack`/`FluidStack` and detach
  `subList` views before storing, so JEI cannot mutate source recipe/config data.
  [GasGiantWrapper.java:40-45, AsteroidWrapper.java:43]
- **INV-JEI-04 [V][BEH]** Paged wrappers page-size is contract-fixed per tab: asteroids 12
  (6×2), laser drill 36 (6×6). [AsteroidWrapper.java:18-20, OrbitalLaserDrillWrapper.java:21-22]
- **INV-JEI-05 [V]** The plugin is a leaf: no Stellurgy class outside `integration/jei` depends
  on it except `ClientProxy` (registers the tick handler under an `isModLoaded("jei")`
  guard). `PacketDimInfo` does not name it: it marks the client's `ServerView` stale and the JEI
  tick handler reads the mark. [ClientProxy.java:477, PacketDimInfo.java:147]
- **INV-JEI-06 [V][BEH]** Laser-drill tab visibility is decided solely by
  `enableLaserDrill && !laserDrillPlanet`; the same predicate gates category, handler,
  recipes and catalyst. [StellurgyJeiPlugin.java:159-162,191-194,308-313]
- **INV-JEI-07 [A]** `MachineCategoryTemplate.setRecipe` assumes ≤10 inputs and ≤10
  outputs; recipes exceeding that silently drop slots (no test pins the bound).
  [MachineCategoryTemplate.java:56-66]

## Failure modes & edge cases

- Gas-giant refresh removes recipes by wrapper **identity**; if JEI wrapped/copied them
  the old entries leak. [StellurgyJeiPlugin.java:134-137]
- Asteroid recipes are not cached: every `getRecipes`
  call re-reads the XML and, if it yields nothing, the connection galaxy's in-memory types, so a
  later-populated `asteroidTypes` is seen the next time JEI asks. [AsteroidRecipeMaker.java:17-36]
- ChemicalReactor armor branch indexes `getOutputs(...).get(0).get(0)` unguarded — an
  armor-input recipe with no item output throws. [ChemicalReactorCategory.java:80]
- `onRuntimeAvailable`/refresh all bail cleanly on null `mc.world`/`jeiRuntime`.
  [StellurgyJeiPlugin.java:123-131]

## Integration seams

- **Forge/FML events:** `JeiClientTickHandler` on the event bus (`ClientTickEvent`),
  registered by `StellurgyJeiPlugin.onRuntimeAvailable` (so only when JEI is present and its runtime
  is up; once per plugin instance). [StellurgyJeiPlugin.java:112-117]
- **Cross-subsystem trigger:** `PacketDimInfo#executeClient` → `ServerView.galaxyChanged`, read by
  `JeiClientTickHandler` (network-wire → client view → JEI). [PacketDimInfo.java:147]
- **JEI advanced GUI handler:** `IAdvancedGuiHandler<GuiModular>` exposes Stellurgy's modular
  GUI extra areas to JEI. [StellurgyJeiPlugin.java:203-220]
- **libVulpes:** `RecipesMachine`, `ProgressBarImage`, `LibVulpes.proxy.getLocalizedString`.
- No packets, capabilities, mixins or ATs are declared here.

## Config surface

Config: see `C4-config-surface`. Full-disable path: JEI itself absent ⇒ `@JEIPlugin` never loads and `ClientProxy`
skips the tick handler; no Stellurgy gameplay is affected. Asteroid types are galaxy state, not config.

## Lang surface (C7)

Keys owned/consumed: `jei.machinerecipe.power|time`, `jei.sb.*`
(`satellitepreview`, `copychiphint`, `assemblyhint`, `copy.output`, `copy.source`),
`jei.stellurgy.gasgiants.*` (`title`, `orbiting`, `harvestcap[.infinite]`),
`jei.ar.fuel.role.*` (mono/biprop_fuel/oxidizer/working_fluid), `jei.ar.asteroids`.
Machine titles reuse existing `tile.*.name` keys. Verified present in en_US.lang:817-834.

## Test coverage

No dedicated tests in `src/test` reference `integration/jei` (client-render code).
INV-JEI-01..07 are all `[V]`/`[A]`; none pinned by test.

## Open questions

- Does JEI 4.x return the same `IRecipeWrapper` identity to `removeRecipe` that was
  passed to `addRecipe`? Determines whether MECH-JEI-05 actually removes stale recipes.
- `FuelingStationRecipeHandler`/`RecipeHandler` for other tabs (12-line boilerplate) not
  individually read; assumed identical to `LatheRecipeHandler` from the pattern.
