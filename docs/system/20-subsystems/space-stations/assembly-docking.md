# space-stations · assembly, docking, landing pads, orbital registry

How a station is built (`TileStationAssembler`), how modules join an existing station
(`SpaceStationObject.onModuleUnpack` + `TileDockingPort`), how rockets attach to it
(`TileLandingPad`), and how players enumerate/program orbital objects (`TileOrbitalRegistry`).

## Mechanics

### MECH-STN-16 — Station assembly
`TileStationAssembler extends TileRocketAssemblingMachine`. `canScan` requires a station-loader
block in slot 0, an `itemSpaceStationChip` in slot 1, and empty outputs (`TileStationAssembler.java:39-60`).
`scanRocket` shrinks the pad bounding box to the tight non-air AABB and sets status
`SUCCESS_STATION`/`EMPTY` (`:62-103`). `assembleRocket` re-scans, cuts the region into a
`StorageChunk` (`StorageChunk.cutWorldBB`, owned by rocket-assembly), and then branches on
`storedId` (`:107-159`):
- **new station** (`storedId == null`): create a `SpaceStationObject`, register it at
  `INVALID_PLANET`, emit an `itemSpaceStation` packed item stamped with the station id, **and** a
  fresh `itemSpaceStationChip` (slot 3) also carrying the id;
- **existing station** (a stored id from a re-inserted chip): emit only the packed item bound to
  the stored id.
`useNetworkData` captures `storedId` from the inserted chip's UUID on scan (0 ⇒ null,
`:219-231`). The packed structure is written via `ItemPackedStructure.setStructure`.

### MECH-STN-17 — Module unpack & docking alignment
`SpaceStationObject.onModuleUnpack(chunk)` pastes a module into the space world
(`SpaceStationObject.java:626-698`). On **first** unpack (`!created`) it pastes centred on the
station's spawn and flips `created`. On a **later** unpack it treats the chunk as a docking module:
it collects the chunk's `TileDockingPort`s, finds the first station-side dock whose stored id
matches a module dock's `targetId`, then rotates the chunk so the two ports face-align (using
`BlockFullyRotatable.FACING`), pastes it offset one block past the station port, and clears the two
mating port blocks so the modules merge. This is the only non-trivial geometry in the subsystem.

### MECH-STN-18 — Docking-port id pairing
`TileDockingPort` holds two strings: `myId` (this port's name) and `targetId` (the port it wants
to mate with). On `onLoad` it registers its `(pos → myId)` with the station via
`addDockingPosition`; on `invalidate` it unregisters (`TileDockingPort.java:130-165`). Both strings
are editable in-GUI (`ModuleTextBox`), synced with `PacketMachine` ids 0/1, and persisted as NBT
`myId`/`targetId` (omitted when empty). MECH-STN-17 consumes these ids to align modules.

### MECH-STN-19 — Landing-pad tile ↔ station registration & rocket link
`TileLandingPad extends TileInventoryHatch implements ILinkableTile`. Its 1-slot inventory holds a
libVulpes `itemLinker` targeting an infrastructure tile; setting/clearing the linker pushes
overridden landing coords (`orbit` altitude) onto any rocket in a ±1×2×1 box and toggles the pad's
auto-land flag (`TileLandingPad.java:242-274`). It subscribes three rocket events (C5) while it
stands in a world, and answers only rockets in THAT world (`isInMyWorld`, `:94`): on
`RocketLanded` it links connected infrastructure and, if a linker is present, overrides the
rocket's target coords; on `RocketPreLaunch` it re-applies the override; on `RocketDismantle`
(space dim only) it frees the pad (`setPadStatus(pos,false)`) (`:144-205`). `registerTileWithStation`
adds the pad to the station and marks it occupied if a rocket already sits on it; the `name`
textbox renames the pad or creates it (`:208-222,339-358`).

### MECH-STN-20 — Orbital-registry scan & chip write
`TileOrbitalRegistry` is a `TileMultiPowerConsumer` with two tabs (satellites / stations), a
scrollable list, a detail pane, and a chip in/out slot pair. **Scan** (network `NET_BUTTON_SCAN=13`)
rebuilds a server-authoritative cache: satellites from the effective dim's `DimensionProperties`
(blacklisting `asteroidMiner`/`gasMining`, `:243-339`), stations from `SpaceObjectManager.getSpaceObjects()`
(`:350-417`); it bumps `scanNonce` so the client knows the cache changed and can reopen the GUI
(`:1281-1326`). **Write** (`NET_BUTTON_WRITE_CHIP=12`) validates via `checkWrite()` then, on the sat
tab, programs an ID chip / ore-scanner for the selected satellite and stamps planet info; on the
station tab, stamps the selected station's id onto an `ItemStationChip` (`:583-655`). Selection is
click-driven with offsets `SAT_LIST_OFFSET=100` / `STATION_LIST_OFFSET=200`.

## State & persistence (NBT / wire — C1/C2)

- `TileStationAssembler`: `storedID` (long, omitted when null) + its 4-slot inventory.
- `TileDockingPort`: `myId`, `targetId` (strings). Sync via `SPacketUpdateTileEntity`
  (`writeToNBT`) and `PacketMachine` (ids 0/1, nbt key `id`).
- `TileLandingPad`: `infrastructureLocations` (int[3n]), `name`; `PacketMachine` id 0 renames.
- `TileOrbitalRegistry`: `satDimId`, `lastSatButton`, `selectedSatId` (long), `lastStationButton`,
  `selectedStationId`, `scanNonce`, plus list caches `satCache`
  (`id`,`dimId`,`registryKey`,`powerGen`,`powerStorage`,`maxData`,`generatesData`) and `stationCache`
  (`id`,`dimId`,`orbitingBodyId`,`anchored`,`mobile`,`freePads`); network nbt keys `tab`,
  `buttonSat`, `buttonStation` (`:1344-1424`). The cache is written into both the save NBT and the
  client-sync network NBT.

## Config surface

Config: see `C4-config-surface`.

## Invariants

- **INV-STN-22 [A][BEH]** A new station assembly registers a `SpaceStationObject` and emits a chip
  carrying its id; the id is unique and appears in the manager list
  (SpaceStationDepthTest; SpaceStationDepthTest:44-58). Pinned by `SpaceStationDepthTest#multipleStationsCoexistWithDistinctIds`, `SpaceStationDepthTest#stationCreateRegistersAndPersistsForList`.
- **INV-STN-23 [A][BEH]** A pad added to a station starts `occupied=false` and `allowAutoLand=false`,
  carries its supplied name, and de-dups on `(x,z)` (SpaceStationDockUndockTest:65-83,167-181). Pinned by `SpaceStationDockUndockTest#addPadGrowsListWithExpectedDefaults`, `SpaceStationDockUndockTest#addPadIsIdempotentForSamePosition`.
- **INV-STN-24 [A][BEH]** Dock claims the pad returned by `getNextLandingPad` and marks it occupied; a
  second dock with no other free auto-land pad fails; undock frees the pad and it can be reclaimed
  (SpaceStationDockUndockTest:100-149; SpaceStationPadPersistenceTest:180-190). Pinned by `SpaceStationDockUndockTest#dockClaimsAutoLandPadAndMarksOccupied`, `SpaceStationDockUndockTest#undockReturnsPadToFreePool`.
- **INV-STN-25 [A][BEH]** Pad ownership is per-station: station A never reports station B's pad
  (SpaceStationDockUndockTest:201-221). Pinned by `SpaceStationDockUndockTest#multipleStationsTrackPadsIndependently`.
- **INV-STN-26 [V][BEH]** Assembly refuses unless loader block + station chip present and outputs empty;
  a `NegativeArraySizeException` on cut sets `FAIL_CUT` rather than crashing
  (`TileStationAssembler.java:40-60,118-125`).
- **INV-STN-27 [V][BEH]** `TileOrbitalRegistry` chip writes only execute server-side and only when
  `checkWrite()` passes (output empty, exactly one input chip, valid selection, and — for stations
  — the station is launched) (`:583-655,487-575`).
- **INV-STN-28 [V][BEH]** Module docking (`onModuleUnpack`, not first unpack) mates two ports only when
  a station port's stored id equals a module port's `targetId`, then clears both port blocks
  (`SpaceStationObject.java:646-696`).

## Failure modes & edge cases

- `onModuleUnpack` docking path assumes `srcTile`/`destTile` block states expose
  `BlockFullyRotatable.FACING`; a mismatched dock block would throw on `getValue` (`:668-669`).
  Not filed — the dock blocks are fixed by MECH-STN-18 registration.
- `TileOrbitalRegistry` persists a possibly-large `satCache`/`stationCache` into the tile's save
  NBT and into every client-sync packet; caches are cleared on `invalidate`/`onChunkUnload`
  (`:1528-1553`) but grow unbounded with world satellite/station count between scans. Bounded by object counts, not per tick.

## Test coverage (this cluster)

`SpaceStationDockUndockTest`, `SpaceStationPadPersistenceTest`, `SpaceStationDepthTest`,
`SpaceStationDepthTest`, `StationLandingLocationTest` (unit), plus the stellurgytest station probe verbs
used by the above.
