# Transport & Field Machines

Part of [multiblock-machines](./00-overview.md). Files: `TileRailgun.java` (652),
`TileSpaceElevator.java` (427), `TileAreaGravityController.java` (400), `TileBeacon.java` (84).

## Purpose

Machines that move matter, people, or physics between/within dimensions: the railgun (item cargo
across a planetary system), the space elevator (a tethered capsule between a planet and its
station), the area gravity controller (a directional gravity field), and the beacon (a rocket
landing marker).

There is no warp core and no station FTL: faster-than-light travel is one mechanic, the
hyperdrive a craft carries (see [hyperdrive](../hyperdrive.md)). `MECH-MBM-26` is retired.

## Key types

| class | role |
|-------|------|
| `TileRailgun` | linked-pair cargo transfer; `ILinkableTile`; fire-status feedback |
| `TileSpaceElevator` | linked-pair tether; summons `EntityElevatorCapsule`; `ILinkableTile` |
| `TileAreaGravityController` | per-side gravity field over a radius; `ISliderBar` |
| `TileBeacon` | registers a beacon location on `DimensionProperties` |

## Mechanics

### MECH-MBM-24 — Railgun cargo transfer
`useEnergy` only draws power (and thus runs) when enabled, redstone-satisfied, and a cargo
transfer succeeds. `attemptCargoTransfer` picks a stack ≥ `minStackTransferSize` from an input
hatch, resolves the linked destination (`ItemLinker` master coords + dim), **loading the dim if it
is registered-but-unloaded**, checks the target is a railgun with room and in the same planetary
system, then moves the stack, spawns an `EntityItemAbducted`, and plays effects. Every outcome is
recorded in a `FireStatus` enum (IDLE/FIRED/NO_TARGET/TARGET_UNAVAILABLE/TARGET_FULL/
DIFFERENT_SYSTEM) synced to the GUI (a failure is never a silent no-op). Required
power scales with distance. Owns its chunk via a `ForgeChunkManager` ticket in `onLoad`. [V]
`TileRailgun.java:200-210,273-329,331-429`.

### MECH-MBM-25 — Space-elevator tether & capsule
Linking two elevators (`onLinkComplete`) validates geostationary orbit
(`isDestinationValid`) and that the tether would not break the station, then records the paired
`DimensionBlockPosition` on both tiles and anchors the station. `summonCapsule` (packet 2) spawns a
single `EntityElevatorCapsule` at the landing pad, killing any existing capsule on either end.
Deconstruction unlinks both ends. Anchoring calls `SpaceStationObject.setIsAnchored` / zeroes
delta-rotation. [V] `TileSpaceElevator.java:80-115,248-381`.

### MECH-MBM-26 — retired
No warp-core machine or `fuelPointsPerDilithium` config exists. See [hyperdrive](../hyperdrive.md).

### MECH-MBM-27 — Area gravity controller
When enabled and its redstone state is satisfied, ramps `currentProgress` toward the slider target
`gravity/100` at ±0.001/tick and applies a per-side directional velocity impulse to every entity
in a `getRadius()` (= `radius+10`) box, using a `ModuleBlockSideSelector` (0=none, 1=set,
2=additive) and `GravityHandler` offsets. Players in creative flight are exempt; fall distance is
zeroed. When disabled it decays `currentProgress` to 0. [V] `TileAreaGravityController.java:146-254`.

### MECH-MBM-28 — Beacon register/unregister
`setMachineEnabled` adds/removes a `HashedBlockPosition` beacon on the local dim's
`DimensionProperties` (`addBeaconLocation`/`removeBeaconLocation`), used by rocket auto-landing.
[V] `TileBeacon.java:62-72`.

## State & persistence

| tile | keys | notes |
|------|------|-------|
| Railgun | `minTfrSize` (int, disk), `redstoneState` (byte, disk), `fireStatus` (byte, network) | packet path also uses `minTransferSize`/`state` transient keys |
| Space elevator | `dstDimId` (int), `dstPos` (int[3]), `tether` (bool) | network-data only; rebuilt into `DimensionBlockPosition` |
| Gravity controller | `gravity` (short), `currGravity` (**float**), `radius` (short), `redstoneState` (byte), `progress`, `bytes` (side-state array) | network-data; `currGravity` is the live field speed |
| Warp core | — | stateless beyond libVulpes base |
| Beacon | — | writes to `DimensionProperties`, not local NBT |

See [C1](../../30-contracts/C1-nbt-persistence.md). `redstoneState` is shared as a key name across
railgun / gravity controller / several infrastructure tiles — same semantic, distinct owners.

## Integration seams

- **Packets** (`PacketMachine`): Railgun 3 fire-fx, 4 min-transfer, 5 redstone. Elevator 2 summon,
  ≥5 button/clear-dest. Gravity 3 slider, 4 side-state, 5 redstone.
- **→ space-stations**: `SpaceStationObject` fuel/anchor/tether; `getSpaceStationFromBlockCoords`.
- **→ dimension-planets**: beacon locations; `DimensionManager.getEffectiveDimId`.
- **→ util-core**: `PlanetaryTravelHelper` (same-system / geostationary checks), `ItemLinker`,
  `DimensionBlockPosition`, `GravityHandler`.
- **Forge**: railgun `ForgeChunkManager` ticket; `DimensionManager.initDimension` on both railgun
  and elevator destination resolution.
- **Entities**: `EntityItemAbducted`, `EntityElevatorCapsule`.

## Config surface

`spaceDimId` — elevator
station-side branch. Railgun/gravity/beacon have no dedicated flags. `GravityHandler.*_OFFSET`
constants are code-level (`tunable`).

## Invariants

- **INV-MBM-20** [T][BEH] The railgun fires to a linked railgun in the same dimension, drains the
  source and fills the destination, and sets `FireStatus.FIRED`; firing at a registered-but-
  unloaded dim loads it and reports `TARGET_UNAVAILABLE` when no railgun is there, preserving cargo.
  `RailgunFiringContractTest.java:75-165`. Pinned by `RailgunFiringContractTest#railgunFiresCargoToLinkedRailgunInSameDimension`, `RailgunFiringContractTest#railgunLoadsRegisteredButUnloadedDestinationDimension`, `RailgunFiringContractTest#railgunReportsUnavailableForUnloadableDestination`.
- **INV-MBM-21** [T][BEH] A failed shot never destroys cargo (cargo preserved on every non-FIRED path).
  `RailgunFiringContractTest.java:95,137,165`. Pinned by `RailgunFiringContractTest#railgunLoadsRegisteredButUnloadedDestinationDimension`, `RailgunFiringContractTest#railgunReportsUnavailableForUnloadableDestination`.
- **INV-MBM-22** [V][BEH] The elevator refuses a same-dimension link and a non-geostationary link, and
  keeps at most one live capsule per line. `TileSpaceElevator.java:307-353,204-219,252-268`.
- **INV-MBM-23** [T][BEH] The area gravity controller zeroes entity `fallDistance` while active.
  `AreaGravityControllerFallDistanceResetTest`. Pinned by `AreaGravityControllerFallDistanceResetTest#controllerResetsFallDistanceInsideRadiusOnly`.
- **INV-MBM-24** [A] `currGravity` is persisted as a float; because it is a display/animation
  speed re-derived toward an integer `gravity/100` target each tick, float drift across save/load
  is self-correcting rather than contract-breaking — assumed, not test-pinned.
  `TileAreaGravityController.java:179-195,322-335`.

## Test coverage

`RailgunMultiblockTest`, `RailgunFiringContractTest` (INV-MBM-20/21),
`RailgunCargoReceiveContractTest`, `RailgunCargoTransitE2ETest`, `SpaceElevatorMultiblockTest`,
`ElevatorCapsuleStateAndNbtTest`, `ElevatorCapsuleRideE2ETest`,
`AreaGravityControllerMultiblockTest`, `AreaGravityControllerFallDistanceResetTest` (INV-MBM-23),
`BeaconMultiblockTest`, `BeaconEnableCycleTest`.

## Failure modes & edge cases

- **SpaceElevator deconstruct NPE.** `deconstructMultiBlock:103` dereferences
  `dimBlockPos.dimid` with no null guard, but `dimBlockPos` is null for an elevator that was never
  tether-linked (initialized null in the constructor, only set on link). Breaking an unlinked
  elevator multiblock NPEs in `deconstructMultiBlock`, which can abort the teardown.
- Debug `System.out.println("client should not call setRunning")` remains in the laser drill hot
  path (cross-referenced from [orbital-laser-drill](./orbital-laser-drill.md)); the railgun/elevator
  are clean of stray prints.
