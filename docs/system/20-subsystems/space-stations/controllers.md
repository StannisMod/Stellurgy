# space-stations · station controllers

Three tiles that drive a station's physics toward a per-axis target. All share the same shape:
a slider sets a target on the `SpaceStationObject`, an optional redstone mode overrides the target
from block power, and `update()` (server, space dim only) walks the actual value toward the target
by a bounded step and throttles a `PacketStationUpdate` to clients. None of them hold a station
reference — each resolves it every tick via `getSpaceStationFromBlockCoords(pos)`.

## Mechanics

### MECH-STN-08 — Altitude convergence
`TileStationAltitudeController.update` reads/writes `station.targetOrbitalDistance`, then steps the
actual `orbitalDistance` toward it by `acc = 0.02`/tick (settle epsilon `0.001`), calling
`setOrbitalDistance` (`TileStationAltitudeController.java:162-203`). While changing it emits
`ALTITUDE_UPDATE` at ≤ ~4 Hz (`wt - lastAltSyncTick >= 10`) and flushes once more on settle so
clients land on the exact value. The GUI slider maps 0..190; `getComparatorOverride` exposes
`(orbitalDistance+5)/13` (`:291-302`).

### MECH-STN-09 — Gravity convergence
`TileStationGravityController.update` drives `properties.getGravitationalMultiplier()` toward
`targetGravity/100` by `acc = 0.001`/tick and emits `DIM_PROPERTY_UPDATE` at ≤ ~4 Hz
(`:188-227`). The target floor depends on config: `getMinGravity() = allowZeroGSpacestations ? 0 : 10`,
read from the config IN FORCE at every use (on a client, the server's synced copy) — never cached, so
the slider range (`getTotalProgress = 100 - getMinGravity()`) and the target write agree with the
server (`:46-57,203-205,297-310`). It is never a static re-set by tile constructors
from whichever side built a tile last. Comparator override is
`(gravitationalMultiplier - 0.1)/0.059` (`:315-326`).

### MECH-STN-10 — Orientation convergence
`TileStationOrientationController.update` holds three axes. For each of EAST/UP/NORTH it steps the
station's angular velocity toward `targetRotationsPerHour[i]/72000` by the station's
`getMaxRotationalAcceleration()` per tick via `setDeltaRotation`, and emits `ROTANGLE_UPDATE` at
≤ ~4 Hz when any axis moved (`:145-185`). Sliders are centred (`progress = target + total/2`,
`total = 120`); a reset button recentres all three (`:240-277`).

### MECH-STN-11 — Redstone target override + comparator
Altitude and gravity controllers carry a `ModuleRedstoneOutputButton` whose `RedstoneState`
(`OFF`/`ON`/`INVERTED`) is persisted as byte `redstoneState` and synced with network id 2. In
`ON`/`INVERTED` the target is derived from `world.getStrongPower(pos)` instead of the slider
(altitude `:171-175`, gravity `:196-200`). Orientation has no redstone mode. All three implement
`IComparatorOverride` so a redstone comparator reads back the current physical value.

## Client display

Each controller adds an anonymous zero-size `ModuleBase` whose `renderBackground` runs only while
the GUI is open: it caches/revalidates the station by id and updates `ModuleText` labels **only
when the displayed key changes** (altitude in Km, gravity 2dp, rate 1dp, per-axis velocity 1dp),
avoiding idle churn (altitude `:69-131`, gravity `:67-146`, orientation `:72-139`). Altitude and
orientation additionally push a full `PacketSpaceStationInfo` to the opening player in
`getModules` so the client has fresh state before first render.

## State & persistence (NBT / wire — C1/C2)

- Altitude & gravity controllers persist `redstoneState` (byte). Network: id 0 carries the slider
  `progress` (short); id 2 carries the redstone `state` byte (nbt key `state`).
- Orientation controller persists nothing of its own (targets live on the station); network id 0
  carries three shorts (the three axis sliders).
- All target values (`targetOrbitalDistance`, `targetGravity`, `targetRotationsPerHour`) are stored
  on the `SpaceStationObject`, not on the tiles (see object-model MECH-STN-07).

## Config surface

Config: see `C4-config-surface` (controllers only tick when `world.provider instanceof WorldProviderSpace`).

## Invariants

- **INV-STN-10 [A][BEH]** Setting the altitude controller's target moves the station's
  `targetOrbitalDistance`, and the actual `orbitalDistance` then walks toward that target over
  ticks (StationControllersTickContractTest:73-127). Pinned by `StationControllersTickContractTest#altitudeControllerWalksStationOrbitalDistanceTowardTarget`.
- **INV-STN-11 [A][BEH]** Driving the gravity controller walks the station's actual gravity
  measurably below its start toward the target (StationControllersTickContractTest:165-200). Pinned by `StationControllersTickContractTest#gravityControllerWalksStationGravityTowardTarget`.
- **INV-STN-12 [A][BEH]** Setting the orientation controller's X target reflects onto the station's
  `targetRotationsPerHour[0]` and the station's rotation around EAST then changes
  (StationControllersTickContractTest:217-261). Pinned by `StationControllersTickContractTest#orientationControllerWalksStationRotationTowardTarget`.
- **INV-STN-13 [A][BEH]** All three controllers place and tick without error on a station
  (StationControllersSmokeTest). Pinned by `StationControllersSmokeTest#orientationControllerPlacesAndTicksWithoutCrash`, `StationControllersSmokeTest#gravityControllerPlacesAndTicksWithoutCrash`, `StationControllersSmokeTest#altitudeControllerPlacesAndTicksWithoutCrash`.
- **INV-STN-14 [V][BEH]** Convergence steps are bounded per tick (altitude `0.02`, gravity `0.001`,
  orientation `getMaxRotationalAcceleration`) so a target change ramps rather than snaps; the exact
  rates are `tunable` (`TileStationAltitudeController.java:183`, `TileStationGravityController.java:210`,
  `TileStationOrientationController.java:168`).
- **INV-STN-15 [V]** Controllers never mutate physics while `world.isRemote`; the write path is
  gated server-side in each `update()` (`:164,192,150`).

## Failure modes & edge cases

- In `TileStationAltitudeController.update` the redstone-derived target uses
  `Math.max(power*13+4, 190)` (and the inverted branch likewise), which **floors** the target at
  190 — for any power 0..14 the target is forced to 190, so redstone control cannot select a low
  altitude. The surrounding slider max is 190 and gravity's analogue uses a plain expression, so a
  `Math.min` cap was almost certainly intended (`TileStationAltitudeController.java:171-175`).

## Open questions

- `TileStationGravityController.updateText` (`:176-186`) is a fully-formed client formatter that is
  never called (the anonymous module supersedes it) — dead, but harmless.
