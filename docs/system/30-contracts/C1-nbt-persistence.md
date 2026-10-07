---
id: C1-nbt-persistence
covers: [all subsystem "State & persistence" sections]
confidence: high
---

# C1 — NBT persistence keys

Consolidates the NBT keys written by the mod against the "State & persistence" section of every
subsystem doc. The exhaustive key list is computed from the live tree by the coverage checker
(`docs/system/tools/check-coverage.py`), not stored here; this doc keeps the rules, the collisions
and the hazards.

## Persistence model — why most apparent clashes are not clashes

Minecraft hands every persistence root its **own private `NBTTagCompound`**: one per
`TileEntity` (`writeToNBT`), one per `Entity` (`writeEntityToNBT`), one per `ItemStack`
(the stack tag), one per `WorldSavedData`, one per nested sub-tag a class allocates. Two
tiles that both write `state` write into two different compounds that never meet on disk.

Therefore a key is a **collision only when two *unrelated* owners co-write it into the
*same* compound**. In this codebase that happens in exactly three structural ways:

- **CMP-1 shared ItemStack tag** — several item / util / capability classes read-modify-write
  the *same* stack's root tag (e.g. a space-suit chest).
- **CMP-2 delegated co-write** — one class calls another's `writeToNBT(sameCompound)`, so both
  key-sets land in one compound. Live case: `SpaceStationObject.writeToNBT` calls
  `properties.writeToNBT(nbt)` (`SpaceStationObject.java`), merging all `DimensionProperties`
  keys into the station compound.
- **CMP-3 global SavedData** — `DimensionManager`, `SpaceObjectManager`,
  `WirelessNetworkSavedData`, `PlanetWeatherSavedData` each own one process-wide compound.

Everything else in the `id` / `state` / `redstoneState` / `x,y,z` / `pos` / `dimId`
fanout is **N distinct private compounds**, disambiguated below. This is stated in-repo at
`multiblock-machines/orbital-laser-drill.md`, `satellite.md`,
`space-stations/object-model.md`.

**Scope note:** a plain key scan also matches non-persistence names — config
field names (`minDimension`, `orbitHeight`, `lavaCentrifugePower/Time`, `stationClearance`,
`dataBusBigMultiplier`, `blackListVanillaBiomes`, `generateVanillaStructures`,
`transBodyInjection`, `resetPlanetsFromXML`, `ResetOnlyOnce` → **C4**), lang keys
(`tooltip.stellurgy.*` → **C7**), vanilla/mixin gamerule + reflection names
(`doDaylightCycle`, `doWeatherCycle`, `forge.test.client`), and packet-only fields
(`pressure` — an int —, `breathable`, `warning`, `holds` in `PacketAtmSync` → **C2**). They are excluded from the persistence
analysis but listed here so contract coverage counts them as owned.

## Disambiguation — the high-fanout keys

| key | co-owners (distinct compounds unless noted) | verdict |
|-----|----------|---------|
| `id` | `StellarBody` (star id), `SpaceObjectBase`/`SpaceStationObject` (station id), `TileOrbitalRegistry`/`TileGuidanceComputer`/`TileDockingPort`/`TileLandingPad`/`TileWarpController` (registry/link ids), `StorageChunk` (per-tile sub-tag), `ItemOreScanner`/`ItemAirUtils` (stack), `TilePlanetSelector`, `TileRocketMonitoringStation`, `TileRocketAssemblingMachine` | **no clash** — every use is a private TE/entity/item/star compound or a list sub-tag |
| `state` | 12 tiles (oxygen vent, fueling, fluid/rocket loader, monitoring, astrobody, railgun, altitude/gravity controllers, holographic selector, transceiver) each in own `writeToNBT` | **no clash** — 12 private tile compounds; semantics = "redstone/machine state" per tile |
| `redstoneState` | 9 tiles, own compounds | **no clash**; enum-ordinal hazard → see risks |
| `x` `y` `z` | `DimensionProperties` (sub-tags), `EntityElevatorCapsule` (entity), `ItemStationChip` (stack), `SpacePosition`/`StorageChunk` (sub-tags), `RocketEntityProvider` (waila render, not persisted) | **no clash** — coordinate triples inside separate compounds |
| `dimId` | `SatelliteBase`/`ItemSatelliteIdentificationChip` (sat id-chip stack + props), `TileOrbitalRegistry` | related satellite domain; distinct compounds |
| `dimid` (lower) | `EntityElevatorCapsule`, `ItemStationChip` | distinct from `dimId`; **casing SSOT smell** (see risks) |
| `pos` | `EntityRocket` (entity), `SpaceStationObject` (list sub-tags), `TileGuidanceComputer`, `TileMicrowaveReciever` | no clash |
| `name` | `DimensionProperties` **owns top-level** `name` in the CMP-2 station compound; `StellarBody`, `ItemIdWithName`/`ItemStationChip` (stack), `TileLandingPad`/`SpaceStationObject` (list sub-tags `pos.getName()` at `:750`, not top-level), `NBTHelper` (generic) | no clash — station's own `name` writes are list-scoped, not top-level |
| `mode` | `ItemJetpack` (stack), `TileWirelessTransceiver`, `TileOrbitalLaserDrill` | no clash |
| `data` | `SatelliteData` vs `TileSatelliteTerminal` — nested `DataStorage`, distinct scopes, no clash (`satellite.md`); `EntityRocket`, `DataBlockProvider` | no clash |
| `weight` | `SatelliteProperties`/`SatelliteRegistry` (props), `ItemSatelliteIdentificationChip` (stack) | no clash |
| `mass` | `StatsRocket`, `StorageChunk` — the key is `mass` because tier-1 is denominated in kilograms | no clash |
| `type` | `PacketAtmSync` (wire, not saved), `SpaceObjectManager` (saved), `DataBlockProvider` | mixed wire/save; distinct compounds |

## Consolidated inventory by owning compound

Each block = one owner (one private compound, or CMP-1/2/3 as noted). Keys are contractual
unless the owning subsystem doc tags them `tunable`/balance. Read-default and migration
notes are called out only where they carry risk (full risk table below).

**dimension-planets** — `DimensionProperties` (own compound, also merged into station via CMP-2):
`name`, `fillBlock`, `fillBlockMeta`, `oceanBlock`, `oceanBlockMeta`, `air` (the atmosphere's
composition), `originalAtmosphereDensity`, `avgTemperature`, `gravitationalMultiplier`, `orbitTheta`,
`orbitPhi`, `orbitalDist`, `baseOrbitTheta`, `rotationalPeriod`, `rotationalPhi`, `sealevel`,
`skyColor`, `fogColor`, `sunriseSunsetColors`, `colorOverride`, `starId`, `parentPlanet`,
`childrenPlanets`, `satallites`, `isNative`, `isGasGiant`, `isRetrograde`,
`hasRings`, `hasRivers`, `ringColor`, `ringAngle`, `genType`, `biomes`, `biomeNames`,
`biomeIds`(via NBTHelper), `craterBiomes`, `craterWeights`, `craterOres`, `craterFrequencyMultiplier`,
`geodeOres`, `geodeFrequencyMultiplier`, `volcanoFrequencyMultiplier`, `laserDrillOres`,
`laserDrillOresRaw`, `canGenerateCraters`/`Geodes`/`Caves`/`Volcanos`/`Structures`,
`acidicRain`, `artifacts`, `beaconLocations`, `weights`, `icon`, `peakInsolationMultiplier`,
`peakInsolationMultiplierWithoutAtmosphere`, `target_sea_level`(dead), `rainMarker`/`thunderMarker`,
`rainStartLength`/`rainProlongationLength`/`thunderStartLength`/`thunderProlongationLength`,
`locallyKnownPlanets` (int array, **written only when non-empty** - what THIS body has learned,
additive over the global set).
`DimensionManager` (**CMP-3** global): `spaceObjects`, `starSystems`, `dimList`, `stat`,
`hasReachedMoon`, `hasReachedWarp`, `nextSatelliteId`, `prevVersion`.
`TerraformingRecord` (`WorldSavedData` `stellurgy_terraforming`, per planet world):
`fullyGeneratedChunks`, `fullyBiomeChangedChunks`, `terraformingProtectedBlocks` — not part of
`dimList`.

**space-stations** — `SpaceStationObject`/`SpaceObjectBase` (**CMP-2** compound, shared with
DimensionProperties above): `id`, `posX`, `posY`, `posZ`, `altitude`, `spawnX`/`Y`/`Z`,
`rotationX`/`Y`/`Z`, `deltaRotationX`/`Y`/`Z`, `launchposX`/`launchposY`, `isAnchored`,
`created`, `occupied`, `autoLand`, `direction`, `destinationDimId`, `fuel`, `orbitalDistance`,
`targetOrbitalDistance`, `targetGravity`, `targetRotationX`/`Y`/`Z`, `knownPlanets`,
`transitionEta`, `numPlayers`, `spawnPositions`, `warpCorePositions`, `dockingPositons`.
`SpaceObjectManager` (**CMP-3**): `spaceContents`, `expireTime`, `nextInt`, `numPlayers`,
`nextStationTransitionTick`. `TileOrbitalRegistry`: `id`, `tab`, `anchored`, `buttonSat`,
`buttonStation`, `freePads`, `generatesData`, `mobile`, `orbitingBodyId`, `powerGen`,
`registryKey`, `satCache`/`stationCache`, `satDimId`, `scanNonce`, `selectedSatId`,
`selectedStationId`, `lastSatButton`/`lastStationButton`, `maxData`.

**rocket-entity** — `EntityRocket` (entity compound): `data`, `pos`, `loc`, `infrastructure`,
`satallite`, `orbit`, `flight`, `selection`, `destinationDimId`, `lastDimensionFrom`,
`inSpaceFlight`, `motionX`/`Y`/`Z`, `up`/`down`/`left`/`right`, `flightMode`, `DataType`,
`stellurgyRocketTransferGrace`, and Free-Flight v2 set (`flightAssistOn`, `ffHasLeftGround`,
`ffLiftoffTargetY`, `ffQuatW`/`X`/`Y`/`Z`, `ffFwd`/`ffStrafe`/`ffVert`/`ffPitch`/`ffYaw`/
`ffRoll`/`ffBrake`/`ffCut`, `faSetpointFwd`/`Right`/`Up`, `rcs_mode`, `rcs_mode_cnt`).
`EntityStationDeployedRocket`: `gas`, `fwd`, `launchX`/`Y`/`Z`, `AlaunchX`/`Y`/`Z`,
`plannedHarvestMb`(dead). `EntityElevatorCapsule`: `x`/`y`/`z`, `dimid`, `dstDimid`, `srcDimid`,
`srcLoc`/`dstLoc`, `motionDir`. `EntityItemAbducted`: `Item`, `Age`, `Lifespan`.
`TileGuidanceComputer`: `id`, `pos`, `landingx`/`y`/`z`, `destDimId`, `stationMapping`.
`EntityHoverCraft`: `up`/`down`.

**rocket-assembly** — `TileRocketAssemblingMachine`: `id`, `status`, `building`, `bb`, `bytes`(via),
`minX`/`Y`/`Z`, `maxX`/`Y`/`Z`, `pwr`, `tik`, `scanTime`, `scanTotalBlocks`,
`infrastructureLocations`. `StorageChunk` (own compound + per-tile sub-tags): `id`, `mass` (kg),
`xSize`/`ySize`/`zSize`, `idList`, `metaList`, `tiles`, `hasServiceMonitor`, `x`/`y`/`z` (cells).

**infrastructure-tiles** — fueling/loader/monitor tiles: `state`, `redstoneState`,
`inputRedstoneState`, `inputstate`, `bytes`, `masterPos`/`masterX`/`Y`/`Z`, `status`,
`statuses`, `chipEjected`, `was_powered`, `abortReason`, `missionID`, `missionDimId`,
`lastFuelCap`/`lastOxCap`, `lastStatusTick`, `uiStatus`, `repairInv`, `initialPartToRepairCount`,
`assemblerPoses`/`partsProcessing`/`statesProcessing`.

**multiblock-machines** — energy tiles: `amtPwr`, `canRender`, `numPanels`, `powerMadeLastTick`,
`slot`, `items`, `savedHatchInv`. Gravity/railgun: `state`, `redstoneState`, `radius`, `bytes`,
`gravity`, `currGravity`, `progress`, `minTfrSize`/`minTransferSize`, `fireStatus`.
Astrobody: `researchingAtmosphere`/`Distance`/`Mass`, `atmosphereProgress`/`distanceProgress`/
`massProgress`. Observatory: `io`, `lb`/`ls`/`pb`/`ps`, `button`/`lastButton`, `printedButtons`,
`printedSetSeed`, `lastSeed`/`lastType`, `isOpen`/`openProgress`, `viewableDist`, `en`;
region-survey half — `regionScan` (a nested `RegionScan`: `distCells`/`stride`/`start`/
`stepDeadline`/`cellsDone`/`cellsPerStep`/`ticksPerStep`, plus EITHER `cone` — a nested `ConeWalk`
of `apex`/`dx`/`dy`/`dz`/`halfAngle`/`reachCells`/`stride` — OR the box radar's `min`/`max`; the two
shapes are exclusive and which key is present is what says which one it is), `scanDirection`,
`scanDistance` (in STEPS of one star's territory), `scanPassive`, `scanWholeSystem` (whether a
detection is followed to the system's bodies or only its address is written; ABSENT reads as true),
`lastScanDiscoveries`, `lastScanObscured` (how many of the last survey's looks a
cloud stood in the way of — the crystal still gained their coordinates, and the operator is told
which), plus network-only `scanStepLy` (what
one step is worth in light years — derived server-side and synced, never persisted to disk).
`distCells`+`stride`: a survey's reach is a length and its stride is a territory, and a sweep in
flight must come back looking at the same region. `cone` persists because the survey is a POINTING
rather than a box: a reloaded survey that had forgotten where it was aimed FROM would quietly
resume over other sky.
Orbital-laser-drill: `currentX`/`Z`, `newX`/`Z`, `laserX`/`Z`, `CenterX`/`Y`, `prevDir`,
`isRunning`, `jammed`, `numSteps`, `terraformingstatus`, `voidCobble`. Terraformer/elevator:
`selected`, `oofluid`, `tether`, `dstDimId`, `dstPos`, `mode`, `radius`. Chemical reactor:
`display`, `Lore`.

**atmosphere-oxygen** — `TileOxygenVent`: `state`, `redstoneState`, `isSealed`, `trace`,
`allowtrace`, `airState` (compound of `n2`/`o2`/`co2`/`airK`, the zone's gas contents and its
TEMPERATURE in thousandths of a kelvin — the zone's shape is rebuilt from the world on load but its
contents are not derivable from blocks; absent `airState` = fresh sea-level air, and an absent `airK` inside one reads as AMBIENT rather than
as zero).
`TileAirRecirculator`: `carbonBuffer` (a LONG — sub-item carbon owed; persisted so an unload neither rounds
the machine's output up nor drops it), `opTicks` (ticks since it last acted — its own cadence, not
a world-clock modulo).
`TileHeatChiller`: `heatStored` (the machine's own share of its hot loop's energy — a chiller is a lump
of metal in contact with the coolant, so it carries thermal mass without being a network member).
`TileHeatLoopBlock` (pipe, accumulator, radiator): `heatStored` (`long`, this block's share of its loop's
energy — a loop has no durable name to be saved under, so the energy is written down per block and
a split divides it correctly for free).
`TileHeatRadiator` adds `heatSinkClosed` (`boolean`, whether this cell has been shut for silent
running — a ship left dark comes back dark, because a state the pilot chose and is paying thermal
mass for may not be undone by a chunk reload).
**Zone air** (`AirState`, written by `TileOxygenVent` under `airState`): `gases` — an NBTTagCompound of SUBSTANCE NAME → partial pressure, written as a LONG in billionths of an atmosphere — plus `airK` (temperature, milli-kelvin). A gas the running game does not know is DROPPED on read rather than mapped to a substitute — a pack that removed a substance did not mean "replace it". Gas names are registry ids and are permanent (C3).
`TileGasSeparator`: `combining` (direction of the mode toggle), `opTicks`. `TileAtmosphereDetector`: `assertion` (the STATEMENT about the air it watches, by enum name so that inserting one in the middle cannot re-point every detector already placed; an unknown one reads as "is it breathable", the harmless default a fresh detector starts on); a statement rather than an atmosphere name, so a player's wiring is not a function of the mod's vocabulary. `ItemSpaceArmor`/`ItemSpaceChest`
(stack, **CMP-1**): `display`, `color`, `air` (dead).

**satellite** — `SatelliteData`: `data`, `maxData`, `collectionMultiplier`(dead), `lastActionTime`.
`SatelliteBiomeChanger`: `biomeId`, `biomeList`, `posList`. `SatelliteWeatherController`/
`ItemWeatherController` (**CMP-1** stack): `floodlevel`, `mode_id`, `last_mode_id`.
`SatelliteMicrowaveEnergy`: `teir`. `TileTerraformingTerminal`: `blockpertick`, `powergen`,
`sat_power_per_tick`, `randomblocks_per_tick`, `was_enabled_last_tick`.

**api-public** — `StatsRocket` (`rocketStats` sub-tag): `thrust` (newtons), `mass` (kg), `drillingPower`,
`passengerSeats`, `dynStats`, `engineLoc`, `workingFluid`, `fuelFluid`, `oxidizerFluid`,
`playerXPos`/`YPos`/`ZPos`, and the full `fuel*`/`fuelBaseRate*`/`fuelRate*`/`fuelCapacity*`
family per propellant type. `SatelliteProperties`/`SatelliteRegistry`: `satId`, `dataType`,
`maxData`, `weight`, `powerGeneration`, `powerStorage`, `satelliteId`, `satelliteName`.
`DataStorage`: `Data`, `maxData`, `DataType`, `locked`. `SatelliteBase`: `dataType`, `item`,
`properties`, `dimId`. `StellarBody`: `id`, `name`, `posX`/`Y`/`Z`, `size`, `temperature`,
`seperation`, `diskAngle`, `isBlackHole`, `subStars`.

**items** — `ItemStationChip` (stack): `name`, `dimid`, `x`/`y`/`z` (float).
`ItemSatelliteIdentificationChip` (stack): `dimId`, `name`, `satelliteId`, `satelliteName`,
`satId`, `weight`. `ItemJetpack` (stack): `mode`, `modeSwitch`, `enabled`, `height`.
`ItemSatellite`: `inv`. `ItemBiomeChanger`: `biome`. `ItemData`/`ItemPressureTank` (lang only).
`ItemOreScanner`: `id`. `ItemPackedStructure`: `chunk`. `ItemWeatherController`: `floodlevel`,
`last_mode_id`, `mode_id`. `ItemIdWithName`: `name`.

**mission** — `MissionResourceCollection`: `loc`, `duration`, `persist`, `rocketStats`,
`rocketStorage`, `startDimid`, `launchDim`, `startWorldTime`, `launchPosX`/`Y`/`Z`,
`plannedHarvestMb`. `MissionGasCollection`: `gas`, `plannedHarvestMb`. `MissionOreMining`:
`asteroidType`, `asteroidUUID`.

**world-gen** — `PlanetWeatherState`/`Manager`/`SavedData` (**CMP-3**): `raining`, `thundering`,
`rainTime`, `thunderTime`, `cleanWeatherTime`, `clearWeatherTime`, `worldTime`, `worldTotalTime`,
`dim`, `dimensions`, `doWeatherCycle`.

**wirelessdata** — `WirelessNetworkSavedData` (**CMP-3**): `nextNetworkId`, `redirects`.
`TileWirelessTransceiver`: `state`, `mode`, `enabled`, `priority`, `networkID`.

**util-core** — `ItemAirUtils` (**CMP-1** stack): `air`. `SpacePosition`: `x`/`y`/`z`, `pitch`/
`yaw`/`roll`, `star`, `world`, `spacePosition`, `isInInterplanetarySpace`. `NBTHelper` (generic
helpers): `name`, `meta`, `minX`/`Y`/`Z`, `maxX`/`Y`/`Z`. `NBTStorableListList`: `list`, `dim`, `loc`.

**misc / TE-misc** — `TileForceFieldProjector`: `ext`. `TilePump`: `tank`. `TileWearable`:
`stage`, `maxStage`, `transitionProb`. `TileStationAssembler`: `storedID`. `TileDockingPort`:
`myId`, `targetId`. `TileSpaceElevator`: `dstDimId`, `dstPos`, `tether`. `TilePlanetSelector`:
`visiblePlanets`. `TileHolographicPlanetSelector`: `state`, `scale`. `Stellurgy` root:
`ResetOnlyOnce`, `resetPlanetsFromXML`.

**integration / non-owned** — `waila/*` and `theoneprobe/*` read foreign compounds
(`stellurgy_data`, `stellurgy_transceiver`, `fuelType`, `dest`, `landing`, `stack`, `linked`, `extracting`,
`networkId`, `nbt` fields) for HUD only — no independent persistence.

## Collisions & risks

### Genuine same-compound key overloads

| key(s) | compound | owners | verdict |
|--------|----------|--------|---------|
| `dataType` | satellite props / base (CMP adjacency) | `SatelliteBase` (type discriminator) **and** `SatelliteProperties` (type string); `DataStorage` writes capital `DataType` for its enum | **overload** — same lowercase key, two meanings in adjacent satellite compounds; capital/lowercase split is a rename trap |
| `status` vs `statuses` | guidance hatch | `TileGuidanceComputerAccessHatch` persists the 4-bit auto-eject mask as `statuses` but transmits it on the wire as `status` | naming SSOT hazard, harmless today (distinct paths) |
| `air` | space-suit chest ItemStack (CMP-1) | `ItemAirUtils` (util-core, **live**) vs `ItemSpaceChest` (armor, **commented-out/dead**) | not a live clash — only one writer live; the dead side is the unused `ItemSpaceChest` path |
| `dimId` vs `dimid` | satellite/elevator/chip stacks | casing differs by owner (`SatelliteBase`/`ItemSatelliteIdentificationChip` use `dimId`; `EntityElevatorCapsule`/`ItemStationChip` use `dimid`) | distinct compounds → no data clash, but a cross-read would silently miss; SSOT smell |
| `satId` vs `satelliteId` | satellite domain | `SatelliteProperties`/`SatelliteRegistry` write `satId`; chip/registry also use `satelliteId` | two keys for one concept across related owners — SSOT smell, no compound clash |

**No new cross-subsystem collision found.** The one structurally dangerous merge — the CMP-2
`SpaceStationObject.writeToNBT → properties.writeToNBT(nbt)` co-write — was checked
key-by-key: `DimensionProperties` owns top-level `name`; the station's own `name`
writes are list-sub-tag scoped, and no station key (`posX`, `altitude`, `rotationX…`)
collides with a `DimensionProperties` key (`orbitalDistance`≠`orbitalDist`, `rotationX`≠
`rotationalPhi`). Delegation, single owner per key. Safe but wide.

### Keys read with no default / no `hasKey` guard

| key | owner | risk |
|-----|-------|------|
| `redstoneState` | `TileOxygenVent` | `getByte` defaults 0 → `RedstoneState.values()[0]`; enum reorder silently remaps saved state |
| `diskAngle` | `StellarBody` | no `hasKey`; legacy stars load `0f` instead of ctor default `70` |
| `networkID` | `TileWirelessTransceiver` | `getInteger` defaults 0, not the `-1` unlinked sentinel → missing key treated as linked network 0 |
| `rocketStats` | `MissionResourceCollection` | read with no default/guard (vs guarded `rocketStorage`); malformed compound skips abandon path |
| `status` (ErrorCodes) | `TileRocketAssemblingMachine` | missing key reads back ordinal 0 = `SUCCESS`; enum reorder reassigns verdicts |
| `craterBiomes`/`craterWeights` | `DimensionProperties` | `getBiomeById` no null guard; integer biome-id drift across modpacks |

### Keys written but never (effectively) read

| key(s) | owner | note |
|--------|-------|------|
| `researchingAtmosphere`/`Distance`/`Mass` | `TileAstrobodyDataProcessor` | `readFromNBT` omits them (only `readNetworkData` reads) → research toggles reset on restart |
| `peakInsolationMultiplier`(+`WithoutAtmosphere`) | `DimensionProperties` | written+read but both getters recompute+overwrite → dead-on-read |
| `target_sea_level` | `DimensionProperties` | both read and write fully commented — a dead key |
| `plannedHarvestMb` | `EntityStationDeployedRocket` | `readMissionPersistentNBT` never reads it (harmless — entity `setDead()`s) |
| `collectionMultiplier` | `SatelliteData` | read value immediately overwritten by recompute |
| `pitch`/`roll` | `SpacePosition` | `readFromNBT` writes them back into the tag it is reading (dead writes) |
| `air` | `ItemSpaceChest` | all `air` int logic commented out; a test still pins an `air` key production never writes |

### Migration / drift hazards (contractual keys, no version gate)

- **Enum-ordinal persistence, no migration:** `status`=`ErrorCodes.ordinal()`,
  `redstoneState`=`RedstoneState.values()[ordinal]`. Reordering either enum
  silently reinterprets every saved value.
- **Integer biome-id persistence:** `craterBiomes`/`craterWeights` persist raw biome ids —
  the exact drift hazard the `biomeNames` encoding avoids.
- **Float in persisted physics** (Free-Flight v2 watch-list): `StorageChunk` blob `mass`
  (low — recomputed on scan), `ItemStationChip` landing `x`/`y`/`z`,
  `SpaceStationObject.orbitalDistance` float.
- **Keys the coverage checker may not list:** `assemblerPoses`/`partsProcessing`/`statesProcessing`
  (`TileRocketServiceStation`) are written via `NBTHelper.writeCollection`, which a plain
  `getTag`/`setTag` scan does not see. They belong to infrastructure-tiles.

The CMP-2 station/properties `name` co-write was verified safe at `SpaceStationObject.java`
vs `DimensionProperties.java`.

## Navigation keys

All in private compounds — no collision with the tables above.

| key | compound | meaning |
|---|---|---|
| `navAddresses` | memory-crystal item stack | `NBTTagList` of address entries; each entry = the `galacticCoord` sub-tag + `name` + `kind` + `detail` (InfoTier name) + `observedTick`. The item is traded between players, so this is a same-version wire contract as well as a save one. |
| `navSeeded` | memory-crystal item stack | the crystal has already been given its starter addresses; a deliberately blanked crystal must stay blank |
| `navTarget` / `navHasTarget` | `TileNavigationComputer` | the coordinate the ship is aimed at (absent flag = no target) |
| `afcOffset` | `TileNavigationComputer` | RELATIVE offset to the ship's flight computer (a long-packed `BlockPos`); relative because a ship's blocks move as a body |
| `syncChannel` | `TileNavigationComputer` | which channel this computer syncs its crystal on; 0 = none |

## Ship thermal keys

- **`stellurgyHeatCharge`** (`long`, ITEM stack NBT) — how much heat a charged slug is carrying, written by
  `TileHeatDump`. On the STACK rather than the machine because the energy travels with the matter:
  the slug is ejected, may be picked up, and is a physical object that still holds what it took.
## Universe schema stamp

These keys live in the root `UniverseRegistry` compound (`stellurgy_universe.dat`). They are the
world-model STAMP, and the one place in this contract where an absent key is a state with a name of its
own rather than a default: `UNSTAMPED` (`-1`, `UniverseRegistry.java:71`) means "no stamp", and it is
resolved once at `populate` rather than guessed at read time.

| key | compound | meaning |
|---|---|---|
| `schemaVersion` | `UniverseRegistry` root | which released world model generated this save (`UniverseSchema.version()`). **ZERO IS A REAL VERSION** — the shipped alpha — so this key is read through `hasKey`, never through `getInteger`'s absent-value default, and the "no stamp" sentinel is `UNSTAMPED = -1`, a value no version can take. Reading the default would report every stampless save as alpha-generated and skip the adoption a fresh world is owed. **Deliberately NOT `version`**, which is this compound's tag LAYOUT (`NBT_VERSION`, `UniverseRegistry.java:63`): a save whose layout is a version behind still describes the same sky, a save whose schema is a version behind describes a different one. The value is an identifier — never renumbered, never reused. A stamp this build does not carry refuses the load |
| `universeLawsFingerprint` | `UniverseRegistry` root | 16 hex over `UniverseRegistry.lawsFingerprintOf(schema.laws())` — what the world's OWN schema version measured when the world was made, taken by running the conversions rather than by listing constants (so it covers any implementation and catches an internal constant that moved). Kept APART from `galaxyConfigFingerprint`: that one is the pack author's knobs, this one is the version's laws, and a shared stamp would blame the wrong party. A mismatch means a RELEASED version was edited in place — a broken build, not a player's situation, and the one thing `upgrade` may not accept. Empty reads as "nothing to compare" rather than as a mismatch |
| `universeDerivationFingerprint` | `UniverseRegistry` root | 16 hex over `UniverseRegistry.derivationFingerprintOf(schema.bodyDerivation())` — what this world's schema answered about BODIES when it was made, measured by running fixed seeds, cells and one sun-like star through the real `IBodyDerivation` and hashing the profiles. The sibling of `universeLawsFingerprint`, which measures `laws()` alone and is blind to a re-derivation of the planets, so this one is what guards a released version against an in-place edit of its bodies. **Empty means NEVER MEASURED**, not "mismatch": a save without the key is adopted rather than refused |
| `universeUpgradeArmed` | `UniverseRegistry` root | an operator's ONE-SHOT permission for the next load to accept a changed `galaxyConfigFingerprint`. It is persisted rather than held in memory because the load it authorises is in a different session by construction — a changed configuration refuses the boot, so the permission must be given by the session before it. Consumed only when the fingerprint actually differs; an unchanged boot leaves it standing |
| `galaxyConfigFingerprint` | `UniverseRegistry` root | 16 hex chars of SHA-256 over the effective `GalaxyGenConfig` (`GalaxyGenConfig.fingerprint()`), or over `"none"` for an authored-anchors-only universe (a pack's `<galaxyGen procedural="false"/>`; no `<galaxyGen>` at all is the shipped configuration's fingerprint, not `"none"`). Stable across JVMs by construction: no `hashCode` anywhere, doubles via `doubleToLongBits`, lists in declared order. A difference at load refuses it — a retuned knob is not balance, it is a different universe, and every unpinned system moves with it |

## Planet generation keys

All in compounds this doc already covers; no new collision. **Every one is written only when
non-default**, which keeps a catalogue that does not use them byte-identical (see INV-DIM-19).

| key | compound | meaning |
|---|---|---|
| `mass` / `radius` | `DimensionProperties` (per-dim; also CMP-2 into a station) | the body's bulk in Earth units. PRIMARY — surface gravity is derived from them (MECH-DIM-19) |
| `gravityAuthored` | `DimensionProperties` | a gravity was STATED, so the mass/radius derivation must not touch it. Absent = derived. The single bit behind INV-DIM-18 |
| `tidallyLocked` | `DimensionProperties` | this world keeps one face to its star: no day/night cycle (MECH-DIM-20). An explicit flag because `rotationalPeriod = 0` maps back to a full day and so cannot say it |
| `metallicity` | `DimensionProperties` | the parent star's metal content relative to Sol; scales the metal fraction of the world's ore palette (MECH-DIM-21) |
| `orbitalDist` | `SystemBody` sub-tag (inside `UniverseRegistry`'s pinned-system store) | how far a universe-layer body orbits its primary, in Stellurgy distance units (100 km), as a LONG (`SystemBody, 371`, like the `DimensionProperties` key and `StellarBody`'s `companionOrbit`). Distinct compound from the `DimensionProperties` key of the same name — a pinned body is not a dimension. It travels WITH the body because a pinned system's worlds are re-derived from it, and a cell is too coarse to recover it from |
| `primaryKind` | pinned-system entry (`UniverseRegistry`'s `pinnedSystems` list) | the `SystemBodyKind` of what stands at this system's anchor. **Written only when it is NOT `STAR`**, so a stellar system's snapshot carries no key, and an absent key reads back as `STAR`. It is stored rather than inferred from a zero `temperature`: a cold star and a system with no star at all are different facts, and telling them apart by their arithmetic is exactly the confusion the kind exists to end. An unrecognised value reads back as `STAR` rather than throwing |
| `radiusEarths` | `SystemBody` sub-tag (inside `UniverseRegistry`'s pinned-system store) | how big the body is, in EARTH radii. Written only when stated, so a body with no size of its own (a belt, a station slot) carries no key at all and reads back as `RADIUS_UNKNOWN`. It travels with the body for the same reason `orbitalDist` does: a procedural world has no dimension to look it up in, and the render feed reaches a client with no registry to ask — without it the sky sized every body by DISTANCE alone |

## Projectile substrate keys

One new per-world `WorldSavedData`, `stellurgyShots` (`ShotRegistry`) — a private compound, no
collision with the tables above. Its `shots` list holds one compound per shot in flight.

| key | compound | meaning |
|---|---|---|
| `nextId` | `stellurgyShots` | the world's shot-id counter. On load it is raised past the highest id actually present, so a save whose counter is behind its own contents cannot hand out an id already in the air |
| `shots` | `stellurgyShots` | `NBTTagList` of shot compounds (below) |
| `id` | one shot | identity within this world |
| `posX`/`posY`/`posZ`, `velX`/`velY`/`velZ` | one shot | position, and velocity in blocks per **tick**, in the frame `hull` names — WORLD when it is absent |
| `hull` | one shot | the ship whose material this round is inside, when it is inside one (absent = free flight). It is what the coordinates above MEAN: without it a reloaded save reads a subspace triple as a world one and the round reappears in the shipyard |
| `radius`, `mass` | one shot | the body, for the layers that draw it and compute momentum; the crossing test is a ray |
| `kind` | one shot | `ImpactKind` NAME, not its ordinal — a save written by a build that knew a kind this one does not falls back to `KINETIC` rather than losing the shot |
| `energy` | one shot | what it is worth on arrival; a shell's short pay lowers it |
| `age` / `lifetime` | one shot | ticks flown, and the declared limit |
| `gravity` | one shot | the declared environment: downward acceleration in blocks/tick², `0` in vacuum |
| `owner` / `faction` | one shot | attribution tokens the substrate never reads (absent = none) |
| `guidance` | one shot | reserved; nothing steers today, but it round-trips so the layer that eventually does has somewhere to put its target |
| `nextImpactId` | `stellurgyShots` | the next identity an impact declared in this world will be remembered by. A registry-level counter, beside `nextId`, NOT a per-shot field: an identity built from a shot's own id plus a bounded counter overflows into the id field, and the round that inherits those identities has every impact refused as a duplicate — it crosses a wall spending nothing. Persisted so a restart does not re-mint identities the dedup memory is still holding |

**Not persisted, deliberately**: the recent end reasons (`ShotRegistry.endings`). They answer a
question asked seconds later, not world state, and a reason surviving a restart would be a claim about
a flight that no longer exists.

## `TileTurret` — a gun's own state

| key | meaning |
|---|---|
| `mount` | compound: `yaw`, `pitch`, `cmdYaw`, `cmdPitch`, `commanded`, `drive` (a `TurretDriveState` ORDINAL) |
| `energy` / `energyMax` | the FE buffer's contents and its size. The size is saved because it is derived from the build, and a reload that resized it to the default would silently dump a big gun's reserve |
| `heat` / `cooldown` | how hot it is, and ticks left before the next round |
| `shots` | how many rounds this gun has fired; diagnostics, and what the standalone tests read |
| `hasTarget` + `targetX/Y/Z` | the gun's OWN target (the linker's), absent when it has none. The network's target is not saved here — it belongs to the network, not to the gun |
| `faction` / `owner` | attribution tokens stamped onto every round fired (absent = none) |
| `targetEntity` | the entity this gun follows on its own (absent = none). The network's entity target is the network's, not saved here |
| `accessCode` | this gun's own credential, used when it is on no network or the network has none |
| `manual` | whether the gun is under a hand: no target is assigned to it and firing is explicit |

**The derived spec is NOT saved.** It is re-walked from the blocks on the tile's first tick after
load, so a save that recorded it could only ever disagree with the build it describes. A gun therefore
reads as un-built for exactly one tick after a chunk loads — the alternative is a cached number that
can be wrong for as long as nobody notices.

**`drive` is an ordinal.** `TurretDriveState` may be appended to; reordering it re-labels every saved
gun's failure. Same-version consistency only, per the 0.1.0 clean break.

## `TileFireControlSensor` — the battery's eyes

| key | meaning |
|---|---|
| `mode` | a `SensorMode` ORDINAL: listening or illuminating. Same append-only rule as `drive` above |
| `energy` | the FE buffer's contents. The SIZE is a constant here, unlike a gun's, because a sensor is not built out of parts |
| `accessCode` | this sensor's own credential, used when it is on no network or the network has none. The list-level friend/foe screen reads it |

**Contacts are NOT saved.** A track is a statement about where something was a moment ago; a reloaded
world that came back holding a contact would be asserting a fact about an entity that may have died,
logged out or moved a chunk away while the chunk was unloaded. A sensor comes back seeing nothing and
sweeps within one interval, which is the only honest state to reload into.

## Player-binding and moon-zone keys

| Key | Where | What |
|---|---|---|
| capability `stellurgy:player_bindings` | player (Forge capability) | `IPlayerBindings`, serialized by `PlayerBindings.serializeNBT` (`player/PlayerBindings.java:96-103`) and copied across a death on `PlayerEvent.Clone` (`CapabilityPlayerBindings.java:92`) `[V]`. Holds the two durable per-player facts below; C13 PRES-10/11 decide which facts those are. |
| `stellurgyShipAboard` | inside the capability's compound | the aboard record, written through `ShipAboardTag.write` (`space/ShipAboardTag.java`) — NOT under ForgeData; a record under ForgeData is not read `[V]`. |
| `graceUntil` | inside the capability's compound | a PLAYER's rocket-transfer suit grace deadline (`PlayerBindings.java:35`); written only when non-zero `[V]`. `stellurgyRocketTransferGrace` (the rocket-entity row above) survives only on NON-player entities' ForgeData (`RocketTransferGrace.java:39`) `[V]`. |
| `zone`, `cw` | `galacticCoord` sub-tag | the zone a coordinate is counted in and that zone's cell width in blocks; written only for a zoned coordinate (a moon's cell), so a galactic coordinate's tag is byte-identical to before; absent `cw` reads back as `WIDTH_UNKNOWN` (`space/GalacticCoord.java`) `[V]`. |
| `massEarths` | `SystemBody` sub-tag (pinned-system store) | the body's mass in Earth masses; written only when known, absent reads as `MASS_UNKNOWN` (`universe/SystemBody.java:354-373`) `[V]`. Every zone in a system is sized against its primary's mass. |

## Ship flight model keys

| Key | Where | What |
|---|---|---|
| `storedMomentumX`, `storedMomentumY`, `storedMomentumZ` | `TileReactionWheel` | N·m·s the wheel has given the hull about each ship axis (`tile/TileReactionWheel.java:37-49`) `[V]`. Durable (C10 STAT-3): a full wheel stays full across a save, an unload and a crossing, or a relog would be a free desaturation. Absent reads as 0. The flight model, recipes and readout are derived and have NO key. |

## Weapon-order keys

| Key | Where | What |
|---|---|---|
| `weaponOrders` | weapon console TE (`TileWeaponConsole#writeToNBT` / `#readFromNBT`) | compound: the battery's orders, written by `WeaponNetworkState#writeOrders`, read by `#adoptOrders` `[V]` |
| `accessCode`, `holdFire`, `hullAllegiance` | inside `weaponOrders` | string; boolean; `HullAllegianceRule` enum NAME (an unknown name logs a warning and reads as the default rule) `[V]` |
| `targetX` / `targetY` / `targetZ` | inside `weaponOrders` | doubles, only when a point target is set `[V]` |
| `targetEntityMost` / `targetEntityLeast` | inside `weaponOrders` | a creature target's UUID (`setUniqueId("targetEntity")`) `[V]` |
| `targetShip` | inside `weaponOrders` | a ship target's durable id `[V]` |
| `weaponOrdersStamp` | weapon console TE | long: `World#getTotalWorldTime` of the orders' last write; the freshest console seeds a rebuilt network (weapons MECH-GUN-43) `[V]` |

