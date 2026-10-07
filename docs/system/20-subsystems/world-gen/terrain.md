# world-gen · terrain generation

Parent: [00-overview.md](./00-overview.md). Covers the provider→generator wiring,
the four chunk generators, the biome-layer stack, world types and the two
ore-gen passes.

## Key types

| class | role |
|-------|------|
| `WorldProviderPlanet` | Stellurgy planet `WorldProvider`; picks generator by `genType`, installs `ChunkManagerPlanet` biome provider (weather hooks in [providers-weather.md](./providers-weather.md)) |
| `WorldProviderAsteroid` | subclass → `ChunkProviderAsteroids`, single space-biome, celestial angle fixed 0.2 |
| `WorldProviderSpace` | subclass → `ChunkProviderSpace`, atmosphere 0, sky from `SpaceObject`/warp state |
| `ChunkProviderPlanet` | main overworld-style generator: perlin heightmap, biome-block replace, structures, decorators, ore populate |
| `ChunkProviderCavePlanet` | `genType==1`: Nether-style solid world with a carved cave layer, mirrored 16→128 |
| `ChunkProviderAsteroids` | floating-island simplex generator, two stacked passes (y=0 and y=100) |
| `ChunkProviderSpace` | empty chunks, all `spaceBiome` |
| `ChunkManagerPlanet` | `BiomeProvider`: builds the GenLayer stack from `DimensionProperties`, feeds `GenLayerBiomePlanet` |
| `GenLayerBiomePlanet` | terminal biome layer; weighted pick from the planet's `List<BiomeEntry>` |
| `GenLayerVoronoiExtended` | voronoi zoom variant (unused-parent reference copy) |
| `WorldTypePlanetGen` / `WorldTypeSpace` | Stellurgy `WorldType`s; `canBeCreated()==false` (not user-selectable) |
| `CustomizableOreGen` | per-`OreEntry` ore placement, stone-block aware predicate |
| `OreGenerator` | legacy global vanilla-material ore pass (copper/tin/…/dilithium) via `IWorldGenerator` |

## Mechanics

### MECH-WGEN-01 — provider selects generator by genType
`WorldProviderPlanet#createChunkGenerator` reads
`DimensionProperties.getGenType()`: `1` → `ChunkProviderCavePlanet`, otherwise →
`ChunkProviderPlanet` (`world/provider/WorldProviderPlanet.java:59-65`). `getHorizon`
returns `0` for `genType==2` else `63` (`:472-478`). The two Stellurgy subclass providers
hard-wire their own generators: asteroid (`WorldProviderAsteroid.java:29-31`),
space (`WorldProviderSpace.java:49-51`). `WorldTypePlanetGen#getChunkGenerator`
also builds a `ChunkProviderPlanet` for the (non-user-creatable) world type
(`world/type/WorldTypePlanetGen.java:26-28`).

### MECH-WGEN-02 — planet heightmap → block fill
`ChunkProviderPlanet` seeds seven vanilla `NoiseGenerator*` from the world seed,
routed through Forge's `InitNoiseGensEvent.ContextOverworld`
(`ChunkProviderPlanet.java:99-152`). `generateHeightmap` blends per-biome base
height / variation with a depth-noise term into an 825-entry map, scaled by
`heightmapMult` (`:391-490`). `setBlocksInChunk` interpolates that map into stone
(`fillblock`) below the density surface and `oceanBlock` below sea level
(`:207-273`). Ocean/stone blocks come from `DimensionProperties` (fallback water/
stone); sea level from `dimProps.getSeaLevel()` (`:120-140`).

### MECH-WGEN-03 — biome-driven surface + structure/decorator pipeline
`getChunkPrimer` runs, in order: biome-block replace (`replaceBiomeBlocks`), caves,
ravines, craters (small/med/huge), volcano, geode, always the swamp-tree pass,
then — only if `mapFeaturesEnabled && habitable && !is_terraforming` — vanilla
mineshaft/village/stronghold/temple/monument (`ChunkProviderPlanet.java:288-358`).
`mapFeaturesEnabled = dimProps.canGenerateStructures() && cfg.generateVanillaStructures`
(`:154`); `habitable = dimProps.getAtmosphere().isBreathable()` (`:158`). The
`bp != null` overload path sets `is_terraforming=true`, which suppresses craters
and structures so terraforming a chunk doesn't re-scar it (`:295-337`).

### MECH-WGEN-04 — decorator enablement gated by atmosphere density
Crater generators are chosen by band in the constructor: small when
`atmDensity<=0.05`, medium when `<2`, huge only when `==0`; each also requires
`cfg.generateCraters && dimProps.canGenerateCraters()`
(`ChunkProviderPlanet.java:161-178`). Geodes require `canGenerateGeodes() &&
cfg.generateGeodes` (`:180-184`); volcanoes `canGenerateVolcanos() &&
cfg.generateVolcanos` (`:186-191`). Caves+ravines are nulled when
`!dimProps.canGenerateCaves()` (`:193-196`). `frequencyMultiplierToChance` folds a
per-planet multiplier into a `max(1, round(base/mult))` chance (`:203-205`,
`base` values `tunable`).

### MECH-WGEN-05 — populate: lakes off, dungeons/ice/animals + ore
`populate` reseeds RNG per chunk from the world seed, fires
`onChunkPopulate` pre/post, generates structures, then dungeons (guarded by
`habitable`), biome decoration, animal spawning and ice/snow, and finally the
ore pass (`ChunkProviderPlanet.java:496-595`). Water- and lava-lake blocks are
dead-gated behind `if (false && …)` (`:533,541`) — vanilla lakes never spawn on
planets.

### MECH-WGEN-06 — cave-planet: mirrored solid world with carved caves
`ChunkProviderCavePlanet#generateChunk` takes the base planet primer, copies rows
16..127 up to 128..239 to build a thick roof (`ChunkProviderCavePlanet.java:236-240`),
then `prepareHeights` fills a Nether-style density field, `buildSurfaces` lays
filler/ocean bands, and three carvers run: `MapGenCavesHell`, `MapGenHighCaves`,
`MapGenMassiveRavine` (`:242-246`). Sets `chunk.setLightPopulated(true)` (`:256`).

### MECH-WGEN-07 — asteroid generator: simplex islands, two stacked passes
`ChunkProviderAsteroids#generateChunk` calls `prepareHeights` twice — at yOffset 0
and yOffset 100 with a +500,+500 coordinate shift — producing two independent
island fields (`ChunkProviderAsteroids.java:269-288`). `getIslandHeightValue`
seeds sparse island centres from a `NoiseGeneratorSimplex` threshold and returns a
falloff radius (`:224-264`). `populate` runs only the ore pass (no structures,
`:323-336`). Space (`ChunkProviderSpace`) returns fully empty chunks tagged with
`spaceBiome` (`ChunkProviderSpace.java:26-40`).

### MECH-WGEN-08 — biome layer stack per planet
`ChunkManagerPlanet` builds a near-vanilla GenLayer chain
(`initializeAllBiomeGenerators`), but branches river layers on
`DimensionProperties.hasRivers()` (`ChunkManagerPlanet.java:73-171`) and injects
`GenLayerBiomePlanet` via `WorldTypePlanetGen#getBiomeLayer`
(`world/type/WorldTypePlanetGen.java:43-52`). `GenLayerBiomePlanet` does a
`WeightedRandom` pick over the planet's `List<BiomeEntry>`; empty list → 100 %
`OCEAN` (`world/GenLayerBiomePlanet.java:58-66`). The provider assigns the layers
back into the vanilla `BiomeProvider` private fields by reflection
(`ChunkManagerPlanet.java:42-43`).

### MECH-WGEN-28 — a dimension publishes its OWN generation identity
AStellurgy dimension carries `StellurgyPlanetWorldInfo`, a `DerivedWorldInfo` subclass installed in place of
vanilla's at `WorldProvider.setWorld` HEAD (`mixin/MixinWorldProvider.java:31-34`,
`world/StellurgyPlanetWorldInfo.java:57-73`). It overrides exactly two answers, both read live from
`DimensionProperties`: `getTerrainType()` — the foreign `WorldType` for a valid `MOD_WORLDTYPE`,
else Stellurgy's own `planetWorldType` — and `getGeneratorOptions()` → `terrainGeneratorOptions`
(`:75-97`). Everything else delegates as before. [V]

The install point is load-bearing: `WorldServer`'s constructor calls `provider.setWorld(this)` and
then `createChunkProvider()`, and `WorldProvider.setWorld` caches both values into private fields
before calling `init()` — so an info swapped in at `WorldEvent.Load` (as the weather wrapper is)
never reaches the biome provider or the chunk generator. The install is deliberately NOT gated by
`perDimWorldInfo`: that flag governs weather and time, not which terrain a planet generates. [V]

The publish channel matters because Stellurgy cannot patch a foreign generator's read sites
— vanilla's own `WorldType.FLAT` biome provider re-reads `WorldInfo.getGeneratorOptions()` rather
than using the argument it was handed. It also reaches the CLIENT for free: `SPacketRespawn` carries
`worldserver.getWorldInfo().getTerrainType()` (`PlayerList.java:656`). [T]
`WorldCommandClientGroupTest#stellurgyGotoMakesTheClientRenderThePlanetsOwnWorldType`

### MECH-WGEN-29 — one resolution, two readers
`TerrainResolution.of(dim, props)` (`dimension/TerrainResolution.java:45-63`) is the single answer to
"what does this planet actually generate with", after the fallbacks: a `MOD_WORLDTYPE` naming an
unregistered world type, or a `TEMPLATE` with no path, degrades to `NATIVE` with one warning per
dimension. Both `WorldProviderPlanet#resolveTerrainSource` and `StellurgyPlanetWorldInfo#getTerrainType`
go through it, so the generator a planet runs and the identity it publishes cannot disagree. [V]

### MECH-WGEN-30 — where a PROCEDURAL planet's terrain source comes from
For an authored planet the `terrainSource` / world type / options / `genType` are XML. For a
procedural one they are **drawn from its planet TYPE** and then FIXED: `PlanetTypes.drawTerrain`
picks one of the type's weighted `<gen>` entries — after dropping any naming a `WorldType` this modset
does not have — and `PlanetRealizer` writes the four fields into the new `DimensionProperties` once,
at realization. From that moment the save owns them: a pack that later adds or removes a world
generator cannot reshape ground somebody has already walked on. [V]

Two consequences for this cluster, both of which it already handles: the world type on a procedural
planet may be a foreign one nobody authored, and it reaches `TerrainResolution` (MECH-WGEN-29) through
exactly the same fields an XML planet uses — there is no second path. The availability filter runs at
DRAW time, so `TerrainResolution`'s own fallback stays the second line of defence (a mod removed
after realization), not the first. Owned by universe-model §4; recorded here because this cluster is
what consumes the result.

## State & persistence

- No NBT owned by this cluster. Biome/height output is derived deterministically
  from `world.getSeed()` + `DimensionProperties`; nothing is persisted here beyond
  vanilla chunk storage.
- `WorldProviderPlanet#getSeed` returns `super.getSeed() + getDimension()`
  (`WorldProviderPlanet.java:82-85`) — the per-dim seed offset is a **contract**:
  changing it re-rolls every existing planet's terrain.
- `getSaveFolder` returns `"advRocketry/"+super` (`:556-559`) — planet region
  files live under that prefix.

## Invariants

- **INV-WGEN-01 [V]** genType 1 ⇒ cave generator, else the standard planet
  generator; no other value produces a distinct generator
  (`WorldProviderPlanet.java:59-65`).
- **INV-WGEN-02 [V]** Terraforming a chunk (biome-provider overload) never runs
  crater or vanilla-structure generation (`ChunkProviderPlanet.java:319-337`,
  `is_terraforming` guard).
- **INV-WGEN-03 [V]** Crater tier is a total function of atmosphere density:
  none>0.05 huge, ==0 huge-enabled, <0.05 small, <2 medium
  (`ChunkProviderPlanet.java:161-178`).
- **INV-WGEN-04 [V]** An empty planet biome list yields all-ocean, never a crash
  (`GenLayerBiomePlanet.java:59-60`).
- **INV-WGEN-05 [V]** Sampling the same chunk twice on a dim returns the same top
  block and biome (deterministic gen): chunk generation re-seeds its `Random` from the chunk
  coordinates before filling (`ChunkProviderPlanet.java:293`). Distinct chunks are independently addressable: still `[T]`,
  `WorldgenDeterminismAndSamplingTest#differentChunksReturnIndependentlyAddressableData:64-65`.
- **INV-WGEN-22 [T]** A planet's published world type is its own, never the save's: a `NATIVE`
  planet answers `PlanetGen` and a `MOD_WORLDTYPE` planet answers the foreign type it runs, and the
  authored options string is what its generator was built from —
  `server/PlanetTerrainSourceTest#planetPublishesItsOwnWorldTypeThroughWorldInfo`,
  `#modWorldtypePlanetConfiguresItsForeignGeneratorFromItsOwnOptions`.
- **INV-WGEN-06 [A]** `ChunkProviderPlanet.settings` is non-null by the time
  `getChunkPrimer` dereferences it (`this.settings.useCaves`,
  `ChunkProviderPlanet.java:311`). It is only assigned when the generator-options
  string is non-null (`:118-119`); an actually-null options string would NPE. In
  practice the options string is never null: it now comes from
  `DimensionProperties.terrainGeneratorOptions`, whose setter maps null to `""`
  (MECH-WGEN-28). Unconfirmed for all callers.

## Failure modes & edge cases

- `GenLayerBiomePlanet.biomeEntries` is NOT static: it is a `final` instance
  field passed at construction (`GenLayerBiomePlanet.java:19-25`).
  `ChunkManagerPlanet` builds each planet's layer stack with that planet's own biome list
  (`ChunkManagerPlanet.java:124`), so two Stellurgy planets — or the client's and the integrated
  server's managers of one planet — never read each other's list; cross-contamination cannot occur.
- `ChunkProviderPlanet.generateChunk2` (`:378-389`) is dead duplicate of
  `generateChunk`.
- `OreGenerator.dilithiumTargetOre` static field is declared but never assigned
  or read (`world/ore/OreGenerator.java:28`).
- Vanilla lakes are permanently disabled by `if (false && …)`
  (`ChunkProviderPlanet.java:533,541`).

## Config surface

Config: see `C4-config-surface`. `generateVanillaStructures` false ⇒ `mapFeaturesEnabled` false (no mineshaft/village/stronghold/temple/monument); each `generate*` flag false ⇒ that generator is null or that material's global ore pass is skipped (`OreGenerator.java:50-80`).

## Test coverage

- INV-WGEN-05 → `[V]` for same-chunk determinism (no test pins it); distinct
  chunks → `server/WorldgenDeterminismAndSamplingTest.java:64-65`.
- ore counting → `unit/OreGenPropertiesTest.java`.
- Planet dim load / generator wiring → `server/PlanetDimensionLoadTest.java`.
- INV-WGEN-22 / MECH-WGEN-28 → `server/PlanetTerrainSourceTest.java`,
  `client/WorldCommandClientGroupTest#stellurgyGotoMakesTheClientRenderThePlanetsOwnWorldType`.

## Open questions

- `GenLayerVoronoiExtended` (`world/gen/GenLayerVoronoiExtended.java`) has no
  caller inside `world/` — `ChunkManagerPlanet` uses vanilla `GenLayerVoronoiZoom`
  (`:166`). Possibly dead / retained for reference.
- `heightmapMult *= 1D` and `heightmapOffset = 0` in the cave/asteroid
  constructors are no-ops — was a per-planet vertical scale intended?
