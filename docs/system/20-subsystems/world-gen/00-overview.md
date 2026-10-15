---
id: world-gen
owns: [world/]
entrypoints: [WorldProviderPlanet#createChunkGenerator, ChunkProviderPlanet#generateChunk, ChunkProviderPlanet#populate, ChunkManagerPlanet#getBiomes, PlanetWeatherManager#wrapWorldInfoIfNeeded, WorldProviderPlanet#updateWeather]
depends-on: [dimension-planets, api-public, util-core, atmosphere-oxygen, mixins-asm-coremod]
depended-by: [client-render, commands-gameplay, space-stations]
contracts: [C1, C4, C5, C6]
confidence: high
---

## Purpose

The `world/` package turns a registered Stellurgy dimension into a living world: it maps
`DimensionProperties` (from `dimension-planets`) onto a chunk generator, a biome
provider, a set of terrain decorators (craters, geodes, volcanoes, alien trees),
an ore-gen pass, a `WorldProvider` (sky/gravity/atmosphere/celestial-angle hooks)
and — new in `feature/true_rcs`'s ancestor branches — a **per-dimension weather &
time-of-day** system that replaces vanilla's shared-overworld `WorldInfo`.

It is the bridge between "a dim id exists in the registry" and "the player is
standing on a Moon with the right stone, sky colour, gravity, craters and clear
weather."

## Responsibility boundary

**Owns**
- Chunk generation for planets, cave-planets, asteroids and empty space
  (`ChunkProvider*`).
- Biome selection per planet (`ChunkManagerPlanet`, `GenLayerBiomePlanet`,
  `GenLayerVoronoiExtended`, the 13 `BiomeGen*` classes).
- Terrain decoration: craters/geodes/volcanoes/crystals/trees/caves/ravines and
  the structure stubs (`MapGen*`, `WorldGen*`).
- Per-dimension ore generation driven by `OreGenProperties`
  (`CustomizableOreGen`) and the legacy global vanilla-ore pass (`OreGenerator`).
- The Stellurgy `WorldProvider` family and the two Stellurgy `WorldType`s.
- Per-dimension **weather + time-of-day** persistence and sync
  (`world/weather/*`), including the `WorldInfo` wrapper the Mixin installs.
- Off-world scratch worlds for rendering rocket contents (`world/util/*Dummy*`).

**Does NOT own**
- `DimensionProperties` / the planet registry — that is `dimension-planets`; this
  subsystem only *reads* it.
- `StellurgyConfiguration` flag definitions — `api-public`; read here.
- The Mixin classes (`MixinWorldServerMulti`, `MixinWorldServer`,
  `MixinPlayerList`) that call into `PlanetWeatherManager` — those live in
  `mixins-asm-coremod`; the wrap *policy and mechanism* live here.
- `StorageChunk` (the rocket block snapshot the dummy world renders) —
  `rocket-assembly`.
- Sky/planet *rendering* — `client-render`; providers only choose which renderer.

## Mechanic index

Each cluster is a sub-doc. Anchor prefix is **WGEN**.

| sub-doc | mechanics | one-liner |
|---------|-----------|-----------|
| [terrain.md](./terrain.md) | MECH-WGEN-01..08, 28..29 | provider→generator wiring, the four chunk generators, biome layer stack, world types, per-dimension generation identity, ore-gen passes |
| [decoration.md](./decoration.md) | MECH-WGEN-09..15 | crater/geode/volcano/crystal decorators, tree WorldGens, cave/ravine extensions, custom biomes, the Luna lander easter egg |
| [providers-weather.md](./providers-weather.md) | MECH-WGEN-16..24 | `WorldProviderPlanet` sky/gravity/atmosphere hooks; per-dim weather cycle; `WorldInfo` wrapper; saved-data; client sync; `/weather` redirect |
| [util-dummies.md](./util-dummies.md) | MECH-WGEN-25..27 | dummy off-world (rocket render), teleporters, `MultiData` scanner store |

## Dependency edges

- **reads** `DimensionManager.getDimensionProperties(dim)` in nearly every class —
  the single source for ocean/stone block, sea level, atmosphere density,
  crater/geode/volcano multipliers, biome list, rain/thunder markers, rotational
  period, gravity, star, sky colours.
- **reads** `StellurgyConfiguration.getCurrentConfig()` for gen toggles
  (`generateCraters/Geodes/Volcanos`, `generateVanillaStructures`), ore toggles,
  and the weather master switches (`perDimWorldInfo`, `enableCustomPlanetWeather`,
  `minAtmosphereDensityForRain`).
- **called by** `mixins-asm-coremod`: `MixinWorldServerMulti` → `wrapWorldInfoIfNeeded`,
  `MixinWorldServer` → `computeSleepWakeTime`, `MixinPlayerList` → weather sync.
- **called by** `client-render`: providers return `RenderPlanetarySky` /
  `RenderSpaceSky` / `RenderAsteroidSky` instances.
- **called by** `commands-gameplay`: `/stellurgy weather` mutates the
  per-dim `PlanetWeatherState`; `/stellurgytest worldgen-sample` reads chunk tops.

## Contracts touched

- **C1 (NBT)** — `PlanetWeatherSavedData` (`stellurgy_planet_weather`,
  key `dimensions`→per-dim compounds), `PlanetWeatherState`
  (`cleanWeatherTime/rainTime/thunderTime/raining/thundering/worldTime/worldTotalTime`),
  legacy read of `WorldInfoSavedData`/`clearWeatherTime`, `MultiData` (per
  `DataType` compound).
- **C4 (config)** — see each sub-doc's *Config surface*; consolidated set in
  [providers-weather.md](./providers-weather.md) and [terrain.md](./terrain.md).
- **C5 (events)** — `PlanetWeatherEventHandler` subscribes `WorldEvent.Load`,
  `CommandEvent`, three `PlayerEvent`s; `MapGenLander` subscribes
  `PopulateChunkEvent.Post`.
- **C6 (mixin/AT)** — `World.worldInfo` is widened to `public` by Stellurgy's access
  transformer so `PlanetWeatherManager` can assign the wrapper; the wrap is also
  driven from Mixins (owned by `mixins-asm-coremod`).

## Open questions

- Two decoration paths coexist: `ChunkProviderPlanet` runs crater/geode/volcano/
  swamp-tree itself, while crystal/pillar/ravine decorators are driven from the
  `BiomeGen*` `decorate()` overrides (e.g. `BiomeGenCrystal` →
  `MapGenLargeCrystal` + `WorldGenLargeCrystal`, `world/biome/BiomeGenCrystal.java:34-44`).
  Both are live; no orphans confirmed. Which biomes reach which decorator is
  documented per-biome in [decoration.md](./decoration.md).
