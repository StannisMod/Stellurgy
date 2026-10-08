# api-public / interfaces — contracts, capabilities, astronomy, sealing, blobs

The decoupling layer: pure interfaces implemented elsewhere, the two Forge capabilities, the
`StellarBody` astronomy value object, and the `AreaBlob` region graph.

Files: `dimension/IDimensionProperties.java` (228), `stations/ISpaceObject.java` (183),
`stations/IStorageChunk.java` (29), `IPlanetaryProvider.java` (86), `IInfrastructure.java` (52),
`IMission.java` (30), `IGravityManager.java` (41),
`atmosphere/IAtmosphereSealHandler.java` (46), `dimension/solar/StellarBody.java` (284),
`dimension/solar/IGalaxy.java` (53), `capability/CapabilityWear.java` (74),
`capability/IPartWear.java` (29), `capability/CapabilitySpaceArmor.java` (36),
`armor/IProtectiveArmor.java` (16), `armor/IFillableArmor.java` (47), `AreaBlob.java` (177),
`util/IBlobHandler.java` (73).

## Key types

| type | role | implemented by |
|------|------|----------------|
| `IDimensionProperties` | full planet-dimension read/write contract (~50 methods) | `dimension.DimensionProperties` |
| `ISpaceObject` | orbiting-station contract (id, orbit, rotation, pads, warp transition) | `stations.SpaceStationObject` |
| `IStorageChunk` | packed structure: `pasteInWorld`, size, `rotateBy` | `util.StorageChunk` |
| `IPlanetaryProvider` | pos-based planet queries (gravity, atmo, temp, rotation period) | world providers |
| `IInfrastructure` | rocket-linkable tile: link/unlink rocket & mission, max distance, render | infrastructure tiles |
| `IMission` | async mission: progress, complete, originating dim, time remaining | mission subsystem |
| `IGravityManager` | set / clear / **read** a per-entity gravity multiplier. The read lets a dependent mod that set a multiplier ask what it is, and tell its own override from another mod's, or from none. It answers `OptionalDouble` and not a number with a 1.0 default — "pinned to earthlike whatever this dimension says" and "no override, the dimension decides" produce different motion, and a caller handed 1.0 for both cannot separate them `[V]` `IGravityManager.java:40`, `GravityHandler.java:194` (`GravityHandlerApiTest.twoHandlersDoNotShareTheirOverrides`, `GravityHandlerApiTest.java:44`, pins that each handler's overrides are its own) | gravity handler |
| (no `IAtmosphere` interface) | The type is the concrete `api/atmosphere/Atmosphere`. An interface invited a dependent mod to supply its own answers to "can this be breathed" and "will this burn", and nothing could check those answers against any gas; air is a composition and everything else about it is derived. A class cannot be told to lie, and there are no setters that let a pack flip a SHARED singleton's flags | atmosphere subsystem |
| `IAtmosphereSealHandler` | `isBlockSealed` + register (un)sealable blocks | seal handler |
| `IGalaxy` | dimension-manager facade: `canTravelTo`, `isDimensionCreated`, same-system test | `dimension.DimensionManager` |
| `IPartWear` / `CapabilityWear` | rocket-part wear capability (stage/maxStage/transition) | `tile.TileBrokenPart` |
| `IProtectiveArmor` / `IFillableArmor` / `CapabilitySpaceArmor` | space-suit protection + fill contract | armor items |
| `StellarBody` | star/black-hole node value object (owned, concrete) | — |
| `AreaBlob` / `IBlobHandler` | oxygen-region adjacency graph + host callback | atmosphere tiles |

## Mechanics

### MECH-API-22 — part-wear capability
`CapabilityWear` declares `Capability<IPartWear> PART_WEAR` via `@CapabilityInject`, registers a
**no-op `IStorage`** (the hosting `TileBrokenPart` persists the stage itself) with a
`DefaultPartWear` factory (`CapabilityWear:21-47`). `IPartWear` is pure state: stage 0 = pristine,
`getMaxStage()` = broken; consequence formulas live in consumers (`IPartWear:13-28`). Static `get(te)`
is the null-safe accessor (`:29-34`).

### MECH-API-23 — protective-armor capability
`CapabilitySpaceArmor` declares `Capability<IProtectiveArmor> PROTECTIVEARMOR`, registers a no-op
`IStorage`, and uses a lambda's `.getClass()` as the default-instance factory
(`CapabilitySpaceArmor:12-34`). `IProtectiveArmor.protectEntity(atmosphere, stack, commitProtection)`
is the single functional method; `IFillableArmor` adds fluid-fill/drain accessors for suit tanks.

### MECH-API-24 — area-blob graph
`AreaBlob` wraps a libVulpes `AdjacencyGraph<HashedBlockPosition>` and an `IBlobHandler` host.
`addBlock` inserts only if the handler `canFormBlob()` and links to existing neighbours
(`AreaBlob:63-67`). `removeBlock` deletes the node and then prunes any neighbour that can no longer
reach the handler's root position (`:138-149`) — flood-fill disconnection. `contains` is
`synchronized(graph)` because the atmosphere thread reads concurrently (`:102-109`). Max radius and
overlap rules delegate to the handler (`:42-44,:129-131`), as does
**whether the region's air is being MAINTAINED**: `IBlobHandler.isMaintainingAtmosphere()` is a
`default false` addition (source- and binary-compatible for dependent mods), answered by
`TileOxygenVent` and read by life support through the new `AreaBlob.getBlobHandler()`. The point of
routing it through the handler rather than inferring it from the blob's published data is INV-ATM-19:
an inference from a derived value cannot survive that value changing.

### MECH-API-25 — star colour from temperature
`StellarBody.getColor()` converts temperature (K) to an RGB float triple with a piecewise
black-body approximation; `setTemperature` caches it and `getColorRGB8()` packs it BGR into an int
(`StellarBody:143-212`). `getDisplayRadius = 100·size` (`:62-64`).

### MECH-API-26 — star NBT tree
`StellarBody.writeToNBT`/`readFromNBT` persist id, temperature, name, `posX/posZ` (as short),
`size`, `companionOrbit` (a LONG, 100 km units — the companion's orbit about its primary) and
`companionTheta` (radians), `isBlackHole`, `diskAngle`, and a recursive `subStars` list
(`StellarBody:380-440`; `companionOrbit` `:390, 422`). `addSubStar` auto-names children
`<parent>-N` and keeps the child's own id (`:92-99`) `[V]`. `getNumPlanets` delegates
to the root parent (`:120-124`).

### MECH-API-27 — seal-handler contract
`IAtmosphereSealHandler` exposes `isBlockSealed(world,pos)` plus `addSealableBlock` /
`addUnsealableBlock` (`IAtmosphereSealHandler:26-40`); the concrete handler is wired into
`StellurgyAPI.atomsphereSealHandler` and fed from config in `loadPostInit`.

## Invariants

- **INV-API-23 [V]** Both capabilities register a no-op `IStorage` returning `null`
  (`CapabilityWear:39-45`, `CapabilitySpaceArmor:22-33`); wear/armor state is persisted by the host
  tile/item, not by capability NBT — capability sync is not a save contract.
- **INV-API-24 [V]** `AreaBlob.contains` is synchronized on the graph; other mutators are not — reads
  are thread-safe against the atmosphere worker, writes are assumed single-threaded
  (`AreaBlob:102-109`).
- **INV-API-25 [V][SYS]** `StellarBody` stores `posX/posZ` as `short` (`:87-97,:233-234`) — star map
  coordinates are bounded to signed-16-bit; assigning >32767 wraps. FOR: public API: StellarBody coordinates.
- **INV-API-26 [V][SYS]** `getColorRGB8` packs bytes as `R | G<<8 | B<<16` (BGR order in the int)
  (`StellarBody:163`) — a rendering contract, not the usual `0xRRGGBB`. FOR: public API: StellarBody colour packing is a rendering contract.
- **INV-API-27 [V][SYS]** `StellarBody.readFromNBT` reads `diskAngle` with no `hasKey` guard (`:259`),
  overwriting the constructor default `70` (`:39`) with `0f` for any save written before the field
  existed — `NBTTagCompound.getFloat` returns `0` for an absent key. Legacy-save regression for the
  accretion-disk render. FOR: save format: StellarBody NBT.

## State & persistence (C1)

StellarBody keys: `id`,`temperature`,`name`,`posX`,`posZ`,`size`,`seperation`,`isBlackHole`,
`diskAngle`,`subStars` (`StellarBody:229-249`). `size`/`seperation` are the only guarded reads.
Capabilities: no NBT (INV-API-23).

## Integration seams (C5)

- Capabilities: `PART_WEAR` (`IPartWear`), `PROTECTIVEARMOR` (`IProtectiveArmor`) — registered in
  `CapabilityWear.register()` / `CapabilitySpaceArmor.register()`.
- Interface handles published via `StellurgyAPI` (see registries.md): `IGalaxy`,
  `IGravityManager`, `IAtmosphereSealHandler`, `ISpaceObjectManager`.
- `IInfrastructure`/`IMission` are the rocket↔ground-support linking contracts consumed by
  `EntityRocketBase` (rocket-stats.md, MECH-API-11).

## Config surface

`enableGravityController` (gates `IGravityManager` machine), seal-handler block lists from
`sealableBlockWhiteList`/`sealableBlockBlackList`/`torchBlocks` (loadPostInit). No config gates the
capabilities or `StellarBody`.

## Open questions

- `IProtectiveArmor` default instance is derived from a lambda's runtime class
  (`CapabilitySpaceArmor:33`) — confirm Forge accepts a lambda-class default factory across
  dev/prod (obfuscation) or whether it silently yields no default. (unconfirmed) (mixin/coremod
  class-identity class).
