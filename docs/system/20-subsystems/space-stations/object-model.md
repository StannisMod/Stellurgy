# space-stations · object model (manager + SpaceStationObject)

Covers `SpaceObjectManager`, `SpaceStationObject`, `SpaceObjectBase`. This is the authoritative
model; the tiles in the other cluster docs are views over it.

## Mechanics

### MECH-STN-01 — Registration & square-spiral placement
`registerSpaceObject(obj, dimId, stationId)` sets the id, stores it in `stationLocations`, then
computes an `(x,z)` grid cell from `stationId` walking a square spiral (top+bottom rows first,
then sides) and, unless the object has a custom spawn, sets the spawn to the cell centre scaled by
`2*stationSize` (`SpaceObjectManager.java:147-187`). The 2-arg overload allocates the next id and
broadcasts a full `PacketSpaceStationInfo`; the client-only overload re-registers under a
server-supplied id (`:209-233`). Ids come from `getNextStationId()`, which linear-scans for the
first free key (`:75-80`) — it does **not** use the persisted `nextId`.

### MECH-STN-02 — Block-coord → station lookup + player containment
`getSpaceStationFromBlockCoords(pos)` inverts the spiral: it rounds `x,z` by `2*stationSize`,
derives a ring radius and a linear index, and returns that station (`:119-138`). This is the
**inverse** of MECH-STN-01 and the two must stay in lockstep (SSOT pair). `onPlayerTick`
(server-side, `spaceDimId` only) teleports a player who falls below y=0 back to their station's
spawn, and reflects `motionX/Z` when the player reaches a station's edge "wall" band so players
cannot walk off the packed grid into a neighbour (`:247-293`).

### MECH-STN-03 — Orbit map & body transfer
`spaceStationOrbitMap` maps a planet dim id → list of stations orbiting it. `moveStationToBody`
removes the station from its old planet's list, adds it to the new one, calls
`setOrbitingBody(dimId)` (which reparents its `DimensionProperties`), and — when `update` —
broadcasts `PacketStationUpdate.ORBIT_UPDATE` and fires a fog burst (`:354-382`). `setOrbitingBody`
early-returns if the id is unchanged and, on `SpaceStationObject`, records the id as the
`destinationDimId` whenever it is not the warp dim (`SpaceStationObject.java:598-606`).

### MECH-STN-04 — Warp transition scheduling
The timed `moveStationToBody(station, dimId, timeDelta)` overload parks the station in the pseudo
dimension `WARPDIMID = Integer.MIN_VALUE`, zeroes its atmosphere, sets
`nextStationTransitionTick = travelTimeMultiplier*timeDelta + now`, and calls
`beginTransition` (`SpaceObjectManager.java:391-413`). `onServerTick` (server, requires the space
world loaded) walks every station parked in `WARPDIMID`; any whose `getTransitionTime() <= now` is
moved to its `getDestOrbitingBody()` and removed from the warp list, and the next wake tick is
recomputed (`:296-316`).

### MECH-STN-05 — Rotation / altitude / gravity / fuel state
`SpaceStationObject` integrates rotation lazily: `getRotation(dir)` returns
`rotation[axis] + angularVelocity[axis]*(now - lastTimeModification[axis])` normalised to
`[0,360)`; `setDeltaRotation` snapshots the integrated angle, stamps `lastTimeModification[axis]`,
then stores the new velocity — and is a **no-op while anchored** (`SpaceStationObject.java:227-293`).
Altitude is `orbitalDistance` (a float, clamped ≥ 4.0, no-op while anchored, `:862-872`). Fuel is
an int capped at `MAX_FUEL=10000`; `addFuel`/`useFuel` clamp, broadcast `FUEL_UPDATE`, and
`useFuel` refuses (returns 0, consumes nothing) if the request exceeds the stock (`:373-420`).
`canTravel()` replaced `hasUsableWarpCore()` in 0.1.0 and returns a flat **false**: the warp core it
consulted was deleted, and a station holds its orbit until stations themselves become craft. The fuel
API survives it, unfed. [V] `SpaceStationObject.java` (`canTravel`).

### MECH-STN-06 — Landing-pad & docking-point registries
Pads are `StationLandingLocation`s keyed by `(x,0,z)`: `addLandingPad` de-dups, `removeLandingPad`
removes by position, `setPadStatus`/`setLandingPadAutoLandStatus` flip `occupied`/`allowedForAutoLand`,
and `getNextLandingPad(commit)` returns the first pad that is **both** free and auto-land-enabled,
optionally marking it occupied (`SpaceStationObject.java:422-556`). Docking points are a
`HashedBlockPosition → id-string` map (`:476-489`).

### MECH-STN-07 — NBT persistence (manager + object)
The manager writes each object as a compound tagged with its registered `type` string, plus
`nextInt` and `nextStationTransitionTick`; temporary objects also write `expireTime`/`numPlayers`
(`SpaceObjectManager.java:415-437`). On read it instantiates the object from `type` via the
class-registry, reads it, drops expired temporaries, and re-registers under its own id
(`:439-467`). `SpaceStationObject` round-trips its full state, storing pad list under
`spawnPositions` (each `occupied`/`autoLand`/`pos`/`name`), docks under `dockingPositons`, and known planets as an int array (`:700-850`).

## State & persistence (NBT — C1)

Manager (`SpaceObjectManager`): `spaceContents` (list), `nextInt`, `nextStationTransitionTick`;
per-entry `type`, `expireTime`, `numPlayers`.

`SpaceStationObject` / `SpaceObjectBase`: `id`, `posX`, `posY`, `altitude`,
`spawnX`/`spawnY`/`spawnZ`, `rotationX`/`Y`/`Z`, `deltaRotationX`/`Y`/`Z`, `launchposX`/`launchposY`,
`isAnchored`, `created`, `destinationDimId`, `fuel`, `orbitalDistance` (float),
`targetOrbitalDistance`, `targetGravity`, `targetRotationX`/`Y`/`Z`, `knownPlanets` (int[]),
`direction` (ordinal, optional), `transitionEta` (optional); list tags `spawnPositions`
(`occupied`,`autoLand`,`pos`,`name`), `dockingPositons` (`pos`,`id`). (`warpCorePositions` was
dropped in 0.1.0 with the warp core; it is neither written nor read.)
`DimensionProperties` fields are co-written into the same compound by `properties.writeToNBT`
(owned by dimension-planets).

## Invariants

- **INV-STN-01 [V]** Placement (`registerSpaceObject`, `:163-183`) and lookup
  (`getSpaceStationFromBlockCoords`, `:119-138`) are inverse encodings of the same square spiral
  over `2*stationSize` cells; a divergence corrupts every tile→station resolution.
- **INV-STN-02 [T]** Distinct stations registered in the same orbit get distinct ids
  (SpaceStationDepthTest:44-51; SpaceStationDockUndockTest:201-208).
- **INV-STN-03 [T]** `useFuel(amt)` consumes nothing and returns 0 when `amt > stock`; a partial
  drain leaves `stock-amt` and returns `amt` (SpaceStationDepthTest:101-135).
- **INV-STN-04 [T]** `addFuel` clamps at `MAX_FUEL` and returns only the amount actually added
  (SpaceStationDepthTest:80-97).
- **INV-STN-05 [T]** A freshly assembled station defaults to `orbitingPlanetId = INVALID_PLANET`
  and `fuelAmount = 0` (SpaceStationDepthTest:28-41).
- **INV-STN-06 [T]** Pads survive a save/reload round-trip with their `occupied`, `name` and
  `allowAutoLand` fields intact; `autoLand` is read from its own key, not tied to `occupied`
  (SpaceStationPadPersistenceTest:114-174; note the corrective comment at
  `SpaceStationObject.java:825-827`).
- **INV-STN-07 [T]** `getNextLandingPad` only returns pads that are free **and** auto-land-enabled;
  a pad must opt into auto-land before a dock can claim it
  (SpaceStationDockUndockTest:87-123; StationLandingLocationTest).
- **INV-STN-08 [V]** Rotation and altitude setters are no-ops while `isAnchored`
  (`SpaceStationObject.java:280-288,868-871`).
- **INV-STN-09 [A]** `nextId` (persisted as `nextInt`) is never consulted for allocation —
  `getNextStationId` always linear-scans — so it is effectively a dead field (no read path found).

## Failure modes & edge cases

- `getSpacePosition` calls `.size()`/`indexOf` on `getSpaceStationsOrbitingPlanet(...)`, which
  returns `null` for a planet with no registered stations → possible NPE (`:341-347`).
- `temporaryDimensionPlayerNumber` is declared but never initialised in the
  constructor (`SpaceObjectManager.java:38`, ctor `:42-49`), while `registerTemporarySpaceObject`
  (`:199`) and `unregisterSpaceObject` (`:216`) call `.put`/`.remove` on it → guaranteed NPE if
  ever reached. Both callers are presently dead (the only `unregisterSpaceObject` call site is
  inside the commented-out `onPlayerTransition`, `:325-352`), so the crash is latent.
- `SpaceObjectBase` (322 sloc) has **no subclass** — `SpaceStationObject`
  implements `ISpaceObject` directly. It is dead legacy code whose NBT keys duplicate the live
  object's, and its `getRotation` never updates `lastTimeModification` (single shared field,
  `:25,126,141-160`).
- `onServerTick` iterates `spaceStationOrbitMap.get(WARPDIMID)` with a for-each
  while calling `.remove(spaceObject)` on the same `LinkedList` inside the loop, and
  `moveStationToBody` further mutates the list — `ConcurrentModificationException` risk on any
  arriving warp (`SpaceObjectManager.java:305-311`).
