# world-gen · decoration & biomes

Parent: [00-overview.md](./00-overview.md). Covers the `MapGen*` terrain
decorators, the `WorldGen*` feature generators, cave/ravine extensions, the 13
custom biomes and the Luna lander easter egg. This is a catalogue-heavy cluster:
the shared pattern is described once, then tabulated.

## Shared pattern

All Stellurgy decorators extend vanilla `MapGenBase` and override `recursiveGenerate`
(or `generate`). The spawn test is uniform and idiosyncratic:

> `rand.nextInt(chancePerChunk) == Math.abs(chunkX) % chancePerChunk [&&|||]
> rand.nextInt(chancePerChunk) == Math.abs(chunkZ) % chancePerChunk`

i.e. a chunk "wins" when the RNG matches its coordinate's residue mod
`chancePerChunk`. `chancePerChunk` is derived from a per-planet frequency
multiplier by `ChunkProviderPlanet.frequencyMultiplierToChance` (MECH-WGEN-04);
its base values are `tunable`. Radii are perturbed by summed `sin` bumps
(`getRadius`, polar-coordinate wobble) so craters/volcanoes aren't perfect
circles. Ores placed by decorators come from `DimensionProperties.craterOres` /
`geodeOres` / `StellurgyConfiguration.standardGeodeOres`, resolved through the Ore
Dictionary at generate time.

Biomes extend vanilla `Biome`; Stellurgy ones override `decorate` /
`genTerrainBlocks` / `getRandomTreeFeature` / colour hooks and set
`topBlock`/`fillerBlock` to Stellurgy blocks. Trees are injected either through the
biome's `decorator.treesPerChunk` + `getRandomTreeFeature`, or hand-placed in
`decorate`.

## Mechanics

### MECH-WGEN-09 — crater family (small / medium / huge)
Three classes share the algorithm, differing only in `range` (scan radius) and
size weighting: `MapGenCraterSmall` (`range=2`), `MapGenCrater` (`range=9`,
`largeCraters` flag), `MapGenCraterHuge` (`range=52`, adds bounds-checked
`setBlockStateSafe`/`isValidPrimerY` for its 84-block radius)
(`decoration/MapGenCraterSmall.java:26-31`, `MapGenCrater.java:27-32`,
`MapGenCraterHuge.java:26-31,57-62`). The bowl is excavated to `Blocks.AIR` (or
back-filled with the detected fluid), a raised ridge of biome top-block + sparse
`craterOres` is thrown up, and large craters spray ejecta
(`MapGenCrater.java:99-160`). `shouldCraterSpawn` further filters by
`DimensionProperties.getCraterBiomeWeights()` (`:172-179`).

### MECH-WGEN-10 — volcano
`MapGenVolcano` builds a `blockBasalt` cone with an inner `blockEnrichedLavaFluid`
node, shaped by a `1/1.028^r` exponential profile plus a spherical lava bulb
(`decoration/MapGenVolcano.java:20-78`). Spawn test uses `&&` (both axes must
match) so volcanoes are rare.

### MECH-WGEN-11 — geode
`MapGenGeode` carves a hollow spheroid of `blocksGeode` shell with ore veins
hanging from the ceiling and rising from the floor; radius =
`geodeBaseSize ± geodeVariation/2` (`decoration/MapGenGeode.java:48-129`). Refuses
ocean/river/beach biomes (`canGeodeGenerate`, `:44-46`). Ore pool = ctor-time
`standardGeodeOres` **plus** per-dim `geodeOres` appended (deduped) on every
`recursiveGenerate` call (`:53-60`).

### MECH-WGEN-12 — large crystal (biome-driven)
`BiomeGenCrystal` runs `MapGenLargeCrystal` from its `genTerrainBlocks` override
when `x%16==0 && z%16==0`, planting a sheared `blockCrystal` spire on a
`PACKED_ICE` filler (`biome/BiomeGenCrystal.java:38-45`,
`decoration/MapGenLargeCrystal.java:29-45`, spire lean/height `tunable`).
`WorldGenLargeCrystal` is the sapling-grown counterpart.

### MECH-WGEN-13 — inverted pillar (ocean spires biome)
`BiomeGenOceanSpires` runs `MapGenInvertedPillar` (mossy/plain cobble + dirt)
from `genTerrainBlocks` at chunk corners
(`biome/BiomeGenOceanSpires.java:32,41`), and swaps the tree feature for a vanilla
`WorldGenShrub` (`:45-46`).

### MECH-WGEN-14 — trees & mushrooms
`WorldGenAlienTree` (20–29-block lightwood trunk, `gen/WorldGenAlienTree.java:30-31`)
for `BiomeGenAlienForest`; `WorldGenCharredTree` for storm/volcanic biomes;
`WorldGenElectricMushroom` hand-placed high in `BiomeGenStormland.decorate`
(`biome/BiomeGenStormland.java:33-40`); `WorldGenNoTree` is a null feature used to
suppress vanilla trees where `treesPerChunk>0` is needed for spacing but real
trees are unwanted (`biome/BiomeGenDeepSwamp.java:21,48`,
`BiomeGenAlienForest.java:18`). `MapGenSwampTree` pre-computes a canopy/root
`BlockPos→state` map once in its constructor and stamps it into `DeepSwamp`
chunks, deliberately run from the chunk provider (not the biome) to avoid tree
"walls" (`decoration/MapGenSwampTree.java:25-55`, comment `ChunkProviderPlanet.java:198-199`).

### MECH-WGEN-15 — cave / ravine extensions & structure stubs
`MapGenCaveExt` extends `MapGenCaves` to treat the planet's custom `fillerBlock`/
`oceanBlock` as carvable/oceanic (`decoration/MapGenCaveExt.java:26-33`);
`MapGenRavineExt` does the same for ravines; `MapGenHighCaves`/`MapGenMassiveRavine`
are the cave-planet variants. `MapGenSpaceVillage` extends vanilla `MapGenVillage`
with a fixed 32-chunk grid and an ocean-biome blacklist
(`decoration/MapGenSpaceVillage.java:13-50`). `MapGenSpaceStation.generateStation`
is a static block-stamper (concrete hub + piston arms), invoked externally.

### MECH-WGEN-15b — Luna lander easter egg
`MapGenLander` (`@SubscribeEvent PopulateChunkEvent.Post`, registered in
`Stellurgy.java:1145`) stamps a decorative iron/gold lander **only** when
the dimension name equals `"Luna"` and the populated column is exactly
`x==2347, z==67` (`decoration/MapGenLander.java:20`). Hardcoded name + absolute
coordinates.

## Biome catalogue

Shared: `spawnableMonster/CreatureList` cleared or reduced, decorator flowers/
grass/falls zeroed, Stellurgy `topBlock`/`fillerBlock`. One row per biome.

| biome | top / filler | decoration | notes |
|-------|--------------|------------|-------|
| `BiomeGenMoon` | `blockMoonTurf` | none | no spawns (`getSpawnableList` empty) |
| `BiomeGenMoonDark` | dark moon turf | none | darker variant |
| `BiomeGenCrystal` | `SNOW`/`PACKED_ICE` | MECH-WGEN-12 crystal spires | no mobs |
| `BiomeGenOceanSpires` | — | MECH-WGEN-13 inverted pillars | shrub trees |
| `BiomeGenAlienForest` | — | `WorldGenAlienTree` | lightwood forest |
| `BiomeGenDeepSwamp` | — | `MapGenSwampTree` (provider) | slimes; `WorldGenNoTree` spacing |
| `BiomeGenMarsh` | — | swamp-like | — |
| `BiomeGenStormland` | — | `WorldGenElectricMushroom` + `WorldGenCharredTree` | creepers only, `getSpawningChance()==0`, black sky |
| `BiomeGenVolcanic` | `blockBasalt` | `WorldGenCharredTree` | creepers |
| `BiomeGenBarrenVolcanic` | basalt-ish | sparse | — |
| `BiomeGenHotDryRock` | rock | none | minimal |
| `BiomeGenSpace` | — | none | used off-world |
| `StellurgyBiomes.spaceBiome` (via `BiomeGenSpace`) | — | none | asteroid/space provider single biome |

## State & persistence

None. All decoration is deterministic from world seed + chunk coords +
`DimensionProperties`. No NBT owned by this cluster.

## Invariants

- **INV-WGEN-07 [V][BEH]** Crater/volcano/geode ore selection is read from
  `DimensionProperties` + Ore Dictionary at generate time, so a planet with an
  empty `craterOres`/`geodeOres` list still generates the landform, just without
  ore inlays (`MapGenCrater.java:62-67,163-169`; `MapGenGeode.java:53-60`).
- **INV-WGEN-08 [V][BEH]** `MapGenGeode` never generates over ocean/river/beach biomes
  (`MapGenGeode.java:44-46,62`).
- **INV-WGEN-09 [V][BEH]** `MapGenLander` fires for exactly one column on exactly the
  dimension literally named `"Luna"` (`MapGenLander.java:20`).
- **INV-WGEN-10 [V]** `MapGenCraterHuge` bounds-checks every Y write via
  `isValidPrimerY` (`MapGenCraterHuge.java:57-62`) — the small/medium crater
  classes rely on their own `y<255` inline checks and do **not** share that
  helper (`MapGenCrater.java:129,148`).

## Failure modes & edge cases

- `MapGenCrater.recursiveGenerate` gates the biome-weight check
  with `&&` bound tighter than `||`, so `shouldCraterSpawn` is bypassed for the
  X-axis branch (`MapGenCrater.java:68`). Craters ignore biome weights ~half the
  time.
- `MapGenGeode`/`MapGenCrater` resolve ores via `OreDictionary.getOres(s).get(0)`
  with a `doesOreNameExist` filter but no empty-list guard on the `.get(0)`; a
  registered-but-empty ore name would `IndexOutOfBounds` (`MapGenGeode.java:56`,
  `MapGenCrater.java:64`). Low likelihood.
- Trees run from `ChunkProviderPlanet` (swamp) execute during primer build, before
  the chunk exists — deliberate, but means they cannot query neighbouring chunks.

## Config surface

Config: see `C4-config-surface`. The crater, geode and volcano generators are nulled upstream when their flag is off (terrain MECH-WGEN-04).

## Test coverage

- No direct decoration unit tests found; landform presence is exercised
  indirectly via `server/WorldgenDeterminismAndSamplingTest` chunk sampling.
- Ore-inlay behaviour overlaps `unit/OreGenPropertiesTest`.

## Open questions

- `BiomeGenMarsh`, `BiomeGenHotDryRock`, `BiomeGenBarrenVolcanic`,
  `BiomeGenMoonDark` decorate paths not deep-read — assumed to follow the shared
  pattern (colour + spawn tweaks, no custom decorator). Confirm before relying on
  the catalogue rows for those four.
- `MapGenSpaceStation.generateStation` has no caller inside `world/`; invoked from
  elsewhere (station assembly?) — ownership of the call site is outside this
  subsystem.
