# util-core / Mass & weight

Parent: [00-overview.md](./00-overview.md) · Files: `WeightEngine.java` (643),
`IWeighted.java` (6)

Resolves the **mass in kilograms** of any block, item, fluid or tile-entity. One block is one
cubic metre, so a table entry doubles as that material's density in kg/m³, and a fluid entry is
kilograms per millibucket. Nothing here is a weight — gravity is applied where a force is needed
(`StatsRocket.weightNewtons`), never baked into the table. Single enum singleton
a `final` class (not an enum singleton — there is no `WeightEngine.INSTANCE`) over a
player-editable Gson file `config/advRocketry/weights.json`: the mod holds ONE instance, reached by
`Stellurgy.weights()`, written once in `postInit` (a second write throws) and only read after, lifetime the
process (`Stellurgy.java:330-341,1365-1368`). `WeightEngine.fromJson(json)` builds a file-less engine of its
own for a test or probe, so no caller needs to reach the game's table to try a different one.

**Denominated in kilograms**: every table is 5000 times its per-block figure and there is no
`weightMaterialScale`. The class and method names still say "weight" — deliberately, because
the player-facing config keys (`advancedWeightSystem*`) do too, so renaming half of it would read worse
than renaming neither. C10 STAT-6 carries the full number table.

## Key types

| type | role |
|------|------|
| `WeightEngine` (final class, one instance held by the mod: `Stellurgy.weights()`) | mass resolver + JSON table store |
| `IWeighted` | one-method `float getWeight()` marker for objects that self-weigh — returns a MASS in kg despite the name; left alone at 1b because `SatelliteProperties` implements it as public API and satellite mass is 0 for every registered component today |

## Mechanics

- **MECH-WGT-01 — resolution chain (per single item, first hit wins).**
  `individual` map → `byRegex` (ordered, first regex match) → Stellurgy component specials
  (fuel-tank / motor / pressure-tank / guidance / satellite-hatch by block class or
  registry name) → block `Material` table → global `fallback`. Every tier is an
  absolute in kilograms; there is no multiplier left anywhere in the chain, `weightMaterialScale`
  having been removed. `WeightEngine.java:90-140`.
- **MECH-WGT-02 — NOT memoised.** `resolveUnitWeight` resolves the chain afresh
  on every call; there is no cache of unit weights by registry name — a cache that a table mutation
  had to remember to clear would be a second copy of the answer. `getWeight(stack)` multiplies the unit weight by `stack.getCount()`.
  `WeightEngine.java:92-111`.
- **MECH-WGT-03 — fluid mass and the propellant table.** A fluid's row in `fluids` carries its
  density (kg/m³ of the condensed form), and where it is a propellant its `molarMass` (g/mol) and its
  `monopropellantExhaustVelocity` (m/s); `propellantPairs` lists `(fuel, oxidizer) → exhaustVelocity,
  mixtureRatio` (O/F by mass). Mass per mB is density / 1000 (INV-WGT-09). Every seeded row is a
  measured value with its source beside it in `WeightEngine#defaultFluids` / `#defaultPairs`. The
  table is the ONE source of a fluid's mass and propellant data; C10 STAT-32 reads it.
- **MECH-WGT-04 — tile-entity weight.** Sums `getWeight` over every item-handler slot and every
  fluid-tank content of the TE's capabilities, times `contentMassScale`, added to the TE's own block
  weight. Unconditional; the scale is the only knob and it reaches content alone
  (`WeightEngine#getTEWeight`). A tier-2 craft splits the same holdings in two
  (`WeightEngine#cargoWeight` / `#reactionWeight`, read by `ShipHullMass`): fluids an engine can burn
  are reaction mass at their real mass, never scaled; the rest is cargo under the scale (C10 STAT-41).
  `#massPerMillibucket` is the double-precision mass of one mB (INV-WGT-09), the drain's conversion.
  `#heaviestFluidOf(FuelType)` is the densest fluid a fuel role accepts — what a tank with no fluid
  chosen is weighed full of, by the tier-1 gate (`StatsRocket#withTanksFull`) and the tier-2 assembler
  (`ShipHullMass#frameOfBoxWithTanksFull`) alike (C10 STAT-12, STAT-29).
- **MECH-WGT-05 — JSON load/seed/save.** `load()` reads the five tables
  (`individual`, `byRegex` [LinkedHashMap, order preserved], `fluids` rows, `propellantPairs`, `materials`,
  scalars `fallback`/`fluidFallbackDensity`); missing file → `seedDefaults()` + `save()`; parse
  failure → defaults + warn. `materials`, `fluids` or `propellantPairs` empty ⇒ the built-in defaults.
  `WeightEngine.java:350-405, 468-495`. The toughness and ablation columns load and save the
  same way, from the same file, and an older file missing its keys simply seeds the defaults.

- **MECH-WGT-06 — schema version.** `save()` writes `formatVersion: 3` (`WeightEngine#FORMAT_VERSION`, `#save`); `load()`
  refuses any file whose version differs, renames it to `weights.json.v<n-1>.bak` and reseeds
  defaults. A file of another schema states its numbers in other units (an older `fluids` entry was
  kilograms per mB; a v3 row is kg/m³ with propellant data); they cannot be converted automatically,
  because an entry may be a default or a deliberate absolute and only its author can tell which. If the rename
  fails, defaults are used in memory and the file is left untouched.
  **The retire happens AFTER the reader closes**, not inside `load()`'s try-with-resources:
  Windows refuses to rename an open file, so the version check sets a flag and the rename runs
  once the resource is released (`WeightEngine.java:405-406,490-496`). [V][T]
  `WeightEngineUnitTest.aTableFromAnotherSchemaIsSetAsideRatherThanRead` (`:86`) asserts the backup EXISTS, not merely that the
  stale values were ignored.

- **MECH-WGT-07 — toughness, a second column over the same keys.** `getToughness(block)`
  resolves by the same chain as weight (`toughnessIndividual` → `toughnessByRegex` →
  `toughnessMaterials` → `toughnessFallback`), so a pack that has tuned one has half the
  work done for the other. It answers a different question — what a block costs to *damage*
  rather than what it *masses* — and is spent by the structural-damage engine.
  **Air and anything unrecognised resolve to the fallback, not to zero**: a block that cost
  nothing to break would let one shot walk an entire hull. Unlike weight it is not memoised
  and not scaled by `weightMaterialScale`. `WeightEngine.java:170-200` [V]

- **MECH-WGT-08 — ablation, a THIRD column, and the only sparse one.** `getResistance(block,
  kind)` answers mechanical toughness for every kind except the thermal ones (`THERMAL`,
  `BEAM`); for those it resolves `ablationIndividual` → `ablationByRegex` and, finding
  neither, DERIVES the figure from the block's toughness times
  `ablationResistanceFactor`. There is deliberately no material tier and no separate
  fallback: the table only ever has to name the blocks whose two channels genuinely
  disagree, so "no row" is the normal case rather than a gap.
  `WeightEngine.java:242-259` [V]

## State & persistence

Not world NBT — an external config file `config/advRocketry/weights.json`: four weight JSON
objects + two scalars, three toughness objects + one scalar, and two ablation objects. Every
one of the twelve is both read and written; `save()` rewrites the whole file, so a column
that `load()` reads and `save()` omits is not "left alone" but LOST the first time anything
saves. Round-trip pinned column-by-column by test (below) rather than by a hand-picked
key, because the column that goes missing is by definition the one nobody put on a list.
Every regex column (`byRegex`, `toughnessByRegex`, `ablationByRegex`) is a `LinkedHashMap`:
matching is first-match-wins, so the order a pack writes its patterns in IS the precedence
between two patterns that both match.

## Invariants

- **INV-WGT-01 [V][BEH]** Empty stack or null registry name ⇒ weight `0`.
  `WeightEngine.java:86-88`.
- **INV-WGT-02 [T][SYS]** `individual` override survives a JSON save→load round-trip.
  `test/unit/WeightEngineUnitTest.java:83-102`. Pinned by `WeightEngineUnitTest#individualOverrideSurvivesSaveLoadRoundTrip`. FOR: save format: weight config file.
- **INV-WGT-03** — retired.
- **INV-WGT-09 [T][SYS]** **A millibucket of any fluid is a litre of its condensed form, and its mass
  is the fluid's density / 1000 kg wherever a fluid is weighed** — a rocket's own tanks and what any
  block holds alike, through one function. The density is the fluid's row in the table's `fluids`
  section (kg/m³), else Forge's `getDensity()` when it is POSITIVE (Forge calls it "completely
  arbitrary" and makes gases negative, so a non-positive one is no density), else the table's
  `fluidFallbackDensity` — and that last answer is ANNOUNCED once per fluid in the log, naming the fluid
  and that its mass is not known. Nothing scales it. REGIONS: `row`, `positive Forge density`,
  `non-positive Forge density`, `fallback`. FOR: maintainer ruling 2026-10-16 ("1a, 2a"). Pinned by
  `WeightEngineUnitTest#aMillibucketWeighsItsDensityOverAThousand`.
- **INV-WGT-04 [T][BEH]** `seedDefaults` populates a non-empty material table.
  `test/unit/WeightEngineUnitTest.java:77`; server-side weight system pinned by
  `test/server/WeightSystemTest.java`. Pinned by `WeightEngineUnitTest#seedDefaultsPopulatesMaterialTable`.
- **INV-WGT-05 [V]** Component masses (tank/motor/pressure/guidance/hatch) are `tunable`
  constants. An empty propellant tank is 10 % of its full contents (1 m³ of its role's fluid:
  hydrazine 102.1, hydrogen 7.08, oxidizer 114.1 kg — a ruled RELATION, real stages run 4-10 %);
  the rest (motor 10 000 / pressure tank 25 000 / guidance 9000 / hatch 25 000 kg) are balance numbers and are not
  pinned here — but the motor mass and `BlockRocketMotor`'s thrust rating are **jointly**
  calibrated (C10 STAT-6) — and no test pins that pairing.
- **INV-WGT-06 [V]** Regex rules that fail `Pattern.compile` are silently skipped and do
  not abort resolution. `WeightEngine.java:291-299` (no test).
- **INV-WGT-07 [T][SYS]** Every column the file declares survives a save→load cycle carrying its
  value, and every regex column comes back in the order it was written.
  `WeightEngineUnitTest#everyColumnAPackCanWriteSurvivesASave` (`:139`), `aRegexColumnKeepsThePackSOrderAcrossASave` (`:185`). Pinned by `WeightEngineUnitTest#aRegexColumnKeepsThePackSOrderAcrossASave`. FOR: save format: weight config file.
- **INV-WGT-08 [T][SYS]** A config file that cannot be read leaves no column carrying the previous load's rows — a column
  the reset forgot would let a broken file silently inherit half of the file before it.
  `WeightEngineUnitTest#aConfigThatCannotBeReadLeavesNoColumnBehind` (`:217`). FOR: save format: weight config file.

## Failure modes & edge cases

- A material with no entry in `MATERIAL_NAMES` maps to `"UNKNOWN"`, misses the `materials`
  table, and falls through to `fallback` (500 kg).
- The chain is keyed only by registry name: two ItemStacks differing solely by NBT
  or meta resolve to the same weight (intended — weight is per registry name; this held for the
  deleted cache and holds for the uncached chain, `WeightEngine.java:92-100`).

## Integration seams

Registry names hard-coded as component discriminators: `stellurgy:guidancecomputer`,
`stellurgy:loader` (C3). No packets, no world NBT, no events.

## Config surface

| flag | mechanic | full-disable path |
|------|----------|-------------------|
| `contentMassScale` (double, default 1.0, ≥ 0) | MECH-WGT-04 | multiplies held content only; 0 ⇒ inventory contents weigh nothing |

## Test coverage

`WeightEngineUnitTest` (unit round-trip/scale) + `WeightSystemTest` (server). See INV
tags above.

## Open questions

- Regex-skip (INV-WGT-06) is untested; a malformed user regex degrades silently.
