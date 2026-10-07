# F6 — Station assembly → warp / navigation

End-to-end: a player builds a structure on a station assembler, packs it into a station item + chip,
deploys it into the shared space dimension, steers it, picks a destination, and (where warp is
reachable) warps it to another planet. The value is the **seams**: where the assembler, the
`SpaceObjectManager` model, the `DimensionProperties` reparent, and the warp controller hand state to
one another — all mediated by one integer station id and the square-spiral grid that turns a `BlockPos`
back into a station.

Owning subsystem: **space-stations** (all `MECH-STN-*`); dimension reparent seam into
**dimension-planets**. Mechanics live in the owner doc; each step names its anchor. The ship-as-station
model of `space-model.md` §10 is the successor of this flow for tier-2 stations; this flow describes the
tier-1 station object that exists today.

## Steps

1. **Scan & pack the structure.** `TileStationAssembler` (extends the rocket-assembly base) checks the
   loader block + `itemSpaceStationChip` + empty outputs, shrinks the pad AABB, cuts the region to a
   `StorageChunk`, and branches on `storedId` (MECH-STN-16). The chip's UUID captured on scan decides
   new-vs-existing.
2. **Register the station model.** For a new station (`storedId == null`) the assembler news a
   `SpaceStationObject` and calls `registerSpaceObject(obj, Constants.INVALID_PLANET)`. Registration
   allocates the next free id by linear scan (MECH-STN-01), computes the station's `(x,z)` grid cell on
   the square spiral, sets its spawn, and slots it into `spaceStationOrbitMap[INVALID_PLANET]` via
   `moveStationToBody(obj, dimId, false)` — `update = false`, so **no** `PacketStationUpdate` fan-out.
3. **Emit item + chip stamped with the id.** The assembler writes the id onto both the packed
   `itemSpaceStation` and a fresh `itemSpaceStationChip` (slot 3), then `setStructure` stores the cut
   `StorageChunk` (MECH-STN-16). *Seam:* the station id is the sole link between the in-memory
   `SpaceObjectManager` entry and the item the player carries away. INV-STN-05 pins the fresh station to
   `orbitingPlanetId = INVALID_PLANET`, `fuel = 0`.
4. **Deploy / unpack into the space world.** When the packed item is used, `onModuleUnpack(chunk)`
   pastes the structure into the space dimension. The first unpack (`!created`) centres it on the
   station's spawn and flips `created`; a later unpack is treated as a docking module and
   geometry-aligned by port ids (MECH-STN-17).
5. **Docking ports register with the station.** Each pasted `TileDockingPort.onLoad` calls
   `registerTileWithStation`, which resolves the station by **grid position**
   (`getSpaceStationFromBlockCoords`, the inverse spiral, MECH-STN-02 / INV-STN-01) and calls
   `addDockingPosition(pos, myId)` (MECH-STN-18). *Seam:* producer = tile lifecycle; consumer =
   MECH-STN-17 alignment, which matches a station-side dock's stored id against a module dock's
   `targetId` (INV-STN-28).
6. **Landing pads register; rockets link in.** `TileLandingPad.registerTileWithStation` adds the pad
   (`occupied = false`, `allowAutoLand = false`, INV-STN-23) and, on `RocketLanded / PreLaunch /
   Dismantle` (C5 events), overrides rocket target coords via its `itemLinker` (MECH-STN-19). Auto-land
   dock selection consumes these pads through `getNextLandingPad` (INV-STN-07 / STN-24).
7. **Steer the station (optional pre-warp).** The three controllers resolve the station each tick by
   grid position and walk altitude / gravity / orientation toward slider targets stored **on the
   `SpaceStationObject`**, not on the tiles (MECH-STN-08/09/10; INV-STN-0A SSOT). All are no-ops while
   `isAnchored` (INV-STN-08) and never write client-side (INV-STN-15).
8. **Pick a destination.** `TileHolographicPlanetSelector.selectSystem` routes through
   `station.setDestOrbitingBody(id)`, which sets `destinationDimId` and broadcasts `DEST_ORBIT_UPDATE`
   so client warp previews stay consistent (MECH-STN-15 / INV-STN-19). *Seam:* the destination is
   written here and **read** by the warp controller's cost and execution.
9. **Compute travel cost.** `TileWarpController.getTravelCost` compares the station's parent-planet
   `DimensionProperties` (dimension-planets) with the destination's: `MAX_VALUE` if undefined, a flat
   cross-star constant, `1` for moon↔parent, else polar Euclidean distance (MECH-STN-12 / INV-STN-17).
   The controller caches its station and drops the cache on unload / invalidate (INV-STN-20).

**Steps 10–13 are not reachable for any station today:** `SpaceStationObject.canTravel()` is a flat
`false`, and the warp press refuses on it (the warp core does not exist). They are the described
behaviour of the code that runs if a station ever answers true.

10. **Execute the warp.** The warp button is `PacketMachine` id 2. Server-side it refuses unless the
    station is unanchored, `canTravel()`, meets the destination's artifact requirement, and
    `useFuel(cost) != 0` — fuel spent atomically before the move (MECH-STN-13 / INV-STN-16;
    `SpaceStationObject.useFuel` INV-STN-03). It then calls the **timed**
    `moveStationToBody(station, dest, clamp(cost, 0, 1000))`.
11. **Park in the warp pseudo-dimension.** The timed overload removes the station from its current
    planet's orbit list, adds it to `spaceStationOrbitMap[WARPDIMID]` (`WARPDIMID = Integer.MIN_VALUE`),
    zeroes atmosphere, sets `nextStationTransitionTick = travelTimeMultiplier * timeDelta + now`, and
    calls `beginTransition` (MECH-STN-04). `setOrbitingBody(WARPDIMID)` reparents `DimensionProperties`
    but deliberately does **not** overwrite `destinationDimId` — step 13 reads it back.
12. **Wait — server tick scheduler.** `onServerTick` (space world loaded only) checks whether any
    station parked in `WARPDIMID` has reached its transition tick (MECH-STN-04). It iterates a snapshot
    of the bucket, because `moveStationToBody` mutates it.
13. **Arrive at the destination body.** For each due station it calls the untimed
    `moveStationToBody(station, station.getDestOrbitingBody())` (`update = true`), which moves it out of
    the `WARPDIMID` bucket into `spaceStationOrbitMap[dest]`, calls `setOrbitingBody(dest)` to reparent
    `DimensionProperties`, broadcasts `PacketStationUpdate.ORBIT_UPDATE`, and fires a fog burst
    (MECH-STN-03). Controllers and warp cost then resolve against the new parent on their next tick.

14. **Data-search discovery (parallel path).** The warp controller's data tab can discover new planets
    from DISTANCE / MASS / COMPOSITION data, mutating `SpaceStationObject.knownPlanets` and broadcasting
    `PacketSpaceStationInfo` (MECH-STN-14 / INV-STN-18), feeding new destinations back into step 8.

## Gaps & mismatches

- **`unregisterSpaceObject` corrupts the orbit map (key-space collision).** It calls
  `spaceStationOrbitMap.remove(id)` with a **station id**, but that map is keyed by **planet / dim id**
  (`SpaceObjectManager.unregisterSpaceObject` vs `moveStationToBody`). So it (a) removes the orbit
  bucket of an unrelated planet whose dim id happens to equal the station id, and (b) never removes the
  station from its *actual* orbit bucket — `getSpaceStationsOrbitingPlanet` (consumed by the MECH-STN-12
  warp cost, the biome scanner, `getSpacePosition`) then returns a **stale, unregistered station** while
  `stationLocations` no longer has it. Producer and consumer disagree.
- **Broken docking-port unregister.** `BlockStationModuleDockingPort.breakBlock` tests the tile
  `instanceof TileLandingPad`, but the block's tile is `TileDockingPort` (which does not extend it), so
  `unregisterTileWithStation` never runs on break — a removed dock stays in `dockingPoints` forever, and
  MECH-STN-17 alignment can match a phantom port. Step 5's producer never retracts.
