---
id: satellite
owns: [satellite/, tile/satellite/, api/satellite/]
entrypoints: [SatelliteData#tickEntity, SatelliteWeatherController#tickEntity, TileSatelliteBuilder#assembleSatellite, TileSatelliteTerminal#performFunction, TileTerraformingTerminal#update]
depends-on: [api-public, dimension-planets, network-wire, items, util-core, world-gen, wirelessdata]
depended-by: [misc-oddities, blocks, items, commands-gameplay]
contracts: [C1, C2, C3, C4]
confidence: high
---

## Purpose

Concrete satellite *types* (the orbital devices a player builds and launches) plus the
three ground-side tiles that build, query and drive them. Each satellite is a server-side
data object stored in a planet's `DimensionProperties` satellite list; this subsystem owns
the behaviour of each type (`tickEntity`, `performAction`, NBT) and the machines that mint
and interrogate them. The abstract base (`SatelliteBase`, `SatelliteProperties`,
`SatelliteRegistry`, `DataStorage`) lives in `api-public` and is *not* owned here.

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
  [V] `SatelliteWeatherController.java:98,174,283,200` [T] `SatelliteWeatherAndMicrowaveNbtTest.java:51`
- **MECH-SAT-08 Satellite assembly.** `TileSatelliteBuilder.assembleSatellite` sums power/data/
  weight from part slots 1-6, mints `SatelliteProperties` with a fresh id from
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

## State & persistence

NBT keys: see `C1-nbt-persistence` (satellite). Notes: `data` is a nested `DataStorage` in distinct scopes (the satellite
object vs the terminal tile), so the two never collide (`SatelliteData.java:149`, `TileSatelliteTerminal.java:333`);
`collectionMultiplier` is written, read, then discarded (`SatelliteData.java:136,152`); `lastActionTime` is also GUI-synced
through `sendChanges` window props. `TileTerraformingTerminal.writeToNBT`/`readFromNBT` are **no-ops** — its state is
re-derived each tick from redstone + chip, and `powergen`/`blockpertick` exist only as packet-22 wire fields (transient).
`SatelliteSpyTelescope` NBT bodies are empty. [V] `TileTerraformingTerminal.java:298,346`

## Invariants

- **INV-SAT-01** [T][BEH] Base battery never exceeds `powerStorage`; accrual is ≈`powerPerTick`/tick.
  `SatelliteTickBehaviourTest.java:63,88` Pinned by `SatelliteTickBehaviourTest#baseSatelliteTickAccruesAtApproximatelyPowerGenRate`, `SatelliteTickBehaviourTest#baseSatelliteBatteryCapsAtPowerStorage`.
- **INV-SAT-02** [T][BEH] `DataStorage.addData` is capped at `maxData`; a data satellite fires the
  data gate ~`ticks/collectionTime` times. `SatelliteTickBehaviourTest.java:112,134` Pinned by `SatelliteTickBehaviourTest#dataSatelliteAccumulatesDataOverTime`, `SatelliteTickBehaviourTest#dataSatelliteRespectsMaxDataCap`.
- **INV-SAT-03** [T][SYS] Weather `mode_id`/`last_mode_id`/`floodlevel` round-trip through NBT; the
  `floodlevel==-1` sentinel must survive so the lazy sea-level fallback fires.
  `SatelliteWeatherAndMicrowaveNbtTest.java:51,79` Pinned by `SatelliteWeatherAndMicrowaveNbtTest#weatherControllerNbtRoundTripPreservesModeIdLastModeIdAndFloodlevel`, `SatelliteWeatherAndMicrowaveNbtTest#weatherControllerNbtRoundTripPreservesFreshDefaults`. FOR: save format: satellite survives reload.
- **INV-SAT-04** [T][SYS] Microwave `teir` byte round-trips through NBT. `SatelliteWeatherAndMicrowaveNbtTest.java:108` Pinned by `SatelliteWeatherAndMicrowaveNbtTest#microwaveEnergyTeirByteRoundTripsAcrossNbt`. FOR: save format: satellite survives reload.
- **INV-SAT-05** [T][BEH] Builder stamps the *same* fresh `satelliteId` into both the chassis item
  (`satId`) and the id chip (`satelliteId`); chassis slot is consumed, output stays empty until
  `completionTime`. `SatelliteBuilderPressBuildContractTest.java:76` Pinned by `SatelliteBuilderPressBuildContractTest#pressBuildAssemblesOpticalSatellite`.
- **INV-SAT-06** [T][BEH] A chip-overriding type (weatherController) rejects the default id chip in
  `canAssembleSatellite`. `SatelliteBuilderPressBuildContractTest.java:133` Pinned by `SatelliteBuilderPressBuildContractTest#pressBuildRejectsDefaultChipForChipOverridingType`.
- **INV-SAT-07** [T][BEH] Terminal status ladder: no chip→0, chip+no power→1, out of range→2,
  linked→3; erase removes the satellite from its dim and blanks the chip.
  `SatelliteTerminalChipRecognitionTest.java:87,107,120,133` (the out-of-range rung, status 2, has no test here) Pinned by `SatelliteTerminalChipRecognitionTest#chippedTerminalWithPowerReachesStatus3`, `SatelliteTerminalChipRecognitionTest#unchippedTerminalReportsNoLink`, `SatelliteTerminalChipRecognitionTest#chippedTerminalWithoutPowerReportsNoPower`, `SatelliteTerminalChipRecognitionTest#pressEraseRemovesSatelliteFromDimAndBlanksChip`.
- **INV-SAT-08** [V][BEH] Terminal download requires the resolved satellite be a `SatelliteData`
  subclass, in the same planetary system, with ≥`getPowerPerOperation()` RF.
  `TileSatelliteTerminal.java:83,228`
- **INV-SAT-09** [V][BEH] `TileTerraformingTerminal` hides its Forge-Energy / Tesla capability so
  probes/pipes see no energy handler. `TileTerraformingTerminal.java:327,337`
- **INV-SAT-10** [A][BEH] `maxQueuedBlocks` bounds a biome changer's `toChangeList` PER SATELLITE: 1 024
  until that satellite's first `performAction`, which raises its own cap to 8 000. The cap is per satellite, never a
  mutable static that one satellite's action would raise for every changer.
  `SatelliteBiomeChanger.java:24,114,125`

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
- **Capabilities:** `SatelliteMicrowaveEnergy implements IUniversalEnergyTransmitter`
  (libVulpes); terminals extend libVulpes `TileInventoriedRFConsumer` / `TileMultiPowerConsumer`.
- **Cross-subsystem:** `DimensionProperties.add_water_locked_pos` / rain marker
  (dimension-planets), `PlanetWeatherManager.syncToPlayersInWorld` + `ChunkManagerPlanet`
  (world-gen), `TileWirelessTransceiver` extract-plug detection (wirelessdata).

## Config surface

Config: see `C4-config-surface` (`enableTerraforming` false ⇒ the terminal still detects chip + redstone and syncs the GUI but runs **no** terraform loop, `TileTerraformingTerminal.java:172`).

## Test coverage

INV-SAT-01/02 → `SatelliteTickBehaviourTest`; INV-SAT-03/04 → `SatelliteWeatherAndMicrowaveNbtTest`;
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
