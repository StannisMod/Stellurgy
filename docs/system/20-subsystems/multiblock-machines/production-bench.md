# Production Bench (machine/)

Part of [multiblock-machines](./00-overview.md). Ten controller tiles in
`tile/multiblock/machine/`, all subclasses of libVulpes `TileMultiblockMachine`. Catalogue doc:
the shared recipe/power cycle is described once, then one table row per machine.

## Shared pattern

Each bench machine is a thin subclass. It supplies:
- `getStructure()` — the block template,
- `getMachineName()` — the block's localized name,
- `getSound()` / `getSoundDuration()` — `AudioRegistry` entry,
- `getRenderBoundingBox()`, `shouldHideBlock()`,
- `getModules()` — usually just adds a `ModuleProgress` bar.

Everything else — recipe matching against `RecipesMachine.getRecipes(<TileClass>.class)`,
ingredient consumption from `itemInPorts`, output to `itemOutPorts`, power draw, and the
`completionTime` countdown to `processComplete()` — is inherited from `TileMultiblockMachine`.
Recipes are keyed by the concrete tile class and are loaded from JSON by the dataloaders
subsystem; only `TileCentrifuge` and `TileChemicalReactor` override `registerRecipes()` to add
code-generated recipes.

### MECH-MBM-15 — Recipe → power → completion cycle (shared)
`onInventoryUpdated` finds a matching recipe, sets `powerPerTick`/`completionTime`, flips the
machine running; the libVulpes running-powered tick draws power and increments `currentTime`
until `completionTime`, then `processComplete()` emits outputs. Machines are pure functions of
their hatch contents; they persist no bespoke gameplay NBT beyond libVulpes base state. [V]
`TileChemicalReactor.java:122-148`, `TileCentrifuge.java:63-66`.

## Catalogue

| tile | sloc | block name | sound | special behaviour |
|------|-----:|------------|-------|-------------------|
| `TileCentrifuge` | 134 | blockCentrifuge | electrolyser | `registerRecipes` builds a lava→nugget recipe from `lavaCentrifugeOutputs` (ore-dict:chance), fixed `lavaCentrifugeTime`/`Power`, enriched-lava→lava fluid |
| `TileChemicalReactor` | 239 | blockChemicalReactor | rollingMachine | MECH-MBM-16 suit-seal recipe generation; gated on `enableOxygen` |
| `TileCrystallizer` | 84 | blockCrystallizer | crystallizer | MECH-MBM-17 gravity gate |
| `TileCuttingMachine` | 80 | blockCuttingMachine | — | plain bench |
| `TileElectricArcFurnace` | 98 | blockArcFurnace | — | plain bench |
| `TileElectrolyser` | 64 | blockElectrolyser | — | plain bench |
| `TileLathe` | 66 | blockLathe | — | plain bench |
| `TilePrecisionAssembler` | 136 | blockPrecisionAssembler | — | `IProgressBar`; adds progress module |
| `TilePrecisionLaserEtcher` | 120 | blockPrecisionLaserEngraver | — | plain bench |
| `TileRollingMachine` | 72 | blockRollingMachine | rollingMachine | plain bench |

### MECH-MBM-16 — Chemical reactor suit-seal recipes
When `enableOxygen`, `registerRecipes` scans the whole item registry and, for every `ItemArmor`
that is not already an `ItemSpaceArmor`, generates a recipe that consumes the armour +
`blockPipeSealer` + titanium-aluminide sheet (+ a pressure tank for chestplates) and outputs the
same armour enchanted with `enchantmentSpaceProtection`. `reloadRecipesSpecial` re-derives that
special set idempotently after a recipe reload, dropping any removed by other mods. Ingredient
consumption is custom (`consumeItemsSpecial`) so the enchant is applied to the actual input stack.
[V] `TileChemicalReactor.java:49-204`.

### MECH-MBM-17 — Crystallizer gravity gate
`isGravityWithinBounds()` returns true unless `crystalliserMaximumGravity != 0` **and** the local
dim's `gravitationalMultiplier ≥ crystalliserMaximumGravity`. `onRunningPoweredTick` only advances
when within bounds (so a high-gravity world stalls it), and the GUI shows a "gravity too high"
line. [V] `TileCrystallizer.java:54-78`.

## State & persistence

None bespoke — recipe progress lives in libVulpes base NBT. The `Lore`/`display` NBT keys the
seam attributes to `TileChemicalReactor` come from a **commented-out** lore block and are inert;
they are not written at runtime. [V] `TileChemicalReactor.java:84-106`.

## Integration seams

- **Recipes**: `RecipesMachine` keyed by tile class; JSON recipes loaded by
  `integration-dataloaders`. No test pins the class↔name mapping.
- **→ atmosphere-oxygen**: chemical reactor gates on `enableOxygen`, outputs the
  `enchantmentSpaceProtection` enchant.
- **→ dimension-planets**: crystallizer reads `gravitationalMultiplier`.

## Config surface

Config: see `C4-config-surface`.

## Invariants

- **INV-MBM-13** [T][BEH] A centrifuge and a crystallizer run a full recipe end-to-end producing the
  expected output. `CentrifugeRecipeEndToEndTest`, `CrystallizerRecipeEndToEndTest`. Pinned by `CentrifugeRecipeEndToEndTest#centrifugeRunsFirstRegisteredRecipe`, `CrystallizerRecipeEndToEndTest#crystallizerRunsFirstRegisteredRecipe`.
- **INV-MBM-14** [V][BEH] `reloadRecipesSpecial` is idempotent: it removes the prior special set before
  regenerating, so repeated reloads never duplicate suit recipes. `TileChemicalReactor.java:49-77`.
- **INV-MBM-15** [V][BEH] The crystallizer's gravity gate blocks *accrual* (`onRunningPoweredTick`),
  not merely the GUI text. `TileCrystallizer.java:62-67`.

## Test coverage

`CentrifugeRecipeEndToEndTest`, `CrystallizerRecipeEndToEndTest`, `MachineRecipeEndToEndKit`,
`MachineDomainSmokeSuite`, `TileMachineDepthRound2Test`

## Failure modes & edge cases

None. The registry-scan recipe generation in the chemical reactor is O(items) but runs once at
recipe-load, not per tick.
