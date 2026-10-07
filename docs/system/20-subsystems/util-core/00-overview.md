---
id: util-core
owns: [util/, dimension/TerraformingRecord.java]
entrypoints: [XMLPlanetLoader#loadPlanetsOrThrow, WeightEngine#getWeight, TerraformingHelper#TerraformingHelper, BiomeHandler#terraform, SealableBlockHandler#isBlockSealed, GravityHandler#applyGravity, NBTHelper#writeCollection]
depends-on: [dimension-planets, api-public, world-gen, network-wire, atmosphere-oxygen, rocket-assembly, space-stations]
depended-by: [rocket-entity, rocket-assembly, multiblock-machines, world-gen, dimension-planets, atmosphere-oxygen, satellite, mission, client-render, items, blocks]
contracts: [C1, C2, C3, C4, C5]
confidence: high
---

## Purpose

`util/` is the mod's grab-bag of stateless helpers and small value/state carriers that
no single gameplay subsystem owns: the block/fluid mass model, the XML config parsers
that build the planet/ore/asteroid data at boot, the terraforming laser's chunk state
machine, air-sealing detection, orbital/gravity math, and a functional NBT
(de)serialization toolkit. It is a leaf-ish dependency: almost every other subsystem
calls into it, and it in turn reaches into `dimension`, `api`, `world` and `network`.

## Responsibility boundary

- **Owns**: mass resolution (`WeightEngine`), the boot-time XML loaders and the value
  objects they emit (`OreGenProperties`, `Asteroid`,
  `SpawnListEntryNBT`), the terraforming/decoration chunk pipeline
  (`TerraformingHelper`, `BiomeHandler`, `chunkdata`), atmosphere-seal detection
  (`SealableBlockHandler`) and the flood-fill blob (`AtmosphereBlob`), orbital/gravity/
  travel math (`AstronomicalBodyHelper`, `GravityHandler`, `PlanetaryTravelHelper`,
  `SpacePosition`, `SpaceTravelInteraction`), collision against a moving structure
  (`MobileAABB`), suit-air NBT accessors (`ItemAirUtils`), inventory-bypass bookkeeping
  (`RocketInventoryHelper`, `RocketGuiNavigation`), machine-recipe registration
  (`RecipeHandler`), sound registration (`AudioRegistry`), the `util/nbt` toolkit, and a
  handful of interfaces (`IWeighted`, `IBreakable`, `IBrokenPartBlock`, `IDataInventory`,
  `ITilePlanetSystemSelectable`) and value carriers (`DimensionBlockPosition`,
  `StationLandingLocation`, `TransitionEntity`, `NBTStorableListList`).
- **Does NOT own**: `StorageChunk` (→ `rocket-assembly`, even though `MobileAABB` and
  `NBTHelper` reference it); the `DimensionProperties`/`DimensionManager` data it reads
  (→ `dimension-planets`); the terraforming *tile* that drives the pipeline
  (`TileTerraforming` → `multiblock-machines`); the `AreaBlob`/`AtmosphereHandler` base
  and registry (→ `api-public` / `atmosphere-oxygen`); `StellurgyConfiguration` itself
  (→ `api-public`).

## Mechanic index

Anchors use a per-cluster abbreviation (the subsystem is split `2L`); numbering is
permanent within each cluster.

| cluster | doc | mechanics | key types |
|---------|-----|-----------|-----------|
| Mass / weight | [weight-mass.md](./weight-mass.md) | MECH-WGT-01..06 | `WeightEngine`, `IWeighted` |

**Lives here, owned elsewhere**: `util/NuclearEngineLimit` sits in this package
but is a rocket-assembly mechanic — see [rocket-assembly.md](../rocket-assembly.md) MECH-RASM-10.
It is in `util/` only because all three of its callers are, and none of them owns the other two.

**Not here — the flight recorder.** `MotionTrace` (motion smoothness
across four clocks) is a DIAGNOSTIC, not a util mechanic, and it does not live in `src/main` at all:
it is `src/test/java/dev/stannismod/stellurgy/test/trace/MotionTrace.java`, one instance per side held
by that side's `SideTrace` (an instance field of `Minecraft` / `MinecraftServer` placed by a test
mixin), so a released jar carries no recorder. Every sample is written by a test mixin — the physics
channel by `MixinPhysicsCalculationsMotionSample`, never by production code in the flight-computer
mixin. Its reading is owned by [stellurgytest-probe-catalog.md](../stellurgytest-probe-catalog.md) and
by the harness. Ship lifecycle announcements are
`ship_lifecycle` event-log records, written by the test source set's `ServerEventRecorder`; production keeps no recorder for them.
| XML config loaders | [xml-loaders.md](./xml-loaders.md) | MECH-XML-01..07 | `XMLPlanetLoader`, `XMLOreLoader`, `XMLAsteroidLoader`, `OreGenProperties`, `Asteroid`, `SpawnListEntryNBT` |
| Terraforming pipeline | [terraforming.md](./terraforming.md) | MECH-TERRA-01..07 | `TerraformingHelper`, `BiomeHandler`, `chunkdata` |
| Sealing & atmosphere blob | [sealing-atmosphere.md](./sealing-atmosphere.md) | MECH-SEAL-01..05 | `SealableBlockHandler`, `AtmosphereBlob` |
| Space navigation & physics | [space-nav.md](./space-nav.md) | MECH-NAV-01..09 | `SpacePosition`, `AstronomicalBodyHelper`, `GravityHandler`, `PlanetaryTravelHelper`, `SpaceTravelInteraction`, `MobileAABB`, `StationLandingLocation`, `DimensionBlockPosition`, `TransitionEntity` |
| NBT toolkit | [nbt-helpers.md](./nbt-helpers.md) | MECH-NBT-01..05 | `NBTHelper`, `NBTTagCompoundBuilder`, `NBTTagListCollector`, `Factory`, `ParametrizedFactory`, `NBTStorableListList` |
| Misc helpers | [misc-helpers.md](./misc-helpers.md) | MECH-HLP-01..07 | `ItemAirUtils`, `InventoryUtil`, `RocketInventoryHelper`, `RocketGuiNavigation`, `RecipeHandler`, `GraphicsHelper`, `AudioRegistry` + interfaces |

## Dependency edges (outbound)

- **→ dimension-planets**: `WeightEngine`/`XML*`/`Terra*`/`Nav*` all read
  `DimensionManager`, `DimensionProperties`, `StellarBody`. XML loaders *build* the
  `DimensionProperties` instances (see `dimension-planets`).
- **→ api-public**: `StellurgyConfiguration.getCurrentConfig()` (11 flags, C4), `AreaBlob`,
  `IGravityManager`/`IPlanetaryProvider`, `IFillableArmor`, `StellurgyBlocks`.
- **→ world-gen**: `TerraformingHelper` constructs `ChunkProviderPlanet` /
  `ChunkManagerPlanet` to re-derive target terrain.
- **→ network-wire**: `BiomeHandler`→`PacketBiomeIDChange`, `AtmosphereBlob`→
  `PacketAirParticle` (C2).
- **→ atmosphere-oxygen**: `AtmosphereBlob` extends `AreaBlob`, calls `AtmosphereHandler`.
- **→ rocket-assembly**: `MobileAABB.chunk` / `NBTHelper.readTileEntity` touch
  `StorageChunk`.

## State & persistence (summary — see C1/C4)

- `WeightEngine` persists `config/advRocketry/weights.json` (its own Gson file, **not**
  world NBT).
- `SpacePosition` round-trips a `spacePosition` NBT sub-compound (keys `x y z yaw pitch
  roll star world isInInterplanetarySpace`).
- `NBTStorableListList` round-trips a `list` tag of `{loc:int[3], dim:int}` compounds.
- `ItemAirUtils` reads/writes the `air` int key on an ItemStack tag compound.
- `NBTHelper`/`NBTTagCompoundBuilder` define the AABB wire shape (`minX..maxZ` doubles)
  and blockstate shape (`name` string + `meta` short) reused mod-wide.
- Everything else in `util/` is transient (per-run caches, request-scoped math).

## Config surface

Config: see `C4-config-surface`. Per-mechanic disable paths are in each sub-doc's Config surface section.

## Failure modes & edge cases

Edge cases (details in each sub-doc):
AudioRegistry registers only 1 of 15 declared sounds; (the `AtmosphereBlob` pool is per-server state, not a static-init that could skew from runtime config, see
[sealing-atmosphere.md](./sealing-atmosphere.md)); `MobileAABB` horizontal-collision disabled; `SpacePosition`
dead writes during read; `XMLAsteroidLoader` un-guarded `getFirstChild()` and dropped
stack `size`.
