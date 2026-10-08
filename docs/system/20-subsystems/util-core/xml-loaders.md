# util-core / XML config loaders

Parent: [00-overview.md](./00-overview.md) · Files: `XMLPlanetLoader.java` (1213),
`XMLOreLoader.java` (328), `XMLAsteroidLoader.java` (235), `OreGenProperties.java` (94),
`Asteroid.java` (141), `SpawnListEntryNBT.java` (50)

Boot-time parsers that turn the mod's XML/DOM config files into in-memory data objects:
the galaxy/planet tree, per-atmosphere ore tables, and asteroid-field definitions. All
three loaders share a `loadFile(File)→boolean` + DOM-walk + `loadPropertyFile()`/
`readAllPlanets()` shape; each attribute is parsed defensively (bad number ⇒ warn + skip
node, never abort the whole file).

## Key types

| type | role |
|------|------|
| `XMLPlanetLoader` | parses `planetDefs.xml` → `DimensionPropertyCoupling{stars,dims}`; also `writeXML` for round-tripping |
| `XMLOreLoader` | parses ore config → ordered `List<SingleEntry<HashedBlockPosition(pressure,temp,0), OreGenProperties>>` |
| `XMLAsteroidLoader` | parses asteroid config → `List<Asteroid>` |
| `OreGenProperties` | list of `OreEntry(state,minH,maxH,clumpSize,chancePerChunk)` + static `[pressure][temp]` lookup grid |
| `Asteroid` | one asteroid-field def; `getHarvest(seed,uncertainty)` seeds RNG → weighted ore yield |
| `SpawnListEntryNBT` | vanilla `SpawnListEntry` + optional NBT applied on `newInstance` |

## Mechanics

- **MECH-XML-01 — planet load entrypoint.** `loadPlanetsOrThrow(File, DimensionManager into)` =
  `loadFile` then `readAllPlanets(into)`; a structural failure (unparseable XML, missing `<galaxy>`
  root) throws `RuntimeException` (→ Forge crash report) rather than killing the JVM.
  `XMLPlanetLoader.java:2121`. `into` is the galaxy the file is read FOR — it allocates unstated ids
  and keeps the `isKnown` bodies; the loader reaches no running server's galaxy, and production passes
  the server's own manager (`DimensionManager.java:914`). [V] The read path never asks
  `DimensionManager.getInstance()`, so a read needs no running server.
- **MECH-XML-02 — per-planet fault isolation.** `readAllPlanets` walks `<galaxy>`→
  `<star>`→(`<planet>`|nested `<star>` substar); each planet parse is wrapped in
  try/catch so one malformed `<planet>` (e.g. an ore from an uninstalled mod) is
  logged-and-skipped, not fatal. `XMLPlanetLoader.java:1135-1183`.
- **MECH-XML-03 — dim-id allocation** (contract: [C29](../../30-contracts/C29-dimension-ids.md)
  DIMID-2/3). Before any body is read, `readAllPlanets` collects every `DIMID` a `<planet>`
  states, at any depth (`claimStatedDims`, `XMLPlanetLoader.java:1093`, called `:1842`). A body
  that states an id takes it; only a body that states none is allocated one
  (`XMLPlanetLoader.java:1133`), by `allocateUnstatedDim` (`:1116`): `getNextFreeDim(offset)`,
  stepped past every stated id and every id this parse already gave out. `offset` is still seeded
  from the `dimOffset` of the galaxy read into (`XMLPlanetLoader.java:2035`) and advanced per body, so ids stay contiguous where nothing is
  stated in between. [V] No body is allocated at parse time before the
  file's later `DIMID`s are known.
- **MECH-XML-04 — ore-table keying.** `XMLOreLoader.loadPropertyFile` requires root
  `<oreconfig>`; each `<oreGen>` must declare at least one of `pressure`/`temp` (clamped
  to `AtmosphereTypes`/`Temps` ranges); emits a `SingleEntry` keyed by
  `(pressure, temp)` with `-1` meaning wildcard. Order MUST be preserved (LinkedList).
  `XMLOreLoader.java:247-327`.
- **MECH-XML-05 — ore entry parse.** `loadOre` reads `block`(required)/`meta`/`minHeight`
  /`maxHeight`/`clumpSize`/`chancePerChunk`; heights clamped (`minHeight≥1`, `maxHeight`
  into [min,255], clump/chance into [1,255]); unknown block name ⇒ warn+skip; returns
  null if no valid entries. `XMLOreLoader.java:33-148`.
- **MECH-XML-06 — asteroid harvest RNG.** `Asteroid.getHarvest(seed,uncertainty)` seeds a
  shared static `Random` with `seed`, rolls mass (± `massVariability`), ore count
  (`richness`± `richnessVariability`), then distributes `numOres` across `itemStacks` by
  normalised `stackProbabilities`; deterministic for a given seed. `Asteroid.java:61-132`.
- **MECH-XML-07 — spawn-entry NBT overlay.** `SpawnListEntryNBT.newInstance` builds the
  vanilla entity, merges the parsed NBT tag over its written NBT, re-reads, and preserves
  the original UUID. `SpawnListEntryNBT.java:38-49`; NBT string parsed lazily via
  `JsonToNBT` in `setNbt`. `SpawnListEntryNBT.java:25-32`.

## State & persistence

These build persisted `DimensionProperties` (owned by `dimension-planets`) but hold no
world NBT themselves. `OreGenProperties` keeps a process-wide static
`[AtmosphereTypes][Temps]` grid populated by the integration data-loaders.
`Asteroid.rand` is a **shared static** `Random` (reseeded per `getHarvest` call).

## Invariants

- **INV-XML-01 [A][BEH]** A malformed planet definition is skipped, not fatal; the rest of the
  galaxy still loads. `test/server/PlanetDefsFaultToleranceTest.java`,
  `test/integration/XMLPlanetLoaderTest.java`, `test/unit/XMLPlanetLoaderTest.java`. Pinned by `PlanetDefsFaultToleranceTest#serverBootsWithMalformedPlanetSkipped`.
- **INV-XML-02 [V][SYS]** `readAllPlanets` throws if `<galaxy>` is absent.
  `XMLPlanetLoader.java:1139-1141`. FOR: public API: planetDefs.xml schema read by pack authors.
- **INV-XML-03 [V][SYS]** Ore `<oreGen>` with neither `pressure` nor `temp` is skipped.
  `XMLOreLoader.java:303-307`. FOR: public API: planetDefs.xml schema read by pack authors.
- **INV-XML-04 [V][SYS]** Ore heights are clamped into legal world range at parse time, so no
  out-of-range `OreEntry` reaches world-gen. `XMLOreLoader.java:73,89,105,121`. FOR: public API: planetDefs.xml schema read by pack authors.
- **INV-XML-05 [A][BEH]** Asteroid dimensions actually contain asteroids after config load
  (end-to-end). `test/server/AsteroidDimensionContainsAsteroidsTest.java`. Pinned by `AsteroidDimensionContainsAsteroidsTest#asteroidDimGeneratesFillBlocks`.
- **INV-XML-06 [V][SYS]** `XMLAsteroidLoader.getStack` accepts both `;`-delimited (new) and
  space-delimited (legacy) `name;meta` forms. `XMLAsteroidLoader.java:31-56`. FOR: public API: asteroid definition forms read by pack authors.
- **INV-XML-07 [A][BEH]** `Asteroid.getHarvest` output is deterministic for a fixed seed
  (shared static RNG is reseeded each call). `Asteroid.java:64` (not directly unit-tested;
  covered indirectly by mission tests).

## Failure modes & edge cases

- `XMLAsteroidLoader.loadPropertyFile` does `doc.getFirstChild().getFirstChild()`
  with no null guard (unlike `XMLOreLoader`/`XMLPlanetLoader` which check the root) —
  NPE on an empty/rootless asteroid file. `XMLAsteroidLoader.java:82`.
- `XMLAsteroidLoader.getStack` declares `size` but never parses the third
  (`size`) field, so item/block stacks from `name;meta;size` always get count 1
  (`XMLPlanetLoader.getStack` *does* parse size). `XMLAsteroidLoader.java:34-53` vs
  `XMLPlanetLoader.java:397-421`.
- Shared static `Asteroid.rand` is not thread-safe; concurrent `getHarvest` calls could
  interleave seeds (single-threaded in practice).

## Integration seams

Registry name `stellurgy:asteroidChip` referenced from `Stellurgy.java`
(C3). No packets/events. Reads `DimensionManager` (dimension-planets).

## Config surface

The asteroid loader feeds the server galaxy's `getAsteroidTypes()` (not config);
`asteroidMiningTimeMult` consumed downstream (mission). No per-mechanic disable flag —
these run unconditionally at boot.

## Test coverage

`XMLPlanetLoaderTest` (unit+integration), `PlanetDefsFaultToleranceTest`,
`AsteroidDimensionContainsAsteroidsTest`, and mission tests exercising asteroid harvest.

## Open questions

- Determinism of asteroid harvest under the shared static RNG (INV-XML-07) is assumed,
  not pinned by a dedicated test.
