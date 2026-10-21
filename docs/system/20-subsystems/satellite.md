---
id: satellite
owns: [satellite/, tile/satellite/, api/satellite/, entity/EntitySatellite, client/render/entity/RenderSatellite]
entrypoints: [SatelliteBase#bringUpToNow, SatelliteLife#bringUpTo, SatelliteWeatherController#tickEntity, TileSatelliteBuilder#assembleSatellite, TileSatelliteTerminal#performFunction, TileTerraformingTerminal#update, EntityRocket#deploySatelliteFromHatch, SatelliteMaterializer#onChunkLoad, EntitySatellite#onUpdate]
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
| `SatelliteData` (abstract) | data-collecting satellite: buffers `DataStorage`; collects with power on its orbit's grid (MECH-SAT-24); base for the four scanners `satellite/SatelliteData.java:23` |
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
| `SatelliteAccrual` | a satellite's power, data, part wear, data losses and readings over whole segments of its orbit — pure, never ticked `satellite/SatelliteAccrual.java` |
| `SatelliteLife` | brings a satellite up to a tick: its body, field, star and chassis parts into the accrual, the doses back onto the parts `satellite/SatelliteLife.java` |
| `RadiationWear` | the measured laws: shield factors, electronics budget, panel curve, memory upsets `satellite/RadiationWear.java` |
| `FailureJournal` | a satellite's dated record of what failed aboard, bounded `satellite/FailureJournal.java` |
| `DoseReadings` / `SatelliteDosimeter` | a dosimeter's dated readings, "absent here" over a fieldless body; the satellite type that takes them `satellite/DoseReadings.java`, `satellite/SatelliteDosimeter.java` |

## Mechanics

- **MECH-SAT-01** — retired.
- **MECH-SAT-02 Data offload.** `SatelliteData.performAction` pushes the whole buffer into an
  adjacent `IDataHandler` tile via `addData` and removes what was accepted. [V] `SatelliteData.java:62`
- **MECH-SAT-03** — retired.
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
  own chassis: its seven slots at the builder's places. The primary function (slot 0) may be taken out and
  takes only a primary function of the satellite's own type — another type would make another satellite
  with another controller — so one the dose has stopped can be renewed. Before any change the satellite's
  life is brought to the present with the parts it had; every change re-sums the properties through the
  one sum assembly uses, keeps the satellite's id and type, and is sent to every client.
  `OrbitalPartsInventory.java` (`sameTypePrimary`, `partsBeforeChange`, `store`), `SatelliteAssembly.java`,
  `EntitySatellite.java` (`processInitialInteract`, `getModules`). FOR: maintainer 2026-10-20: *"По клику
  открывается... ну пока что пусть такая же панель, что и в сборнике. Это интерактивный ремонт"*; on a dead
  satellite being repairable this way; 2026-10-21 on slot 0 never changing the type: *"ок"*.
- **MECH-SAT-21 [V][BEH] Destruction leaves nothing.** An explosion or a projectile destroys a satellite,
  and so do two blows from a player within 5 ticks — the first is taken for a missed click. Its record
  leaves its body's list, a zero-strength explosion plays (sound and particles, no damage), and nothing
  drops. `EntitySatellite.java` (`attackEntityFrom`, `destroy`). FOR: maintainer 2026-10-20: *"При
  разрушении, по сути взрыве, спутника ничего не остаётся. Только анимашка взрыва"*; 2026-10-21 on the
  two blows: *"ок"*.

### The dose on its parts

- **MECH-SAT-22 [V][SYS] Its life is computed when asked, on a fixed grid.** A released satellite's power,
  data, wear and failure journal are not ticked: they are brought up to the space clock's present when
  something reads or spends them — a terminal's download, its own status, a repair, a microwave receiver's
  pull, an ore scan, and every 20 ticks while its entity stands in a live cell. Time is cut into segments
  of a 64th of its orbital period counted from the orbit's epoch; only whole segments are taken, each is
  evaluated at its middle, and its chance is drawn from a generator seeded by the satellite and the
  segment's index. A type that acts on its world every tick (the biome changer, the weather controller,
  missions) is still ticked, and brings its life up to date first. A satellite with no orbit is not in
  space and accrues nothing. `SatelliteAccrual.java` (`advance`), `SatelliteLife.java` (`bringUpTo`),
  `SatelliteBase.java` (`canTick`, `bringUpToNow`). FOR: maintainer 2026-10-21, on making the per-tick
  accrual lazy: *"ок"*; and on the grid, taken *"по рекомендациям"*.
- **MECH-SAT-23 [V][SYS] Power comes from the star, and the body hides it.** Its panels give power only
  while the point on its orbit is outside the body's cylindrical shadow along the direction to the nearest
  star; the star's direction is read on a fixed grid of a tenth of a day. Every satellite spends 1 power
  unit per tick on upkeep; the battery holds between empty and its capacity. `SatelliteAccrual.java`
  (`sunlit`, `advance`), `SatelliteLife.java` (`starDirection`). FOR: maintainer 2026-10-21, on the
  body's shadow belonging to the dose's computation: *"согласен"*, and including it, *"по
  рекомендациям"*.
- **MECH-SAT-24 [V][SYS] Data is collected with power.** A data satellite's instrument draws half of what
  its working panels were built to give while it collects, and collects one point per `200/√(0.1·P)`
  ticks of full power, `P` its built generation. Short of that power it collects for the share of the
  segment the energy covers; with its buffer full it does not collect. So worn panels and eclipses mean
  fewer points, with nothing lost. `SatelliteAccrual.java` (`advance`), `SatelliteData.java` (`payload`).
  FOR: maintainer 2026-10-20, on the next contact window showing *"that less arrived than the power
  allowed"*.
- **MECH-SAT-25 [V][SYS] The dose reaches each part as it is placed.** From the body's field along its
  orbit, each segment gives a dose per belt (inner-belt protons, outer-belt electrons, Gy(Si) behind the
  reference shield, times the configured scale). The panels take the whole of it. What sits inside the
  chassis — the primary function and the data units — takes it thinned by a shield part if one is
  installed: protons by 117/64.7, electrons by 1266/1.5 (Shields-1, SHIELDOSE-2 for an aluminium sphere,
  0.535 → 3.00 g/cm²); the shield part weighs that step of aluminium over a block-sized satellite's six
  square metres, 148 kg (`WeightEngine`). The battery and the shield take none. The dose is held on each part's own item, so
  a part carries its wear wherever it goes. `SatelliteAccrual.java` (`advance`), `RadiationWear.java`,
  `SatelliteLife.java` (`partOf`, `DOSE_KEY`). FOR: maintainer 2026-10-20, *"и то и другое в зависимости
  от дозы"* and the per-part rule, *"да"*; 2026-10-21, the shield covers what is inside
  the chassis and thins each belt by its own factor, *"по рекомендациям"*.
- **MECH-SAT-26 [V][SYS] Each part has a budget.** Electronics stop at 50 Gy (5 krad — NASA's SmallSat note:
  most commercial components tolerate 5 krad). A panel gives `1 − 0.2·log10(1 + D/316 Gy)` of its power —
  the triple-junction datasheet curve, carried onto our dose by the 15-year geostationary design point —
  and nothing once that reaches zero. A part that stops is journalled with its slot; it adds nothing to
  the satellite's sum but its mass. A stopped primary function is a dead satellite: it collects, reads,
  transmits and scans nothing, and stays in its body's list and in orbit. A stopped data unit takes its
  capacity with it, and the points over the new capacity are lost and journalled.
  `RadiationWear.java`, `SatelliteAccrual.java`, `SatelliteAssembly.java`, `SatelliteLife.java`
  (`functioning`). FOR: maintainer 2026-10-20, a part past its budget stops; 2026-10-21 on the
  figures, *"по рекомендациям"*.
- **MECH-SAT-27 [V][SYS] Memory upsets destroy data.** Each buffered point is lost in a segment with
  probability `1 − exp(−k·D_p)`, `D_p` the protons reaching the memory and `k` = 1.5e-14 cm²/bit (a
  commercial 65 nm SRAM) × 1.08e9 protons/cm² per Gy × 8·2²⁰ bits (a 1 MB point — a choice). Each
  segment's losses are one journal entry with their count. `RadiationWear.java`
  (`pointLossProbability`), `SatelliteAccrual.java` (`binomial`). FOR: maintainer 2026-10-20, data points
  dropped with a probability proportional to the dose rate, each loss journalled.
- **MECH-SAT-28 [V][BEH] The failure journal.** A satellite keeps its own dated record: data lost, a part
  stopped, data that no longer fit — each with its tick and its phase on the orbit. It holds 256 entries;
  older ones are dropped and counted. It is evidence about the device, never a measurement of the place.
  `FailureJournal.java`. FOR: maintainer 2026-10-20, the device's own dated journal, *"да"*.
- **MECH-SAT-29 [V][BEH] The dosimeter.** A primary function (`satellitePrimaryFunction` metadata 7, type
  `dosimeter`) that reads the trapped dose rate at the satellite's point once per segment, while its
  electronics work, with the tick and the phase; over a body with no field the reading is "absent here".
  1024 readings are held, older ones dropped and counted. The quantity is `DataType.RADIATION_DOSE_RATE`,
  Gy/s. `SatelliteDosimeter.java`, `DoseReadings.java`. FOR: maintainer 2026-10-20 on the quantity,
  *"Я бы взял то, что по си"*; 2026-10-21 on the dosimeter, *"по рекомендациям"*.
- **MECH-SAT-30 [V][BEH] The wear can be switched off.** With `satelliteRadiationWear` off no part takes
  dose, no point is lost, no part is stopped by its dose and nothing of that kind is journalled; power,
  eclipses and a dosimeter's readings go on. `satelliteRadiationWearScale` multiplies the dose the parts
  and the memory take. `SatelliteAccrual.java` (`Part#works`, `advance`), `SatelliteLife.java` (`rules`).
  FOR: maintainer 2026-10-21: *"Износ можно отключить, да. Но рядом с флагом надо написать, что это
  отключает и часть геймплея."*

## State & persistence

NBT keys: see `C1-nbt-persistence` (satellite). Notes: `data` is a nested `DataStorage` in distinct scopes (the satellite
object vs the terminal tile), so the two never collide (`SatelliteData.java:149`, `TileSatelliteTerminal.java:333`);
`collectionMultiplier` is written, read, then discarded (`SatelliteData.java:136,152`); `lastActionTime` is also GUI-synced
through `sendChanges` window props. `TileTerraformingTerminal.writeToNBT`/`readFromNBT` are **no-ops** — its state is
re-derived each tick from redstone + chip, and `powergen`/`blockpertick` exist only as packet-22 wire fields (transient).
`SatelliteSpyTelescope` NBT bodies are empty. [V] `TileTerraformingTerminal.java:298,346`

A released satellite writes `orbit` {`radius`, `inclination`, `node`, `mu`, `epoch`} beside its other keys;
absent means no orbit (not yet released, or a mission). Its parts are the chassis item's `inv`, inside `item`;
each part that has taken dose carries `radiationDose` (Gy) on its own item. Every satellite writes `life`
{`segments`, `energyCarry`, `collectionCarry`, `journal` {`entries` [{`tick`, `phase`, `kind`, `count`,
`slot`}], `dropped`}}; a dosimeter adds `readings` {`readings` [{`tick`, `phase`, `rate`?}], `dropped`}, a
missing `rate` being "absent here". Nothing records where it has been: only the parts' doses and the journal.
The guidance computer writes `releaseAltitude` (metres, only when one is set) and `releaseInclination`
(degrees). `EntitySatellite` writes nothing: it is never saved. Its attitude rides two synced data
parameters (unit vectors as `Rotations`); its satellite id rides the spawn data.

## Invariants

- **INV-SAT-01** — retired.
- **INV-SAT-02** — retired.
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
- **INV-SAT-21 [T][SYS] Asking does not change the answer.** A satellite's state at a tick is the same
  whether it was brought there in one catch-up or in any number of pieces, irregular ones included: its
  data, its journal and the ticks of its readings exactly; its energy, the dose on every part and the
  rates it read to within the rounding of the arithmetic — the platform's transcendental functions may
  differ in their last bit between an interpreted and a compiled call, and nothing else may differ. REGIONS: `one catch-up`, `per segment`,
  `irregular pieces`. Pinned by `SatelliteLawsTest#askingDoesNotChangeTheAnswer`. FOR: maintainer 2026-10-20, *"an unloaded orbit costs nothing per tick … and on load
  the wear equals what was accrued over the interval"*, and making the per-tick accrual lazy, *"ок"*.
- **INV-SAT-22 [T][SYS] Failures follow the place.** Of two identical satellites on one low orbit at
  different phases, over a window in which only one of them crosses the high-dose segment, that one
  journals data lost and the other journals none; and over a whole day the losses, weighted by their
  count, sit at the dose rate that losses in proportion to the dose predict — `Σr²/Σr` over the day's
  segments, within a quarter — so the journal, correlated with phase, reproduces the region. Pinned by `SatelliteJointsTest#failuresFollowThePlace`. FOR: maintainer 2026-10-20 — *"Сбой зависит от поля в точке, где спутник
  сейчас"* — so that two satellites on one orbit at different phases tell the region apart by their journals.
- **INV-SAT-23 [T][SYS] Each part answers to its own dose.** An unscreened satellite crossing the inner
  belt's low segment loses data while its generation stays within a thousandth of what it was built for; a
  shielded satellite in the outer belt loses generation while no point is lost.
  REGIONS: `electronics exposed`, `panels exposed`. Pinned by `SatelliteLawsTest#eachPartAnswersToItsOwnDose`. FOR: maintainer 2026-10-20, the per-part rule, *"да"*, which is what tells data loss from a power fault, and
  acceptance 3.
- **INV-SAT-24 [T][SYS] A shield thins what is inside, not the panels.** The same satellite on the same
  orbit over the same interval takes less dose on its primary function and data units with a shield part
  than without, by each belt's factor, and the same dose on its panels.
  REGIONS: `inner belt`, `outer belt`. Pinned by `SatelliteLawsTest#aShieldThinsWhatIsInsideNotThePanels`. FOR: maintainer 2026-10-21, a shield covering what is inside the chassis, thinning each belt by its own factor, *"по рекомендациям"*.
- **INV-SAT-25 [T][SYS] Past its budget a part stops, and the satellite with it.** Electronics work below
  their budget and stop at it, and the stop is journalled once with the slot; a stopped primary function
  collects and reads nothing more; the same satellite with that part renewed works again; a stopped data
  unit's capacity is gone and the points over it are journalled as overflowed. REGIONS: `below budget`,
  `at budget`, `renewed`, `data unit`. Pinned by `SatelliteLawsTest#pastItsBudgetAPartStops`. FOR: maintainer 2026-10-20, a part past its budget stops, and dead electronics make a dead satellite.
- **INV-SAT-26 [T][SYS] The dosimeter reads the place.** Each reading equals the trapped dose rate the body's
  environment gives at the satellite's point at that reading's tick; over a body with no field every
  reading is "absent here". REGIONS: `field`, `no field`. Pinned by `SatelliteJointsTest#theDosimeterReadsThePlace`. FOR: maintainer 2026-10-20 on the dose quantity,
  *"Я бы взял то, что по си"*, and a body with no field read as "absent here", never as zero.
- **INV-SAT-27 [T][SYS] Power bounds what is collected.** The battery holds between empty and its capacity;
  in the body's shadow nothing is generated; in full light with energy to spare one point arrives per
  collection time, never more and never past the buffer's capacity; and a satellite whose panels give less
  collects fewer points than one whose panels give what they were built to, with nothing lost. REGIONS:
  `light`, `shadow`, `full buffer`, `worn panels`. Pinned by `SatelliteLawsTest#powerBoundsWhatIsCollected`. FOR: maintainer 2026-10-20, a contact window showing *"that less arrived than the power allowed"*.
- **INV-SAT-28 [T][SYS] Nothing records where it has been.** What a satellite persists of its life is its
  progress along the grid, the carries below one unit, its journal, its readings and its parts' doses — no
  region, no flag of having been somewhere. Pinned by `SatelliteLawsTest#nothingRecordsWhereItHasBeen`. FOR: the design of the satellite on its orbit, *"Nothing stores 'was in the
  region'"*.
- **INV-SAT-29 [T][SYS] Switched off, the dose does nothing.** With the wear off the same satellite on the same
  orbit takes no dose on any part, loses no point and journals nothing, its panels give what they were
  built to, and its dosimeter reads what it reads with the wear on. REGIONS: `wear on`, `wear off`. Pinned by `SatelliteLawsTest#switchedOffTheDoseDoesNothing`. FOR:
  maintainer 2026-10-21, *"Износ можно отключить, да"*.
- **INV-SAT-31 [A][SYS] A shield part weighs its aluminium.** The shield part's mass is its areal density,
  2.465 g/cm² of aluminium, over the six square metres of a block-sized satellite's faces, so a
  satellite built with it is that much heavier. FOR: maintainer 2026-10-20, a shield part that *"adds
  mass (a heavier payload)"*.
- **INV-SAT-32 [A][BEH] On its orbit, its parts take its body's field.** While the server ticks, a satellite
  released onto an orbit inside its body's belts carries on its panel the dose the environment the game
  derives for that body gives along that orbit over the segments taken; with the wear switched off, its
  parts take no more. FOR: maintainer 2026-10-20, the dose acts on the satellite's parts, part by part,
  and 2026-10-21, *"Износ можно отключить, да"*.
- **INV-SAT-33 [A][BEH] A dead satellite stays on its orbit, and a renewed part revives it.** A satellite whose
  primary function the dose has stopped is still recorded on its orbit, journalled with the slot that
  stopped; its panel takes a primary function of its own type, which makes it work again, and refuses one
  of another type. FOR: maintainer 2026-10-20, *"dead electronics make a dead satellite, which stays in
  orbit as an object (and can be repaired in orbit)"*.
- **INV-SAT-30 [T][SYS] The budgets are the measured ones.** A panel keeps 0.91, 0.87 and 0.73 of its power,
  within 0.02, at the doses that stand for 5e14, 1e15 and 5e15 e/cm² of 1 MeV electrons on the
  geostationary design point (1e15 e/cm² for 15 years of our geostationary dose); electronics work below
  5 krad(Si) and stop at it. Pinned by `SatelliteLawsTest#theBudgetsAreTheMeasuredOnes`. FOR: maintainer 2026-10-21 on the figures, *"по рекомендациям"*.
- **INV-SAT-34 [T][SYS] Each data satellite collects its one quantity.** The optical telescope collects
  distance, the density scanner atmosphere density, the mass scanner mass and the composition scanner
  composition (`satellite/SatelliteOptical.java:12`, and the same line of the other three); its buffer
  refuses a point of any other quantity and takes one of its own. The ore scanner is no data satellite: its
  payload is the base one, which collects nothing (`api/satellite/SatelliteBase.java:145`, read in code, not
  pinned) — it scans when its player asks. REGIONS: `distance`, `atmosphere density`, `mass`, `composition`, `one quantity`. Pinned
  by `SatelliteLawsTest#eachDataSatelliteCollectsItsOneQuantity`. FOR: a storage takes a point only of its
  own quantity (`DataStorage#addData`), so a scanner that changed its quantity would hand every reader of
  its data the wrong one.

## Failure modes & edge cases

- `SatelliteWeatherController.tickEntity` looks up `world` then dereferences it inside the
  `mode_id` branches without a null guard when `viable_positions` is non-empty → potential NPE
  if the dimension is unloaded mid-run. `SatelliteWeatherController.java:112,117`
- `SatelliteSpyTelescope.getInfo` returns `null`; any UI that renders info would NPE. It is
  `canTick()==false` and `performAction` no-ops, so effectively inert. `SatelliteSpyTelescope.java:13`
- `TileTerraformingTerminal.update` performs up to 1000 terraform iterations per tick and lazily
  loads/creates the `TerraformingHelper` (chunk-scoped work) inside the tick; it catches
  `NoClassDefFoundError` and prints stacktrace. `TileTerraformingTerminal.java:178,208`
- A satellite's life is taken only when something reads or spends it; a satellite nobody asks about for
  a long time is brought up in one catch-up, whose cost grows with the segments missed (a low orbit has
  64 of them in about 1 540 ticks) and never with the tick rate.

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
  `satellitePowerSource`, `satellitePrimaryFunction` (metadata 7 the dosimeter), `satelliteShield`
  registered from `Stellurgy.java` (misc-oddities), referenced here; satellite type `dosimeter`.
  `satelliteShield` and the dosimeter have no recipe.
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
The dose on its parts: INV-SAT-21, -23..-25, -27..-31 → `SatelliteLawsTest` (unit); INV-SAT-22, -26 →
`SatelliteJointsTest` (integration, the joint with `environment`); INV-SAT-32, -33 → `SatelliteOnItsOrbitGroupTest`
(server). Not pinned: a player's path to seeing a failure — the journal's and the dosimeter's readout is
the terminal's downlink, not built yet; and the renewal through a window click (INV-SAT-33 enters the
panel's inventory on the server).
INV-SAT-03/04 are storage round trips, read in code, not tested;
INV-SAT-05/06 → `SatelliteBuilderPressBuildContractTest`; INV-SAT-07 → `SatelliteTerminalChipRecognitionTest`;
terraforming chip recognition → `TerraformingTerminalChipRecognitionTest`,
`TerraformingTerminalSmokeTest`; the quantity each data satellite collects, INV-SAT-34 →
`SatelliteLawsTest` (unit); scanning types → `ScanningSatelliteTickContractTest`,
`SatelliteTypeBehaviourTest`, `ScanningSatelliteContractTest`; lifecycle →
`SatelliteLifecycleSmokeTest`, `SatelliteCoverageGapsTest`.

## Open questions

- Does `SatelliteWeatherController.viable_positions` (never persisted) leaving stale positions
  across a mode toggle vs a reload matter for players? Cleared on mode change only.
- Is `teir` (`SatelliteMicrowaveEnergy`) ever set to a non-zero value in production? No writer
  found in this subsystem — appears write-0/read-0 today (future-reserved).
