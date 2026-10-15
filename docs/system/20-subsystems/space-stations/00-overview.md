---
id: space-stations
owns: [stations/, tile/station/, api/stations/, tile/TileOrbitalRegistry.java, tile/TileStationAssembler.java]
entrypoints: [SpaceObjectManager#onServerTick, SpaceObjectManager#registerSpaceObject, TileWarpController#useNetworkData, TileStationAssembler#assembleRocket, TileOrbitalRegistry#getModules]
depends-on: [dimension-planets, network-wire, rocket-assembly, satellite, rocket-entity, world-gen, util-core, api-public]
depended-by: [client-render, rocket-entity, commands-gameplay, items, inventory-containers]
contracts: [C1, C2, C4, C5]
confidence: high
---

## Purpose

Space stations (and their warp-capable variant, "starships") are player-built structures
that live packed into a single shared **space dimension** (`spaceDimId`). This subsystem owns
the in-memory model of every orbiting object, its placement on a square-spiral grid inside that
one dimension, its orbital physics (altitude / gravity / rotation / fuel), its warp travel
between planets, and all the block tiles a player uses to build, program, steer and track a
station.

## Responsibility boundary

**Owns:**
- `stations/` — `SpaceObjectManager` (the singleton registry + server tick), the `ISpaceObject`
  implementation `SpaceStationObject`, and the legacy abstract `SpaceObjectBase`.
- `tile/TileOrbitalRegistry` — GUI to enumerate satellites/stations and program ID chips.
- `tile/TileStationAssembler` — packs a built structure into a station item + chip.
- `tile/station/` — docking port, landing pad, holographic planet selector, warp controller,
  and the three station controllers (altitude / gravity / orientation).

**Does NOT own:** `DimensionProperties` and dim id resolution (→ dimension-planets); the packet
wire types `PacketStationUpdate` / `PacketSpaceStationInfo` / `PacketMachine` (→ network-wire);
`StorageChunk` cut/paste and `TileRocketAssemblingMachine` base (→ rocket-assembly);
`SatelliteBase` / `SatelliteRegistry` (→ satellite); `EntityRocket` landing (→ rocket-entity);
`WorldProviderSpace` / station grid worldgen (→ world-gen); block & item registration in
`Stellurgy.java` (→ blocks / items); station entity renderers & `EntityUIPlanet/Star`
(→ client-render).

## Key types

| type | role |
|------|------|
| `SpaceObjectManager` | singleton: station-id→object, planet-id→[stations] orbit map, warp scheduler, save/load |
| `ISpaceObject` (api) | contract every orbiting object implements (interface owned by api-public) |
| `SpaceStationObject` | the concrete station: physics, fuel, pads, docks, warp cores, known planets |
| `SpaceObjectBase` | abstract legacy base — **no live subclass** |
| `TileStationAssembler` | scan pad → cut structure → register `SpaceStationObject` → emit item+chip |
| `TileWarpController` | travel-cost, warp trigger, data-search planet discovery |
| `TileHolographicPlanetSelector` | holographic star/planet picker that sets a station's destination |
| `TileStationAltitudeController` | drives `orbitalDistance` toward a target |
| `TileStationGravityController` | drives `gravitationalMultiplier` toward a target |
| `TileStationOrientationController` | drives per-axis angular velocity toward a target |
| `TileDockingPort` | named/target-id pair used to align docked modules |
| `TileLandingPad` | registers a pad with the station; links rocket infrastructure on land |
| `TileOrbitalRegistry` | multiblock GUI: list satellites/stations, write ID chips |

## Mechanic index

Detail lives in the four cluster docs; every mechanic there cites `file:line`.

- **[object-model.md](./object-model.md)** — the manager + `SpaceStationObject`:
  MECH-STN-01 registration & square-spiral placement · 02 block-coord lookup + player
  containment · 03 orbit map & body transfer · 04 warp transition scheduling ·
  05 rotation/altitude/gravity/fuel state · 06 pad & docking registries · 07 NBT persistence.
- **[controllers.md](./controllers.md)** — the three steering tiles:
  MECH-STN-08 altitude convergence · 09 gravity convergence · 10 orientation convergence ·
  11 redstone target override + comparator output.
- **[warp-navigation.md](./warp-navigation.md)** — travel & discovery:
  MECH-STN-12 travel-cost · 13 warp execution · 14 data-search planet discovery ·
  15 holographic system render & selection.
- **[assembly-docking.md](./assembly-docking.md)** — build/join/register + registry GUI:
  MECH-STN-16 station assembly · 17 module unpack & docking alignment · 18 docking-port pairing ·
  19 landing-pad tile↔station registration & rocket link · 20 orbital-registry scan & chip write.

## Dependency edges (data flow)

- **worldgen → manager**: `getSpaceStationFromBlockCoords` decodes a `BlockPos` in `spaceDimId`
  into a station id via the same square-spiral index used by placement (SSOT-critical pair).
- **controllers/tiles → SpaceStationObject**: every station tile resolves its station each tick
  by `getSpaceStationFromBlockCoords(pos)` — the tiles hold no station reference of their own
  (except `TileWarpController`, which caches and clears on unload/invalidate).
- **manager → clients**: mutations fan out via `PacketStationUpdate` (typed deltas) and
  `PacketSpaceStationInfo` (full snapshot); the manager also drives `proxy.fireFogBurst`.
- **manager → dimension-planets**: `setOrbitingBody` reparents the station's `DimensionProperties`;
  warp uses `Type` deltas and `realizeAtmosphere(false, 0)` (a vacuum).

## Contracts touched

- **C1 NBT** — manager save (`spaceContents`/`nextInt`/`nextStationTransitionTick`) + per-object
  keys; tile keys (`storedID`, `myId`/`targetId`, `infrastructureLocations`, cache lists).
- **C2 packets** — consumes `PacketStationUpdate`, `PacketSpaceStationInfo`, `PacketMachine`.
- **C4 config** — `stationSize`, `spaceDimId`, `orbit`, `travelTimeMultiplier`,
  `planetsMustBeDiscovered`, `allowZeroGSpacestations`, `planetDiscoveryChance`.
- **C5 events** — `SpaceObjectManager` subscribes `PlayerTickEvent` + `ServerTickEvent`;
  `TileLandingPad` subscribes `RocketLanded/PreLaunch/Dismantle`.

## Cross-cutting invariants

- **INV-STN-00 [V]** There is exactly one `SpaceObjectManager` (private static singleton,
  `SpaceObjectManager.java:30`), reachable via `getSpaceManager()` and `StellurgyAPI`.
- **INV-STN-0A [V]** A station's identity, orbit and physics are the **single source of truth**
  on the `SpaceStationObject`; every tile is a stateless view resolved by grid position
  (`getSpaceStationFromBlockCoords`). Tiles that cache (warp controller) clear on unload
  (`TileWarpController.java:992,999`).

See each cluster doc for the numbered invariants and their test pins. Open questions and failure modes are at the bottom of the relevant cluster doc.
