---
id: satellite
owns: [satellite/, tile/satellite/, api/satellite/, entity/EntitySatellite, client/render/entity/RenderSatellite]
entrypoints: [SatelliteData#tickEntity, SatelliteWeatherController#tickEntity, TileSatelliteBuilder#assembleSatellite, TileSatelliteTerminal#performFunction, TileTerraformingTerminal#update, EntityRocket#deploySatelliteFromHatch, SatelliteMaterializer#onChunkLoad, EntitySatellite#onUpdate]
depends-on: [api-public, dimension-planets, network-wire, items, util-core, world-gen, wirelessdata, space, universe, environment]
depended-by: [misc-oddities, blocks, items, commands-gameplay]
contracts: [C1, C2, C3, C4, C19]
confidence: high
---

## Purpose

Concrete satellite *types* (the orbital devices a player builds and launches), the three
ground-side tiles that build, query and drive them, and the satellite as a **physical object on
its orbit**. Each satellite is a record in a planet's `DimensionProperties` satellite list: its
type's behaviour (`tickEntity`, `performAction`, NBT), the parts it was built from, and — once a
rocket has released it — its orbit. Where its body's space cell is live, the record is also
**present**: a block-sized entity standing at its point, oriented by its parts, clickable for a
repair in orbit and destructible. The cell's frame flies the orbit (C19 FRAME-5), so the satellite
stands still in the cell as a parked ship does; its phase is computed from its orbit and the clock,
and changes only its relation to what does not fly with the frame — the body's field, the turning
surface, the star. The abstract base (`SatelliteBase`, `SatelliteProperties`, `SatelliteRegistry`,
`DataStorage`) lives in `api-public`; the orbit value (`SatelliteOrbit`) and the instrument subject
(`InstrumentSubject`) beside it are owned here.

## Responsibility boundary

Owns: every concrete `SatelliteBase` subclass under `satellite/`; the `SatelliteData`
data-satellite family; the builder / control-center / terraforming-terminal tiles under
`tile/satellite/`. Owns their per-type NBT keys and tick logic.
Does NOT own: `SatelliteBase`/`SatelliteProperties`/`SatelliteRegistry`/`DataStorage`
(api-public), the satellite ID-chip / biome-changer / ore-scanner *items* (items), the
satellite list persistence on `DimensionProperties` (dimension-planets), the
`nextSatelliteId` counter (`DimensionManager`, dimension-planets), the particle/machine
packets (network-wire), `BiomeHandler`/`TerraformingHelper`/`WeightEngine`/
`PlanetaryTravelHelper` (util-core).

## Key types

| class | role |
|-------|------|
| `SatelliteData` (abstract) | data-collecting satellite: buffers `DataStorage`, drains battery, ticks up data; base for the four scanners `satellite/SatelliteData.java:23` |
| `SatelliteComposition` / `SatelliteDensity` / `SatelliteMassScanner` / `SatelliteOptical` | `SatelliteData` subclasses locked to one `DataType` (COMPOSITION / ATMOSPHEREDENSITY / MASS / DISTANCE) `SatelliteComposition.java:10`. ⚠️ `SatelliteDensity` is registered but **UNREACHABLE** — bound to no `itemSatellitePrimaryFunction` metadata (0-4 = optical/composition/mass/microwave/oreMapping) and no recipe ships |
| `SatelliteOreMapping` | on-demand chunk ore scanner; opens GUI 100, no data buffer `SatelliteOreMapping.java:68` |
| `SatelliteMicrowaveEnergy` | `IUniversalEnergyTransmitter`; beams stored power down, scaled by config `SatelliteMicrowaveEnergy.java:16` |
| `SatelliteBiomeChanger` | terraform-by-radius: queues block positions, rewrites biomes on tick `SatelliteBiomeChanger.java:23` |
| `SatelliteWeatherController` | three-mode hydrology/weather driver (rain / drain / flood) `SatelliteWeatherController.java:45` |
| `SatelliteSpyTelescope` | stub — all bodies empty / commented `SatelliteSpyTelescope.java:9` |
| `SatelliteDefunct` | placeholder returned for unresolved satellites (no-op) `SatelliteDefunct.java:9` |
| `TileSatelliteBuilder` | multiblock: assembles a satellite item from part slots; copies chips `TileSatelliteBuilder.java:31` |
| `TileSatelliteTerminal` | Satellite Control Center: chip → link → data download + erase button `TileSatelliteTerminal.java:40` |
| `TileTerraformingTerminal` | redstone-gated tile that drives a biome-changer chip's terraform loop `TileTerraformingTerminal.java:46` |
| `SatelliteOrbit` | a circular orbit's elements — radius, inclination, node, the body's gravitational parameter, the release tick — and the phase and position computed from them; a `Trajectory` for the environment `api/satellite/SatelliteOrbit.java:18` |
| `InstrumentSubject` | what a primary function looks at: the body, or space `api/satellite/InstrumentSubject.java:7` |
| `SatelliteAssembly` | the one sum of a chassis' parts into properties, read by assembly and by repair `satellite/SatelliteAssembly.java:13` |
| `SatellitePlacement` | the altitudes a body admits, the orbit a release gets, the point a satellite stands at `satellite/SatellitePlacement.java:26` |
| `SatelliteAttitude` | which way the parts face at a tick: the nearest star, the body `satellite/SatelliteAttitude.java:26` |
| `SatelliteMaterializer` | stands a satellite's entity up where its live cell's chunk loads, or at release `satellite/SatelliteMaterializer.java:33` |
| `OrbitalPartsInventory` | a satellite's parts as the builder panel's seven slots, for a repair in orbit `satellite/OrbitalPartsInventory.java:24` |
| `EntitySatellite` | the satellite's presence in a live cell: kinematic, never saved, clickable, destructible `entity/EntitySatellite.java:47` |
| `RenderSatellite` | draws the chassis, its dish and antennas, and each part facing by rule `client/render/entity/RenderSatellite.java:34` |

## Mechanics

- **MECH-SAT-01 Data accrual.** `SatelliteData.tickEntity` calls `getDataCreated()`: if the
  buffer is not full it extracts `powerConsumption` from the battery every tick and adds one
  data point when `worldTime % collectionTime == 0`; `collectionTime =
  200/sqrt(0.1·powerPerTick)`. [V] `SatelliteData.java:75,96,106` [T] `SatelliteTickBehaviourTest.java:112`
- **MECH-SAT-02 Data offload.** `SatelliteData.performAction` pushes the whole buffer into an
  adjacent `IDataHandler` tile via `addData` and removes what was accepted. [V] `SatelliteData.java:62`
- **MECH-SAT-03 Base power upkeep.** `SatelliteBase.tickEntity` accepts `powerPerTick-1`
  into the battery each tick, clamped at `powerStorage`. [V] `api/satellite/SatelliteBase.java:111`
  [T] `SatelliteTickBehaviourTest.java:63,88`
- **MECH-SAT-04 Ore scan.** `SatelliteOreMapping.scanChunk` charges 1000 RF base + `zoom·(250|375)`
  RF, columns the world, and returns an ore-vs-other density grid; filtered scan requires
  `maxDataStorage==3000`. [V] `SatelliteOreMapping.java:77,142,221`
- **MECH-SAT-05 Microwave transmit.** `transmitEnergy` sends `round(microwaveRecieverMulitplier ·
  (powerPerTick-1))` RF from the battery per pull. [V] `SatelliteMicrowaveEnergy.java:61`
- **MECH-SAT-06 Biome change.** `SatelliteBiomeChanger.performAction` floods a noisy square of
  `(radius+noise)` around the target into `toChangeList` (y forced to 0); `tickEntity` drains
  120 RF per block and calls `BiomeHandler.terraform`, up to 10 blocks/tick. [V] `SatelliteBiomeChanger.java:88,120`
- **MECH-SAT-07 Weather modes.** `mode_id` 0=rain, 1=drain, 2=flood. On mode change
  `applyWeatherMode` sets the planet's rain marker (+1/-1) and toggles `WorldInfo` raining;
  `performAction` recomputes `viable_positions` per mode; `tickEntity` mutates one water/air
  block per tick with particle packets. `setDead` releases the rain marker (rain/drain only).
  [V] `SatelliteWeatherController.java:98,174,283,200`
- **MECH-SAT-08 Satellite assembly.** `TileSatelliteBuilder.assembleSatellite` sums power/data/
  weight from part slots 1-6 through `SatelliteAssembly.propertiesOf` (720 RF of base buffer on top
  of the batteries) — the parts stay in the chassis' own `inv`, so a launched satellite carries
  them — mints `SatelliteProperties` with a fresh id from
  `DimensionManager.getNextSatelliteId()`, stamps the chassis (`ItemSatellite`) and the id
  chip via `sat.getControllerItemStack`, then moves chassis→holding→(output after
  `completionTime`). Gated by `canAssembleSatellite` (needs primary function, a power gen,
  and an acceptable controller chip). [V] `TileSatelliteBuilder.java:98,69` [T] `SatelliteBuilderPressBuildContractTest.java:76,133`
- **MECH-SAT-09 Chip copy.** Button 1 copies a satellite/planet/station/ore-scanner chip into
  a blank second chip (holding slot), decrementing the source. [V] `TileSatelliteBuilder.java:144,160`
- **MECH-SAT-10 Terminal link + download.** `TileSatelliteTerminal` resolves the satellite from
  the slot-0 id chip, requires it be a `SatelliteData`, in-range
  (`PlanetaryTravelHelper.isTravelAnywhereInPlanetarySystem`) and powered (≥1 RF), then pulls
  data via `performAction`. Button 0 = manual connect (packet id 100); button 1 = erase (id
  101) which removes the satellite from its dim and blanks the chip. [V] `TileSatelliteTerminal.java:207,283`
  [T] `SatelliteTerminalChipRecognitionTest.java:87,133`
- **MECH-SAT-11 Auto-download backoff.** `performFunction` (every 16 ticks) polls
  `maybeAutoDownloadFromSatellite`: needs an adjacent enabled+extract `TileWirelessTransceiver`,
  a resolvable satellite, link and power; interval doubles on each failed poll (64→512 ticks),
  resets to 64 on success or on chip insertion / `extractData`. [V] `TileSatelliteTerminal.java:101,185`
- **MECH-SAT-12 Terraforming terminal.** `TileTerraformingTerminal.update`: if a valid
  biome-changer chip is present and the block is redstone-powered, sets the visual `STATE`,
  syncs power/rate to nearby clients (packet id 22), and — when `enableTerraforming` — runs up
  to 1000 iterations draining the chip battery (80 RF each) and terraforming the next planet
  position via `TerraformingHelper`. [V] `TileTerraformingTerminal.java:129,171`
- **MECH-SAT-13 Data item transfer (terminal).** `loadData`/`storeData` move data between the
  terminal buffer and an `IDataItem` in slot 1, moving only what the receiver accepts.
  [V] `TileSatelliteTerminal.java:352,388`

### The satellite on its orbit

- **MECH-SAT-14 [V][SYS] An orbit is its elements; the phase is computed.** A released satellite holds
  a circular orbit: radius from the body's centre, inclination, the longitude of the ascending node, the
  body's gravitational parameter (Earth's, 3.986 004 418e14 m³/s², times the body's mass in Earth
  masses) and the release tick. Its phase — the angle from the node — advances at `√(μ/r³)`, one game
  tick standing for 3.6 s, and its position is computed in the body-centred frame whose axes do not turn
  with the body, z along the spin axis: the frame the environment is asked in. Nothing ticks. At release
  the node lies under the body's meridian 0 and the satellite is at the node; meridian 0 is where
  `BodyRotation` puts it — the body's rotational period, angle zero at tick zero — the same rule the
  field turns by. `SatelliteOrbit.java:46-91`, `BodyRotation.java:27-30`, `SatellitePlacement.java:141-174`.
  FOR: maintainer 2026-10-20, asked *"фазу считать по времени из элементов орбиты"*, answered *"этот"*.
- **MECH-SAT-15 [V][BEH] Where it stands.** The cell's frame flies the orbit, so the satellite stands
  still in its body's cell: at the body's address displaced by its place on the orbit at release, in
  chart blocks along the cell's axes — x stays x, the spin axis is Y, y is −Z. Two satellites released at
  different ticks stand at different points. A satellite with no orbit, a body the universe does not name,
  or a cell with no lattice gives no point. `SatellitePlacement.java:180-207`, `SatelliteOrbit.java:92-98`.
  FOR: maintainer 2026-10-20: *"У нас и корабль "стоит" на орбите. Спутник будет так же стоять"*.
- **MECH-SAT-16 [V][BEH] Release: the orbit is chosen on the guidance computer.** When a rocket deploys
  a hatch's satellite, the orbit is the body's at the altitude and inclination set on its guidance
  computer — with no altitude set, the body's orbit line (its line's world Y is its altitude, a world
  block being a metre); with no inclination, equatorial; a rocket with no guidance computer releases at
  both defaults. A body admits altitudes from its descent shell (below it there is no cell to stand in)
  to its sphere of influence (beyond it the satellite is no longer in its frame). Outside them, or about
  a body with no size, mass or sphere, no orbit exists: the satellite stays in the hatch and the pilot is
  told. The guidance computer's screen shows the range and whether the setting is inside it — the server
  computes it, the client is told. `EntityRocket.java:2608-2650`, `SatellitePlacement.java:107-169`,
  `TileGuidanceComputer.java` (`statedReleaseAltitudeM`, `computeReleaseStatus`). FOR: maintainer
  2026-10-20, choosing how a satellite gets its orbit: the altitude and inclination set at launch on the
  rocket's guidance computer, the planet's orbit line by default; 2026-10-21 on the bounds: *"да"*.
- **MECH-SAT-17 [V][BEH] Presence in a live cell.** A satellite's entity exists only in a loaded chunk
  of the live cell that holds its point: it is stood up when that chunk loads, and at release when it is
  already loaded. It is never saved with the cell — the record is the satellite — so a reload or a
  re-bound slot cannot duplicate it, and it dies once its record has left its body's list. It is
  kinematic: it integrates nothing, never moves, and is no physics ship. With no live cell the subsystem
  does no work for it. `SatelliteMaterializer.java:40-99`, `EntitySatellite.java` (`onUpdate`,
  `writeToNBTOptional`). FOR: maintainer 2026-10-20 on the satellite as an entity rather than a one-block
  ship: *"её можно уничтожить, и она кликабельна"*; 2026-10-21 on where it is while nobody sees it:
  *"ок"*.
- **MECH-SAT-18 [V][BEH] Attitude.** Its power generation faces the nearest star of its system and its
  dish its body, along the vectors the cell's sky is drawn along: the star's position at the tick minus
  the satellite's point, through the frames. Recomputed every 20 ticks — the sky feed's cadence — on a
  phase smeared by satellite id, and at once on its first update; the satellite's own phase is not
  applied, because the cell does not draw it. `SatelliteAttitude.java:51-82`, `EntitySatellite.java`
  (`onUpdate`). FOR: maintainer 2026-10-20: *"солнечные панели - к ближайшей звезде … тарелка для
  передачи данных - к планете"*; 2026-10-21 on the star of the cell's sky: *"согласен"*.
- **MECH-SAT-19 [V][BEH] Instruments face what the primary function surveys.** A data satellite's
  instruments face by the quantity it collects — distance is read across space, so away from the body;
  every other quantity is the body's, so toward it; a device that acts on its world faces it. Derived
  from the type, never stored. `DataStorage.java` (`DataType#subject`), `SatelliteData.java`
  (`instrumentSubject`), `SatelliteBase.java` (`instrumentSubject`). FOR: maintainer 2026-10-20:
  *"всякие измерительные приборы, если он исследует космос - от планеты, если исследует планету - к
  планете"*.
- **MECH-SAT-20 [V][BEH] Repair in orbit.** Right-click opens the builder's part panel on the satellite's
  own chassis: its seven slots at the builder's places. The primary function (slot 0) is shown and never
  moves — swapping it would make another type with another controller. A change to slots 1–6 re-sums the
  properties through the one sum assembly uses, keeps the satellite's id and type, and is sent to every
  client. `OrbitalPartsInventory.java:49-62`, `SatelliteAssembly.java:28-48`, `EntitySatellite.java`
  (`processInitialInteract`, `getModules`). FOR: maintainer 2026-10-20: *"По клику открывается... ну пока
  что пусть такая же панель, что и в сборнике. Это интерактивный ремонт"*; 2026-10-21 on slot 0: *"ок"*.
- **MECH-SAT-21 [V][BEH] Destruction leaves nothing.** An explosion or a projectile destroys a satellite,
  and so do two blows from a player within 5 ticks — the first is taken for a missed click. Its record
  leaves its body's list, a zero-strength explosion plays (sound and particles, no damage), and nothing
  drops. `EntitySatellite.java` (`attackEntityFrom`, `destroy`). FOR: maintainer 2026-10-20: *"При
  разрушении, по сути взрыве, спутника ничего не остаётся. Только анимашка взрыва"*; 2026-10-21 on the
  two blows: *"ок"*.

## State & persistence

NBT keys: see `C1-nbt-persistence` (satellite). Notes: `data` is a nested `DataStorage` in distinct scopes (the satellite
object vs the terminal tile), so the two never collide (`SatelliteData.java:149`, `TileSatelliteTerminal.java:333`);
`collectionMultiplier` is written, read, then discarded (`SatelliteData.java:136,152`); `lastActionTime` is also GUI-synced
through `sendChanges` window props. `TileTerraformingTerminal.writeToNBT`/`readFromNBT` are **no-ops** — its state is
re-derived each tick from redstone + chip, and `powergen`/`blockpertick` exist only as packet-22 wire fields (transient).
`SatelliteSpyTelescope` NBT bodies are empty. [V] `TileTerraformingTerminal.java:298,346`

A released satellite writes `orbit` {`radius`, `inclination`, `node`, `mu`, `epoch`} beside its other keys;
absent means no orbit (not yet released, or a mission). Its parts are the chassis item's `inv`, inside `item`.
The guidance computer writes `releaseAltitude` (metres, only when one is set) and `releaseInclination`
(degrees). `EntitySatellite` writes nothing: it is never saved. Its attitude rides two synced data
parameters (unit vectors as `Rotations`); its satellite id rides the spawn data.

## Invariants

- **INV-SAT-01** [T][BEH] Base battery never exceeds `powerStorage`; accrual is ≈`powerPerTick`/tick.
  `SatelliteTickBehaviourTest.java:63,88` Pinned by `SatelliteTickBehaviourTest#baseSatelliteTickAccruesAtApproximatelyPowerGenRate`, `SatelliteTickBehaviourTest#baseSatelliteBatteryCapsAtPowerStorage`.
- **INV-SAT-02** [T][BEH] `DataStorage.addData` is capped at `maxData`; a data satellite fires the
  data gate ~`ticks/collectionTime` times. `SatelliteTickBehaviourTest.java:112,134` Pinned by `SatelliteTickBehaviourTest#dataSatelliteAccumulatesDataOverTime`, `SatelliteTickBehaviourTest#dataSatelliteRespectsMaxDataCap`.
- **INV-SAT-03** [V][SYS] Weather `mode_id`/`last_mode_id`/`floodlevel` round-trip through NBT; the
  `floodlevel==-1` sentinel must survive so the lazy sea-level fallback fires.
  `SatelliteWeatherController.java:372-374` write, `:379-381` read; the fallback `:52-53`. FOR: save format: satellite survives reload.
- **INV-SAT-04** [V][SYS] Microwave `teir` byte round-trips through NBT. `SatelliteMicrowaveEnergy.java:78` write, `:84` read. FOR: save format: satellite survives reload.
- **INV-SAT-05** [T][BEH] Builder stamps the *same* fresh `satelliteId` into both the chassis item
  (`satId`) and the id chip (`satelliteId`); chassis slot is consumed, output stays empty until
  `completionTime`. `SatelliteBuilderPressBuildContractTest.java:76` Pinned by `SatelliteBuilderPressBuildContractTest#pressBuildAssemblesOpticalSatellite`.
- **INV-SAT-06** [T][BEH] A chip-overriding type (weatherController) rejects the default id chip in
  `canAssembleSatellite`. `SatelliteBuilderPressBuildContractTest.java:133` Pinned by `SatelliteBuilderPressBuildContractTest#pressBuildRejectsDefaultChipForChipOverridingType`.
- **INV-SAT-07** [A][BEH] Terminal status ladder: no chip→0, chip+no power→1, out of range→2,
  linked→3; erase removes the satellite from its dim and blanks the chip.
  `SatelliteTerminalChipRecognitionTest.java:87,107,120,133` (the out-of-range rung, status 2, has no test here) Pinned by `SatelliteTerminalChipRecognitionTest#chippedTerminalWithPowerReachesStatus3`, `SatelliteTerminalChipRecognitionTest#unchippedTerminalReportsNoLink`, `SatelliteTerminalChipRecognitionTest#chippedTerminalWithoutPowerReportsNoPower`, `SatelliteTerminalChipRecognitionTest#pressEraseRemovesSatelliteFromDimAndBlanksChip`.
- **INV-SAT-08** [V][BEH] Terminal download requires the resolved satellite be a `SatelliteData`
  subclass, in the same planetary system, with ≥`getPowerPerOperation()` RF.
  `TileSatelliteTerminal.java:83,228`
- **INV-SAT-09** [V][BEH] `TileTerraformingTerminal` hides its Forge-Energy / Tesla capability so
  probes/pipes see no energy handler. `TileTerraformingTerminal.java:327,337`
- **INV-SAT-10** [V][BEH] `maxQueuedBlocks` bounds a biome changer's `toChangeList` PER SATELLITE: 1 024
  until that satellite's first `performAction`, which raises its own cap to 8 000. The cap is per satellite, never a
  mutable static that one satellite's action would raise for every changer.
  `SatelliteBiomeChanger.java:24,114,125`
- **INV-SAT-11 [T][SYS] The phase is the clock's.** At every tick a released satellite's position lies at
  its orbit's radius from the body; one period of `2π√(r³/μ)` later it is where it was; at release it lies
  at the node under the body's meridian 0; a quarter period after the node it stands at the latitude of
  its inclination. REGIONS: `equatorial`, `inclined`, `polar`, `retrograde`. Pinned by
  `SatelliteLawsTest#thePhaseIsTheClocks`. FOR: maintainer 2026-10-20, asked *"фазу считать по времени из
  элементов орбиты"*, answered *"этот"*.
- **INV-SAT-12 [T][SYS] Standing still is orbiting.** A satellite's standing point lies at its orbit's
  radius from the body, along its place on the orbit at release; carried onto the cell's axes, every
  length is kept and the frame's handedness with it. Pinned by `SatelliteLawsTest#standingStillIsOrbiting`. FOR:
  maintainer 2026-10-20: *"Спутник будет так же стоять. Это некоторое приближение понятия "на орбите""*.
- **INV-SAT-13 [T][SYS] Releases at different ticks stand apart.** Two satellites released onto the same
  altitude and inclination at different ticks of one rotation stand at different points; released at the
  same tick, at the same point. Pinned by `SatelliteLawsTest#releasesAtDifferentTicksStandApart`. FOR:
  maintainer 2026-10-21, on the point taken from the place on the orbit at release: *"ок"*.
- **INV-SAT-14 [T][SYS] The attitude is computed from the tick.** The power-generation direction is the
  unit vector from the satellite's point to the NEAREST star at that tick, the dish's the unit vector to
  its body; when the star has moved between two ticks the attitude has followed it; with no star there is
  none. REGIONS: `one star`, `two stars`, `moved star`, `no star`. Pinned by
  `SatelliteLawsTest#theAttitudeIsComputedFromTheTick`. FOR: maintainer 2026-10-21, on facing the star of
  the cell's sky: *"согласен"*.
- **INV-SAT-15 [T][SYS] The quantity decides the instruments.** Distance faces space; humidity,
  temperature, composition, atmosphere density and mass face the body; an undefined quantity faces
  neither; a data satellite faces as the quantity it collects does.
  REGIONS: `space survey`, `body survey`, `undefined`. Pinned by
  `SatelliteLawsTest#theQuantityDecidesTheInstruments`. FOR: maintainer 2026-10-20: *"если он исследует
  космос - от планеты, если исследует планету - к планете"*.
- **INV-SAT-16 [T][BEH] Present only where its cell is live, and there at its point.** A released satellite
  stands up nowhere while its cell is not live; once the chunk of its live cell that holds its point loads,
  it stands up there, at exactly that point. Pinned by
  `SatelliteOnItsOrbitGroupTest#aSatelliteStandsAtItsPointOnlyWhereItsCellIsLive`.
- **INV-SAT-17 [T][BEH] A click opens its panel, and a swapped part takes effect.** A player's right-click on a
  standing satellite opens the builder's part panel on it; a power part put into it raises the satellite's
  generation by that part's own. Pinned by
  `SatelliteClientGroupTest#aClickOpensTheSatellitesPanelAndASwappedPartTakesEffect`.
- **INV-SAT-18 [T][BEH] Destroyed, it leaves nothing.** One blow from a player leaves it standing; two in a row
  destroy it, and so does an explosion; destroyed, its record is gone, nothing stands or lies where it stood,
  and only its own explosion is seen. Pinned by `SatelliteOnItsOrbitGroupTest#aSatelliteDestroyedLeavesNothing`.
- **INV-SAT-20 [T][BEH] Its panels face the star its sky draws.** The star direction a standing satellite
  carries and the star vector its cell's sky feed sends name one direction, to within the angle the
  satellite's offset from the sky's observer subtends and the star's motion over either side's cadence.
  Pinned by `SatelliteOnItsOrbitGroupTest#theStarASatelliteFacesIsTheStarItsSkyDraws`.
- **INV-SAT-19 [T][BEH] The guidance computer chooses the orbit.** A release goes to the altitude and
  inclination its rocket's guidance computer sets; with no altitude set, to the body's orbit line; where the
  body admits no orbit at the altitude set, the satellite stays in the hatch and nothing is released. Pinned
  by `SatelliteOnItsOrbitGroupTest#aSatelliteGoesToTheOrbitItsGuidanceComputerSets`.

## Failure modes & edge cases

- `SatelliteWeatherController.tickEntity` looks up `world` then dereferences it inside the
  `mode_id` branches without a null guard when `viable_positions` is non-empty → potential NPE
  if the dimension is unloaded mid-run. `SatelliteWeatherController.java:112,117`
- `SatelliteSpyTelescope.getInfo` returns `null`; any UI that renders info would NPE. It is
  `canTick()==false` and `performAction` no-ops, so effectively inert. `SatelliteSpyTelescope.java:13`
- `TileTerraformingTerminal.update` performs up to 1000 terraform iterations per tick and lazily
  loads/creates the `TerraformingHelper` (chunk-scoped work) inside the tick; it catches
  `NoClassDefFoundError` and prints stacktrace. `TileTerraformingTerminal.java:178,208`
- `SatelliteData.getDataCreated` extracts power even when the buffer is full (guard is
  `maxData >= data`, i.e. also true at exact cap). `SatelliteData.java:81,96`

## Integration seams

- **Packets (C2):** `PacketMachine` id 22 (terraforming terminal power/rate sync),
  ids 100/101 (terminal connect/erase), id -1/-2 (store/load data); `PacketAirParticle` /
  `PacketFluidParticle` (weather visuals) — all owned by network-wire, consumed here.
  `TileTerraformingTerminal.java:144`, `TileSatelliteTerminal.java:322`, `SatelliteWeatherController.java:120`
- **GUI sync:** `SatelliteData` overrides `numberChangesToSend`/`sendChanges`/`onChangeReceived`
  to stream `lastActionTime` as four 16-bit window properties. `SatelliteData.java:156`
- **Registry (C3):** satellite *types* keyed via `SatelliteRegistry.getKey(class)` in
  `SatelliteBase` ctor (`dataType` NBT); blocks/items `satelliteBuilder`,
  `satelliteControlCenter`, `weatherController`, `biomeChanger`, `satelliteIdChip`,
  `satellitePowerSource`, `satellitePrimaryFunction` registered from `Stellurgy.java`
  (misc-oddities), referenced here.
- **Entity (C3):** `stellurgy:satellite` (`EntitySatellite`), declared last in `EntityNetworkIds`; tracking
  range 256, update every 20 ticks, no velocity. Its attitude rides the entity's own synced data
  parameters, its satellite id the spawn data — no packet of its own.
- **Space and universe:** the standing point is the body's `addressAt` plus the place on the orbit
  (`GalacticCoord.plusLocal`); a live cell's slot is `SpaceManager.slotDimOf`; bounds read
  `DescentShell.orbitLineWorldY` and `SpaceSubsystem.zoneSphereRadiusOf`; the attitude reads the system's
  stars through `UniverseRegistry.systemBodiesAt` at the space clock, as the sky feed (C14) does.
- **Environment:** `SatelliteOrbit` is a `Trajectory`; meridian 0 is `BodyRotation`'s, the same rule the
  field turns by.
- **Capabilities:** `SatelliteMicrowaveEnergy implements IUniversalEnergyTransmitter`
  (libVulpes); terminals extend libVulpes `TileInventoriedRFConsumer` / `TileMultiPowerConsumer`.
- **Cross-subsystem:** `DimensionProperties.add_water_locked_pos` / rain marker
  (dimension-planets), `PlanetWeatherManager.syncToPlayersInWorld` + `ChunkManagerPlanet`
  (world-gen), `TileWirelessTransceiver` extract-plug detection (wirelessdata).

## Config surface

Config: see `C4-config-surface` (`enableTerraforming` false ⇒ the terminal still detects chip + redstone and syncs the GUI but runs **no** terraform loop, `TileTerraformingTerminal.java:172`).

## Test coverage

INV-SAT-11..15 → `SatelliteLawsTest` (unit); INV-SAT-16, -18, -19, -20 → `SatelliteOnItsOrbitGroupTest` (server);
INV-SAT-17 → `SatelliteClientGroupTest` (client). Not pinned: the render's appearance (how the parts look),
and the guidance computer's screen fields. "Never a physics ship" holds by construction — the materializer
spawns a plain entity and nothing on its path reaches the physics substrate.
INV-SAT-01/02 → `SatelliteTickBehaviourTest`; INV-SAT-03/04 are storage round trips, read in code, not tested;
INV-SAT-05/06 → `SatelliteBuilderPressBuildContractTest`; INV-SAT-07 → `SatelliteTerminalChipRecognitionTest`;
terraforming chip recognition → `TerraformingTerminalChipRecognitionTest`,
`TerraformingTerminalSmokeTest`; scanning types → `ScanningSatelliteTickContractTest`,
`SatelliteTypeBehaviourTest`, `ScanningSatelliteContractTest`; lifecycle →
`SatelliteLifecycleSmokeTest`, `SatelliteCoverageGapsTest`.

## Open questions

- Does `SatelliteWeatherController.viable_positions` (never persisted) leaving stale positions
  across a mode toggle vs a reload matter for players? Cleared on mode change only.
- Is `teir` (`SatelliteMicrowaveEnergy`) ever set to a non-zero value in production? No writer
  found in this subsystem — appears write-0/read-0 today (future-reserved).
