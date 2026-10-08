---
id: dimension-planets
owns: [dimension/, api/dimension/, client/ServerView.java]
entrypoints: [DimensionManager#createAndLoadDimensions, DimensionManager#saveDimensions, DimensionManager#loadDimensions, DimensionProperties#readFromNBT, DimensionProperties#writeToNBT, DimensionManager#generateRandom]
depends-on: [api-public, util-core, world-gen, space-stations, network-wire, atmosphere-oxygen, satellite]
depended-by: [world-gen, client-render, rocket-entity, space-stations, satellite, atmosphere-oxygen, commands-gameplay, mission]
contracts: [C1, C2, C3, C4, C29]
confidence: high
---

## Purpose

Owns the **planet/star model**: every Stellurgy celestial body is a `DimensionProperties`
(stats, atmosphere, gravity, orbit, biome list, ores, weather, satellites) keyed by Minecraft
dimension id. `DimensionManager` is the galaxy registry — ONE PER SIDE AND LIFETIME (MECH-DIM-23):
the running server's, and each client connection's — that creates, registers, ticks, and persists
all bodies plus the stat/version bookkeeping. This subsystem is the single
source of truth other subsystems query for "what is dimension N like".

## Responsibility boundary

**Owns**: the `DimensionProperties` value type and its NBT round-trip; the `DimensionManager`
registry, planet/star generation, `temp.dat` + `planetDefs.xml` save/load, dimension
registration with Forge, the satellite-tick pump, and the `hasReachedMoon`/`hasReachedWarp`
progression flags. Owns three Forge `DimensionType`s (planet/space/asteroid). Owns the
`Temps`/`AtmosphereTypes`/`PlanetIcons` enums and the `PropLookup` reflection helper used by
the `/planet` commands.

**Does NOT own**: `StellarBody` and the solar/orbital math (→ api-public
`api/dimension/solar`, `util/AstronomicalBodyHelper`); the XML parse/serialize
(→ util-core `XMLPlanetLoader`); the `WorldProvider*`/terrain generation (→ world-gen); the
terraforming progress (`TerraformingRecord`, a `WorldSavedData` of the planet's own world, and the
`TerraformingHelper` runtime part) (→ util-core terraforming — this subsystem does not persist it); `SatelliteBase`
implementations (→ satellite); space-station objects (→ space-stations); the wire encoding of
`PacketDimInfo`/`PacketSatellite*` (→ network-wire, only invoked here).

## Key types

| type | role |
|------|------|
| `DimensionManager` | One side's galaxy (`IGalaxy`); dim/star registry, gen, tick, persistence. Not a singleton: `getInstance()` answers the caller's side (MECH-DIM-23) |
| `TerraformingRecord` | `WorldSavedData` of one planet world: terraformed / biome-changed chunks, protecting blocks (util-core terraforming) |
| `DimensionProperties` | Per-body value type; all planet stats + NBT + satellite bag |
| `DimensionProperties.Temps` | Kelvin band enum (TOOHOT…SNOWBALL); `getTempFromValue` |
| `DimensionProperties.AtmosphereTypes` | Pressure band enum (SUPERHIGHPRESSURE…NONE) |
| `DimensionProperties.PlanetIcons` | Enum of orbit-view icon `ResourceLocation`s (+ LEO variant) |
| `DimensionProperties.PropLookup` | Reflection get/set of non-static/non-final fields, for commands |
| `watersourcelocked` | Tiny holder: a `HashedBlockPosition` + countdown timer (terraforming water lock) |

## Mechanics

- **MECH-DIM-01 — Body persistence round-trip.** `writeToNBT` serialises every planet stat;
  `readFromNBT` restores it, `createFromNBT` wraps construct+read. Colors are float lists;
  ints/doubles/booleans by key. `DimensionProperties.java:1892`, `:1483`, `:387`.
- **MECH-DIM-02 — Galaxy save.** `saveDimensions` writes `starSystems`, `nextSatelliteId`,
  per-dim `dimList` (each = `writeToNBT`), `stat`, `spaceObjects`
  to a gzipped `temp.dat` via **tmp-file + atomic move** (fsync, `ATOMIC_MOVE` with non-atomic
  fallback), and the human-editable `planetDefs.xml` the same way. `DimensionManager.java:590`.
- **MECH-DIM-03 — Galaxy load.** `loadDimensions` reads `temp.dat`: stars, `nextSatelliteId`,
  `stat`, each dim via `createFromNBT`, `spaceObjects`; empty/EOF file → empty map (silent).
  `DimensionManager.java:1059`.
- **MECH-DIM-04 — World bootstrap.** `createAndLoadDimensions` locates `planetDefs.xml`
  (world dir first, else `config/` copy), parses via `XMLPlanetLoader`, then either loads
  `temp.dat` or, if none, generates the default galaxy (Sol + Moon + random planets + 6 extra
  named stars). Merges XML ore/star overrides onto loaded dims. `DimensionManager.java:795`.
- **MECH-DIM-05 — Random planet generation.** `generateRandom(…)` clamps atmosphere/distance/
  gravity around a base ± factor, walks orbital distance to avoid collisions (min gap 4),
  derives temperature via `AstronomicalBodyHelper`, seeds sky color/rings/rotation, adds viable
  biomes, `initDefaultAttributes`, registers. A gas giant skips Forge dim registration; what
  can be harvested from it is read off its air (`getHarvestableGases`, CON-C21-02), not rolled. `DimensionManager.java:276`, `:353`.
- **MECH-DIM-06 — Dimension registration.** `registerDim`→`registerDimNoUpdate` inserts into
  `dimensionList`; registers with Forge **only** when body `hasSurface()` (not gas giants/stars)
  and not already registered, choosing `AsteroidDimensionType` vs `PlanetDimensionType`;
  `registerDim` additionally broadcasts `PacketDimInfo`. `DimensionManager.java:443`, `:429`.
  It REFUSES (answers `false`) an id `dimensionList` already holds (`:446`). On the fresh-world
  planetDefs.xml load a refused body is bound to no star, skipped by the merge loop, and logged at
  WARN naming the id and both bodies (`DimensionManager.java:940`, `:1117`) — C29 DIMID-4. [V]
  A refused body is never bound to its star (the star's id-keyed map would put it over the holder).
  A refused NESTED body is also detached from the parent the XML parse linked it to
  (`DimensionManager#detachFromParseParent`, after the registration loop): its id leaves the parent's
  moon set unless the body that holds that id is itself that parent's moon — the moon set's own
  invariant (`PlanetDefsAuthoringTest#aRefusedMoonIsNoLongerItsParentsChild`). [T]
- **MECH-DIM-07 — Effective-dim resolution.** `getEffectiveDimId(world/id, pos)` maps the space
  dim id to the station's parent body (via `SpaceObjectManager`) or the default-space props,
  else the normal props. Star ids (`≥STAR_ID_OFFSET`) yield a throwaway props naming the star.
  `DimensionManager.java:116`, `:527`.
- **MECH-DIM-08 — Planet hierarchy.** `setParentPlanet`/`addChildPlanet`/`removeChild` maintain
  a bidirectional parent↔`childPlanets` graph and inherit the star; `isMoon` = has a valid
  non-warp parent; moons recurse to a parent's solar distance/theta. `DimensionProperties.java:756`,
  `:791`, `:734`.
- **MECH-DIM-09 — The planet's air is a COMPOSITION, and the label is read off it** .
  One field, `air` (an `AirState`), is the authority; `getAtmosphereDensity` (its
  total, ROUNDED to centi-atm) and `hasOxygen()` (any free O2) are readouts, nothing stores either
  beside it. `getAtmosphere()`: vacuum below the NONE band; then the planet's own rungs —
  ≥900 K, TOOHOT, SUPERHIGH/HIGH pressure — each in its O2/NoO2 variant by `hasOxygen()`; then no O2
  ⇒ `NOO2`; else `AirState.oxygenRung(AIR)`, the SAME band a zone is judged by (`lowO2` / `air` /
  `highO2`). Consequences: an oxygen world at density 143..200 reads `highO2`, at 76
  `lowO2`. `DimensionProperties.java:1120-1135`, `:1215`; `AirState.java:549`. `[T]`
  `ATraceOfOxygenOutdoorsReadsAsTooLittleTest` (the `lowO2` side). The `highO2` side is unpinned (a pin
  has to set the band in the configuration).
- **MECH-DIM-10 — The air is realized ONCE, then changed only by exchanges.**
  `realizeAtmosphere(oxygenated, statedDensity)` is the one creation door — called LAST, after
  mass, radius, temperature and giant-ness, by every creator (`PlanetRealizer:301`, the XML loader
  `:1637`, Earth `DimensionManager:176`, Luna/space/stations with density 0); a positive density
  routes through `BodyAtmosphere.derive` (C21-11), zero is vacuum and needs no bulk. In play:
  `addToAtmosphere(portion)` adds real gas (the terraformer's step-up), and
  `setAtmosphereDensity(int)` rescales the whole mix proportionally — REFUSING
  (`IllegalStateException`) to thicken an empty mix, since scaling cannot create a gas. Both end in
  `atmosphereChanged()`: temperature recompute, `load_terraforming_helper(true)`, `PacketDimInfo`.
  `setAtmosphereDensityDirect` is GONE. An AUTHORED body enters by the second door,
  `authorAtmosphere(stated)` — the planet file's `<atmosphere>` gases, or a `copyOf` resolved by the
  loader's second pass (`XMLPlanetLoader#resolveAtmosphereCopies`) — which consults nothing about the
  body (CON-C21-11: authoring overrides derivation). `DimensionProperties.java:1145-1200`.
- **MECH-DIM-11 — Orbit tick.** `DimensionManager.tickDimensions` calls `prop.tick()` per loaded
  dim (server) and pushes `PacketSatellitesUpdate` every 100 ticks; `prop.tick()` ticks
  satellites, `updateOrbit()` (θ from orbital dist + gravity, retrograde sign), expires
  water-source locks, and drains up to 5 terraforming-decoration blocks. Client uses
  `tickDimensionsClient` (orbit only). `DimensionManager.java:205`, `DimensionProperties.java:1056`, `:1115`.
- **MECH-DIM-12 — Satellite bag.** `addSatellite`/`removeSatellite`/`getSatellite` keep
  `satellites` + `tickingSatellites` maps; server adds broadcast `PacketSatellite`. Persisted
  under `satallites` [sic]. `DimensionProperties.java:957`, `:1021`, `:1773`.
- **MECH-DIM-13 — Viable-biome selection.** `getViableBiomes(notTerraforming)` picks biomes by
  temperature band + atmosphere, honoring `BiomeDictionary` types, the Stellurgy blacklist, the
  `allowNonStellurgyBiomesInTerraforming` gate, and a `maxBiomesPerPlanet` cap; may random-pick a single
  biome. `DimensionProperties.java:1176`. Biome lists are stored as Forge `BiomeEntry` weights
  (`allowedBiomes`, `craterBiomeWeights`). `DimensionProperties.java:1266`, `:1286`.
- **MECH-DIM-14 — Feature-frequency clamps.** Crater/geode/volcano multipliers are clamped to
  `[0.01, 10]` on every set and on NBT load. `DimensionProperties.java:81`, `:2228`, `:1673`.
- **MECH-DIM-15 — Custom weather.** Rain/thunder start+prolongation lengths and −1/0/1 markers
  plus `acidicRain`; `updateCustomWorldInfo` flags a body as non-default; markers clamped to
  `[-1,1]` and lengths sanity-clamped to defaults on load. `DimensionProperties.java:2316`, `:1680`.
- **MECH-DIM-16 — Dimension deletion.** `deleteDimension` detaches from star/parent, recursively
  deletes children (broadcasting `PacketDimInfo(child,null)`), unloads+unregisters the Forge dim
  (native only), removes from list, and deletes the `DIM<n>` world folder.
  `DimensionManager.java:460`. A CLIENT receiving the deletion runs `forgetDimension` instead — drops
  the body and its moons from the connection's galaxy and touches no world, registration or file
  (`DimensionManager.java:433`, `PacketDimInfo.executeClient`). `[V]` A REMOTE client (`ServerView.current().remote()`) runs `withdrawDimension` instead, which also
  unregisters the Forge dimension of the body and its moons under `unregisterAllDimensions`' own
  condition — otherwise the registration outlived the body and a later body on the id kept the old
  dimension type. Single-player is untouched: the integrated server owns that registration.
  `WorldCommandClientGroupTest#aDeletedPlanetLeavesNoForgeDimensionOnARemoteClient` (the harness
  client is always remote, so the single-player branch is unpinned). `[T]`
- **MECH-DIM-17 — Beacon registry.** `addBeaconLocation`/`removeBeaconLocation` maintain
  `beaconLocations` and toggle `knownPlanets`; on removal the planet stays known if it is in
  the manager's `getInitiallyKnownPlanets()` (filled by the planet load; was a config field). Broadcasts `PacketDimInfo` (server). `DimensionProperties.java:536`, `:550`.
- **MECH-DIM-18 — Command reflection surface.** `PropLookup` exposes get/set `MethodHandle`s over
  non-static non-final fields (set restricted to primitives/Strings/arrays), driving `/planet`
  get/set commands. `DimensionProperties.java:2539`.

- **MECH-DIM-19 — Bulk properties: mass and radius are PRIMARY, gravity is derived.** `setBulk(m, r)`
  states a body's mass (Earth masses) and radius (Earth radii) and writes
  `gravitationalMultiplier = derivedGravity(m, r) = clamp(M/R², 0.05, MAX_GRAVITY/100)` —
  `DimensionProperties.java` `setBulk`/`derivedGravity`. `[T]` `PlanetDerivationTest`.
  **The derivation only fills in a gravity nobody stated**: `gravityAuthored` is set by the
  `<gravitationalMultiplier>` XML element and by `setGravitationalMultiplier`, and while it is set
  `setBulk` leaves the gravity alone. That is what makes the whole mechanic additive — an authored
  planet that gains a mass keeps exactly the gravity its author wrote.
  **Who calls it.** The writers are
  `PlanetRealizer.realize` (procedural bodies), `PlanetGenerateCommand` (admin) and `XMLPlanetLoader`
  (reading a `<radius>` that must already be in the file). **The two bodies Stellurgy ships built itself —
  the overworld-as-Earth and Luna — pass through none of them**, so both are seeded where the bodies are
  BUILT (`DimensionManager`), with their gravity marked authored: Earth `(1.0, 1.0)` by definition, Luna
  `(0.0123, 0.2727)` measured. A body left at `BULK_UNSET = 0` would make `DescentShell.radiusAround`
  take its no-radius branch (the flat 512-block proximity sphere meant for belts, 1/50 of an Earth) and
  put `radiusBlocks = 0` on the render channel, which pins a planet at `ApparentSize.MIN_HALF_SIZE` —
  1.9° of sky at any range. Their orbits are stated: Earth at one AU (`DimensionManager.java:142-143`), Luna at the Moon's real distance (`:1083-1087`).
  **There is no teardown of Earth's state:**
  each server lifetime builds a new manager, seeded by `seedEarthDefaults` in the constructor
  (`DimensionManager.java:99`). `[T]` `ServerLifetimeStartsFreshTest`.
  A repair exists for saved worlds: the writer emits bulk only
  for a body that has it (INV-DIM-19), so a world saved in that state records an Earth with no
  radius. `repairOverworldBulk` gives dim 0 the unit bulk when its SAVED state holds none, and WARNs
  (`DimensionManager.java:128`, called at the end of `createAndLoadDimensions`). **The planet-file
  route does not reach it**: every `<planet>` must state `<mass>` and
  `<radius>` — airless, oxygen and `DIMID="0"` alike — or `readPlanetFromNode` throws and the
  per-planet guard skips it with its nested moons (`XMLPlanetLoader.java:1621`). `[T]`
  `ASizelessBodyInThePlanetFileIsRefusedTest`.
- **MECH-DIM-22 — A star's RETINUE SIZE is the only thing that says a system has unauthored content,
  and it crosses the save boundary.** `StellarBody.setMaxRetinueBodies` bounds what
  `UniverseRegistry.withDerivedRetinue` may derive for an authored system; at zero it returns the
  authored list untouched. The stock galaxy authors **two** planets (Earth, Luna) and states the rest
  as counts on seven hard-coded stars — Sol 10, Wolf 12 5, Epsilon ire 7, Proxima Centaurs 3, Magnis
  Vulpes 2, Ma-Roo 6, Alykitt 4 — so 37 of 39 bodies exist ONLY because of this number.
  It is persisted in `planetDefs.xml` as `numPlanets` (the `numGasGiants` attribute is a legacy
  sibling; both readers sum the pair and neither asks for one alone, so the total is written into the
  first) and carried onto the star on EVERY load, not only on the first. `[T]`
  `StarKeepsItsPlanetsAcrossARestartTest`, red-witnessed against the defect.
  The writer stores the real count, never a literal `"0"`: a stored zero is indistinguishable from a
  real one and every reloaded galaxy would come back with only its authored bodies.
  **Ratified as C15 ADDR-24 (rulings 2026-10-03)**: the count IS the pack's declaration, a
  star with neither bodies nor a count holds its star alone (not even the outer belt), and the stock
  counts above stay. `[T]` `PlanetDefsAuthoringTest.anAuthoredStarHoldsWhatItsPackDeclaresAndNothingElse`
  (a count of 3 derives 2 planets, 1 giant, 2 moons, 2 belts in that file's world).
- **MECH-DIM-20 — Tidal locking is an explicit flag, not a rotational period.**
  `isTidallyLocked()`/`setTidallyLocked`. `WorldProviderPlanet.calculateCelestialAngle` returns a
  constant for a locked world, so it has **no day/night cycle at all** rather than a long day.
  `rotationalPeriod = 0` deliberately does NOT express this: `StellurgyDimensionWorldInfo` maps `<= 0` back to
  a full day, so zero silently means "an ordinary planet". `[V]`
  **Bounded on purpose:** one sky serves a whole dimension, so the permanently-dark hemisphere and the
  temperate terminator strip — which are properties of WHERE you stand — are not expressible through a
  celestial angle and are left to the terrain/biome layer.
- **MECH-DIM-21 — Metallicity scales the METAL fraction of a world's ore palette.**
  `getOreGenProperties` falls back to the shared climate table
  (`OreGenProperties.getOresForPressure(atmosphere, temperature)`) as before, then — when
  `metallicity != 1` — returns `climate.withMetalsScaled(metallicity)`, a **copy** (the climate table
  is one shared static object per `(pressure, temperature)` cell, so scaling it in place would give one
  star's metal poverty to every world in that climate). Metallic entries are classified through the ore
  dictionary; coal/redstone/lapis/diamond/emerald/quartz and anything unrecognised pass through
  untouched. Climate answers *which kinds* of deposit; the star answers *how much metal is in them*.
  `[V]` `DimensionProperties.java` `getOreGenProperties`, `OreGenProperties.withMetalsScaled`.
- **MECH-DIM-23 — One galaxy per side and per lifetime .** The SERVER's galaxy and
  station manager live in its `ServerState` (with the space clock, the hyperspace world and the
  space-slot pool), built by `Stellurgy.beginServerLifetime` at `serverAboutToStart` and released by
  `endServerLifetime` at `serverStopped`, which first withdraws the Forge dimension registrations —
  planets, space slots and hyperspace alike; the slot and hyperspace `DimensionType`s are the only
  part registered once per process, in pre-init
  (`Stellurgy.java:331`, `:339`, `:1499`, `:1633`); nothing is cleared for reuse. A CLIENT's copy
  belongs to the client's VIEW of the server it is connected to: `ServerView`, held by the
  `ClientProxy` field `serverView`, built on `ClientConnectedToServerEvent` and released on the game
  thread when the client unloads a world with that connection already closed — the moment it stops
  showing that server; a REMOTE server's Forge dimension registrations are withdrawn there
  (`ClientProxy.java:333`, `:348`, `:368`, `:384`) `[V]`.
  `getInstance()` / `getSpaceManager()` decide by the CALLER: `CommonProxy` answers the server's;
  `ClientProxy` answers the server's on a thread of Forge's server group and the connection's
  otherwise (`CommonProxy.java:146`, `ClientProxy.java:593`); with none to answer they THROW, never
  hand back an empty galaxy. So in single player the integrated server and the client do not
  share one object: the client sees only what the server syncs, exactly as on a dedicated server,
  and the login sync now also sends the asteroid kinds and the globally known planets
  (`PacketAsteroidInfo`, `PacketKnownPlanets`, `PlanetEventHandler.java:437-441`). `[V]`;
  `[T]` `ServerLifetimeStartsFreshTest` (server side), `PlanetWorldOnTheClientGroupTest`
  (a client that left holds no galaxy).

## State & persistence

All keys below live in the gzipped `temp.dat` (`DimensionManager.workingPath`+`tempFile`), except
`starSystems`/`spaceObjects` whose bodies are owned by other subsystems. **C1 / C3.**

**DimensionManager top-level** (`saveDimensions`/`loadDimensions`): `starSystems` (compound of
`StellarBody`), `nextSatelliteId` (long, instance field), `dimList` (compound of per-dim
NBT), `stat`{`hasReachedMoon`,`hasReachedWarp`} (instance booleans), `spaceObjects` (SpaceObjectManager),
`prevVersion` (string; nothing reads it, the upgrade path is commented out). Registry ids: `DimensionType` `planet`(2)/`space`(3)/`asteroid`(4),
`GASGIANT_DIMID_OFFSET`=0x100. **C3**: `DimensionManager.java:55`.

**Local knowledge is a property of the BODY** `[V]`. `DimensionProperties.locallyKnownPlanets` is the
set of planets a tier-1 launch pad standing on THAT body may be aimed at, beyond the global floor.
`discoverPlanet(int)` teaches it, `isPlanetKnownHere(int)` asks it, and it is persisted as an int
array under `locallyKnownPlanets` only when non-empty. `readFromNBT` CLEARS before reading: these
objects are reused across loads, and a merge would make a body remember what a previous save taught
it (`DimensionProperties.java`, pinned by `LocalKnowledgeBelongsToABodyTest`).

**The observatory is the writer, and WHERE it stands is what it teaches** `[V]`. A survey batch
reports every body it actually made out (`TelescopeScan.characterise` takes an optional `IntConsumer`
and fires it only for a body whose `dimId` is real), and `TileObservatory` teaches those to the
properties of its own world, syncing through `PacketDimInfo` exactly as a beacon does. The coupling
is deliberate and pinned: a look that only REGISTERED a system, one the dust obscured, and one the
operator set to positions-only all teach nothing — the aperture and the operator's own choice now
bound what tier-1 can be aimed at, not just what a crystal holds
(`TelescopeConeSurveyTest`, three cases).

**The gate is local-OR-global, and the global half is a FLOOR** `[V]`.
`EntityRocket.isPlanetKnown` answers `true` when `planetsMustBeDiscovered` is off, then when
`DimensionManager.isPlanetKnown` holds (what the pack authored as `<isKnown>`, plus dim 0), and only
then asks the properties of the world the rocket is standing in. So a pack authors exactly as it did,
a body adds to what it was given, and a neighbouring body learns nothing from it - a pad on a moon
offers a different list than the pad on the planet below. The shape is `SpaceStationObject`'s
(`:860`), generalised from stations to every body.

**DimensionProperties per-dim keys**: listed in `C1-nbt-persistence` (dimension-planets). Behaviour that is not obvious from the list:

- `air` is the planet's composition (`AirState.writeToNBT`: `gases` by substance name + `airK`); the density and the oxygen flag are readouts of it.
- `avgTemperature`: what is persisted is what is read. The field is private, `getAverageTemp` is a pure read, and the recompute happens in `recalculateTemperature()` off `setAtmosphereDensity` — the one input that changes while a world is in play. It is never reassigned on an accessor call, so the restored value survives the first read after a load.
- `orbitalDist` is a `long` (100 km units); `getLong` also reads an int tag. `originalAtmosphereDensity` is the total at realization, which the climate ore table and rivers read.
- `isNative` defaults to **true** when absent. `ring*` colours are written only if `hasRings`. `peakInsolationMultiplier*` are persisted but recomputed on read.
- `mass` / `radius` (Earth units) are **written only when stated** — a planet that never declared one writes no key, so existing catalogues round-trip byte-identically. `gravityAuthored` is written only when true (the bit that keeps an XML-stated `gravitationalMultiplier` an OVERRIDE, MECH-DIM-19); `tidallyLocked` only when true (MECH-DIM-20); `metallicity` only when ≠ 1.0 (the star's metal content, scaling the metal fraction of this world's ore, MECH-DIM-21).
- Biomes: `biomeNames` + `weights` (registry names) are the authoritative list; `biomes` + `weights` (integer ids) are a read-only legacy path; `craterBiomes` + `craterWeights` are still stored by integer id.
- `sealevel` is clamped 0-255; the crater/geode/volcano frequency multipliers are clamped on load; the key is `canGenerateVolcanos` (no 'e'); `satallites` is the satellite bag (spelling is contractual); `target_sea_level` is dead (read and write both commented out); `beaconLocations` goes through `writeTechnicalNBT` and sets `knownPlanets` on read.

## Invariants

- **INV-DIM-01 [V][BEH]** Default props are stable: name `Temp`, gravity 1.0, seaLevel 63, Earth-like air
  (`AirState.earthLike()`), isNative, no rings, not a gas giant, orbital distance one AU
  (`DISTANCE_UNITS_PER_AU`) — `DimensionProperties.java:298-336,544-585`.
- **INV-DIM-02 [V][SYS]** Core identity (id, name, starId, gravity, dist, period, atmosphere) survives
  the NBT round-trip (`DimensionProperties.java:1880-2090` reads what `:2258-2420` writes). No test
  round-trips a populated `DimensionProperties`. FOR: save format: a dimension survives a world reload.
- **INV-DIM-03 [V][SYS]** Weather config (start/prolongation lengths, markers, acidicRain) survives the
  round-trip (`DimensionProperties.java:2151-2152` read, `:2473` write). FOR: save format: a dimension survives a world reload.
- **INV-DIM-04 [V][SYS]** Generation flags + crater/volcano multipliers survive the round-trip
  (`DimensionProperties.java:1880-2090` read, `:2258-2420` write). That `getGeodeMultiplier` returns the
  geode (not volcano) field is `[V]` — `DimensionProperties.java:2781-2783`. FOR: save format: a dimension survives a world reload.
- **INV-DIM-05 [V][SYS]** Ring angle/color and sky/fog/sunrise colors survive the round-trip
  (`DimensionProperties.java:1891-1916,2111` read, `:2275-2298` write). FOR: save format: a dimension survives a world reload.
- **INV-DIM-06 [T][SYS]** `realizeAtmosphere` does not corrupt id or hierarchy. `DimensionPropertiesTest#realizingTheAtmosphereDoesNotCorruptIdOrHierarchy`. FOR: save format: a dimension survives a world reload.
- **INV-DIM-07 [V][BEH]** Parent↔child links are bidirectional (`DimensionProperties.java:1313-1320`,
  `addChildPlanet` sets both ends); a moon inherits its parent's solar distance (`:1034-1040`).
- **INV-DIM-08 [V][SYS]** Empty-NBT round-trip yields post-constructor defaults (no NPE / partial state):
  `readFromNBT` guards every optional key with `hasKey` (`DimensionProperties.java:1880-2090`). FOR: save format: a dimension survives a world reload.
- **INV-DIM-09 [V][BEH]** A dimension is registered with Forge **iff** `hasSurface()` (gas giants and
  stars never get a Forge dim). `DimensionManager.java:431`, `:493`.
- **INV-DIM-10 [V]** `getDimensionProperties` never returns null: unknown → this manager's
  `getOverworldProperties()`, space dim / `Integer.MIN_VALUE` → its `getDefaultSpaceProperties()`.
  `DimensionManager.java:550`.
- **INV-DIM-11 [V]** `isNative` absent on load ⇒ treated as native (`true`), preventing breakage of
  pre-`isNative` worlds. `DimensionProperties.java:1659`.
- **INV-DIM-12 [V]** `biomeNames` is authoritative: if present, legacy `biomes` int-ids are ignored;
  writer emits only `biomeNames` for native surface dims. `DimensionProperties.java:1524`, `:1929`.
- **INV-DIM-13 [V]** Feature-frequency multipliers are always in `[0.01,10]` (clamped on set and on
  load). `DimensionProperties.java:81`, `:1673`.
- **INV-DIM-14 [V][SYS]** `temp.dat`/`planetDefs.xml` writes are crash-atomic: fsync'd tmp file then
  `ATOMIC_MOVE` (fallback non-atomic). `DimensionManager.java:655`, `:687`. FOR: save format: a crash never loses the galaxy.
- **INV-DIM-15 [V][SYS]** `saveDimensions` refuses to write when there are no stars or no dims (throws),
  guarding against clobbering a good file with an empty galaxy. `DimensionManager.java:592`. FOR: save format: a crash never loses the galaxy.
- **INV-DIM-16 [T][BEH]** A registered planet uses `WorldProviderPlanet`:
  `PlanetDimensionLoadTest#providerClassIsWorldProviderPlanet`. The "registered planets are preloaded / the overworld reports
  loaded" half is `[A]` (no test pins it); the class also pins
  `saveFolderResolvesToExpectedPath` (`:50`).
- **INV-DIM-18 [V][BEH]** An authored gravity always wins (`DimensionProperties.java:633-639`): with `gravityAuthored` set, `setBulk` may change
  `mass`/`radius` but never `gravitationalMultiplier`. This is the compatibility guarantee of MECH-DIM-19
  — no planet in an existing catalogue changes when bulk properties arrive.
- **INV-DIM-19 [V][SYS]** `mass`, `radius`, `gravityAuthored`, `tidallyLocked` and `metallicity` are written
  **only when non-default**, so a planet that states none of them produces byte-identical NBT to the
  one it produced before these keys existed (the `terrainSource` / `originalAtmosphereDensity` idiom;
  `DimensionProperties.java:2402-2420`). FOR: maintainer ruling 2026-09-30 (0.1.0 clean break): no marker, byte-identical NBT when nothing is stated.
- **INV-DIM-20 [V]** A per-planet ore table is never the shared climate object: `getOreGenProperties`
  returns either the planet's own `oreProperties`, the shared table unchanged (metallicity 1), or a
  **copy** with metals scaled. Nothing mutates a table another world is reading.
- **INV-DIM-17 [A][BEH]** `orbitTheta` is deterministic for a fixed world time (celestial angle stable) —
  asserted at world level by `celestialAngleProgressesAcrossDifferentWorldTimes` (`PlanetDimensionLoadTest.java:58-75`, a soft assertion that three world times do not collapse to one angle — not a determinism check; no test pins stability across the same world time).

## Failure modes & edge cases

- `updateOrbit` (non-moon) dereferences `getStar().getSize()`; a null star (missing `starId`) NPEs
  inside the tick loop. `DimensionProperties.java:1120`.
- `getViableBiomes` seeds `new Random(System.nanoTime())` and can short-circuit to a single random
  biome — biome sets are **non-deterministic** across regenerations. `DimensionProperties.java:1183`.
- `loadDimensions` swallows `EOFException` and returns an empty galaxy silently (deliberate, to
  dodge early-JEI loads) — a truncated `temp.dat` looks like "no planets". `DimensionManager.java:1006`.
- `getDimensionProperties` for a star id allocates a fresh `DimensionProperties` every call; this is
  on the `getEffectiveDimId` path. `DimensionManager.java:534`.
- `deleteDimension` refuses while the world is still loaded (logs + returns). `DimensionManager.java:462`.

## Integration seams

- **Packets (C2, network-wire)** broadcast from here: `PacketDimInfo` (register/mutate/delete/beacon
  — `DimensionManager.java:414`, `DimensionProperties.java:542,558,810`), `PacketSatellite`
  (`DimensionProperties.java:972`), `PacketSatellitesUpdate` (`DimensionManager.java:213`). All guarded
  by `!world.isRemote`/`!isRemote` except `setAtmosphereDensity`.
- **Forge (C3)**: registers `DimensionType`s and forwards to `net.minecraftforge.common.DimensionManager`
  for register/unregister/unload and save-dir resolution. `DimensionManager.java:55`, `:431`.
- **Terraforming (util-core terraforming)**: `TerraformingRecord.of(world)` (persisted with the planet
  world) and `TerraformingHelper.of(world)` (a `WorldRuntime` part); this subsystem builds the helper
  in `load_terraforming_helper` and pumps decoration in `tick()`. `DimensionProperties.java:329`.
- **Consumers**: `SpaceObjectManager` (effective-dim + space objects), `AstronomicalBodyHelper`
  (temperature/orbit), `XMLPlanetLoader` (XML ↔ props coupling), `SatelliteRegistry` (satellite NBT).

## Config surface

Config: see `C4-config-surface`. Galaxy regeneration is driven by the presence/`resetFromXml` of `planetDefs.xml`, not a boolean flag.
`MoonId`, `initiallyKnownPlanets` and `asteroidTypes` are not config: they are per-galaxy state
(`getMoonId`, `getInitiallyKnownPlanets`, `getAsteroidTypes`, `DimensionManager.java:142-152`), and
`minDimension` is a config field read once at pre-init and handed to each new manager.

## Test coverage

INV-DIM-06 → `test/integration/DimensionPropertiesTest.java`. INV-DIM-02..05, 07, 08 and 18, 19 are code-verified `[V]`: no test round-trips a populated `DimensionProperties`. INV-DIM-16 → `test/server/PlanetDimensionLoadTest.java` (provider
class only). INV-DIM-17 → same (celestial angle, soft).
Additional oracles: `XMLPlanetLoaderTest`, `PacketSerializationTest`, `PlanetaryTravelHelperTest`,
`AsteroidDimensionContainsAsteroidsTest`, `TerraformerMultiblockTest`, `BeaconEnableCycleTest`.
INV-DIM-09..15 are code-verified, not directly pinned by a unit test.

## Open questions

- Does any live code still consume `prevBuild`/`prevVersion`? The upgrade path is commented out..
- Should `craterBiomes` be migrated to registry-name storage like `biomeNames`?.
- `getViableBiomes` non-determinism: intended flavor, or a reproducibility hazard for terraforming?
