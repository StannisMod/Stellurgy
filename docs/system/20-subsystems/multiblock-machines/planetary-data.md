# Planetary Data & Observation

Part of [multiblock-machines](./00-overview.md). Files: `TileObservatory.java` (1488),
`TileAstrobodyDataProcessor.java` (621), `TilePlanetSelector.java` (220),
`TileBiomeScanner.java` (123).

## Purpose

The science tier: discover asteroids and print asteroid chips (Observatory), refine asteroid-chip
data across three data types (Astrobody Data Processor / "planet analyser"), pick a planet system
from a star map (Planet Selector), and read a planet's biome list (Biome Scanner). These tiles
move `DataStorage` between `TileDataBus` cables and item chips.

## Key types

| class | role |
|-------|------|
| `TileObservatory` | **3-tab** GUI (data · asteroids · region survey); view-distance from optics; seed-based asteroid discovery; chip printing; the galactic region survey (MECH-MBM-29). |
| `TileAstrobodyDataProcessor` | 3 data-bus inputs (COMPOSITION/DISTANCE/MASS) → asteroid chip |
| `TilePlanetSelector` | `TilePointer`; star-map selection, visible-planet sync |
| `TileBiomeScanner` | read-only GUI listing the orbited planet's biomes |

## Mechanics

### MECH-MBM-18 — Observatory open/close & view distance
The dome "opens" (server) when enabled, running, and either night+clear-sky or in the space dim;
`openProgress` ramps to `openTime=100`. View distance accrues at multiblock formation:
`replaceStandardBlock` adds +5 per lens and +25/+50/+100/+175 per motor tier;
`getMaxDistance = viewDistance + 10`. [V] `TileObservatory.java:254-284,232-251,630-632`.

### MECH-MBM-19 — Asteroid discovery & chip printing
The asteroid tab (tab 1) picks a seed = `worldTime/100` on scan, consuming
`dataConsumedPerRefresh=100` DISTANCE data; from the seed and the viable `asteroidTypes` (those
within `getMaxDistance`) it builds a randomized asteroid list. Selecting one (button ≥ `LIST_OFFSET`)
and pressing process (`PROCESS_CHIP`, server) writes an `ItemAsteroidChip` (UUID = `seed+button`,
type, maxData=1000) into the output slot, costing 500 RF, requiring `isOpen`. A per-seed
`printedButtonsThisSeed` set (with `printedSetSeed`) blocks printing the same selection twice; the
set is synced to the client (`SYNC_PRINTED`) and persisted. [V] `TileObservatory.java:456-530,715-774`.

### MECH-MBM-29 — Region survey (tab 2): aim in star territories, sweep, write a crystal
The third tab points the instrument at a patch of sky and works through it. The operator picks one of
six axis DIRECTIONS and a distance in **STEPS**, where one step is the edge of the cube that holds at
most one system (`IGalaxyGenerator.minSpacingCells`) — the pick lives on the tile, so it survives the
GUI being closed, and it is clamped to `RegionScan.Tuning.maxRangeSteps()`, derived from the
`telescopeScanRangeLightYears` horizon against the live spacing. `beginRegionScan` builds a
`RegionScan` whose looks stand one territory apart; `beginPassiveSweep` builds a `RegionScan.local`
around the observatory's own cell, which walks CELLS instead and is the mode that names the system
the machine is standing in — and so the mode a player learns the home world's MOONS by, which the
starter crystal does not carry (`universe-model`). One mode at a time.

**The origin is always a GALACTIC cell** (`scanOrigin` → `GalacticCoord.galacticCell`): an
observatory on a moon surveys from its system's cell in the lattice a survey walks, not from the
moon's name inside its planet's zone, whose sector triple counts a lattice four orders of magnitude
finer. [V] `TileObservatory.scanOrigin`; [T] `ObservatoryOnAMoonTest`; the home radar naming the moon is leg T of
`M1PlanetToPlanetMilestoneE2ETest`.

Each step is a deadline, not a counter: `completeRegionScanIfDue` resolves
`telescopeScanCellsPerStep` cells when the world clock passes `stepDeadline`, charging
`telescopeSurveyDataPerStep` DISTANCE data (an unfed instrument stalls, and does not move its
deadline). Without `planetsMustBeDiscovered` the whole region resolves outright — research is what
makes the time curve a mechanic. Each look is written onto the memory crystal in `SLOT_CRYSTAL`
through `TelescopeScan.resolveCell`, which resolves the system that OWNS that cell; a finished
observation with no crystal is HELD rather than discarded. The aim is displayed with its length in
light years, computed server-side and synced (`scanStepLy`) because the client may not hold the
pack's star spacing. [V] `TileObservatory.java:771-830,910-951,1053-1065`, `RegionScan.java`,
`TelescopeScan.java:78-110`.

**A cloud in the way costs a look its detail, and says so.** Each look is resolved FROM this
observatory's own cell, so `TelescopeScan.isObscured` can measure the dust between it and the system
it is aimed at (`UniverseRegistry.extinctionBetween`, in magnitudes). Past
`telescopeObscuredAtMagnitudes` the look writes the system's BARE COORDINATE instead of its bodies —
it never writes nothing, because a survey that silently returned less would be indistinguishable
from an empty sky. The tile counts those looks
(`lastScanObscured`) and the status line reports them BEFORE the found-count: *"Dust in the way —
coordinates only: N"*. [V] `TileObservatory.java` (`countObscured`, `scanStatusText`),
`TelescopeScan.isObscured`.

### MECH-MBM-20 — Data-bus store / load
`storeData`/`loadData` move `DataStorage` between the observatory's data buses and a chip in slots
0/3/4, matched by type (DISTANCE↔0, COMPOSITION↔3, MASS↔4 via `doesSlotIndexMatchDataType`).
Untyped buses default to DISTANCE on integrate. [V] `TileObservatory.java:927-1014,1085-1087,193-211`.

### MECH-MBM-21 — Astrobody data processor research
Three data cables are locked to COMPOSITION/DISTANCE/MASS on integrate (by slot order). With an
`ItemAsteroidChip` in its single slot and any research toggle on, each powered tick advances the
corresponding progress (0→`maxResearchTime=10`); on completion it consumes 1 data from the matching
cable and adds 1 to the chip's data type. When all three types hit the chip's max, the chip is
ejected to the output hatch. Toggles are 3 bits packed into packet id 4. [V]
`TileAstrobodyDataProcessor.java:80-107,283-332,166-177`.

### MECH-MBM-22 — Planet selector
Extends `TilePointer`. `getModules` builds a `ModulePlanetSelector` seeded from the player's
current star; selection (`onSelected`) calls back to the master block
(`ITilePlanetSystemSelectable.setSelectedPlanetId`) and sends packet 0. `visiblePlanets` (from the
master) is synced via the description packet so the client map shows known systems. Progress bars
express certainty from the selected dim's atmosphere/orbit/gravity. [V] `TilePlanetSelector.java:42-214`.
The orbit bar is `distanceGauge(body)` — 6.25 hundredths of the bar per AU of `orbitalDist`, so
Earth reads 6 (`TilePlanetSelector.java:38-58`); the rocket's own selector reads the same law
(`EntityRocket.java:344`). A MOON reads `moonViewUnits / 16` of its distance from its planet — Luna
9 (through the planet law she would read 0). [T]
`test/server/SelectorServerSmokeTest.java` (`theDistanceGaugeReadsAnOrbitAtItsScalePerAu`,
`theDistanceGaugeReadsAMoonAsItReadLunaAt150`; the rocket's wiring is unpinned).

### MECH-MBM-23 — Biome scanner
Client-only readout: if the column below is clear to bedrock and the orbited planet is not the
warp dim, it lists the planet's biomes (or "gas"/"star" text) in a scroll pane. No server state.
[V] `TileBiomeScanner.java:63-110`.

## State & persistence

Observatory keys (`writeToNBT`/`readFromNBT` + `writeNetworkData`/`readNetworkData`), astrobody keys and the planet selector's keys:
see `C1-nbt-persistence`. Notes: `printedSetSeed` and `printedButtons` are the disk-persisted print state; `tab`, `button`,
`ps pb ls lb io en`, `state` and `id` are wire-only packet payload keys, never persisted.

## Integration seams

- **Packets** (`PacketMachine` discriminators): Observatory 10 tab-switch, 11 button, 12 process,
  13 seed-change, 14 sync-printed, 15 request-reopen, 16 sync-seed, −1 store, −2 load. Astrobody 2
  process, 4 research bitmask. Planet selector 0 select.
- **→ dimension-planets**: `DimensionProperties` (biomes, atmosphere, star), `getEffectiveDimId`.
- **→ util-core**: `Asteroid`/`StackEntry` harvest tables; `IDataInventory`/`IDataItem`.
- **→ space-stations**: biome scanner orbit lookup, `WARPDIMID`.
- **Client proxy**: observatory scroll-cache clear + asteroid list pan (client-render).

## Config surface

The galaxy's `getAsteroidTypes()` (map, keyed by type name) — the discovery table (galaxy state).
No dedicated disable flags; these are gated by block presence and data availability.

## Invariants

- **INV-MBM-16** [A][BEH] A powered analyser with data on its buses increments the chip's composition
  data from 0. `PlanetAnalyserResearchContractTest.java:54-127`. Pinned by `PlanetAnalyserResearchContractTest#poweredAnalyserIncrementsChipCompositionFromDataBus`.
- **INV-MBM-17** [V][BEH] The observatory refuses to print the same selection twice per seed: the
  server `PROCESS_CHIP` path hard-blocks when `printedButtonsThisSeed.contains(lastButton)`.
  `TileObservatory.java:738-748`.
- **INV-MBM-18** [V][BEH] View distance is a pure function of installed optics computed once at
  formation; it resets to 0 on deconstruct. `TileObservatory.java:232-251,228`.
- **INV-MBM-19** [V] Chip printing is server-authoritative (client button press only requests;
  the write, energy cost and dedup are inside `!world.isRemote`). `TileObservatory.java:635-663,700-774`.

## Test coverage

`ObservatoryMultiblockTest`, `PlanetAnalyserMultiblockTest`,
`PlanetAnalyserResearchContractTest` (INV-MBM-16), `ItemDataCarrierNBTRoundTripTest` (chip NBT).

## Failure modes & edge cases

- **Astrobody research flags lost on reload.** `writeToNBT`
  (`TileAstrobodyDataProcessor.java:514-516`) persists `researchingAtmosphere/Distance/Mass`, but
  `readFromNBT` (493-499) reads back only the inventory and the three progress ints, **not** the
  research booleans. They are restored only in `readNetworkData` (504-506, the description-packet
  path). Assuming libVulpes `readFromNBT` does not delegate to `readNetworkData`, a server restart
  resets all research toggles to false, silently halting an in-progress analyser until the player
  re-opens the GUI and re-toggles. Save/load asymmetry (key written, not read).
- **BiomeScanner NPE off-station.** `TileBiomeScanner.getModules:78-79`
  calls `SpaceObjectManager.getSpaceStationFromBlockCoords(pos).getOrbitingPlanetId()` with no null
  check; a scanner not on a space station (`spaceObject == null`) NPEs on GUI open.
