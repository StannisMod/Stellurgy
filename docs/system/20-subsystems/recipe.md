---
id: recipe
owns: [recipe/]
entrypoints: [RecipeMachineFactory#getMachine (overridden per class)]
depends-on: [multiblock-machines]
depended-by: []          # production: none; wired only via _factories.json asset + one unit test
contracts: [C3, C7]
confidence: high
---

## Purpose

Ten one-method classes that bind Forge's data-driven recipe loader to Stellurgy's
multiblock processing machines. Each is a thin `RecipeMachineFactory` (libVulpes) subclass
whose sole job is to answer "which tile class do the recipes I parse belong to?" via
`getMachine()`. They are the glue between a `data/…/recipes/*.json` file declaring
`"type": "stellurgy:<name>"` and the `RecipesMachine` recipe table keyed by tile class.

## Responsibility boundary

- **Owns**: the 10 `Recipe*` factory classes in `recipe/` and their tile-class binding.
- **Does NOT own**: the recipe-loading engine, JSON parsing, `RecipesMachine` table, and the
  `IRecipeFactory` contract — all libVulpes (foreign, not described here). Does NOT own the
  machine-set registration (`RecipeHandler` in `util/`, owned by util-core), nor the
  machine-processing runtime (owned by multiblock-machines).

## Key types

| class | binds JSON type → tile machine |
|-------|-------|
| `RecipeRollingMachine` | `TileRollingMachine` |
| `RecipePrecisionAssembler` | `TilePrecisionAssembler` |
| `RecipeElectricArcFurnace` | `TileElectricArcFurnace` |
| `RecipeCuttingMachine` | `TileCuttingMachine` |
| `RecipeChemicalReactor` | `TileChemicalReactor` |
| `RecipeElectrolyser` | `TileElectrolyser` |
| `RecipeCrystallizer` | `TileCrystallizer` |
| `RecipePrecisionLaserEtcher` | `TilePrecisionLaserEtcher` |
| `RecipeCentrifuge` | `TileCentrifuge` — **not registered** (see MECH-RCP-02) |
| `RecipeLathe` | `TileLathe` — **not registered** (see MECH-RCP-02) |

Every class body is identical: override `getMachine()` to return one `Tile*.class` literal
(e.g. `RecipeCentrifuge.java:9-11`). No other state or behaviour.

## Mechanics

**MECH-RCP-01 — factory binds recipe JSON to a tile machine.**
Trigger: Forge resource load reads `assets/stellurgy/recipes/_factories.json`, which
maps a short id (e.g. `"rollingmachine"`) to a fully-qualified factory class name
(`_factories.json:3-10`). State: Forge instantiates the factory and calls it for every recipe
whose `"type"` is `stellurgy:<id>`; the parsed recipe is filed under the tile class the
factory's `getMachine()` names. Effect: authoring a JSON recipe of that type routes it to the
correct machine's recipe list. 8 ids are wired: rollingmachine, precisionassembler,
electricarcfurnace, cuttingmachine, chemicalreactor, electrolyser, crystallizer,
precisionlaseretcher (`_factories.json:3-10`). Actual JSON usage observed for 7 of the 8
(crystallizer recipes come from auto-gen/XML instead). [V] `_factories.json:2-11`

**MECH-RCP-02 — two factory classes are unreachable.**
`RecipeCentrifuge` and `RecipeLathe` exist and pass the mapping test, but neither appears in
`_factories.json`. Forge therefore has no id → class entry for them, so no `stellurgy:centrifuge`
or `stellurgy:lathe` JSON recipe type can resolve. In production these two classes are
instantiated by nothing (grep: only the unit test and their own defs reference them). Centrifuge
and lathe processing recipes are instead supplied via XML (`RecipeHandler.registerXMLRecipes`,
`util/RecipeHandler.java:49,52`) and auto-gen (`util/RecipeHandler.java:100`). [V] absence in
`_factories.json:2-11`; `Stellurgy.java:488-497` registers the tiles but not via these factories.

## State & persistence

None. These classes hold no fields and touch no NBT. Persistence is out of scope for this subsystem.

## Integration seams

- **C7 / asset**: `assets/stellurgy/recipes/_factories.json` — the id → factory-class map.
  The 8 short ids AND the 8 fully-qualified class-name strings are both contractual: renaming a
  factory class without updating this file, or changing an id, silently breaks every recipe JSON
  of that type. [V] `_factories.json:3-10`
- **C3 / naming**: the recipe-`type` tokens `stellurgy:<id>` in data files must match the
  ids above verbatim. Observed live types: chemicalreactor, cuttingmachine, electricarcfurnace,
  electrolyser, precisionassembler, precisionlaseretcher, rollingmachine. [V] recipe JSON `type` scan.
- **Upstream tile binding**: each `getMachine()` returns a `tile/multiblock/machine/Tile*` class
  (multiblock-machines). The same tiles are separately registered for recipe storage in
  `Stellurgy.java:488-497` and for XML loading in `util/RecipeHandler.java:43-53`.

## Config surface

None. No `StellurgyConfiguration` flag gates these factory classes. (The *content* generated in
`RecipeHandler` is config-gated — `allowSawmillVanillaWood`, `allowMakingItemsForOtherMods` —
but that logic lives in util-core, not here.)

## Invariants

- **INV-RCP-01 [V][BEH]** Each `Recipe*.getMachine()` returns exactly its matching `Tile*` class and
  never null — one literal per class (`recipe/RecipeCentrifuge.java:9`, `RecipeLathe.java:9`, …, ten
  files). No test catches a wrong-tile typo.
- **INV-RCP-02 [V]** Every `Recipe*` remains a direct subclass of `RecipeMachineFactory`, so the
  loader's `instanceof` check keeps matching — the `extends` clause of each (`recipe/RecipeCentrifuge.java:6`, …),
  against the abstract `getMachine` at `libvulpes/recipe/RecipeMachineFactory.java:92`. (Was `[T]`, same
  deletion.)
- **INV-RCP-03 [V]** Only factory ids present in `_factories.json` are loadable; the class set (10)
  is a strict superset of the registered set (8). `_factories.json:2-11`.
- **INV-RCP-04 [A]** A recipe JSON with a `type` id absent from `_factories.json` fails to load
  (Forge cannot find a factory). Inferred from Forge's `IRecipeFactory` dispatch; not exercised by
  a repo test.

## Failure modes & edge cases

- Renaming a `Tile*` class updates the factory (it imports the class); a wrong-tile typo silently misroutes recipes — nothing guards it.
- Renaming a `Recipe*` class or its package without editing `_factories.json` breaks JSON recipe
  loading for that machine with no compile error — the string reference is only in the asset.
- Machine set is declared in four places that must stay in sync (this subsystem's 10 classes,
  `_factories.json`'s 8 ids, `Stellurgy.registerMachine` ×10, `RecipeHandler.registerXMLRecipes`
  ×11 incl. `BlockSmallPlatePress`). MECH-RCP-02's dead classes are exactly a drift between them.

## Test coverage

- INV-RCP-01, INV-RCP-02 → none: both invariants are `[V]` above. The per-machine recipe e2e classes still exercise the
  routing end to end for the machines they build.

## Open questions

- Are `RecipeCentrifuge`/`RecipeLathe` intended future JSON support, or leftover dead code? The
  test pins them but the asset never wires them.
- Confirm (INV-RCP-04) Forge's exact behaviour for an unregistered recipe `type` id (hard error vs
  skipped) in this MC/Forge version — not reproduced in-repo.
