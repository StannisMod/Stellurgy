---
id: mission
owns: [mission/]
entrypoints: [MissionResourceCollection#tickEntity, MissionResourceCollection#onMissionComplete, MissionOreMining#onMissionComplete, MissionGasCollection#onMissionComplete]
depends-on: [rocket-entity, rocket-assembly, dimension-planets, satellite, api-public]
depended-by: [rocket-entity, infrastructure-tiles, network-wire]
contracts: [C1, C3, C4]
confidence: high
---

## Purpose

An in-progress resource-gathering mission (asteroid ore mining, atmospheric gas
collection) modelled as a headless satellite. A launched rocket is *consumed* into a
mission object that lives in the destination planet's satellite list, counts down world
ticks, and on completion re-spawns the rocket with harvested cargo and re-links the
ground infrastructure that fed it.

## Responsibility boundary

Owns: the mission lifecycle (construct-from-rocket → tick countdown → complete → respawn
rocket), the mission NBT save format, and the two concrete harvest recipes (ore, gas).
Does NOT own: the rocket entity or its `StorageChunk`/`StatsRocket` (rocket-entity /
rocket-assembly), the satellite ID allocator and satellite list (dimension-planets /
satellite), the `PacketSatellite` sync wire (network-wire, sent by the rocket at
handoff), the monitor-station GUI or its `missionID`/`missionDimId` keys
(infrastructure-tiles), and the `plannedHarvestMb`/`intakePower` values which the rocket
writes into the mission's persistent NBT at launch.

## Key types

| class | role |
|-------|------|
| `MissionResourceCollection` | abstract base — a `SatelliteBase implements IMission`; holds rocket snapshot, countdown, infrastructure links, NBT round-trip, `tickEntity` completion poll |
| `MissionOreMining` | asteroid-ore harvest: reads the asteroid chip, rolls harvest by chip data, inserts into rocket inventory tiles, re-spawns an `EntityRocket` |
| `MissionGasCollection` | atmospheric-gas harvest: fills rocket fluid tiles with a configured fluid, re-spawns an `EntityStationDeployedRocket` |

## Mechanics

- **MECH-MSN-01 — Rocket→mission handoff (construct).** The parameterised base ctor
  snapshots the rocket into the mission: it copies `storage` (`StorageChunk`), `stats`
  (`StatsRocket`), launch position `x/y/z`, `launchDimension`+`worldId` (both = the
  rocket's current dimension), allocates a satellite id, stamps `startWorldTime` from
  overworld (dim 0) total time, clamps `duration` to ≥ 1 tick, and calls
  `entity.writeMissionPersistentNBT(...)` into `missionPersistantNBT`. Infrastructure
  tiles are recorded as `HashedBlockPosition`.
  `MissionResourceCollection.java:49-74`. Ore start:
  `EntityRocket.java:2308-2318`; gas start: `EntityStationDeployedRocket.java:492-504`.
- **MECH-MSN-02 — Registration & sync.** The concrete mission is registered under a
  registry name (`asteroidMiner`/`gasMining`), added to the effective destination
  dimension's satellite list, broadcast with `PacketSatellite`, and each connected
  infrastructure has `linkMission(mission)` called. Registration keys:
  `Stellurgy.java:362-363`. Ore path `EntityRocket.java:2309-2318`.
- **MECH-MSN-03 — Completion poll (tick).** `tickEntity` runs every
  `MISSION_COMPLETION_TICKS` (60, tunable) — not every tick. It aborts if
  `invalidRocketStorage`, if overworld is null/remote, or if the launch world is not
  loaded; otherwise when `getProgress ≥ 1` it calls `setDead()` then
  `onMissionComplete()`. `MissionResourceCollection.java:123-148`.
- **MECH-MSN-04 — Progress.** `getProgress = max((universalTime(0) − startWorldTime) /
  duration, 0)`, unbounded above 1; returns 1.0 when `duration ≤ 0`.
  `getTimeRemainingInSeconds` converts remaining ticks /20. Uses overworld (dim 0)
  universal time, not the mission's own dimension. `MissionResourceCollection.java:83-92`.
- **MECH-MSN-05 — Ore harvest & respawn.** If `drillingPower ≠ 0`: read the asteroid
  chip's DISTANCE/COMPOSITION/MASS/MAX data, roll `distance/max > rand` to decide any
  harvest, look up the `Asteroid` config type, split the harvest into max-stack chunks,
  and insert into the rocket's inventory tiles via `IItemHandler` (capability first, then
  `IInventory` fallback), voiding overflow. Then unconditionally reset guidance slot 0 to
  a blank asteroid chip, spawn a fresh `EntityRocket` in the launch dim at y=999 falling
  (`motionY=-1`), and re-link + `linkInfrastructure` each recorded tile.
  `MissionOreMining.java:95-190`.
- **MECH-MSN-06 — Gas harvest & respawn.** Reads `intakePower` from `rocketStats`; if
  `> 0` and a `gasFluid` is set, computes a `remaining` mB budget (planned value if
  `plannedHarvestMb` present, else infinite or `64000 × gasHarvestAmountMultiplier`) and
  fills the rocket's fluid tiles via `IFluidHandler` (simulate then commit). Then spawns
  an `EntityStationDeployedRocket`, deducts 1000 mB fuel (and oxidizer for bipropellant),
  replays `missionPersistantNBT`, offsets the spawn by 64 blocks in the rocket's forward
  direction, and re-links infrastructure. `MissionGasCollection.java:42-121`.
- **MECH-MSN-07 — Corrupt-storage self-abandon.** `readFromNBT` deserialises the
  `rocketStorage` compound inside try/catch; on failure it resets storage and calls
  `abandonInvalidMission`, which sets `invalidRocketStorage`, logs a structured error,
  unlinks all infrastructure, and `setDead()`s. `tickEntity` then removes the mission on
  its next run. `MissionResourceCollection.java:150-185, 248-257`.

## State & persistence

Every mission key is written by `MissionResourceCollection.writeToNBT` and read back symmetrically (`MissionResourceCollection.java:187-257`);
the key list is in `C1-nbt-persistence` (mission). Notes: `rocketStats` is read unguarded (always a fresh `StatsRocket`);
`rocketStorage` is written as an empty compound if `invalidRocketStorage`, and its read is wrapped in try/catch (MECH-MSN-07);
`persist` (the rocket-authored `missionPersistantNBT` bag) defaults to an empty compound and carries `plannedHarvestMb` (the gas
budget cap, MECH-MSN-06) and, for ore missions, `asteroidType` / `asteroidUUID` (`MissionOreMining.java:51,85-93`);
`MissionGasCollection` adds only `gas`, the fluid registry name (`MissionGasCollection.java:124-140`).

## Invariants

- **INV-MSN-01 [V][SYS]** The gas save key is the literal `"gas"` carrying the fluid registry
  name and the read resolves the fluid by it. `MissionGasCollection.java:127,138`. FOR: save format: mission survives restart (INV-MSN-03).
- **INV-MSN-02 [V][SYS]** `infrastructure` is a tag list of compounds each holding a 3-int
  `loc` array; read repopulates `infrastructureCoords` with matching coords, guard
  `coords.length >= 3`. `MissionResourceCollection.java:212-218,239-245`. (Was `[T]`, same
  deletion.) FOR: save format: mission survives restart (INV-MSN-03).
- **INV-MSN-03 [A][BEH]** A gas/ore mission survives a full server restart with its type and
  `duration` intact and not dead. `MissionPersistenceRestartTest.java:107-140,144-...`. Pinned by `MissionPersistenceRestartTest#gasMissionSurvivesServerRestart`, `MissionPersistenceRestartTest#oreMissionSurvivesServerRestart`.
- **INV-MSN-04 [A][BEH]** `getProgress` is linear in world time and unbounded above 1.0;
  completion fires exactly at progress ≥ 1. `MissionLifecyclePyramidTest.java:91-134`;
  code `MissionResourceCollection.java:85-86,144-147`. Pinned by `MissionLifecyclePyramidTest#progressAdvancesLinearlyWithWorldTime`, `MissionLifecyclePyramidTest#progressIsUnboundedAboveOne`, `MissionLifecyclePyramidTest#completionFiresAtProgressOne`.
- **INV-MSN-05 [A][BEH]** Ore completion always leaves a blank asteroid chip in guidance
  slot 0 on the respawned rocket. `MissionOreCompletionTest.java:81-95`; code
  `MissionOreMining.java:172-174`. Pinned by `MissionOreCompletionTest#oreCompletionAlwaysRefillsGuidanceWithBlankAsteroidChip`.
- **INV-MSN-06 [A][BEH]** Ore completion with `drillingPower == 0` performs no harvest (only
  the refill chip). `MissionOreCompletionTest.java:98-123`; code guard
  `MissionOreMining.java:97`. Pinned by `MissionOreCompletionTest#oreCompletionSkipsHarvestWhenDrillingPowerZero`.
- **INV-MSN-07 [A][BEH]** Gas completion fills fluid tiles only when `intakePower > 0`.
  `MissionGasCompletionTest.java:105-160`; code `MissionGasCollection.java:44-47`. Pinned by `MissionGasCompletionTest#gasCompletionDoesNotFillFluidWhenIntakePowerZero`, `MissionGasCompletionTest#gasCompletionFillsRocketFluidTilesWithConfiguredFluid`.
- **INV-MSN-08 [A][BEH]** Completion unlinks all infrastructure from the mission and links it
  to the respawned rocket. `MissionInfrastructureLifecycleTest.java:139-197`; code
  `MissionOreMining.java:183-189`, `MissionGasCollection.java:114-120`. Pinned by `MissionInfrastructureLifecycleTest#completionUnlinksInfrastructureFromMission`, `MissionInfrastructureLifecycleTest#completionLinksInfrastructureToRespawnedRocket`, `MissionInfrastructureLifecycleTest#startLinksInfrastructureToMission`.
- **INV-MSN-09 [V][BEH]** `failureChance` is 0, `canTick` is true, `performAction` false,
  `getInfo` null — the base is a passive countdown with no player interaction.
  `MissionResourceCollection.java:104-117,94-97`; pinned only as far as
  `defaultMissionSerialisesToNbtWithoutThrowing` (`MissionResourceCollectionContractTest.java:23-32`).
- **INV-MSN-10 [V][SYS]** `duration` is clamped to ≥ 1 tick at construction, so
  `getProgress` never divides by zero for live missions.
  `MissionResourceCollection.java:57-60`. FOR: INV-MSN-04.
- **INV-MSN-11 [V]** Completion only ever runs server-side and only when the launch world
  is loaded — `tickEntity` returns early on remote/null overworld and null launch world.
  `MissionResourceCollection.java:134-142`.
- **INV-MSN-12 [A]** `missionPersistantNBT` is the SSOT for rocket-authored data
  (`plannedHarvestMb`, `intakePower`-adjacent stats, `asteroidType/UUID`); the mission
  never mutates it after construction except the ore ctor stamping asteroid metadata.
  Inferred from the read-only accessors; not test-pinned.

## Failure modes & edge cases

- Corrupt/undeserialisable `rocketStorage` → mission self-abandons cleanly on next tick
  (MECH-MSN-07) rather than throwing during world load.
- `duration ≤ 0` on load → `getProgress` returns 1.0, so the mission completes on its
  first eligible tick.
- Gas respawn re-initialises the launch dimension via `DimensionManager.initDimension`
  if unloaded. `MissionGasCollection.java:88-92`.
- Ore harvest overflow beyond the rocket's inventory capacity is intentionally voided.
  `MissionOreMining.java:165`.

## Integration seams

- **Registry (C3):** `asteroidMiner` → `MissionOreMining`, `gasMining` →
  `MissionGasCollection`, registered via `SatelliteRegistry.registerSatellite`.
  `Stellurgy.java:362-363`. These names are the persisted `DataType` satellite
  type strings — renaming is safe under the 0.1.0 clean break, which deliberately abandons pre-0.1.0 worlds.
- **Capability:** consumes `CapabilityItemHandler` (ore, `MissionOreMining.java:62-83`)
  and `CapabilityFluidHandler` (gas, `MissionGasCollection.java:70-83`) on the rocket's
  cargo tiles.
- **Interfaces:** implements `IMission` (`api-public`); extends `SatelliteBase`
  (`satellite`); calls `IInfrastructure.linkMission/unlinkMission/linkInfrastructure`.
- **Packets:** the rocket (not the mission) broadcasts `PacketSatellite` at handoff
  (`EntityRocket.java:2315`).

## Config surface

Config: see `C4-config-surface`. The asteroid harvest table is the galaxy's `getAsteroidTypes()` (MECH-MSN-05; not config): an unknown type
harvests nothing (`MissionOreMining.java:118-120`). The `64000` mB gas base and the `MISSION_COMPLETION_TICKS = 60` poll interval are
`tunable` hardcoded constants, not config flags.

## Test coverage

INV-MSN-01/02 → none (both are `[V]`); INV-MSN-03 →
`MissionPersistenceRestartTest`; INV-MSN-04 → `MissionLifecyclePyramidTest`;
INV-MSN-05/06 → `MissionOreCompletionTest`; INV-MSN-07 → `MissionGasCompletionTest`;
INV-MSN-08 → `MissionInfrastructureLifecycleTest`; INV-MSN-09 →
`MissionResourceCollectionContractTest`.

## Open questions

- `getName()` on the base returns `mission.asteroidmining.name` and is inherited
  unchanged by `MissionOreMining` — correct for ore, but the base name is asteroid-specific
  rather than generic. Cosmetic; not a defect.
- Whether any consumer relies on `startDimid` (`worldId`) diverging from `launchDim`
  (`launchDimension`) — at construction they are identical (a comparison nothing makes).
